package com.flower.spirit.utils;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import jakarta.annotation.PostConstruct;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.danmu.bilibili.DanmakuSeg.DanmakuElem;
import com.flower.spirit.danmu.bilibili.DanmakuSeg.DmSegMobileReply;
import com.flower.spirit.danmu.bilibili.DanmuToAssConverter;
import com.flower.spirit.dao.FfmpegQueueDao;
import com.flower.spirit.dao.FfmpegQueueDataDao;
import com.flower.spirit.entity.FfmpegQueueDataEntity;
import com.flower.spirit.entity.FfmpegQueueEntity;

@Component
public class BiliUtil {

	private static Logger logger = LoggerFactory.getLogger(BiliUtil.class);

	@Autowired
	private FfmpegQueueDataDao ffmpegQueueDataDao;

	@Autowired
	private FfmpegQueueDao ffmpegQueueDao;
	

	private static BiliUtil biliUtil;
	
	
	private static final BigInteger XOR_CODE = new BigInteger("23442827791579");
	private static final BigInteger MASK_CODE = new BigInteger("2251799813685247");
	private static final BigInteger MAX_AID = BigInteger.ONE.shiftLeft(51);
	private static final BigInteger BASE = new BigInteger("58");
	private static final String DATA_STRING = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";


	@PostConstruct
	public void init() {
		biliUtil = this;
		biliUtil.ffmpegQueueDataDao = this.ffmpegQueueDataDao;
		biliUtil.ffmpegQueueDao = this.ffmpegQueueDao;
	}

