package com.flower.spirit.service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.dao.CollectdDataDetailDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.CollectDataDetailEntity;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.GraphicContentEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.dto.CollectTaskListItem;
import com.flower.spirit.executor.DouYinExecutor;
import com.flower.spirit.platform.PlatformCatalog;
import com.flower.spirit.task.QuartzTaskService;
import com.flower.spirit.utils.Aria2Util;
import com.flower.spirit.utils.BiliUtil;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.DateUtils;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.DouyinSourceUrlUtil;
import com.flower.spirit.utils.AuthorIdentityUtil;
import com.flower.spirit.utils.EmbyMetadataGenerator;
import com.flower.spirit.utils.FileUtil;
import com.flower.spirit.utils.FileNameTemplateUtil;
import com.flower.spirit.utils.HttpUtil;
import com.flower.spirit.utils.StringUtil;
import com.flower.spirit.utils.XbogusUtil;
import com.flower.spirit.utils.sendNotify;

@Service
public class CollectDataService {

	private static final Pattern PYTHON_EXCEPTION_PATTERN = Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*(?:Error|Exception)):\\s*(.+)$");
	private static final Pattern PYTHON_FILE_PATTERN = Pattern.compile("File \"([^\"]+)\", line (\\d+), in ([^\\r\\n]+)");
	private static final Pattern SNAPSHOT_HAS_VIDEO_PATTERN = Pattern.compile("\"has_video_play_addr\"\\s*:\\s*(true|false)");

	@Autowired
	private CollectdDataDao collectdDataDao;

	@Autowired
	private CollectDataDetailService collectDataDetailService;

	@Autowired
	private CollectdDataDetailDao collectDataDetailDao;

	@Autowired
	private VideoDataService videoDataService;

	@Autowired
	private VideoDataDao videoDataDao;

	@Autowired
	private GraphicContentDao graphicContentDao;

	@Autowired
	private HlsTranscodeService hlsTranscodeService;

	@Autowired
	private AuthorProfileService authorProfileService;

	@Autowired
	private BlockedWorkService blockedWorkService;

	@Autowired
	private PlatformCookieService platformCookieService;

	@Autowired
	private DouyinCookieHealthService douyinCookieHealthService;

	@Autowired
	private CollectEnqueueService collectEnqueueService;

	@Autowired
	private CollectRunService collectRunService;

	@Autowired
	private RuntimeControlService runtimeControlService;

	@Autowired
	private SnapshotCodec snapshotCodec;

	@Autowired
	private CollectRunQueryService collectRunQueryService;

	@Autowired
	private RawPayloadService rawPayloadService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Logger logger = LoggerFactory.getLogger(CollectDataService.class);

	@Autowired
	private QuartzTaskService quartzTaskService;

	private volatile long lastCollectTaskFinishedAt = 0L;
	private final ThreadLocal<F2FailureDiagnosis> lastF2FailureDiagnosis = new ThreadLocal<>();
	private final ThreadLocal<FetchRunContext> lastFetchRunContext = new ThreadLocal<>();
	private final ThreadLocal<Long> activePersistentRunId = new ThreadLocal<>();

	/**
	 * 文件储存真实路径
	 */
	@Value("${file.save.path}")
	private String uploadRealPath;

	/**
	 * 映射路径
	 */
	@Value("${file.save}")
	private String savefile;
	
    @Transactional
	public synchronized AjaxEntity saveCollectData(CollectDataEntity entity) {
		if (entity == null) {
			return new AjaxEntity(Global.ajax_uri_error, "收藏任务不能为空", null);
		}
		String originalAddress = normalizeCollectAddress(entity.getOriginaladdress());
		if (originalAddress == null) {
			return new AjaxEntity(Global.ajax_uri_error, "收藏任务来源地址不能为空", null);
		}
		entity.setOriginaladdress(originalAddress);
		entity.setPlatform(trimToNull(entity.getPlatform()));
		entity.setTaskname(trimToNull(entity.getTaskname()));
		CollectDataEntity duplicate = findDuplicateCollectTask(entity.getPlatform(), originalAddress);
		if (duplicate != null) {
			String message = "收藏任务已存在：ID " + duplicate.getId() + "，"
					+ valueOr(duplicate.getTaskname(), "未命名任务") + "，状态 "
					+ valueOr(duplicate.getTaskstatus(), "未执行");
			return new AjaxEntity(Global.ajax_uri_error, message, duplicate);
		}
		if (entity.getTaskenabled() == null || entity.getTaskenabled().trim().isEmpty()) {
			entity.setTaskenabled("Y");
		}
        collectdDataDao.save(entity);
        quartzTaskService.scheduleTask(entity);
     	return new AjaxEntity(Global.ajax_success, "任务创建成功", entity);
    }
    

    public AjaxEntity findPage(CollectDataEntity res) {
		int pageNo = res == null ? 0 : Math.max(0, res.getPageNo());
		int pageSize = res == null ? 25 : Math.min(Math.max(1, res.getPageSize()), 200);
		String taskId = res == null || !StringUtil.isString(res.getTaskid()) ? null : "%" + res.getTaskid() + "%";
		String platform = res == null || !StringUtil.isString(res.getPlatform()) ? null : "%" + res.getPlatform() + "%";
		String keyword = res == null || !StringUtil.isString(res.getKeyword()) ? null : "%" + res.getKeyword().trim() + "%";
		List<String> filters = new ArrayList<>();
		List<Object> parameters = new ArrayList<>();
		if (taskId != null) {
			filters.add("taskid LIKE ?");
			parameters.add(taskId);
		}
		if (platform != null) {
			filters.add("platform LIKE ?");
			parameters.add(platform);
		}
		if (keyword != null) {
			filters.add("(LOWER(COALESCE(taskname, '')) LIKE LOWER(?) OR CAST(id AS TEXT) LIKE ? "
					+ "OR LOWER(COALESCE(taskid, '')) LIKE LOWER(?) OR LOWER(COALESCE(originaladdress, '')) LIKE LOWER(?) "
					+ "OR LOWER(COALESCE(platform, '')) LIKE LOWER(?))");
			for (int i = 0; i < 5; i++) {
				parameters.add(keyword);
			}
		}
		String where = filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters);
		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM biz_collect_data" + where, Long.class,
				parameters.toArray());
		String sql = "SELECT id, taskid, platform, taskname, taskstatus, createtime, endtime, count, carriedout, "
				+ "originaladdress, monitoring, taskenabled, lastCheckTime, lastid, maxcur, omaxcur, generatenfo, "
				+ "taskcron, lastfetchtime, lastfetchcount FROM biz_collect_data" + where
				+ " ORDER BY id DESC LIMIT ? OFFSET ?";
		List<Object> pageParameters = new ArrayList<>(parameters);
		pageParameters.add(pageSize);
		pageParameters.add((long) pageNo * pageSize);
		List<CollectTaskListItem> items = jdbcTemplate.query(sql, (row, index) -> new CollectTaskListItem(
				row.getInt("id"), row.getString("taskid"), row.getString("platform"), row.getString("taskname"),
				row.getString("taskstatus"), row.getString("createtime"), row.getString("endtime"),
				row.getString("count"), row.getString("carriedout"), row.getString("originaladdress"),
				row.getString("monitoring"), row.getString("taskenabled"), row.getString("lastCheckTime"),
				row.getString("lastid"), nullableInteger(row, "maxcur"), nullableInteger(row, "omaxcur"),
				row.getString("generatenfo"), row.getString("taskcron"), row.getString("lastfetchtime"),
				nullableInteger(row, "lastfetchcount")), pageParameters.toArray());
		Page<CollectTaskListItem> page = new PageImpl<>(items, PageRequest.of(pageNo, pageSize),
				total == null ? 0 : total);
		return new AjaxEntity(Global.ajax_success, "数据获取成功", page);
    }

	private CollectDataEntity findDuplicateCollectTask(String platform, String originalAddress) {
		String platformKey = normalizeCollectPlatform(platform);
		return collectdDataDao.findByNormalizedOriginalAddress(originalAddress).stream()
				.filter(existing -> platformKey.equals(normalizeCollectPlatform(existing.getPlatform())))
				.findFirst().orElse(null);
	}

	private String normalizeCollectPlatform(String platform) {
		String normalized = trimToNull(platform);
		if (normalized == null) {
			return "";
		}
		return PlatformCatalog.findByAlias(normalized)
				.map(definition -> definition.getKey().toLowerCase(Locale.ROOT))
				.orElse(normalized.toLowerCase(Locale.ROOT));
	}

	private String normalizeCollectAddress(String address) {
		String normalized = trimToNull(address);
		if (normalized == null) {
			return null;
		}
		for (String prefix : List.of("bili-seaarc-", "bili-fav-", "bili-arc-", "recommend", "post", "like", "fav-")) {
			if (normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
				return prefix + normalized.substring(prefix.length()).trim();
			}
		}
		return normalized;
	}

	private String trimToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	private String valueOr(String value, String fallback) {
		String normalized = trimToNull(value);
		return normalized == null ? fallback : normalized;
	}

	private Integer nullableInteger(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		int value = row.getInt(column);
		return row.wasNull() ? null : value;
	}


	public AjaxEntity deleteCollectData(CollectDataEntity collectDataEntity) {
		try {
			if (quartzTaskService.isTaskRunning(collectDataEntity.getId())) {
				return new AjaxEntity(Global.ajax_uri_error, "任务正在执行中，请稍后再试", null);
			}
			quartzTaskService.deleteTask(collectDataEntity.getId());
			collectDataDetailService.deleteDataid(collectDataEntity.getId());
			collectdDataDao.deleteById(collectDataEntity.getId());

			return new AjaxEntity(Global.ajax_success, "操作成功", null);
		} catch (Exception e) {
			logger.error("删除失败：{}", collectDataEntity.getTaskname(), e);
			return new AjaxEntity(Global.ajax_uri_error, "删除失败", null);
		}
	}

	/**
	 * 提交任务
	 * 
	 * @param collectDataEntity
	 * @return
	 */
	public AjaxEntity submitCollectData(CollectDataEntity collectDataEntity, String monitor) {
		logger.info("[CollectTask] submit start id={} name={} platform={} monitorParam={} monitoring={} originaladdress={} taskCron={} maxcur={} omaxcur={}",
				collectDataEntity.getId(), collectDataEntity.getTaskname(), collectDataEntity.getPlatform(), monitor,
				collectDataEntity.getMonitoring(), collectDataEntity.getOriginaladdress(), collectDataEntity.getTaskcron(),
				collectDataEntity.getMaxcur(), collectDataEntity.getOmaxcur());
		if (Global.isCollectPaused()) {
			logger.info("[CollectTask] collection paused, skip submit id={} name={}", collectDataEntity.getId(), collectDataEntity.getTaskname());
			return new AjaxEntity(Global.ajax_uri_error, "收藏/爬取任务已暂停，本次跳过", null);
		}
		if (null != collectDataEntity.getPlatform() && collectDataEntity.getPlatform().equals("哔哩")) {
			// 必须授权ck
			if (null == Global.bilicookies || Global.bilicookies.equals("")) {
				logger.info("必须填写bili ck,本次执行失败");
				return new AjaxEntity(Global.ajax_uri_error, "必须填写bili ck", null);
			}
			// 判断类别
			// 执行不同的调度
			if (collectDataEntity.getOriginaladdress().startsWith("bili-fav-")) {
				return createBillFav(collectDataEntity, monitor);
			}

			if (collectDataEntity.getOriginaladdress().startsWith("bili-arc-")) {
				return createBillArc(collectDataEntity, monitor);
			}
			if (collectDataEntity.getOriginaladdress().startsWith("bili-seaarc-")) {
				return createBillSeasonsArchives(collectDataEntity, monitor);
			}

		}
		if (null != collectDataEntity.getPlatform() && collectDataEntity.getPlatform().equals("抖音")) {
			String submitCookie = platformCookieService.currentDouyinCookie("submit_collect");
			if (null == submitCookie || submitCookie.equals("")) {
				logger.error("[CollectTask] douyin cookie missing id={} name={}", collectDataEntity.getId(), collectDataEntity.getTaskname());
				return new AjaxEntity(Global.ajax_uri_error, "此功能必须填写ck", null);
			}
			if (collectDataEntity.getOriginaladdress().startsWith("post")
					|| collectDataEntity.getOriginaladdress().startsWith("like")
					|| collectDataEntity.getOriginaladdress().startsWith("fav-")
					|| collectDataEntity.getOriginaladdress().startsWith("recommend")) {
				logger.info("[CollectTask] douyin route matched id={} originaladdress={}", collectDataEntity.getId(), collectDataEntity.getOriginaladdress());
				try {
					// 进线程前创建collectDataEntity
					collectDataEntity.setTaskstatus("已提交待处理");
					collectDataEntity.setCreatetime(DateUtils.formatDateTime(new Date()));
					collectDataEntity.setCount("0");
					// collectDataEntity.setCarriedout("0"); // 归零
					CollectDataEntity save = collectdDataDao.save(collectDataEntity);
					if (null != monitor && monitor.equals("Y")) {
						this.createDyData(save, "Y");
						//然后判断字段是不是监控 如果不是 删除这个触发器
						String monitoring = collectDataEntity.getMonitoring();
						if (!"Y".equals(monitoring)) {
						    quartzTaskService.deleteTask(collectDataEntity.getId());
						}
						return new AjaxEntity(Global.ajax_success, "任务启动成功", null);
					}
					return new AjaxEntity(Global.ajax_success, "任务创建成功", null);

				} catch (Exception e) {
					logger.error("[CollectTask] synchronous execution failed taskId={}", collectDataEntity.getId(), e);
					throw new IllegalStateException("收藏任务执行失败: " + rootCauseMessage(e), e);
				}

			} else {
				logger.warn("[CollectTask] unsupported douyin originaladdress id={} originaladdress={}", collectDataEntity.getId(), collectDataEntity.getOriginaladdress());
				return new AjaxEntity(Global.ajax_uri_error, "请按页面要求填写地址", null);
			}

		}
		return null;
	}

	/**
	 * 获取Quartz任务调度器状态信息
	 * 
	 * @return 任务调度器状态的Map集合
	 */
	public Map<String, Object> getCollectThreadPoolStatus() {
		Map<String, Object> status = new HashMap<>();
		status.put("paused", Global.isCollectPaused());

		try {
			// Quartz调度器基本信息
			Map<String, Object> quartzStatus = new HashMap<>();
			quartzStatus.put("schedulerName", "收藏任务调度器");
			quartzStatus.put("schedulerType", "Quartz Scheduler");

			// 获取调度器状态信息，可能抛出SchedulerException
			try {
				quartzStatus.put("isStarted", quartzTaskService.getScheduler().isStarted());
				quartzStatus.put("isShutdown", quartzTaskService.getScheduler().isShutdown());
				quartzStatus.put("isInStandbyMode", quartzTaskService.getScheduler().isInStandbyMode());
			} catch (SchedulerException e) {
				logger.error("获取调度器状态失败", e);
				quartzStatus.put("isStarted", false);
				quartzStatus.put("isShutdown", true);
				quartzStatus.put("isInStandbyMode", false);
				quartzStatus.put("schedulerError", "调度器状态获取失败：" + e.getMessage());
			}

			// 获取所有收藏夹任务
			List<CollectDataEntity> allTasks = collectdDataDao.findByMonitoring("Y");
			quartzStatus.put("totalTasks", allTasks.size());

			// 获取当前正在执行的任务详情
			List<Map<String, Object>> executingDetails = new ArrayList<>();
			try {
				List<JobExecutionContext> executingJobs = quartzTaskService.getScheduler().getCurrentlyExecutingJobs();

				for (JobExecutionContext context : executingJobs) {
					if ("collect".equals(context.getJobDetail().getKey().getGroup())) {
						Map<String, Object> execInfo = new HashMap<>();
						execInfo.put("jobName", context.getJobDetail().getKey().getName());
						execInfo.put("fireTime", context.getFireTime());
						execInfo.put("scheduledFireTime", context.getScheduledFireTime());
						execInfo.put("runTime", context.getJobRunTime());
						execInfo.put("refireCount", context.getRefireCount());

						// 从JobDataMap获取任务信息
						JobDataMap dataMap = context.getJobDetail().getJobDataMap();
						execInfo.put("taskId", dataMap.getInt("taskId"));
						execInfo.put("taskName", dataMap.getString("taskName"));

						executingDetails.add(execInfo);
					}
				}
			} catch (SchedulerException e) {
				logger.error("获取正在执行的任务失败", e);
				// 添加错误信息到执行详情中
				Map<String, Object> errorInfo = new HashMap<>();
				errorInfo.put("error", "获取正在执行任务失败：" + e.getMessage());
				executingDetails.add(errorInfo);
			}

			quartzStatus.put("executingJobs", executingDetails);
			quartzStatus.put("executingJobCount", executingDetails.size());

			status.put("quartz", quartzStatus);

		} catch (Exception e) {
			logger.error("获取Quartz调度器状态失败", e);

			// 返回错误信息
			Map<String, Object> errorStatus = new HashMap<>();
			errorStatus.put("schedulerName", "Quartz任务调度器");
			errorStatus.put("error", "获取状态失败：" + e.getMessage());
			errorStatus.put("isStarted", false);
			status.put("quartz", errorStatus);
		}

		return status;
	}

	/**
	 * namepath 只是收藏夹名称
	 * 方法需要代码优化 有时间再说
	 * 
	 * @param entity
	 * @param json
	 * @throws Exception
	 */
	public void createBiliData(CollectDataEntity entity, JSONArray json, String namepath, String vt) throws Exception {
		sleepCollectTaskIntervalIfNeeded();
		entity.setTaskstatus("已开始处理");
		collectdDataDao.save(entity);
		int videoaddcount = 0;
		for (int i = 0; i < json.size(); i++) {
			JSONObject data = json.getJSONObject(i);
			String bvid = data.getString("bvid");
			List<Map<String, String>> videoDataInfo = BiliUtil.getVideoDataInfo("/video/" + bvid);
			for (int y = 0; y < videoDataInfo.size(); y++) {
				Map<String, String> map = videoDataInfo.get(y);
				String status = "";
				String currentVideoId = map == null ? bvid : map.get("cid");
				if (blockedWorkService.isBlocked("哔哩", currentVideoId, "video")) {
					continue;
				}
				if (map != null) {
					String cid = map.get("cid");
					List<VideoDataEntity> findByVideoid = videoDataService.findByVideoid(cid);
					// 这里判断 视频库 是否存在 存在则不处理
					String filename = StringUtil.getFileName(map.get("title"), cid);
					if (findByVideoid.size() == 0) {
						Map<String, String> findVideoStreaming = BiliUtil.findVideoStreamingNoData(map,
								Global.bilicookies, map.get("quality"), namepath);
						if(findVideoStreaming!= null) {
							// 从BiliUtil返回结果中获取文件名（BiliUtil已在下载时生成），覆盖默认值
							filename = findVideoStreaming.get("filename");
							String videounaddr = FileUtil.generateDir(false, Global.platform.bilibili.name(), false,
									filename, namepath, "mp4");
							String duration = findVideoStreaming.get("duration"); //视频秒数
							String aid = findVideoStreaming.get("aid");
							
							// 封面down
							String codir = FileUtil.generateDir(false, Global.platform.bilibili.name(), false, filename,
									namepath, null);
							String dir = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, filename,
									namepath, null);
							String dirpath = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, null,
									namepath, null);
							HttpUtil.downBiliFromUrl(findVideoStreaming.get("pic"), filename + ".jpg", dir);
							// 封面down
							VideoDataEntity videoDataEntity = new VideoDataEntity(findVideoStreaming.get("cid"),
									findVideoStreaming.get("title"), findVideoStreaming.get("desc"), "哔哩",
									codir + "/" + filename + ".jpg", findVideoStreaming.get("video"), videounaddr, bvid);
							rawPayloadService.storeVideoRawPayload(videoDataEntity,
									JSONObject.toJSONString(findVideoStreaming));
							logger.info(vt + (i + 1) + "下载流程结束");

							JSONObject owner = JSONObject.parseObject(map.get("owner"));
							String upface = owner.getString("face");
							String upname = owner.getString("name");
							String upmid = owner.getString("mid");
							authorProfileService.upsertAuthor("哔哩", upmid, upmid, upname, upface,
									upmid != null && !upmid.trim().isEmpty() ? "https://space.bilibili.com/" + upmid : null);
							String ctime = map.get("ctime");
							// 下载up 头像 up头像不参与数据 只参与nfo
							HttpUtil.downBiliFromUrl(upface, "upcover" + upmid + ".jpg", dir);
							String uplocal = "upcover" + upmid + ".jpg";
							if (null != Global.nfonetaddr && !"".equals(Global.nfonetaddr)) {
								uplocal = Global.nfonetaddr + codir + uplocal + "?apptoken=" + Global.readonlytoken;
							}
							String piclocal = filename + ".jpg";
							map.put("upname", upname);
							map.put("upmid", upmid);
							map.put("upface", uplocal);
							map.put("piclocal", piclocal);
							map.put("ctime", ctime);
							map.put("title", filename);
							if (Global.getGeneratenfo) {
								EmbyMetadataGenerator.createFavoriteEpisodeNfo(map, dir, i + 1, dirpath);
							}
							if(Global.danmudown && Global.bilicollectdmm) {
								BiliUtil.biliDanmaku("1", cid, aid, Integer.valueOf(duration), dir + File.separator+filename+".ass",findVideoStreaming.get("title"));
							    JSONObject videoInfoJson = new JSONObject();
						        videoInfoJson.put("aid", aid);
						        videoInfoJson.put("duration", duration);
						        rawPayloadService.storeVideoRawPayload(videoDataEntity, videoInfoJson.toJSONString());
							}
							videoDataEntity.setVideoauthor(upname);
							videoDataEntity.setAuthoruid(upmid);
							videoDataEntity.setAuthorusername(upmid);
							videoDataEntity.setAuthoravatar(upface);
							VideoDataEntity saved = videoDataDao.save(videoDataEntity);
							if (saved != null && saved.getId() != null) {
								hlsTranscodeService.enqueueByIds(String.valueOf(saved.getId()));
							}
						}else {
							logger.info(vt + (i + 1) + "-"+filename+"非常规类视频  当前不支持bangumi模式");
						}
				
					}else {
						logger.info(vt + (i + 1) + "-"+filename+"已存在,不下载");
					}
					// 新建明细
					status = findByVideoid.size() == 0 ? "已完成" : "已完成(未下载已存在)";
				} else {
					status = "视频异常下载失败";
				}
				// 这里应该判断一下CollectDataDetailEntity记录是否存在 存在 则不处理 因为已经不预删除了
				CollectDataDetailEntity collectDataDetailEntity = new CollectDataDetailEntity();
				collectDataDetailEntity.setVideoid(map == null ? bvid : map.get("cid"));
				collectDataDetailEntity.setDataid(entity.getId());
				CollectDataDetailEntity byVideoAndDataid = collectDataDetailService.findByVideoAndDataid(
						collectDataDetailEntity.getVideoid(), collectDataDetailEntity.getDataid());
				if (byVideoAndDataid == null) {
					collectDataDetailEntity.setVideoname(map.get("title"));
					collectDataDetailEntity.setOriginaladdress(bvid);
					collectDataDetailEntity.setStatus(status);
					collectDataDetailEntity.setCreatetime(DateUtils.formatDateTime(new Date()));
					collectDataDetailService.save(collectDataDetailEntity);
					// 修改主体
					String carriedout = entity.getCarriedout() == null ? "1"
							: String.valueOf(Integer.parseInt(entity.getCarriedout()) + 1);
					entity.setCarriedout(carriedout);
					collectdDataDao.save(entity);
					videoaddcount++;
				}

				sleepCollectItemInterval();
			}
		}
		if (videoaddcount > 0) {
			sendNotify.sendMessage(videoaddcount, entity.getTaskname());
		}
		entity.setTaskstatus("处理完成");
		entity.setEndtime(DateUtils.formatDateTime(new Date()));
		collectdDataDao.save(entity);
		markCollectTaskFinished();
		System.gc();

	}

	public void createDyData(CollectDataEntity entity, String monitor) throws Exception {
		createDyData(entity, monitor, null);
	}

	private void createDyData(CollectDataEntity entity, String monitor, Long persistentRunId) throws Exception {
		sleepCollectTaskIntervalIfNeeded();
		String runId = persistentRunId == null ? buildCollectRunId(entity) : "collect-run-" + persistentRunId;
		logger.info("[CollectTask] createDyData start runId={} id={} name={} monitor={} originaladdress={}",
				runId, entity.getId(), entity.getTaskname(), monitor, entity.getOriginaladdress());
		String taskname = entity.getTaskname(); // 任务名称 作为tvshou.nfo元数据
		// 生成tvshow.nfo元数据
		String temporaryDirectory = FileUtil.generateDir(true, Global.platform.douyin.name(), false, null, taskname,
				null);
		if (Global.getGeneratenfo) {
			if (!(new File(temporaryDirectory + File.separator + "tvshow.nfo").exists())) {
				EmbyMetadataGenerator.createFavoriteDouNfo(taskname, temporaryDirectory);
			}

		}
		int videoaddcount = 0;
		int graphiccount = 0;
		int successThisRun = 0;
		int targetSuccess = entity.getMaxcur() != null ? entity.getMaxcur() : 80;
		int failedThisRun = 0;
		int skippedThisRun = 0;
		logger.info("任务开始" + entity.getOriginaladdress());
		JSONArray allDYData = this.getDYData(entity, monitor, runId);
		FetchRunContext fetchContext = lastFetchRunContext.get();
		Map<String, JSONObject> authorReconcileProfileCache = new HashMap<>();
		if (allDYData != null) {
			allDYData = sortDouyinItemsByPublishTime(allDYData);
			if (persistentRunId != null) {
				collectRunService.storeFetchedItems(persistentRunId, buildPersistentRunItems(allDYData));
			}
			entity.setLastfetchcount(allDYData.size());
			entity.setLastfetchtime(DateUtils.formatDateTime(new Date()));
			entity.setLastfetchsnapshot(buildFetchSnapshot(allDYData, fetchContext));
			collectdDataDao.save(entity);
			logFetchSnapshotItems(entity, fetchContext, allDYData, "sorted");
			prefillDouyinAuthorProfile(allDYData, fetchContext, runId);
		}
		logger.info("[CollectTask] getDYData result runId={} id={} isNull={} size={}", runId, entity.getId(),
				allDYData == null, allDYData == null ? 0 : allDYData.size());
		if (allDYData == null) {
			logger.error("[CollectTask] getDYData returned null runId={} id={} name={} originaladdress={}",
					runId, entity.getId(), entity.getTaskname(), entity.getOriginaladdress());
			F2FailureDiagnosis diagnosis = lastF2FailureDiagnosis.get();
			recordFetchFailureDetail(entity,
					diagnosis == null ? "FETCH_DY_DATA_FAIL" : diagnosis.errorCode(),
					diagnosis == null ? "用户作品列表抓取失败" : diagnosis.rootCause(),
					getLastF2Context(diagnosis));
			lastF2FailureDiagnosis.remove();
			if (persistentRunId != null) {
				throw new CollectFetchException(diagnosis == null ? "FETCH_DY_DATA_FAIL" : diagnosis.errorCode(),
						diagnosis == null ? "用户作品列表抓取失败" : diagnosis.rootCause());
			}
		}
		// System.out.println(allDYData.size());
		String risk = "0";
		if (allDYData != null) {
			if (persistentRunId == null) {
				entity.setCount(String.valueOf(allDYData.size()));
				entity.setTaskstatus("已开始处理");
				collectdDataDao.save(entity);
			}
			JSONArray planItems = new JSONArray();
			for (int i = 0; i < allDYData.size(); i++) {
				assertCollectExecutionAllowed();
				if (successThisRun >= targetSuccess) {
					logger.info("[CollectTask] 已达到本轮目标成功数，停止本轮处理 targetSuccess={} successThisRun={}", targetSuccess, successThisRun);
					break;
				}
				// System.out.println(allDYData.get(i));
				logger.info(entity.getOriginaladdress() + "任务中第" + i + "个");
				String status = "";
				String mediaType = "unknown";
				String errorCode = null;
				String errorMsg = null;
				StringBuilder processLog = new StringBuilder();
				JSONObject aweme_detail = allDYData.getJSONObject(i);
				String awemeId = aweme_detail.getString("aweme_id");
				String awemeCreateTime = aweme_detail.getString("create_time");
				String awemePublishTime = formatPublishTimeFromEpochSeconds(awemeCreateTime);
				JSONObject planItem = new JSONObject();
				planItem.put("runId", runId);
				if (fetchContext != null) {
					planItem.put("fetchMode", fetchContext.mode());
					planItem.put("sourceId", fetchContext.sourceId());
					planItem.put("maxc", fetchContext.maxc());
				}
				planItem.put("aweme_id", awemeId);
				planItem.put("desc", aweme_detail.getString("desc"));
				planItem.put("create_time", awemeCreateTime);
				planItem.put("publish_time", awemePublishTime);
				planItem.put("index", i + 1);
				String desc = aweme_detail.getString("desc");
				String displayName = safeDisplayName(desc, awemeId, "视频");
				String detailJson = safeDetailJson(aweme_detail);
				appendLog(processLog, "run", "runId=" + runId + ", fetchMode=" + (fetchContext == null ? "" : fetchContext.mode())
						+ ", sourceId=" + (fetchContext == null ? "" : fetchContext.sourceId())
						+ ", maxc=" + (fetchContext == null ? "" : fetchContext.maxc()));
				appendLog(processLog, "item-start", "awemeId=" + awemeId + ", desc=" + (desc == null ? "" : desc));
				String coveruri = "";
				JSONArray cover = aweme_detail.getJSONArray("cover");
				if (cover.size() >= 2) {
					coveruri = cover.getString(cover.size() - 1);
				} else {
					coveruri = cover.getString(0);
				}
				JSONArray jsonArray = aweme_detail.getJSONArray("video_play_addr");
				if (jsonArray == null || jsonArray.isEmpty()) {
					mediaType = "image";
					planItem.put("mediatype", mediaType);
					planItem.put("downloadUrlPresent", false);
					planItem.put("decision", "image-branch");
					planItem.put("reason", "video_play_addr empty");
					displayName = safeDisplayName(desc, awemeId, "图文");
					appendLog(processLog, "branch", "video_play_addr empty -> imageText executor");
					boolean blockedGraphic = blockedWorkService.isBlocked(Global.platform.douyin.name(), awemeId, "graphic");
					planItem.put("blocked", blockedGraphic);
					if (blockedGraphic) {
						appendLog(processLog, "skip", "blocked work");
						skippedThisRun++;
						planItem.put("decision", "skip-blocked");
						planItem.put("reason", "blocked work");
						addPlanItem(planItems, planItem, status, errorCode, errorMsg, processLog);
						continue;
					}
					CollectDataDetailEntity existingDetail = collectDataDetailService.findByVideoAndDataid(awemeId, entity.getId());
					Optional<GraphicContentEntity> existingGraphic = graphicContentDao
							.findByVideoidAndPlatform(awemeId, Global.platform.douyin.name());
					if (existingGraphic.isPresent()) {
						authorProfileService.reconcileDouyinGraphic(existingGraphic.get(), observedDouyinAuthor(aweme_detail),
								authorReconcileProfileCache);
					}
					planItem.put("detailExists", existingDetail != null);
					if (existingDetail != null) {
						boolean graphicExists = existingGraphic.isPresent();
						planItem.put("graphicExists", graphicExists);
						if (graphicExists) {
							appendLog(processLog, "skip", "detail exists in collect_data_detail and graphic exists");
							skippedThisRun++;
							planItem.put("decision", "skip-detail-exists");
							planItem.put("reason", "detail and graphic content already exist");
							addPlanItem(planItems, planItem, status, errorCode, errorMsg, processLog);
							continue;
						}
						appendLog(processLog, "repair", "detail exists but graphic content missing, retry imageText executor");
						planItem.put("decision", "image-retry-missing-graphic");
						planItem.put("reason", "detail exists but graphic content missing");
					}
					// 不支持
					try {
						DouYinExecutor.ImageTextExecutor(awemeId, entity.getOriginaladdress(), (String) null,
								authorReconcileProfileCache);
						CollectDataDetailEntity collectDataDetailEntity = existingDetail == null ? new CollectDataDetailEntity() : existingDetail;
						collectDataDetailEntity.setDataid(entity.getId());
						collectDataDetailEntity.setVideoid(awemeId);
						collectDataDetailEntity.setOriginaladdress(awemeId);
						status = "图文已完成";
						collectDataDetailEntity.setStatus(status);
						collectDataDetailEntity.setMediatype(mediaType);
						collectDataDetailEntity.setVideoname(displayName);
						collectDataDetailEntity.setDetailjson(detailJson);
						appendLog(processLog, "imageText", "executor success");
						collectDataDetailEntity.setProcesslog(processLog.toString());
						collectDataDetailEntity.setErrorcode(errorCode);
						collectDataDetailEntity.setErrormsg(errorMsg);
						collectDataDetailEntity.setCreatetime(DateUtils.formatDateTime(new Date()));
						sleepCollectItemInterval();
						collectDataDetailService.save(collectDataDetailEntity);
						if (existingDetail == null) {
							String carriedout = entity.getCarriedout() == null ? "1"
									: String.valueOf(Integer.parseInt(entity.getCarriedout()) + 1);
							if (persistentRunId == null) {
								entity.setCarriedout(carriedout);
								collectdDataDao.save(entity);
							}
						}
						graphiccount++;
						successThisRun++;
						planItem.put("decision", "image-success");
						planItem.put("reason", "imageText executor success");
					} catch (Exception e) {
						logger.error("收藏类模块中抖音图集下载异常");
						logger.error(e.getMessage());
						logger.error("收藏类模块中抖音图集下载异常");
						status = "执行失败";
						errorCode = "IMAGE_TEXT_EXECUTOR_FAIL";
						errorMsg = trimMsg(e.getMessage());
						appendLog(processLog, "imageText", "executor failed: " + trimMsg(e.getMessage()));
						failedThisRun++;
						CollectDataDetailEntity failed = new CollectDataDetailEntity();
						failed.setDataid(entity.getId());
						failed.setVideoid(awemeId);
						failed.setOriginaladdress(awemeId);
						failed.setStatus(status);
						failed.setMediatype(mediaType);
						failed.setVideoname(displayName);
						failed.setDetailjson(detailJson);
						failed.setProcesslog(processLog.toString());
						failed.setErrorcode(errorCode);
						failed.setErrormsg(errorMsg);
						failed.setCreatetime(DateUtils.formatDateTime(new Date()));
						if (collectDataDetailService.findByVideoAndDataid(awemeId, entity.getId()) == null) {
							collectDataDetailService.save(failed);
						}
						planItem.put("decision", "image-fail");
						planItem.put("reason", trimMsg(e.getMessage()));
					}
					addPlanItem(planItems, planItem, status, errorCode, errorMsg, processLog);
					continue;
				}
				mediaType = "video";
				planItem.put("mediatype", mediaType);
				String videoplay = "";
				if (jsonArray.size() >= 2) {
					videoplay = jsonArray.getString(jsonArray.size() - 1);
				} else {
					videoplay = jsonArray.getString(0);
				}
				appendLog(processLog, "branch", "video path selected");
				planItem.put("downloadUrlPresent", videoplay != null && !videoplay.trim().isEmpty());
				logger.info("[CollectTask] item start taskId={} index={} awemeId={} desc={}", entity.getId(), i,
						awemeId, desc);

				List<VideoDataEntity> findByVideoid = videoDataService.findByVideoid(awemeId);
				boolean blockedVideo = blockedWorkService.isBlocked(Global.platform.douyin.name(), awemeId, "video");
				planItem.put("blocked", blockedVideo);
				if (blockedVideo) {
					appendLog(processLog, "skip", "blocked work");
					skippedThisRun++;
					planItem.put("decision", "skip-blocked");
					planItem.put("reason", "blocked work");
					addPlanItem(planItems, planItem, status, errorCode, errorMsg, processLog);
					continue;
				}
				planItem.put("videoExists", findByVideoid.size() > 0);
				if (findByVideoid.size() > 0) {
					VideoDataEntity existsVideo = findByVideoid.get(0);
					File vf = existsVideo.getVideoaddr() == null ? null : new File(existsVideo.getVideoaddr());
					planItem.put("fileExists", vf != null && vf.exists());
					planItem.put("existingVideoPath", existsVideo.getVideoaddr());
					if (vf == null || !vf.exists()) {
						appendLog(processLog, "dedup", "db exists but file missing, force redownload");
						planItem.put("reason", "video db record exists but file missing");
						findByVideoid = new ArrayList<>();
					} else {
						authorProfileService.reconcileDouyinVideo(existsVideo, observedDouyinAuthor(aweme_detail),
								authorReconcileProfileCache);
					}
				}
				appendLog(processLog, "dedup", "exists=" + (findByVideoid.size() > 0));
				logger.info("[CollectTask] item dedup taskId={} awemeId={} exists={}", entity.getId(), awemeId,
						findByVideoid.size() > 0);
				if (findByVideoid.size() == 0) {
					planItem.put("decision", "video-download");
					String dyNickname = aweme_detail.getString("nickname");
					String dyCreateTime = aweme_detail.getString("create_time");
					String filename = FileNameTemplateUtil.resolveFileName(desc, awemeId, dyNickname, dyCreateTime, "抖音");
					String dir = FileUtil.generateDir(Global.down_path, Global.platform.douyin.name(), false, filename,
							taskname, null);
					String videofile = FileUtil.generateDir(Global.down_path, Global.platform.douyin.name(), false,
							filename, taskname, "mp4");
					String videounrealaddr = FileUtil.generateDir(false, Global.platform.douyin.name(), false, filename,
							taskname, "mp4");
					String coverunaddr = FileUtil.generateDir(false, Global.platform.douyin.name(), false, filename,
							taskname, "jpg");
					String dir2 = FileUtil.generateDir(true, Global.platform.douyin.name(), false, filename, taskname,
							null);
					boolean localVideoExists = false;
					File localVideoFile = new File(videofile);
					if (localVideoFile.exists() && localVideoFile.isFile() && localVideoFile.length() > 0) {
						localVideoExists = true;
						appendLog(processLog, "download", "local file exists, skip real download");
						logger.info("[CollectTask] local file hit, skip download taskId={} awemeId={} path={}", entity.getId(),
								awemeId, videofile);
						planItem.put("decision", "video-local-hit");
						planItem.put("reason", "local file exists before download");
					}
					logger.info("已使用批量下载,下载器类型为:" + Global.downtype);
					planItem.put("fileExists", localVideoExists);
					planItem.put("targetVideoPath", videofile);
					String itemCookie = platformCookieService.currentDouyinCookie("collect_item_download");
					if (!localVideoExists && Global.downtype.equals("a2")) {
						appendLog(processLog, "download", "using aria2");
						Aria2Util.sendMessage(Global.a2_link, Aria2Util.createDouparameter(videoplay, dir,
								filename + ".mp4", Global.a2_token, itemCookie));
					}
					HashMap<String, String> header = new HashMap<String, String>();
					header.put("User-Agent", DouUtil.ua);
					header.put("cookie", itemCookie);
					header.put("Referer", "https://www.douyin.com/");
					if (!localVideoExists && Global.downtype.equals("http")) {
						appendLog(processLog, "download", "using http builtin");
						// 内置下载器
						dir = FileUtil.generateDir(true, Global.platform.douyin.name(), false, filename, taskname,
								null);
						videofile = FileUtil.generateDir(true, Global.platform.douyin.name(), false, filename, taskname,
								null);
						String downloadFileWithOkHttp = "";
						if (Global.RangeNumber == 1) {
							downloadFileWithOkHttp = HttpUtil.downloadFileWithOkHttp(videoplay, filename + ".mp4",
									videofile, header);
						} else {
							downloadFileWithOkHttp = HttpUtil.downloadFileWithOkHttp(videoplay, filename + ".mp4",
									videofile, header, Global.RangeNumber);
						}
						if (downloadFileWithOkHttp.equals("1")) {
							logger.info(aweme_detail.toJSONString());
							risk = "1";
							status = "执行失败";
							errorCode = "DOWNLOAD_RISK_OR_FAIL";
							errorMsg = "内置下载返回1";
							appendLog(processLog, "download", "http downloader returned 1");
							planItem.put("reason", "http downloader returned 1");
							failedThisRun++;
							logger.error("[CollectTask] risk triggered taskId={} awemeId={} output={}", entity.getId(), awemeId,
									downloadFileWithOkHttp);
							addPlanItem(planItems, planItem, status, errorCode, errorMsg, processLog);
							break;
						}
					}
					if (!localVideoExists) {
						HttpUtil.downloadFileWithOkHttp(coveruri, filename + ".jpg", dir2, header);
					}
					String sourceUrl = DouyinSourceUrlUtil.video(awemeId);
					JSONObject hybridData = DouUtil.fetchHybridVideoData(sourceUrl);
					String rawJsonData = hybridData == null ? detailJson : hybridData.toJSONString();
					VideoDataEntity videoDataEntity = new VideoDataEntity(awemeId, desc, desc, "抖音", coverunaddr,
							FileUtil.generateDir(true, Global.platform.douyin.name(), false, filename, taskname, "mp4"),
							videounrealaddr, entity.getOriginaladdress());
					rawPayloadService.storeVideoRawPayload(videoDataEntity, rawJsonData);
					videoDataEntity.setPublishtime(formatPublishTimeFromEpochSeconds(aweme_detail.getString("create_time")));
					String taskSourceId = fetchContext == null
							? extractCanonicalTaskSourceId(entity.getOriginaladdress()) : fetchContext.sourceId();
					DouyinAuthorSnapshot authorSnapshot = resolveDouyinAuthorSnapshot(aweme_detail, hybridData, null,
							taskSourceId);
					String authorUidForSave = authorSnapshot.canonicalUid();
					String uniqueId = authorSnapshot.uniqueId();
					String authorUid = authorSnapshot.numericUid();
					String avatar = authorSnapshot.avatar();
					String signature = authorSnapshot.signature();
					dyNickname = firstNotBlank(authorSnapshot.nickname(), dyNickname);
					if (authorUidForSave == null) {
						logger.warn("[CollectTask] author unresolved runId={} taskId={} awemeId={} listSecUidPresent={} hybridSecUidPresent={} taskSourceId={}",
								runId, entity.getId(), awemeId, hasText(authorSecUid(observedDouyinAuthor(aweme_detail))),
								hasText(authorSecUid(extractHybridAuthor(hybridData))), taskSourceId);
					} else {
						authorProfileService.upsertAuthor("抖音", authorUidForSave, uniqueId, dyNickname, avatar,
								AuthorIdentityUtil.douyinHomepage(authorUidForSave), signature);
					}
					videoDataEntity.setAuthoruid(authorUidForSave);
					videoDataEntity.setSecuid(authorUidForSave);
					videoDataEntity.setAuthorusername(uniqueId);
					videoDataEntity.setUniqueid(uniqueId);
					videoDataEntity.setAuthoravatar(avatar);
					videoDataEntity.setAuthorhomepage(AuthorIdentityUtil.douyinHomepage(authorUidForSave));
					videoDataEntity.setPlatformkey("douyin");
					videoDataEntity.setContenttype("video");
					videoDataEntity.setSourceurl(sourceUrl);
					if (Global.getGeneratenfo) {
						String uid = authorUid;
						String publisher = dyNickname + "-" + uid + ".png";
						String coverdir = FileUtil.generateDir(true, Global.platform.douyin.name(), false, filename,
								taskname, null);
						HttpUtil.downloadFileWithOkHttp(avatar, publisher, coverdir,
								header);
						if (null != Global.nfonetaddr && !"".equals(Global.nfonetaddr)) {
							String publisherdir = FileUtil.generateDir(false, Global.platform.douyin.name(), false,
									filename, taskname, null);
							// System.out.println(publisherdir);
							publisher = Global.nfonetaddr + publisherdir + "/" + publisher + "?apptoken="
									+ Global.readonlytoken;
						}

						Map<String, String> map = new HashMap<String, String>();
						map.put("title", desc);
						map.put("desc", desc);
						map.put("upname", aweme_detail.getString("nickname"));
						map.put("ctime", aweme_detail.getString("create_time"));
						map.put("piclocal", filename + ".jpg");
						map.put("upmid", authorUid);
						map.put("cid", awemeId);
						map.put("upface", publisher);
						EmbyMetadataGenerator.createFavoriteEpisodeDouNfo(map, dir, i + 1, temporaryDirectory);
						videoDataEntity.setVideoauthor(dyNickname);
					} else {
						videoDataEntity.setVideoauthor(dyNickname);
					}
					VideoDataEntity saved = videoDataDao.save(videoDataEntity);
					if (saved != null && saved.getId() != null) {
						hlsTranscodeService.enqueueByIds(String.valueOf(saved.getId()));
					}
					appendLog(processLog, "save", "video saved");
					if (localVideoExists) {
						status = "已完成(文件已存在-已入库)";
					}
					logger.info("下载流程结束");
					if (!localVideoExists) {
						sleepCollectItemInterval();
						logger.info("等待设定间隔后继续下一个");
					}
				}
				if (status.equals("")) {
					status = findByVideoid.size() == 0 ? "已完成" : "已完成(未下载已存在)";
				}
				if (status.contains("已存在")) {
					appendLog(processLog, "skip", "already exists in video library");
					skippedThisRun++;
					planItem.put("decision", "skip-video-exists");
				}

				// 这里应该判断一下CollectDataDetailEntity记录是否存在 存在 则不处理 因为已经不预删除了
				CollectDataDetailEntity collectDataDetailEntity = new CollectDataDetailEntity();
				collectDataDetailEntity.setDataid(entity.getId());
				collectDataDetailEntity.setVideoid(awemeId);
				CollectDataDetailEntity byVideoAndDataid = collectDataDetailService.findByVideoAndDataid(
						collectDataDetailEntity.getVideoid(), collectDataDetailEntity.getDataid());
				if (byVideoAndDataid == null) {
					collectDataDetailEntity.setVideoname(displayName);
					collectDataDetailEntity.setOriginaladdress(awemeId);
					collectDataDetailEntity.setStatus(status);
					collectDataDetailEntity.setMediatype(mediaType);
					collectDataDetailEntity.setDetailjson(detailJson);
					collectDataDetailEntity.setProcesslog(processLog.toString());
					collectDataDetailEntity.setErrorcode(errorCode);
					collectDataDetailEntity.setErrormsg(errorMsg);
					collectDataDetailEntity.setCreatetime(DateUtils.formatDateTime(new Date()));
					collectDataDetailService.save(collectDataDetailEntity);
					// 修改主体
					String carriedout = entity.getCarriedout() == null ? "1"
							: String.valueOf(Integer.parseInt(entity.getCarriedout()) + 1);
					if (persistentRunId == null) {
						entity.setCarriedout(carriedout);
						collectdDataDao.save(entity);
					}
					videoaddcount++;
					if (status.startsWith("已完成") || "图文已完成".equals(status)) {
						successThisRun++;
						planItem.put("decision", "video-success");
					}
					if ("执行失败".equals(status)) {
						failedThisRun++;
						planItem.put("decision", "video-fail");
					}
				} else {
					appendLog(processLog, "skip", "detail exists in collect_data_detail");
					skippedThisRun++;
					planItem.put("decision", "skip-detail-exists");
				}
				addPlanItem(planItems, planItem, status, errorCode, errorMsg, processLog);

			}
			planItems.add(0, buildRunSummaryPlanItem(runId, fetchContext, allDYData.size(), successThisRun,
					failedThisRun, skippedThisRun, videoaddcount, graphiccount, targetSuccess));
			entity.setLastplanitems(snapshotCodec().encodePlan(planItems, snapshotContext(runId, fetchContext)));
			collectdDataDao.save(entity);
		}
		int totalCount = videoaddcount + graphiccount;
		if (totalCount > 0) {
		    sendNotify.sendMessage(totalCount, entity.getTaskname());
		}
		if (persistentRunId == null) {
			entity.setTaskstatus(resolveDouyinCollectStatus(risk, allDYData == null, successThisRun, failedThisRun, skippedThisRun));
			entity.setEndtime(DateUtils.formatDateTime(new Date()));
			collectdDataDao.save(entity);
		}
		markCollectTaskFinished();
		System.gc();
		logger.info("任务结束" + entity.getOriginaladdress());
		logger.info("[CollectTask] createDyData finish id={} addedVideo={} addedGraphic={} totalAdded={} successThisRun={} targetSuccess={} failedThisRun={} skippedThisRun={} finalStatus={} carriedout={}",
				entity.getId(), videoaddcount, graphiccount, totalCount, successThisRun, targetSuccess, failedThisRun, skippedThisRun, entity.getTaskstatus(), entity.getCarriedout());
		lastF2FailureDiagnosis.remove();
		lastFetchRunContext.remove();
	}

	static String resolveDouyinCollectStatus(String risk, boolean fetchFailed, int successThisRun, int failedThisRun, int skippedThisRun) {
		if (fetchFailed) {
			return "执行失败(抓取异常)";
		}
		boolean onlySkippedExistingItems = successThisRun == 0 && failedThisRun == 0 && skippedThisRun > 0;
		if ("1".equals(risk) && !onlySkippedExistingItems) {
			return "可能触发风控本次已终止";
		}
		return "处理完成";
	}

	String buildFetchSnapshot(JSONArray allData) {
		return buildFetchSnapshot(allData, null);
	}

	String buildFetchSnapshot(JSONArray allData, FetchRunContext context) {
		JSONArray normalized = new JSONArray();
		for (int i = 0; i < allData.size(); i++) {
			JSONObject src = allData.getJSONObject(i);
			if (src == null) continue;
			JSONObject item = new JSONObject(src);
			item.put("publish_time", formatPublishTimeFromEpochSeconds(src.getString("create_time")));
			normalized.add(item);
		}
		return snapshotCodec().encodeFetch(normalized, snapshotContext(context == null ? null : context.runId(), context));
	}

	private SnapshotCodec snapshotCodec() {
		return snapshotCodec == null
				? new SnapshotCodec(SnapshotCodec.DEFAULT_MAX_BYTES, SnapshotCodec.DEFAULT_FORMAT_VERSION)
				: snapshotCodec;
	}

	private Map<String, Object> snapshotContext(String runId, FetchRunContext context) {
		Map<String, Object> result = new HashMap<>();
		if (runId != null) result.put("runId", runId);
		if (context == null) return result;
		result.put("taskId", context.taskId());
		result.put("taskName", context.taskName());
		result.put("fetchMode", context.mode());
		result.put("sourceId", context.sourceId());
		result.put("maxc", context.maxc());
		result.put("existingDetailCount", context.existingDetailCount());
		result.put("successDetailCount", context.successDetailCount());
		result.put("originaladdress", context.originaladdress());
		return result;
	}

	private String buildCollectRunId(CollectDataEntity entity) {
		String taskId = entity == null || entity.getId() == null ? "unknown" : String.valueOf(entity.getId());
		return "collect-" + taskId + "-" + System.currentTimeMillis();
	}

	private void logFetchSnapshotItems(CollectDataEntity entity, FetchRunContext context, JSONArray data, String order) {
		if (data == null) {
			logger.info("[CollectTask] fetch snapshot runId={} taskId={} order={} count=0",
					context == null ? null : context.runId(), entity == null ? null : entity.getId(), order);
			return;
		}
		logger.info("[CollectTask] fetch snapshot runId={} taskId={} mode={} sourceId={} maxc={} order={} count={} first={} last={}",
				context == null ? null : context.runId(), entity == null ? null : entity.getId(),
				context == null ? null : context.mode(), context == null ? null : context.sourceId(),
				context == null ? null : context.maxc(), order, data.size(), firstAwemeId(data), lastAwemeId(data));
		for (int i = 0; i < data.size(); i++) {
			JSONObject item = data.getJSONObject(i);
			logger.info("[CollectTask] fetched item runId={} taskId={} order={} index={} awemeId={} publishTime={} type={} hasVideo={} desc={}",
					context == null ? null : context.runId(), entity == null ? null : entity.getId(), order, i + 1,
					item.getString("aweme_id"), formatPublishTimeFromEpochSeconds(item.getString("create_time")),
					item.getString("type"),
					item.getJSONArray("video_play_addr") != null && !item.getJSONArray("video_play_addr").isEmpty(),
					trimForLog(item.getString("desc"), 160));
		}
	}

	private void addPlanItem(JSONArray planItems, JSONObject planItem, String status, String errorCode, String errorMsg,
			StringBuilder processLog) {
		planItem.put("status", status == null || status.trim().isEmpty() ? planItem.getString("decision") : status);
		planItem.put("errorcode", errorCode);
		planItem.put("errormsg", errorMsg);
		planItem.put("time", DateUtils.formatDateTime(new Date()));
		String logText = processLog == null ? "" : processLog.toString();
		planItem.put("processlog", logText.length() > 12000 ? logText.substring(0, 12000) + "...(truncated)" : logText);
		planItems.add(planItem);
		Long persistentRunId = activePersistentRunId.get();
		if (persistentRunId != null && planItem.getString("aweme_id") != null) {
			collectRunService.updateItem(persistentRunId, planItem.getString("aweme_id"),
					normalizeRunDecision(planItem.getString("decision")), runItemState(planItem.getString("decision"),
							planItem.getString("status"), errorCode), errorCode, errorMsg);
		}
		logger.info("[CollectTask] plan item runId={} awemeId={} index={} mediaType={} decision={} status={} reason={} errorCode={} log={}",
				planItem.getString("runId"), planItem.getString("aweme_id"), planItem.get("index"),
				planItem.getString("mediatype"), planItem.getString("decision"), planItem.getString("status"),
				trimForLog(planItem.getString("reason"), 160), errorCode, trimForLog(logText, 500));
	}

	private List<CollectRunFetchedItem> buildPersistentRunItems(JSONArray items) {
		List<CollectRunFetchedItem> result = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			JSONObject item = items.getJSONObject(i);
			String workId = item.getString("aweme_id");
			if (workId == null || workId.isBlank()) continue;
			JSONArray playAddress = item.getJSONArray("video_play_addr");
			String mediaType = playAddress == null || playAddress.isEmpty() ? "image" : "video";
			result.add(new CollectRunFetchedItem(i + 1, "douyin", workId,
					firstNotBlank(item.getString("sec_uid"), item.getString("author_uid")),
					item.getString("nickname"), item.getString("desc"),
					firstNotBlank(item.getString("publish_time"), item.getString("create_time")), mediaType));
		}
		return result;
	}

	private String runItemState(String decision, String status, String errorCode) {
		String combined = (valueOrEmpty(decision) + " " + valueOrEmpty(status)).toLowerCase();
		if (errorCode != null || combined.contains("fail") || combined.contains("失败")) return "FAILED";
		if (combined.contains("skip") || combined.contains("exist") || combined.contains("已存在")) return "SKIPPED";
		return "COMPLETED";
	}

	static String normalizeRunDecision(String decision) {
		String value = valueOrEmpty(decision).toLowerCase();
		if (value.contains("retry") || value.contains("repair") || value.contains("missing")) return "RETRY";
		if (value.contains("fail")) return "FAILED";
		if (value.contains("skip") || value.contains("exist") || value.contains("blocked")) return "SKIP";
		if (value.contains("download") || value.contains("success") || value.contains("local-hit")) return "NEW";
		return "UNKNOWN";
	}

	private static String valueOrEmpty(String value) {
		return value == null ? "" : value;
	}

	private String rootCauseMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		return root.getMessage() == null || root.getMessage().isBlank()
				? root.getClass().getSimpleName() : root.getMessage();
	}

	private void assertCollectExecutionAllowed() {
		PauseDecision collect = runtimeControlService.mayRun(TaskCategory.COLLECT_FETCH);
		if (!collect.allowed()) {
			throw new CollectExecutionPausedException(collect.controlKey(), collect.reason());
		}
		PauseDecision download = runtimeControlService.mayRun(TaskCategory.MEDIA_DOWNLOAD);
		if (!download.allowed()) {
			throw new CollectExecutionPausedException(download.controlKey(), download.reason());
		}
	}

	private JSONObject buildRunSummaryPlanItem(String runId, FetchRunContext context, int fetchedCount,
			int successThisRun, int failedThisRun, int skippedThisRun, int addedVideo, int addedGraphic,
			int targetSuccess) {
		JSONObject summary = new JSONObject();
		summary.put("stage", "run-summary");
		summary.put("runId", runId);
		if (context != null) {
			summary.put("fetchMode", context.mode());
			summary.put("sourceId", context.sourceId());
			summary.put("maxc", context.maxc());
			summary.put("existingDetailCount", context.existingDetailCount());
			summary.put("successDetailCount", context.successDetailCount());
			summary.put("originaladdress", context.originaladdress());
		}
		summary.put("fetchedCount", fetchedCount);
		summary.put("targetSuccess", targetSuccess);
		summary.put("successThisRun", successThisRun);
		summary.put("failedThisRun", failedThisRun);
		summary.put("skippedThisRun", skippedThisRun);
		summary.put("addedVideo", addedVideo);
		summary.put("addedGraphic", addedGraphic);
		summary.put("decision", "run-summary");
		summary.put("status", "run-summary");
		summary.put("reason", "fetch/process summary");
		summary.put("time", DateUtils.formatDateTime(new Date()));
		logger.info("[CollectTask] run summary runId={} mode={} sourceId={} maxc={} fetched={} targetSuccess={} success={} failed={} skipped={} addedVideo={} addedGraphic={}",
				runId, context == null ? null : context.mode(), context == null ? null : context.sourceId(),
				context == null ? null : context.maxc(), fetchedCount, targetSuccess, successThisRun,
				failedThisRun, skippedThisRun, addedVideo, addedGraphic);
		return summary;
	}

	JSONArray sortDouyinItemsByPublishTime(JSONArray allData) {
		if (allData == null || allData.size() < 2) {
			return allData;
		}
		List<JSONObject> items = new ArrayList<>();
		for (int i = 0; i < allData.size(); i++) {
			items.add(allData.getJSONObject(i));
		}
		items.sort(Comparator
				.comparing((JSONObject item) -> publishTimeMillis(item.getString("create_time")),
						Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(item -> item.getString("aweme_id"), Comparator.nullsLast(Comparator.reverseOrder())));
		JSONArray sorted = new JSONArray();
		for (JSONObject item : items) {
			sorted.add(item);
		}
		if (!sameAwemeOrder(allData, sorted)) {
			logger.info("[CollectTask] sorted F2 result by publish time size={} firstBefore={} firstAfter={}",
					allData.size(), firstAwemeId(allData), firstAwemeId(sorted));
		}
		return sorted;
	}

	private boolean sameAwemeOrder(JSONArray left, JSONArray right) {
		if (left == null || right == null || left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			String leftId = left.getJSONObject(i).getString("aweme_id");
			String rightId = right.getJSONObject(i).getString("aweme_id");
			if (leftId == null ? rightId != null : !leftId.equals(rightId)) {
				return false;
			}
		}
		return true;
	}

	private String firstAwemeId(JSONArray data) {
		if (data == null || data.isEmpty()) {
			return null;
		}
		return data.getJSONObject(0).getString("aweme_id");
	}

	private String lastAwemeId(JSONArray data) {
		if (data == null || data.isEmpty()) {
			return null;
		}
		return data.getJSONObject(data.size() - 1).getString("aweme_id");
	}

	private String trimForLog(String text, int maxLength) {
		if (text == null) {
			return null;
		}
		String normalized = text.replace("\r", " ").replace("\n", " ");
		return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
	}

	private Long publishTimeMillis(String rawCreateTime) {
		String publishTime = formatPublishTimeFromEpochSeconds(rawCreateTime);
		if (publishTime == null) {
			return null;
		}
		Date date = DateUtils.parseDate(publishTime);
		return date == null ? null : date.getTime();
	}

	public JSONArray getDYData(CollectDataEntity entity, String monitor) throws IOException {
		return getDYData(entity, monitor, buildCollectRunId(entity));
	}

	public JSONArray getDYData(CollectDataEntity entity, String monitor, String runId) throws IOException {
		lastF2FailureDiagnosis.remove();
		lastFetchRunContext.remove();
		String taskout = Global.apppath + "lot" + System.getProperty("file.separator") + entity.getId() + "_"
				+ entity.getTaskname() + ".json";
		logger.info("[CollectTask] getDYData start runId={} id={} name={} originaladdress={} monitor={} tempFile={}",
				runId, entity.getId(), entity.getTaskname(), entity.getOriginaladdress(), monitor, taskout);
		String originaladdress = entity.getOriginaladdress() == null ? "" : entity.getOriginaladdress();
		String sec_user_id = originaladdress.replaceFirst("^(post|like|recommend)", "");
		int maxc = 80;

		// if ("N".equals(monitor)) {
		// maxc = null!= entity.getOmaxcur() ?entity.getOmaxcur():80;
		// } else if ("Y".equals(monitor)) {
		// maxc = null!= entity.getMaxcur() ?entity.getMaxcur():80;
		// }

		List<String> successStatuses = new ArrayList<>();
		successStatuses.add("已完成");
		successStatuses.add("图文已完成");
		long successCountByDataid = collectDataDetailDao.countByDataidAndStatusIn(entity.getId(), successStatuses);
		long countByDataid = collectDataDetailDao.countByDataid(entity.getId());
		if (countByDataid > 0) {
			// 监控阶段：基于“历史成功条目数”扩大抓取窗口，失败条目不占成功基数。
			int monitorWindow = null != entity.getMaxcur() ? entity.getMaxcur() : 80;
			long expanded = successCountByDataid + monitorWindow;
			// 防止窗口无限膨胀导致单次抓取过大
			maxc = (int) Math.min(expanded, 2000L);
			logger.info("[CollectTask] monitor mode runId={} targetNew={} expandedFetchWindow={} (successProcessed={} + target={}, allDetail={})",
					runId, monitorWindow, maxc, successCountByDataid, monitorWindow, countByDataid);
		} else {
			maxc = null != entity.getOmaxcur() ? entity.getOmaxcur() : 80;
		}
		logger.info("[CollectTask] getDYData resolved runId={} maxc={} existingDetailCount={} successDetailCount={} (omaxcur={}, maxcur={})",
				runId, maxc, countByDataid, successCountByDataid, entity.getOmaxcur(), entity.getMaxcur());

		if (originaladdress.startsWith("post")) {
			return executeDouyinListFetch(entity, runId, monitor, "post", sec_user_id, null,
					"fetch_user_post_videos", maxc, taskout, countByDataid, successCountByDataid);
		}
		if (originaladdress.startsWith("like")) {
			return executeDouyinListFetch(entity, runId, monitor, "like", sec_user_id, null,
					"fetch_user_like_videos", maxc, taskout, countByDataid, successCountByDataid);
		}
		if (originaladdress.startsWith("recommend")) {
			return executeDouyinListFetch(entity, runId, monitor, "recommend", sec_user_id, null,
					"fetch_user_feed_videos", maxc, taskout, countByDataid, successCountByDataid);
		}
		if (originaladdress.startsWith("fav-")) {
			String startTag = "fav-";
			String endTag = "-fav";
			int startIndex = originaladdress.indexOf(startTag) + startTag.length();
			int endIndex = originaladdress.indexOf(endTag);
			if (startIndex < startTag.length() || endIndex <= startIndex) {
				logger.error("[CollectTask] getDYData invalid fav address runId={} id={} originaladdress={}",
						runId, entity.getId(), originaladdress);
				return null;
			}
			String content = originaladdress.substring(startIndex, endIndex).trim();
			return executeDouyinListFetch(entity, runId, monitor, "fav", content, content,
					"fetch_user_collects_videos", maxc, taskout, countByDataid, successCountByDataid);
		}

		if (originaladdress.startsWith("post")) {
			logger.info("[CollectTask] getDYData mode=post uid={} maxc={} out={}", sec_user_id, maxc, taskout);
			String cookie = platformCookieService.currentDouyinCookie("fetch_user_post_videos");
			String f2cmd = CommandUtil.f2cmd(cookie, null, "fetch_user_post_videos", sec_user_id, null,
					maxc, taskout);
			reportF2CookieResult("抖音", cookie, f2cmd);
			logF2Result(entity, "post", sec_user_id, maxc, f2cmd, taskout);
			if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
				JSONArray jsonFromFile = FileUtil.readJsonFromFile(taskout);
				logger.info("[CollectTask] getDYData parsed count={} mode=post", jsonFromFile == null ? 0 : jsonFromFile.size());
				Files.deleteIfExists(Paths.get(taskout));
				return jsonFromFile;
			}
		}
		if (originaladdress.startsWith("fav-")) {
			String startTag = "fav-";
			String endTag = "-fav";
			int startIndex = entity.getOriginaladdress().indexOf(startTag) + startTag.length();
			int endIndex = entity.getOriginaladdress().indexOf(endTag);
			String content = entity.getOriginaladdress().substring(startIndex, endIndex).trim();
			sec_user_id = sec_user_id.replaceAll(startTag + content + endTag, "");
			logger.info("[CollectTask] getDYData mode=fav cid={} maxc={} out={}", content, maxc, taskout);
			String cookie = platformCookieService.currentDouyinCookie("fetch_user_collects_videos");
			String f2cmd = CommandUtil.f2cmd(cookie, null, "fetch_user_collects_videos", null, content,
					maxc, taskout);
			reportF2CookieResult("抖音", cookie, f2cmd);
			logF2Result(entity, "fav", content, maxc, f2cmd, taskout);
			if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
				JSONArray jsonFromFile = FileUtil.readJsonFromFile(taskout);
				logger.info("[CollectTask] getDYData parsed count={} mode=fav", jsonFromFile == null ? 0 : jsonFromFile.size());
				Files.deleteIfExists(Paths.get(taskout));
				return jsonFromFile;
			}
		}
		// 删除文件
		logger.error("[CollectTask] getDYData returning null id={} originaladdress={}", entity.getId(), entity.getOriginaladdress());
		return null;
	}

	private JSONArray executeDouyinListFetch(CollectDataEntity entity, String runId, String monitor, String mode,
			String sourceId, String cid, String functionName, int maxc, String taskout, long existingDetailCount,
			long successDetailCount) throws IOException {
		FetchRunContext context = new FetchRunContext(runId, entity.getId(), entity.getTaskname(),
				entity.getOriginaladdress(), monitor, mode, sourceId, maxc, existingDetailCount, successDetailCount,
				entity.getOmaxcur(), entity.getMaxcur(), taskout);
		lastFetchRunContext.set(context);
		logger.info("[CollectTask] getDYData request runId={} taskId={} mode={} sourceId={} cid={} maxc={} out={}",
				runId, entity.getId(), mode, sourceId, cid, maxc, taskout);
		String cookie = platformCookieService.currentDouyinCookie(functionName);
		String f2cmd = CommandUtil.f2cmd(cookie, null, functionName,
				"fav".equals(mode) ? null : sourceId, "fav".equals(mode) ? cid : null, maxc, taskout);
		reportF2CookieResult(Global.platform.douyin.name(), cookie, f2cmd);
		logF2Result(entity, runId, mode, sourceId, maxc, f2cmd, taskout, cookie);
		if (f2cmd != null && f2cmd.contains("stream-vault-ok")) {
			JSONArray jsonFromFile = FileUtil.readJsonFromFile(taskout);
			logger.info("[CollectTask] getDYData parsed runId={} count={} mode={} sourceId={}",
					runId, jsonFromFile == null ? 0 : jsonFromFile.size(), mode, sourceId);
			logFetchSnapshotItems(entity, context, jsonFromFile, "f2-raw");
			reportCookieDegradedFetch(entity, mode, cookie, maxc, jsonFromFile, existingDetailCount);
			Files.deleteIfExists(Paths.get(taskout));
			return jsonFromFile;
		}
		return null;
	}

	private void logF2Result(CollectDataEntity entity, String mode, String sourceId, int maxc, String f2cmd, String taskout) {
		logF2Result(entity, null, mode, sourceId, maxc, f2cmd, taskout, null);
	}

	private void logF2Result(CollectDataEntity entity, String runId, String mode, String sourceId, int maxc, String f2cmd, String taskout) {
		logF2Result(entity, runId, mode, sourceId, maxc, f2cmd, taskout, null);
	}

	private void logF2Result(CollectDataEntity entity, String runId, String mode, String sourceId, int maxc,
			String f2cmd, String taskout, String cookie) {
		boolean success = f2cmd != null && f2cmd.contains("stream-vault-ok");
		Integer exitCode = CommandUtil.getLastF2ExitCode();
		Long durationMs = CommandUtil.getLastF2DurationMs();
		logger.info("[CollectTask] getDYData f2 runId={} mode={} sourceId={} outputLength={} containsSuccessMarker={} exitCode={} durationMs={}",
				runId, mode, sourceId, f2cmd == null ? 0 : f2cmd.length(), success, exitCode, durationMs);
		if (!success) {
			boolean outFileExists = Files.exists(Paths.get(taskout));
			long outFileSize = outFileExists ? safeFileSize(taskout) : -1;
			JSONObject diagnostics = buildF2FailureDiagnostics(mode, sourceId, cookie);
			F2FailureDiagnosis diagnosis = analyzeF2Failure(mode, entity.getOriginaladdress(), sourceId, maxc, taskout,
					outFileExists, outFileSize, exitCode, durationMs, f2cmd, diagnostics);
			lastF2FailureDiagnosis.set(diagnosis);
			logger.error("[CollectTask] getDYData f2 rootCause runId={} {}", runId, diagnosis.toLogMessage());
			logger.error("[CollectTask] getDYData f2 diagnostics runId={} mode={} sourceId={} diagnostics={}",
					runId, mode, sourceId, diagnostics == null ? null : diagnostics.toJSONString());
			logger.error("[CollectTask] getDYData f2 failed runId={} mode={} sourceId={} outputPreview={}", runId, mode, sourceId, previewOutput(f2cmd));
			logger.error("[CollectTask] getDYData f2 failed runId={} mode={} outPath={} outFileExists={} outFileSize={}",
					runId, mode, taskout, outFileExists, outFileSize);
		} else {
			logger.info("[CollectTask] getDYData output file runId={} exists={} path={}", runId, Files.exists(Paths.get(taskout)), taskout);
		}
	}

	static F2FailureDiagnosis analyzeF2Failure(String mode, String originaladdress, String sourceId, int maxc,
			String outPath, boolean outFileExists, long outFileSize, Integer exitCode, Long durationMs, String output) {
		return analyzeF2Failure(mode, originaladdress, sourceId, maxc, outPath, outFileExists, outFileSize,
				exitCode, durationMs, output, null);
	}

	static F2FailureDiagnosis analyzeF2Failure(String mode, String originaladdress, String sourceId, int maxc,
			String outPath, boolean outFileExists, long outFileSize, Integer exitCode, Long durationMs, String output,
			JSONObject diagnostics) {
		String normalized = output == null ? "" : output;
		String exceptionType = null;
		String exceptionMessage = null;
		Matcher exceptionMatcher = PYTHON_EXCEPTION_PATTERN.matcher(normalized);
		while (exceptionMatcher.find()) {
			exceptionType = exceptionMatcher.group(1);
			exceptionMessage = exceptionMatcher.group(2);
		}
		String stackTop = extractStackTop(normalized);
		String errorCode = classifyF2Failure(exceptionType, exceptionMessage, normalized, outFileExists, exitCode);
		String rootCause = buildRootCause(errorCode, exceptionType, exceptionMessage, normalized);
		String outputPreview = previewOutput(output);
		return new F2FailureDiagnosis(mode, originaladdress, sourceId, maxc, outPath, outFileExists, outFileSize,
				exitCode, durationMs, errorCode, exceptionType, exceptionMessage, stackTop, rootCause, outputPreview,
				diagnostics);
	}

	private static String extractStackTop(String output) {
		Matcher matcher = PYTHON_FILE_PATTERN.matcher(output == null ? "" : output);
		String stackTop = null;
		while (matcher.find()) {
			stackTop = matcher.group(1) + ":" + matcher.group(2) + " in " + matcher.group(3).trim();
		}
		return stackTop;
	}

	private static String classifyF2Failure(String exceptionType, String exceptionMessage, String output,
			boolean outFileExists, Integer exitCode) {
		String text = ((exceptionType == null ? "" : exceptionType) + "\n"
				+ (exceptionMessage == null ? "" : exceptionMessage) + "\n"
				+ (output == null ? "" : output)).toLowerCase();
		if (text.contains("nickname_raw") && text.contains("unboundlocalerror")) {
			return "F2_INTERNAL_NICKNAME_RAW_UNBOUND";
		}
		if (text.contains("login") || text.contains("cookie") || text.contains("passport") || text.contains("verify")) {
			return "F2_COOKIE_OR_VERIFY_REQUIRED";
		}
		if (text.contains("captcha") || text.contains("风控") || text.contains("risk")) {
			return "F2_RISK_CONTROL";
		}
		if (text.contains("timeout") || text.contains("timed out")) {
			return "F2_TIMEOUT";
		}
		if (!outFileExists && exitCode != null && exitCode != 0) {
			return "F2_PROCESS_FAILED_NO_OUTPUT";
		}
		if (exitCode != null && exitCode != 0) {
			return "F2_PROCESS_FAILED";
		}
		return "F2_UNKNOWN_FAILURE";
	}

	private static String buildRootCause(String errorCode, String exceptionType, String exceptionMessage, String output) {
		if ("F2_INTERNAL_NICKNAME_RAW_UNBOUND".equals(errorCode)) {
			return "f2 douyin fetch failed before nickname_raw was initialized; likely first-page response abnormal, cookie/risk-control, or upstream response schema changed";
		}
		if (exceptionType != null && exceptionMessage != null) {
			return exceptionType + ": " + exceptionMessage;
		}
		if (output == null || output.trim().isEmpty()) {
			return "f2 returned no stdout/stderr, so no root exception could be extracted";
		}
		return "f2 did not return stream-vault-ok success marker";
	}

	private void reportF2CookieResult(String platform, String cookie, String f2cmd) {
		if (f2cmd != null && f2cmd.contains("stream-vault-ok")) {
			platformCookieService.reportSuccess(platform, cookie);
			return;
		}
		if (platformCookieService.isRiskSignal(f2cmd)) {
			platformCookieService.reportRisk(platform, cookie, previewOutput(f2cmd));
		}
	}

	private void reportCookieDegradedFetch(CollectDataEntity entity, String mode, String cookie, int requested,
			JSONArray jsonFromFile, long existingDetailCount) {
		int fetched = jsonFromFile == null ? 0 : jsonFromFile.size();
		douyinCookieHealthService.reportCollectFetchWindow(entity, mode, cookie, requested, fetched, existingDetailCount);
	}

	private JSONObject buildF2FailureDiagnostics(String mode, String sourceId, String cookie) {
		JSONObject diagnostics = new JSONObject();
		diagnostics.put("mode", mode);
		diagnostics.put("sourceId", sourceId);
		diagnostics.put("cookiePresent", cookie != null && !cookie.trim().isEmpty());
		diagnostics.put("cookieLength", cookie == null ? 0 : cookie.length());
		diagnostics.put("cookieHasSessionid", containsIgnoreCase(cookie, "sessionid"));
		diagnostics.put("cookieHasSidGuard", containsIgnoreCase(cookie, "sid_guard"));
		diagnostics.put("cookieHasTtwid", containsIgnoreCase(cookie, "ttwid"));
		diagnostics.put("cookieHasPassportCsrf", containsIgnoreCase(cookie, "passport_csrf_token"));
		if ("post".equals(mode) || "like".equals(mode) || "recommend".equals(mode)) {
			JSONObject profileDiagnostic = DouUtil.diagnoseUserProfile(sourceId);
			diagnostics.put("profileDiagnostic", profileDiagnostic);
		}
		if ("post".equals(mode) || "like".equals(mode)) {
			diagnostics.put("awemeListDiagnostic", diagnoseDouyinAwemeList(mode, sourceId, cookie));
		}
		return diagnostics;
	}

	private boolean containsIgnoreCase(String text, String needle) {
		return text != null && needle != null && text.toLowerCase().contains(needle.toLowerCase());
	}

	private JSONObject diagnoseDouyinAwemeList(String mode, String sourceId, String cookie) {
		JSONObject diagnostic = new JSONObject();
		diagnostic.put("mode", mode);
		diagnostic.put("sourceId", sourceId);
		diagnostic.put("count", 1);
		diagnostic.put("maxCursor", "0");
		if (sourceId == null || sourceId.trim().isEmpty()) {
			diagnostic.put("success", false);
			diagnostic.put("error", "empty sourceId");
			return diagnostic;
		}
		String endpoint;
		if ("post".equals(mode)) {
			endpoint = "https://www.douyin.com/aweme/v1/web/aweme/post/?";
		} else if ("like".equals(mode)) {
			endpoint = "https://www.douyin.com/aweme/v1/web/aweme/favorite/?";
		} else {
			diagnostic.put("success", false);
			diagnostic.put("error", "unsupported mode");
			return diagnostic;
		}
		try {
			String query = "aid=6383&sec_user_id=#uid#&count=1&max_cursor=0&cookie_enabled=true&platform=PC&downlink=10"
					.replace("#uid#", sourceId);
			String xbogus = XbogusUtil.getXBogus(query);
			String url = endpoint + query + "&X-Bogus=" + xbogus;
			diagnostic.put("endpoint", endpoint);
			diagnostic.put("xbogusPresent", xbogus != null && !xbogus.trim().isEmpty());
			JSONObject httpDiagnostic = DouUtil.diagnoseHttpGet(url, cookie, 800);
			diagnostic.put("http", httpDiagnostic);
			diagnostic.put("success", httpDiagnostic != null && httpDiagnostic.getBooleanValue("success"));
		} catch (Exception e) {
			diagnostic.put("success", false);
			diagnostic.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		return diagnostic;
	}

	private void recordFetchFailureDetail(CollectDataEntity entity, String errorCode, String errorMsg, JSONObject detailJson) {
		if (entity == null || entity.getId() == null) {
			return;
		}
		FetchRunContext fetchContext = lastFetchRunContext.get();
		CollectDataDetailEntity existing = collectDataDetailService.findByVideoAndDataid("__FETCH__", entity.getId());
		CollectDataDetailEntity detail = existing == null ? new CollectDataDetailEntity() : existing;
		detail.setDataid(entity.getId());
		detail.setVideoid("__FETCH__");
		detail.setVideoname("用户作品列表抓取失败");
		detail.setOriginaladdress(entity.getOriginaladdress());
		detail.setStatus("执行失败");
		detail.setMediatype("task");
		detail.setErrorcode(errorCode);
		detail.setErrormsg(errorMsg);
		StringBuilder processLog = new StringBuilder();
		appendLog(processLog, "getDYData", "originaladdress=" + entity.getOriginaladdress());
		if (fetchContext != null) {
			appendLog(processLog, "run", "runId=" + fetchContext.runId() + ", fetchMode=" + fetchContext.mode()
					+ ", sourceId=" + fetchContext.sourceId() + ", maxc=" + fetchContext.maxc());
		}
		appendLog(processLog, "f2", "exitCode=" + CommandUtil.getLastF2ExitCode() + ", durationMs=" + CommandUtil.getLastF2DurationMs());
		if (detailJson != null && detailJson.getString("outputPreview") != null) {
			appendLog(processLog, "f2-output", detailJson.getString("outputPreview"));
		}
		if (detailJson != null && detailJson.getJSONObject("diagnostics") != null) {
			appendLog(processLog, "diagnostics", detailJson.getJSONObject("diagnostics").toJSONString());
		}
		detail.setProcesslog(processLog.toString());
		JSONObject context = new JSONObject();
		context.put("taskId", entity.getId());
		context.put("taskName", entity.getTaskname());
		context.put("originaladdress", entity.getOriginaladdress());
		context.put("error", errorMsg);
		if (fetchContext != null) {
			context.put("runId", fetchContext.runId());
			context.put("fetchMode", fetchContext.mode());
			context.put("sourceId", fetchContext.sourceId());
			context.put("maxc", fetchContext.maxc());
			context.put("taskout", fetchContext.taskout());
		}
		if (detailJson != null) {
			context.put("detail", detailJson);
		}
		detail.setDetailjson(context.toJSONString());
		detail.setCreatetime(DateUtils.formatDateTime(new Date()));
		collectDataDetailService.save(detail);

		JSONArray planItems = new JSONArray();
		JSONObject planItem = new JSONObject();
		planItem.put("stage", "getDYData");
		planItem.put("decision", "fetch-fail");
		if (fetchContext != null) {
			planItem.put("runId", fetchContext.runId());
			planItem.put("fetchMode", fetchContext.mode());
			planItem.put("sourceId", fetchContext.sourceId());
			planItem.put("maxc", fetchContext.maxc());
		}
		planItem.put("errorcode", errorCode);
		planItem.put("errormsg", errorMsg);
		planItem.put("exitCode", CommandUtil.getLastF2ExitCode());
		planItem.put("durationMs", CommandUtil.getLastF2DurationMs());
		if (detailJson != null) {
			planItem.put("rootCause", detailJson.getString("rootCause"));
			planItem.put("mode", detailJson.getString("mode"));
			planItem.put("sourceId", detailJson.getString("sourceId"));
			planItem.put("outPath", detailJson.getString("outPath"));
			planItem.put("outFileExists", detailJson.get("outFileExists"));
			planItem.put("outFileSize", detailJson.get("outFileSize"));
			planItem.put("exceptionType", detailJson.getString("exceptionType"));
			planItem.put("exceptionMessage", detailJson.getString("exceptionMessage"));
			planItem.put("stackTop", detailJson.getString("stackTop"));
			planItem.put("outputPreview", detailJson.getString("outputPreview"));
			planItem.put("diagnostics", detailJson.getJSONObject("diagnostics"));
			planItem.put("processlog", processLog.toString());
		}
		planItems.add(planItem);
		if (activePersistentRunId.get() == null) {
			entity.setTaskstatus("执行失败(抓取异常)");
			entity.setEndtime(DateUtils.formatDateTime(new Date()));
			entity.setCount("0");
		}
		entity.setLastplanitems(snapshotCodec().encodePlan(planItems,
				snapshotContext(fetchContext == null ? null : fetchContext.runId(), fetchContext)));
		entity.setLastfetchtime(DateUtils.formatDateTime(new Date()));
		collectdDataDao.save(entity);
	}

	private JSONObject getLastF2Context(F2FailureDiagnosis diagnosis) {
		JSONObject context = new JSONObject();
		context.put("exitCode", CommandUtil.getLastF2ExitCode());
		context.put("durationMs", CommandUtil.getLastF2DurationMs());
		if (diagnosis != null) {
			context.put("errorCode", diagnosis.errorCode());
			context.put("rootCause", diagnosis.rootCause());
			context.put("mode", diagnosis.mode());
			context.put("originaladdress", diagnosis.originaladdress());
			context.put("sourceId", diagnosis.sourceId());
			context.put("maxc", diagnosis.maxc());
			context.put("outPath", diagnosis.outPath());
			context.put("outFileExists", diagnosis.outFileExists());
			context.put("outFileSize", diagnosis.outFileSize());
			context.put("exceptionType", diagnosis.exceptionType());
			context.put("exceptionMessage", diagnosis.exceptionMessage());
			context.put("stackTop", diagnosis.stackTop());
			context.put("outputPreview", diagnosis.outputPreview());
			context.put("diagnostics", diagnosis.diagnostics());
		}
		return context;
	}

	static record F2FailureDiagnosis(String mode, String originaladdress, String sourceId, int maxc, String outPath,
			boolean outFileExists, long outFileSize, Integer exitCode, Long durationMs, String errorCode,
			String exceptionType, String exceptionMessage, String stackTop, String rootCause, String outputPreview,
			JSONObject diagnostics) {
		String toLogMessage() {
			return "errorCode=" + errorCode
					+ " rootCause=" + rootCause
					+ " exceptionType=" + exceptionType
					+ " exceptionMessage=" + exceptionMessage
					+ " stackTop=" + stackTop
					+ " mode=" + mode
					+ " originaladdress=" + originaladdress
					+ " sourceId=" + sourceId
					+ " maxc=" + maxc
					+ " exitCode=" + exitCode
					+ " durationMs=" + durationMs
					+ " outPath=" + outPath
					+ " outFileExists=" + outFileExists
					+ " outFileSize=" + outFileSize
					+ " outputPreview=" + outputPreview
					+ " diagnostics=" + (diagnostics == null ? null : diagnostics.toJSONString());
		}
	}

	static record FetchRunContext(String runId, Integer taskId, String taskName, String originaladdress,
			String monitor, String mode, String sourceId, int maxc, long existingDetailCount, long successDetailCount,
			Integer omaxcur, Integer maxcur, String taskout) {
	}

	private long safeFileSize(String path) {
		try {
			return Files.size(Paths.get(path));
		} catch (Exception e) {
			return -1;
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

	private void appendLog(StringBuilder sb, String stage, String msg) {
		sb.append("[").append(DateUtils.formatDateTime(new Date())).append("] ")
				.append(stage).append(" - ").append(msg == null ? "" : msg).append("\n");
	}

	private String safeDisplayName(String desc, String awemeId, String fallbackPrefix) {
		if (desc != null && !desc.trim().isEmpty()) {
			return desc;
		}
		return "[" + fallbackPrefix + "]-" + (awemeId == null ? "unknown" : awemeId);
	}

	private String trimMsg(String msg) {
		if (msg == null) {
			return null;
		}
		return msg.length() > 500 ? msg.substring(0, 500) : msg;
	}

	private String safeDetailJson(JSONObject obj) {
		if (obj == null) {
			return null;
		}
		String text = obj.toJSONString();
		if (text.length() > 16000) {
			return text.substring(0, 16000) + "...(truncated)";
		}
		return text;
	}

	private String formatPublishTimeFromEpochSeconds(String epochSeconds) {
		return DateUtils.normalizePublishTime(epochSeconds);
	}

	public JSONArray getAllDYData(CollectDataEntity entity) throws Exception {
		String api = "";
		String sign = "aid=6383&sec_user_id=#uid#&count=35&max_cursor=#max_cursor#&cookie_enabled=true&platform=PC&downlink=10";
		if (entity.getOriginaladdress().contains("post")) {
			api = "https://www.douyin.com/aweme/v1/web/aweme/post/?";
		}
		if (entity.getOriginaladdress().contains("like")) {
			api = "https://www.douyin.com/aweme/v1/web/aweme/favorite/?";
		}
		String sec_user_id = entity.getOriginaladdress().replaceAll("post", "").replaceAll("like", "");
		String singnew = sign.replaceAll("#uid#", sec_user_id);
		api = api + singnew;
		JSONArray dyNextData = this.getDYNextData(api, new JSONArray(), "0", singnew);
		return dyNextData;

	}

	public JSONArray getDYNextData(String api, JSONArray data, String max_cursor, String sign) throws Exception {
		String newsign = sign.replaceAll("#max_cursor#", max_cursor);
		String apiaddt = api.replaceAll("#max_cursor#", max_cursor);
		String xbogus = XbogusUtil.getXBogus(newsign);
		apiaddt = apiaddt + "&X-Bogus=" + xbogus;
		System.out.println(apiaddt);
		String cookie = platformCookieService.currentDouyinCookie("legacy_aweme_page");
		String httpget = DouUtil.httpget(apiaddt, cookie);
		JSONObject parseObject = JSONObject.parseObject(httpget);
		JSONArray jsonArray = parseObject.getJSONArray("aweme_list");
		max_cursor = parseObject.getString("max_cursor");
		if (!max_cursor.equals("0")) {
			data.addAll(jsonArray);
			sleepCollectItemInterval();
			return this.getDYNextData(api, data, max_cursor, sign);
		} else {
			data.addAll(jsonArray);
			return data;
		}
	}

	public AjaxEntity loadDouFav(String uid) {
		String cookie = platformCookieService.currentDouyinCookie("load_collects");
		String f2cmd = CommandUtil.f2cmd(cookie, null, "fetch_user_collects", uid, null, null, null);
		reportF2CookieResult("抖音", cookie, f2cmd);
		String startTag = "stream-vault-start-collects";
		String endTag = "stream-vault-end-collects";
		int startIndex = f2cmd.indexOf(startTag) + startTag.length();
		int endIndex = f2cmd.indexOf(endTag);
		String content = f2cmd.substring(startIndex, endIndex).trim();
		return new AjaxEntity(Global.ajax_success, content, "请求成功");
	}

	public AjaxEntity resolveDouyinUserLink(String text) {
		if (text == null || text.trim().isEmpty()) {
			return new AjaxEntity(Global.ajax_uri_error, "链接内容不能为空", null);
		}
		String shortUrl = extractFirstUrl(text.trim());
		if (shortUrl == null) {
			return new AjaxEntity(Global.ajax_uri_error, "未识别到链接", null);
		}
		try {
			Document document = Jsoup.connect(shortUrl)
					.userAgent(DouUtil.ua)
					.followRedirects(true)
					.timeout(10000)
					.get();
			String finalUrl = document.baseUri();
			String secUserId = extractSecUserId(finalUrl);
			String authorName = extractDouyinAuthorName(document == null ? null : document.title());
			if (secUserId == null || secUserId.trim().isEmpty()) {
				return new AjaxEntity(Global.ajax_uri_error, "未解析到抖音用户ID", null);
			}
			JSONObject profileUser = extractProfileUser(DouUtil.fetchUserProfile(secUserId));
			String nickname = profileUser == null ? null : profileUser.getString("nickname");
			Map<String, String> record = new HashMap<>();
			record.put("platform", "抖音");
			record.put("finalUrl", finalUrl);
			record.put("secUserId", secUserId);
			String resolvedName = firstNotBlank(nickname, authorName);
			if (resolvedName != null && !resolvedName.trim().isEmpty()) {
				record.put("nickname", resolvedName.trim());
				record.put("authorName", resolvedName.trim());
			}
			return new AjaxEntity(Global.ajax_success, "解析成功", record);
		} catch (Exception e) {
			logger.error("resolveDouyinUserLink error", e);
			return new AjaxEntity(Global.ajax_uri_error, "抖音链接解析失败", null);
		}
	}

	private String extractFirstUrl(String text) {
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("https?://[^\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE)
				.matcher(text);
		if (!m.find()) {
			return null;
		}
		String url = m.group();
		while (url.endsWith("。") || url.endsWith("，") || url.endsWith("；") || url.endsWith(",") || url.endsWith(".")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	private String extractSecUserId(String finalUrl) {
		if (finalUrl == null || finalUrl.isEmpty()) {
			return null;
		}
		try {
			URI uri = new URI(finalUrl);
			String path = uri.getPath();
			if (path == null) {
				return null;
			}
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("/user/([^/?#]+)").matcher(path);
			if (m.find()) {
				return m.group(1);
			}
		} catch (URISyntaxException e) {
			logger.warn("invalid final url: {}", finalUrl);
		}
		return null;
	}

	private String extractDouyinAuthorName(String title) {
		if (title == null) {
			return null;
		}
		String t = title.trim();
		if (t.isEmpty()) {
			return null;
		}
		t = t.replaceAll("[|｜]\\s*抖音.*$", "").trim();
		t = t.replaceAll("的抖音.*$", "").trim();
		t = t.replaceAll("\\s*[-—].*$", "").trim();
		if (t.isEmpty()) {
			return null;
		}
		return t;
	}

	public AjaxEntity createBillFav(CollectDataEntity collectDataEntity, String monitor) {
		// 收藏夹 修改 支持 分类目录
		String newod = collectDataEntity.getOriginaladdress().replaceAll("bili-fav-", "");
		String info = "https://api.bilibili.com/x/v3/fav/folder/info?media_id=" + newod;
		// System.out.println(newod);
		String infobili = HttpUtil.httpGetBili(info, "UTF-8", Global.bilicookies);
		// 收藏夹介绍
		JSONObject object = JSONObject.parseObject(infobili);
		String namepath = object.getJSONObject("data").getString("title");
		String temporaryDirectory = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, null, namepath,
				null);
		if (Global.getGeneratenfo) {
			// 防止重复写问题
			if (!(new File(temporaryDirectory + File.separator + "tvshow.nfo").exists())) {
				// 文件不存在
				EmbyMetadataGenerator.createFavoriteNfo(infobili, temporaryDirectory);
			}

		}
		String api = "https://api.bilibili.com/x/v3/fav/resource/ids?media_id=" + newod + "&platform=web";
		String httpGetBili = HttpUtil.httpGetBili(api, "UTF-8", Global.bilicookies);
		JSONArray jsonArray = JSONObject.parseObject(httpGetBili).getJSONArray("data");
		if (jsonArray.size() > 0) {
			// 进线程前创建collectDataEntity
			collectDataEntity.setTaskstatus("已提交待处理");
			collectDataEntity.setCreatetime(DateUtils.formatDateTime(new Date()));
			collectDataEntity.setCount(String.valueOf(jsonArray.size())); // 收藏夹肯定是全量 这里无所谓 count怎么处理
			// collectDataEntity.setCarriedout("0"); // 归零
			CollectDataEntity save = collectdDataDao.save(collectDataEntity);
			// 提交线程
			if (null != monitor && monitor.equals("Y")) {
				try {
					this.createBiliData(save, jsonArray, namepath, "收藏夹");
					return new AjaxEntity(Global.ajax_success, "任务启动成功", null);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			return new AjaxEntity(Global.ajax_success, "任务创建成功", null);
		}
		return new AjaxEntity(Global.ajax_uri_error, "数据为空 请检查收藏ID", null);
	}

	public AjaxEntity createBillArc(CollectDataEntity collectDataEntity, String monitor) {
		String newod = collectDataEntity.getOriginaladdress().replaceAll("bili-arc-", "");
		if (null != monitor && monitor.equals("Y")) {
			try {
				Integer maxc = 300;
				long countByDataid = collectDataDetailDao.countByDataid(collectDataEntity.getId());
				if (countByDataid > 0) {
					maxc = null != collectDataEntity.getMaxcur() ? collectDataEntity.getMaxcur() : 300;
				} else {
					maxc = null != collectDataEntity.getOmaxcur() ? collectDataEntity.getOmaxcur() : 300;
				}
				JSONArray arcSearch = BiliUtil.ArcSearch(newod, maxc); // 根据maxcur获取数据
				if (null != arcSearch && arcSearch.size() > 0) {
					JSONObject ddd = arcSearch.getJSONObject(0);
					String namepath = ddd.getString("author");
					collectDataEntity.setTaskstatus("已提交待处理");
					collectDataEntity.setCreatetime(DateUtils.formatDateTime(new Date()));
					collectDataEntity.setCount(String.valueOf(arcSearch.size()));
					// collectDataEntity.setCarriedout("0"); // 归零
					CollectDataEntity save = collectdDataDao.save(collectDataEntity);

					JSONObject infobili = new JSONObject();
					JSONObject data = new JSONObject();
					String cover = "";
					try {
						cover = ddd.getJSONObject("meta").getString("cover");
					} catch (Exception e) {
						logger.error(ddd.toJSONString());
					}
					data.put("title", namepath + "的投稿");
					data.put("intro", namepath + "的投稿");
					data.put("cover", cover);
					data.put("ctime", DateUtils.getDate());
					infobili.put("data", data);
					// 创建
					String temporaryDirectory = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, null,
							namepath, null);
					if (Global.getGeneratenfo) {
						// 防止重复写问题
						if (!(new File(temporaryDirectory + File.separator + "tvshow.nfo").exists())) {
							// 文件不存在
							EmbyMetadataGenerator.createFavoriteNfo(infobili.toJSONString(), temporaryDirectory);
						}
					}

					this.createBiliData(save, arcSearch, namepath, "投稿");
					return new AjaxEntity(Global.ajax_success,"任务启动成功", null);
				}
			} catch (Exception e2) {
				e2.printStackTrace();
			}
		}
		return new AjaxEntity(Global.ajax_success,"任务创建成功", null);
	}
	
	
	public AjaxEntity createBillSeasonsArchives(CollectDataEntity collectDataEntity, String monitor) {
		String newod = collectDataEntity.getOriginaladdress().replaceAll("bili-seaarc-", "");
		if (null != monitor && monitor.equals("Y")) {
			try {
				Integer maxc = 300;
				long countByDataid = collectDataDetailDao.countByDataid(collectDataEntity.getId());
				if (countByDataid > 0) {
					maxc = null != collectDataEntity.getMaxcur() ? collectDataEntity.getMaxcur() : 300;
				} else {
					maxc = null != collectDataEntity.getOmaxcur() ? collectDataEntity.getOmaxcur() : 300;
				}
//				maxc = 30;
				String[] datasp = newod.split("#");
				JSONArray arcSearch = BiliUtil.SeasonsSearch(datasp[0],datasp[1], maxc); 
				if (null != arcSearch && arcSearch.size() > 0) {
					JSONObject ddd = arcSearch.getJSONObject(0);
					String namepath = FileNameTemplateUtil.resolveFileName(ddd.getString("name"), collectDataEntity.getTaskname(), null, null, "哔哩");
					String description = ddd.getString("description");
					collectDataEntity.setTaskstatus("已提交待处理");
					collectDataEntity.setCreatetime(DateUtils.formatDateTime(new Date()));
					collectDataEntity.setCount(String.valueOf(arcSearch.size()));
					CollectDataEntity save = collectdDataDao.save(collectDataEntity);

					JSONObject infobili = new JSONObject();
					JSONObject data = new JSONObject();
					String cover = "";
					try {
						cover = ddd.getString("cover");
					} catch (Exception e) {
						logger.error(ddd.toJSONString());
					}
					data.put("title", namepath + "的投稿");
					data.put("intro", (description!=null?description:namepath));
					data.put("cover", cover);
					data.put("ctime", DateUtils.getDate());
					infobili.put("data", data);
					String temporaryDirectory = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, null,
							namepath, null);
					if (Global.getGeneratenfo) {
						if (!(new File(temporaryDirectory + File.separator + "tvshow.nfo").exists())) {
							EmbyMetadataGenerator.createFavoriteNfo(infobili.toJSONString(), temporaryDirectory);
						}
					}

					this.createBiliData(save, arcSearch, namepath, "合集");
					return new AjaxEntity(Global.ajax_success,"任务启动成功", null);
				}
			} catch (Exception e2) {
				e2.printStackTrace();
			}
		}
		return new AjaxEntity(Global.ajax_success,"任务创建成功", null);
	}

	public AjaxEntity fixBiliFav(String id) {
		Optional<CollectDataEntity> byId = collectdDataDao.findById(Integer.parseInt(id));
		if (byId.isPresent()) {
			CollectDataEntity collectDataEntity = byId.get();
			String originaladdress = "bili-fav-" + collectDataEntity.getOriginaladdress();
			collectDataEntity.setOriginaladdress(originaladdress);
			collectdDataDao.save(collectDataEntity);
			return new AjaxEntity(Global.ajax_success, "更新成功", null);
		}
		return new AjaxEntity(Global.ajax_uri_error, "数据异常", null);
	}

	public Long countTotal() {
		Long collectDataTotal = collectdDataDao.countTotal();
		return collectDataTotal;
	}

	public Optional<CollectDataEntity> findById(Integer taskId) {
		return collectdDataDao.findById(taskId);
	}

	public boolean isCollectTaskEnabled(Integer taskId) {
		return collectdDataDao.findById(taskId).map(task -> !"N".equalsIgnoreCase(task.getTaskenabled())).orElse(false);
	}

	public void executeQueuedCollectTask(Integer taskId, long runId) {
		CollectDataEntity task = collectdDataDao.findById(taskId)
				.orElseThrow(() -> new IllegalArgumentException("收藏任务不存在: " + taskId));
		activePersistentRunId.set(runId);
		try {
			assertCollectExecutionAllowed();
			if ("抖音".equals(task.getPlatform())) {
				String cookie = platformCookieService.currentDouyinCookie("collect_worker");
				if (cookie == null || cookie.isBlank()) {
					throw new CollectFetchException("COOKIE_MISSING", "抖音 Cookie 为空，无法抓取作品列表");
				}
				String address = task.getOriginaladdress();
				if (address == null || !(address.startsWith("post") || address.startsWith("like")
						|| address.startsWith("fav-") || address.startsWith("recommend"))) {
					throw new CollectFetchException("INVALID_SOURCE", "收藏任务地址格式不受支持");
				}
				createDyData(task, "Y", runId);
				return;
			}
			collectRunService.storeFetchedItems(runId, List.of());
			AjaxEntity result = submitCollectData(task, "Y");
			if (result == null || !Global.ajax_success.equals(result.getResCode())) {
				throw new CollectFetchException("COLLECT_EXECUTION_FAILED",
						result == null ? "收藏任务未返回执行结果" : result.getMessage());
			}
		} catch (CollectFetchException e) {
			throw e;
		} catch (DataAccessException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("收藏任务执行异常: " + rootCauseMessage(e), e);
		} finally {
			activePersistentRunId.remove();
			lastF2FailureDiagnosis.remove();
			lastFetchRunContext.remove();
		}
	}


	public AjaxEntity execCollectData(CollectDataEntity collectDataEntity){
		if (collectDataEntity == null || collectDataEntity.getId() == null) {
			return new AjaxEntity(Global.ajax_uri_error, "任务ID不能为空", null);
		}
		Optional<CollectDataEntity> current = collectdDataDao.findById(collectDataEntity.getId());
		if (current.isPresent() && "N".equalsIgnoreCase(current.get().getTaskenabled())) {
			return new AjaxEntity(Global.ajax_uri_error, "任务已暂停，请先开始任务", null);
		}
		try {
			CollectEnqueueResult result = collectEnqueueService.enqueueManual(collectDataEntity.getId());
			String message = result.skippedPaused() ? "任务当前已暂停，本次已记录为跳过"
					: result.inserted() ? "任务已进入持久队列" : "当前任务已在队列或运行中，请勿重复提交";
			return new AjaxEntity(Global.ajax_success, message, result);
		} catch (RuntimeException error) {
			logger.error("[CollectManualEnqueue] failed taskId={}", collectDataEntity.getId(), error);
			return new AjaxEntity(Global.ajax_uri_error, "任务入队失败: " + rootCauseMessage(error), null);
		}
	}

	public AjaxEntity pauseCollectData(Integer id) {
		if (id == null) {
			return new AjaxEntity(Global.ajax_uri_error, "任务ID不能为空", null);
		}
		Optional<CollectDataEntity> byId = collectdDataDao.findById(id);
		if (!byId.isPresent()) {
			return new AjaxEntity(Global.ajax_uri_error, "任务不存在", null);
		}
		CollectDataEntity task = byId.get();
		task.setTaskenabled("N");
		collectdDataDao.save(task);
		quartzTaskService.removeTaskSchedule(id);
		return new AjaxEntity(Global.ajax_success, "任务已暂停", task);
	}

	public AjaxEntity resumeCollectData(Integer id) {
		if (id == null) {
			return new AjaxEntity(Global.ajax_uri_error, "任务ID不能为空", null);
		}
		Optional<CollectDataEntity> byId = collectdDataDao.findById(id);
		if (!byId.isPresent()) {
			return new AjaxEntity(Global.ajax_uri_error, "任务不存在", null);
		}
		CollectDataEntity task = byId.get();
		task.setTaskenabled("Y");
		collectdDataDao.save(task);
		quartzTaskService.scheduleTask(task);
		return new AjaxEntity(Global.ajax_success, "任务已开始", task);
	}

	public AjaxEntity updateCollectData(CollectDataEntity input) {
		if (input == null || input.getId() == null) {
			return new AjaxEntity(Global.ajax_uri_error, "任务ID不能为空", null);
		}
		Optional<CollectDataEntity> byId = collectdDataDao.findById(input.getId());
		if (!byId.isPresent()) {
			return new AjaxEntity(Global.ajax_uri_error, "任务不存在", null);
		}
		CollectDataEntity db = byId.get();
		// 不允许修改基础识别参数（类型/平台/地址）
		db.setTaskname(input.getTaskname() != null ? input.getTaskname() : db.getTaskname());
		db.setMonitoring(input.getMonitoring() != null ? input.getMonitoring() : db.getMonitoring());
		db.setTaskcron(input.getTaskcron() != null ? input.getTaskcron().trim() : db.getTaskcron());
		if (input.getMaxcur() != null) {
			db.setMaxcur(input.getMaxcur());
		}
		if (input.getOmaxcur() != null) {
			db.setOmaxcur(input.getOmaxcur());
		}
		collectdDataDao.save(db);
		// 修改后立即生效：若启用监控则重建调度；否则移除
		if ("Y".equalsIgnoreCase(db.getMonitoring()) && !"N".equalsIgnoreCase(db.getTaskenabled())) {
			quartzTaskService.scheduleTask(db);
		} else {
			quartzTaskService.removeTaskSchedule(db.getId());
		}
		return new AjaxEntity(Global.ajax_success, "任务更新成功", db);
	}

	private void sleepCollectTaskIntervalIfNeeded() {
		int intervalMs = Math.max(0, Global.collectTaskIntervalMs);
		if (intervalMs <= 0) {
			return;
		}
		long previous = lastCollectTaskFinishedAt;
		if (previous <= 0L) {
			return;
		}
		long elapsed = System.currentTimeMillis() - previous;
		long remaining = intervalMs - elapsed;
		if (remaining <= 0L) {
			return;
		}
		try {
			Thread.sleep(remaining);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void sleepCollectItemInterval() {
		int intervalMs = Math.max(0, Global.collectItemIntervalMs);
		if (intervalMs <= 0) {
			return;
		}
		try {
			Thread.sleep(intervalMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void markCollectTaskFinished() {
		lastCollectTaskFinishedAt = System.currentTimeMillis();
	}

	private static JSONObject observedDouyinAuthor(JSONObject awemeDetail) {
		if (awemeDetail == null) {
			return null;
		}
		JSONObject author = awemeDetail.getJSONObject("author");
		return author == null ? awemeDetail : author;
	}

	static DouyinAuthorSnapshot resolveDouyinAuthorSnapshot(JSONObject awemeDetail, JSONObject hybridData,
			JSONObject profileUser, String taskSourceId) {
		JSONObject listAuthor = observedDouyinAuthor(awemeDetail);
		JSONObject hybridAuthor = extractHybridAuthor(hybridData);
		String canonicalUid = firstCanonicalDouyinUid(
				authorSecUid(listAuthor), authorSecUid(hybridAuthor), extractCanonicalTaskSourceId(taskSourceId),
				authorSecUid(profileUser));
		String uniqueId = firstNotBlank(value(profileUser, "unique_id"),
				firstNotBlank(value(hybridAuthor, "unique_id"), value(listAuthor, "unique_id")));
		String numericUid = firstNotBlank(value(profileUser, "uid"),
				firstNotBlank(value(hybridAuthor, "uid"), value(listAuthor, "uid")));
		String nickname = firstNotBlank(value(profileUser, "nickname"),
				firstNotBlank(value(hybridAuthor, "nickname"), value(listAuthor, "nickname")));
		String avatar = firstNotBlank(DouUtil.extractAvatar(profileUser),
				firstNotBlank(DouUtil.extractAvatar(hybridAuthor),
						firstNotBlank(DouUtil.extractAvatar(listAuthor), value(listAuthor, "avatar_thumb"))));
		String signature = firstNotBlank(value(profileUser, "signature"),
				firstNotBlank(value(hybridAuthor, "signature"), value(listAuthor, "signature")));
		return new DouyinAuthorSnapshot(canonicalUid, uniqueId, numericUid, nickname, avatar, signature);
	}

	private static JSONObject extractHybridAuthor(JSONObject hybridData) {
		if (hybridData == null) {
			return null;
		}
		JSONObject detail = DouUtil.findAwemeDetail(hybridData);
		JSONObject author = detail == null ? null : detail.getJSONObject("author");
		if (author != null) {
			return author;
		}
		JSONObject data = hybridData.getJSONObject("data");
		return data == null ? null : data.getJSONObject("author");
	}

	private static String authorSecUid(JSONObject author) {
		return value(author, "sec_uid");
	}

	private static String firstCanonicalDouyinUid(String... candidates) {
		if (candidates == null) {
			return null;
		}
		for (String candidate : candidates) {
			if (AuthorIdentityUtil.isDouyinSecUid(candidate)) {
				return candidate.trim();
			}
		}
		return null;
	}

	private static String extractCanonicalTaskSourceId(String source) {
		if (!hasText(source)) {
			return null;
		}
		String value = source.trim().replaceFirst("^(post|like|recommend)", "");
		return AuthorIdentityUtil.isDouyinSecUid(value) ? value : null;
	}

	private static String value(JSONObject object, String key) {
		return object == null ? null : object.getString(key);
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String firstNotBlank(String first, String second) {
		if (first != null && !first.trim().isEmpty()) {
			return first;
		}
		return second;
	}

	static record DouyinAuthorSnapshot(String canonicalUid, String uniqueId, String numericUid, String nickname,
			String avatar, String signature) {
		boolean needsProfileEnrichment() {
			return (hasText(canonicalUid) || hasText(uniqueId))
					&& (!hasText(uniqueId) || !hasText(nickname) || !hasText(avatar) || !hasText(signature));
		}
	}

	private JSONObject extractProfileUser(JSONObject profile) {
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

	public List<Map<String, Object>> authorDownloadStats() {
		List<Map<String, Object>> result = new ArrayList<>();
		List<CollectDataEntity> tasks = collectdDataDao.findAll();
		List<String> successStatuses = new ArrayList<>();
		successStatuses.add("已完成");
		successStatuses.add("图文已完成");
		for (CollectDataEntity task : tasks) {
			if (!"抖音".equals(task.getPlatform())) {
				continue;
			}
			Map<String, Object> row = new HashMap<>();
			row.put("taskId", task.getId());
			row.put("taskName", task.getTaskname());
			row.put("enabled", task.getTaskenabled());
			row.put("monitoring", task.getMonitoring());
			long doneVideo = collectDataDetailDao.countByDataidAndMediatypeAndStatusIn(task.getId(), "video", successStatuses);
			long doneImage = collectDataDetailDao.countByDataidAndMediatypeAndStatusIn(task.getId(), "image", successStatuses);
			Map<String, Long> latestTotals = collectRunQueryService.latestMediaTotals(task.getId());
			long totalVideo = Math.max(doneVideo, latestTotals.getOrDefault("video", 0L));
			long totalImage = Math.max(doneImage, latestTotals.getOrDefault("graphic", 0L));
			row.put("videoDone", doneVideo);
			row.put("videoTotal", totalVideo);
			row.put("imageDone", doneImage);
			row.put("imageTotal", totalImage);
			result.add(row);
		}
		return result;
	}

	private void prefillDouyinAuthorProfile(JSONArray allDYData, FetchRunContext context, String runId) {
		if (allDYData == null || allDYData.isEmpty() || authorProfileService == null) {
			return;
		}
		JSONObject awemeDetail = allDYData.getJSONObject(0);
		if (awemeDetail == null) {
			return;
		}
		String taskSourceId = context == null ? null : context.sourceId();
		DouyinAuthorSnapshot snapshot = resolveDouyinAuthorSnapshot(awemeDetail, null, null, taskSourceId);
		String authorUidForSave = snapshot.canonicalUid();
		if (authorUidForSave == null) {
			logger.info("[CollectTask] author prefill skipped runId={} reason=no-author-uid uniqueId={} nickname={}",
					runId, snapshot.uniqueId(), snapshot.nickname());
			return;
		}
		authorProfileService.upsertAuthor("抖音", authorUidForSave, snapshot.uniqueId(), snapshot.nickname(), snapshot.avatar(),
				AuthorIdentityUtil.douyinHomepage(authorUidForSave), snapshot.signature());
		logger.info("[CollectTask] author prefill runId={} authorUid={} uniqueId={} nickname={}",
				runId, authorUidForSave, snapshot.uniqueId(), snapshot.nickname());
	}

	static SnapshotMediaStats parseSnapshotMediaStats(String snapshot) {
		SnapshotReadResult result = new SnapshotCodec(SnapshotCodec.DEFAULT_MAX_BYTES,
				SnapshotCodec.DEFAULT_FORMAT_VERSION).read(snapshot);
		if (!result.available()) throw new IllegalArgumentException(result.warningMessage());
		return new SnapshotMediaStats(result.videoTotal(), result.graphicTotal());
	}

	static boolean isSnapshotTruncated(String snapshot) {
		return snapshot != null && (snapshot.contains("...(truncated)")
				|| snapshot.contains("\"snapshot_truncated\":true") || snapshot.contains("\"truncated\":true"));
	}

	static SnapshotMediaStats scanSnapshotMediaStats(String snapshot) {
		if (snapshot == null || snapshot.isEmpty()) {
			return new SnapshotMediaStats(0, 0);
		}
		int videoCount = 0;
		int imageCount = 0;
		Matcher matcher = SNAPSHOT_HAS_VIDEO_PATTERN.matcher(snapshot);
		while (matcher.find()) {
			if ("true".equals(matcher.group(1))) {
				videoCount++;
			} else {
				imageCount++;
			}
		}
		return new SnapshotMediaStats(videoCount, imageCount);
	}

	static String previewText(String value, int limit) {
		if (value == null) {
			return "";
		}
		String text = value.replace('\r', ' ').replace('\n', ' ');
		if (text.length() <= limit) {
			return text;
		}
		return text.substring(0, limit) + "...";
	}

	record SnapshotMediaStats(int videoCount, int imageCount) {
	}
}
