package com.flower.spirit.web.admin;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.common.RequestEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dto.UpdateWorkMetadataRequest;
import com.flower.spirit.dto.WorkOperationRequest;
import com.flower.spirit.dto.AdminAuthorDeletionRequest;
import com.flower.spirit.dto.AdminDeleteWorkRequest;
import com.flower.spirit.entity.BiliConfigEntity;
import com.flower.spirit.entity.AuthorProfileEntity;
import com.flower.spirit.entity.BlockedWorkEntity;
import com.flower.spirit.entity.CollectDataDetailEntity;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.ConfigEntity;
import com.flower.spirit.entity.CookiesConfigEntity;
import com.flower.spirit.entity.CookiesRequestEntity;
import com.flower.spirit.entity.DownloaderEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.ProcessHistoryEntity;
import com.flower.spirit.entity.TikTokConfigEntity;
import com.flower.spirit.entity.UserEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.entity.VideoMixEntity;
import com.flower.spirit.service.BiliConfigService;
import com.flower.spirit.service.CollectDataDetailService;
import com.flower.spirit.service.CollectDataService;
import com.flower.spirit.service.AnalysisService;
import com.flower.spirit.service.AdminMediaManagementService;
import com.flower.spirit.service.AuthorProfileService;
import com.flower.spirit.service.BlockedWorkService;
import com.flower.spirit.service.ConfigService;
import com.flower.spirit.service.CookiesConfigService;
import com.flower.spirit.service.DouYinService;
import com.flower.spirit.service.DouyinCookieHealthService;
import com.flower.spirit.service.DouyinAuthorReconciliationService;
import com.flower.spirit.service.DouyinAuthorProfileRefreshService;
import com.flower.spirit.service.DouyinWorkMaintenanceService;
import com.flower.spirit.service.DownloaderService;
import com.flower.spirit.service.GraphicContentService;
import com.flower.spirit.service.HlsTranscodeService;
import com.flower.spirit.service.MediaFeedService;
import com.flower.spirit.service.ProcessHistoryService;
import com.flower.spirit.service.SystemService;
import com.flower.spirit.service.TikTokConfigService;
import com.flower.spirit.service.UserService;
import com.flower.spirit.service.VideoDataService;
import com.flower.spirit.service.VideoMixService;
import com.flower.spirit.service.WorkMetadataEditService;
import com.flower.spirit.service.WorkRedownloadService;
import com.flower.spirit.service.WorkRefreshService;
import com.flower.spirit.platform.WorkMetadataValidationException;


/**
 * 
 * 后台api 控制器
 * @author flower
 *
 */
@RestController
@RequestMapping("/admin/api")
public class AdminController {
	
	@Autowired
	private SystemService systemService;
	
	@Autowired
	private UserService userService;
	
	
	@Autowired
	private DownloaderService downloaderService;
	
	
	@Autowired
	private ConfigService configService;

	
	@Autowired
	private VideoDataService videoDataService;
	
	@Autowired
	private BiliConfigService biliConfigService;
	
	@Autowired
	private ProcessHistoryService processHistoryService;
	
	@Autowired
	private CollectDataService collectDataService;
	
	
	@Autowired
	private TikTokConfigService  tikTokConfigService;
	
	
	@Autowired
	private CollectDataDetailService collectDataDetailService;
	
	@Autowired
	private CookiesConfigService cookiesConfigService;
	
	@Autowired
	private DouYinService douYinService;
	
	@Autowired
	private VideoMixService videoMixService;

	@Autowired
	private AuthorProfileService authorProfileService;

	@Autowired
	private DouyinAuthorProfileRefreshService douyinAuthorProfileRefreshService;

	@Autowired
	private BlockedWorkService blockedWorkService;
	
	@Autowired
	private GraphicContentService graphicContentService;

	@Autowired
	private MediaFeedService mediaFeedService;
	
	@Autowired
	private AnalysisService analysisService;

	@Autowired
	private HlsTranscodeService hlsTranscodeService;

