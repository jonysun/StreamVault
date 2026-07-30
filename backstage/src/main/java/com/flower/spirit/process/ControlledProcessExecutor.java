package com.flower.spirit.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Executes external tools without allowing blocked pipes or child processes to retain a worker. */
@Component
public class ControlledProcessExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ControlledProcessExecutor.class);
    private static final long GRACEFUL_STOP_SECONDS = 2;
    private static final long READER_JOIN_MILLIS = 2_000;
    private static final int DEFAULT_OUTPUT_LIMIT = 64 * 1024;

    public Result execute(List<String> command, Duration timeout, String operation) throws IOException, InterruptedException {
        return execute(command, timeout, operation, DEFAULT_OUTPUT_LIMIT);
    }

    public Result execute(List<String> command, Duration timeout, String operation, int outputLimit)
            throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        int boundedLimit = Math.max(1024, outputLimit);
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        BoundedOutput stdout = new BoundedOutput(boundedLimit);
        BoundedOutput stderr = new BoundedOutput(boundedLimit);
        AtomicBoolean readerFailed = new AtomicBoolean();
        Thread stdoutReader = startReader(process.getInputStream(), stdout, readerFailed, operation, "stdout");
        Thread stderrReader = startReader(process.getErrorStream(), stderr, readerFailed, operation, "stderr");
        long startedAt = System.nanoTime();
        boolean completed = false;
        boolean interrupted = false;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminate(process, operation);
            }
        } catch (InterruptedException error) {
            interrupted = true;
            terminate(process, operation);
            throw error;
        } finally {
            if (!completed && process.isAlive()) {
                terminate(process, operation);
            }
            joinReader(stdoutReader, operation, "stdout");
            joinReader(stderrReader, operation, "stderr");
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        int exitCode = completed ? process.exitValue() : -1;
        return new Result(exitCode, !completed, stdout.text(), stderr.text(),
                Duration.ofNanos(System.nanoTime() - startedAt), readerFailed.get());
    }

    private Thread startReader(InputStream stream, BoundedOutput output, AtomicBoolean failed,
            String operation, String streamName) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException error) {
                failed.set(true);
                logger.debug("[Process] output reader failed operation={} stream={} type={}", operation,
                        streamName, error.getClass().getSimpleName());
            }
        }, "process-output-" + streamName + "-" + Integer.toHexString(System.identityHashCode(stream)));
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void joinReader(Thread reader, String operation, String streamName) {
        try {
            reader.join(READER_JOIN_MILLIS);
            if (reader.isAlive()) {
                logger.warn("[Process] output reader did not stop operation={} stream={}", operation, streamName);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logger.warn("[Process] interrupted while joining reader operation={} stream={}", operation, streamName);
        }
    }

    private void terminate(Process process, String operation) {
        if (process == null) {
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                destroyTree(process, operation, true);
            } else {
                destroyTree(process, operation, false);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            destroyTree(process, operation, true);
        }
    }

    private void destroyTree(Process process, String operation, boolean forcibly) {
        List<ProcessHandle> descendants = new ArrayList<>(process.toHandle().descendants().toList());
        Collections.reverse(descendants);
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                if (forcibly) {
                    descendant.destroyForcibly();
                } else {
                    descendant.destroy();
                }
            }
        }
        if (process.isAlive()) {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
        logger.warn("[Process] terminated operation={} descendants={} forcibly={}", operation,
                descendants.size(), forcibly);
    }

    public record Result(int exitCode, boolean timedOut, String stdout, String stderr,
            Duration duration, boolean outputReaderFailed) {
        public boolean successful() {
            return !timedOut && exitCode == 0;
        }

        public String diagnosticOutput() {
            return (stdout + "\n" + stderr).trim();
        }
    }

    private static final class BoundedOutput {
        private final int limit;
        private final StringBuilder value = new StringBuilder();
        private boolean truncated;

        private BoundedOutput(int limit) {
            this.limit = limit;
        }

        private synchronized BoundedOutput append(String text) {
            if (value.length() >= limit) {
                truncated = true;
                return this;
            }
            int remaining = limit - value.length();
            value.append(text, 0, Math.min(remaining, text.length()));
            if (text.length() > remaining) {
                truncated = true;
            }
            return this;
        }

        private synchronized BoundedOutput append(char character) {
            if (value.length() < limit) {
                value.append(character);
            } else {
                truncated = true;
            }
            return this;
        }

        private synchronized String text() {
            return truncated ? value + "\n[output truncated]" : value.toString();
        }
    }
}
