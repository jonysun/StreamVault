package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.flower.spirit.utils.CommandUtil.F2CommandResult;

class DouyinIncrementalFetchServiceTest {

	@Test
	void parsesStructuredEnvelopeAndAlwaysDeletesTemporaryFiles() {
		FakeRunner runner = new FakeRunner();
		runner.resultJson = """
				{"items":[{"aweme_id":"1","uid":"MS4-author","create_time":"100","desc":"中文"}],
				 "newWorkIds":["1"],"outcome":"KNOWN_BOUNDARY","pagesFetched":2,
				 "emptyPages":0,"lastCursor":"99","diagnostics":{"pages":[]}}
				""";
		DouyinIncrementalFetchService service = new DouyinIncrementalFetchService(runner);

		DouyinFetchEnvelope result = service.fetch(request(Set.of("old-1", "作品-2")));

		assertThat(result.outcome()).isEqualTo("KNOWN_BOUNDARY");
		assertThat(result.newWorkIds()).containsExactly("1");
		assertThat(result.items()).singleElement().satisfies(item ->
				assertThat(item.getString("desc")).isEqualTo("中文"));
		assertThat(runner.knownIdsJson).contains("old-1", "作品-2");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void acceptsLegacyArrayResults() {
		FakeRunner runner = new FakeRunner();
		runner.resultJson = """
				[{"aweme_id":"1","uid":"MS4-author","create_time":"100"},
				 {"aweme_id":"1","uid":"MS4-author","create_time":"100"},
				 {"aweme_id":"2","uid":"MS4-author","create_time":"90"}]
				""";

		DouyinFetchEnvelope result = new DouyinIncrementalFetchService(runner).fetch(request(Set.of()));

		assertThat(result.items()).hasSize(3);
		assertThat(result.newWorkIds()).containsExactly("1", "2");
		assertThat(result.outcome()).isEqualTo("LEGACY_ARRAY");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void rejectsNonzeroProcessExitAndPreservesStructuredErrorCode() {
		FakeRunner runner = new FakeRunner();
		runner.commandResult = new F2CommandResult(2,
				"stream-vault-fetch-error={\"errorCode\":\"F2_COOKIE_OR_VERIFY_REQUIRED\",\"message\":\"login required\"}", 25);

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_COOKIE_OR_VERIFY_REQUIRED");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void appendsStructuredDiagnosticsToFetchErrorMessage() {
		FakeRunner runner = new FakeRunner();
		runner.commandResult = new F2CommandResult(3,
				"stream-vault-fetch-error={\"errorCode\":\"UPSTREAM_SCHEMA_ERROR\","
						+ "\"message\":\"Douyin profile returned a nonzero status\","
						+ "\"diagnostics\":{\"profileStatus\":{\"statusCode\":2090,"
						+ "\"statusText\":\"upstream denied\","
						+ "\"topLevelKeys\":[\"status_code\",\"user\"]},"
						+ "\"exceptionType\":\"UpstreamSchemaError\"}}", 25);

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("Douyin profile returned a nonzero status")
				.hasMessageContaining("diagnostics=")
				.hasMessageContaining("\"statusCode\":2090")
				.hasMessageContaining("upstream denied");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void malformedStructuredErrorRemainsAProtocolFailure() {
		FakeRunner runner = new FakeRunner();
		runner.commandResult = new F2CommandResult(3,
				"stream-vault-fetch-error={not-json}", 25);

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_PROTOCOL_ERROR");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void missingStructuredErrorIsAnApplicationProtocolFailure() {
		FakeRunner runner = new FakeRunner();
		runner.commandResult = new F2CommandResult(3, "plain process output", 25);

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_PROTOCOL_ERROR");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void processTimeoutIsClassifiedAsUpstreamTimeout() {
		FakeRunner runner = new FakeRunner();
		runner.commandResult = new F2CommandResult(-1,
				"process timeout after 60 seconds", 60_000);

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_UPSTREAM_TIMEOUT");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void rejectsMalformedResultJsonWithoutLeakingCookieValues() {
		FakeRunner runner = new FakeRunner();
		runner.resultJson = "not-json-with-cookie=sessionid=secret";

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("Malformed Douyin fetch result")
				.hasMessageNotContaining("secret")
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_PROTOCOL_ERROR");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void rejectsStructuredEnvelopeMissingRequiredKeys() {
		FakeRunner runner = new FakeRunner();
		runner.resultJson = "{\"items\":[],\"newWorkIds\":[],\"outcome\":\"NO_MORE\"}";

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("missing required keys")
				.hasMessageContaining("pagesFetched")
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_PROTOCOL_ERROR");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void rejectsStringifiedArraysInStructuredEnvelope() {
		FakeRunner runner = new FakeRunner();
		runner.resultJson = """
				{"items":"[]","newWorkIds":[],"outcome":"NO_MORE","pagesFetched":1,
				 "emptyPages":0,"lastCursor":"0","diagnostics":{}}
				""";

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("items must be an array")
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_PROTOCOL_ERROR");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void rejectsUnknownStructuredOutcome() {
		FakeRunner runner = new FakeRunner();
		runner.resultJson = """
				{"items":[],"newWorkIds":[],"outcome":"MYSTERY","pagesFetched":1,
				 "emptyPages":0,"lastCursor":"0","diagnostics":{}}
				""";

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("unsupported outcome: MYSTERY")
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_PROTOCOL_ERROR");
		assertTemporaryFilesDeleted(runner);
	}

	@Test
	void deletesTemporaryFilesWhenRunnerThrows() {
		FakeRunner runner = new FakeRunner();
		runner.failure = new IllegalStateException("launch failed");

		assertThatThrownBy(() -> new DouyinIncrementalFetchService(runner).fetch(request(Set.of())))
				.isInstanceOf(CollectFetchException.class)
				.hasMessageContaining("launch failed")
				.extracting(error -> ((CollectFetchException) error).getErrorCode())
				.isEqualTo("F2_PROTOCOL_ERROR");
		assertTemporaryFilesDeleted(runner);
	}

	private DouyinFetchRequest request(Set<String> knownIds) {
		return new DouyinFetchRequest("MS4-author", knownIds, "100", 20, 10, 3,
				DouyinFetchMode.INCREMENTAL, 40);
	}

	private void assertTemporaryFilesDeleted(FakeRunner runner) {
		assertThat(runner.knownIdsFile).doesNotExist();
		assertThat(runner.outputFile).doesNotExist();
	}

	private static final class FakeRunner implements DouyinIncrementalFetchService.CommandRunner {
		private F2CommandResult commandResult = new F2CommandResult(0,
				"stream-vault-ok\nstream-vault-fetch-outcome={\"outcome\":\"KNOWN_BOUNDARY\"}", 10);
		private RuntimeException failure;
		private String resultJson;
		private Path knownIdsFile;
		private Path outputFile;
		private String knownIdsJson;

		@Override
		public F2CommandResult run(DouyinFetchRequest request, Path knownIdsFile, Path outputFile) {
			this.knownIdsFile = knownIdsFile;
			this.outputFile = outputFile;
			if (failure != null) {
				throw failure;
			}
			try {
				knownIdsJson = Files.readString(knownIdsFile, StandardCharsets.UTF_8);
				if (resultJson != null) {
					Files.writeString(outputFile, resultJson, StandardCharsets.UTF_8);
				}
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return commandResult;
		}
	}
}
