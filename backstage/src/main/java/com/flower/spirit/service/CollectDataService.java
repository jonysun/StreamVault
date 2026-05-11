package com.flower.spirit.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.io.File;
import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.common.AjaxEntity;
import com.flower.spirit.config.Global;
import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.dao.CollectdDataDetailDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.CollectDataDetailEntity;
import com.flower.spirit.entity.CollectDataEntity;
import com.flower.spirit.entity.VideoDataEntity;
import com.flower.spirit.executor.DouYinExecutor;
import com.flower.spirit.task.QuartzTaskService;
import com.flower.spirit.utils.Aria2Util;
import com.flower.spirit.utils.BiliUtil;
import com.flower.spirit.utils.CommandUtil;
import com.flower.spirit.utils.DateUtils;
import com.flower.spirit.utils.DouUtil;
import com.flower.spirit.utils.EmbyMetadataGenerator;
import com.flower.spirit.utils.FileUtil;
import com.flower.spirit.utils.FileNameTemplateUtil;
import com.flower.spirit.utils.HttpUtil;
import com.flower.spirit.utils.StringUtil;
import com.flower.spirit.utils.XbogusUtil;
import com.flower.spirit.utils.sendNotify;

@Service
public class CollectDataService {

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

	private Logger logger = LoggerFactory.getLogger(CollectDataService.class);

	@Autowired
	private QuartzTaskService quartzTaskService;

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
    public AjaxEntity saveCollectData(CollectDataEntity entity) {
		if (entity.getTaskenabled() == null || entity.getTaskenabled().trim().isEmpty()) {
			entity.setTaskenabled("Y");
		}
        collectdDataDao.save(entity);
        quartzTaskService.scheduleTask(entity);
     	return new AjaxEntity(Global.ajax_success, "任务创建成功", entity);
    }
    

    public AjaxEntity findPage(CollectDataEntity res) {
        PageRequest pageRequest = PageRequest.of(res.getPageNo(), res.getPageSize());

        Specification<CollectDataEntity> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (res != null) {
                if (StringUtil.isString(res.getTaskid())) {
                    predicates.add(cb.like(root.get("taskid"), "%" + res.getTaskid() + "%"));
                }
                if (StringUtil.isString(res.getPlatform())) {
                    predicates.add(cb.like(root.get("platform"), "%" + res.getPlatform() + "%"));
                }
            }

            query.orderBy(cb.desc(root.get("id")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<CollectDataEntity> findAll = collectdDataDao.findAll(specification, pageRequest);
        return new AjaxEntity(Global.ajax_success, "数据获取成功", findAll);
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
			if (null == Global.tiktokCookie || Global.tiktokCookie.equals("")) {
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
					logger.error("异常" + e.getMessage());
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
							logger.info(vt + (i + 1) + "下载流程结束");

							JSONObject owner = JSONObject.parseObject(map.get("owner"));
							String upface = owner.getString("face");
							String upname = owner.getString("name");
							String upmid = owner.getString("mid");
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
						        videoDataEntity.setVideoinfo(videoInfoJson.toJSONString());
							}
							videoDataEntity.setVideoauthor(upname);
							videoDataDao.save(videoDataEntity);
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

				Thread.sleep(2500);
			}
		}
		if (videoaddcount > 0) {
			sendNotify.sendMessage(videoaddcount, entity.getTaskname());
		}
		entity.setTaskstatus("处理完成");
		entity.setEndtime(DateUtils.formatDateTime(new Date()));
		collectdDataDao.save(entity);
		System.gc();

	}

