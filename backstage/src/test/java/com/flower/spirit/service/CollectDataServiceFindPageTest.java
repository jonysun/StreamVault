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
			assertThat(sql).startsWith("SELECT id, taskid");
			assertThat(sql).doesNotContain("lastfetchsnapshot", "lastplanitems", "SELECT *");
		});
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE biz_collect_data (id INTEGER PRIMARY KEY, taskid TEXT, platform TEXT, "
				+ "taskname TEXT, taskstatus TEXT, createtime TEXT, endtime TEXT, count TEXT, carriedout TEXT, "
				+ "originaladdress TEXT, monitoring TEXT, taskenabled TEXT, lastCheckTime TEXT, lastid TEXT, "
				+ "maxcur INTEGER, omaxcur INTEGER, generatenfo TEXT, taskcron TEXT, lastfetchtime TEXT, "
				+ "lastfetchcount INTEGER, lastfetchsnapshot TEXT, lastplanitems TEXT)");
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
