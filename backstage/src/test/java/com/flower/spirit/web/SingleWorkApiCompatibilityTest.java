package com.flower.spirit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.service.AnalysisService;
import com.flower.spirit.web.admin.AdminController;

class SingleWorkApiCompatibilityTest {

	@Test
	void processingVideosKeepsLegacyRecordAndAddsSubmissionFields() throws Exception {
		AnalysisService analysis = mock(AnalysisService.class);
		AnalysisService.SubmissionResult submission =
				new AnalysisService.SubmissionResult(77, "youtube", "new", "submitted");
		when(analysis.submitProcessingVideos("token", "https://youtu.be/work")).thenReturn(submission);
		when(analysis.applySubmission(org.mockito.ArgumentMatchers.any(AjaxEntity.class), same(submission)))
				.thenAnswer(invocation -> {
					AjaxEntity response = invocation.getArgument(0);
					response.setTaskId(77);
					response.setPlatformKey("youtube");
					response.setMode("new");
					response.setStatus("submitted");
					return response;
				});
		ApiController controller = new ApiController();
		ReflectionTestUtils.setField(controller, "analysisService", analysis);

		AjaxEntity response = controller.processingVideos("token", "https://youtu.be/work");

		assertThat(response.getResCode()).isEqualTo(Global.ajax_success);
		assertThat(response.getRecord()).isEqualTo("");
		assertThat(response.getTaskId()).isEqualTo(77);
		assertThat(response.getPlatformKey()).isEqualTo("youtube");
	}

	@Test
	void publicDirectDataKeepsTokenAndVideoParameters() {
		AnalysisService analysis = mock(AnalysisService.class);
		AjaxEntity expected = new AjaxEntity(Global.ajax_success, "parsed", java.util.Map.of("videoUrl", "url"));
		when(analysis.directData("token", "video", "http")).thenReturn(expected);
		ApiController controller = new ApiController();
		ReflectionTestUtils.setField(controller, "analysisService", analysis);

		assertThat(controller.directData("token", "video")).isSameAs(expected);
		verify(analysis).directData("token", "video", "http");
	}

	@Test
	void adminDirectDataKeepsEntityAndTypeParameter() {
		AnalysisService analysis = mock(AnalysisService.class);
		VideoDataEntity video = new VideoDataEntity();
		video.setOriginaladdress("https://youtu.be/work");
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setParameter("type", "1");
		AjaxEntity expected = new AjaxEntity(Global.ajax_success, "submitted", null);
		when(analysis.directData(video, request)).thenReturn(expected);
		AdminController controller = new AdminController();
		ReflectionTestUtils.setField(controller, "analysisService", analysis);

		assertThat(controller.directData(video, request)).isSameAs(expected);
		verify(analysis).directData(video, request);
	}
}
