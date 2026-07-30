package com.flower.spirit.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import com.flower.spirit.config.Global;
import com.flower.spirit.process.ControlledProcessExecutor;
import com.flower.spirit.service.DouyinFetchRequest;

public class CommandUtil {

    private static Logger logger = LoggerFactory.getLogger(CommandUtil.class);

    static Pattern pattern = Pattern.compile("\"(.*?)\"");

    private static final Pattern SENSITIVE_COOKIE_PATTERN = Pattern.compile(
            "(?i)(sessionid(?:_ss)?|msToken|ttwid|odin_tt|passport_csrf_token)=([^;\\s]+)");

    private static final ThreadLocal<Integer> LAST_F2_EXIT_CODE = new ThreadLocal<>();

    private static final ThreadLocal<Long> LAST_F2_DURATION_MS = new ThreadLocal<>();

    private static final long INCREMENTAL_BASE_TIMEOUT_SECONDS = 30;
    private static final long INCREMENTAL_PER_PAGE_TIMEOUT_SECONDS = 15;
    private static final long INCREMENTAL_MIN_TIMEOUT_SECONDS = 60;
    private static final long INCREMENTAL_MAX_TIMEOUT_SECONDS = 15 * 60;
    private static final long PROCESS_STOP_WAIT_SECONDS = 2;
    private static final long OUTPUT_READER_JOIN_MILLIS = 2000;
    private static final long LEGACY_F2_TIMEOUT_SECONDS = 15 * 60;
    private static final ControlledProcessExecutor CONTROLLED_PROCESS_EXECUTOR = new ControlledProcessExecutor();

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
        if (cookie == null || cookie.isBlank()) {
            LAST_F2_EXIT_CODE.set(-2);
            LAST_F2_DURATION_MS.set(0L);
            logger.warn("[F2] skipped func={} because no platform cookie is currently available", fuc);
            return "";
        }

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
        long startMs = System.currentTimeMillis();
        try {
            logger.info("[F2] process launch");
            ControlledProcessExecutor.Result result = CONTROLLED_PROCESS_EXECUTOR.execute(cmdList,
                    java.time.Duration.ofSeconds(LEGACY_F2_TIMEOUT_SECONDS), "legacy-f2", 128 * 1024);
            String output = result.diagnosticOutput();
            int exitCode = result.exitCode();
            if (result.timedOut()) {
                output = output + "\nprocess timeout after " + LEGACY_F2_TIMEOUT_SECONDS + " seconds";
                logger.error("[F2] process timeout operation=legacy-f2 durationMs={}",
                        result.duration().toMillis());
            }
            LAST_F2_EXIT_CODE.set(exitCode);
            LAST_F2_DURATION_MS.set(System.currentTimeMillis() - startMs);
            logger.info("[F2] process finished exitCode={} outputLength={}", exitCode, output.length());
            if (output.length() == 0) {
                logger.warn("[F2] process returned empty output");
            } else if (suppressOutputPreview) {
                logger.info("[F2] output preview suppressed for signed media metadata");
            } else {
                String preview = previewOutput(output);
                logger.info("[F2] output preview={}", preview);
                if (exitCode != 0) {
                    logger.error("[F2] process failed exitCode={} output={}", exitCode, preview);
                    if (Global.f2logfullonerror) {
                        logger.error("[F2][FAIL][OUTPUT_BEGIN]\n{}\n[F2][FAIL][OUTPUT_END]", maskSensitiveOutput(output));
                    }
                }
            }
            return output.trim();
        } catch (IOException e) {
            logger.error("[F2] process execution failed type={}", e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("[F2] process execution interrupted");
        }
        LAST_F2_EXIT_CODE.set(-1);
        LAST_F2_DURATION_MS.set(System.currentTimeMillis() - startMs);
        return "";
    }

