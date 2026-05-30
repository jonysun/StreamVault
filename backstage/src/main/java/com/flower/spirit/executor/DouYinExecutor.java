package com.flower.spirit.executor;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.ProcessHistoryEntity;
import com.flower.spirit.service.ProcessHistoryService;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.DouyinSourceUrlUtil;
import com.flower.spirit.utils.FileUtil;
import com.flower.spirit.utils.FileNameTemplateUtil;
import com.flower.spirit.utils.HttpUtil;
import com.flower.spirit.utils.StringUtil;
import com.flower.spirit.utils.sendNotify;

/**
 * 这里是新版的写法 日后为了扩展性  新功能 将使用单独的executor  然后最后由入口发起选择调度
 * 但是这里只是负责解析  返回标准数据  传回调度 由调度再负责保存等实际业务逻辑
 * 旧版解析器 看时间 也可能会迁移 也许能用就算了
 */
@Service
public class DouYinExecutor {

	private static final Logger logger = LoggerFactory.getLogger(DouYinExecutor.class);
	
	@Autowired
	private GraphicContentDao graphicContentDao;
	
	@Autowired
	private ProcessHistoryService processHistoryService;

	@Autowired
	private com.flower.spirit.service.AuthorProfileService authorProfileService;

	@Autowired
	private com.flower.spirit.service.BlockedWorkService blockedWorkService;

	@Autowired
	private com.flower.spirit.service.PlatformCookieService platformCookieService;
	
    private static GraphicContentDao staticGraphicContentDao;
    
    private static ProcessHistoryService staticprocessHistoryService;

    private static com.flower.spirit.service.AuthorProfileService staticAuthorProfileService;

    private static com.flower.spirit.service.BlockedWorkService staticBlockedWorkService;

    private static com.flower.spirit.service.PlatformCookieService staticPlatformCookieService;

    @PostConstruct
    public void init() {
        staticGraphicContentDao = graphicContentDao;
        staticprocessHistoryService = processHistoryService;
        staticAuthorProfileService = authorProfileService;
        staticBlockedWorkService = blockedWorkService;
        staticPlatformCookieService = platformCookieService;
    }
	
	
	/**
	 * 图文  图文暂时没有提交记录
	 * @param postid
	 * @throws IOException 
	 */
	public static void ImageTextExecutor(String originaladdress,String post) throws IOException {
		ImageTextExecutor(originaladdress, post, (Integer) null);
	}

