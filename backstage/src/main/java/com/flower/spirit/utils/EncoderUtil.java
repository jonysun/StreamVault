package com.flower.spirit.utils;

import java.util.concurrent.TimeUnit;

import com.flower.spirit.config.Global;

public class EncoderUtil {

    public static void checkAndSetEncoder() {
        try {
            // 检查是否支持 h264_qsv, hevc_qsv, vp9_qsv, vaapi
            String encoder = getSupportedEncoder();

            // 将选中的编码器赋值给 Global.encoder
            Global.encoder = encoder;

            System.out.println("检测到编码器: " + encoder);
        } catch (Exception e) {
            // 出现异常时选择 libx264
            Global.encoder = "libx264";
            System.err.println("检测编码器失败，默认使用 libx264: " + e.getMessage());
        }
    }

    private static String getSupportedEncoder() {
        // 检查 h264_qsv, hevc_qsv, vp9_qsv, vaapi 的支持情况
        if (isHardwareAcceleratedEncoderSupported("h264_qsv")) {
            return "h264_qsv";
        } else if (isHardwareAcceleratedEncoderSupported("hevc_qsv")) {
            return "hevc_qsv";
        } else if (isHardwareAcceleratedEncoderSupported("vp9_qsv")) {
            return "vp9_qsv";
        } else if (isHardwareAcceleratedEncoderSupported("h264_vaapi")) {
            return "h264_vaapi";
        } else if (isHardwareAcceleratedEncoderSupported("hevc_vaapi")) {
            return "hevc_vaapi";
        } else if (isHardwareAcceleratedEncoderSupported("vp9_vaapi")) {
            return "vp9_vaapi";
        } else {
            // 如果没有硬件加速支持，返回默认的软件编码器
            return "libx264";
        }
    }

    private static boolean isHardwareAcceleratedEncoderSupported(String encoderName) {
        Process process = null;
        try {
            // 执行 ffmpeg 命令来检查是否支持硬件加速编码器
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", buildFFmpegCommand(encoderName));
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", buildFFmpegCommand(encoderName));
            }
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = processBuilder.start();

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                return false;
            }

            return process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String buildFFmpegCommand(String encoderName) {
        // 生成检测硬件加速的命令
        if (encoderName.equals("h264_qsv") || encoderName.equals("hevc_qsv") || encoderName.equals("vp9_qsv")) {
            return "ffmpeg -init_hw_device qsv=hw -filter_hw_device hw -f lavfi -i testsrc=duration=1:size=1280x720:rate=30 -vf 'format=nv12,hwupload' -c:v " + encoderName + " -f null -";
        } else if (encoderName.equals("h264_vaapi") || encoderName.equals("hevc_vaapi") || encoderName.equals("vp9_vaapi")) {
            return "ffmpeg -init_hw_device vaapi=hw -filter_hw_device hw -f lavfi -i testsrc=duration=1:size=1280x720:rate=30 -vf 'format=nv12,hwupload' -c:v " + encoderName + " -f null -";
        } else {
            return "";
        }
    }

    public static void main(String[] args) {
        System.out.println(Global.encoder);
        checkAndSetEncoder();
        System.out.println(Global.encoder);
    }
}
