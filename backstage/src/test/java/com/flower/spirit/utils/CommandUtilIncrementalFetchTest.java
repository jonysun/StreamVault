package com.flower.spirit.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.service.DouyinFetchMode;
import com.flower.spirit.service.DouyinFetchRequest;

class CommandUtilIncrementalFetchTest {

	@Test
	void blankCookieSkipsLegacyF2ProcessLaunch() {
		assertThat(CommandUtil.f2cmd("", "123", "fetch_video", null, null, null, null)).isEmpty();
		assertThat(CommandUtil.getLastF2ExitCode()).isEqualTo(-2);
		assertThat(CommandUtil.getLastF2DurationMs()).isZero();
	}

	@Test
	void buildsExactSeparateIncrementalArguments() {
		DouyinFetchRequest request = new DouyinFetchRequest(
				"MS4-author", Set.of("known-1"), "", 20, 30, 3,
				DouyinFetchMode.AUDIT, 41);
		Path knownIds = Path.of("C:/temp/known ids.json");
		Path output = Path.of("C:/temp/result file.json");
		String cookie = "sid_guard=guard-secret; sid_tt=tt-secret";

		List<String> command = CommandUtil.buildIncrementalCommand(
				request, knownIds, output, cookie);

		assertThat(command).containsExactly(
				"/opt/venv/bin/python3", "/home/app/script/douyin.py",
				"fetch_douyin_list_incremental", "--cookie", cookie,
				"--sec_user_id", "MS4-author",
				"--known_ids_file", knownIds.toString(),
				"--last_seen_publish_time", "",
				"--known_boundary", "20",
				"--max_pages", "30",
				"--empty_page_limit", "3",
				"--mode", "audit",
				"--max_items", "41",
				"--backfill_cursor", "",
				"--backfill_complete", "0",
				"--backfill_verifying", "0",
				"--backfill_clean_passes", "0",
				"--output", output.toString());
	}

	@Test
	void masksExactCookieAndUnknownCookieFieldValuesInCommandAndOutput() {
		DouyinFetchRequest request = new DouyinFetchRequest(
				"MS4-author", Set.of(), null, 20, 20, 3,
				DouyinFetchMode.INCREMENTAL, 0);
		String cookie = "sid_guard=guard-secret; sid_tt=tt-secret; "
				+ "passport_auth_status=auth-secret";
		List<String> command = CommandUtil.buildIncrementalCommand(
				request, Path.of("known.json"), Path.of("result.json"), cookie);
		String processOutput = "request failed cookie=" + cookie
				+ " guard=guard-secret tt=tt-secret auth=auth-secret";

		String safeCommand = CommandUtil.buildSafeCommandString(command);
		String safeOutput = CommandUtil.sanitizeF2Output(processOutput, command);

		assertThat(safeCommand).contains("--cookie ***masked***")
				.doesNotContain(cookie, "guard-secret", "tt-secret", "auth-secret");
		assertThat(safeOutput).doesNotContain(
				cookie, "guard-secret", "tt-secret", "auth-secret")
				.contains("***masked***");
	}

	@Test
	void shortNumericCookieValuesDoNotCorruptStructuredDiagnostics() {
		DouyinFetchRequest request = new DouyinFetchRequest(
				"MS4-author", Set.of(), "", 20, 20, 3,
				DouyinFetchMode.INCREMENTAL, 0);
		String cookie = "passport_auth_status=0; flag=1; sid_guard=guard-secret";
		List<String> command = CommandUtil.buildIncrementalCommand(
				request, Path.of("known.json"), Path.of("result.json"), cookie);
		String output = "stream-vault-fetch-error={\"errorCode\":\"UPSTREAM_SCHEMA_ERROR\","
				+ "\"diagnostics\":{\"page\":1,\"statusCode\":10000,\"itemCount\":0},"
				+ "\"cookie\":\"" + cookie + "\"}";

		String safe = CommandUtil.sanitizeF2Output(output, command);
		JSONObject payload = JSON.parseObject(safe.substring(safe.indexOf('=') + 1));
		JSONObject diagnostics = payload.getJSONObject("diagnostics");

		assertThat(diagnostics.getIntValue("page")).isEqualTo(1);
		assertThat(diagnostics.getIntValue("statusCode")).isEqualTo(10000);
		assertThat(diagnostics.getIntValue("itemCount")).isZero();
		assertThat(safe).doesNotContain(cookie, "passport_auth_status=0", "guard-secret");
	}

	@Test
	void publicSanitizerUsesGlobalCookieForUnknownFields() {
		String original = Global.tiktokCookie;
		String cookie = "sid_guard=guard-secret; sid_tt=tt-secret; passport_auth_status=0";
		try {
			Global.tiktokCookie = cookie;

			String safe = CommandUtil.sanitizeF2Output(
					"failed cookie=" + cookie + " guard=guard-secret tt=tt-secret");

			assertThat(safe).doesNotContain(cookie, "guard-secret", "tt-secret")
					.contains("***masked***");
		} finally {
			Global.tiktokCookie = original;
		}
	}