	@Autowired
	private DouyinWorkMaintenanceService douyinWorkMaintenanceService;

	@Autowired
	private DouyinCookieHealthService douyinCookieHealthService;

	@Autowired
	private DouyinAuthorReconciliationService douyinAuthorReconciliationService;

	@Autowired
	private WorkMetadataEditService workMetadataEditService;

	@Autowired
	private WorkRefreshService workRefreshService;

	@Autowired
	private WorkRedownloadService workRedownloadService;

	@Autowired
	private AdminMediaManagementService adminMediaManagementService;
	
	/**  
	
	 * <p>Title: login</p>  
	
	 * <p>Description:用户管理员登录 </p>  
	
	 * @param userEntity
	 * @return  
	
	 */  
	@PostMapping(value = "/login")
	public AjaxEntity login(UserEntity userEntity,HttpServletRequest request) {
		return systemService.loginUser(userEntity,request);
	}
/**  
	
	 * <p>Title: findUserList</p>  
	
	 * <p>Description:分页获取管理员的列表 </p>  
	
	 * @param res
	 * @param request
	 * @return  
	
	 */  
	@PostMapping(value = "/findUserList")
	public AjaxEntity findUserList(RequestEntity res,HttpServletRequest request) {
		return userService.findUserList(res);
	}
	
	/**  
	
	 * <p>Title: addUser</p>  
	
	 * <p>Description: 添加用户</p>  
	
	 * @param userEntity
	 * @param request
	 * @return  
	
	 */  
	@PostMapping(value = "/addUser")
	public AjaxEntity addUser(UserEntity userEntity,HttpServletRequest request) {
		return userService.addUser(userEntity);
	}
	
