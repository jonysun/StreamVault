package com.flower.spirit.service;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.DateUtils;
import com.flower.spirit.utils.DouUtil;

@Service
public class PublishTimeBackfillService {

	private static final Logger logger = LoggerFactory.getLogger(PublishTimeBackfillService.class);

	@Autowired
	private VideoDataDao videoDataDao;

	@Autowired
	private GraphicContentDao graphicContentDao;

	public void backfillDouyinPublishTime() {
		int videoUpdated = 0;
		int videoFailed = 0;
		List<VideoDataEntity> videos = videoDataDao.findByVideoplatformAndPublishtimeIsNull("抖音");
		for (VideoDataEntity video : videos) {
			try {
				Map<String, String> data = DouUtil.downVideo(video.getOriginaladdress());
				if (data != null) {
					String publishTime = formatPublishTimeFromEpochSeconds(data.get("create_time"));
					if (publishTime != null) {
						video.setPublishtime(publishTime);
						videoDataDao.save(video);
						videoUpdated++;
						continue;
					}
				}
				videoFailed++;
				logger.warn("[PublishBackfill] video unresolved id={} originaladdress={}", video.getId(), video.getOriginaladdress());
			} catch (Exception e) {
				videoFailed++;
				logger.error("[PublishBackfill] video backfill failed id={} originaladdress={}", video.getId(), video.getOriginaladdress(), e);
			}
		}

		int graphicUpdated = 0;
		int graphicFailed = 0;
		List<GraphicContentEntity> graphics = graphicContentDao.findByPlatformAndPublishtimeIsNull("douyin");
		for (GraphicContentEntity item : graphics) {
			try {
				Map<String, String> data = DouUtil.downVideo(item.getOriginaladdress());
				if (data != null) {
					String publishTime = formatPublishTimeFromEpochSeconds(data.get("create_time"));
					if (publishTime != null) {
						item.setPublishtime(publishTime);
						graphicContentDao.save(item);
						graphicUpdated++;
						continue;
					}
				}
				graphicFailed++;
				logger.warn("[PublishBackfill] graphic unresolved id={} originaladdress={}", item.getId(), item.getOriginaladdress());
			} catch (Exception e) {
				graphicFailed++;
				logger.error("[PublishBackfill] graphic backfill failed id={} originaladdress={}", item.getId(), item.getOriginaladdress(), e);
			}
		}

		logger.info("[PublishBackfill] douyin backfill finish videoUpdated={} videoFailed={} graphicUpdated={} graphicFailed={}",
				videoUpdated, videoFailed, graphicUpdated, graphicFailed);
	}

	private String formatPublishTimeFromEpochSeconds(String epochSeconds) {
		if (epochSeconds == null || epochSeconds.trim().isEmpty()) {
			return null;
		}
		try {
			long sec = Long.parseLong(epochSeconds.trim());
			return DateUtils.formatDateTime(new Date(sec * 1000L));
		} catch (Exception e) {
			return null;
		}
	}
}
