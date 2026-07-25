package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.VideoDataEntity;

class VideoDataServicePlayVideoTest {

	@TempDir
	Path tempDir;

	private VideoDataService service;
	private VideoDataDao videoDataDao;

	@BeforeEach
	void setUp() {
		service = new VideoDataService();
		videoDataDao = mock(VideoDataDao.class);
		ReflectionTestUtils.setField(service, "videoDataDao", videoDataDao);
	}

	@Test
	void servesFirstRangeWithPartialHeaders() throws Exception {
		stubVideo(1, Files.write(tempDir.resolve("first.mp4"), bytes(32)));
		HttpHeaders request = new HttpHeaders();
		request.set(HttpHeaders.RANGE, "bytes=0-7");

		ResponseEntity<StreamingResponseBody> response = service.playVideo(request, "1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
		assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-7/32");
		assertThat(response.getHeaders().getContentLength()).isEqualTo(8);
		assertThat(readBody(response)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
	}

	@Test
	void servesMiddleRangeWithoutLeakingRemainingFile() throws Exception {
		stubVideo(2, Files.write(tempDir.resolve("middle.mp4"), bytes(32)));
		HttpHeaders request = new HttpHeaders();
		request.set(HttpHeaders.RANGE, "bytes=11-15");

		ResponseEntity<StreamingResponseBody> response = service.playVideo(request, "2");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 11-15/32");
		assertThat(readBody(response)).containsExactly(11, 12, 13, 14, 15);
	}

	@Test
	void rejectsInvalidIdAndUnsatisfiableRange() throws Exception {
		assertThat(service.playVideo(new HttpHeaders(), "not-a-number").getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		stubVideo(3, Files.write(tempDir.resolve("short.mp4"), bytes(8)));
		HttpHeaders request = new HttpHeaders();
		request.set(HttpHeaders.RANGE, "bytes=20-30");
		ResponseEntity<StreamingResponseBody> response = service.playVideo(request, "3");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */8");
	}

	@Test
	void rejectsMalformedRangeWithoutServerError() throws Exception {
		stubVideo(5, Files.write(tempDir.resolve("malformed.mp4"), bytes(8)));
		HttpHeaders request = new HttpHeaders();
		request.set(HttpHeaders.RANGE, "bytes=invalid");

		ResponseEntity<StreamingResponseBody> response = service.playVideo(request, "5");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */8");
	}

	@Test
	void missingMediaFileReturnsNotFound() throws Exception {
		stubVideo(4, tempDir.resolve("missing.mp4"));
		assertThat(service.playVideo(new HttpHeaders(), "4").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private void stubVideo(int id, Path path) {
		VideoDataEntity video = new VideoDataEntity();
		video.setId(id);
		video.setVideoaddr(path.toString());
		when(videoDataDao.findById(id)).thenReturn(Optional.of(video));
	}

	private byte[] readBody(ResponseEntity<StreamingResponseBody> response) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		response.getBody().writeTo(output);
		return output.toByteArray();
	}

	private byte[] bytes(int size) {
		byte[] value = new byte[size];
		for (int index = 0; index < size; index++) {
			value[index] = (byte) index;
		}
		return value;
	}
}