	/**
	 * 删除管理用户
	 * @param userEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/delUser")
	public AjaxEntity delUser(UserEntity userEntity,HttpServletRequest request) {
		return userService.delUser(userEntity);
	}
	
	/**
	 * 分页获取下载器列表
	 * @param res
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/finddownLoaderList")
	public AjaxEntity finddownLoaderList(RequestEntity res,HttpServletRequest request) {
		return downloaderService.finddownLoaderList(res);
	}
	
	/**
	 * 删除下载器
	 * @param downloaderEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/deleteDownLoader")
	public AjaxEntity delDownLoader(DownloaderEntity downloaderEntity,HttpServletRequest request) {
		return downloaderService.delDownLoader(downloaderEntity);
	}
	
	/**
	 * 新增或修改下载器
	 * @param downloaderEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/addDownLoader")
	public AjaxEntity addDownLoader(DownloaderEntity downloaderEntity,HttpServletRequest request) {
		return downloaderService.addDownLoader(downloaderEntity);
	}
	/**
	 * 获取单个下载器信息
	 * @param downloaderEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/getDownLoader")
	public AjaxEntity getDownLoader(DownloaderEntity downloaderEntity,HttpServletRequest request) {
		return downloaderService.getDownLoader(downloaderEntity);
	}
	
	/**
	 * 修改系统基础设置 apptoken
	 * @param configEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/saveConfig")
	public AjaxEntity saveConfig(ConfigEntity configEntity,HttpServletRequest request) {
		return configService.saveConfig(configEntity);
	}
	/**
	 * 分页获取已缓存的视频历史记录
	 * @param videoDataEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/findVideoDataList")
	public AjaxEntity findVideoDataList(VideoDataEntity videoDataEntity,
			@RequestParam(name = "lite", defaultValue = "0") String lite,
			HttpServletRequest request) {
		boolean liteMode = "1".equals(lite) || "true".equalsIgnoreCase(lite);
		return videoDataService.findPage(videoDataEntity, liteMode);
	}

	@PostMapping(value = "/findMediaFeedList")
	public AjaxEntity findMediaFeedList(VideoDataEntity videoDataEntity, HttpServletRequest request) {
		return mediaFeedService.findPage(videoDataEntity);
	}
	
	/**
	 * 删除视频缓存信息及视频文件
	 * @param downloaderEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/deleteVideoData")
	public AjaxEntity deleteVideoData(VideoDataEntity downloaderEntity,HttpServletRequest request) {
		AdminDeleteWorkRequest deleteRequest = new AdminDeleteWorkRequest();
		deleteRequest.setWorkType("video");
		deleteRequest.setId(downloaderEntity.getId());
		deleteRequest.setBlockWork(!"0".equals(downloaderEntity.getBlockwork()));
		try {
			return new AjaxEntity(Global.ajax_success, "操作成功", adminMediaManagementService.deleteWork(deleteRequest));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}
	
	/**
	 * 更新视频基础文件
	 * @param downloaderEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/updateVideoData")
	public AjaxEntity updateVideoData(VideoDataEntity downloaderEntity,HttpServletRequest request) {
		return videoDataService.updateVideoData(downloaderEntity);
	}

	@PostMapping(value = "/redownloadVideoData")
	public AjaxEntity redownloadVideoData(Integer id) {
		return videoDataService.redownloadVideoData(id);
	}

	@PostMapping(value = "/updateWorkMetadata")
	public AjaxEntity updateWorkMetadata(@RequestBody UpdateWorkMetadataRequest updateRequest,
			HttpServletRequest request) {
		Object sessionUser = request.getSession(false) == null ? null
				: request.getSession(false).getAttribute(Global.user_session_key);
		if (!(sessionUser instanceof UserEntity user) || user.getUsername() == null
				|| user.getUsername().trim().isEmpty()) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Metadata updated",
					workMetadataEditService.update(updateRequest, user.getUsername()));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@GetMapping(value = "/workMetadata")
	public AjaxEntity workMetadata(String workType, Integer id, HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Metadata loaded",
					adminMediaManagementService.findWorkMetadata(workType, id));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@PostMapping(value = "/deleteWork")
	public AjaxEntity deleteWork(@RequestBody AdminDeleteWorkRequest deleteRequest, HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Work deleted",
					adminMediaManagementService.deleteWork(deleteRequest));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@PostMapping(value = "/previewDeleteAuthor")
	public AjaxEntity previewDeleteAuthor(@RequestBody AdminAuthorDeletionRequest deletionRequest,
			HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Author deletion preview",
					adminMediaManagementService.previewAuthorDeletion(deletionRequest));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@PostMapping(value = "/deleteAuthor")
	public AjaxEntity deleteAuthor(@RequestBody AdminAuthorDeletionRequest deletionRequest,
			HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Author deletion started",
					adminMediaManagementService.startAuthorDeletion(deletionRequest));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@GetMapping(value = "/authorDeletionStatus")
	public AjaxEntity authorDeletionStatus(String jobId, HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Author deletion status",
					adminMediaManagementService.authorDeletionStatus(jobId));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@PostMapping(value = "/refreshWorkMetadata")
	public AjaxEntity refreshWorkMetadata(@RequestBody WorkOperationRequest operationRequest,
			HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Metadata refreshed",
					workRefreshService.refresh(operationRequest));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@PostMapping(value = "/redownloadWork")
	public AjaxEntity redownloadWork(@RequestBody WorkOperationRequest operationRequest,
			HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "Redownload processed",
					workRedownloadService.redownload(operationRequest));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	private boolean hasAuthenticatedAdmin(HttpServletRequest request) {
		Object sessionUser = request.getSession(false) == null ? null
				: request.getSession(false).getAttribute(Global.user_session_key);
		return sessionUser instanceof UserEntity user && user.getUsername() != null
				&& !user.getUsername().trim().isEmpty();
	}
	
	
	/**修改Bili配置信息
	 * @param biliConfigEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/updateBiliConfig")
	public AjaxEntity updateBiliConfig(BiliConfigEntity biliConfigEntity,HttpServletRequest request) {
		return biliConfigService.updateBiliConfig(biliConfigEntity);
	}
	
	/**
	 * 获取Bili 登录验证码
	 * @return
	 */
	@GetMapping(value = "/getBiliCode")
	public AjaxEntity getBiliCode() {
		return biliConfigService.getBiliCode();
	}
	
	
	/**
	 * 检查Bili 登录状态
	 * @param qrcodekey
	 * @return
	 */
	@GetMapping(value = "/checkBiliLogin")
	public AjaxEntity checkBiliLogin(String qrcodekey) {
		return biliConfigService.checkBiliLogin(qrcodekey);
	}
	
