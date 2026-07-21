package com.flower.spirit.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YtDlpUtil {

	private static final Logger logger = LoggerFactory.getLogger(YtDlpUtil.class);
	private static final String DOWNLOADED_FILE_PREFIX = "__STREAMVAULT_FILE__";

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
		Process process = new ProcessBuilder(command).start();
		Thread stderrReader = new Thread(() -> drainStream(process.getErrorStream()),
				"yt-dlp-stderr-" + System.currentTimeMillis());
		stderrReader.setDaemon(true);
		stderrReader.start();
		String stdout;
		try (InputStream input = process.getInputStream()) {
			stdout = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		int exitCode = process.waitFor();
		stderrReader.join();
		if (exitCode != 0) {
			throw new IOException("yt-dlp " + operation + " failed with exit code " + exitCode);
		}
		return stdout;
	}

	private static void drainStream(InputStream stream) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			while (reader.readLine() != null) { }
		} catch (IOException e) {
			logger.debug("Failed to read yt-dlp stderr", e);
		}
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
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();
		StringBuilder stringBuilder = new StringBuilder();
		Thread progressThread = null;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			progressThread = new Thread(() -> {
				try {
					while (!Thread.currentThread().isInterrupted()) {
						Thread.sleep(5000);
						logger.info("yt-dlp 下载进行中...");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			progressThread.setName("yt-dlp-progress-" + System.currentTimeMillis());
			progressThread.setDaemon(true);
			progressThread.start();

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains("[download]") && line.contains("%")) {
					logger.info("yt-dlp 下载进度: {}", line.trim());
				} else if (line.startsWith("{")) {
					stringBuilder.append(line).append("\n");
				} else if (line.contains("[Merger]") || line.contains("[VideoConvertor]")) {
					logger.info("yt-dlp 处理: {}", line.trim());
				} else if (line.contains("ERROR") || line.contains("error")) {
					logger.error("yt-dlp 错误: {}", line.trim());
				}
			}
		} finally {
			if (progressThread != null) {
				progressThread.interrupt();
			}
		}

		int exitCode = process.waitFor();
		String completeString = stringBuilder.toString();

		if (exitCode != 0) {
			logger.error("yt-dlp执行失败，退出码: {}, 输出: {}", exitCode, completeString);
			throw new RuntimeException("yt-dlp执行失败，退出码: " + exitCode);
		}

		logger.info("yt-dlp执行成功，退出码: {}", exitCode);
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
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		Process process = processBuilder.start();
		InputStream inputStream = process.getInputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
		StringBuilder stringBuilder = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			stringBuilder.append(line);
			;
		}
		int exitCode = process.waitFor();
		System.out.println("Command executed with exit code: " + exitCode);
		String completeString = stringBuilder.toString();
		return completeString;
	}

	public static String getPlatform(String url) {
	    if (url == null || url.trim().isEmpty()) {
	        logger.warn("URL为空，无法获取平台信息");
	        return null;
	    }

	    Process process = null;
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
	        ProcessBuilder processBuilder = new ProcessBuilder(command);
	        processBuilder.redirectErrorStream(false);
	        process = processBuilder.start();
	        String stdout;
	        try (BufferedReader reader = new BufferedReader(
	                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
	            stdout = reader.readLine();
	        }
	        String stderr;
	        try (BufferedReader reader = new BufferedReader(
	                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
	            StringBuilder sb = new StringBuilder();
	            String line;
	            while ((line = reader.readLine()) != null) {
	                sb.append(line).append("\n");
	            }
	            stderr = sb.toString();
	        }

	        int exitCode = process.waitFor();

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

	    } catch (Exception e) {
	        logger.error("获取平台信息失败: {}", e.getMessage(), e);
	        return null;
	    } finally {
	        if (process != null) {
	            process.destroyForcibly();
	        }
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

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		Process process = processBuilder.start();

		Thread stderrThread = new Thread(() -> {
			try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
				String errLine;
				while ((errLine = errReader.readLine()) != null) {
					logger.warn("yt-dlp stderr: " + errLine);
				}
			} catch (IOException e) {
				logger.error("yt-dlp 错误输出失败", e);
			}
		});
		stderrThread.setName("yt-dlp-stderr-reader");
		stderrThread.start();

		StringBuilder stringBuilder = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				stringBuilder.append(line).append("\n");
			}
		} finally {
			process.waitFor();
			stderrThread.join();
			process.destroy();
		}

		String completeString = stringBuilder.toString();
		if (process.exitValue() != 0) {
			logger.error("yt-dlp 执行失败 (exitCode: {}): {}", process.exitValue(), completeString);
//			throw new IOException("yt-dlp 执行失败 (exitCode: " + process.exitValue() + "): " + completeString);
		} else {
			logger.info("yt-dlp executed with exit code: {}, 输出长度: {}", process.exitValue(), completeString.length());
		}
		return completeString;
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

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		Process process = processBuilder.start();

		Thread stderrThread = new Thread(() -> {
			try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
				String errLine;
				while ((errLine = errReader.readLine()) != null) {
					logger.warn("yt-dlp stderr: " + errLine);
				}
			} catch (IOException e) {
				logger.error("yt-dlp 错误输出失败", e);
			}
		});
		stderrThread.setName("yt-dlp-audio-stderr-reader");
		stderrThread.start();

		StringBuilder stringBuilder = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				stringBuilder.append(line).append("\n");
			}
		} finally {
			process.waitFor();
			stderrThread.join();
			process.destroy();
		}

		String completeString = stringBuilder.toString();
		if (process.exitValue() != 0) {
			logger.error("yt-dlp 音频执行失败 (exitCode: {}): {}", process.exitValue(), completeString);
			throw new IOException("yt-dlp 音频执行失败 (exitCode: " + process.exitValue() + "): " + completeString);
		} else {
			logger.info("yt-dlp 音频执行成功, 输出长度: {}", completeString.length());
		}
		return completeString;
	}
}
