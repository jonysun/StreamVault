package com.flower.spirit.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.VideoDataEntity;

@Service
public class Mp4FaststartMaintenanceService {

	private static final int MAX_PREVIEW_LIMIT = 500;
	private static final int MAX_APPLY_IDS = 50;

	private final JdbcTemplate jdbcTemplate;
	private final VideoDataDao videoDataDao;
	private final Mp4FaststartService faststartService;
	private final HlsTranscodeService hlsTranscodeService;

	public Mp4FaststartMaintenanceService(JdbcTemplate jdbcTemplate, VideoDataDao videoDataDao,
			Mp4FaststartService faststartService, HlsTranscodeService hlsTranscodeService) {
		this.jdbcTemplate = jdbcTemplate;
		this.videoDataDao = videoDataDao;
		this.faststartService = faststartService;
		this.hlsTranscodeService = hlsTranscodeService;
	}

	public PreviewPage preview(Integer afterId, Integer requestedLimit) {
		int cursor = Math.max(0, afterId == null ? 0 : afterId);
		int limit = Math.max(1, Math.min(MAX_PREVIEW_LIMIT, requestedLimit == null ? 100 : requestedLimit));
		List<PreviewItem> items = jdbcTemplate.query(
				"SELECT id, videoaddr FROM biz_video WHERE id > ? AND lower(videoaddr) LIKE '%.mp4' ORDER BY id LIMIT ?",
				(rs, rowNum) -> inspect(rs.getInt("id"), rs.getString("videoaddr")), cursor, limit);
		int nextAfterId = items.isEmpty() ? cursor : items.get(items.size() - 1).id();
		return new PreviewPage(items, nextAfterId, items.size() == limit);
	}

	public ApplyResult apply(List<Integer> requestedIds) {
		LinkedHashSet<Integer> ids = new LinkedHashSet<>();
		if (requestedIds != null) {
			for (Integer id : requestedIds) {
				if (id != null && id > 0) {
					ids.add(id);
				}
				if (ids.size() >= MAX_APPLY_IDS) {
					break;
				}
			}
		}
		List<ApplyItem> items = new ArrayList<>();
		for (Integer id : ids) {
			if (hlsTranscodeService.isRunning(id)) {
				items.add(new ApplyItem(id, "SKIPPED_HLS_RUNNING", null));
				continue;
			}
			VideoDataEntity video = videoDataDao.findById(id).orElse(null);
			Path path = video == null ? null : toPath(video.getVideoaddr());
			Mp4FaststartService.FaststartState state = faststartService.inspect(path);
			if (state == Mp4FaststartService.FaststartState.FASTSTART) {
				items.add(new ApplyItem(id, "ALREADY_FASTSTART", path.toString()));
				continue;
			}
			if (state == Mp4FaststartService.FaststartState.MISSING || path == null) {
				items.add(new ApplyItem(id, "MISSING", path == null ? null : path.toString()));
				continue;
			}
			try {
				items.add(new ApplyItem(id, faststartService.optimize(path) ? "OPTIMIZED" : "FAILED", path.toString()));
			} catch (Exception error) {
				items.add(new ApplyItem(id, "FAILED: " + error.getClass().getSimpleName(), path.toString()));
			}
		}
		long optimized = items.stream().filter(item -> "OPTIMIZED".equals(item.status())).count();
		return new ApplyResult(items, optimized);
	}

	private PreviewItem inspect(int id, String rawPath) {
		Path path = toPath(rawPath);
		Mp4FaststartService.FaststartState state = faststartService.inspect(path);
		long bytes = 0;
		try {
			bytes = path != null && Files.isRegularFile(path) ? Files.size(path) : 0;
		} catch (Exception ignored) {
		}
		return new PreviewItem(id, rawPath, bytes, state.name(),
				state == Mp4FaststartService.FaststartState.NEEDS_OPTIMIZATION);
	}

	private Path toPath(String rawPath) {
		try {
			return rawPath == null || rawPath.isBlank() ? null : Path.of(rawPath);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	public record PreviewItem(int id, String path, long bytes, String state, boolean needsOptimization) {
	}

	public record PreviewPage(List<PreviewItem> items, int nextAfterId, boolean hasMore) {
	}

	public record ApplyItem(int id, String status, String path) {
	}

	public record ApplyResult(List<ApplyItem> items, long optimized) {
	}
}