	/**
	 * 获取历史进度数据
	 * @param processHistoryEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/findProcessHistoryList")
	public AjaxEntity findProcessHistoryList(ProcessHistoryEntity processHistoryEntity,HttpServletRequest request) {
		return processHistoryService.findPage(processHistoryEntity);
	}
	
	/**
	 * 删除历史进度数据
	 * @param processHistoryEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/deleteProcessHistoryData")
	public AjaxEntity deleteProcessHistoryData(ProcessHistoryEntity processHistoryEntity,HttpServletRequest request) {
		return processHistoryService.deleteProcessHistoryData(processHistoryEntity);
	}
	
	/**
	 * 获取收藏夹分页
	 * @param collectDataEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/findCollectDataList")
	public AjaxEntity findCollectDataList(CollectDataEntity collectDataEntity,HttpServletRequest request) {
		return collectDataService.findPage(collectDataEntity);
	}
	
	/**
	 * 删除收藏夹信息
	 * @param collectDataEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/deleteCollectData")
	public AjaxEntity deleteCollectData(CollectDataEntity collectDataEntity,HttpServletRequest request) {
		return collectDataService.deleteCollectData(collectDataEntity);
	}
	/**
	 * 新建收藏夹信息
	 * @param collectDataEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/submitCollectData")
	public AjaxEntity submitCollectData(CollectDataEntity collectDataEntity,HttpServletRequest request) {
		return collectDataService.saveCollectData(collectDataEntity);
	}

	@PostMapping(value = "/updateCollectData")
	public AjaxEntity updateCollectData(CollectDataEntity collectDataEntity) {
		return collectDataService.updateCollectData(collectDataEntity);
	}
	
	
	/**
	 * 手动/创建之后立即执行任务
	 * @param collectDataEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/execCollectData")
	public AjaxEntity execCollectData(CollectDataEntity collectDataEntity,HttpServletRequest request) {
		return collectDataService.execCollectData(collectDataEntity);
	}

	@GetMapping(value = "/pauseCollectData")
	public AjaxEntity pauseCollectData(Integer id) {
		return collectDataService.pauseCollectData(id);
	}

	@GetMapping(value = "/resumeCollectData")
	public AjaxEntity resumeCollectData(Integer id) {
		return collectDataService.resumeCollectData(id);
	}
	
	//updateTikTokConfig
	/**
	 * 更新抖音相关下载配置
	 * @param tikTokConfigEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/updateTikTokConfig")
	public AjaxEntity updateTikTokConfig(TikTokConfigEntity tikTokConfigEntity,HttpServletRequest request) {
		return tikTokConfigService.updateTikTokConfig(tikTokConfigEntity);
	}
	
	
	/**
	 * 获取对应收藏夹明细
	 * @param entity
	 * @return
	 */
	@PostMapping(value = "/findCollectDataDetail")
	public AjaxEntity findCollectDataDetail(CollectDataDetailEntity entity) {
		return collectDataDetailService.findPage(entity);
	}

	/**
	 * 获取线程池状态信息
	 * 
	 * @return 线程池状态信息
	 */
	@GetMapping(value = "/getThreadPoolStatus")
	public AjaxEntity getThreadPoolStatus() {
		try {
			Map<String, Object> result = new HashMap<>();
			Map<String, Object> analysisStatus = analysisService.getThreadPoolStatus();
			result.put("analysis", analysisStatus);
			Map<String, Object> collectStatus = collectDataService.getCollectThreadPoolStatus();
			result.put("collect", collectStatus);

			return new AjaxEntity(Global.ajax_success, "获取线程池状态成功", result);
		} catch (Exception e) {
			return new AjaxEntity(Global.ajax_uri_error, "获取线程池状态失败: " + e.getMessage(), null);
		}
	}

