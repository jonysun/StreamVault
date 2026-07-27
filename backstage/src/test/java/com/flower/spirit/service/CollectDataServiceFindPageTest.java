package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.dto.CollectTaskListItem;
import com.flower.spirit.entity.CollectDataEntity;

class CollectDataServiceFindPageTest {

	@Test
	void taskListUsesLightweightProjectionWithoutSnapshotColumns() throws Exception {
		CapturingJdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, taskid, taskname, lastfetchsnapshot, lastplanitems) "
				+ "VALUES (1, 'task-1', 'Task One', ?, ?)", "x".repeat(10000), "y".repeat(10000));
		CollectDataService service = new CollectDataService();
		ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);

		AjaxEntity response = service.findPage(new CollectDataEntity());

		Page<?> page = (Page<?>) response.getRecord();
		assertThat(page.getContent()).singleElement().isInstanceOf(CollectTaskListItem.class);
		assertThat(jdbc.queries).anySatisfy(sql -> {
			assertThat(sql).startsWith("SELECT c.id, c.taskid");
			assertThat(sql).doesNotContain("lastfetchsnapshot", "lastplanitems", "SELECT *");
		});
	}

	@Test
	void taskListIncludesActiveQueueAndRunMetadata() throws Exception {
		CapturingJdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, taskid, taskname, taskstatus) VALUES (1, 'task-1', 'Queued One', '排队中')");
		jdbc.update("INSERT INTO biz_collect_data(id, taskid, taskname, taskstatus) VALUES (2, 'task-2', 'Queued Two', '排队中')");
		jdbc.update("INSERT INTO biz_collect_data(id, taskid, taskname, taskstatus) VALUES (3, 'task-3', 'Running', '排队中')");
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, created_at) "
				+ "VALUES (11, 1, 'MANUAL', 'QUEUED', '2026-07-26 20:31:00')");
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, created_at) "
				+ "VALUES (12, 2, 'MANUAL', 'QUEUED', '2026-07-26 20:31:01')");
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, heartbeat_at, created_at) "
				+ "VALUES (13, 3, 'MANUAL', 'FETCHING', '2026-07-26 20:32:00', '2026-07-26 20:31:02')");
		jdbc.update("INSERT INTO biz_job_queue(id, job_type, dedupe_key, payload, state, priority, available_at, "
				+ "attempt_count, max_attempts, created_at, updated_at) VALUES "
				+ "(101, 'COLLECT_FETCH', 'collect:1', '{}', 'QUEUED', 10, '2026-07-26 20:31:00', 0, 3, "
				+ "'2026-07-26 20:31:00', '2026-07-26 20:31:00')");
		jdbc.update("INSERT INTO biz_job_queue(id, job_type, dedupe_key, payload, state, priority, available_at, "
				+ "attempt_count, max_attempts, created_at, updated_at) VALUES "
				+ "(102, 'COLLECT_FETCH', 'collect:2', '{}', 'QUEUED', 10, '2026-07-26 20:31:01', 0, 3, "
				+ "'2026-07-26 20:31:01', '2026-07-26 20:31:01')");
		jdbc.update("INSERT INTO biz_job_queue(id, job_type, dedupe_key, payload, state, priority, available_at, "
				+ "attempt_count, max_attempts, locked_by, locked_at, created_at, updated_at) VALUES "
				+ "(103, 'COLLECT_FETCH', 'collect:3', '{}', 'RUNNING', 10, '2026-07-26 20:31:02', 1, 3, "
				+ "'worker-1', '2026-07-26 20:32:00', '2026-07-26 20:31:02', '2026-07-26 20:32:00')");
		CollectDataService service = new CollectDataService();
		ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);

		Page<?> page = (Page<?>) service.findPage(new CollectDataEntity()).getRecord();

		CollectTaskListItem running = findById(page, 3);
		assertThat(running.activeJobId()).isEqualTo(103L);
		assertThat(running.activeRunId()).isEqualTo(13L);
		assertThat(running.jobState()).isEqualTo("RUNNING");
		assertThat(running.runState()).isEqualTo("FETCHING");
		assertThat(running.queuePosition()).isNull();
		assertThat(running.heartbeatAt()).isEqualTo("2026-07-26 20:32:00");

		CollectTaskListItem firstQueued = findById(page, 1);
		assertThat(firstQueued.activeJobId()).isEqualTo(101L);
		assertThat(firstQueued.activeRunId()).isEqualTo(11L);
		assertThat(firstQueued.jobState()).isEqualTo("QUEUED");
		assertThat(firstQueued.runState()).isEqualTo("QUEUED");
		assertThat(firstQueued.queuePosition()).isEqualTo(1);

		CollectTaskListItem secondQueued = findById(page, 2);
		assertThat(secondQueued.queuePosition()).isEqualTo(2);
	}

	@Test
	void taskListSeparatesLatestFetchAndDownloadStates() throws Exception {
		CapturingJdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, taskid, taskname) VALUES (1, 'task-1', 'Author')");
		jdbc.update("INSERT INTO biz_collect_run(id, collect_task_id, trigger_type, state, fetch_stop_reason, "
				+ "fetch_warning, created_at) VALUES (11, 1, 'SCHEDULED', 'COMPLETED', 'KNOWN_BOUNDARY', "
				+ "'cookie nearing expiry', '2026-07-27 01:00:00')");
		String[] states = { "QUEUED", "QUEUED", "RUNNING", "RETRY_WAIT", "COMPLETED", "COMPLETED",
				"SKIPPED_EXISTING", "FAILED" };
		for (int index = 0; index < states.length; index++) {
			jdbc.update("INSERT INTO biz_collect_run_item(id, run_id, ordinal, work_id, decision, process_state, "
					+ "queue_generation, created_at, updated_at) VALUES (?, 11, ?, ?, 'NEW', ?, "
					+ "'FETCH_DOWNLOAD_V1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
					100 + index, index + 1, "work-" + index, states[index]);
		}
		CollectDataService service = new CollectDataService();
		ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);

		CollectTaskListItem item = findById((Page<?>) service.findPage(new CollectDataEntity()).getRecord(), 1);

		assertThat(item.fetchState()).isEqualTo("COMPLETED");
		assertThat(item.downloadQueued()).isEqualTo(2);
		assertThat(item.downloadRunning()).isEqualTo(1);
		assertThat(item.downloadRetryWait()).isEqualTo(1);
		assertThat(item.downloadCompleted()).isEqualTo(2);
		assertThat(item.downloadSkipped()).isEqualTo(1);
		assertThat(item.downloadFailed()).isEqualTo(1);
		assertThat(item.latestStopReason()).isEqualTo("KNOWN_BOUNDARY");
		assertThat(item.latestFetchWarning()).isEqualTo("cookie nearing expiry");
	}

	@Test
	void keywordSearchMatchesTaskNameAddressPlatformAndDatabaseId() throws Exception {
		CapturingJdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id, taskid, platform, taskname, originaladdress) "
				+ "VALUES (21, 'legacy-one', '抖音', 'Alpha Author', 'postMS4-alpha')");
		jdbc.update("INSERT INTO biz_collect_data(id, taskid, platform, taskname, originaladdress) "
				+ "VALUES (22, 'legacy-two', '哔哩', 'Beta Author', 'bili-arc-7788')");
		CollectDataService service = new CollectDataService();
		ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);

		CollectDataEntity byName = new CollectDataEntity();
		byName.setKeyword("alpha");
		Page<?> namePage = (Page<?>) service.findPage(byName).getRecord();
		assertThat(namePage.getContent()).extracting(item -> ((CollectTaskListItem) item).id())
				.containsExactly(21);

		CollectDataEntity byAddress = new CollectDataEntity();
		byAddress.setKeyword("7788");
		Page<?> addressPage = (Page<?>) service.findPage(byAddress).getRecord();
		assertThat(addressPage.getContent()).extracting(item -> ((CollectTaskListItem) item).id())
				.containsExactly(22);

		CollectDataEntity byId = new CollectDataEntity();
		byId.setKeyword("21");
		Page<?> idPage = (Page<?>) service.findPage(byId).getRecord();
		assertThat(idPage.getContent()).extracting(item -> ((CollectTaskListItem) item).id())
				.containsExactly(21);
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskid TEXT, platform TEXT, "
				+ "taskname TEXT, taskstatus TEXT, createtime TEXT, endtime TEXT, count TEXT, carriedout TEXT, "
				+ "originaladdress TEXT, monitoring TEXT, taskenabled TEXT, lastCheckTime TEXT, lastid TEXT, "
				+ "maxcur INTEGER, omaxcur INTEGER, generatenfo TEXT, taskcron TEXT, lastfetchtime TEXT, "
				+ "lastfetchcount INTEGER, lastfetchsnapshot TEXT, lastplanitems TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_run (id INTEGER PRIMARY KEY AUTOINCREMENT, collect_task_id INTEGER NOT NULL, "
				+ "trigger_type TEXT NOT NULL, state TEXT NOT NULL, requested_limit INTEGER, fetched_count INTEGER, "
				+ "planned_count INTEGER, inserted_count INTEGER, skipped_existing_count INTEGER, failed_item_count INTEGER, "
				+ "started_at DATETIME, heartbeat_at DATETIME, finished_at DATETIME, error_code TEXT, error_message TEXT, "
				+ "error_detail TEXT, fetch_stop_reason TEXT, fetch_warning TEXT, created_at DATETIME NOT NULL)");
		jdbc.execute("CREATE TABLE biz_collect_run_item (id INTEGER PRIMARY KEY, run_id INTEGER, ordinal INTEGER, "
				+ "work_id TEXT, decision TEXT, process_state TEXT, queue_generation TEXT, created_at DATETIME, "
				+ "updated_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_job_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, job_type TEXT NOT NULL, "
				+ "dedupe_key TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL, priority INTEGER NOT NULL, "
				+ "available_at DATETIME NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, "
				+ "locked_by TEXT, locked_at DATETIME, last_error_code TEXT, last_error_message TEXT, "
				+ "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
	}

	private CollectTaskListItem findById(Page<?> page, int id) {
		return page.getContent().stream()
				.map(CollectTaskListItem.class::cast)
				.filter(item -> item.id() == id)
				.findFirst()
				.orElseThrow();
	}

	private CapturingJdbcTemplate jdbcTemplate() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-task-list.db"));
		return new CapturingJdbcTemplate(dataSource);
	}

	private static final class CapturingJdbcTemplate extends JdbcTemplate {
		private final List<String> queries = new ArrayList<>();

		private CapturingJdbcTemplate(SQLiteDataSource dataSource) {
			super(dataSource);
		}

		@Override
		public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
			queries.add(sql);
			return super.query(sql, rowMapper, args);
		}
	}
}