    public static F2CommandResult f2IncrementalFetch(DouyinFetchRequest request,
            Path knownIdsFile, Path outputFile) {
        String cookie = request.cookie() == null
                ? nullToEmpty(Global.tiktokCookie) : nullToEmpty(request.cookie());
        List<String> command = buildIncrementalCommand(request, knownIdsFile, outputFile, cookie);
        logger.info("[F2] start func=fetch_douyin_list_incremental uid={} knownIdsFile={} output={} "
                        + "cookiePresent={} cookie=***masked***",
                request.secUserId(), knownIdsFile, outputFile, !cookie.isBlank());
        logger.info("[F2] command={}", buildSafeCommandString(command));
        return runIncrementalCommand(command, incrementalTimeoutSeconds(request),
                CommandUtil::startIncrementalProcess);
    }

    static List<String> buildIncrementalCommand(DouyinFetchRequest request,
            Path knownIdsFile, Path outputFile, String cookie) {
        return List.of(
                "/opt/venv/bin/python3", "/home/app/script/douyin.py",
                "fetch_douyin_list_incremental", "--cookie", nullToEmpty(cookie),
                "--sec_user_id", request.secUserId(),
                "--known_ids_file", knownIdsFile.toString(),
                "--last_seen_publish_time", nullToEmpty(request.lastSeenPublishTime()),
                "--known_boundary", String.valueOf(request.knownBoundary()),
                "--max_pages", String.valueOf(request.maxPages()),
                "--empty_page_limit", String.valueOf(request.emptyPageLimit()),
                "--mode", request.mode().name().toLowerCase(Locale.ROOT),
                "--max_items", String.valueOf(request.maxItems()),
                "--output", outputFile.toString());
    }

    static long incrementalTimeoutSeconds(DouyinFetchRequest request) {
        long calculated = INCREMENTAL_BASE_TIMEOUT_SECONDS
                + Math.max(0, request.maxPages()) * INCREMENTAL_PER_PAGE_TIMEOUT_SECONDS;
        return Math.max(INCREMENTAL_MIN_TIMEOUT_SECONDS,
                Math.min(INCREMENTAL_MAX_TIMEOUT_SECONDS, calculated));
    }

    private static Process startIncrementalProcess(List<String> command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        return processBuilder.start();
    }

    static F2CommandResult runIncrementalCommand(List<String> command,
            long timeoutSeconds, IncrementalProcessStarter processStarter) {
        StringBuffer output = new StringBuffer();
        Process process = null;
        Thread outputReader = null;
        long startMs = System.currentTimeMillis();
        int exitCode = -1;
        boolean completed = false;
        boolean interrupted = false;
        try {
            process = processStarter.start(command);
            outputReader = startIncrementalOutputReader(process, output);
            completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (completed) {
                exitCode = process.exitValue();
            } else {
                appendProcessDiagnostic(output,
                        "process timeout after " + timeoutSeconds + " seconds");
            }
        } catch (IOException e) {
            appendProcessDiagnostic(output,
                    "process launch failed: " + e.getClass().getSimpleName());
            logger.error("[F2] incremental process launch failed type={}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            interrupted = true;
            appendProcessDiagnostic(output, "process interrupted");
            logger.error("[F2] incremental process interrupted");
        } finally {
            if (process != null && !completed) {
                interrupted |= stopIncrementalProcess(process, output, interrupted);
            }
            if (outputReader != null) {
                interrupted |= finishIncrementalOutputReader(outputReader, process);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        long durationMs = System.currentTimeMillis() - startMs;
        LAST_F2_EXIT_CODE.set(exitCode);
        LAST_F2_DURATION_MS.set(durationMs);
        String safeOutput = sanitizeF2Output(output.toString().trim(), command);
        logger.info("[F2] incremental process finished exitCode={} durationMs={} outputPreview={}",
                exitCode, durationMs, boundedOutput(safeOutput));
        return new F2CommandResult(exitCode, safeOutput, durationMs);
    }

    private static Thread startIncrementalOutputReader(Process process, StringBuffer output) {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendProcessDiagnostic(output, line);
                }
            } catch (IOException error) {
                appendProcessDiagnostic(output,
                        "process output read failed: " + error.getClass().getSimpleName());
            }
        }, "douyin-incremental-output-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        return readerThread;
    }

