package com.flower.spirit.platform.adapter;

import java.nio.file.Path;
import java.util.List;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkParseRequest;

public interface PlatformWorkAdapter {

	@FunctionalInterface
	interface OperationScope extends AutoCloseable {
		OperationScope NOOP = () -> { };

		@Override
		void close();
	}

	String platformKey();

	boolean supports(String input);

	WorkMetadata parse(WorkParseRequest request);

	default List<WorkMetadata> parseAll(WorkParseRequest request) {
		return List.of(parse(request));
	}

	default OperationScope openOperationScope(String purpose) {
		return OperationScope.NOOP;
	}

	DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request);

	default void postProcessDownloaded(WorkMetadata metadata, Path outputDirectory,
			List<WorkMediaResource> downloadedResources) {
	}
}