	public void createDyData(CollectDataEntity entity, String monitor) throws Exception {
		logger.info("[CollectTask] createDyData start id={} name={} monitor={} originaladdress={}",
				entity.getId(), entity.getTaskname(), monitor, entity.getOriginaladdress());
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
		JSONArray allDYData = this.getDYData(entity, monitor);
		if (allDYData != null) {
			entity.setLastfetchcount(allDYData.size());
			entity.setLastfetchtime(DateUtils.formatDateTime(new Date()));
			entity.setLastfetchsnapshot(buildFetchSnapshot(allDYData));
			collectdDataDao.save(entity);
		}
		logger.info("[CollectTask] getDYData result id={} isNull={} size={}", entity.getId(), allDYData == null,
				allDYData == null ? 0 : allDYData.size());
		if (allDYData == null) {
			logger.error("[CollectTask] getDYData returned null id={} name={} originaladdress={}",
					entity.getId(), entity.getTaskname(), entity.getOriginaladdress());
		}
		// System.out.println(allDYData.size());
		String risk = "0";
		if (allDYData != null) {
			entity.setCount(String.valueOf(allDYData.size()));
			entity.setTaskstatus("已开始处理");
			collectdDataDao.save(entity);
			JSONArray planItems = new JSONArray();
			for (int i = 0; i < allDYData.size(); i++) {
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
				JSONObject planItem = new JSONObject();
				planItem.put("aweme_id", awemeId);
				planItem.put("desc", aweme_detail.getString("desc"));
				planItem.put("index", i + 1);
				String desc = aweme_detail.getString("desc");
				String displayName = safeDisplayName(desc, awemeId, "视频");
				String detailJson = safeDetailJson(aweme_detail);
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
					planItem.put("decision", "image-branch");
					displayName = safeDisplayName(desc, awemeId, "图文");
					appendLog(processLog, "branch", "video_play_addr empty -> imageText executor");
					CollectDataDetailEntity existingDetail = collectDataDetailService.findByVideoAndDataid(awemeId, entity.getId());
					if (existingDetail != null) {
						appendLog(processLog, "skip", "detail exists in collect_data_detail");
						skippedThisRun++;
						planItem.put("decision", "skip-detail-exists");
						planItems.add(planItem);
						continue;
					}
					// 不支持
					try {
						DouYinExecutor.ImageTextExecutor(awemeId, entity.getOriginaladdress(),null);
						CollectDataDetailEntity collectDataDetailEntity = new CollectDataDetailEntity();
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
						Thread.sleep(2500);
						collectDataDetailService.save(collectDataDetailEntity);
						String carriedout = entity.getCarriedout() == null ? "1"
								: String.valueOf(Integer.parseInt(entity.getCarriedout()) + 1);
						entity.setCarriedout(carriedout);
						collectdDataDao.save(entity);
						graphiccount++;
						successThisRun++;
						planItem.put("decision", "image-success");
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
					}
					planItems.add(planItem);
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
				logger.info("[CollectTask] item start taskId={} index={} awemeId={} desc={}", entity.getId(), i,
						awemeId, desc);

				List<VideoDataEntity> findByVideoid = videoDataService.findByVideoid(awemeId);
				if (findByVideoid.size() > 0) {
					VideoDataEntity existsVideo = findByVideoid.get(0);
					File vf = existsVideo.getVideoaddr() == null ? null : new File(existsVideo.getVideoaddr());
					if (vf == null || !vf.exists()) {
						appendLog(processLog, "dedup", "db exists but file missing, force redownload");
						findByVideoid = new ArrayList<>();
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
					logger.info("已使用批量下载,下载器类型为:" + Global.downtype);
					if (Global.downtype.equals("a2")) {
						appendLog(processLog, "download", "using aria2");
						Aria2Util.sendMessage(Global.a2_link, Aria2Util.createDouparameter(videoplay, dir,
								filename + ".mp4", Global.a2_token, Global.tiktokCookie));
					}
					HashMap<String, String> header = new HashMap<String, String>();
					header.put("User-Agent", DouUtil.ua);
					header.put("cookie", Global.tiktokCookie);
					header.put("Referer", "https://www.douyin.com/");
					if (Global.downtype.equals("http")) {
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
							logger.error("[CollectTask] risk triggered taskId={} awemeId={} output={}", entity.getId(), awemeId,
									downloadFileWithOkHttp);
							break;
						}
					}
					HttpUtil.downloadFileWithOkHttp(coveruri, filename + ".jpg", dir2, header);
					VideoDataEntity videoDataEntity = new VideoDataEntity(awemeId, desc, desc, "抖音", coverunaddr,
							FileUtil.generateDir(true, Global.platform.douyin.name(), false, filename, taskname, "mp4"),
							videounrealaddr, entity.getOriginaladdress());
					videoDataEntity.setPublishtime(formatPublishTimeFromEpochSeconds(aweme_detail.getString("create_time")));
					String taskUid = entity.getOriginaladdress().replaceFirst("^(post|like|recommend)", "");
					if (taskUid != null && !taskUid.trim().isEmpty() && !taskUid.startsWith("fav-")) {
						videoDataEntity.setSourceurl("https://www.douyin.com/user/" + taskUid + "?modal_id=" + awemeId);
					}
					if (Global.getGeneratenfo) {
						String uid = aweme_detail.getString("uid");
						String publisher = dyNickname + "-" + uid + ".png";
						String coverdir = FileUtil.generateDir(true, Global.platform.douyin.name(), false, filename,
								taskname, null);
						HttpUtil.downloadFileWithOkHttp(aweme_detail.getString("avatar_thumb"), publisher, coverdir,
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
						map.put("upmid", aweme_detail.getString("uid"));
						map.put("cid", awemeId);
						map.put("upface", publisher);
						EmbyMetadataGenerator.createFavoriteEpisodeDouNfo(map, dir, i + 1, temporaryDirectory);
						videoDataEntity.setVideoauthor(dyNickname);
					} else {
						videoDataEntity.setVideoauthor(dyNickname);
					}
					videoDataDao.save(videoDataEntity);
					appendLog(processLog, "save", "video saved");
					logger.info("下载流程结束");
					Thread.sleep(5000);
					logger.info("等待五秒在继续下一个");
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
					entity.setCarriedout(carriedout);
					collectdDataDao.save(entity);
					videoaddcount++;
					if ("已完成".equals(status) || "图文已完成".equals(status)) {
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
				planItems.add(planItem);

			}
			entity.setLastplanitems(planItems.toJSONString());
			collectdDataDao.save(entity);
		}
		int totalCount = videoaddcount + graphiccount;
		if (totalCount > 0) {
		    sendNotify.sendMessage(totalCount, entity.getTaskname());
		}
		entity.setTaskstatus("处理完成");
		if (risk.equals("1")) {
			entity.setTaskstatus("可能触发风控本次已终止");
		}
		if (allDYData == null) {
			entity.setTaskstatus("执行失败(抓取异常)");
		}
		entity.setEndtime(DateUtils.formatDateTime(new Date()));
		collectdDataDao.save(entity);
		System.gc();
		logger.info("任务结束" + entity.getOriginaladdress());
		logger.info("[CollectTask] createDyData finish id={} addedVideo={} addedGraphic={} totalAdded={} successThisRun={} targetSuccess={} failedThisRun={} skippedThisRun={} finalStatus={} carriedout={}",
				entity.getId(), videoaddcount, graphiccount, totalCount, successThisRun, targetSuccess, failedThisRun, skippedThisRun, entity.getTaskstatus(), entity.getCarriedout());
	}

	private String buildFetchSnapshot(JSONArray allData) {
		JSONArray arr = new JSONArray();
		for (int i = 0; i < allData.size(); i++) {
			JSONObject src = allData.getJSONObject(i);
			JSONObject item = new JSONObject();
			item.put("index", i + 1);
			item.put("aweme_id", src.getString("aweme_id"));
			item.put("desc", src.getString("desc"));
			item.put("has_video_play_addr", src.getJSONArray("video_play_addr") != null && !src.getJSONArray("video_play_addr").isEmpty());
			arr.add(item);
		}
		String text = arr.toJSONString();
		if (text.length() > 200000) {
			return text.substring(0, 200000) + "...(truncated)";
		}
		return text;
	}

	public JSONArray getDYData(CollectDataEntity entity, String monitor) throws IOException {
		String taskout = Global.apppath + "lot" + System.getProperty("file.separator") + entity.getId() + "_"
				+ entity.getTaskname() + ".json";
		logger.info("[CollectTask] getDYData start id={} name={} originaladdress={} monitor={} tempFile={}",
				entity.getId(), entity.getTaskname(), entity.getOriginaladdress(), monitor, taskout);
		String sec_user_id = entity.getOriginaladdress().replaceAll("post", "").replaceAll("like", "");
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
			logger.info("[CollectTask] monitor mode targetNew={} expandedFetchWindow={} (successProcessed={} + target={}, allDetail={})",
					monitorWindow, maxc, successCountByDataid, monitorWindow, countByDataid);
		} else {
			maxc = null != entity.getOmaxcur() ? entity.getOmaxcur() : 80;
		}
		logger.info("[CollectTask] getDYData resolved maxc={} existingDetailCount={} (omaxcur={}, maxcur={})",
				maxc, countByDataid, entity.getOmaxcur(), entity.getMaxcur());

		if (entity.getOriginaladdress().startsWith("post")) {
			logger.info("[CollectTask] getDYData mode=post uid={} maxc={} out={}", sec_user_id, maxc, taskout);
			String f2cmd = CommandUtil.f2cmd(Global.tiktokCookie, null, "fetch_user_post_videos", sec_user_id, null,
					maxc, taskout);
			logF2Result("post", f2cmd, taskout);
			if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
				JSONArray jsonFromFile = FileUtil.readJsonFromFile(taskout);
				logger.info("[CollectTask] getDYData parsed count={} mode=post", jsonFromFile == null ? 0 : jsonFromFile.size());
				Files.deleteIfExists(Paths.get(taskout));
				return jsonFromFile;
			}
		}
		if (entity.getOriginaladdress().startsWith("like")) {
			logger.info("[CollectTask] getDYData mode=like uid={} maxc={} out={}", sec_user_id, maxc, taskout);
			String f2cmd = CommandUtil.f2cmd(Global.tiktokCookie, null, "fetch_user_like_videos", sec_user_id, null,
					maxc, taskout);
			logF2Result("like", f2cmd, taskout);
			if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
				JSONArray jsonFromFile = FileUtil.readJsonFromFile(taskout);
				logger.info("[CollectTask] getDYData parsed count={} mode=like", jsonFromFile == null ? 0 : jsonFromFile.size());
				Files.deleteIfExists(Paths.get(taskout));
				return jsonFromFile;
			}
		}
		if (entity.getOriginaladdress().startsWith("fav-")) {
			String startTag = "fav-";
			String endTag = "-fav";
			int startIndex = entity.getOriginaladdress().indexOf(startTag) + startTag.length();
			int endIndex = entity.getOriginaladdress().indexOf(endTag);
			String content = entity.getOriginaladdress().substring(startIndex, endIndex).trim();
			sec_user_id = sec_user_id.replaceAll(startTag + content + endTag, "");
			logger.info("[CollectTask] getDYData mode=fav cid={} maxc={} out={}", content, maxc, taskout);
			String f2cmd = CommandUtil.f2cmd(Global.tiktokCookie, null, "fetch_user_collects_videos", null, content,
					maxc, taskout);
			logF2Result("fav", f2cmd, taskout);
			if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
				JSONArray jsonFromFile = FileUtil.readJsonFromFile(taskout);
				logger.info("[CollectTask] getDYData parsed count={} mode=fav", jsonFromFile == null ? 0 : jsonFromFile.size());
				Files.deleteIfExists(Paths.get(taskout));
				return jsonFromFile;
			}
		}
		if (entity.getOriginaladdress().startsWith("recommend")) {
			sec_user_id = entity.getOriginaladdress().replaceAll("recommend", "");
			logger.info("[CollectTask] getDYData mode=recommend uid={} out={}", sec_user_id, taskout);
			String f2cmd = CommandUtil.f2cmd(Global.tiktokCookie, null, "fetch_user_feed_videos", sec_user_id, null,
					maxc, taskout);
			logF2Result("recommend", f2cmd, taskout);
			if (null != f2cmd && f2cmd.contains("stream-vault-ok")) {
				JSONArray jsonFromFile = FileUtil.readJsonFromFile(taskout);
				logger.info("[CollectTask] getDYData parsed count={} mode=recommend", jsonFromFile == null ? 0 : jsonFromFile.size());
				Files.deleteIfExists(Paths.get(taskout));
				return jsonFromFile;
			}
		}
		// 删除文件
		logger.error("[CollectTask] getDYData returning null id={} originaladdress={}", entity.getId(), entity.getOriginaladdress());
		return null;
	}

	private void logF2Result(String mode, String f2cmd, String taskout) {
		boolean success = f2cmd != null && f2cmd.contains("stream-vault-ok");
		Integer exitCode = CommandUtil.getLastF2ExitCode();
		Long durationMs = CommandUtil.getLastF2DurationMs();
		logger.info("[CollectTask] getDYData f2 outputLength={} containsSuccessMarker={} exitCode={} durationMs={}",
				f2cmd == null ? 0 : f2cmd.length(), success, exitCode, durationMs);
		if (!success) {
			logger.error("[CollectTask] getDYData f2 failed mode={} outputPreview={}", mode, previewOutput(f2cmd));
			logger.error("[CollectTask] getDYData f2 failed mode={} outPath={} outFileExists={} outFileSize={}",
					mode, taskout, Files.exists(Paths.get(taskout)),
					Files.exists(Paths.get(taskout)) ? safeFileSize(taskout) : -1);
		} else {
			logger.info("[CollectTask] getDYData output file exists={} path={}", Files.exists(Paths.get(taskout)), taskout);
		}
	}

	private long safeFileSize(String path) {
		try {
			return Files.size(Paths.get(path));
		} catch (Exception e) {
			return -1;
		}
	}

	private String previewOutput(String output) {
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
		String httpget = DouUtil.httpget(apiaddt, Global.tiktokCookie);
		JSONObject parseObject = JSONObject.parseObject(httpget);
		JSONArray jsonArray = parseObject.getJSONArray("aweme_list");
		max_cursor = parseObject.getString("max_cursor");
		if (!max_cursor.equals("0")) {
			data.addAll(jsonArray);
			Thread.sleep(2500);
			return this.getDYNextData(api, data, max_cursor, sign);
		} else {
			data.addAll(jsonArray);
			return data;
		}
	}

	public AjaxEntity loadDouFav(String uid) {
		String f2cmd = CommandUtil.f2cmd(Global.tiktokCookie, null, "fetch_user_collects", uid, null, null, null);
		String startTag = "stream-vault-start-collects";
		String endTag = "stream-vault-end-collects";
		int startIndex = f2cmd.indexOf(startTag) + startTag.length();
		int endIndex = f2cmd.indexOf(endTag);
		String content = f2cmd.substring(startIndex, endIndex).trim();
		return new AjaxEntity(Global.ajax_success, content, "请求成功");
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


	public AjaxEntity execCollectData(CollectDataEntity collectDataEntity){
		if (collectDataEntity == null || collectDataEntity.getId() == null) {
			return new AjaxEntity(Global.ajax_uri_error, "任务ID不能为空", null);
		}
		Optional<CollectDataEntity> current = collectdDataDao.findById(collectDataEntity.getId());
		if (current.isPresent() && "N".equalsIgnoreCase(current.get().getTaskenabled())) {
			return new AjaxEntity(Global.ajax_uri_error, "任务已暂停，请先开始任务", null);
		}
		//先判断 任务存不存在
		boolean taskExists = quartzTaskService.isTaskExists(collectDataEntity.getId());
		if(taskExists) {
			//判断是否在运行
			boolean taskRunning = quartzTaskService.isTaskRunning(collectDataEntity.getId());
			if(!taskRunning) {
				quartzTaskService.triggerTask(collectDataEntity.getId());
			}else {
				return new AjaxEntity(Global.ajax_success, "当前任务已在运行,请勿重复提交", null);
			}
			
		}else {
			//不存在 需要先查询 然后注册  在触发
			Optional<CollectDataEntity> byId = collectdDataDao.findById(collectDataEntity.getId());
			if(byId.isPresent()) {
				CollectDataEntity db = byId.get();
				quartzTaskService.scheduleTask(db);
				try {Thread.sleep(2000);} catch (InterruptedException e) {}
				quartzTaskService.triggerTask(db.getId());
			}
		}
		return new AjaxEntity(Global.ajax_success, "任务启动成功", null);
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
}
