package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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

	private DatabaseMaintenanceService service(boolean enabled, boolean allPaused) {
		RuntimeControlService controls = mock(RuntimeControlService.class);
		when(controls.snapshot()).thenReturn(new RuntimeControlSnapshot(allPaused, false, false, false,
				allPaused, allPaused, allPaused, Map.of()));
		return new DatabaseMaintenanceService(mock(DatabaseAuditService.class),
				mock(DatabaseMaintenanceTransaction.class), controls, mock(JdbcTemplate.class), enabled, 1800,
				"test-preview-secret");
	}

	private DatabaseMaintenanceRequest request(String token) {
		DatabaseMaintenanceRequest request = new DatabaseMaintenanceRequest();
		request.setPreviewToken(token);
		return request;
	}
}
