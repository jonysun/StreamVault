package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.flower.spirit.platform.WorkMediaResource;

class Mp4FaststartServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void atomicallyReplacesMp4AndLeavesImagesUntouched() throws Exception {
		Path video = Files.writeString(tempDir.resolve("video.mp4"), "original");
		Path image = Files.writeString(tempDir.resolve("image.jpg"), "image");
		Mp4FaststartService service = new Mp4FaststartService() {
			@Override
			protected int executeFaststartCommand(Path source, Path output) throws java.io.IOException {
				Files.writeString(output, "optimized");
				return 0;
			}
		};
		ReflectionTestUtils.setField(service, "enabled", true);

		Mp4FaststartService.FaststartResult result = service.optimizeNewDownloads(List.of(
				resource(WorkMediaResource.Type.VIDEO, video),
				resource(WorkMediaResource.Type.IMAGE, image)), tempDir);

		assertThat(result.candidates()).isEqualTo(1);
		assertThat(result.optimized()).isEqualTo(1);
		assertThat(result.failed()).isZero();
		assertThat(video).hasContent("optimized");
		assertThat(image).hasContent("image");
	}

	@Test
	void failedFaststartPreservesOriginalMp4() throws Exception {
		Path video = Files.writeString(tempDir.resolve("failed.mp4"), "original");
		Mp4FaststartService service = new Mp4FaststartService() {
			@Override
			protected int executeFaststartCommand(Path source, Path output) {
				return 9;
			}
		};
		ReflectionTestUtils.setField(service, "enabled", true);

		Mp4FaststartService.FaststartResult result = service.optimizeNewDownloads(
				List.of(resource(WorkMediaResource.Type.VIDEO, video)), tempDir);

		assertThat(result.failed()).isEqualTo(1);
		assertThat(video).hasContent("original");
	}

	@Test
	void detectsMoovBeforeAndAfterMediaDataWithoutReadingPayload() throws Exception {
		Mp4FaststartService service = new Mp4FaststartService();
		Path fast = Files.write(tempDir.resolve("fast.mp4"), concat(atom("ftyp", 4), atom("moov", 4), atom("mdat", 64)));
		Path slow = Files.write(tempDir.resolve("slow.mp4"), concat(atom("ftyp", 4), atom("mdat", 64), atom("moov", 4)));

		assertThat(service.inspect(fast)).isEqualTo(Mp4FaststartService.FaststartState.FASTSTART);
		assertThat(service.inspect(slow)).isEqualTo(Mp4FaststartService.FaststartState.NEEDS_OPTIMIZATION);
	}

	private byte[] atom(String type, int payloadSize) {
		ByteBuffer buffer = ByteBuffer.allocate(8 + payloadSize);
		buffer.putInt(8 + payloadSize);
		buffer.put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		buffer.put(new byte[payloadSize]);
		return buffer.array();
	}

	private byte[] concat(byte[]... values) {
		int length = java.util.Arrays.stream(values).mapToInt(value -> value.length).sum();
		ByteBuffer buffer = ByteBuffer.allocate(length);
		for (byte[] value : values) {
			buffer.put(value);
		}
		return buffer.array();
	}

	private WorkMediaResource resource(WorkMediaResource.Type type, Path path) {
		return new WorkMediaResource(0, type, null, path, null, Map.of());
	}
}
