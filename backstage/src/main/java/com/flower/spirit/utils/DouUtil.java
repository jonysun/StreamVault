package com.flower.spirit.utils;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.HttpException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.executor.DouYinExecutor;

public class DouUtil {
	
	private static Logger logger = LoggerFactory.getLogger(DouUtil.class);
	
	//  备用
	
	private static String ttwid ="https://ttwid.bytedance.com/ttwid/union/register/";  //ttwid申请 
	
	//private static JSONObject ttwidData =  JSONObject.parseObject("{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":true}");  //需要配合ttwid 使用
	
	public static String ua="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0";
	public static String  odin_tt ="324fb4ea4a89c0c05827e18a1ed9cf9bf8a17f7705fcc793fec935b637867e2a5a9b8168c885554d029919117a18ba69";
	public static String  passport_csrf_token ="2f142a9bb5db1f81f249d6fc997fe4a1";
	public static String referer =  "https://www.douyin.com/";
	

	private static final Pattern VIDEO_ID_PATTERN_V1 = Pattern.compile("(?<=/video/)([^/?]+)");
	private static final Pattern VIDEO_ID_PATTERN_V2 = Pattern.compile("/video/(\\d+)(?=[/?]|$)");
	
	private static final Pattern NOTE_ID_PATTERN_V1 = Pattern.compile("(?<=/note/)([^/?]+)");
	private static final Pattern NOTE_ID_PATTERN_V2 = Pattern.compile("/note/(\\d+)(?=[/?]|$)");

	public static JSONObject fetchHybridVideoData(String rawUrl) {
		return fetchDouyinApi("/api/hybrid/video_data", "url", rawUrl);
	}

	public static JSONObject fetchUserProfile(String secUid) {
		return fetchDouyinApi("/api/douyin/web/handler_user_profile", "sec_user_id", secUid);
	}

	public static JSONObject fetchUserProfileByUniqueId(String uniqueId) {
		return fetchDouyinApi("/api/douyin/web/handler_user_profile", "unique_id", uniqueId);
	}

	public static JSONObject diagnoseUserProfile(String secUid) {
		JSONObject diagnostic = new JSONObject();
		diagnostic.put("path", "/api/douyin/web/handler_user_profile");
		diagnostic.put("lookupKey", "sec_user_id");
		diagnostic.put("lookupValue", secUid);
		diagnostic.put("configured", Global.douyinApiUrls != null && !Global.douyinApiUrls.trim().isEmpty());
		if (secUid == null || secUid.trim().isEmpty()) {
			diagnostic.put("success", false);
			diagnostic.put("error", "empty sec_uid");
			return diagnostic;
		}
		if (Global.douyinApiUrls == null || Global.douyinApiUrls.trim().isEmpty()) {
			diagnostic.put("success", false);
			diagnostic.put("error", "douyin api urls not configured");
			return diagnostic;
		}
		JSONArray attempts = new JSONArray();
		String[] bases = Global.douyinApiUrls.split("\\r?\\n");
		for (String base : bases) {
			if (base == null || base.trim().isEmpty()) continue;
			JSONObject attempt = new JSONObject();
			attempt.put("base", base.trim());
			try {
				String url = buildApiUrl(base.trim(), "/api/douyin/web/handler_user_profile", "sec_user_id", secUid);
				String response = HttpUtil.getPage(url, null, null);
				attempt.put("responsePresent", response != null && !response.trim().isEmpty());
				attempt.put("responseLength", response == null ? 0 : response.length());
				attempt.put("responsePreview", truncateForDiagnostic(response, 500));
				if (response == null || response.trim().isEmpty()) {
					attempt.put("success", false);
					attempt.put("error", "empty response");
					attempts.add(attempt);
					continue;
				}
				JSONObject object = JSONObject.parseObject(response);
				attempt.put("parseOk", object != null);
				if (object != null) {
					attempt.put("topLevelKeys", object.keySet().toString());
					copyIfPresent(object, attempt, "code");
					copyIfPresent(object, attempt, "status_code");
					copyIfPresent(object, attempt, "status_msg");
					copyIfPresent(object, attempt, "message");
					copyIfPresent(object, attempt, "msg");
					JSONObject user = extractDiagnosticUser(object);
					if (user != null) {
						attempt.put("userFound", true);
						copyIfPresent(user, attempt, "sec_uid");
						copyIfPresent(user, attempt, "uid");
						copyIfPresent(user, attempt, "unique_id");
						copyIfPresent(user, attempt, "nickname");
						attempt.put("success", true);
						attempts.add(attempt);
						diagnostic.put("success", true);
						diagnostic.put("attempts", attempts);
						return diagnostic;
					} else {
						attempt.put("userFound", false);
						attempt.put("success", false);
					}
				} else {
					attempt.put("success", false);
					attempt.put("error", "response is not a JSON object");
				}
			} catch (Exception e) {
				attempt.put("success", false);
				attempt.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
			}
			attempts.add(attempt);
		}
		diagnostic.put("success", false);
		diagnostic.put("attempts", attempts);
		return diagnostic;
	}

