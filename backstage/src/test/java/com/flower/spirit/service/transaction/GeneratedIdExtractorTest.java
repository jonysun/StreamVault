package com.flower.spirit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.GeneratedKeyHolder;

class GeneratedIdExtractorTest {

	@Test
	void extractsIdFromPostgresqlFullRowGeneratedKeys() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder(List.of(Map.of(
				"id", 5394L,
				"collect_task_id", 95,
				"state", "QUEUED")));

		assertThat(GeneratedIdExtractor.requireId(keys, "collect run")).isEqualTo(5394L);
	}

	@Test
	void extractsSingleAnonymousSqliteGeneratedKey() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder(List.of(Map.of("last_insert_rowid()", 42L)));

		assertThat(GeneratedIdExtractor.requireId(keys, "collect run")).isEqualTo(42L);
	}

	@Test
	void matchesIdColumnCaseInsensitively() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder(List.of(Map.of("ID", 17, "STATE", "QUEUED")));

		assertThat(GeneratedIdExtractor.requireId(keys, "collect job")).isEqualTo(17L);
	}

	@Test
	void rejectsMissingGeneratedKeyRow() {
		assertThatThrownBy(() -> GeneratedIdExtractor.requireId(new GeneratedKeyHolder(), "collect run"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Expected one generated key row for collect run but got 0");
	}

	@Test
	void rejectsMultipleGeneratedKeyRows() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder(List.of(Map.of("id", 1L), Map.of("id", 2L)));

		assertThatThrownBy(() -> GeneratedIdExtractor.requireId(keys, "collect run"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Expected one generated key row for collect run but got 2");
	}

	@Test
	void rejectsAmbiguousRowWithoutId() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder(List.of(Map.of("first", 1L, "second", 2L)));

		assertThatThrownBy(() -> GeneratedIdExtractor.requireId(keys, "collect run"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No numeric generated ID returned for collect run")
				.hasMessageContaining("first")
				.hasMessageContaining("second");
	}

	@Test
	void rejectsNonNumericId() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder(List.of(Map.of("id", "5394")));

		assertThatThrownBy(() -> GeneratedIdExtractor.requireId(keys, "collect run"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No numeric generated ID returned for collect run")
				.hasMessageContaining("id");
	}
}
