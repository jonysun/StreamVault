package com.flower.spirit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.flower.spirit.config.F2RuntimeVersionLogger.CommandResult;
import com.flower.spirit.config.F2RuntimeVersionLogger.RuntimeVersionStatus;

class F2RuntimeVersionLoggerTest {

	@Test
	void everyDockerfilePinsAndPrintsTheF2Version() throws Exception {
		Path directory = Path.of("src", "main", "docker", "buildx");
		for (String name : List.of("Dockerfile", "Dockerfile.dev", "Dockerfile.jre17", "Dockerfile.multi",
				"Dockerfile.ubuntu")) {
			String dockerfile = Files.readString(directory.resolve(name), StandardCharsets.UTF_8);
			assertThat(dockerfile).as(name)
					.contains("f2==0.0.1.7", "importlib.metadata.version('f2')")
					.doesNotContain("pip install --no-cache-dir f2\n");
		}
	}

	@Test
	void reportsExactVersionWithoutExposingCommandOutput() throws Exception {
		Path python = Files.createTempFile("fake-python-", ".bin");
		Path script = Path.of("/home/app/script/douyin_incremental.py");
		F2RuntimeVersionLogger logger = new F2RuntimeVersionLogger(
				(path, expression) -> new CommandResult(0, "0.0.1.7\n"), List.of(python), script);

		RuntimeVersionStatus status = logger.inspectAndLog();

		assertThat(status.available()).isTrue();
		assertThat(status.version()).isEqualTo("0.0.1.7");
		assertThat(status.pythonPath()).isEqualTo(python.toString());
		assertThat(status.scriptPath()).isEqualTo(script.toString());
		assertThat(status.detail()).isNull();
	}

	@Test
	void missingRuntimeOrMetadataFailureDoesNotAbortStartup() throws Exception {
		Path script = Path.of("/home/app/script/douyin_incremental.py");
		F2RuntimeVersionLogger missing = new F2RuntimeVersionLogger(
				(path, expression) -> new CommandResult(0, "unused"),
				List.of(Path.of("target", "missing-python")), script);
		RuntimeVersionStatus missingStatus = missing.inspectAndLog();

		Path python = Files.createTempFile("fake-python-error-", ".bin");
		F2RuntimeVersionLogger failed = new F2RuntimeVersionLogger(
				(path, expression) -> new CommandResult(1, "metadata unavailable"), List.of(python), script);
		RuntimeVersionStatus failedStatus = failed.inspectAndLog();

		assertThat(missingStatus.available()).isFalse();
		assertThat(missingStatus.detail()).contains("python runtime not found");
		assertThat(failedStatus.available()).isFalse();
		assertThat(failedStatus.detail()).contains("exitCode=1", "metadata unavailable");
	}
}
