package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.flower.spirit.dto.DatabaseMaintenanceRequest;
import com.flower.spirit.service.transaction.DatabaseMaintenanceTransaction;

class DatabaseMaintenanceServiceTest {

	@Test
	void applyIsDisabledUnlessExplicitlyEnabled() {
		DatabaseMaintenanceService service = service(false, false);
		DatabaseMaintenanceRequest request = request("preview-token");

		assertThatThrownBy(() -> service.apply(request))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("apply");
	}

	@Test
	void applyRequiresGlobalPauseEvenWhenMaintenanceIsEnabled() {
		DatabaseMaintenanceService service = service(true, false);
		DatabaseMaintenanceRequest request = request("preview-token");

		assertThatThrownBy(() -> service.apply(request))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("暂停全部后台任务");
	}

	@Test
	void previewUsesAuditRetentionCountsInReferentiallySafeDefaultOrder() {
		DatabaseAuditService audit = mock(DatabaseAuditService.class);
		when(audit.audit()).thenReturn(Map.of(
				"video", Map.of(
						"exactEqualRows", 12L,
						"exactDuplicateVideoInfoChars", 999L,
						"differentRows", 2L),
				"retentionCandidates", Map.of(
						"runItems", 3L,
						"runEvents", 4L,
						"terminalRuns", 5L,
						"terminalJobs", 6L),
				"fingerprint", "sha256:audit"));
		DatabaseMaintenanceService service = service(false, false, audit);

		Map<String, Object> preview = service.preview(null);
		Map<String, Object> operations = map(preview.get("operations"));

		assertThat(operations.keySet()).containsExactly(
				DatabaseMaintenanceService.CLEAR_EXACT_DUPLICATE_VIDEOINFO,
				DatabaseMaintenanceService.PURGE_EXPIRED_RUN_ITEMS,
				DatabaseMaintenanceService.PURGE_EXPIRED_RUN_EVENTS,
				DatabaseMaintenanceService.PURGE_EXPIRED_TERMINAL_RUNS,
				DatabaseMaintenanceService.PURGE_EXPIRED_TERMINAL_JOBS);
		assertThat(map(operations.get(DatabaseMaintenanceService.PURGE_EXPIRED_RUN_ITEMS)))
				.containsEntry("rows", 3L);
		assertThat(map(operations.get(DatabaseMaintenanceService.PURGE_EXPIRED_RUN_EVENTS)))
				.containsEntry("rows", 4L);
		assertThat(map(operations.get(DatabaseMaintenanceService.PURGE_EXPIRED_TERMINAL_RUNS)))
				.containsEntry("rows", 5L);
		assertThat(map(operations.get(DatabaseMaintenanceService.PURGE_EXPIRED_TERMINAL_JOBS)))
				.containsEntry("rows", 6L);
		assertThat(map(operations.get(DatabaseMaintenanceService.CLEAR_EXACT_DUPLICATE_VIDEOINFO)))
				.containsEntry("rows", 12L)
				.containsEntry("logicalChars", 999L)
				.containsEntry("unhandledDifferentRows", 2L);
	}

	@Test
	void previewRejectsUnknownMaintenanceOperation() {
		assertThatThrownBy(() -> service(false, false).preview(List.of("DELETE_WORKS")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("不支持");
	}

	private DatabaseMaintenanceService service(boolean enabled, boolean allPaused) {
		return service(enabled, allPaused, mock(DatabaseAuditService.class));
	}

	private DatabaseMaintenanceService service(boolean enabled, boolean allPaused, DatabaseAuditService audit) {
		RuntimeControlService controls = mock(RuntimeControlService.class);
		when(controls.snapshot()).thenReturn(new RuntimeControlSnapshot(allPaused, false, false, false,
				allPaused, allPaused, allPaused, Map.of()));
		return new DatabaseMaintenanceService(audit,
				mock(DatabaseMaintenanceTransaction.class), controls, enabled, 1800,
				"test-preview-secret");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	private DatabaseMaintenanceRequest request(String token) {
		DatabaseMaintenanceRequest request = new DatabaseMaintenanceRequest();
		request.setPreviewToken(token);
		return request;
	}
}
