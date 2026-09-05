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