	public static JSONObject diagnoseHttpGet(String addr, String ck, int previewLength) {
		JSONObject diagnostic = new JSONObject();
		diagnostic.put("url", addr);
		diagnostic.put("cookiePresent", ck != null && !ck.trim().isEmpty());
		HttpURLConnection conn = null;
		try {
			URL url = new URL(addr);
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("user-agent", ua);
			conn.setRequestProperty("referer", "https://www.douyin.com/");
			if (ck != null) {
				conn.setRequestProperty("cookie", ck);
			}
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);
			int statusCode = conn.getResponseCode();
			diagnostic.put("success", statusCode >= 200 && statusCode < 400);
			diagnostic.put("statusCode", statusCode);
			diagnostic.put("contentType", conn.getContentType());
			InputStream stream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
			String response = readStream(stream);
			diagnostic.put("responsePresent", response != null && !response.trim().isEmpty());
			diagnostic.put("responseLength", response == null ? 0 : response.length());
			diagnostic.put("responsePreview", truncateForDiagnostic(response, previewLength));
			summarizeDiagnosticJson(response, diagnostic);
		} catch (Exception e) {
			diagnostic.put("success", false);
			diagnostic.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
		return diagnostic;
	}

	private static JSONObject fetchDouyinApi(String path, String key, String value) {
		if (value == null || value.trim().isEmpty() || Global.douyinApiUrls == null || Global.douyinApiUrls.trim().isEmpty()) {
			return null;
		}
		String[] bases = Global.douyinApiUrls.split("\\r?\\n");
		for (String base : bases) {
			if (base == null || base.trim().isEmpty()) continue;
			try {
				String url = buildApiUrl(base.trim(), path, key, value);
				String response = HttpUtil.getPage(url, null, null);
				if (response == null || response.trim().isEmpty()) continue;
				JSONObject object = JSONObject.parseObject(response);
				if (object != null) {
					logger.info("douyin api request success path={} base={} responseLength={}", path, base, response.length());
					return object;
				}
			} catch (Exception e) {
				logger.warn("douyin api request failed path={} base={}: {}", path, base, e.getMessage());
			}
		}
		return null;
	}

	private static JSONObject extractDiagnosticUser(JSONObject object) {
		if (object == null) {
			return null;
		}
		JSONObject user = object.getJSONObject("user");
		if (user != null) {
			return user;
		}
		JSONObject data = object.getJSONObject("data");
		if (data != null) {
			user = data.getJSONObject("user");
			if (user != null) {
				return user;
			}
			JSONObject userInfo = data.getJSONObject("user_info");
			if (userInfo != null) {
				return userInfo;
			}
		}
		JSONObject userInfo = object.getJSONObject("user_info");
		if (userInfo != null) {
			return userInfo;
		}
		return null;
	}

	private static void copyIfPresent(JSONObject source, JSONObject target, String key) {
		if (source != null && source.containsKey(key)) {
			target.put(key, source.get(key));
		}
	}

