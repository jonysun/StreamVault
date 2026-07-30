package com.flower.spirit.utils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Duration;

import com.flower.spirit.process.ControlledProcessExecutor;

public class M3U8Downloader {
    private static final Pattern URL_PATTERN = Pattern.compile("(http|https)://[^\\s<>\"']+");
    private static final int THREAD_POOL_SIZE = 10;
    private static final int MAX_RETRIES = 3;
    private static final int TIMEOUT = 30000; // 30秒超时
    private static final ControlledProcessExecutor PROCESS_EXECUTOR = new ControlledProcessExecutor();

    /**
     * 下载并合并M3U8视频和音频
     * 
     * @param videoM3u8Url 视频M3U8地址
     * @param audioM3u8Url 音频M3U8地址
     * @param outputPath   输出文件路径
     */
    public void downloadAndMerge(String videoM3u8Url, String audioM3u8Url, String outputPath) throws Exception {
        // 创建临时目录
        Path tempDir = Files.createTempDirectory("m3u8_download");
        try {
            // 下载视频和音频片段
            String videoPath = downloadM3U8(videoM3u8Url, tempDir.resolve("video"));
            String audioPath = downloadM3U8(audioM3u8Url, tempDir.resolve("audio"));

            // 合并视频和音频
            mergeVideoAudio(videoPath, audioPath, outputPath);
        } finally {
            // 清理临时文件
            FileUtils.deleteDirectory(tempDir.toFile());
        }
    }

    /**
     * 下载M3U8文件并返回合并后的文件路径
     */
    private String downloadM3U8(String m3u8Url, Path outputDir) throws Exception {
        // 创建输出目录
        Files.createDirectories(outputDir);

        // 下载M3U8文件
        String m3u8Content = downloadContent(m3u8Url);
        List<String> segmentUrls = extractSegmentUrls(m3u8Content, m3u8Url);

        // 使用线程池下载片段
        ExecutorService executor = new ThreadPoolExecutor(THREAD_POOL_SIZE, THREAD_POOL_SIZE, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(THREAD_POOL_SIZE * 2),
                new ThreadPoolExecutor.CallerRunsPolicy());
        List<Path> segmentFiles = new ArrayList<>();

        try {
            for (int i = 0; i < segmentUrls.size(); i++) {
                final int index = i;
                final String segmentUrl = segmentUrls.get(i);
                final Path segmentFile = outputDir.resolve(String.format("%05d.ts", index));
                segmentFiles.add(segmentFile);

                executor.submit(() -> {
                    downloadSegment(segmentUrl, segmentFile);
                });
            }

            // 等待所有下载完成
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                executor.shutdownNow();
                throw new RuntimeException("M3U8 segment download timed out");
            }
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }

        // 合并片段
        Path mergedFile = outputDir.resolve("merged.ts");
        mergeSegments(segmentFiles, mergedFile);

