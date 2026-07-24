package com.flower.spirit.service;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.platform.WorkMetadataValidationException;

@Service
public class MediaPathService {

	private final Path localRoot;
	private final String publicRoot;

	@Autowired
	public MediaPathService(@Value("${file.save.path}") String localRoot,
			@Value("${file.save:}") String configuredPublicRoot,
			@Value("${file.save.staticAccessPath:/cos/**}") String staticAccessPath) {
		this.localRoot = Path.of(localRoot).toAbsolutePath().normalize();
		this.publicRoot = normalizePublicRoot(hasText(configuredPublicRoot)
				? configuredPublicRoot : staticAccessPath);
	}

	MediaPathService(Path localRoot, String publicRoot) {
		this.localRoot = localRoot.toAbsolutePath().normalize();
		this.publicRoot = normalizePublicRoot(publicRoot);
	}

	public String toPublicPath(Path localPath) {
		if (localPath == null) return null;
		Path normalized = localPath.toAbsolutePath().normalize();
		if (!normalized.startsWith(localRoot)) {
			throw new WorkMetadataValidationException("media path is outside configured storage root");
		}
		String relative = localRoot.relativize(normalized).toString().replace('\\', '/');
		return relative.isEmpty() ? publicRoot : publicRoot + "/" + relative;
	}

	public Path toLocalPath(String publicPath) {
		if (!hasText(publicPath)) return null;
		String normalized = publicPath.trim().replace('\\', '/');
		if (!normalized.equals(publicRoot) && !normalized.startsWith(publicRoot + "/")) return null;
		String relative = normalized.substring(publicRoot.length());
		while (relative.startsWith("/")) relative = relative.substring(1);
		Path local = localRoot.resolve(relative).normalize();
		return local.startsWith(localRoot) ? local : null;
	}

	public Path requireOwnedLocalPath(String storedPath) {
		if (!hasText(storedPath)) {
			throw new WorkMetadataValidationException("media path is empty");
		}
		Path publicLocal = toLocalPath(storedPath);
		Path candidate = publicLocal != null ? publicLocal : Path.of(storedPath.trim());
		if (!candidate.isAbsolute()) {
			candidate = localRoot.resolve(candidate);
		}
		Path normalized = candidate.toAbsolutePath().normalize();
		if (normalized.equals(localRoot) || !normalized.startsWith(localRoot)) {
			throw new WorkMetadataValidationException("media path is outside configured storage root");
		}
		return normalized;
	}

	Path localRoot() {
		return localRoot;
	}

	private static String normalizePublicRoot(String value) {
		String normalized = hasText(value) ? value.trim().replace('\\', '/') : "/cos";
		if (normalized.endsWith("/**")) normalized = normalized.substring(0, normalized.length() - 3);
		while (normalized.endsWith("/") && normalized.length() > 1) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if (!normalized.startsWith("/")) normalized = "/" + normalized;
		return normalized;
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