	/**
	 * 更新Cookie 配置
	 * @param entity
	 * @return
	 */
	private Map<String, Object> buildBackgroundTaskControlStatus() {
		Map<String, Object> control = new HashMap<>();
		control.put("allPaused", Global.backgroundTaskPauseAll);
		control.put("downloadPaused", Global.backgroundTaskPauseDownload);
		control.put("collectPaused", Global.backgroundTaskPauseCollect);
		control.put("hlsPaused", Global.backgroundTaskPauseHls);
		control.put("effectiveDownloadPaused", Global.isDownloadPaused());
		control.put("effectiveCollectPaused", Global.isCollectPaused());
		control.put("effectiveHlsPaused", Global.isHlsPaused());
		return control;
	}

	@GetMapping(value = "/getBackgroundTaskStatus")
	public AjaxEntity getBackgroundTaskStatus() {
		try {
			Map<String, Object> result = new HashMap<>();
			result.put("control", buildBackgroundTaskControlStatus());
			result.put("analysis", analysisService.getThreadPoolStatus());
			result.put("collect", collectDataService.getCollectThreadPoolStatus());
			AjaxEntity hls = hlsTranscodeService.stats();
			result.put("hls", hls == null ? null : hls.getRecord());
			return new AjaxEntity(Global.ajax_success, "获取后台任务状态成功", result);
		} catch (Exception e) {
			return new AjaxEntity(Global.ajax_uri_error, "获取后台任务状态失败: " + e.getMessage(), null);
		}
	}

	@PostMapping(value = "/setBackgroundTaskPause")
	public AjaxEntity setBackgroundTaskPause(String scope, String paused) {
		boolean value = "1".equals(paused) || "true".equalsIgnoreCase(String.valueOf(paused)) || "Y".equalsIgnoreCase(String.valueOf(paused));
		String normalized = scope == null ? "" : scope.trim().toLowerCase();
		switch (normalized) {
		case "all":
			Global.backgroundTaskPauseAll = value;
			Global.backgroundTaskPauseDownload = value;
			Global.backgroundTaskPauseCollect = value;
			Global.backgroundTaskPauseHls = value;
			break;
		case "download":
			Global.backgroundTaskPauseDownload = value;
			break;
		case "collect":
			Global.backgroundTaskPauseCollect = value;
			break;
		case "hls":
			Global.backgroundTaskPauseHls = value;
			break;
		default:
			return new AjaxEntity(Global.ajax_uri_error, "未知任务范围: " + scope, null);
		}
		return getBackgroundTaskStatus();
	}

	@PostMapping(value = "/updateCookie")
	public AjaxEntity updateCookie(CookiesConfigEntity entity) {
		return cookiesConfigService.updateCookie(entity);
	}
	
	
	@GetMapping(value = "/getDouYinCodeLogin")
	public AjaxEntity getDouYinCodeLogin() throws Exception {
		return douYinService.getDouYinCodeLogin();
	}
	
	@GetMapping(value = "/checkDouYinLogin")
	public AjaxEntity checkDouYinLogin(String token) throws Exception {
		return douYinService.checkLoginStatus(token);
	}
	@GetMapping(value = "/checkVersion")
	public AjaxEntity checkVersion() {
		return systemService.checkVersion();
	}
	
	@PostMapping(value = "/writeCookies")
	public AjaxEntity writeCookies(CookiesRequestEntity cookiesRequestEntity) {
		return cookiesConfigService.writeCookies(cookiesRequestEntity);
	}
	
	@PostMapping(value = "/checkCookies")
	public AjaxEntity checkCookies() {
		return cookiesConfigService.checkCookies();
	}

	@PostMapping(value = "/checkDouyinCookies")
	public AjaxEntity checkDouyinCookies() {
		return new AjaxEntity(Global.ajax_success, "检测完成", douyinCookieHealthService.checkDouyinCookies(false));
	}
	
