package com.flower.spirit.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DouyinPythonRuntimeContractTest {

	@Test
	void f2ImportsUseAnIsolatedTemporaryWorkingDirectory() throws Exception {
		String script = Files.readString(Path.of("src", "main", "docker", "buildx", "script", "douyin.py"),
				StandardCharsets.UTF_8);
		int isolation = script.indexOf("tempfile.TemporaryDirectory(prefix=\"stream-vault-f2-\")");
		int firstF2Import = script.indexOf("from f2.apps.douyin.handler import DouyinHandler");

		assertThat(isolation).isGreaterThanOrEqualTo(0);
		assertThat(firstF2Import).isGreaterThan(isolation);
		assertThat(script).contains("os.chdir(_f2_runtime_directory.name)",
				"finally:\n    os.chdir(_f2_original_working_directory)");
	}
}