	/**
	 * 
	 * 用于收藏夹下载
	 * 
	 * 2023/09/11 移除参数url 无用参数
	 * 2023/09/11 移除参数filepath 优化路径生成
	 * 
	 * @throws Exception
	 * 
	 */
	public static Map<String, String> findVideoStreamingNoData(Map<String, String> videoDataInfo, String token,
			String quality, String namepath) throws Exception {
		String api = buildInterfaceWbiAddress(videoDataInfo.get("aid"), videoDataInfo.get("cid"), token, quality);
		String httpGetBili = HttpUtil.httpGetBili(api, "UTF-8", token);
		JSONObject parseObject = JSONObject.parseObject(httpGetBili);
	    int code = parseObject.getIntValue("code");
	    if (code == -404) {
	        return null; 
	    }
		String filename;
		{
			String biliAuthor = null;
			String biliCtime = videoDataInfo.get("ctime");
			try {
				JSONObject ownerObj = JSONObject.parseObject(videoDataInfo.get("owner"));
				if (ownerObj != null) {
					biliAuthor = ownerObj.getString("name");
				}
			} catch (Exception e) {}
			filename = FileNameTemplateUtil.resolveFileName(videoDataInfo.get("title"), videoDataInfo.get("cid"), biliAuthor, biliCtime, "哔哩");
		}
		if ((Integer.valueOf(Global.bilibitstream) >= 80 && quality.equals("1"))
				|| parseObject.getJSONObject("data").containsKey("dash")) {
			Map<String, String> processing = processing(parseObject, videoDataInfo, filename, namepath);
			return processing;
		}
//		String video = parseObject.getJSONObject("data").getJSONArray("durl").getJSONObject(0).getString("url");
		List<String> choiceMediaAddr = choiceMediaAddr(parseObject.getJSONObject("data").getJSONArray("durl"), 0, false, Global.cdnsort);
		if (Global.downtype.equals("http")) {
			HttpUtil.downBiliFromUrl(choiceMediaAddr, filename + ".mp4",
					FileUtil.generateDir(true, Global.platform.bilibili.name(), false, filename, namepath, null));
		}
		if (Global.downtype.equals("a2")) {

			Aria2Util.sendMessage(Global.a2_link,
					Aria2Util.createBiliparameter(
							choiceMediaAddr.get(0),
							FileUtil.generateDir(Global.down_path, Global.platform.bilibili.name(), false, filename,
									namepath, null),
							filename + ".mp4",
							Global.a2_token));
		}
		String videodir = FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, namepath, "mp4");
		if(namepath!= null) {
			videodir = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, filename, namepath, "mp4");
		}
		videoDataInfo.put("video",videodir);
		videoDataInfo.put("videoname", filename + ".mp4");
		videoDataInfo.put("filename", filename);
		// System.out.println(videoDataInfo);
		return videoDataInfo;
	}

	/**
	 * 解析视频信息并下载视频源文件
	 * 
	 * @param url   视频URL
	 * @param token 用户token
	 * @return 视频信息列表
	 * @throws Exception 处理过程中的异常
	 */
	public static List<Map<String, String>> findVideoStreaming(String url, String token) throws Exception {
		List<Map<String, String>> result = new ArrayList<>();

		// 获取视频基本信息
		List<Map<String, String>> videoInfoList = getVideoDataInfo(url);
		if (videoInfoList == null || videoInfoList.isEmpty()) {
			logger.warn("未找到视频信息: {}", url);
			return result;
		}

		logger.info("获取到{}个视频数据", videoInfoList.size());

		for (Map<String, String> videoInfo : videoInfoList) {
			try {
				// 获取必要参数
				String aid = videoInfo.get("aid");
				String cid = videoInfo.get("cid");
				String title = videoInfo.get("title");
				String quality = videoInfo.get("quality");
				String duration = videoInfo.get("duration");
				String width = videoInfo.get("width");
				String height = videoInfo.get("height");
				if (aid == null || cid == null || title == null) {
					logger.warn("视频数据不完整: aid={}, cid={}, title={}", aid, cid, title);
					continue;
				}

				// 生成文件名
				String biliAuthor = null;
				String biliCtime = videoInfo.get("ctime");
				try {
					JSONObject ownerObj = JSONObject.parseObject(videoInfo.get("owner"));
					if (ownerObj != null) {
						biliAuthor = ownerObj.getString("name");
					}
				} catch (Exception e) {
					// owner可能为空
				}
				String filename = FileNameTemplateUtil.resolveFileName(title, cid, biliAuthor, biliCtime, "哔哩");

				// 构建API请求地址
//				String apiUrl = buildInterfaceAddress(aid, cid, token, quality);
				String apiUrl = buildInterfaceWbiAddress(aid, cid, token, quality);

				// 请求视频源信息
				String response = HttpUtil.httpGetBili(apiUrl, "UTF-8", token);
				JSONObject videoData = JSONObject.parseObject(response);

				// 根据视频流类型选择处理方法
				Map<String, String> processedVideo;

				// 判断是否为DASH格式视频
				boolean isDashFormat = (Integer.valueOf(Global.bilibitstream) >= 80 && quality.equals("1")) ||
						videoData.getJSONObject("data").containsKey("dash");

				if (isDashFormat) {
					// 处理DASH格式视频
					processedVideo = processDashVideo(videoData, videoInfo, filename, null);
				} else {
					// 处理普通格式视频
					processedVideo = processNormalVideo(videoData, videoInfo, filename);
				}
				processedVideo.put("duration", duration);
				processedVideo.put("width", width);
				processedVideo.put("height", height);
				result.add(processedVideo);
				// 如果是DASH格式，通常只有一个视频
				if (isDashFormat) {
					return result;
				}
			} catch (Exception e) {
				logger.error("处理视频{}时出错: {}", videoInfo.get("title"), e.getMessage());
				throw e;
			}
		}

		return result;
	}

	/**
	 * 处理DASH格式视频
	 * 
	 * @param videoData 视频JSON数据
	 * @param videoInfo 视频基本信息
	 * @param filename  文件名
	 * @return 处理后的视频信息
	 * @throws Exception 处理过程中的异常
	 */
	private static Map<String, String> processDashVideo(JSONObject videoData, Map<String, String> videoInfo,
			String filename, String fav) throws Exception {
		return processing(videoData, videoInfo, filename, fav);
	}

	/**
	 * 处理普通格式视频
	 * 
	 * @param videoData 视频JSON数据
	 * @param videoInfo 视频基本信息
	 * @param filename  文件名
	 * @return 处理后的视频信息
	 * @throws Exception 处理过程中的异常
	 */
	private static Map<String, String> processNormalVideo(JSONObject videoData, Map<String, String> videoInfo,
			String filename) throws Exception {
		// 获取视频直链
//		String videoUrl = videoData.getJSONObject("data").getJSONArray("durl").getJSONObject(0).getString("url");
		List<String> videoUrl = choiceMediaAddr(videoData.getJSONObject("data").getJSONArray("durl"), 0, false, true);
		// 根据下载类型选择下载方式
		if (Global.downtype.equals("http")) {
			// 使用HTTP直接下载
			String targetDir = FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, null, null);
			HttpUtil.downBiliFromUrl(videoUrl, filename + ".mp4", targetDir);
		} else if (Global.downtype.equals("a2")) {
			// 使用Aria2下载
			String targetDir = FileUtil.generateDir(Global.down_path, Global.platform.bilibili.name(), true, filename,
					null, null);
			Aria2Util.sendMessage(
					Global.a2_link,
					Aria2Util.createBiliparameter(videoUrl.get(0), targetDir, filename + ".mp4", Global.a2_token));
		}

		// 更新视频信息
		Map<String, String> result = new HashMap<>(videoInfo);
		result.put("video", FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, null, "mp4"));
		result.put("videoname", filename + ".mp4");
		result.put("filename", filename);

		return result;
	}

	/**
	 * 处理DASH格式视频，下载和合并音视频
	 * 
	 * @param videoData JSON视频数据
	 * @param videoInfo 视频信息
	 * @param filename  文件名
	 * @return 更新后的视频信息
	 * @throws Exception 处理过程中可能的异常
	 */
	private static Map<String, String> processing(JSONObject videoData, Map<String, String> videoInfo, String filename,
			String fav)
			throws Exception {
		// 获取音视频流URL
		JSONObject dashData = videoData.getJSONObject("data").getJSONObject("dash");
//		String videoUrl = dashData.getJSONArray("video").getJSONObject(0).getString("base_url");
//		String audioUrl = dashData.getJSONArray("audio").getJSONObject(0).getString("base_url");
		List<String> videoList = choiceMediaAddr(dashData.getJSONArray("video"), 0, true, Global.cdnsort); 
		List<String> audioUrl = choiceMediaAddr(dashData.getJSONArray("audio"), 0, true, Global.cdnsort); 
		// 根据下载类型处理
		if (Global.downtype.equals("http")) {
			processHttpDownload(videoList, audioUrl, filename, fav);
		} else if (Global.downtype.equals("a2")) {
			processAria2Download(videoList.get(0), audioUrl.get(0), videoInfo, filename, fav);
		}

		// 更新视频信息
		Map<String, String> result = new HashMap<>(videoInfo);
		String videodir = FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, null, "mp4");
		if(fav != null) {
			videodir = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, filename, fav, "mp4");
		}
		result.put("video",videodir);
		result.put("videoname", filename + ".mp4");
		result.put("filename", filename);

		return result;
	}

	/**
	 * 使用HTTP方式下载并处理DASH格式视频
	 */
	private static void processHttpDownload(List<String> videoUrl, List<String> audioUrl, String filename, String fav)
			throws Exception {
		// 创建临时目录
		String tempDir = FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, null, null);
		String outputPath = FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, null, "mp4");

		if (fav != null) {
			tempDir = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, filename, fav, null);
			outputPath = FileUtil.generateDir(true, Global.platform.bilibili.name(), false, filename, fav, "mp4");
		}

		// 确保目录存在
		FileUtils.createDirectory(tempDir);

		// 下载音视频文件
		String videoFile = tempDir + File.separator + filename + "-video.m4s";
		String audioFile = tempDir + File.separator + filename + "-audio.m4s";
		// System.err.println(videoFile);
		// System.err.println(audioFile);
		HttpUtil.downBiliFromUrl(videoUrl, filename + "-video.m4s", tempDir);
		HttpUtil.downBiliFromUrl(audioUrl, filename + "-audio.m4s", tempDir);

		// 合并音视频文件
		String ffmpegCmd = String.format("ffmpeg -y -i %s -i %s -c:v copy -c:a copy -f mp4 %s",
				videoFile, audioFile, outputPath);
		// System.out.println(ffmpegCmd);
		CommandUtil.command(ffmpegCmd);

		// 清理临时文件
		System.gc();
		FileUtils.deleteFile(videoFile);
		FileUtils.deleteFile(audioFile);
	}

	/**
	 * 使用Aria2方式下载DASH格式视频
	 */
	private static void processAria2Download(String videoUrl, String audioUrl, Map<String, String> videoInfo,
			String filename, String fav) throws Exception {
		// 创建下载目录
		String downloadDir = FileUtil.generateDir(Global.down_path, Global.platform.bilibili.name(), true, filename,
				null, null);
		if (fav != null) {
			downloadDir = FileUtil.generateDir(Global.down_path, Global.platform.bilibili.name(), false, filename, null,
					fav);
		}
		// 发送下载任务
		String videores = Aria2Util.sendMessage(Global.a2_link,
				Aria2Util.createBiliparameter(videoUrl, downloadDir, filename + "-video.m4s", Global.a2_token));

		String audiores = Aria2Util.sendMessage(Global.a2_link,
				Aria2Util.createBiliparameter(audioUrl, downloadDir, filename + "-audio.m4s", Global.a2_token));

		// 创建合并任务
		createFfmpegMergeTask(videoInfo, filename, videores, audiores);
	}

	/**
	 * 创建FFmpeg合并任务
	 */
	private static void createFfmpegMergeTask(Map<String, String> videoInfo, String filename, String videores,
			String audiores) {
		logger.info("高清视频使用DASH格式，提交到FFmpeg队列等待下载完成后合并");

		// 临时目录和输出路径
		String tempDir = FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, null, null);
		String outputPath = FileUtil.generateDir(true, Global.platform.bilibili.name(), true, filename, null, "mp4");
		// 保存队列信息
		FfmpegQueueEntity ffmpegQueue = new FfmpegQueueEntity();
		ffmpegQueue.setVideoid(videoInfo.get("cid"));
		ffmpegQueue.setVideoname(videoInfo.get("title"));
		ffmpegQueue.setPendingfolder(tempDir);
		ffmpegQueue.setAudiostatus("0");
		ffmpegQueue.setVideostatus("0");
		ffmpegQueue.setFilepath(outputPath);
		ffmpegQueue.setStatus("0");
		ffmpegQueue.setCreatetime(DateUtils.getDateTime());
		biliUtil.ffmpegQueueDao.save(ffmpegQueue);

		// 保存视频下载任务
		saveQueueData(ffmpegQueue.getId(), JSONObject.parseObject(videores).getString("result"),
				"v", tempDir + "/" + filename + "-video.m4s");

		// 保存音频下载任务
		saveQueueData(ffmpegQueue.getId(), JSONObject.parseObject(audiores).getString("result"),
				"a", tempDir + "/" + filename + "-audio.m4s");
	}

	/**
	 * 保存队列数据
	 */
	private static void saveQueueData(Integer queueId, String taskId, String fileType, String filePath) {
		FfmpegQueueDataEntity queueData = new FfmpegQueueDataEntity();
		queueData.setQueueid(queueId);
		queueData.setTaskid(taskId);
		queueData.setFiletype(fileType);
		queueData.setStatus("0");
		queueData.setFilepath(filePath);
		queueData.setCreatetime(DateUtils.getDateTime());
		biliUtil.ffmpegQueueDataDao.save(queueData);
	}

	/**
	 * 方法需要代码优化 有时间再说
	 * 
	 * @param url
	 * @return
	 */
	public static List<Map<String, String>> getVideoDataInfo(String url) {
		List<Map<String, String>> res = new ArrayList<Map<String, String>>();
		String parseEntry = BiliUtil.parseEntry(url);
		String api = "";
		if (parseEntry.contains("BV")) {
			api = "https://api.bilibili.com/x/web-interface/view?bvid=" + parseEntry.substring(2, parseEntry.length());
		}
		if (parseEntry.contains("av")) {
			api = "https://api.bilibili.com/x/web-interface/view?aid=" + parseEntry.substring(2, parseEntry.length());
		}
		String serchPersion = HttpUtil.getSerchPersion(api, "UTF-8");
		JSONObject videoData = JSONObject.parseObject(serchPersion);
		if (videoData.getString("code").equals("0")) {
			// 优化多集问题 从page 里取

			String bvid = videoData.getJSONObject("data").getString("bvid");
			String aid = videoData.getJSONObject("data").getString("aid");
			String desc = videoData.getJSONObject("data").getString("desc");
			JSONObject dimension = videoData.getJSONObject("data").getJSONObject("dimension");
			Integer width = dimension.getInteger("width");
			Integer height = dimension.getInteger("height");
			JSONArray jsonArray = videoData.getJSONObject("data").getJSONArray("pages");
			for (int i = 0; i < jsonArray.size(); i++) {
				Map<String, String> data = new HashMap<String, String>();
				JSONObject jsonObject = jsonArray.getJSONObject(i);
				String cid = jsonObject.getString("cid");
				String title = chooseVideoTitle(
					    videoData.getJSONObject("data") != null ? videoData.getJSONObject("data").getString("title") : "",
					    jsonObject.getString("part") != null ? jsonObject.getString("part") : ""
				);
				String pic = videoData.getJSONObject("data").getString("pic");
				data.put("aid", aid);
				data.put("bvid", bvid);
				data.put("desc", desc);
				if (width >= 1920 || height >= 1080) {
					data.put("quality", "1");
				} else {
					data.put("quality", "0");
				}
				if (null == pic) {
					pic = jsonObject.getString("first_frame");
				}
				String duration = jsonObject.getString("duration");
				data.put("cid", cid);
				data.put("title", title);
				data.put("pic", pic);
				data.put("duration", duration);
				data.put("width", Integer.toString(width));
				data.put("height", Integer.toString(height));
				data.put("owner", videoData.getJSONObject("data").getString("owner"));
				data.put("ctime", videoData.getJSONObject("data").getString("ctime"));
				res.add(data);
			}
			return res;
		} else {
			return null;
		}
	}

	public static String parseEntry(String url) {
		if (url.contains("/video/av") || url.contains("/video/BV")) {
			return BiliUtil.findUrlAidOrBid(url);
		} else {
			Document document = null;
			try {
				document = Jsoup.connect(url).userAgent(
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36")
						.get();
				String baseUri = document.baseUri();
				return BiliUtil.findUrlAidOrBid(baseUri);
			} catch (IOException e1) {

			}
		}
		return "";
	}

	public static String findUrlAidOrBid(String url) {
		String replace = "";
		Pattern pattern = Pattern.compile("/video/(BV\\w+|av\\d+)", Pattern.CASE_INSENSITIVE);
		Matcher matcher = pattern.matcher(url);
		if (matcher.find()) {
			String videoId = matcher.group(1);
			return videoId;
		}
		// 下边是旧代码 后续移除 理论无效
		if (url.contains("http")) {
			replace = url.replaceAll("http://", "").replaceAll("https://", "").replace("www.bilibili.com/video/", "");
			int indexOf = replace.indexOf("/");
			String id = replace.substring(0, indexOf);
			return id;
		} else {
			replace = url.replaceAll("/video/", "");
			return replace;
		}
	}
	
	
	public static String buildInterfaceWbiAddress(String aid, String cid, String token, String quality) {
		String qn;
		String fnver;
		String fourk;
		String fnval;
		String bilibitstream = Global.bilibitstream;
		if (quality.equals("0")) {
			logger.info("视频没有2k以上进行画质降级");
			bilibitstream = "80";
		}
		if (null != token && !token.equals("")) {
			if (!bilibitstream.equals("64")) {
				// vip
				if (Integer.valueOf(bilibitstream) >= 120) {
					qn ="0";
				} else {
					qn=bilibitstream;
				}
			} else {
				qn ="80";
			}
		} else {
			qn ="64";
		}
		fnver ="0";
		switch (bilibitstream) {
			case "80":
				fourk ="1";
				fnval = Integer.toString(16 | 128);
				break;
			case "112":
				fourk ="1";
				fnval = Integer.toString(16 | 128);
				break;
			case "116":
				fourk ="1";
				fnval = Integer.toString(16 | 128);
				break;
			case "120":
				fourk ="1";
				fnval = Integer.toString(16 | 128);
				break;
			case "125":
				fourk ="1";
				fnval = Integer.toString(16 | 64);
				break;
			case "126":
				fourk ="1";
				fnval = Integer.toString(16 | 512);
				break;
			case "127":
				fourk ="1";
				fnval = Integer.toString(16 | 1024);
				break;
			default:
				fourk ="0";
				fnval = "1";
				break;
		}
		TreeMap<String, Object> params = new TreeMap<>();
		params.put("avid", aid);
		params.put("cid", cid);
		params.put("qn", qn);
		params.put("fnver", fnver);
		params.put("fourk", fourk);
		params.put("fnval", fnval);
		String wbiUrl = WbiUtil.buildWbiUrl(params);
		return  "https://api.bilibili.com/x/player/wbi/playurl?" + wbiUrl;
	}
	

	public static String buildInterfaceAddress(String aid, String cid, String token, String quality) {
		String bilibitstream = Global.bilibitstream;
		if (quality.equals("0")) {
			logger.info("视频没有2k以上进行画质降级");
			bilibitstream = "80"; // 画质降级
		}
		String api = "https://api.bilibili.com/x/player/playurl?avid=" + aid + "&cid=" + cid;
		if (null != token && !token.equals("")) {
			if (!bilibitstream.equals("64")) {
				// vip
				if (Integer.valueOf(bilibitstream) >= 120) {
					api = api + "&qn=0";
				} else {
					api = api + "&qn=" + bilibitstream;
				}

			} else {
				api = api + "&qn=80";
			}

		} else {
			api = api + "&qn=64";
		}
		api = api + "&fnver=0"; // 固定 0
		switch (bilibitstream) {
			case "80":
				api = api + "&fourk=1&fnval=" + Integer.toString(16 | 128); // 4k 传128
				break;
			case "112":
				api = api + "&fourk=1&fnval=" + Integer.toString(16 | 128); // 4k 传128
				break;
			case "116":
				api = api + "&fourk=1&fnval=" + Integer.toString(16 | 128); // 4k 传128
				break;
			case "120":
				api = api + "&fourk=1&fnval=" + Integer.toString(16 | 128); // 4k 传128
				break;
			case "125":
				api = api + "&fourk=1&fnval=" + Integer.toString(16 | 64); // hdr 传64
				break;
			case "126":
				api = api + "&fourk=1&fnval=" + Integer.toString(16 | 512); // 杜比视界 传128
				break;
			case "127":
				api = api + "&fourk=1&fnval=" + Integer.toString(16 | 1024); // 8k 传128
				break;
			default:
				api = api + "&fourk=0&fnval=1";
				break;
		}
		return api;
	}

	/**
	 * 获取用户投稿视频
	 * 
	 * @param mid    用户ID
	 * @param maxcur 限制数量
	 * @return 视频列表
	 */
	public static JSONArray getSeasonsArchives(String mid,String seaarcid,Integer maxcur) {
		List<JSONObject> videos = new ArrayList<>();
		getSeasonsArchives(mid,seaarcid,"1" ,maxcur, videos);
		JSONArray array = new JSONArray();
		array.addAll(videos);
		return array;
	}
	
	/**
	 * 获取用户投稿视频
	 * 
	 * @param mid    用户ID
	 * @param maxcur 限制数量
	 * @return 视频列表
	 */
	public static JSONArray getVideos(String mid, Integer maxcur) {
		List<JSONObject> videos = new ArrayList<>();
		getVideosRecursive(mid, "1", maxcur, videos);
		JSONArray array = new JSONArray();
		array.addAll(videos);
		return array;
	}
	
	
	/**
	 * 递归获取视频的具体实现
	 */
	private static void getSeasonsArchives(String mid,String seaarcid ,String pn, Integer maxcur, List<JSONObject> videos) {
		String apiUrl = "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?mid="+mid+"&season_id="+seaarcid+"&sort_reverse=false&page_num="+pn+"&page_size=30";
		try {
			String response = HttpUtil.httpGetBili(apiUrl, Global.bilicookies, "https://space.bilibili.com",
					"https://space.bilibili.com/" + mid);
//			System.out.println(apiUrl);
			JSONObject json = JSONObject.parseObject(response);
//			System.out.println(json);
			if (json.getInteger("code") == 0) {
				JSONObject data = json.getJSONObject("data");
				JSONArray list = data.getJSONArray("archives");
//				JSONArray vlist = list.getJSONArray("vlist");
				if (list.size() == 0) {
					return;
				}
				JSONObject meta = data.getJSONObject("meta");
				String cover = meta.getString("cover");
				String description = meta.getString("description");
				String name = meta.getString("name");
				for (int i = 0; i < list.size(); i++) {
					JSONObject video = list.getJSONObject(i);
					if (videos.isEmpty()) {
					    video.put("cover", cover);
					    video.put("description", description);
					    video.put("name", name);
					}
					videos.add(video);
					if (maxcur != null && videos.size() >= maxcur) {
						return;
					}
				}
				if (maxcur == null || videos.size() < maxcur) {
					Thread.sleep(5000); 
					int page = Integer.parseInt(pn);
					int count = data.getJSONObject("page").getInteger("total");
					int ps = data.getJSONObject("page").getInteger("page_size");
					int totalPages = (count + ps - 1) / ps;
					if (page < totalPages) {
						getVideosRecursive(mid, String.valueOf(page + 1), maxcur, videos);
					}
				}
				
			}
		} catch (Exception e) {
			logger.error("获取视频列表失败: {}", e.getMessage());
		}
	}

	/**
	 * 递归获取视频的具体实现
	 */
	private static void getVideosRecursive(String mid, String pn, Integer maxcur, List<JSONObject> videos) {
		TreeMap<String, Object> params = new TreeMap<>();
		params.put("mid", mid);
		params.put("ps", "30");
		params.put("pn", pn);
		params.put("order", "pubdate");

		String wbiUrl = WbiUtil.buildWbiUrl(params);
		if (wbiUrl == null) {
			return;
		}

		String apiUrl = "https://api.bilibili.com/x/space/wbi/arc/search?" + wbiUrl;
		try {
			String response = HttpUtil.httpGetBili(apiUrl, Global.bilicookies, "https://space.bilibili.com",
					"https://space.bilibili.com/" + mid);
			JSONObject json = JSONObject.parseObject(response);
			if (json.getInteger("code") == 0) {
				JSONObject data = json.getJSONObject("data");
				JSONObject list = data.getJSONObject("list");
				JSONArray vlist = list.getJSONArray("vlist");

				if (vlist.size() == 0) {
					return;
				}

				// 添加视频
				for (int i = 0; i < vlist.size(); i++) {
					JSONObject video = vlist.getJSONObject(i);
					videos.add(video);

					// 如果设置了maxcur且已达到限制,则停止获取
					if (maxcur != null && videos.size() >= maxcur) {
						return;
					}
				}

				// 检查是否需要获取下一页
				if (maxcur == null || videos.size() < maxcur) {
					Thread.sleep(5000); // 睡一会
					int page = Integer.parseInt(pn);
					int count = data.getJSONObject("page").getInteger("count");
					int ps = data.getJSONObject("page").getInteger("ps");
					int totalPages = (count + ps - 1) / ps;

					if (page < totalPages) {
						getVideosRecursive(mid, String.valueOf(page + 1), maxcur, videos);
					}
				}
			}
		} catch (Exception e) {
			logger.error("获取视频列表失败: {}", e.getMessage());
		}
	}
	
	/** 合集列表类查询
	 * @param mid
	 * @param maxcur
	 * @return
	 */
	public static JSONArray SeasonsSearch(String mid,String seaarcid, Integer maxcur) {
		JSONArray videos = getSeasonsArchives(mid,seaarcid,maxcur);
		if (videos != null && !videos.isEmpty()) {
			String logMessage = maxcur == null ? String.format("获取到%d个视频", videos.size())
					: String.format("获取到%d个视频(限制:%s)", videos.size(), maxcur);
			logger.info(logMessage);
			return videos;
		}
		return null;
	}
	
	

	/** 投稿类查询
	 * @param mid
	 * @param maxcur
	 * @return
	 */
	public static JSONArray ArcSearch(String mid, Integer maxcur) {
		JSONArray videos = getVideos(mid, maxcur);
		if (videos != null && !videos.isEmpty()) {
			String logMessage = maxcur == null ? String.format("获取到%d个视频", videos.size())
					: String.format("获取到%d个视频(限制:%s)", videos.size(), maxcur);
			logger.info(logMessage);
			return videos;
		}
		return null;
	}
	
	
    /**
     * 传入两个标题 判断使用哪个标题
     * @param name1
     * @param name2
     * @return
     */
	public static String chooseVideoTitle(String name1, String name2) {
	    if ((name1 == null || name1.isEmpty()) && (name2 == null || name2.isEmpty())) {
	        return "";
	    }
	    if (isSystemGenerated(name1) && !isSystemGenerated(name2)) return name2;
	    if (isSystemGenerated(name2) && !isSystemGenerated(name1)) return name1;
	    boolean n1HasChinese = hasChinese(name1);
	    boolean n2HasChinese = hasChinese(name2);
	    if (n1HasChinese && !n2HasChinese) return name1;
	    if (n2HasChinese && !n1HasChinese) return name2;
	    if (n1HasChinese && n2HasChinese) {
	        int len1 = chineseLength(name1);
	        int len2 = chineseLength(name2);
	        if (len1 != len2) {
	            return len1 > len2 ? name1 : name2;
	        }
	    }
	    if (name2 != null && !name2.isEmpty()) return name2;

	    return name1 != null ? name1 : "";
	}

	/** 统计字符串里的中文字符数量 */
	private static int chineseLength(String str) {
	    if (str == null) return 0;
	    int count = 0;
	    for (char c : str.toCharArray()) {
	        if (String.valueOf(c).matches("[\\u4e00-\\u9fa5]")) {
	            count++;
	        }
	    }
	    return count;
	}

    
    /**
     * 判断标题是否为系统标题
     * @param name
     * @return
     */
    private static boolean isSystemGenerated(String name) {
        return name != null && name.matches("studio_video_\\d+");
    }
    /**
     * 判断是否包含中文
     * @param name
     * @return
     */
    private static boolean hasChinese(String name) {
        if (name == null) return false;
        return name.codePoints().anyMatch(
                c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
        );
    }
    /**
     * 
     * 下载弹幕
     * 
     * @param type
     * @param oid
     * @param aid
     * @param duration
     * @param filename
     * @param title
     */
    public static void biliDanmaku(String type, String oid, String aid, int duration,String filename,String title)  {
        int segmentLength = 360;
        int segmentCount = (int) Math.ceil(duration / (double) segmentLength);
        List<DanmakuElem> dm = new ArrayList<DanmakuElem>();
        for (int i = 1; i <= segmentCount; i++) {
            TreeMap<String, Object> params = new TreeMap<>();
            params.put("type", "1");           
            params.put("oid", oid);
            if(null != aid) {
            	params.put("pid", aid);
            }
            params.put("segment_index", String.valueOf(i));

            String wbiUrl = WbiUtil.buildWbiUrl(params);
            try {
            	String url = "https://api.bilibili.com/x/v2/dm/wbi/web/seg.so?" + wbiUrl;
            	byte[] result = HttpUtil.httpGetBiliBytes(url, Global.bilicookies, "https://www.bilibili.com", "https://www.bilibili.com/video/" + oid);
            	if (result != null) {
            		  DmSegMobileReply danmakuSeg = DmSegMobileReply.parseFrom(result);
            		  dm.addAll(danmakuSeg.getElemsList());
                }
               Thread.sleep(2222);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        File outputFile = new File(filename);

        try {
			DanmuToAssConverter.convertToAss(dm, outputFile, title);
		} catch (IOException e) {
		
			e.printStackTrace();
		}
        

     
    }
    
	/**
	 * 转换算法来自知乎@mcfx，其以WTFPL开源
	 * @param bv
	 * @return
	 */
	public static String bv2av(String bv) {
		char[] n = bv.toCharArray();
		char temp = n[3];
		n[3] = n[9];
		n[9] = temp;
		temp = n[4];
		n[4] = n[7];
		n[7] = temp;
		char[] newArray = new char[n.length - 3];
		System.arraycopy(n, 3, newArray, 0, n.length - 3);
		BigInteger result = BigInteger.ZERO;
		for (char c : newArray) {
			result = result.multiply(BASE).add(BigInteger.valueOf(DATA_STRING.indexOf(c)));
		}
		BigInteger avNumber = result.and(MASK_CODE).xor(XOR_CODE);
		return "av" + avNumber.toString();
	}

	/**
	 * 转换算法来自知乎@mcfx，其以WTFPL开源
	 * @param bv
	 * @return
	 */
	public static String av2bv(String av) {
		String avNumber = av.substring(2);
		BigInteger t = new BigInteger(avNumber);
		BigInteger r = (MAX_AID.or(t)).xor(XOR_CODE);
		StringBuilder sb = new StringBuilder("B");
		char[] n = new char[12];
		int o = n.length - 1;
		while (r.compareTo(BigInteger.ZERO) > 0) {
			n[o] = DATA_STRING.charAt(r.mod(BASE).intValue());
			r = r.divide(BASE);
			o--;
		}
		char temp = n[3];
		n[3] = n[9];
		n[9] = temp;
		temp = n[4];
		n[4] = n[7];
		n[7] = temp;
		for (char c : n) {
			sb.append(c);
		}
		return sb.toString();
	}
	
    public static String getCorrespondPath(String plaintext) {
    	try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            String publicKeyStr = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB";
            byte[] publicBytes = Base64.getDecoder().decode(publicKeyStr);
            X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(publicBytes);
            PublicKey publicKey = keyFactory.generatePublic(x509EncodedKeySpec);
            String algorithm = "RSA/ECB/OAEPPadding";
            Cipher cipher = Cipher.getInstance(algorithm);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] plaintextBytes = plaintext.getBytes("UTF-8");
            OAEPParameterSpec oaepParams = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams);
            byte[] encryptedBytes = cipher.doFinal(plaintextBytes);
            return new BigInteger(1, encryptedBytes).toString(16);
		} catch (Exception e) {
			return null;
		}
    }
    

    /**传入的是地址数组
     * @param data
     * @param mediatype
     * @param isdash
     * @param cdnsort
     * @return
     */
    public static List<String>  choiceMediaAddr(JSONArray data,int mediatype,boolean isdash,boolean cdnsort) {
		if (!cdnsort) {
			if (isdash) {
				JSONObject mediaItem = data.getJSONObject(0);
				return Arrays.asList(mediaItem.getString("base_url"));
			} else {
				JSONObject durlItem = data.getJSONObject(0);
				return Arrays.asList(durlItem.getString("url"));
			}
		}
		
		List<String> urls = new ArrayList<>();
		
		if (isdash) {
			for (int i = 0; i < data.size(); i++) {
				JSONObject mediaItem = data.getJSONObject(i);
				urls.add(mediaItem.getString("base_url"));
				if (mediaItem.containsKey("backup_url")) {
					JSONArray backupUrls = mediaItem.getJSONArray("backup_url");
					for (int j = 0; j < backupUrls.size(); j++) {
						urls.add(backupUrls.getString(j));
					}
				}
			}
		} else {
			for (int i = 0; i < data.size(); i++) {
				JSONObject durlItem = data.getJSONObject(i);
				urls.add(durlItem.getString("url"));
				if (durlItem.containsKey("backup_url")) {
					JSONArray backupUrls = durlItem.getJSONArray("backup_url");
					for (int j = 0; j < backupUrls.size(); j++) {
						urls.add(backupUrls.getString(j));
					}
				}
			}
		}
		
		urls.sort((u1, u2) -> {
			int priority1 = getCdnPriority(u1);
			int priority2 = getCdnPriority(u2);
			return Integer.compare(priority1, priority2);
		});
		
		return urls;
	}
	
	private static int getCdnPriority(String url) {
		if (url.contains("upos-")) {
			return 0;
		} else if (url.contains("cn-")) {
			return 1;
		} else if (url.contains("mcdn")) {
			return 2;
		} else {
			return 3;
		}
	}

}
