package com.streamvault.nativefeed;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

final class NativeFeedLaunchOptions {
    private NativeFeedLaunchOptions() {}

    static boolean hasLaunchData(JSONObject options) {
        if (options == null) return false;
        JSONArray videos = options.getJSONArray("videos");
        if (videos != null && !videos.isEmpty()) return true;
        return hasServerConfig(options);
    }

    private static boolean hasServerConfig(JSONObject options) {
        return !value(options.getString("serveraddr")).isEmpty()
                && !value(options.getString("serverport")).isEmpty()
                && !value(options.getString("servertoken")).isEmpty();
    }

    private static String value(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
