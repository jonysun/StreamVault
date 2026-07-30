package com.flower.spirit.service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flower.spirit.platform.WorkMediaResource;
import com.flower.spirit.process.ControlledProcessExecutor;

@Service
public class Mp4FaststartService {

	private static final Logger logger = LoggerFactory.getLogger(Mp4FaststartService.class);
	private static final Duration FFMPEG_TIMEOUT = Duration.ofHours(2);
	private static final ControlledProcessExecutor PROCESS_EXECUTOR = new ControlledProcessExecutor();

	@Value("${streamvault.media.faststart.enabled:true}")
	private boolean enabled;

	public FaststartResult optimizeNewDownloads(List<WorkMediaResource> resources, Path stagingDirectory) {
		if (!enabled || resources == null || resources.isEmpty()) {
			return new FaststartResult(0, 0, 0);
		}
		int candidates = 0;
		int optimized = 0;
		int failed = 0;
		for (WorkMediaResource resource : resources) {
			Path media = resource == null ? null : resource.getLocalPath();
			if (resource == null || resource.getType() != WorkMediaResource.Type.VIDEO || !isMp4(media)) {
				continue;
			}
			Path normalized = media.toAbsolutePath().normalize();
			if (stagingDirectory != null && !normalized.startsWith(stagingDirectory.toAbsolutePath().normalize())) {
				logger.warn("[Faststart] skipped media outside staging path={}", normalized);
				continue;
			}
			candidates++;
			try {
				if (inspect(normalized) == FaststartState.FASTSTART) {
					continue;
				}
				if (optimize(normalized)) {
					optimized++;
				} else {
					failed++;
				}
			} catch (Exception error) {
				failed++;
				logger.warn("[Faststart] optimization failed path={} error={}", normalized,
						error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
			}
		}
		return new FaststartResult(candidates, optimized, failed);
	}

	public boolean optimize(Path source) throws IOException {
		if (!Files.isRegularFile(source) || Files.size(source) <= 0) {
			return false;
		}
		String fileName = source.getFileName().toString();
		Path output = source.resolveSibling("." + fileName + ".faststart-" + UUID.randomUUID() + ".mp4");
		Path backup = source.resolveSibling("." + fileName + ".pre-faststart-" + UUID.randomUUID());
		long startedAt = System.nanoTime();
		try {
			int exitCode = executeFaststartCommand(source, output);
			if (exitCode != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0) {
				logger.warn("[Faststart] ffmpeg did not produce output path={} exitCode={}", source, exitCode);
				return false;
			}
			move(source, backup);
			try {
				move(output, source);
			} catch (IOException | RuntimeException error) {
				move(backup, source);
				throw error;
			}
			Files.deleteIfExists(backup);
			logger.info("[Faststart] optimized path={} bytes={} durationMs={}", source, Files.size(source),
					(System.nanoTime() - startedAt) / 1_000_000L);
			return true;
		} finally {
			Files.deleteIfExists(output);
			if (Files.exists(backup) && !Files.exists(source)) {
				move(backup, source);
			}
		}
	}

	public FaststartState inspect(Path source) {
		if (source == null || !Files.isRegularFile(source)) {
			return FaststartState.MISSING;
		}
		try (RandomAccessFile input = new RandomAccessFile(source.toFile(), "r")) {
			long length = input.length();
			long offset = 0;
			boolean mediaDataSeen = false;
			int atoms = 0;
			while (offset + 8 <= length && atoms++ < 128) {
				input.seek(offset);
				long atomSize = Integer.toUnsignedLong(input.readInt());
				byte[] typeBytes = new byte[4];
				input.readFully(typeBytes);
				String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
				long headerSize = 8;
				if (atomSize == 1) {
					atomSize = input.readLong();
					headerSize = 16;
				} else if (atomSize == 0) {
					atomSize = length - offset;
				}
				if (atomSize < headerSize || offset + atomSize > length) {
					return FaststartState.UNKNOWN;
				}
				if ("moov".equals(type)) {
					return mediaDataSeen ? FaststartState.NEEDS_OPTIMIZATION : FaststartState.FASTSTART;
				}
				if ("mdat".equals(type)) {
					mediaDataSeen = true;
				}
				offset += atomSize;
			}
		} catch (IOException error) {
			logger.debug("[Faststart] inspection failed path={} error={}", source, error.toString());
		}
		return FaststartState.UNKNOWN;
	}

	protected int executeFaststartCommand(Path source, Path output) throws IOException {
		try {
			ControlledProcessExecutor.Result result = PROCESS_EXECUTOR.execute(
					List.of("ffmpeg", "-y", "-i", source.toString(), "-c", "copy",
							"-movflags", "+faststart", output.toString()),
					FFMPEG_TIMEOUT, "ffmpeg-faststart");
			if (result.timedOut()) {
				throw new IOException("ffmpeg faststart timeout after " + FFMPEG_TIMEOUT.toHours() + " hours");
			}
			if (result.exitCode() != 0) {
				logger.warn("[Faststart] ffmpeg failed exitCode={}", result.exitCode());
			}
			return result.exitCode();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("ffmpeg faststart interrupted", error);
		}
	}

	private boolean isMp4(Path path) {
		return path != null && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4");
	}

	private void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException error) {
			Files.move(source, target);
		}
	}

	public record FaststartResult(int candidates, int optimized, int failed) {
	}

	public enum FaststartState {
		FASTSTART,
		NEEDS_OPTIMIZATION,
		UNKNOWN,
		MISSING
	}
}
