package com.flower.spirit.platform;

import java.nio.file.Path;
import java.util.Objects;

public final class WorkDownloadRequest {

	private final Path outputDirectory;
	private final boolean replaceExisting;

	public WorkDownloadRequest(Path outputDirectory, boolean replaceExisting) {
		this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
		this.replaceExisting = replaceExisting;
	}

	public Path getOutputDirectory() {
		return outputDirectory;
	}

	public boolean isReplaceExisting() {
		return replaceExisting;
	}
}
