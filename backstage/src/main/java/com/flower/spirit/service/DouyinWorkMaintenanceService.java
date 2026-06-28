package com.flower.spirit.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.utils.Aria2Util;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.DateUtils;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.DouyinSourceUrlUtil;
import com.flower.spirit.utils.FileNameTemplateUtil;
import com.flower.spirit.utils.FileUtil;
import com.flower.spirit.utils.HttpUtil;

@Service
public class DouyinWorkMaintenanceService {

	private static final Logger logger = LoggerFactory.getLogger(DouyinWorkMaintenanceService.class);

	@Autowired
	private VideoDataDao videoDataDao;

	@Autowired
	private GraphicContentDao graphicContentDao;

	@Autowired
	private AuthorProfileService authorProfileService;

	@Autowired
	private PlatformCookieService platformCookieService;

	public AjaxEntity redownloadVideo(Integer id) {
		if (id == null) {
			return new AjaxEntity(Global.ajax_uri_error, "视频ID不能为空", null);
		}
		Optional<VideoDataEntity> optional = videoDataDao.findById(id);
		if (optional.isEmpty()) {
			return new AjaxEntity(Global.ajax_uri_error, "视频不存在", null);
		}
		VideoDataEntity existing = optional.get();
		if (!isDouyinPlatform(existing.getVideoplatform())) {
			return new AjaxEntity(Global.ajax_uri_error, "仅支持抖音视频重新下载", null);
		}
		String source = resolveVideoSource(existing);
		if (isBlank(source)) {
			return new AjaxEntity(Global.ajax_uri_error, "缺少原作品链接，无法重新下载", null);
		}
		String cookie = platformCookieService.currentDouyinCookie("redownload_video");
		Map<String, String> data = DouUtil.downVideo(source, null, cookie);
		if (data == null) {
			platformCookieService.reportRisk("抖音", cookie, "redownload video parse failed");
		} else {
			platformCookieService.reportSuccess("抖音", cookie);
		}
		if (data == null) {
			return new AjaxEntity(Global.ajax_uri_error, "抖音视频解析失败", null);
		}
		String parsedAwemeId = data.get("awemeid");
		if (!isBlank(existing.getVideoid()) && !existing.getVideoid().equals(parsedAwemeId)) {
			return new AjaxEntity(Global.ajax_uri_error, "解析到的作品ID与当前行不一致，已停止重新下载", null);
		}
		try {
			applyVideoDownload(existing, data, source);
			videoDataDao.save(existing);
			return new AjaxEntity(Global.ajax_success, "视频重新下载完成", existing);
		} catch (Exception e) {
			logger.error("redownload douyin video failed id={} source={}", id, source, e);
			return new AjaxEntity(Global.ajax_uri_error, "视频重新下载失败: " + e.getMessage(), null);
		}
	}

	public AjaxEntity redownloadGraphic(Integer id) {
		if (id == null) {
			return new AjaxEntity(Global.ajax_uri_error, "图文ID不能为空", null);
		}
		Optional<GraphicContentEntity> optional = graphicContentDao.findById(id);
		if (optional.isEmpty()) {
			return new AjaxEntity(Global.ajax_uri_error, "图文不存在", null);
		}
		GraphicContentEntity existing = optional.get();
		if (!isDouyinPlatform(existing.getPlatform())) {
			return new AjaxEntity(Global.ajax_uri_error, "仅支持抖音图文重新下载", null);
		}
		String postId = existing.getVideoid();
		String source = isBlank(postId) ? resolveGraphicSource(existing) : DouyinSourceUrlUtil.note(postId);
		if (isBlank(postId) || isBlank(source)) {
			return new AjaxEntity(Global.ajax_uri_error, "缺少图文ID或原作品链接，无法重新下载", null);
		}
		try {
			applyGraphicDownload(existing, source, postId);
			graphicContentDao.save(existing);
			return new AjaxEntity(Global.ajax_success, "图文重新下载完成", existing);
		} catch (Exception e) {
			logger.error("redownload douyin graphic failed id={} postId={} source={}", id, postId, source, e);
			return new AjaxEntity(Global.ajax_uri_error, "图文重新下载失败: " + e.getMessage(), null);
		}
	}

