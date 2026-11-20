package com.flower.spirit.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite 里 `createtime` 到底存成什么 —— 媒体流的游标比较要用。
 *
 * 媒体流（{@link MediaFeedQueryDao}）要支持「按下载时间排序」时，游标的
 * WHERE 条件得跟列的存储类型对上：
 * - SQLite 里如果存的是 **epoch 毫秒整数**，比较就得绑 Long；
 * - 存的是 TEXT（ISO 串），绑 Timestamp/String 才是对的；
 * - 两边混着存的话，SQLite 的类型序（INTEGER < TEXT）会让比较直接失效。
 *
 * 这个测试把事实钉住 —— 换了 sqlite 驱动版本或改了 JPA 映射就会红。
 */
class SqliteCreatetimeStorageTest {

	@Test
	void createtimeIsStoredAsEpochMillisAndComparesAgainstLongParameters() throws Exception {
		Path directory = Path.of("target", "test-databases");
		Files.createDirectories(directory);
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + "-createtime.db"));
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE TABLE biz_graphic_content (id INTEGER PRIMARY KEY, createtime DATETIME)");

		Timestamp moment = Timestamp.from(Instant.parse("2026-11-20T08:00:00Z"));
		jdbc.update("INSERT INTO biz_graphic_content(id, createtime) VALUES (?, ?)", 1, moment);

		Object stored = jdbc.queryForObject("SELECT createtime FROM biz_graphic_content WHERE id = 1", Object.class);
		assertThat(stored).isInstanceOf(Number.class);

		// 整数毫秒：跟 Long 参数比较是对的（SQLite 里 INTEGER 和 TEXT 不是一个类型序）
		long millis = ((Number) stored).longValue();
		assertThat(millis).isEqualTo(moment.getTime());

		assertThat(jdbc.queryForList("SELECT id FROM biz_graphic_content WHERE createtime < ?", millis))
				.as("严格更早的行").isEmpty();
		assertThat(jdbc.queryForList("SELECT id FROM biz_graphic_content WHERE createtime > ?", millis - 1))
				.as("更晚的行").containsExactly(java.util.Map.of("id", 1));
	}
}
