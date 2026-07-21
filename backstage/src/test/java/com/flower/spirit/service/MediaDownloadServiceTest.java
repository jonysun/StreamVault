package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.platform.WorkContentType;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.platform.WorkParseRequest;
import com.flower.spirit.platform.adapter.PlatformWorkAdapter;

class MediaDownloadServiceTest {

	@TempDir
	Path tempDir;

	private final MediaDownloadService service = new MediaDownloadService();

	@Test
	void verifiesPromotesAndPreservesOrderedMediaPaths() {
		PlatformWorkAdapter adapter = adapter((metadata, request) -> {
			Path second = write(request.getOutputDirectory().resolve("second.mp4"), "video");
			Path first = write(request.getOutputDirectory().resolve("first.jpg"), "image");
			return DownloadResult.completed(List.of(
					resource(1, WorkMediaResource.Type.VIDEO, second),
					resource(0, WorkMediaResource.Type.IMAGE, first)));
		});
		Path target = tempDir.resolve("work-1");

		MediaDownloadService.DownloadOutcome result = service.download(adapter, metadata(),
				new WorkDownloadRequest(target, false));

		assertThat(result.status()).isEqualTo(DownloadResult.Status.COMPLETED);
		assertThat(result.mediaResources()).extracting(WorkMediaResource::getOrder).containsExactly(0, 1);
		assertThat(result.mediaResources()).extracting(resource -> resource.getLocalPath().getFileName().toString())
				.containsExactly("first.jpg", "second.mp4");
		assertThat(Files.isRegularFile(target.resolve("first.jpg"))).isTrue();
		assertThat(stagingDirectories()).isEmpty();
	}

	@Test
	void rejectsEmptyFilesAndRemovesStagingWithoutCreatingTarget() {
		PlatformWorkAdapter adapter = adapter((metadata, request) -> {
			Path empty = request.getOutputDirectory().resolve("empty.mp4");
			try {
				Files.createFile(empty);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			return DownloadResult.completed(List.of(resource(0, WorkMediaResource.Type.VIDEO, empty)));
		});
		Path target = tempDir.resolve("work-empty");

		assertThatThrownBy(() -> service.download(adapter, metadata(), new WorkDownloadRequest(target, false)))
				.isInstanceOf(WorkMetadataValidationException.class)
				.hasMessageContaining("empty");
		assertThat(Files.exists(target)).isFalse();
		assertThat(stagingDirectories()).isEmpty();
	}

	@Test
	void queuedDownloadKeepsStagingAndDoesNotPromoteTarget() {
		PlatformWorkAdapter adapter = adapter((metadata, request) -> DownloadResult.queued("aria2 task accepted"));
		Path target = tempDir.resolve("work-queued");

		MediaDownloadService.DownloadOutcome result = service.download(adapter, metadata(),
				new WorkDownloadRequest(target, false));

		assertThat(result.status()).isEqualTo(DownloadResult.Status.QUEUED);
		assertThat(result.workingDirectory()).exists().isDirectory();
		assertThat(Files.exists(target)).isFalse();
	}

	private List<Path> stagingDirectories() {
		try (var paths = Files.list(tempDir)) {
			return paths.filter(path -> path.getFileName().toString().contains(".staging-")).toList();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Path write(Path path, String value) {
		try {
			return Files.writeString(path, value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private WorkMetadata metadata() {
		return WorkMetadata.builder()
				.platform(PlatformCatalog.requireByKey("youtube"))
				.workId("work-1")
				.contentType(WorkContentType.MIXED)
				.mediaResources(List.of(new WorkMediaResource(0, WorkMediaResource.Type.VIDEO,
						"https://cdn.example/video.mp4", null, "mp4", Map.of())))
				.build();
	}

	private WorkMediaResource resource(int order, WorkMediaResource.Type type, Path localPath) {
		return new WorkMediaResource(order, type, null, localPath, null, Map.of());
	}

	private PlatformWorkAdapter adapter(DownloadBehavior behavior) {
		return new PlatformWorkAdapter() {
			@Override public String platformKey() { return "youtube"; }
			@Override public boolean supports(String input) { return true; }
			@Override public WorkMetadata parse(WorkParseRequest request) { return metadata(); }
			@Override public DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request) {
				return behavior.download(metadata, request);
			}
		};
	}

	@FunctionalInterface
	private interface DownloadBehavior {
		DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request);
	}
}
