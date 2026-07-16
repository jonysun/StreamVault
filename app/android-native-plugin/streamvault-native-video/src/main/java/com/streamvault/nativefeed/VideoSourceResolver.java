package com.streamvault.nativefeed;

public final class VideoSourceResolver {
    private VideoSourceResolver() {}

    public static String resolve(NativeVideoItem item) {
        if (item == null) return "";
        String mp4 = item.mp4Url == null ? "" : item.mp4Url;
        String hls = item.hlsUrl == null ? "" : item.hlsUrl;
        String mode = item.sourceMode == null ? "prefer_mp4" : item.sourceMode;

        switch (mode) {
            case "mp4_only":
                return mp4;
            case "hls_only":
                return hls;
            case "prefer_hls":
                return !hls.isEmpty() ? hls : mp4;
            case "prefer_mp4":
            default:
                return !mp4.isEmpty() ? mp4 : hls;
        }
    }

    public static String fallbackSource(NativeVideoItem item, String failedUrl) {
        if (item == null || failedUrl == null) return "";
        String mode = item.sourceMode == null ? "prefer_mp4" : item.sourceMode;
        if ("mp4_only".equals(mode) || "hls_only".equals(mode)) return "";

        String mp4 = item.mp4Url == null ? "" : item.mp4Url;
        String hls = item.hlsUrl == null ? "" : item.hlsUrl;
        if (sameSource(failedUrl, hls) && !mp4.isEmpty()) return mp4;
        if (sameSource(failedUrl, mp4) && !hls.isEmpty()) return hls;
        return "";
    }

    public static boolean isHlsSource(String value) {
        return value != null && value.toLowerCase().matches(".*\\.m3u8(\\?.*|$)");
    }

    private static boolean sameSource(String left, String right) {
        return right != null && !right.isEmpty() && left.equals(right);
    }
}