	@GetMapping(value = "/loadDouFav")
	public AjaxEntity loadDouFav(String uid) {
		return collectDataService.loadDouFav(uid);
	}

	@PostMapping(value = "/resolveDouyinUserLink")
	public AjaxEntity resolveDouyinUserLink(String text) {
		return collectDataService.resolveDouyinUserLink(text);
	}
	
	@GetMapping(value = "/fixBiliFav")
	public AjaxEntity fixBiliFav(String id) {
		return collectDataService.fixBiliFav(id);
	}
	
	@PostMapping(value = "/getMixList")
	public AjaxEntity getMixList(VideoMixEntity entity) {
		return videoMixService.getMixList(entity);
	}
	
	@PostMapping(value = "/saveMix")
	public AjaxEntity saveMix(@RequestBody VideoMixEntity entity) {
		return videoMixService.saveMix(entity);
	}
	
	@GetMapping(value = "/deleteMix")
	public AjaxEntity deleteMix(String id) {
		return videoMixService.deleteMix(id);
	}
	
	@GetMapping(value = "/startMix")
	public AjaxEntity startMix(String id) {
		return videoMixService.startMix(id);
	}
	
	@PostMapping(value = "/ytextractor")
	public AjaxEntity ytextractor(VideoDataEntity enity) {
		return configService.ytextractor(enity);
	}
	