	@Test
	void computesBoundedIncrementalTimeoutFromMaxPages() {
		DouyinFetchRequest onePage = requestWithMaxPages(1);
		DouyinFetchRequest manyPages = requestWithMaxPages(1000);

		assertThat(CommandUtil.incrementalTimeoutSeconds(onePage)).isEqualTo(60);
		assertThat(CommandUtil.incrementalTimeoutSeconds(manyPages)).isEqualTo(900);
	}

	@Test
	void timeoutDestroysThenForciblyCleansUpProcess() {
		BlockingFakeProcess process = new BlockingFakeProcess(false);
		List<String> command = List.of("python", "--cookie", "sid_guard=secret-value");

		CommandUtil.F2CommandResult result = CommandUtil.runIncrementalCommand(
				command, 60, ignored -> process);

		assertThat(result.exitCode()).isNotZero();
		assertThat(result.output()).contains("process timeout after 60 seconds")
				.doesNotContain("secret-value");
		assertThat(process.destroyCalled).isTrue();
		assertThat(process.destroyForciblyCalled).isTrue();
		assertThat(process.isAlive()).isFalse();
		assertThat(process.timedWaitCalls).isGreaterThanOrEqualTo(2);
	}

	@Test
	void interruptionPreservesFlagAndForciblyCleansUpProcess() {
		BlockingFakeProcess process = new BlockingFakeProcess(false, 1);
		List<String> command = List.of("python", "--cookie", "sid_guard=secret-value");

		try {
			CommandUtil.F2CommandResult result = CommandUtil.runIncrementalCommand(
					command, 60, ignored -> process);

			assertThat(result.exitCode()).isNotZero();
			assertThat(result.output()).contains("process interrupted")
					.doesNotContain("secret-value");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(process.destroyCalled).isTrue();
			assertThat(process.destroyForciblyCalled).isTrue();
			assertThat(process.isAlive()).isFalse();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void reportsCleanupFailureWhenProcessSurvivesForcedDestroy() {
		BlockingFakeProcess process = new BlockingFakeProcess(true);
		List<String> command = List.of("python", "--cookie", "sid_guard=secret-value");

		CommandUtil.F2CommandResult result = CommandUtil.runIncrementalCommand(
				command, 60, ignored -> process);

		assertThat(result.exitCode()).isNotZero();
		assertThat(result.output())
				.contains("process cleanup failed: still alive after force")
				.doesNotContain("secret-value");
		assertThat(process.destroyForciblyCalled).isTrue();
		assertThat(process.isAlive()).isTrue();
	}

	@Test
	void interruptedResistantCleanupReportsFailureAndPreservesFlag() {
		BlockingFakeProcess process = new BlockingFakeProcess(true, 1, 2);
		List<String> command = List.of("python", "--cookie", "sid_guard=secret-value");

		try {
			CommandUtil.F2CommandResult result = CommandUtil.runIncrementalCommand(
					command, 60, ignored -> process);

			assertThat(result.output())
					.contains("process interrupted")
					.contains("process cleanup failed: still alive after force");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(process.destroyForciblyCalled).isTrue();
			assertThat(process.isAlive()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	private DouyinFetchRequest requestWithMaxPages(int maxPages) {
		return new DouyinFetchRequest(
				"MS4-author", Set.of(), "", 20, maxPages, 3,
				DouyinFetchMode.INCREMENTAL, 0);
	}

	private static final class BlockingFakeProcess extends Process {
		private final BlockingInputStream input = new BlockingInputStream();
		private final boolean resistsForcedDestroy;
		private final Set<Integer> interruptedWaitCalls;
		private boolean alive = true;
		private boolean destroyCalled;
		private boolean destroyForciblyCalled;
		private int timedWaitCalls;

		private BlockingFakeProcess(boolean resistsForcedDestroy,
				Integer... interruptedWaitCalls) {
			this.resistsForcedDestroy = resistsForcedDestroy;
			this.interruptedWaitCalls = Set.of(interruptedWaitCalls);
		}

		@Override
		public OutputStream getOutputStream() {
			return new ByteArrayOutputStream();
		}

		@Override
		public InputStream getInputStream() {
			return input;
		}

		@Override
		public InputStream getErrorStream() {
			return new ByteArrayInputStream(new byte[0]);
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
			timedWaitCalls++;
			if (interruptedWaitCalls.contains(timedWaitCalls)) {
				throw new InterruptedException("test interruption");
			}
			return !alive;
		}

		@Override
		public int exitValue() {
			if (alive) {
				throw new IllegalThreadStateException("still running");
			}
			return 0;
		}

		@Override
		public void destroy() {
			destroyCalled = true;
			input.release();
		}

		@Override
		public Process destroyForcibly() {
			destroyForciblyCalled = true;
			if (!resistsForcedDestroy) {
				alive = false;
			}
			input.release();
			return this;
		}

		@Override
		public boolean isAlive() {
			return alive;
		}
	}

	private static final class BlockingInputStream extends InputStream {
		private boolean released;

		@Override
		public synchronized int read() throws IOException {
			while (!released) {
				try {
					wait();
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
					throw new IOException("reader interrupted");
				}
			}
			return -1;
		}

		private synchronized void release() {
			released = true;
			notifyAll();
		}
	}
}
