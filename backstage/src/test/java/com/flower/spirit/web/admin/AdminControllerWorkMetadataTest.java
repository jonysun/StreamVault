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
import com.flower.spirit.entity.UserEntity;
import com.flower.spirit.service.WorkMetadataEditService;
import com.flower.spirit.service.WorkMetadataEditService.EditResult;

@ExtendWith(MockitoExtension.class)
class AdminControllerWorkMetadataTest {

	@Mock private WorkMetadataEditService editService;

	private AdminController controller;
	private UpdateWorkMetadataRequest updateRequest;

	@BeforeEach
	void setUp() {
		controller = new AdminController();
		ReflectionTestUtils.setField(controller, "workMetadataEditService", editService);
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
}