	private static void summarizeDiagnosticJson(String response, JSONObject diagnostic) {
		if (response == null || response.trim().isEmpty()) {
			return;
		}
		try {
			JSONObject object = JSONObject.parseObject(response);
			diagnostic.put("jsonParseOk", object != null);
			if (object == null) {
				return;
			}
			diagnostic.put("topLevelKeys", object.keySet().toString());
			copyIfPresent(object, diagnostic, "code");
			copyIfPresent(object, diagnostic, "status_code");
			copyIfPresent(object, diagnostic, "status_msg");
			copyIfPresent(object, diagnostic, "message");
			copyIfPresent(object, diagnostic, "msg");
			copyIfPresent(object, diagnostic, "max_cursor");
			copyIfPresent(object, diagnostic, "has_more");
			JSONArray awemeList = object.getJSONArray("aweme_list");
			if (awemeList != null) {
				diagnostic.put("awemeListSize", awemeList.size());
				if (!awemeList.isEmpty() && awemeList.getJSONObject(0) != null) {
					diagnostic.put("firstAwemeId", awemeList.getJSONObject(0).getString("aweme_id"));
					diagnostic.put("firstCreateTime", awemeList.getJSONObject(0).getString("create_time"));
				}
			}
		} catch (Exception e) {
			diagnostic.put("jsonParseOk", false);
			diagnostic.put("jsonParseError", e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	private static String readStream(InputStream stream) throws IOException {
		if (stream == null) {
			return null;
		}
		try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder response = new StringBuilder();
			String inputLine;
			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			return response.toString();
		}
	}

	private static String truncateForDiagnostic(String text, int maxLength) {
		if (text == null) {
			return null;
		}
		String normalized = text.replace("\r", "\\r").replace("\n", "\\n");
		return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
	}

	private static String buildApiUrl(String base, String path, String key, String value) {
		String url = base;
		if (url.contains("{url}")) {
			return url.replace("{url}", encode(value));
		}
		if (url.contains("{sec_uid}")) {
			return url.replace("{sec_uid}", encode(value));
		}
		if (url.contains("{unique_id}")) {
			return url.replace("{unique_id}", encode(value));
		}
		if (!url.contains(path)) {
			url = url.replaceAll("/+$", "") + path;
		}
		return url + (url.contains("?") ? "&" : "?") + key + "=" + encode(value);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
	

	/**
	 * 下载抖音视频信息
	 * 优化版本：减少重复代码，提高效率，增强可读性
	 * @param url 抖音视频链接
	 * @return 视频信息Map，包含视频ID、播放地址等
	 */
	public static Map<String, String> downVideo(String url) {
		return downVideo(url, null, Global.tiktokCookie);
	}

	public static Map<String, String> downVideo(String url, Integer historyId) {
		return downVideo(url, historyId, Global.tiktokCookie);
	}

	public static Map<String, String> downVideo(String url, Integer historyId, String cookie) {
		try {
			logger.info("[DouyinSingle] start rawUrl={}", url);
			// 获取重定向后的真实URL
			Document document = Jsoup.connect(url).userAgent(ua).get();
			String baseUri = document.baseUri();
			logger.info("抖音解析URL: {}", baseUri);
			logger.info("[DouyinSingle] resolvedUrl={}", baseUri);
			
			// 提取视频ID
			String videoId = extractVideoId(baseUri);
			if (videoId == null) {
				String noteId = extractNoteId(baseUri);
				logger.warn("[DouyinSingle] no videoId extracted, noteId={}", noteId);
				if(noteId!= null) {
					DouYinExecutor.ImageTextExecutor(url,noteId, historyId);
				}
				return null;
			}
			
			logger.info("抖音视频ID: {}", videoId);
			logger.info("[DouyinSingle] extracted videoId={}", videoId);
			
			// 获取视频数据
			Map<String, String> data = getBogusWithSource(videoId, url, cookie);
			if (data != null) {
				logger.info("接口解析成功，数据: {}", data);
				return data;
			} else {
				logger.warn("接口解析失败，视频ID: {}", videoId);
				return null;
			}
			
		} catch (IOException e) {
			logger.error("解析异常: {}", e.getMessage(), e);
			return null;
		}
	}

	public static String resolveWorkUrl(String url) throws IOException {
		if (url == null || url.trim().isEmpty()) {
			throw new IOException("Douyin URL is empty");
		}
		Document document = Jsoup.connect(url).userAgent(ua).get();
		String resolved = document.baseUri();
		return resolved == null || resolved.trim().isEmpty() ? url.trim() : resolved.trim();
	}

	public static String extractWorkId(String resolvedUrl) {
		String videoId = extractVideoId(resolvedUrl);
		return videoId == null ? extractNoteId(resolvedUrl) : videoId;
	}

	public static String fetchWorkDataJson(String awemeId, String cookie) {
		if (awemeId == null || awemeId.trim().isEmpty() || cookie == null || cookie.trim().isEmpty()) {
			return null;
		}
		String output = CommandUtil.f2cmd(cookie, awemeId, "fetch_work_data", null, null, null, null);
		if (output == null || output.trim().isEmpty()) {
			return null;
		}
		try {
			JSONObject object = JSONObject.parseObject(output.trim());
			return object == null ? null : object.toJSONString();
		} catch (RuntimeException e) {
			logger.warn("[DouyinSingle] fetch_work_data returned invalid JSON awemeId={} outputLength={}",
					awemeId, output.length());
			return null;
		}
	}
	
	/**
	 * 从URL中提取视频ID
	 * 支持多种URL格式的视频ID提取
	 * @param baseUri 基础URI
	 * @return 视频ID，如果提取失败返回null
	 */
	private static String extractVideoId(String baseUri) {
		// 尝试第一种模式：/video/xxx/
		Matcher matcher1 = VIDEO_ID_PATTERN_V1.matcher(baseUri);
		if (matcher1.find()) {
			return matcher1.group(1);
		}
		
		// 尝试第二种模式：/video/数字
		Matcher matcher2 = VIDEO_ID_PATTERN_V2.matcher(baseUri);
		if (matcher2.find()) {
			return matcher2.group(1);
		}
		
		return null;
	}
	
	
	/**
	 * 从URL中提取图文ID
	 * 支持多种URL格式的图文ID提取
	 * @param baseUri 基础URI
	 * @return 视频ID，如果提取失败返回null
	 */
	private static String extractNoteId(String baseUri) {
		// 尝试第一种模式：/video/xxx/
		Matcher matcher1 = NOTE_ID_PATTERN_V1.matcher(baseUri);
		if (matcher1.find()) {
			return matcher1.group(1);
		}
		
		// 尝试第二种模式：/video/数字
		Matcher matcher2 = NOTE_ID_PATTERN_V2.matcher(baseUri);
		if (matcher2.find()) {
			return matcher2.group(1);
		}
		
		return null;
	}
//	public static  Map<String, String> htmlclient(String url) {
//		 logger.info("WebClient客户端开始启动");
//		 Map<String, String> res = new HashMap<String, String>();
//		 WebClient webClient = ThreadConfig.getWebClient();
//	        HtmlPage page = null;
//	        try {
//	            page = webClient.getPage(url);
//		        webClient.waitForBackgroundJavaScript(300);
//		        String pageXml = page.asXml();
//		        Document parse = Jsoup.parse(pageXml);
//		        Element render_data = parse.getElementById("RENDER_DATA");
//		        String encode = URLDecoder.decode(render_data.html().substring("//<![CDATA[".length(), render_data.html().length() - "//]]>".length()).trim(), "UTF-8");
//		        JSONObject jsonObject = JSON.parseObject(encode);
////		        System.out.println(jsonObject);
//		        jsonObject.forEach((key, value) -> {
//		        	if(DouUtil.isJSONString(value.toString())) {
//		        		  JSONObject aweme = JSONObject.parseObject(value.toString()).getJSONObject("aweme");
//		        		  if(aweme != null) {
//		        			  JSONObject detail = aweme.getJSONObject("detail");
//		        			  String awemeId = detail.getString("awemeId");
//		        			  String desc = detail.getString("desc");
//		        			  JSONObject videoobj = detail.getJSONObject("video");
//		        	          String playApi = videoobj.getString("playApi");
//		        	          String cover = videoobj.getString("cover");
//		        	          res.put("cover", cover);
//		        	     	  res.put("awemeid", awemeId);
//		        			  res.put("videoplay", playApi);
//		        			  res.put("desc", desc);
//		        			  res.put("type", "client");
//		        		  }
//		        	}
//					
//				});
//	        } catch (Exception e) {
//	        	logger.info("获取不到");
//	        }finally {
//				//如果后续观察内存占用问题比较大 考虑取消此处注释
//				webClient.getCurrentWindow().getJobManager().removeAllJobs();
//				webClient.getCurrentWindow().getJobManager().shutdown();
//				webClient.close();
//				System.gc();
//	        }
//	        logger.info("下载流程结束");
//	        return res;
//	
//	}
	
	/**
	 * 获取xBogus 并获取视频数据
	 * @param aweme_id
	 * @param type
	 * @return
	 * @throws HttpException
	 * @throws IOException
	 */
	public static Map<String, String> getBogus(String aweme_id, String rawUrl) throws HttpException, IOException {
		Map<String, String> res = getBogusWithCookie(aweme_id, Global.tiktokCookie);
		JSONObject hybrid = fetchHybridVideoData(rawUrl);
		if (res != null && hybrid != null) {
			mergeHybridData(res, hybrid);
		}
		return res;
	}

	public static  Map<String, String> getBogus(String aweme_id) throws HttpException, IOException {
		return getBogusWithCookie(aweme_id, Global.tiktokCookie);
	}

	public static Map<String, String> getBogusWithSource(String aweme_id, String rawUrl, String cookie) throws HttpException, IOException {
		Map<String, String> res = getBogusWithCookie(aweme_id, cookie);
		JSONObject hybrid = fetchHybridVideoData(rawUrl);
		if (res != null && hybrid != null) {
			mergeHybridData(res, hybrid);
		}
		return res;
	}

	public static  Map<String, String> getBogusWithCookie(String aweme_id, String cookie) throws HttpException, IOException {
		 Map<String, String> res = new HashMap<String, String>();
		 if(null !=cookie && !"".equals(cookie) ) {
			 logger.info("[DouyinSingle] fetch_video start awemeId={}", aweme_id);
		
			 String httpget = CommandUtil.f2cmd(cookie,aweme_id,"fetch_video",null,null,null,null);
			 Integer exitCode = CommandUtil.getLastF2ExitCode();
			 Long durationMs = CommandUtil.getLastF2DurationMs();
			 logger.info("[DouyinSingle] fetch_video outputLength={}", httpget == null ? 0 : httpget.length());
			 logger.info("[DouyinSingle] fetch_video exitCode={} durationMs={}", exitCode, durationMs);
			 logger.info("[DouyinSingle] fetch_video preview={}", previewOutput(httpget));
			 if (httpget == null || httpget.isBlank()) {
				 logger.error("[DouyinSingle] fetch_video empty output awemeId={}", aweme_id);
				 return null;
			 }
//			 System.out.println(httpget);
			 JSONObject data = JSONObject.parseObject(httpget);
			 if (data == null) {
				 logger.error("[DouyinSingle] fetch_video json parse failed awemeId={}", aweme_id);
				 return null;
			 }
			 logger.info("[DouyinSingle] parsed fields descPresent={} playAddrPresent={} coverPresent={} nicknamePresent={}",
					data.containsKey("desc"), data.containsKey("video_play_addr"), data.containsKey("cover"), data.containsKey("nickname"));
			 String coveruri = "";
			 JSONArray cover = data.getJSONArray("cover");
			 if(cover.size() >=2) {
				 coveruri = cover.getString(cover.size()-1);
			 }else {
				 coveruri = cover.getString(0);
			 }
			 JSONArray jsonArray = data.getJSONArray("video_play_addr");
			 String videoplay = "";
			 if(jsonArray.size() >=2) {
				 videoplay = jsonArray.getString(jsonArray.size()-1);
			 }else {
				 videoplay = jsonArray.getString(0);
			 }
			 String desc = data.getString("desc");
			 String nickname = data.getString("nickname");
			 String uid = data.getString("uid");
			 String secUid = data.getString("sec_uid");
			 String uniqueId = data.getString("unique_id");
			 JSONObject author = data.getJSONObject("author");
			 if (author != null) {
				 if (nickname == null || nickname.trim().isEmpty()) nickname = author.getString("nickname");
				 if (uid == null || uid.trim().isEmpty()) uid = author.getString("uid");
				 secUid = firstNotBlank(secUid, author.getString("sec_uid"));
				 uniqueId = firstNotBlank(uniqueId, author.getString("unique_id"));
			 }
			 String create_time = data.getString("create_time");
			 res.put("awemeid", aweme_id);
			 res.put("videoplay", videoplay);
			 res.put("desc", desc);
			 res.put("cover", coveruri);
			 res.put("type", "api");
			 res.put("nickname", nickname);
			 res.put("uid", uid);
			 res.put("sec_uid", secUid);
			 res.put("unique_id", uniqueId);
			 res.put("create_time", create_time);
			 res.put("avatar_thumb", data.getString("avatar_thumb"));
			 res.put("jsonData", data.toJSONString());
			 return res;
		 }
		 return null;

	}

	private static void mergeHybridData(Map<String, String> res, JSONObject hybrid) {
		res.put("jsonData", hybrid.toJSONString());
		JSONObject detail = findAwemeDetail(hybrid);
		if (detail == null) return;
		JSONObject author = detail.getJSONObject("author");
		if (author != null) {
			res.put("nickname", firstNotBlank(author.getString("nickname"), res.get("nickname")));
			res.put("uid", firstNotBlank(author.getString("uid"), res.get("uid")));
			res.put("sec_uid", firstNotBlank(author.getString("sec_uid"), res.get("sec_uid")));
			res.put("unique_id", firstNotBlank(author.getString("unique_id"), res.get("unique_id")));
			res.put("signature", author.getString("signature"));
			res.put("avatar_thumb", firstNotBlank(extractAvatar(author), res.get("avatar_thumb")));
		}
		res.put("desc", firstNotBlank(detail.getString("desc"), res.get("desc")));
		res.put("create_time", firstNotBlank(detail.getString("create_time"), res.get("create_time")));
	}

	public static JSONObject findAwemeDetail(JSONObject object) {
		if (object == null) return null;
		JSONObject direct = object.getJSONObject("aweme_detail");
		if (direct != null) return direct;
		JSONObject data = object.getJSONObject("data");
		if (data != null) {
			JSONObject fromData = data.getJSONObject("aweme_detail");
			if (fromData != null) return fromData;
			JSONObject item = data.getJSONObject("item");
			if (item != null) return item;
		}
		JSONObject record = object.getJSONObject("record");
		if (record != null) return findAwemeDetail(record);
		return null;
	}

	public static String extractAvatar(JSONObject author) {
		if (author == null) return null;
		String[] keys = {"avatar_300x300", "avatar_medium", "avatar_thumb"};
		for (String key : keys) {
			Object value = author.get(key);
			String url = extractUrl(value);
			if (url != null && !url.trim().isEmpty()) return url;
		}
		return null;
	}

	private static String extractUrl(Object value) {
		if (value == null) return null;
		if (value instanceof String) return (String) value;
		if (value instanceof JSONObject) {
			JSONArray urls = ((JSONObject) value).getJSONArray("url_list");
			if (urls != null && !urls.isEmpty()) return urls.getString(urls.size() - 1);
		}
		if (value instanceof JSONArray && !((JSONArray) value).isEmpty()) return ((JSONArray) value).getString(((JSONArray) value).size() - 1);
		return null;
	}

	private static String firstNotBlank(String first, String second) {
		if (first != null && !first.trim().isEmpty()) {
			return first;
		}
		return second;
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
	
	/**
	 * 
	 * 已废弃接口  目前需要填写用户cookie 所以暂时这个接口不用了
	 * @param aweme_id
	 * @param type
	 * @return
	 * @throws HttpException
	 * @throws IOException
	 */
	@Deprecated
	public static  Map<String, String> getBogusDiscard(String aweme_id,String type) throws HttpException, IOException {
		Map<String, String> res = new HashMap<String, String>();
		 String url ="";
		 String cookie ="";
		 String code = "";
		// 2023.07.10 优先本地模式  懒得改了 先这样写
		if(type.equals("local")) {
			Map<String, String> generatetoken = generatetoken(aweme_id);
			try {
				if(generatetoken != null) {
					logger.info("使用本地生成xBogus");
					code="200";
					url = generatetoken.get("url");
					cookie = generatetoken.get("cookie");
				}else {
					 logger.info("本地生成异常(空信息)--正在使用remote模式");
					 return getBogus(aweme_id);
				}
			} catch (Exception e) {
				 logger.info("本地生成异常--正在使用remote模式");
				 return getBogus(aweme_id);
			}
		}else {
//			logger.info("使用远程生成xBogus");
////			JSONObject token = HttpUtil.doPostNew(Global.analysiSserver+"/spirit-token", DouUtil.generateparameters(aweme_id));
//			code = token.getString("code");
//			if(code.equals("200")) {
//				code="200";
//				url = token.getJSONObject("data").getString("url");
//				cookie = token.getJSONObject("data").getString("cookie"); 
//			}
		}
		// 2023.07.10 优先本地模式  懒得改了 先这样写
		if(code.equals("200")) {
			 String httpget = DouUtil.httpget(url.trim(), cookie.trim());
			 JSONObject data = JSONObject.parseObject(httpget);
			 if(null == data) {
				 return getBogus(aweme_id);
			 }
			 JSONObject aweme_detail = data.getJSONObject("aweme_detail");
			 if(null == aweme_detail && type.equals("local")) {
				 return getBogus(aweme_id);
			 }
			 String coveruri = "";
			 JSONArray cover = aweme_detail.getJSONObject("video").getJSONObject("cover").getJSONArray("url_list");
			 if(cover.size() >=2) {
				 coveruri = cover.getString(cover.size()-1);
			 }else {
				 coveruri = cover.getString(0);
			 }
			 JSONArray jsonArray = aweme_detail.getJSONObject("video").getJSONObject("play_addr").getJSONArray("url_list");
			 String videoplay = "";
			 if(jsonArray.size() >=2) {
				 videoplay = jsonArray.getString(jsonArray.size()-1);
			 }else {
				 videoplay = jsonArray.getString(0);
			 }
			 String desc = aweme_detail.getString("desc");
			 res.put("awemeid", aweme_id);
			 res.put("videoplay", videoplay);
			 res.put("desc", desc);
			 res.put("cover", coveruri);
			 res.put("cookie", cookie.trim());
			 res.put("type", "api");
			 return res;
		}
		return null;

		
	}
	/**
	 * get 请求 接口数据
	 * @param addr
	 * @param ck
	 * @return
	 * @throws IOException
	 */
	public static String httpget(String addr,String ck) throws IOException {
		String cookie = ck;
        String urlString = addr;    
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("user-agent", ua);
        conn.setRequestProperty("referer", "https://www.douyin.com/");
        conn.setRequestProperty("cookie", cookie);
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        return response.toString();
	}
	
	

	
	/**
	 * 构建请求数据
	 * @param aweme_id
	 * @return
	 */
	public static JSONObject generateparameters(String aweme_id) {
		JSONObject data =  new JSONObject();
		data.put("awemeid", aweme_id);
		data.put("ua", ua);
		return data;
	}

	
	/**
	 * 判断是否为json
	 * @param str
	 * @return
	 */
	@SuppressWarnings("unused")
	public static boolean isJSONString(String str) {
	    boolean result = false;
	    try {
	    	JSONObject obj=JSONObject.parseObject(str);
	        result = true;
	    } catch (Exception e) {
	        result=false;
	    }
	    return result;
	}
	
	
	/**
	 * 生成Xbogus并返回 请求URL
	 * @param aid
	 * @return
	 */
	public static Map<String, String> generatetoken(String aid) {
		String url ="https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=#awemeid#&aid=1128&version_name=23.5.0&device_platform=android&os_version=2333&X-Bogus=#bogus#";
		Map<String, String> res = new HashMap<String, String>();
//	    String urlPath = "aweme_id="+aid+"&aid=1128&cookie_enabled=true&platform=android&downlink=10";
	    String urlPath = "aweme_id="+aid+"&aid=1128&version_name=23.5.0&device_platform=android&os_version=2333";
		try {
			String xbogusToken = XbogusUtil.getXBogus(urlPath);
			String queryurl = url.replace("#awemeid#",aid).replace("#bogus#",xbogusToken);
			res.put("xbogus", xbogusToken);
			res.put("url", queryurl);
			return res;
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}
	
	/**
	 * 
	 * 获取Ttwid 其实可以加缓存 复用ttwid  暂时没有加缓存
	 * 后续添加
	 * @return
	 */
	public static String getTtwid() {
        try {
            String data = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":true}";
            URL url = new URL(ttwid);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("user-agent", ua);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(data.getBytes());
            outputStream.flush();
            outputStream.close();
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                StringBuilder response = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String setCookie = connection.getHeaderField("Set-Cookie");
                if (setCookie != null) {
                	  String[] parts = setCookie.split(";");
                      for (String part : parts) {
                          if (part.trim().startsWith("ttwid=")) {
                              String[] keyValue = part.split("=", 2);
                              if (keyValue.length == 2) {
                                  return keyValue[1].trim();
                              }
                          }
                      }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }
	/**
	 * 简化cookie
	 * @param cookie
	 * @return
	 */
	public static String simplifycookie(String cookie) {
		Map<String, String> parseCookieString = parseCookieString(cookie);
		String ck ="odin_tt="+parseCookieString.get("odin_tt")+";sessionid_ss="+parseCookieString.get("sessionid_ss")+";ttwid="+parseCookieString.get("ttwid")+";passport_csrf_token="+parseCookieString.get("passport_csrf_token")+";msToken="+parseCookieString.get("msToken")+";";
		return ck;
		
	}
	
	
	/**
	 * cookie 转map
	 * @param cookieString
	 * @return
	 */
	public static Map<String, String> parseCookieString(String cookieString) {
        Map<String, String> cookieMap = new HashMap<>();

        if (cookieString != null && !cookieString.isEmpty()) {
            String[] cookiePairs = cookieString.split("; ");
            for (String cookiePair : cookiePairs) {
                String[] parts = cookiePair.split("=");
                if (parts.length == 2) {
                    String name = parts[0];
                    String value = parts[1];
                    cookieMap.put(name, value);
                }
            }
        }

        return cookieMap;
    }
	
	
	 public static String getFp() {
		 String e = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	        int t = e.length();
	        long milliseconds = System.currentTimeMillis();
	        StringBuilder base36 = new StringBuilder();

	        while (milliseconds > 0) {
	            int remainder = (int) (milliseconds % 36);
	            if (remainder < 10) {
	                base36.insert(0, remainder);
	            } else {
	                base36.insert(0, (char) (Character.valueOf('a') + remainder - 10));
	            }
	            milliseconds = milliseconds / 36;
	        }
	        String r = base36.toString();
	        char[] o = new char[36];
	        o[8] = o[13] = o[18] = o[23] = '_';
	        o[14] = '4';

	        Random random = new Random();
	        for (int i = 0; i < 36; i++) {
	            if (o[i] == 0) {
	                int n = random.nextInt(t);
	                if (i == 19) {
	                    n = 3 & n | 8;
	                }
	                o[i] = e.charAt(n);
	            }
	        }
	        StringBuilder ret = new StringBuilder("verify_" + r + "_");
	        ret.append(o);
	        return ret.toString();
	    }

	public static void main(String[] args) {
		Map<String, String> generatetoken = DouUtil.generatetoken("7221047525594139944");
		System.out.println(generatetoken);
		
	}

	
}
