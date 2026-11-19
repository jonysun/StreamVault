package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import com.flower.spirit.database.DatabaseWriteExecutor;
import com.flower.spirit.service.transaction.CollectDownloadTransaction;
import com.flower.spirit.service.transaction.CollectQueueTransaction;

class DownloadCenterServiceTest {

	@Test
	@SuppressWarnings("unchecked")
	void activeItemsMergeCollectionAndDirectQueuesWithNormalizedSources() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id,taskname) VALUES(4,'Favorite author')");
		jdbc.update("INSERT INTO biz_collect_run(id,collect_task_id) VALUES(9,4)");
		jdbc.update("INSERT INTO biz_collect_run_item(id,run_id,platform_key,work_id,title_snapshot,decision,"+
				"process_state,attempt_count,max_attempts,available_at,queue_generation,created_at,updated_at) "
				+ "VALUES(90,9,'douyin','work-90','Collect work','NEW','QUEUED',0,4,CURRENT_TIMESTAMP,"+
				"'FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
		jdbc.update("INSERT INTO biz_job_queue(id,job_type,dedupe_key,payload,state,priority,available_at,"+
				"attempt_count,max_attempts,created_at,updated_at) VALUES(20,'DIRECT_DOWNLOAD','direct:x',?,"+
				"'RUNNING',100,CURRENT_TIMESTAMP,1,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				"{\"sourceUrl\":\"https://youtu.be/x\",\"sourceType\":\"YOUTUBE_COLLECTION\","+
						"\"platformName\":\"YouTube\",\"title\":\"Playlist work\",\"author\":\"Creator\"}");
		DownloadCenterService service = new DownloadCenterService(jdbc, mock(DirectDownloadQueueService.class),
				mock(CollectRunService.class), mock(RuntimeControlService.class), mock(DatabaseWriteExecutor.class));

		Map<String, Object> result = service.items("active", "ALL", null, null, 0, 25);
		List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("content");

		assertThat(result).containsEntry("totalElements", 2L);
		assertThat(rows).extracting(row -> row.get("sourceType"))
				.containsExactly("YOUTUBE_COLLECTION", "COLLECT");
		assertThat(rows.get(0)).containsEntry("title", "Playlist work")
				.containsEntry("source_url", "https://youtu.be/x");
	}

	@Test
	void marksOnlySnapshotPendingCollectionItemsAsRemoteMissing() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id,taskname) VALUES(4,'Favorite author')");
		jdbc.update("INSERT INTO biz_collect_run(id,collect_task_id) VALUES(9,4)");
		jdbc.update("INSERT INTO biz_collect_run_item(id,run_id,platform_key,work_id,decision,process_state,attempt_count,max_attempts,"
				+ "error_code,queue_generation,created_at,updated_at) VALUES"
				+ "(90,9,'douyin','pending','EXISTING','RETRY_WAIT',0,4,'LIST_SNAPSHOT_PENDING','FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
				+ "(91,9,'douyin','failed','EXISTING','FAILED',1,4,'LIST_SNAPSHOT_PENDING','FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

		CollectDownloadTransaction transaction = new CollectDownloadTransaction(jdbc);
		DatabaseWriteExecutor writes = new DatabaseWriteExecutor() {
			@Override
			public <T> T execute(String operation, Supplier<T> action) {
				return action.get();
			}
		};
		CollectRunService runs = new CollectRunService(mock(CollectQueueTransaction.class), transaction, writes);
		DownloadCenterService service = new DownloadCenterService(jdbc, mock(DirectDownloadQueueService.class),
				runs, mock(RuntimeControlService.class), writes);

		Map<String, Object> result = service.transition(List.of("COLLECT:90", "COLLECT:91", "DIRECT:20"),
				"MARK_REMOTE_MISSING");

		assertThat(result).containsEntry("changed", 1).containsEntry("skipped", 2);
		assertThat(jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE id=90", String.class))
				.isEqualTo("SKIPPED_REMOTE_MISSING");
		assertThat(jdbc.queryForObject("SELECT error_code FROM biz_collect_run_item WHERE id=90", String.class))
				.isEqualTo("REMOTE_LIST_MISSING");
		assertThat(jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE id=91", String.class))
				.isEqualTo("FAILED");
	}

	@Test
	void allMatchingHistorySelectionRetriesOnlyFailedFilteredRecords() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id,taskname) VALUES(4,'Favorite author')");
		jdbc.update("INSERT INTO biz_collect_run(id,collect_task_id) VALUES(9,4)");
		jdbc.update("INSERT INTO biz_collect_run_item(id,run_id,platform_key,work_id,decision,process_state,attempt_count,max_attempts,error_code,queue_generation,created_at,updated_at) VALUES"
				+ "(90,9,'douyin','done','EXISTING','COMPLETED',1,4,NULL,'FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
				+ "(91,9,'douyin','failed','EXISTING','FAILED',1,4,'MEDIA_ERROR','FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
		CollectRunService runs = mock(CollectRunService.class);
		DownloadCenterService service = new DownloadCenterService(jdbc, mock(DirectDownloadQueueService.class),
				runs, mock(RuntimeControlService.class), mock(DatabaseWriteExecutor.class));

		service.transition(List.of(), "RETRY", true, List.of(), "history", "COLLECT", "FAILED", null);

		verify(runs).manualRetryDownloads(List.of(91L));
	}

	@Test
	void refreshAuthorListRequeuesOnlyPendingCollectionItems() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id,taskname) VALUES(4,'Favorite author')");
		jdbc.update("INSERT INTO biz_collect_run(id,collect_task_id) VALUES(9,4)");
		jdbc.update("INSERT INTO biz_collect_run_item(id,run_id,platform_key,work_id,decision,process_state,attempt_count,max_attempts,error_code,queue_generation,created_at,updated_at) VALUES"
				+ "(90,9,'douyin','pending','EXISTING','RETRY_WAIT',0,4,'LIST_SNAPSHOT_PENDING','FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
				+ "(91,9,'douyin','done','EXISTING','FAILED',1,4,'MEDIA_ERROR','FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
		CollectEnqueueService enqueue = mock(CollectEnqueueService.class);
		when(enqueue.enqueueSnapshotRefresh(4)).thenReturn(new CollectEnqueueResult(11L, 12L,
				CollectRunState.QUEUED, true, false));
		DatabaseWriteExecutor writes = new DatabaseWriteExecutor() {
			@Override
			public <T> T execute(String operation, Supplier<T> action) { return action.get(); }
		};
		DownloadCenterService service = new DownloadCenterService(jdbc, mock(DirectDownloadQueueService.class),
				mock(CollectRunService.class), mock(RuntimeControlService.class), writes, enqueue);

		Map<String, Object> result = service.transition(List.of("COLLECT:90", "COLLECT:91"),
				"REFRESH_AUTHOR_LIST");

		assertThat(result).containsEntry("changed", 1).containsEntry("refreshEnqueued", 1);
		assertThat(jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE id=90", String.class))
				.isEqualTo("QUEUED");
		assertThat(jdbc.queryForObject("SELECT error_code FROM biz_collect_run_item WHERE id=90", String.class))
				.isNull();
		assertThat(jdbc.queryForObject("SELECT process_state FROM biz_collect_run_item WHERE id=91", String.class))
				.isEqualTo("FAILED");
	}

	@Test
	@SuppressWarnings("unchecked")
	void errorFiltersFindLegacyAndStructuredRecordsAndLimitCrossPageRetry() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id,taskname) VALUES(4,'Favorite author')");
		jdbc.update("INSERT INTO biz_collect_run(id,collect_task_id) VALUES(9,4)");
		insertCollectionFailure(jdbc, 91, "DOWNLOAD_EXECUTION_FAILED", "DTK 节点仍在冷却，约 44 秒后重试");
		insertCollectionFailure(jdbc, 92, "DOUYIN_GLOBAL_COOLDOWN", null);
		insertCollectionFailure(jdbc, 93, "INGEST_STATE_ERROR", "download target already exists: /media/work");
		insertDirectFailure(jdbc, 20, "DTK_NODE_COOLDOWN", null);
		CollectRunService runs = mock(CollectRunService.class);
		DirectDownloadQueueService direct = mock(DirectDownloadQueueService.class);
		DownloadCenterService service = new DownloadCenterService(jdbc, direct, runs,
				mock(RuntimeControlService.class), immediateWrites());

		Map<String, Object> firstPage = service.items("history", "ALL", "FAILED", "douyin", "cooldown", 0, 1);
		assertThat(firstPage).containsEntry("totalElements", 3L).containsEntry("totalPages", 3L);
		assertThat((List<Map<String, Object>>) firstPage.get("content")).hasSize(1);
		assertThat(service.matchingRecordKeys("history", "ALL", "FAILED", null, "DOWNLOAD_TARGET_EXISTS"))
				.containsExactly("COLLECT:93");

		Map<String, Object> result = service.transition(List.of(), "RETRY", true, List.of("COLLECT:92"),
				"history", "ALL", "FAILED", "douyin", "COOLDOWN");
		assertThat(result).containsEntry("selected", 2);
		verify(runs).manualRetryDownloads(List.of(91L));
		verify(direct).manualRetryItems(List.of(20L));
	}

	@Test
	void canMarkConfirmedCollectionFailuresOrHideOnlyFilteredHistory() throws Exception {
		JdbcTemplate jdbc = jdbcTemplate();
		createSchema(jdbc);
		jdbc.update("INSERT INTO biz_collect_data(id,taskname) VALUES(4,'Favorite author')");
		jdbc.update("INSERT INTO biz_collect_run(id,collect_task_id) VALUES(9,4)");
		insertCollectionFailure(jdbc, 91, "DOWNLOAD_EXECUTION_FAILED",
				"DTK API HTTP status=404, endpoint=/api/v1/douyin/video, body=missing");
		insertCollectionFailure(jdbc, 92, "DTK_WORK_NOT_FOUND", null);
		insertCollectionFailure(jdbc, 93, "DTK_UPSTREAM_HTTP",
				"DTK API HTTP status=404, endpoint=/api/v1/douyin/profile, body=missing");
		insertCollectionFailure(jdbc, 94, "DOWNLOAD_TARGET_EXISTS", null);
		insertDirectFailure(jdbc, 20, "F2_WORK_UNAVAILABLE", null);
		DatabaseWriteExecutor writes = immediateWrites();
		CollectRunService runs = new CollectRunService(mock(CollectQueueTransaction.class),
				new CollectDownloadTransaction(jdbc), writes);
		DownloadCenterService service = new DownloadCenterService(jdbc, mock(DirectDownloadQueueService.class),
				runs, mock(RuntimeControlService.class), writes);

		assertThat(service.transition(List.of("COLLECT:91", "COLLECT:93", "COLLECT:94"), "MARK_REMOTE_MISSING"))
				.containsEntry("changed", 1).containsEntry("skipped", 2);
		assertThat(service.items("history", "ALL", "FAILED", null, "REMOTE_UNAVAILABLE", 0, 1))
				.containsEntry("totalElements", 2L);
		assertThat(service.transition(List.of(), "HIDE_HISTORY", true, List.of(), "history", "ALL", "FAILED", null,
				"REMOTE_UNAVAILABLE")).containsEntry("changed", 2);
		assertThat(jdbc.queryForList("SELECT record_key FROM biz_download_history_hidden", String.class))
				.containsExactlyInAnyOrder("COLLECT:92", "DIRECT:20");
		assertThat(service.matchingRecordKeys("history", "ALL", "FAILED", null, null))
				.containsExactlyInAnyOrder("COLLECT:93", "COLLECT:94");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_collect_run_item", Integer.class)).isEqualTo(4);
	}

	private void insertCollectionFailure(JdbcTemplate jdbc, long id, String code, String message) {
		jdbc.update("INSERT INTO biz_collect_run_item(id,run_id,platform_key,work_id,title_snapshot,decision,"
				+ "process_state,attempt_count,max_attempts,error_code,error_message,queue_generation,created_at,updated_at) "
				+ "VALUES(?,9,'douyin',?,'douyin video','NEW','FAILED',1,4,?,?,'FETCH_DOWNLOAD_V1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				id, "work-" + id, code, message);
	}

	private void insertDirectFailure(JdbcTemplate jdbc, long id, String code, String message) {
		jdbc.update("INSERT INTO biz_job_queue(id,job_type,dedupe_key,payload,state,priority,attempt_count,max_attempts,"
				+ "last_error_code,last_error_message,created_at,updated_at) "
				+ "VALUES(?,'DIRECT_DOWNLOAD',?,?,'FAILED',100,1,4,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				id, "direct:" + id, "{\"platformName\":\"douyin\",\"sourceType\":\"SINGLE_LINK\"}", code, message);
	}

	private DatabaseWriteExecutor immediateWrites() {
		return new DatabaseWriteExecutor() {
			@Override
			public <T> T execute(String operation, Supplier<T> action) {
				return action.get();
			}
		};
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_data(id INTEGER PRIMARY KEY,taskname TEXT)");
		jdbc.execute("CREATE TABLE biz_collect_run(id INTEGER PRIMARY KEY,collect_task_id INTEGER)");
		jdbc.execute("CREATE TABLE biz_collect_run_item(id INTEGER PRIMARY KEY,run_id INTEGER,platform_key TEXT,"+
				"work_id TEXT,nickname_snapshot TEXT,title_snapshot TEXT,decision TEXT,process_state TEXT,"+
				"attempt_count INTEGER,max_attempts INTEGER,available_at DATETIME,started_at DATETIME,"+
				"finished_at DATETIME,locked_by TEXT,locked_at DATETIME,error_code TEXT,error_message TEXT,error_detail TEXT,metadata_snapshot TEXT,queue_generation TEXT,created_at DATETIME,"+
				"updated_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_job_queue(id INTEGER PRIMARY KEY,job_type TEXT,dedupe_key TEXT,payload TEXT,"+
				"state TEXT,priority INTEGER,available_at DATETIME,attempt_count INTEGER,max_attempts INTEGER,"+
				"locked_by TEXT,locked_at DATETIME,last_error_code TEXT,last_error_message TEXT,created_at DATETIME,"+
				"updated_at DATETIME)");
		jdbc.execute("CREATE TABLE biz_download_history_hidden(record_key TEXT PRIMARY KEY,source_type TEXT,"+
				"source_id INTEGER,hidden_at DATETIME)");
	}

	private JdbcTemplate jdbcTemplate() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-download-center.db"));
		return new JdbcTemplate(dataSource);
	}
}