	/**
	 * 分页获取已缓存的视频历史记录
	 * 
	 * @param videoDataEntity
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/findGraphicContentList")
	public AjaxEntity findGraphicContentList(GraphicContentEntity graphicContentEntity, HttpServletRequest request) {
		return graphicContentService.findPage(graphicContentEntity);
	}

	@PostMapping(value = "/findAuthorProfileList")
	public AjaxEntity findAuthorProfileList(AuthorProfileEntity authorProfileEntity) {
		return authorProfileService.findPage(authorProfileEntity);
	}

	@GetMapping(value = "/findAuthorNameHistory")
	public AjaxEntity findAuthorNameHistory(Integer authorProfileId) {
		return new AjaxEntity(Global.ajax_success, "数据获取成功", authorProfileService.findNameHistory(authorProfileId));
	}

	@PostMapping(value = "/refreshDouyinAuthorProfile")
	public AjaxEntity refreshDouyinAuthorProfile(@RequestParam Integer authorProfileId, HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		try {
			return new AjaxEntity(Global.ajax_success, "作者资料刷新完成",
					douyinAuthorProfileRefreshService.refresh(authorProfileId));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@GetMapping(value = "/authorProfileSummary")
	public AjaxEntity authorProfileSummary(String platform, String authoruid, String authorusername, String author) {
		return authorProfileService.findProfileSummary(platform, authoruid, authorusername, author);
	}

	@GetMapping(value = "/authorProfileWorks")
	public AjaxEntity authorProfileWorks(String platform, String authoruid, String authorusername, String author,
			String type, Integer pageNo, Integer pageSize) {
		return authorProfileService.findProfileWorks(platform, authoruid, authorusername, author, type, pageNo, pageSize);
	}

	@PostMapping(value = "/rebuildDouyinAuthors")
	public AjaxEntity rebuildDouyinAuthors(HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		return douyinAuthorReconciliationService.start();
	}

	@GetMapping(value = "/douyinAuthorReconcilePreview")
	public AjaxEntity douyinAuthorReconcilePreview(HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		return douyinAuthorReconciliationService.preview();
	}

	@PostMapping(value = "/startDouyinAuthorReconcile")
	public AjaxEntity startDouyinAuthorReconcile(HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		return douyinAuthorReconciliationService.start();
	}

	@GetMapping(value = "/douyinAuthorReconcileStatus")
	public AjaxEntity douyinAuthorReconcileStatus(HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		return douyinAuthorReconciliationService.getStatus();
	}

	@PostMapping(value = "/repairDouyinMetadata")
	public AjaxEntity repairDouyinMetadata() {
		return douyinWorkMaintenanceService.repairDouyinMetadata();
	}

	@PostMapping(value = "/cleanupDuplicateDouyinHistory")
	public AjaxEntity cleanupDuplicateDouyinHistory() {
		return processHistoryService.cleanupDuplicateDouyinHistory();
	}

	@PostMapping(value = "/findBlockedWorkList")
	public AjaxEntity findBlockedWorkList(BlockedWorkEntity blockedWorkEntity) {
		return blockedWorkService.findPage(blockedWorkEntity);
	}

	@GetMapping(value = "/restoreBlockedWork")
	public AjaxEntity restoreBlockedWork(Integer id) {
		return blockedWorkService.restore(id);
	}

	@GetMapping(value = "/deleteGraphicContent")
	public AjaxEntity deleteGraphicContent(String id, String blockwork, HttpServletRequest request) {
		AdminDeleteWorkRequest deleteRequest = new AdminDeleteWorkRequest();
		deleteRequest.setWorkType("graphic");
		try {
			deleteRequest.setId(Integer.valueOf(id));
		} catch (RuntimeException e) {
			return new AjaxEntity(Global.ajax_uri_error, "positive work id is required", null);
		}
		deleteRequest.setBlockWork(!"0".equals(blockwork));
		try {
			return new AjaxEntity(Global.ajax_success, "操作成功", adminMediaManagementService.deleteWork(deleteRequest));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@PostMapping(value = "/redownloadGraphicContent")
	public AjaxEntity redownloadGraphicContent(Integer id) {
		return graphicContentService.redownloadGraphicContent(id);
	}

	/**
	 * 获取数据统计信息
	 * 
	 * @return 统计数据
	 */
	@GetMapping(value = "/getDataStatistics")
	public AjaxEntity getDataStatistics() {
		try {
			Map<String, Object> result = new HashMap<>();
			result.put("videoPlatformStats", videoDataService.countByVideoplatformGroupBy());
			result.put("graphicPlatformStats", graphicContentService.countByPlatformGroupBy());
			result.put("collectDataTotal", collectDataService.countTotal());
			// 添加今日新增数据统计
			result.put("videoTodayAdded", videoDataService.countTodayAdded());
			result.put("graphicTodayAdded", graphicContentService.countTodayAdded());
			return new AjaxEntity(Global.ajax_success, "获取数据统计成功", result);
		} catch (Exception e) {
			return new AjaxEntity(Global.ajax_uri_error, "获取数据统计失败: " + e.getMessage(), null);
		}
	}
	
	/**
	 * 删除视频缓存信息及视频文件
	 * @param downloaderEntity
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/refreshDanmu")
	public AjaxEntity refreshDanmu(VideoDataEntity downloaderEntity,HttpServletRequest request) {
		return videoDataService.refreshDanmu(downloaderEntity);
	}
	
	/**
	 * 更新并检查应用及yt-dlp版本
	 * @param checkAndUpdate
	 * @param request
	 * @return
	 */
	@GetMapping(value = "/checkAndUpdate")
	public AjaxEntity checkAndUpdate(String proxyup) {
		return systemService.checkAndUpdate(proxyup);
	}
	
	/**
	 * 直链提交
	 * @param directData
	 * @param request
	 * @return
	 */
	@PostMapping(value = "/directData")
	public AjaxEntity directData(VideoDataEntity video,HttpServletRequest request) {
		return analysisService.directData(video,request);
	}
	
	
	@GetMapping(value = "/isNeedRefreshAndUpdate")
	public AjaxEntity isNeedRefreshAndUpdate() {
		biliConfigService.isNeedRefreshAndUpdate();
		cookiesConfigService.checkCookieStatus();
		return new AjaxEntity(Global.ajax_success, "提交检查通知,请检查webhook通知", null);
	}
	
	
	/**
	 * 最新入库
	 * @return
	 */
	@GetMapping(value = "/recentlyAdded")
	public AjaxEntity recentlyAdded() {
		Map<String, Object> result = new HashMap<>();
		result.put("graphicContent", graphicContentService.findRecentlyAdded());
		result.put("videoData", videoDataService.findRecentlyAdded());
		return new AjaxEntity(Global.ajax_success, "数据获取成功", result);
	}