    private static boolean stopIncrementalProcess(Process process,
            StringBuffer output, boolean forceImmediately) {
        boolean interrupted = forceImmediately;
        boolean gracefulWaitCompleted = false;
        process.destroy();
        if (!forceImmediately) {
            try {
                gracefulWaitCompleted = process.waitFor(
                        PROCESS_STOP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
                forceImmediately = true;
            }
        }

        if (forceImmediately || !gracefulWaitCompleted || process.isAlive()) {
            process.destroyForcibly();
            boolean forcedWaitCompleted = false;
            try {
                forcedWaitCompleted = process.waitFor(
                        PROCESS_STOP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
            }
            boolean stillAlive = process.isAlive();
            boolean forcedCleanupConfirmed = forcedWaitCompleted && !stillAlive;
            if (!forcedCleanupConfirmed && stillAlive) {
                appendProcessDiagnostic(output,
                        "process cleanup failed: still alive after force");
            }
        }
        return interrupted;
    }

    private static boolean finishIncrementalOutputReader(Thread outputReader,
            Process process) {
        boolean interrupted = false;
        try {
            outputReader.join(OUTPUT_READER_JOIN_MILLIS);
        } catch (InterruptedException error) {
            interrupted = true;
        }
        if (outputReader.isAlive()) {
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // The process stream may already be closed by process termination.
            }
            try {
                outputReader.join(OUTPUT_READER_JOIN_MILLIS);
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static void appendProcessDiagnostic(StringBuffer output, String message) {
        synchronized (output) {
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(message);
        }
    }

    @FunctionalInterface
    interface IncrementalProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    public static String sanitizeF2Output(String output) {
        return sanitizeF2OutputWithCookie(output, nullToEmpty(Global.tiktokCookie));
    }

    private static String sanitizeF2OutputWithCookie(String output, String cookie) {
        if (output == null) {
            return null;
        }
        String safe = redactExactCookieSecrets(output, cookie);
        Matcher matcher = SENSITIVE_COOKIE_PATTERN.matcher(safe);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement(matcher.group(1) + "=***masked***"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    static String sanitizeF2Output(String output, List<String> command) {
        if (output == null) {
            return null;
        }
        String cookie = commandArgument(command, "--cookie");
        String safe = sanitizeF2OutputWithCookie(output, cookie);
        String globalCookie = nullToEmpty(Global.tiktokCookie);
        if (!globalCookie.equals(cookie)) {
            safe = sanitizeF2OutputWithCookie(safe, globalCookie);
        }
        return safe;
    }

    private static String redactExactCookieSecrets(String output, String cookie) {
        String safe = output;
        if (cookie != null && !cookie.isEmpty()) {
            List<String> secrets = new ArrayList<>();
            secrets.add(cookie);
            for (String part : cookie.split(";")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    continue;
                }
                secrets.add(token);
                int equals = token.indexOf('=');
                if (equals >= 0 && equals + 1 < token.length()) {
                    String value = token.substring(equals + 1).trim();
                    if (isPlausibleBareCookieSecret(value)) {
                        secrets.add(value);
                    }
                }
            }
            secrets.sort((left, right) -> Integer.compare(right.length(), left.length()));
            for (String secret : secrets) {
                if (!secret.isEmpty()) {
                    safe = safe.replace(secret, "***masked***");
                }
            }
        }
        return safe;
    }

    private static boolean isPlausibleBareCookieSecret(String value) {
        return value.length() >= 6 && !value.chars().allMatch(Character::isDigit);
    }

    private static String commandArgument(List<String> command, String name) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if (name.equals(command.get(index))) {
                return command.get(index + 1);
            }
        }
        return null;
    }

    private static String boundedOutput(String output) {
        if (output == null) {
            return "null";
        }
        int limit = Global.f2logmaxpreview > 0 ? Global.f2logmaxpreview : 1000;
        return output.length() <= limit ? output : output.substring(0, limit);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record F2CommandResult(int exitCode, String output, long durationMs) {
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

    static String buildSafeCommandString(List<String> cmdList) {
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
