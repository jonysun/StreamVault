package com.flower.spirit.web.admin;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.common.RequestEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dto.UpdateWorkMetadataRequest;
import com.flower.spirit.dto.WorkOperationRequest;
import com.flower.spirit.dto.AdminAuthorDeletionRequest;
import com.flower.spirit.dto.AdminDeleteWorkRequest;
import com.flower.spirit.dto.DatabaseMaintenanceRequest;
import com.flower.spirit.dto.MediaFeedRequest;
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
import com.flower.spirit.service.CollectEnqueueResult;
import com.flower.spirit.service.CollectEnqueueService;
import com.flower.spirit.service.CollectRunQueryService;
import com.flower.spirit.service.CollectRunService;
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
import com.flower.spirit.service.DatabaseAuditService;
import com.flower.spirit.service.DatabaseMaintenanceService;
import com.flower.spirit.service.DownloaderService;
import com.flower.spirit.service.DownloadCenterService;
import com.flower.spirit.service.GraphicContentService;
import com.flower.spirit.service.HlsTranscodeService;
import com.flower.spirit.service.MediaFeedService;
import com.flower.spirit.service.Mp4FaststartMaintenanceService;
import com.flower.spirit.service.ProcessHistoryService;
import com.flower.spirit.service.RuntimeControlService;
import com.flower.spirit.service.ApplicationReadinessGate;
import com.flower.spirit.service.RuntimeControlSnapshot;
import com.flower.spirit.service.PlatformCookieService;
import com.flower.spirit.service.RuntimeJobQueryService;
import com.flower.spirit.service.SystemService;
import com.flower.spirit.service.TikTokConfigService;
import com.flower.spirit.service.UserService;
import com.flower.spirit.service.VideoDataService;
import com.flower.spirit.service.VideoMixService;
import com.flower.spirit.service.WorkMetadataEditService;
import com.flower.spirit.service.WorkRedownloadService;
import com.flower.spirit.service.WorkRefreshService;
import com.flower.spirit.platform.WorkMetadataValidationException;
import com.flower.spirit.database.sqlite.SqliteRuntimeVerifier;


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
	private CollectRunQueryService collectRunQueryService;

	@Autowired
	private CollectEnqueueService collectEnqueueService;

	@Autowired
	private CollectRunService collectRunService;

	@Autowired
	private RuntimeControlService runtimeControlService;

	@Autowired
	private ApplicationReadinessGate applicationReadinessGate;

	@Autowired
	private RuntimeJobQueryService runtimeJobQueryService;

	@Autowired
	private DownloadCenterService downloadCenterService;

	@Autowired
	private DatabaseAuditService databaseAuditService;

	@Autowired
	private DatabaseMaintenanceService databaseMaintenanceService;

	@Autowired
	private ObjectProvider<SqliteRuntimeVerifier> sqliteRuntimeVerifierProvider;
	
	
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
	private Mp4FaststartMaintenanceService mp4FaststartMaintenanceService;

	@Autowired
	private DouyinWorkMaintenanceService douyinWorkMaintenanceService;

	@Autowired
	private DouyinCookieHealthService douyinCookieHealthService;

	@Autowired
	private PlatformCookieService platformCookieService;

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

	@GetMapping(value = "/media-feed")
	public AjaxEntity findMediaFeed(MediaFeedRequest request) {
		try {
			return new AjaxEntity(Global.ajax_success, "媒体列表获取成功", mediaFeedService.findCursorPage(request));
		} catch (IllegalArgumentException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
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

	@GetMapping(value = "/mp4FaststartPreview")
	public AjaxEntity mp4FaststartPreview(Integer afterId, Integer limit, HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		return new AjaxEntity(Global.ajax_success, "MP4 faststart preview",
				mp4FaststartMaintenanceService.preview(afterId, limit));
	}

	@PostMapping(value = "/mp4FaststartApply")
	public AjaxEntity mp4FaststartApply(@RequestBody List<Integer> ids, HttpServletRequest request) {
		if (!hasAuthenticatedAdmin(request)) {
			return new AjaxEntity(Global.ajax_login_err, "Unauthorized", null);
		}
		return new AjaxEntity(Global.ajax_success, "MP4 faststart apply completed",
				mp4FaststartMaintenanceService.apply(ids));
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
	public AjaxEntity deleteCollectData(CollectDataEntity collectDataEntity,
			@RequestParam(defaultValue = "CANCEL_QUEUE") String queuePolicy, HttpServletRequest request) {
		return collectDataService.deleteCollectData(collectDataEntity, queuePolicy);
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
		RuntimeControlSnapshot snapshot = runtimeControlService.snapshot();
		Map<String, Object> control = new HashMap<>();
		control.put("allPaused", snapshot.allPaused());
		control.put("downloadPaused", snapshot.downloadPaused());
		control.put("collectPaused", snapshot.collectPaused());
		control.put("hlsPaused", snapshot.hlsPaused());
		control.put("effectiveDownloadPaused", snapshot.effectiveDownloadPaused());
		control.put("effectiveCollectPaused", snapshot.effectiveCollectPaused());
		control.put("effectiveHlsPaused", snapshot.effectiveHlsPaused());
		control.put("values", snapshot.values());
		return control;
	}

	@GetMapping("/collect-tasks/{taskId}/runs")
	public List<Map<String, Object>> findCollectRuns(@PathVariable Integer taskId,
			@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") long afterId) {
		return collectRunQueryService.findRuns(taskId, limit, afterId);
	}

	@GetMapping("/collect-runs/{runId}")
	public Map<String, Object> findCollectRun(@PathVariable long runId) {
		return collectRunQueryService.findRun(runId);
	}

	@GetMapping("/collect-runs/{runId}/items")
	public List<Map<String, Object>> findCollectRunItems(@PathVariable long runId,
			@RequestParam(defaultValue = "all") String decision, @RequestParam(defaultValue = "100") int limit,
			@RequestParam(defaultValue = "0") long afterId) {
		return collectRunQueryService.findItems(runId, decision, limit, afterId);
	}

	@GetMapping("/collect-runs/{runId}/events")
	public List<Map<String, Object>> findCollectRunEvents(@PathVariable long runId,
			@RequestParam(defaultValue = "0") int afterSequence, @RequestParam(defaultValue = "200") int limit) {
		return collectRunQueryService.findEvents(runId, afterSequence, limit);
	}

	@PostMapping("/collectData/retryItem")
	public AjaxEntity retryCollectDownloadItem(@RequestParam long id) {
		try {
			return new AjaxEntity(Global.ajax_success, "下载项已重新排队", collectRunService.retryDownloadItem(id));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@PostMapping("/collectData/retryFailedItems")
	public AjaxEntity retryCollectDownloadItems(@RequestParam long runId) {
		try {
			return new AjaxEntity(Global.ajax_success, "失败下载项已重新排队",
					Map.of("requeued", collectRunService.retryFailedDownloads(runId)));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@PostMapping("/collectData/audit")
	public AjaxEntity auditCollectTask(@RequestParam int taskId) {
		try {
			CollectEnqueueResult result = collectEnqueueService.enqueueAudit(taskId);
			if (result.skippedUnsupported() || result.skippedPaused()) {
				return new AjaxEntity(Global.ajax_uri_error,
						result.reason() == null ? "全量审计未能入队" : result.reason(), result);
			}
			return new AjaxEntity(Global.ajax_success, "全量审计已排队", result);
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@GetMapping("/collectData/downloadQueue")
	public AjaxEntity collectDownloadQueue(@RequestParam(required = false) Integer taskId,
			@RequestParam(defaultValue = "100") int limit) {
		try {
			return new AjaxEntity(Global.ajax_success, "下载队列获取成功",
					collectRunQueryService.downloadQueue(taskId, limit));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@GetMapping("/collect-tasks/{taskId}/latest-items")
	public Map<String, Object> findLatestCollectItems(@PathVariable int taskId,
			@RequestParam(defaultValue = "all") String view, @RequestParam(defaultValue = "500") int limit,
			@RequestParam(defaultValue = "0") long afterId) {
		return collectRunQueryService.findLatestItems(taskId, view, limit, afterId);
	}

	@PostMapping("/collect-runs/{runId}/requeue-preview")
	public Map<String, Object> previewCollectRunRequeue(@PathVariable long runId) {
		return collectRunQueryService.requeuePreview(runId, Global.isCollectPaused());
	}

	@PostMapping("/collect-runs/{runId}/requeue")
	public AjaxEntity requeueCollectRun(@PathVariable long runId) {
		Map<String, Object> preview = collectRunQueryService.requeuePreview(runId, Global.isCollectPaused());
		if (!Boolean.TRUE.equals(preview.get("canRequeue"))) {
			return new AjaxEntity(Global.ajax_uri_error, "当前运行不可重排队", preview);
		}
		try {
			int taskId = ((Number) preview.get("taskId")).intValue();
			CollectEnqueueResult result = collectEnqueueService.enqueueManual(taskId);
			if (result.skippedUnsupported()) {
				return new AjaxEntity(Global.ajax_uri_error, result.reason(), result);
			}
			return new AjaxEntity(Global.ajax_success, "已重新入队", result);
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, "重排队失败: " + error.getMessage(), null);
		}
	}

	@GetMapping(value = "/getBackgroundTaskStatus")
	public AjaxEntity getBackgroundTaskStatus() {
		try {
			Map<String, Object> result = new HashMap<>();
			result.put("control", buildBackgroundTaskControlStatus());
			result.put("analysis", analysisService.getThreadPoolStatus());
			result.put("collect", collectDataService.getCollectThreadPoolStatus());
			result.put("jobs", runtimeJobQueryService.dashboard(50));
			AjaxEntity hls = hlsTranscodeService.stats();
			result.put("hls", hls == null ? null : hls.getRecord());
			return new AjaxEntity(Global.ajax_success, "获取后台任务状态成功", result);
		} catch (Exception e) {
			return new AjaxEntity(Global.ajax_uri_error, "获取后台任务状态失败: " + e.getMessage(), null);
		}
	}

	@PostMapping(value = "/setBackgroundTaskPause")
	public AjaxEntity setBackgroundTaskPause(String scope, String paused, String reason,
			HttpServletRequest request) {
		boolean value = "1".equals(paused) || "true".equalsIgnoreCase(String.valueOf(paused)) || "Y".equalsIgnoreCase(String.valueOf(paused));
		try {
			runtimeControlService.set(scope, value, runtimeActor(request), reason);
			return getBackgroundTaskStatus();
		} catch (IllegalArgumentException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@GetMapping("/runtime-controls")
	public AjaxEntity runtimeControls() {
		return new AjaxEntity(Global.ajax_success, "获取运行控制成功", runtimeControlService.snapshot());
	}

	@GetMapping("/database-runtime")
	public AjaxEntity databaseRuntime() {
		SqliteRuntimeVerifier sqlite = sqliteRuntimeVerifierProvider.getIfAvailable();
		Map<String, Object> status = new HashMap<>();
		status.put("database", sqlite == null ? Map.of("databaseKind", "postgresql") : sqlite.snapshot());
		status.put("readiness", applicationReadinessGate.snapshot());
		return new AjaxEntity(Global.ajax_success, "获取数据库运行状态成功", status);
	}

	@GetMapping("/download-center/summary")
	public AjaxEntity downloadCenterSummary() {
		return new AjaxEntity(Global.ajax_success, "Download summary loaded", downloadCenterService.summary());
	}

	@GetMapping("/download-center/items")
	public AjaxEntity downloadCenterItems(@RequestParam(defaultValue = "active") String view,
			@RequestParam(defaultValue = "ALL") String source,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String keyword,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int pageSize) {
		return new AjaxEntity(Global.ajax_success, "Download records loaded",
				downloadCenterService.items(view, source, state, keyword, page, pageSize));
	}

	@PostMapping("/download-center/retry")
	public AjaxEntity retryDownloadCenterItem(@RequestParam String recordKey) {
		try {
			boolean retried = downloadCenterService.retry(recordKey);
			return new AjaxEntity(retried ? Global.ajax_success : Global.ajax_uri_error,
					retried ? "Download requeued" : "Only failed downloads can be retried", null);
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@PostMapping("/download-center/retry-batch")
	public AjaxEntity retryDownloadCenterItems(@RequestBody Map<String, List<String>> request) {
		try {
			return new AjaxEntity(Global.ajax_success, "Failed downloads requeued",
					Map.of("requeued", downloadCenterService.retry(request.get("recordKeys"))));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@PostMapping("/download-center/history/hide")
	public AjaxEntity hideDownloadCenterHistory(@RequestBody Map<String, List<String>> request) {
		try {
			return new AjaxEntity(Global.ajax_success, "Download history hidden",
					Map.of("hidden", downloadCenterService.hideHistory(request.get("recordKeys"))));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@PostMapping("/download-center/transition")
	public AjaxEntity transitionDownloadCenterItems(@RequestBody Map<String, Object> request) {
		try {
			List<String> keys = stringList(request.get("recordKeys"));
			List<String> excludedKeys = stringList(request.get("excludedKeys"));
			String action = String.valueOf(request.getOrDefault("action", ""));
			boolean allMatching = Boolean.TRUE.equals(request.get("allMatching"));
			String view = request.get("view") == null ? null : String.valueOf(request.get("view"));
			String source = request.get("source") == null ? null : String.valueOf(request.get("source"));
			String state = request.get("state") == null ? null : String.valueOf(request.get("state"));
			String keyword = request.get("keyword") == null ? null : String.valueOf(request.get("keyword"));
			return new AjaxEntity(Global.ajax_success, "下载队列已更新",
					downloadCenterService.transition(keys, action, allMatching, excludedKeys, view, source, state, keyword));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	private List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) return List.of();
		return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}

	@PostMapping("/douyin/clear-global-cooldown")
	public AjaxEntity clearDouyinGlobalCooldown(HttpServletRequest request) {
		UserEntity user = (UserEntity) request.getSession().getAttribute(Global.user_session_key);
		String operator = user == null ? "unknown" : String.valueOf(user.getUsername());
		return new AjaxEntity(Global.ajax_success, "已解除一次抖音全局风控冷却",
				platformCookieService.clearDouyinGlobalCooldown(operator));
	}

	@PostMapping("/download-center/delete-and-block")
	public AjaxEntity deleteAndBlockDownloadCenterItems(@RequestBody Map<String, List<String>> request) {
		try {
			return new AjaxEntity(Global.ajax_success, "下载项已删除并加入黑名单",
					Map.of("blocked", downloadCenterService.deleteAndBlock(request.get("recordKeys"))));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@PostMapping("/runtime-controls/pause-all")
	public AjaxEntity pauseAllRuntimeTasks(@RequestBody(required = false) Map<String, Object> body,
			HttpServletRequest request) {
		return updateRuntimeControl("all", true, body, request);
	}

	@PostMapping("/runtime-controls/resume-all")
	public AjaxEntity resumeAllRuntimeTasks(@RequestBody(required = false) Map<String, Object> body,
			HttpServletRequest request) {
		return updateRuntimeControl("all", false, body, request);
	}

	@PostMapping("/runtime-controls/{category}/pause")
	public AjaxEntity pauseRuntimeCategory(@PathVariable String category,
			@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
		return updateRuntimeControl(category, true, body, request);
	}

	@PostMapping("/runtime-controls/{category}/resume")
	public AjaxEntity resumeRuntimeCategory(@PathVariable String category,
			@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
		return updateRuntimeControl(category, false, body, request);
	}

	@GetMapping("/runtime-jobs")
	public AjaxEntity runtimeJobs(@RequestParam(defaultValue = "RUNNING,QUEUED,RETRY_WAIT") String state,
			@RequestParam(defaultValue = "100") int limit) {
		return new AjaxEntity(Global.ajax_success, "获取运行任务成功", runtimeJobQueryService.findJobs(state, limit));
	}

	@GetMapping("/database/audit")
	public AjaxEntity databaseAudit() {
		return new AjaxEntity(Global.ajax_success, "数据库审计完成", databaseAuditService.audit());
	}

	@PostMapping("/database/maintenance/preview")
	public AjaxEntity previewDatabaseMaintenance(@RequestBody(required = false) DatabaseMaintenanceRequest request) {
		try {
			return new AjaxEntity(Global.ajax_success, "数据库维护预览完成",
					databaseMaintenanceService.preview(request == null ? null : request.getOperations()));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@PostMapping("/database/maintenance/apply")
	public AjaxEntity applyDatabaseMaintenance(@RequestBody DatabaseMaintenanceRequest request) {
		try {
			return new AjaxEntity(Global.ajax_success, "数据库维护批次执行完成",
					databaseMaintenanceService.apply(request));
		} catch (RuntimeException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	@GetMapping("/database/maintenance/{operationId}")
	public AjaxEntity databaseMaintenanceStatus(@PathVariable long operationId) {
		Map<String, Object> status = databaseMaintenanceService.status(operationId);
		return status.isEmpty() ? new AjaxEntity(Global.ajax_uri_error, "数据库维护任务不存在", null)
				: new AjaxEntity(Global.ajax_success, "获取数据库维护状态成功", status);
	}

	private AjaxEntity updateRuntimeControl(String scope, boolean paused, Map<String, Object> body,
			HttpServletRequest request) {
		try {
			String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
			return new AjaxEntity(Global.ajax_success, paused ? "已暂停" : "已恢复",
					runtimeControlService.set(scope, paused, runtimeActor(request), reason));
		} catch (IllegalArgumentException error) {
			return new AjaxEntity(Global.ajax_uri_error, error.getMessage(), null);
		}
	}

	private String runtimeActor(HttpServletRequest request) {
		Object sessionUser = request == null || request.getSession(false) == null ? null
				: request.getSession(false).getAttribute(Global.user_session_key);
		if (sessionUser instanceof UserEntity user && user.getUsername() != null && !user.getUsername().isBlank()) {
			return user.getUsername();
		}
		return "admin";
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
			return new AjaxEntity(Global.ajax_success, "作者资料刷新任务已入队",
					douyinAuthorProfileRefreshService.refresh(authorProfileId));
		} catch (WorkMetadataValidationException e) {
			return new AjaxEntity(Global.ajax_uri_error, e.getMessage(), null);
		}
	}

	@GetMapping(value = "/authorProfileSummary")
	public AjaxEntity authorProfileSummary(String platformkey, String platform, String authoruid, String authorusername,
			String author) {
		return authorProfileService.findProfileSummary(platformkey, platform, authoruid, authorusername, author);
	}

	@GetMapping(value = "/authorProfileWorks")
	public AjaxEntity authorProfileWorks(String platformkey, String platform, String authoruid, String authorusername, String author,
			String type, Integer pageNo, Integer pageSize) {
		return authorProfileService.findProfileWorks(platformkey, platform, authoruid, authorusername, author, type, pageNo, pageSize);
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
