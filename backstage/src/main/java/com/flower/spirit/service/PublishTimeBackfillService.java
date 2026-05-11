package com.flower.spirit.service;

import java.util.Date;
import java.util.List;

import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.DateUtils;

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
				JSONObject data = fetchDouyinVideoById(video.getVideoid());
				if (data != null) {
					String publishTime = formatPublishTimeFromEpochSeconds(data.getString("create_time"));
					if (publishTime != null) {
						video.setPublishtime(publishTime);
						String uid = data.get("uid") == null ? null : data.get("uid").toString();
						if (uid != null && !uid.trim().isEmpty()) {
							video.setSourceurl("https://www.douyin.com/user/" + uid + "?modal_id=" + video.getVideoid());
						}
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
				JSONObject data = fetchDouyinPostById(item.getVideoid());
				if (data != null) {
					JSONObject awemeDetail = data.getJSONObject("aweme_detail");
					String publishTime = awemeDetail == null ? null : formatPublishTimeFromEpochSeconds(awemeDetail.getString("create_time"));
					if (publishTime != null) {
						item.setPublishtime(publishTime);
						if (awemeDetail != null) {
							JSONObject author = awemeDetail.getJSONObject("author");
							String uid = author == null ? null : author.getString("sec_uid");
							if (uid == null || uid.trim().isEmpty()) {
								uid = author == null ? null : author.getString("uid");
							}
							if (uid != null && !uid.trim().isEmpty()) {
								item.setSourceurl("https://www.douyin.com/user/" + uid + "?modal_id=" + item.getVideoid());
							}
						}
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

	private JSONObject fetchDouyinVideoById(String awemeId) {
		if (awemeId == null || awemeId.trim().isEmpty()) {
			return null;
		}
		String output = CommandUtil.f2cmd(com.flower.spirit.config.Global.tiktokCookie, awemeId, "fetch_video", null, null, null, null);
		if (output == null || output.isBlank()) {
			return null;
		}
		try {
			return JSONObject.parseObject(output);
		} catch (Exception e) {
			logger.warn("[PublishBackfill] fetch_video parse failed awemeId={}", awemeId);
			return null;
		}
	}

	private JSONObject fetchDouyinPostById(String awemeId) {
		if (awemeId == null || awemeId.trim().isEmpty()) {
			return null;
		}
		String out = "/tmp/backfill_" + awemeId + ".json";
		String output = CommandUtil.f2cmd(com.flower.spirit.config.Global.tiktokCookie, awemeId, "fetch_post_data", null, null, null, out);
		if (output == null || output.isBlank()) {
			return null;
		}
		try {
			return JSONObject.parseObject(com.flower.spirit.utils.FileUtil.readJson(out));
		} catch (Exception e) {
			logger.warn("[PublishBackfill] fetch_post_data parse failed awemeId={}", awemeId);
			return null;
		}
	}

	private String formatPublishTimeFromEpochSeconds(String epochSeconds) {
		return DateUtils.normalizePublishTime(epochSeconds);
	}
}