	public static void ImageTextExecutor(String originaladdress,String post, Integer historyId) throws IOException {
		logger.info("[DouyinImageText] start originaladdress={} postId={}", originaladdress, post);
		ProcessHistoryEntity saveProcess = historyId == null ? staticprocessHistoryService.saveProcess(null, originaladdress, "抖音") : null;
		String taskout = Global.apppath + "lot" +System.getProperty("file.separator") + "imageText_"+post + ".json";
		GraphicContentEntity graphicContentEntity = new GraphicContentEntity();
		graphicContentEntity.setVideoid(post);
		graphicContentEntity.setPlatform(Global.platform.douyin.name());
		
		Optional<GraphicContentEntity> byVideoidAndPlatform = staticGraphicContentDao.findByVideoidAndPlatform(post,Global.platform.douyin.name());
		if(byVideoidAndPlatform.isPresent()) {
			return;
		}
		if (staticBlockedWorkService != null && staticBlockedWorkService.isBlocked("抖音", post, "graphic")) {
			return;
		}
		String cookie = staticPlatformCookieService.currentDouyinCookie("image_text");
		String f2cmd = CommandUtil.f2cmd(cookie, post, "fetch_post_data", null, null, null, taskout);
		reportCookieResult(cookie, f2cmd);
		logger.info("[DouyinImageText] fetch_post_data outputLength={} containsSuccessMarker={}",
				f2cmd == null ? 0 : f2cmd.length(), f2cmd != null && f2cmd.contains("stream-vault-ok"));
		if (f2cmd == null || !f2cmd.contains("stream-vault-ok")) {
			logger.error("[DouyinImageText] fetch_post_data failed postId={} outputPreview={}", post, previewOutput(f2cmd));
		}
		if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
			String json = FileUtil.readJson(taskout);
			JSONObject object = JSONObject.parseObject(json);
			//判断
			JSONObject aweme_detail = object.getJSONObject("aweme_detail");
			String desc = aweme_detail.getString("desc");
			String nickname = aweme_detail.getJSONObject("author").getString("nickname");
			JSONArray images = aweme_detail.getJSONArray("images");
			logger.info("[DouyinImageText] parsed postId={} nickname={} imageCount={}", post, nickname, images == null ? 0 : images.size());
			JSONArray imageList=  new JSONArray();
			HashMap<String, String> header = new HashMap<String, String>();
			header.put("Referer", "https://www.douyin.com/");
			header.put("User-Agent", DouUtil.ua);
			header.put("cookie", cookie);
			String filename = FileNameTemplateUtil.resolveFileName(desc, post, nickname, aweme_detail.getString("create_time"), "抖音");
			String markroute = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null,0);
			for(int i = 0;i<images.size();i++) {
				JSONObject video = images.getJSONObject(i).getJSONObject("video");
				if(null !=video) {
					logger.info("[DouyinImageText] item type=video postId={} index={}", post, i);
					//多
					String videoplay ="";
					JSONArray jsonArray = video.getJSONObject("play_addr").getJSONArray("url_list");
					 if(jsonArray.size() >=2) {
						 videoplay = jsonArray.getString(jsonArray.size()-1);
					 }else {
						 videoplay = jsonArray.getString(0);
					 }
					String storage = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null,i);
					String cos = FileUtil.generateDir(false, Global.platform.douyin.name(), filename, null, "mp4",i);
					imageList.add(cos);
					String target = storage + File.separator + filename+"-index-"+i + ".mp4";
					if (isExistingNonEmptyFile(target)) {
						logger.info("[DouyinImageText] local file hit, skip video download postId={} index={} path={}", post, i, target);
					} else {
						HttpUtil.downloadFileWithOkHttp(videoplay, filename+"-index-"+i + ".mp4", storage, header);
					}
				}else {
					logger.info("[DouyinImageText] item type=image postId={} index={}", post, i);
					//普通
					String picaddr ="";
					JSONArray piclist = images.getJSONObject(i).getJSONArray("url_list");
					 if(piclist.size() >=2) {
						 picaddr = piclist.getString(piclist.size()-1);
					 }else {
						 picaddr = piclist.getString(0);
					 }
					 String storage = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null,i);
					 String cos = FileUtil.generateDir(false, Global.platform.douyin.name(), filename, null, "jpeg",i);
					 String target = storage + File.separator + filename+"-index-"+i + ".jpeg";
					 if (isExistingNonEmptyFile(target)) {
						 logger.info("[DouyinImageText] local file hit, skip image download postId={} index={} path={}", post, i, target);
					 } else {
						 HttpUtil.downloadFileWithOkHttp(picaddr, filename+"-index-"+i + ".jpeg", storage, header);
					 }
					 imageList.add(cos);
				}
			}
	
			graphicContentEntity.setOriginaladdress(originaladdress);
			graphicContentEntity.setTitle(desc);
			graphicContentEntity.setMarkroute(markroute);
			graphicContentEntity.setContent(desc);
			graphicContentEntity.setImages(imageList.toJSONString());
			graphicContentEntity.setAuthor(nickname);
			AuthorSnapshot authorSnapshot = resolveAuthor(aweme_detail, nickname);
			graphicContentEntity.setAuthor(authorSnapshot.nickname);
			graphicContentEntity.setAuthoruid(authorSnapshot.authorUid);
			graphicContentEntity.setSecuid(authorSnapshot.secUid);
			graphicContentEntity.setAuthorusername(authorSnapshot.uniqueId);
			graphicContentEntity.setUniqueid(authorSnapshot.uniqueId);
			graphicContentEntity.setAuthoravatar(authorSnapshot.avatar);
			String sourceUrl = DouyinSourceUrlUtil.note(post);
			JSONObject hybridData = DouUtil.fetchHybridVideoData(sourceUrl);
			graphicContentEntity.setJsonData(hybridData == null ? json : hybridData.toJSONString());
			graphicContentEntity.setPublishtime(formatPublishTimeFromEpochSeconds(aweme_detail.getString("create_time")));
			if (staticAuthorProfileService != null) {
				staticAuthorProfileService.upsertAuthor("抖音", authorSnapshot.authorUid, authorSnapshot.uniqueId, authorSnapshot.nickname,
						authorSnapshot.avatar,
						authorSnapshot.authorUid != null ? "https://www.douyin.com/user/" + authorSnapshot.authorUid : null);
			}
			graphicContentEntity.setSourceurl(sourceUrl);
			graphicContentEntity.setCreatetime(new Date());
			staticGraphicContentDao.save(graphicContentEntity);
			Files.deleteIfExists(Paths.get(taskout));
			sendNotify.sendNotifyData(filename, originaladdress, "抖音");
			if (historyId == null && saveProcess != null) {
				staticprocessHistoryService.completeProcess(saveProcess.getId(), "任务执行完成");
			}
			logger.info("[DouyinImageText] finish postId={} savedCount={}", post, imageList.size());
		}

		
		
		
	}
	
	
	public static void ImageTextExecutor(String post,String type,String patch) throws IOException {
		logger.info("[DouyinImageText] start alt postId={} type={} patch={}", post, type, patch);
		String taskout = Global.apppath + "lot" +System.getProperty("file.separator") + "imageText_"+post + ".json";
		GraphicContentEntity graphicContentEntity = new GraphicContentEntity();
		graphicContentEntity.setVideoid(post);
		graphicContentEntity.setPlatform(Global.platform.douyin.name());
		
		Optional<GraphicContentEntity> byVideoidAndPlatform = staticGraphicContentDao.findByVideoidAndPlatform(post,Global.platform.douyin.name());
		if(byVideoidAndPlatform.isPresent()) {
			return;
		}
		if (staticBlockedWorkService != null && staticBlockedWorkService.isBlocked("抖音", post, "graphic")) {
			return;
		}
		String cookie = staticPlatformCookieService.currentDouyinCookie("image_text_alt");
		String f2cmd = CommandUtil.f2cmd(cookie, post, "fetch_post_data", null, null, null, taskout);
		reportCookieResult(cookie, f2cmd);
		logger.info("[DouyinImageText] fetch_post_data outputLength={} containsSuccessMarker={}",
				f2cmd == null ? 0 : f2cmd.length(), f2cmd != null && f2cmd.contains("stream-vault-ok"));
		if (f2cmd == null || !f2cmd.contains("stream-vault-ok")) {
			logger.error("[DouyinImageText] fetch_post_data failed postId={} outputPreview={}", post, previewOutput(f2cmd));
		}
		if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
			String json = FileUtil.readJson(taskout);
			JSONObject object = JSONObject.parseObject(json);
			//判断
			JSONObject aweme_detail = object.getJSONObject("aweme_detail");
			String desc = aweme_detail.getString("desc");
			String nickname = aweme_detail.getJSONObject("author").getString("nickname");
			JSONArray images = aweme_detail.getJSONArray("images");
			logger.info("[DouyinImageText] parsed postId={} nickname={} imageCount={}", post, nickname, images == null ? 0 : images.size());
			JSONArray imageList=  new JSONArray();
			HashMap<String, String> header = new HashMap<String, String>();
			header.put("Referer", "https://www.douyin.com/");
			header.put("User-Agent", DouUtil.ua);
			header.put("cookie", cookie);
			String filename = FileNameTemplateUtil.resolveFileName(desc, post, nickname, aweme_detail.getString("create_time"), "抖音");
			String markroute = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null,0);
			for(int i = 0;i<images.size();i++) {
				JSONObject video = images.getJSONObject(i).getJSONObject("video");
				if(null !=video) {
					logger.info("[DouyinImageText] item type=video postId={} index={}", post, i);
					//多
					String videoplay ="";
					JSONArray jsonArray = video.getJSONObject("play_addr").getJSONArray("url_list");
					 if(jsonArray.size() >=2) {
						 videoplay = jsonArray.getString(jsonArray.size()-1);
					 }else {
						 videoplay = jsonArray.getString(0);
					 }
					String storage = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null,i);
					String cos = FileUtil.generateDir(false, Global.platform.douyin.name(), filename, null, "mp4",i);
					imageList.add(cos);
					String target = storage + File.separator + filename+"-index-"+i + ".mp4";
					if (isExistingNonEmptyFile(target)) {
						logger.info("[DouyinImageText] local file hit, skip video download postId={} index={} path={}", post, i, target);
					} else {
						HttpUtil.downloadFileWithOkHttp(videoplay, filename+"-index-"+i + ".mp4", storage, header);
					}
				}else {
					logger.info("[DouyinImageText] item type=image postId={} index={}", post, i);
					//普通
					String picaddr ="";
					JSONArray piclist = images.getJSONObject(i).getJSONArray("url_list");
					 if(piclist.size() >=2) {
						 picaddr = piclist.getString(piclist.size()-1);
					 }else {
						 picaddr = piclist.getString(0);
					 }
					 String storage = FileUtil.generateDir(true, Global.platform.douyin.name(), filename, null, null,i);
					 String cos = FileUtil.generateDir(false, Global.platform.douyin.name(), filename, null, "jpeg",i);
					 String target = storage + File.separator + filename+"-index-"+i + ".jpeg";
					 if (isExistingNonEmptyFile(target)) {
						 logger.info("[DouyinImageText] local file hit, skip image download postId={} index={} path={}", post, i, target);
					 } else {
						 HttpUtil.downloadFileWithOkHttp(picaddr, filename+"-index-"+i + ".jpeg", storage, header);
					 }
					 imageList.add(cos);
				}
			}
	
			graphicContentEntity.setOriginaladdress(type);
			graphicContentEntity.setTitle(desc);
			graphicContentEntity.setMarkroute(markroute);
			graphicContentEntity.setContent(desc);
			graphicContentEntity.setImages(imageList.toJSONString());
			graphicContentEntity.setAuthor(nickname);
			AuthorSnapshot authorSnapshot = resolveAuthor(aweme_detail, nickname);
			graphicContentEntity.setAuthor(authorSnapshot.nickname);
			graphicContentEntity.setAuthoruid(authorSnapshot.authorUid);
			graphicContentEntity.setSecuid(authorSnapshot.secUid);
			graphicContentEntity.setAuthorusername(authorSnapshot.uniqueId);
			graphicContentEntity.setUniqueid(authorSnapshot.uniqueId);
			graphicContentEntity.setAuthoravatar(authorSnapshot.avatar);
			String sourceUrl = DouyinSourceUrlUtil.note(post);
			JSONObject hybridData = DouUtil.fetchHybridVideoData(sourceUrl);
			graphicContentEntity.setJsonData(hybridData == null ? json : hybridData.toJSONString());
			graphicContentEntity.setPublishtime(formatPublishTimeFromEpochSeconds(aweme_detail.getString("create_time")));
			if (staticAuthorProfileService != null) {
				staticAuthorProfileService.upsertAuthor("抖音", authorSnapshot.authorUid, authorSnapshot.uniqueId, authorSnapshot.nickname,
						authorSnapshot.avatar,
						authorSnapshot.authorUid != null ? "https://www.douyin.com/user/" + authorSnapshot.authorUid : null);
			}
			graphicContentEntity.setSourceurl(sourceUrl);
			graphicContentEntity.setCreatetime(new Date());
			staticGraphicContentDao.save(graphicContentEntity);
			Files.deleteIfExists(Paths.get(taskout));
			logger.info("[DouyinImageText] finish postId={} savedCount={}", post, imageList.size());
		}

		
		
		
	}

	private static String previewOutput(String output) {
		if (output == null) {
			return "null";
		}
		String normalized = output.replace("\r", "\\r").replace("\n", "\\n");
		if (normalized.length() > 1000) {
			return normalized.substring(0, 1000);
		}
		return normalized;
	}

	private static void reportCookieResult(String cookie, String f2cmd) {
		if (staticPlatformCookieService == null) {
			return;
		}
		if (f2cmd != null && f2cmd.contains("stream-vault-ok")) {
			staticPlatformCookieService.reportSuccess("抖音", cookie);
			return;
		}
		if (staticPlatformCookieService.isRiskSignal(f2cmd)) {
			staticPlatformCookieService.reportRisk("抖音", cookie, previewOutput(f2cmd));
		}
	}

	private static boolean isExistingNonEmptyFile(String path) {
		if (path == null || path.trim().isEmpty()) {
			return false;
		}
		File file = new File(path);
		return file.exists() && file.isFile() && file.length() > 0;
	}

	private static AuthorSnapshot resolveAuthor(JSONObject awemeDetail, String fallbackName) {
		JSONObject author = awemeDetail == null ? null : awemeDetail.getJSONObject("author");
		AuthorSnapshot snapshot = new AuthorSnapshot();
		snapshot.nickname = author == null ? fallbackName : firstNotBlank(author.getString("nickname"), fallbackName);
		snapshot.secUid = author == null ? null : author.getString("sec_uid");
		snapshot.uniqueId = author == null ? null : author.getString("unique_id");
		snapshot.uid = author == null ? null : author.getString("uid");
		snapshot.avatar = DouUtil.extractAvatar(author);
		JSONObject profileUser = extractProfileUser(DouUtil.fetchUserProfile(snapshot.secUid));
		if (profileUser == null) {
			profileUser = extractProfileUser(DouUtil.fetchUserProfileByUniqueId(snapshot.uniqueId));
		}
		if (profileUser != null) {
			snapshot.nickname = firstNotBlank(profileUser.getString("nickname"), snapshot.nickname);
			snapshot.secUid = firstNotBlank(profileUser.getString("sec_uid"), snapshot.secUid);
			snapshot.uniqueId = firstNotBlank(profileUser.getString("unique_id"), snapshot.uniqueId);
			snapshot.uid = firstNotBlank(profileUser.getString("uid"), snapshot.uid);
			snapshot.avatar = firstNotBlank(DouUtil.extractAvatar(profileUser), snapshot.avatar);
		}
		snapshot.authorUid = firstNotBlank(snapshot.secUid, snapshot.uid);
		return snapshot;
	}

	private static JSONObject extractProfileUser(JSONObject profile) {
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

	private static String firstNotBlank(String first, String second) {
		if (first != null && !first.trim().isEmpty()) return first;
		return second;
	}

	private static class AuthorSnapshot {
		String nickname;
		String authorUid;
		String secUid;
		String uniqueId;
		String uid;
		String avatar;
	}

	private static String formatPublishTimeFromEpochSeconds(String epochSeconds) {
		return com.flower.spirit.utils.DateUtils.normalizePublishTime(epochSeconds);
	}

	private static String extractTaskUid(String taskAddress) {
		if (taskAddress == null || taskAddress.trim().isEmpty()) {
			return null;
		}
		String uid = taskAddress.replaceFirst("^(post|like|recommend)", "");
		if (uid.startsWith("fav-")) {
			return null;
		}
		return uid.isEmpty() ? null : uid;
	}

}
