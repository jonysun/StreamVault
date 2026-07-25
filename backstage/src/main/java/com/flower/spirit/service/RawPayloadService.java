package com.flower.spirit.service;

import org.springframework.stereotype.Service;

import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.platform.RawMetadataSanitizer;

@Service
public class RawPayloadService {

	public String loadVideoRawPayload(VideoDataEntity video) {
		if (video == null) return null;
		if (hasText(video.getJsonData())) return video.getJsonData();
		return hasText(video.getVideoinfo()) ? video.getVideoinfo() : null;
	}

	public String loadGraphicRawPayload(GraphicContentEntity graphic) {
		return graphic != null && hasText(graphic.getJsonData()) ? graphic.getJsonData() : null;
	}

	public void storeVideoRawPayload(VideoDataEntity video, String payload) {
		if (video == null || payload == null) return;
		String sanitized = RawMetadataSanitizer.sanitize(payload);
		if (sanitized != null) video.setJsonData(sanitized);
	}

	public void storeGraphicRawPayload(GraphicContentEntity graphic, String payload) {
		if (graphic == null || payload == null) return;
		String sanitized = RawMetadataSanitizer.sanitize(payload);
		if (sanitized != null) graphic.setJsonData(sanitized);
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
