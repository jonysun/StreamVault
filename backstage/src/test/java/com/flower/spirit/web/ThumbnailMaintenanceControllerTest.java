package com.flower.spirit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.flower.spirit.config.Global;
import com.flower.spirit.service.WorkRefreshService;

class ThumbnailMaintenanceControllerTest {

	private final String originalToken = Global.apptoken;
	private final WorkRefreshService service = mock(WorkRefreshService.class);
	private final ThumbnailMaintenanceController controller = new ThumbnailMaintenanceController(service);

	@AfterEach
	void restoreToken() { Global.apptoken = originalToken; }

	@Test
	void localAuthenticatedRequestReturnsCover() {
		Global.apptoken = "private-token";
		MockHttpServletRequest request = request("127.0.0.1");
		when(service.resolveVideoCover(7)).thenReturn("https://cdn.example/cover.jpg");
		var response = controller.source(request, "private-token", 7);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("coverUrl", "https://cdn.example/cover.jpg");
	}

	@Test
	void remoteAndInvalidTokensNeverReachProvider() {
		Global.apptoken = "private-token";
		MockHttpServletRequest remote = request("192.168.1.10");
		remote.addHeader("X-Forwarded-For", "127.0.0.1");
		assertThat(controller.source(remote, "private-token", 7).getStatusCode().value()).isEqualTo(403);
		assertThat(controller.source(request("::1"), null, 7).getStatusCode().value()).isEqualTo(403);
		assertThat(controller.source(request("127.0.0.1"), "wrong", 7).getStatusCode().value()).isEqualTo(403);
		Global.apptoken = "";
		assertThat(controller.source(request("127.0.0.1"), "", 7).getStatusCode().value()).isEqualTo(403);
		verifyNoInteractions(service);
	}

	@Test
	void providerErrorDoesNotExposeSignedUrls() {
		Global.apptoken = "private-token";
		when(service.resolveVideoCover(7)).thenThrow(new IllegalStateException("https://secret.example?token=private"));
		var response = controller.source(request("127.0.0.1"), "private-token", 7);
		assertThat(response.getStatusCode().value()).isEqualTo(502);
		assertThat(response.getBody().toString()).doesNotContain("private", "secret.example");
	}

	private MockHttpServletRequest request(String remote) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remote);
		return request;
	}
}
