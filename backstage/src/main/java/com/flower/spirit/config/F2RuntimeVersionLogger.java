package com.flower.spirit.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flower.spirit.utils.CommandUtil;

@Component
public class F2RuntimeVersionLogger {

	private static final Logger logger = LoggerFactory.getLogger(F2RuntimeVersionLogger.class);
	private static final String VERSION_EXPRESSION =
			"import importlib.metadata; print(importlib.metadata.version('f2'))";
	private static final Path SCRIPT_PATH = Path.of("/home/app/script/douyin_incremental.py");
	private static final List<Path> DEFAULT_PYTHON_PATHS = List.of(
			Path.of("/opt/venv/bin/python3"), Path.of("/usr/local/bin/python3"));

	private final CommandRunner commandRunner;
	private final List<Path> pythonPaths;
	private final Path scriptPath;

	public F2RuntimeVersionLogger() {
		this(F2RuntimeVersionLogger::runCommand, DEFAULT_PYTHON_PATHS, SCRIPT_PATH);
	}

	F2RuntimeVersionLogger(CommandRunner commandRunner, List<Path> pythonPaths, Path scriptPath) {
		this.commandRunner = commandRunner;
		this.pythonPaths = List.copyOf(pythonPaths);
		this.scriptPath = scriptPath;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		inspectAndLog();
	}

	RuntimeVersionStatus inspectAndLog() {
		Path pythonPath = pythonPaths.stream().filter(Files::isRegularFile).findFirst().orElse(null);
		if (pythonPath == null) {
			RuntimeVersionStatus status = RuntimeVersionStatus.unavailable(null, scriptPath,
					"python runtime not found");
			logUnavailable(status);
			return status;
		}
		try {
			CommandResult result = commandRunner.run(pythonPath, VERSION_EXPRESSION);
			String output = sanitize(result.output());
			if (result.exitCode() != 0 || output.isBlank()) {
				RuntimeVersionStatus status = RuntimeVersionStatus.unavailable(pythonPath, scriptPath,
						"metadata lookup exitCode=" + result.exitCode() + " output=" + output);
				logUnavailable(status);
				return status;
			}
			String version = output.lines().findFirst().orElse("").trim();
			RuntimeVersionStatus status = new RuntimeVersionStatus(true, pythonPath.toString(),
					scriptPath.toString(), version, null);
			logger.info("F2 runtime version={} pythonPath={} scriptPath={}", status.version(),
					status.pythonPath(), status.scriptPath());
			return status;
		} catch (Exception error) {
			RuntimeVersionStatus status = RuntimeVersionStatus.unavailable(pythonPath, scriptPath,
					error.getClass().getSimpleName() + ": " + sanitize(error.getMessage()));
			logUnavailable(status);
			return status;
		}
	}

	private void logUnavailable(RuntimeVersionStatus status) {
		logger.warn("F2 runtime unavailable pythonPath={} scriptPath={} detail={}", status.pythonPath(),
				status.scriptPath(), status.detail());
	}

	private String sanitize(String value) {
		String safe = CommandUtil.sanitizeF2Output(value == null ? "" : value).trim();
		return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
	}

	private static CommandResult runCommand(Path pythonPath, String expression) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(pythonPath.toString(), "-c", expression)
				.redirectErrorStream(true).start();
		boolean finished = process.waitFor(10, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			return new CommandResult(124, "version lookup timed out");
		}
		return new CommandResult(process.exitValue(),
				new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	@FunctionalInterface
	interface CommandRunner {
		CommandResult run(Path pythonPath, String expression) throws Exception;
	}

	record CommandResult(int exitCode, String output) {
	}

	record RuntimeVersionStatus(boolean available, String pythonPath, String scriptPath, String version,
			String detail) {
		static RuntimeVersionStatus unavailable(Path pythonPath, Path scriptPath, String detail) {
			return new RuntimeVersionStatus(false, pythonPath == null ? null : pythonPath.toString(),
					scriptPath.toString(), null, detail);
		}
	}
}