        return mergedFile.toString();
    }

    /**
     * 下载单个片段
     */
    private void downloadSegment(String url, Path outputFile) {
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                URL segmentUrl = new URL(url);
                try (InputStream in = segmentUrl.openStream();
                        OutputStream out = Files.newOutputStream(outputFile)) {
                    IOUtils.copy(in, out);
                }
                return;
            } catch (Exception e) {
                retries++;
                if (retries == MAX_RETRIES) {
                    throw new RuntimeException("Failed to download segment: " + url, e);
                }
                try {
                    Thread.sleep(1000 * retries); // 重试延迟
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 提取M3U8中的片段URL
     */
    private List<String> extractSegmentUrls(String m3u8Content, String baseUrl) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(m3u8Content);
        while (matcher.find()) {
            String url = matcher.group();
            if (url.endsWith(".ts")) {
                urls.add(url);
            }
        }
        return urls;
    }

    /**
     * 下载URL内容
     */
    private String downloadContent(String url) throws Exception {
        try (InputStream in = new URL(url).openStream()) {
            return IOUtils.toString(in, "UTF-8");
        }
    }

    /**
     * 合并TS片段
     */
    private void mergeSegments(List<Path> segmentFiles, Path outputFile) throws IOException {
        try (OutputStream out = Files.newOutputStream(outputFile)) {
            for (Path segmentFile : segmentFiles) {
                Files.copy(segmentFile, out);
            }
        }
    }

    /**
     * 合并视频和音频
     */
    private void mergeVideoAudio(String videoPath, String audioPath, String outputPath) throws Exception {
        // 使用ffmpeg合并视频和音频
        ControlledProcessExecutor.Result result = PROCESS_EXECUTOR.execute(List.of(
                "ffmpeg", "-i", videoPath, "-i", audioPath, "-c:v", "copy", "-c:a", "aac", outputPath),
                Duration.ofMinutes(30), "ffmpeg-m3u8-merge");
        if (result.timedOut()) {
            throw new RuntimeException("FFmpeg merge timed out");
        }
        int exitCode = result.exitCode();

        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg merge failed with exit code: " + exitCode);
        }
    }
    
    public static void main(String[] args) throws Exception {
    	M3U8Downloader downloader = new M3U8Downloader();
    	downloader.downloadAndMerge(
    	    "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1742907723/ei/61TiZ8KWJ5eV1d8Pr9bq6AU/ip/91.229.132.230/id/7fb10315d035d298/itag/625/source/youtube/requiressl/yes/ratebypass/yes/pfa/1/wft/1/sgovp/clen%3D46448917%3Bdur%3D71.571%3Bgir%3Dyes%3Bitag%3D313%3Blmt%3D1728395960407763/rqh/1/hls_chunk_host/rr1---sn-i3belnl7.googlevideo.com/xpc/EgVo2aDSNQ%3D%3D/met/1742886123,/mh/Y7/mm/31,29/mn/sn-i3belnl7,sn-i3b7knld/ms/au,rdu/mv/m/mvi/1/pl/24/rms/au,au/initcwndbps/2536250/bui/AccgBcP7ygLr3Km_XZI4xUxcSHmWjzR1qbju7EscnF1rp1TA0iBnzmr3Yg2vtys9uRQsot_wxc9ZV6xR/spc/_S3wKlfQU85Sz3Hl0d62nnbt5K7HOpTcJ0QttK424wXaBJDadVpiAmxmyRM/vprv/1/playlist_type/DVR/dover/13/txp/4532434/mt/1742885589/fvip/2/short_key/1/keepalive/yes/sparams/expire,ei,ip,id,itag,source,requiressl,ratebypass,pfa,wft,sgovp,rqh,xpc,bui,spc,vprv,playlist_type/sig/AJfQdSswRAIgVHfbR7AX19BtW_akrJbBSs4H1UZa-bmtWGRwe-XOrjcCIFV4fnhoHU-Hw8odwN6XDII-jOZ_CG5f4sQ9BWnEbRBS/lsparams/hls_chunk_host,met,mh,mm,mn,ms,mv,mvi,pl,rms,initcwndbps/lsig/AFVRHeAwRQIhAJkH60l9PpK29vRel4lAGKec5wEKKDI8mqfuL_E5AqyUAiBkilr0-s7wqRSXfWGlVXcQYKBdbwRfApjQ3MS4ISkgtg%3D%3D/playlist/index.m3u8",
    	    "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1742907723/ei/61TiZ8KWJ5eV1d8Pr9bq6AU/ip/91.229.132.230/id/7fb10315d035d298/itag/234/source/youtube/requiressl/yes/ratebypass/yes/pfa/1/goi/133/sgoap/clen%3D1160068%3Bdur%3D71.633%3Bgir%3Dyes%3Bitag%3D140%3Blmt%3D1694134193119898/rqh/1/hls_chunk_host/rr1---sn-i3belnl7.googlevideo.com/xpc/EgVo2aDSNQ%3D%3D/met/1742886123,/mh/Y7/mm/31,29/mn/sn-i3belnl7,sn-i3b7knld/ms/au,rdu/mv/m/mvi/1/pl/24/rms/au,au/initcwndbps/2536250/bui/AccgBcP7ygLr3Km_XZI4xUxcSHmWjzR1qbju7EscnF1rp1TA0iBnzmr3Yg2vtys9uRQsot_wxc9ZV6xR/spc/_S3wKlfQU85Sz3Hl0d62nnbt5K7HOpTcJ0QttK424wXaBJDadVpiAmxmyRM/vprv/1/playlist_type/DVR/dover/13/txp/4532434/mt/1742885589/fvip/2/short_key/1/keepalive/yes/sparams/expire,ei,ip,id,itag,source,requiressl,ratebypass,pfa,goi,sgoap,rqh,xpc,bui,spc,vprv,playlist_type/sig/AJfQdSswRAIgErTM4W_SPi5Pm-sTMtXd4firdsQrXKO59PGUHa5G7_kCIHo7eBLWqHSn-E6gdrirnfN2OOXjwLSETpMxKdg6es1j/lsparams/hls_chunk_host,met,mh,mm,mn,ms,mv,mvi,pl,rms,initcwndbps/lsig/AFVRHeAwRAIgSDBCV8iJmGi_SCXkROixIZav5t1jUkVeUci_KKsQqc8CIAkG9-mlf7EF2EG6ZbBFTo9BNhtvTquxwo0SknyHXe2Q/playlist/index.m3u8",
    	    "output.mp4"
    	);
	}
}
