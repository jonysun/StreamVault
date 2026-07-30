package com.flower.spirit.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import com.flower.spirit.process.ControlledProcessExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YtDlpUtil {

	private static final Logger logger = LoggerFactory.getLogger(YtDlpUtil.class);
	private static final String DOWNLOADED_FILE_PREFIX = "__STREAMVAULT_FILE__";
	private static final Duration METADATA_TIMEOUT = Duration.ofMinutes(2);
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofHours(2);
	private static final int DIAGNOSTIC_OUTPUT_LIMIT = 128 * 1024;
	private static final ControlledProcessExecutor PROCESS_EXECUTOR = new ControlledProcessExecutor();

	public static String execSingleMetadata(String url, String platform) throws IOException, InterruptedException {
		validateUrl(url);
		String cookiePlatform = platform;
		if (cookiePlatform == null || cookiePlatform.trim().isEmpty()) {
			cookiePlatform = getPlatform(url);
		}
		List<String> command = new ArrayList<>();
		command.add("yt-dlp");
		command.add("--dump-json");
		command.add("--skip-download");
		command.add("--no-playlist");
		addCookieConfig(command, cookiePlatform);
		addNetworkConfig(command);
		command.add(url.trim());
		logger.info("Executing yt-dlp single-work metadata probe for platform={}", platform);
		return runCommand(command, "metadata probe");
	}

	public static List<Path> downloadSingleVideo(String url, Path outputDirectory, String platform)
			throws IOException, InterruptedException {
		validateUrl(url);
		if (outputDirectory == null) throw new IllegalArgumentException("output directory is required");
		Path output = outputDirectory.toAbsolutePath().normalize();
		Files.createDirectories(output);
		List<String> command = new ArrayList<>();
		command.add("yt-dlp");
		command.add("--no-playlist");
		command.add("--newline");
		command.add("-f");
		command.add("bestvideo+bestaudio/best");
		command.add("--merge-output-format");
		command.add("mp4");
		command.add("--print");
		command.add("after_move:" + DOWNLOADED_FILE_PREFIX + "%(filepath)s");
		command.add("-o");
		command.add(output.resolve("%(id)s.%(ext)s").toString());
		addCookieConfig(command, platform);
		addNetworkConfig(command);
		command.add(url.trim());
		logger.info("Executing yt-dlp single-work download for platform={}", platform);
		String result = runCommand(command, "video download");
		LinkedHashSet<Path> files = new LinkedHashSet<>();
		for (String line : result.split("\\R")) {
			if (!line.startsWith(DOWNLOADED_FILE_PREFIX)) continue;
			Path file = Path.of(line.substring(DOWNLOADED_FILE_PREFIX.length())).toAbsolutePath().normalize();
			if (!file.startsWith(output)) {
				throw new IOException("yt-dlp reported a downloaded file outside the output directory");
			}
			files.add(file);
		}
		if (files.isEmpty()) throw new IOException("yt-dlp completed without reporting a downloaded video file");
		return List.copyOf(files);
	}

	private static String runCommand(List<String> command, String operation)
			throws IOException, InterruptedException {
		Duration timeout = operation.contains("download") ? DOWNLOAD_TIMEOUT : METADATA_TIMEOUT;
		ControlledProcessExecutor.Result result = PROCESS_EXECUTOR.execute(command, timeout,
				"yt-dlp-" + operation, DIAGNOSTIC_OUTPUT_LIMIT);
		if (!result.successful()) {
			String reason = result.timedOut() ? "timeout" : "exit code " + result.exitCode();
			logger.warn("yt-dlp operation failed operation={} reason={} stderrPreview={}", operation, reason,
					preview(result.stderr()));
			throw new IOException("yt-dlp " + operation + " failed: " + reason);
		}
		return result.stdout();
	}

	private static String preview(String output) {
		if (output == null || output.isBlank()) return "";
		String normalized = output.replaceAll("[\\r\\n]+", " ").trim();
		return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000) + "...";
	}

	private static void addNetworkConfig(List<String> command) {
		if (Global.proxyinfo != null && !Global.proxyinfo.trim().isEmpty()) {
			command.add("--proxy");
			command.add(Global.proxyinfo);
		}
		if (Global.useragent != null && !Global.useragent.trim().isEmpty()) {
			command.add("--user-agent");
			command.add(Global.useragent);
		}
	}

	private static void validateUrl(String url) {
		if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("URL must not be blank");
	}

	public static String exec(String url, String outpath, String p, Boolean createnfo)
			throws IOException, InterruptedException {
		if (url == null || url.trim().isEmpty()) {
			throw new IllegalArgumentException("URL不能为空");
		}

		List<String> command = new ArrayList<>();
		command.add("yt-dlp");
		command.add("--print-json");
		command.add("--progress");
		command.add("--newline");

		addCookieConfig(command, p);

		if (Global.proxyinfo != null && !Global.proxyinfo.trim().isEmpty()) {
			command.add("--proxy");
			command.add(Global.proxyinfo);
		}
		command.add(url);
		command.add("-o");
		if (Global.getGeneratenfo && createnfo) {
			command.add(outpath + File.separator + "%(title)s" + File.separator + "%(id)s.%(ext)s");
		} else {
			command.add(outpath + File.separator + "%(title)s" + File.separator + "%(title)s.%(ext)s");
		}

		command.add("--write-thumbnail");
		command.add("--convert-thumbnails");
		command.add("webp");
		command.add("--no-restrict-filenames");
		command.add("--windows-filenames");

//		String[] specialChars = {
//			    "#", "\\?", "\\|", "\\<", "\\>", "\\/", "\\\\", 
//			    "\\*", "\\+", "\\.", "\\^", "\\$", "\\[", "\\]", "\\(", "\\)", "\\{", "\\}", "\\!", "\\~", "\\&", "\\%", "\\@"
//		};
		command.add("--replace-in-metadata");
		command.add("title");
		command.add("[^\\w\\u4e00-\\u9fa5]"); 
		command.add("");
		
		
		if (Global.useragent != null && !Global.useragent.trim().isEmpty()) {
			command.add("--user-agent");
			command.add(Global.useragent);
		}
		logger.info("执行yt-dlp下载命令: {}", String.join(" ", command));
		ControlledProcessExecutor.Result result = PROCESS_EXECUTOR.execute(command, DOWNLOAD_TIMEOUT,
				"yt-dlp-download", DIAGNOSTIC_OUTPUT_LIMIT);
		String completeString = result.stdout();
		if (!result.successful()) {
			String reason = result.timedOut() ? "timeout" : "exit code " + result.exitCode();
			logger.error("yt-dlp执行失败 reason={} stderrPreview={}", reason, preview(result.stderr()));
			throw new RuntimeException("yt-dlp执行失败: " + reason);
		}
		logger.info("yt-dlp执行成功，退出码: {}", result.exitCode());
		return completeString;
	}

	
	/**
	 * 添加Cookie配置到命令中
	 * 
	 * @param command  命令列表
	 * @param platform 平台类型
	 */
	private static void addCookieConfig(List<String> command, String platform) {
		if (platform == null) {
			return;
		}

		String apppath = Global.apppath;
		if (apppath == null || apppath.trim().isEmpty()) {
			logger.warn("应用路径未配置，跳过Cookie设置");
			return;
		}

		File cookieDir = new File(apppath, "cookies");
		if (!cookieDir.exists()) {
			logger.debug("Cookie目录不存在: {}", cookieDir.getAbsolutePath());
			return;
		}

		File cookieFile;
		switch (platform.toLowerCase()) {
			case "youtube":
				cookieFile = new File(cookieDir, "youtube.txt");
				break;
			case "twitter":
				cookieFile = new File(cookieDir, "twitter.txt");
				break;
			default:
				cookieFile = new File(cookieDir, platform + ".txt");
				break;
		}

		if (cookieFile.exists()) {
			command.add("--cookies");
			command.add(cookieFile.getAbsolutePath());
		}
	}
	
	public static boolean isVideoStream(JSONObject format) {
		String vcodec = format.getString("vcodec");
		return vcodec != null && !"none".equals(vcodec);
	}

	public static boolean isAudioStream(JSONObject format) {
		String vcodec = format.getString("vcodec");
		String audioExt = format.getString("audio_ext");
		return "none".equals(vcodec) &&
				audioExt != null &&
				!"none".equals(audioExt);
	}

	public static String exec(String url) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("yt-dlp");
		command.add("--print-json");
		command.add("--skip-download");
		command.add(url);
		return runCommand(command, "metadata");
	}

	public static String getPlatform(String url) {
	    if (url == null || url.trim().isEmpty()) {
	        logger.warn("URL为空，无法获取平台信息");
	        return null;
	    }

	    try {
	        List<String> command = new ArrayList<>();
	        command.add("yt-dlp");
	        command.add("--print");
	        command.add("%(extractor)s");    
	        command.add("--no-download");
	        command.add("--skip-download");
	        command.add("--ignore-config"); 
	        if (Global.proxyinfo != null && !Global.proxyinfo.trim().isEmpty()) {
	            command.add("--proxy");
	            command.add(Global.proxyinfo);
	        }
	        if (Global.useragent != null && !Global.useragent.isEmpty()) {
	            command.add("--user-agent");
	            command.add(Global.useragent);
	        }
	        command.add(url);
	        ControlledProcessExecutor.Result result = PROCESS_EXECUTOR.execute(command, METADATA_TIMEOUT,
	                "yt-dlp-platform", DIAGNOSTIC_OUTPUT_LIMIT);
	        String stdout = result.stdout().lines().findFirst().orElse(null);
	        String stderr = result.stderr();
	        int exitCode = result.exitCode();

	        // 情况1: stdout 有 extractor（成功）
	        if (stdout != null && !stdout.trim().isEmpty()) {
	            String platform = stdout.trim();
	            logger.debug("从 stdout 获取平台: {}", platform);
	            return platform;
	        }

	        if (stderr != null) {
	            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[(\\w+)\\]");
	            java.util.regex.Matcher matcher = pattern.matcher(stderr);
	            if (matcher.find()) {
	                String platform = matcher.group(1);
	                logger.debug("从 stderr 提取平台: {}", platform);
	                return platform;
	            }
	        }

	        logger.warn("无法识别平台，exitCode={}, stdout={}, stderr={}", exitCode, stdout, stderr);
	        return null;

	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	        logger.error("获取平台信息被中断");
	        return null;
	    } catch (Exception e) {
	        logger.error("获取平台信息失败: {}", e.getMessage(), e);
	        return null;
	    }
	}

	/**
	 * 执行 yt-dlp 获取视频 JSON 信息（用于本地下载）
	 * @param url 视频URL
	 * @param platform 平台名称
	 * @return JSON 字符串
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static String execForJson(String url, String platform) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("yt-dlp");
		command.add("--dump-json");
		command.add("--no-download");
		// 添加 --flat-playlist 支持播放列表
		command.add("--flat-playlist");
		
		command.add("-f");
		command.add("bestvideo+bestaudio/best");
		
		addCookieConfig(command, platform);

		if (Global.proxyinfo != null && !Global.proxyinfo.trim().isEmpty()) {
			command.add("--proxy");
			command.add(Global.proxyinfo);
			logger.info("使用代理: {}", Global.proxyinfo);
		} else {
			logger.warn("未配置代理，某些平台可能无法访问");
		}

		if (null != Global.useragent && !"".equals(Global.useragent)) {
			command.add("--user-agent");
			command.add(Global.useragent);
		}

		command.add(url);
		
		logger.info("执行 yt-dlp 命令: {}", String.join(" ", command));
		ControlledProcessExecutor.Result result = PROCESS_EXECUTOR.execute(command, METADATA_TIMEOUT,
				"yt-dlp-video-json", DIAGNOSTIC_OUTPUT_LIMIT);
		if (!result.successful()) {
			String reason = result.timedOut() ? "timeout" : "exit code " + result.exitCode();
			logger.warn("yt-dlp JSON metadata failed reason={} stderrPreview={}", reason, preview(result.stderr()));
		}
		return result.stdout();
	}

	/**
	 * 执行 yt-dlp 获取音频 JSON 信息（用于音乐平台本地下载）
	 * 针对音乐平台进行优化，优先获取音频流
	 * @param url 音乐URL
	 * @param platform 平台名称
	 * @return JSON 字符串
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static String execForAudioJson(String url, String platform) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("yt-dlp");
		command.add("--dump-json");
		command.add("--no-download");
		// 只获取音频格式
		command.add("-f");
		command.add("bestaudio/best");
		// 不下载播放列表
		command.add("--no-playlist");
		
		String apppath = Global.apppath;
		File cookieDir = new File(apppath + "/cookies");
		
		// 根据平台加载 cookie
		if (null != platform) {
			// 网易云音乐 cookie
			if (platform.equals("网易云音乐") || platform.toLowerCase().contains("netease")) {
				File neteaseFile = new File(cookieDir, "netease.txt");
				if (neteaseFile.exists()) {
					command.add("--cookies");
					command.add(neteaseFile.getAbsolutePath());
					logger.info("已加载网易云音乐 cookie 文件");
				}
			}
			// QQ音乐 cookie
			else if (platform.equals("QQ音乐") || platform.toLowerCase().contains("qq")) {
				File qqFile = new File(cookieDir, "qqmusic.txt");
				if (qqFile.exists()) {
					command.add("--cookies");
					command.add(qqFile.getAbsolutePath());
					logger.info("已加载QQ音乐 cookie 文件");
				}
			}
			// 其他平台
			else {
				File all = new File(cookieDir, platform + ".txt");
				if (all.exists()) {
					command.add("--cookies");
					command.add(all.getAbsolutePath());
					logger.info("已加载 {} cookie 文件", platform);
				}
			}
		}

		if (Global.proxyinfo != null && !Global.proxyinfo.trim().isEmpty()) {
			command.add("--proxy");
			command.add(Global.proxyinfo);
			logger.info("使用代理: {}", Global.proxyinfo);
		}

		if (null != Global.useragent && !"".equals(Global.useragent)) {
			command.add("--user-agent");
			command.add(Global.useragent);
		}

		command.add(url);
		
		logger.info("执行 yt-dlp 音频命令: {}", String.join(" ", command));
		ControlledProcessExecutor.Result result = PROCESS_EXECUTOR.execute(command, METADATA_TIMEOUT,
				"yt-dlp-audio-json", DIAGNOSTIC_OUTPUT_LIMIT);
		if (!result.successful()) {
			String reason = result.timedOut() ? "timeout" : "exit code " + result.exitCode();
			logger.error("yt-dlp 音频执行失败 reason={} stderrPreview={}", reason, preview(result.stderr()));
			throw new IOException("yt-dlp 音频执行失败: " + reason);
		}
		logger.info("yt-dlp 音频执行成功, 输出长度: {}", result.stdout().length());
		return result.stdout();
	}
}