	public AjaxEntity repairDouyinMetadata() {
		int videoUpdated = 0;
		int graphicUpdated = 0;
		int jsonUpdated = 0;
		int sourceUrlUpdated = 0;
		int failed = 0;

		List<VideoDataEntity> videos = videoDataDao.findByVideoplatform("抖音");
		for (VideoDataEntity video : videos) {
			try {
				boolean changed = false;
				String oldSource = video.getSourceurl();
				String originalAddress = video.getOriginaladdress();
				String sourceUrl = isBlank(video.getVideoid()) ? null : DouyinSourceUrlUtil.video(video.getVideoid());
				if (!isBlank(sourceUrl) && !sourceUrl.equals(video.getSourceurl())) {
					video.setSourceurl(sourceUrl);
					sourceUrlUpdated++;
					changed = true;
				}
				JSONObject hybrid = null;
				if (isBlank(video.getJsonData())) {
					hybrid = fetchHybridWithFallbacks(oldSource, originalAddress, sourceUrl);
					if (hybrid != null) {
						String json = hybrid.toJSONString();
						video.setJsonData(json);
						video.setVideoinfo(json);
						jsonUpdated++;
						changed = true;
					}
				}
				if (hybrid != null) {
					changed = applyVideoAuthorFromHybrid(video, hybrid) || changed;
				}
				if (changed) {
					videoDataDao.save(video);
					videoUpdated++;
				}
			} catch (Exception e) {
				failed++;
				logger.warn("repair douyin video metadata failed id={}", video.getId(), e);
			}
		}

		List<GraphicContentEntity> graphics = graphicContentDao.findByPlatform("抖音");
		for (GraphicContentEntity item : graphics) {
			try {
				boolean changed = false;
				String oldSource = item.getSourceurl();
				String originalAddress = item.getOriginaladdress();
				String sourceUrl = isBlank(item.getVideoid()) ? null : DouyinSourceUrlUtil.note(item.getVideoid());
				if (!isBlank(sourceUrl) && !sourceUrl.equals(item.getSourceurl())) {
					item.setSourceurl(sourceUrl);
					sourceUrlUpdated++;
					changed = true;
				}
				JSONObject hybrid = null;
				if (isBlank(item.getJsonData())) {
					hybrid = fetchHybridWithFallbacks(oldSource, originalAddress, sourceUrl);
					if (hybrid != null) {
						item.setJsonData(hybrid.toJSONString());
						jsonUpdated++;
						changed = true;
					}
				}
				if (hybrid != null) {
					changed = applyGraphicAuthorFromHybrid(item, hybrid) || changed;
				}
				if (changed) {
					graphicContentDao.save(item);
					graphicUpdated++;
				}
			} catch (Exception e) {
				failed++;
				logger.warn("repair douyin graphic metadata failed id={}", item.getId(), e);
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("videoUpdated", videoUpdated);
		result.put("graphicUpdated", graphicUpdated);
		result.put("jsonUpdated", jsonUpdated);
		result.put("sourceUrlUpdated", sourceUrlUpdated);
		result.put("failed", failed);
		return new AjaxEntity(Global.ajax_success, "抖音元数据修复完成", result);
	}

	private JSONObject fetchHybridWithFallbacks(String... urls) {
		for (String url : urls) {
			if (isBlank(url)) {
				continue;
			}
			JSONObject hybrid = DouUtil.fetchHybridVideoData(url);
			if (hybrid != null) {
				return hybrid;
			}
		}
		return null;
	}

	private void applyVideoDownload(VideoDataEntity target, Map<String, String> map, String source) throws IOException, InterruptedException {
		String awemeId = map.get("awemeid");
		String desc = map.get("desc");
		String playApi = map.get("videoplay");
		String cover = map.get("cover");
		String nickname = map.get("nickname");
		String createTime = map.get("create_time");
		String filename = FileNameTemplateUtil.resolveFileName(desc, awemeId, nickname, createTime, "抖音");
		String videoDir = FileUtil.generateDir(Global.down_path, Global.platform.douyin.name(), true, filename, null, null);
		String videoUnrealAddr = FileUtil.generateDir(false, Global.platform.douyin.name(), true, filename, null, "mp4");
		String coverUnrealAddr = FileUtil.generateDir(false, Global.platform.douyin.name(), true, filename, null, "jpg");
		String coverDir = FileUtil.generateDir(true, Global.platform.douyin.name(), true, filename, null, null);

		String cookie = platformCookieService.currentDouyinCookie("redownload_video_download");
		Map<String, String> header = douyinHeaders(cookie);
		if ("a2".equals(Global.downtype)) {
			Aria2Util.sendMessage(Global.a2_link,
					Aria2Util.createDouparameter(playApi, videoDir, filename + ".mp4", Global.a2_token, cookie));
		} else {
			videoDir = FileUtil.generateDir(true, Global.platform.douyin.name(), true, filename, null, null);
			if (Global.RangeNumber == 1) {
				HttpUtil.downloadFileWithOkHttp(playApi, filename + ".mp4", videoDir, header);
			} else {
				HttpUtil.downloadFileWithOkHttp(playApi, filename + ".mp4", videoDir, header, Global.RangeNumber);
			}
		}
		HttpUtil.downloadFileWithOkHttp(cover, filename + ".jpg", coverDir, header);

		String oldPrivacy = target.getVideoprivacy();
		String oldTag = target.getVideotag();
		JSONObject author = resolveAuthor(map.get("sec_uid"), map.get("unique_id"));
		String secUid = map.get("sec_uid");
		String uniqueId = map.get("unique_id");
		String uid = map.get("uid");
		String avatar = map.get("avatar_thumb");
		if (author != null) {
			secUid = firstNotBlank(author.getString("sec_uid"), secUid);
			uniqueId = firstNotBlank(author.getString("unique_id"), uniqueId);
			nickname = firstNotBlank(author.getString("nickname"), nickname);
			uid = firstNotBlank(author.getString("uid"), uid);
			avatar = firstNotBlank(DouUtil.extractAvatar(author), avatar);
		}
		String authorUidForSave = AuthorProfileService.preferDouyinAuthorUid(secUid, uid);
		String jsonData = firstNotBlank(map.get("jsonData"), JSONObject.toJSONString(map));

		target.setVideoid(awemeId);
		target.setVideoname(desc);
		target.setVideodesc(desc);
		target.setVideoplatform("抖音");
		target.setVideocover(coverUnrealAddr);
		target.setVideoaddr(videoDir + filename + ".mp4");
		target.setVideounrealaddr(videoUnrealAddr);
		target.setOriginaladdress(source);
		target.setVideoprivacy(oldPrivacy);
		target.setVideotag(oldTag);
		target.setVideoinfo(jsonData);
		target.setJsonData(jsonData);
		target.setPublishtime(DateUtils.normalizePublishTime(createTime));
		target.setSourceurl(DouyinSourceUrlUtil.video(awemeId));
		target.setVideoauthor(nickname);
		target.setAuthoruid(authorUidForSave);
		target.setSecuid(authorUidForSave);
		target.setAuthorusername(uniqueId);
		target.setUniqueid(uniqueId);
		target.setAuthoravatar(avatar);
		target.setCreatetime(new Date());
		if (authorProfileService != null) {
			authorProfileService.upsertAuthor("抖音", authorUidForSave, uniqueId, nickname, avatar,
					!isBlank(authorUidForSave) ? "https://www.douyin.com/user/" + authorUidForSave : null);
		}
	}

	private void applyGraphicDownload(GraphicContentEntity target, String source, String postId) throws IOException {
		String taskout = Global.apppath + "lot" + File.separator + "imageText_" + postId + ".json";
		String cookie = platformCookieService.currentDouyinCookie("redownload_graphic");
		String f2cmd = CommandUtil.f2cmd(cookie, postId, "fetch_post_data", null, null, null, taskout);
		if (f2cmd != null && f2cmd.contains("stream-vault-ok")) {
			platformCookieService.reportSuccess("抖音", cookie);
		} else if (platformCookieService.isRiskSignal(f2cmd)) {
			platformCookieService.reportRisk("抖音", cookie, "redownload graphic failed");
		}
		if (f2cmd == null || !f2cmd.contains("stream-vault-ok")) {
			throw new IOException("fetch_post_data failed");
		}
		String json = FileUtil.readJson(taskout);
		JSONObject object = JSONObject.parseObject(json);
		JSONObject detail = object.getJSONObject("aweme_detail");
		if (detail == null) {
			throw new IOException("aweme_detail missing");
		}
		String desc = detail.getString("desc");
		JSONObject authorObject = detail.getJSONObject("author");
		String nickname = authorObject == null ? null : authorObject.getString("nickname");
		JSONArray images = detail.getJSONArray("images");
		if (images == null) {
			images = new JSONArray();
		}
		String filename = FileNameTemplateUtil.resolveFileName(desc, postId, nickname, detail.getString("create_time"), "抖音");
		String markroute = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null, 0);
		JSONArray imageList = new JSONArray();
		Map<String, String> header = douyinHeaders(cookie);
		for (int i = 0; i < images.size(); i++) {
			JSONObject image = images.getJSONObject(i);
			JSONObject video = image.getJSONObject("video");
			if (video != null) {
				JSONArray urls = video.getJSONObject("play_addr").getJSONArray("url_list");
				String play = urls.getString(urls.size() >= 2 ? urls.size() - 1 : 0);
				String storage = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null, i);
				String cos = FileUtil.generateDir(false, Global.platform.douyin.name(), filename, null, "mp4", i);
				HttpUtil.downloadFileWithOkHttp(play, filename + "-index-" + i + ".mp4", storage, header);
				imageList.add(cos);
			} else {
				JSONArray urls = image.getJSONArray("url_list");
				String pic = urls.getString(urls.size() >= 2 ? urls.size() - 1 : 0);
				String storage = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null, i);
				String cos = FileUtil.generateDir(false, Global.platform.douyin.name(), filename, null, "jpeg", i);
				HttpUtil.downloadFileWithOkHttp(pic, filename + "-index-" + i + ".jpeg", storage, header);
				imageList.add(cos);
			}
		}
		AuthorSnapshot snapshot = resolveAuthorSnapshot(detail, nickname);
		String sourceUrl = DouyinSourceUrlUtil.note(postId);
		JSONObject hybrid = DouUtil.fetchHybridVideoData(sourceUrl);
		target.setVideoid(postId);
		target.setPlatform("抖音");
		target.setOriginaladdress(source);
		target.setTitle(desc);
		target.setContent(desc);
		target.setImages(imageList.toJSONString());
		target.setMarkroute(markroute);
		target.setAuthor(snapshot.nickname);
		target.setAuthoruid(snapshot.authorUid);
		target.setSecuid(snapshot.secUid);
		target.setAuthorusername(snapshot.uniqueId);
		target.setUniqueid(snapshot.uniqueId);
		target.setAuthoravatar(snapshot.avatar);
		target.setJsonData(hybrid == null ? json : hybrid.toJSONString());
		target.setPublishtime(DateUtils.normalizePublishTime(detail.getString("create_time")));
		target.setSourceurl(sourceUrl);
		target.setCreatetime(new Date());
		if (authorProfileService != null) {
			authorProfileService.upsertAuthor("抖音", snapshot.authorUid, snapshot.uniqueId, snapshot.nickname, snapshot.avatar,
					!isBlank(snapshot.authorUid) ? "https://www.douyin.com/user/" + snapshot.authorUid : null);
		}
		Files.deleteIfExists(Paths.get(taskout));
	}

	private boolean applyVideoAuthorFromHybrid(VideoDataEntity video, JSONObject hybrid) {
		JSONObject detail = DouUtil.findAwemeDetail(hybrid);
		JSONObject author = detail == null ? null : detail.getJSONObject("author");
		if (author == null) return false;
		boolean changed = false;
		changed = setIfBlankVideoAuthor(video, author.getString("sec_uid"), author.getString("unique_id"),
				author.getString("nickname"), DouUtil.extractAvatar(author)) || changed;
		return changed;
	}

	private boolean applyGraphicAuthorFromHybrid(GraphicContentEntity item, JSONObject hybrid) {
		JSONObject detail = DouUtil.findAwemeDetail(hybrid);
		JSONObject author = detail == null ? null : detail.getJSONObject("author");
		if (author == null) return false;
		boolean changed = false;
		String secUid = author.getString("sec_uid");
		String uniqueId = author.getString("unique_id");
		String nickname = author.getString("nickname");
		String avatar = DouUtil.extractAvatar(author);
		if (isBlank(item.getAuthoruid()) && !isBlank(secUid)) { item.setAuthoruid(secUid); changed = true; }
		if (isBlank(item.getSecuid()) && !isBlank(secUid)) { item.setSecuid(secUid); changed = true; }
		if (isBlank(item.getAuthorusername()) && !isBlank(uniqueId)) { item.setAuthorusername(uniqueId); changed = true; }
		if (isBlank(item.getUniqueid()) && !isBlank(uniqueId)) { item.setUniqueid(uniqueId); changed = true; }
		if (isBlank(item.getAuthor()) && !isBlank(nickname)) { item.setAuthor(nickname); changed = true; }
		if (isBlank(item.getAuthoravatar()) && !isBlank(avatar)) { item.setAuthoravatar(avatar); changed = true; }
		return changed;
	}

	private boolean setIfBlankVideoAuthor(VideoDataEntity video, String secUid, String uniqueId, String nickname, String avatar) {
		boolean changed = false;
		if (isBlank(video.getAuthoruid()) && !isBlank(secUid)) { video.setAuthoruid(secUid); changed = true; }
		if (isBlank(video.getSecuid()) && !isBlank(secUid)) { video.setSecuid(secUid); changed = true; }
		if (isBlank(video.getAuthorusername()) && !isBlank(uniqueId)) { video.setAuthorusername(uniqueId); changed = true; }
		if (isBlank(video.getUniqueid()) && !isBlank(uniqueId)) { video.setUniqueid(uniqueId); changed = true; }
		if (isBlank(video.getVideoauthor()) && !isBlank(nickname)) { video.setVideoauthor(nickname); changed = true; }
		if (isBlank(video.getAuthoravatar()) && !isBlank(avatar)) { video.setAuthoravatar(avatar); changed = true; }
		return changed;
	}

	private AuthorSnapshot resolveAuthorSnapshot(JSONObject detail, String fallbackName) {
		JSONObject author = detail == null ? null : detail.getJSONObject("author");
		AuthorSnapshot snapshot = new AuthorSnapshot();
		snapshot.nickname = author == null ? fallbackName : firstNotBlank(author.getString("nickname"), fallbackName);
		snapshot.secUid = author == null ? null : author.getString("sec_uid");
		snapshot.uniqueId = author == null ? null : author.getString("unique_id");
		snapshot.uid = author == null ? null : author.getString("uid");
		snapshot.avatar = DouUtil.extractAvatar(author);
		JSONObject profile = resolveAuthor(snapshot.secUid, snapshot.uniqueId);
		if (profile != null) {
			snapshot.nickname = firstNotBlank(profile.getString("nickname"), snapshot.nickname);
			snapshot.secUid = firstNotBlank(profile.getString("sec_uid"), snapshot.secUid);
			snapshot.uniqueId = firstNotBlank(profile.getString("unique_id"), snapshot.uniqueId);
			snapshot.uid = firstNotBlank(profile.getString("uid"), snapshot.uid);
			snapshot.avatar = firstNotBlank(DouUtil.extractAvatar(profile), snapshot.avatar);
		}
		snapshot.authorUid = AuthorProfileService.preferDouyinAuthorUid(snapshot.secUid, snapshot.uid);
		snapshot.secUid = snapshot.authorUid;
		return snapshot;
	}

	private JSONObject resolveAuthor(String secUid, String uniqueId) {
		JSONObject profile = extractProfileUser(DouUtil.fetchUserProfile(secUid));
		if (profile == null) {
			profile = extractProfileUser(DouUtil.fetchUserProfileByUniqueId(uniqueId));
		}
		return profile;
	}

	public JSONObject extractProfileUser(JSONObject profile) {
		if (profile == null) return null;
		JSONObject user = profile.getJSONObject("user");
		if (user != null) return user;
		JSONObject data = profile.getJSONObject("data");
		if (data != null) {
			JSONObject dataUser = data.getJSONObject("user");
			if (dataUser != null) return dataUser;
			return data;
		}
		return profile;
	}

	private Map<String, String> douyinHeaders(String cookie) {
		Map<String, String> header = new HashMap<>();
		header.put("Referer", "https://www.douyin.com/");
		header.put("User-Agent", DouUtil.ua);
		header.put("cookie", cookie);
		return header;
	}

	private String resolveVideoSource(VideoDataEntity item) {
		String source = firstNotBlank(item.getSourceurl(), item.getOriginaladdress());
		if (isBlank(source) && !isBlank(item.getVideoid())) {
			source = DouyinSourceUrlUtil.video(item.getVideoid());
		}
		return source;
	}

	private String resolveGraphicSource(GraphicContentEntity item) {
		String source = firstNotBlank(item.getSourceurl(), item.getOriginaladdress());
		if (isBlank(source) && !isBlank(item.getVideoid())) {
			source = DouyinSourceUrlUtil.note(item.getVideoid());
		}
		return source;
	}

	private boolean isDouyinPlatform(String platform) {
		return "抖音".equals(platform) || "douyin".equalsIgnoreCase(platform);
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private String firstNotBlank(String first, String second) {
		return !isBlank(first) ? first.trim() : (!isBlank(second) ? second.trim() : null);
	}

	private static class AuthorSnapshot {
		String nickname;
		String authorUid;
		String secUid;
		String uniqueId;
		String uid;
		String avatar;
	}
}