	@PostMapping(value = "/hlsEnqueueByIds")
	public AjaxEntity hlsEnqueueByIds(String ids) {
		return hlsTranscodeService.enqueueByIds(ids);
	}

	@PostMapping(value = "/hlsEnqueueMissingLatest")
	public AjaxEntity hlsEnqueueMissingLatest(Integer limit) {
		int v = limit == null ? 200 : limit.intValue();
		return hlsTranscodeService.enqueueMissingLatest(v);
	}

	@PostMapping(value = "/hlsRebuildByIds")
	public AjaxEntity hlsRebuildByIds(String ids) {
		return hlsTranscodeService.rebuildByIds(ids);
	}

	@PostMapping(value = "/hlsRebuildLatest")
	public AjaxEntity hlsRebuildLatest(Integer limit) {
		int v = limit == null ? 200 : limit.intValue();
		return hlsTranscodeService.rebuildLatest(v);
	}

	@PostMapping(value = "/hlsProcessNow")
	public AjaxEntity hlsProcessNow() {
		return hlsTranscodeService.processNowOnce();
	}

	@GetMapping(value = "/hlsStats")
	public AjaxEntity hlsStats() {
		return hlsTranscodeService.stats();
	}

	@GetMapping(value = "/videoAuthors")
	public AjaxEntity videoAuthors() {
		return new AjaxEntity(Global.ajax_success, "数据获取成功", videoDataService.findDistinctAuthors());
	}

	@GetMapping(value = "/graphicAuthors")
	public AjaxEntity graphicAuthors() {
		return new AjaxEntity(Global.ajax_success, "数据获取成功", graphicContentService.findDistinctAuthors());
	}

	@GetMapping(value = "/authorDownloadStats")
	public AjaxEntity authorDownloadStats() {
		return new AjaxEntity(Global.ajax_success, "数据获取成功", collectDataService.authorDownloadStats());
	}
	
//	@ResponseBody
//	@GetMapping("/playStream")
//	public Map<String, Object> playStream(@RequestParam String videoid,@RequestParam(defaultValue = "720p") String quality) throws Exception {
//	    VideoDataEntity byId = videoDataService.findById(videoid);
//	    if (byId == null) {
//	        return Map.of("error", "video not found");
//	    }
//	    int[] wh = ResolutionUtil.select(byId.getVideoaddr(), quality);
//	    int width = wh[0];
//	    int height = wh[1];
//	    String sessionId = UUID.randomUUID().toString().replace("-", "");
//	    String outDir = Global.apppath + "/hls/" + sessionId;
//	    new File(outDir).mkdirs();
//	    List<String> cmd = FfmpegCommandBuilder.build(byId.getVideoaddr(), width, height, outDir);
//	    new ProcessBuilder(cmd).redirectErrorStream(true).start();
//	    return Map.of("sessionId", sessionId,"m3u8", "/playStream/hls/" + sessionId + "/index.m3u8");
//	}
//	
//	@GetMapping("/playStream/hls/{sessionId}/{file}")
//	public void hlsFile(@PathVariable String sessionId,@PathVariable String file,HttpServletResponse response) throws Exception {
//	    File f = new File(Global.apppath + "/hls/" + sessionId + "/" + file);
//	    if (!f.exists()) {
//	        response.setStatus(404);
//	        return;
//	    }
//	    if (file.endsWith(".m3u8")) {
//	        response.setContentType("application/x-mpegURL");
//	    } else if (file.endsWith(".ts")) {
//	        response.setContentType("video/mp2t");
//	    }
//	    try (InputStream in = new FileInputStream(f);
//	         OutputStream out = response.getOutputStream()) {
//	        in.transferTo(out);
//	    }
//	}




}
