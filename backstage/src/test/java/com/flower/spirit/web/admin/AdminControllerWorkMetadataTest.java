package com.flower.spirit.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dto.UpdateWorkMetadataRequest;
import com.flower.spirit.dto.WorkOperationRequest;
import com.flower.spirit.entity.UserEntity;
import com.flower.spirit.service.WorkMetadataEditService;
import com.flower.spirit.service.WorkMetadataEditService.EditResult;
import com.flower.spirit.service.WorkRedownloadService;
import com.flower.spirit.service.WorkRefreshService;

@ExtendWith(MockitoExtension.class)
class AdminControllerWorkMetadataTest {

	@Mock private WorkMetadataEditService editService;
	@Mock private WorkRefreshService refreshService;
	@Mock private WorkRedownloadService redownloadService;

	private AdminController controller;
	private UpdateWorkMetadataRequest updateRequest;

	@BeforeEach
	void setUp() {
		controller = new AdminController();
		ReflectionTestUtils.setField(controller, "workMetadataEditService", editService);
		ReflectionTestUtils.setField(controller, "workRefreshService", refreshService);
		ReflectionTestUtils.setField(controller, "workRedownloadService", redownloadService);
		updateRequest = new UpdateWorkMetadataRequest();
		updateRequest.setWorkType("video");
		updateRequest.setId(7);
	}

	@Test
	void rejectsUnauthenticatedMetadataUpdate() {
		AjaxEntity response = controller.updateWorkMetadata(updateRequest, new MockHttpServletRequest());

		assertThat(response.getResCode()).isEqualTo(Global.ajax_login_err);
		verify(editService, never()).update(any(), any());
	}

	@Test
	void derivesEditorFromAuthenticatedSession() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		UserEntity user = new UserEntity();
		user.setUsername("admin-user");
		request.getSession().setAttribute(Global.user_session_key, user);
		EditResult result = new EditResult("video", 7, false, null, null);
		when(editService.update(updateRequest, "admin-user")).thenReturn(result);

		AjaxEntity response = controller.updateWorkMetadata(updateRequest, request);

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(response.getRecord()).isSameAs(result);
		verify(editService).update(eq(updateRequest), eq("admin-user"));
	}

	@Test
	void rejectsUnauthenticatedRefreshAndRedownload() {
		WorkOperationRequest operation = operationRequest();

		AjaxEntity refresh = controller.refreshWorkMetadata(operation, new MockHttpServletRequest());
		AjaxEntity redownload = controller.redownloadWork(operation, new MockHttpServletRequest());

		assertThat(refresh.getResCode()).isEqualTo(Global.ajax_login_err);
		assertThat(redownload.getResCode()).isEqualTo(Global.ajax_login_err);
		verify(refreshService, never()).refresh(any());
		verify(redownloadService, never()).redownload(any());
	}

	@Test
	void acceptsAuthenticatedRefreshAndRedownload() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		UserEntity user = new UserEntity();
		user.setUsername("admin-user");
		request.getSession().setAttribute(Global.user_session_key, user);
		WorkOperationRequest operation = operationRequest();

		AjaxEntity refresh = controller.refreshWorkMetadata(operation, request);
		AjaxEntity redownload = controller.redownloadWork(operation, request);

		assertThat(refresh.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(redownload.getResCode()).isEqualTo(Global.ajax_success);
		verify(refreshService).refresh(operation);
		verify(redownloadService).redownload(operation);
	}

	private WorkOperationRequest operationRequest() {
		WorkOperationRequest request = new WorkOperationRequest();
		request.setWorkType("video");
		request.setId(7);
		return request;
	}
}
