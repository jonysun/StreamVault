package com.flower.spirit.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
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
        return execute(command, timeout, operation, outputLimit, outputLimit);
    }

    public Result execute(List<String> command, Duration timeout, String operation,
            int stdoutLimit, int stderrLimit) throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        BoundedOutput stdout = new BoundedOutput(Math.max(1024, stdoutLimit));
        BoundedOutput stderr = new BoundedOutput(Math.max(1024, stderrLimit));
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
            try (Reader input = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.append(buffer, count);
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
        List<ProcessHandle> descendants = new ArrayList<>(process.toHandle().descendants().toList());
        Collections.reverse(descendants);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        try {
            process.destroy();
            process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS);
            boolean force = process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
            if (force) {
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
            }
            logger.warn("[Process] terminated operation={} descendants={} forcibly={}", operation,
                    descendants.size(), force);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            logger.warn("[Process] terminated operation={} descendants={} forcibly=true", operation,
                    descendants.size());
        }
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

        private synchronized void append(char[] buffer, int count) {
            if (value.length() >= limit) {
                truncated = true;
                return;
            }
            int remaining = limit - value.length();
            value.append(buffer, 0, Math.min(remaining, count));
            if (count > remaining) {
                truncated = true;
            }
        }

        private synchronized String text() {
            return truncated ? value + "\n[output truncated]" : value.toString();
        }
    }
}
