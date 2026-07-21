package com.flower.spirit.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import com.flower.spirit.config.Global;

public class CommandUtil {

    private static Logger logger = LoggerFactory.getLogger(CommandUtil.class);

    static Pattern pattern = Pattern.compile("\"(.*?)\"");

    private static final Pattern SENSITIVE_COOKIE_PATTERN = Pattern.compile(
            "(?i)(sessionid(?:_ss)?|msToken|ttwid|odin_tt|passport_csrf_token)=([^;\\s]+)");

    private static final ThreadLocal<Integer> LAST_F2_EXIT_CODE = new ThreadLocal<>();

    private static final ThreadLocal<Long> LAST_F2_DURATION_MS = new ThreadLocal<>();

    /**
     * 执行命令并输出结果到控制台
     * @param command 要执行的命令
     */
    public static void command(String command) {
        Process process = null;
        try {
            ProcessBuilder processBuilder;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
            }

            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            try (InputStream inputStream = process.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
            int exitCode = process.waitFor();
            logger.info("命令执行完毕，退出码：" + exitCode);
        } catch (IOException | InterruptedException e) {
            logger.error("命令执行异常：" + e.getMessage(), e);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                } catch (Exception e) {
                    logger.error("销毁进程时发生异常：" + e.getMessage());
                }
            }
        }
    }

    public static String steamcmd(String account, String password, String wallpaper) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("steamcmd", "+login " + account + " " + password + "",
                    "+workshop_download_item 431960 " + wallpaper + "", "+quit");
            Process process = processBuilder.start();
            InputStream inputStream = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            String path = "";
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    path = matcher.group(1);
                }
            }
            int exitCode = process.waitFor();
            System.out.println("SteamCMD执行完毕，退出码：" + exitCode);
            return path;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return wallpaper;
    }

    /**
     * 执行命令并返回输出结果
     * @param command 要执行的命令
     * @return 命令执行的输出结果
     */
    public static String commandos(String command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        BufferedReader reader = null;
        try {
            ProcessBuilder processBuilder;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
            }
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            try (InputStream inputStream = process.getInputStream();
                 BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                reader = bufferedReader;
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            logger.info("命令执行完毕，退出码：" + exitCode);
        } catch (IOException | InterruptedException e) {
            logger.error("命令执行异常：" + e.getMessage(), e);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                } catch (Exception e) {
                    logger.error("销毁进程时发生异常：" + e.getMessage());
                }
            }
        }
        return output.toString().trim();
    }

    public static String f2cmd(String cookie, String aid, String fuc, String uid, String cid, Integer maxc,
            String out) {

        logger.info("[F2] start func={} aid={} uid={} cid={} maxc={} out={} cookiePresent={} cookiePreview={}",
                fuc, aid, uid, cid, maxc, out, cookie != null && !cookie.isBlank(), maskCookie(cookie));
        logger.info("[F2] runtime pythonPath=/opt/venv/bin/python3 scriptPath=/home/app/script/douyin.py");

        List<String> cmdList = new ArrayList<>();
        cmdList.add("/opt/venv/bin/python3");
        cmdList.add("/home/app/script/douyin.py");

        switch (fuc) {
            case "fetch_video":
                cmdList.add("fetch_video");
                cmdList.add("--cookie"); cmdList.add(cookie);
                cmdList.add("--aweme_id"); cmdList.add(aid);
                break;

            case "fetch_work_data":
                cmdList.add("fetch_work_data");
                cmdList.add("--cookie"); cmdList.add(cookie);
                cmdList.add("--aweme_id"); cmdList.add(aid);
                break;

            case "fetch_user_like_videos":
            case "fetch_user_post_videos":
                cmdList.add(fuc);
                cmdList.add("--cookie"); cmdList.add(cookie);
                cmdList.add("--uid"); cmdList.add(uid);
                cmdList.add("--maxc"); cmdList.add(String.valueOf(maxc));
                cmdList.add("--output"); cmdList.add(out);
                break;

            case "fetch_user_collects":
                cmdList.add("fetch_user_collects");
                cmdList.add("--cookie"); cmdList.add(cookie);
                break;

            case "fetch_user_collects_videos":
                cmdList.add("fetch_user_collects_videos");
                cmdList.add("--cookie"); cmdList.add(cookie);
                cmdList.add("--cid"); cmdList.add(cid);
                cmdList.add("--maxc"); cmdList.add(String.valueOf(maxc));
                cmdList.add("--output"); cmdList.add(out);
                break;

            case "fetch_user_feed_videos":
                cmdList.add("fetch_user_feed_videos");
                cmdList.add("--cookie"); cmdList.add(cookie);
                cmdList.add("--uid"); cmdList.add(uid);
                cmdList.add("--output"); cmdList.add(out);
                break;

            case "fetch_post_data":
                cmdList.add("fetch_post_data");
                cmdList.add("--cookie"); cmdList.add(cookie);
                cmdList.add("--aweme_id"); cmdList.add(aid);
                cmdList.add("--output"); cmdList.add(out);
                break;

            default:
                throw new IllegalArgumentException("Unsupported function: " + fuc);
        }

        logger.info("[F2] command={}", buildSafeCommandString(cmdList));

        String output = runCommandList(cmdList, "fetch_work_data".equals(fuc));
        return output;
    }
    
    public static String runCommandList(List<String> cmdList) {
        return runCommandList(cmdList, false);
    }

    private static String runCommandList(List<String> cmdList, boolean suppressOutputPreview) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        long startMs = System.currentTimeMillis();
        try {
            logger.info("[F2] process launch");
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true);
            process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            LAST_F2_EXIT_CODE.set(exitCode);
            LAST_F2_DURATION_MS.set(System.currentTimeMillis() - startMs);
            logger.info("[F2] process finished exitCode={} outputLength={}", exitCode, output.length());
            if (output.length() == 0) {
                logger.warn("[F2] process returned empty output");
            } else if (suppressOutputPreview) {
                logger.info("[F2] output preview suppressed for signed media metadata");
            } else {
                String preview = previewOutput(output.toString());
                logger.info("[F2] output preview={}", preview);
                if (exitCode != 0) {
                    logger.error("[F2] process failed exitCode={} output={}", exitCode, preview);
                    if (Global.f2logfullonerror) {
                        logger.error("[F2][FAIL][OUTPUT_BEGIN]\n{}\n[F2][FAIL][OUTPUT_END]", maskSensitiveOutput(output.toString()));
                    }
                }
            }

        } catch (IOException | InterruptedException e) {
            logger.error("命令执行异常：" + e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return output.toString().trim();
    }

    public static Integer getLastF2ExitCode() {
        return LAST_F2_EXIT_CODE.get();
    }

    public static Long getLastF2DurationMs() {
        return LAST_F2_DURATION_MS.get();
    }

    private static String maskCookie(String cookie) {
        if (cookie == null) {
            return "null";
        }
        String trimmed = cookie.trim();
        if (trimmed.isEmpty()) {
            return "empty";
        }
        if (trimmed.length() <= 8) {
            return trimmed;
        }
        if (trimmed.length() <= 16) {
            return trimmed.substring(0, 4) + "***" + trimmed.substring(trimmed.length() - 4);
        }
        return trimmed.substring(0, 8) + "***" + trimmed.substring(trimmed.length() - 8);
    }

    private static String buildSafeCommandString(List<String> cmdList) {
        List<String> safe = new ArrayList<>(cmdList);
        for (int i = 0; i < safe.size() - 1; i++) {
            if ("--cookie".equals(safe.get(i))) {
                safe.set(i + 1, "***masked***");
            }
        }
        return String.join(" ", safe);
    }

    private static String previewOutput(String output) {
        if (output == null) {
            return "null";
        }
        String normalized = output.replace("\r", "\\r").replace("\n", "\\n");
        String safe = maskSensitiveOutput(normalized);
        int maxLen = Global.f2logmaxpreview > 0 ? Global.f2logmaxpreview : 1000;
        if (safe.length() > maxLen) {
            return safe.substring(0, maxLen);
        }
        return safe;
    }

    private static String maskSensitiveOutput(String output) {
        if (output == null) {
            return null;
        }
        if (!Global.f2logmasksensitive) {
            return output;
        }
        Matcher matcher = SENSITIVE_COOKIE_PATTERN.matcher(output);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            String masked = key + "=" + maskCookie(value);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static boolean deleteDirectory(String directoryPath) {
        // System.out.println(directoryPath);
        logger.error("[删除目录] 正在准备删除目录:" + directoryPath);
        if (directoryPath == null || directoryPath.trim().isEmpty()) {
            logger.error("[删除目录警告] 正在尝试删除空目录或根路径");
            return false;
        }
        try {
            // 规范化路径
            File directory = new File(directoryPath);
            String canonicalPath = directory.getCanonicalPath();
            String saveFileCanonical = new File(Global.uploadRealPath).getCanonicalPath();

            // 验证目标路径是否在允许的目录下
            if (!canonicalPath.startsWith(saveFileCanonical)) {
                logger.error("[删除目录警告] 正在删除白名单外的目录" + saveFileCanonical);
                return false;
            }
            // 验证目录是否存在
            if (!directory.exists() || !directory.isDirectory()) {
                logger.error("[删除目录警告] 目标目录不存在");
                return false;
            }

            ProcessBuilder processBuilder;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", "rmdir", "/s", "/q", canonicalPath);
            } else {
                processBuilder = new ProcessBuilder("rm", "-rf", canonicalPath);
            }

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[删除目录输出] " + line);
            }
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }
}
