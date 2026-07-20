package com.streamvault.nativefeed;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NativeFeedRepository {
    public interface Callback {
        void onResult(boolean ok, List<NativeVideoItem> videos, String message);
    }

    public static class Request {
        public final String url;
        public final String body;

        Request(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void findAll(String serveraddr, String serverport, String token, String author, NativeFeedSortMode sortMode, String randomSeed, String sourceMode, Callback callback) {
        executor.execute(() -> {
            try {
                Request request = buildFindAllRequest(serveraddr, serverport, token, author, sortMode, randomSeed);
                HttpURLConnection conn = (HttpURLConnection) new URL(request.url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(request.body.getBytes(StandardCharsets.UTF_8));
                }
                String json = read(conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream());
                List<NativeVideoItem> videos = parseVideoList(json, sourceMode, serveraddr, serverport, token);
                callback.onResult(true, videos, "");
            } catch (Exception e) {
                callback.onResult(false, new ArrayList<>(), e.getMessage() == null ? "load failed" : e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public static Request buildFindAllRequest(String serveraddr, String serverport, String token, String author, NativeFeedSortMode sortMode, String randomSeed) {
        String endpoint = trimRight(serveraddr, "/") + ":" + value(serverport) + "/api/findAllVideos?token=" + enc(token);
        List<String> parts = new ArrayList<>();
        if (!value(author).isEmpty()) {
            parts.add("videoauthor=" + enc(author));
        }
        NativeFeedSortMode mode = sortMode == null ? NativeFeedSortMode.DESC : sortMode;
        if (mode == NativeFeedSortMode.RANDOM) {
            parts.add("randomMode=1");
            if (!value(randomSeed).isEmpty()) parts.add("randomSeed=" + enc(randomSeed));
        } else {
            parts.add("sortField=publishtime");
            parts.add("sortOrder=" + enc(mode.value()));
        }
        return new Request(endpoint, join(parts));
    }

    public static List<NativeVideoItem> parseVideoList(String json, String sourceMode, String serveraddr, String serverport, String token) throws Exception {
        List<NativeVideoItem> out = new ArrayList<>();
        JSONObject root = JSON.parseObject(json == null ? "{}" : json);
        JSONArray array = findVideoArray(root);
        if (array == null) return out;
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj == null) continue;
            out.add(normalize(fromJson(obj, sourceMode), serveraddr, serverport, token));
        }
        return out;
    }

    private static JSONArray findVideoArray(JSONObject root) {
        JSONArray direct = firstArray(root, "record", "records", "list", "content", "data", "rows");
        if (direct != null) return direct;
        JSONObject record = objectValue(root, "record");
        if (record != null) {
            JSONArray nested = firstArray(record, "records", "list", "content", "data", "rows");
            if (nested != null) return nested;
        }
        JSONObject data = objectValue(root, "data");
        if (data != null) {
            return firstArray(data, "records", "list", "content", "data", "rows");
        }
        return null;
    }

    private static JSONArray firstArray(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.get(key);
            JSONArray array = value instanceof JSONArray ? (JSONArray) value : null;
            if (array != null) return array;
        }
        return null;
    }

    private static JSONObject objectValue(JSONObject object, String key) {
        Object value = object.get(key);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    private static NativeVideoItem fromJson(JSONObject obj, String sourceMode) {
        String itemSourceMode = first(obj, "sourceMode", "playbackSourceMode");
        return new NativeVideoItem(
                first(obj, "id", "videoid"),
                first(obj, "title", "videoname"),
                first(obj, "desc", "videodesc"),
                first(obj, "author", "videoauthor"),
                canonicalAuthorId(obj),
                first(obj, "authorAvatarUrl", "authoravatar"),
                first(obj, "authorDesc"),
                first(obj, "publishTime", "publishtime", "createTime"),
                first(obj, "coverUrl", "videocover"),
                first(obj, "mp4Url", "videounrealaddr", "playSrc"),
                first(obj, "hlsUrl", "playurl"),
                first(obj, "originalUrl", "sourceurl", "originaladdress"),
                itemSourceMode.isEmpty() ? sourceMode : itemSourceMode,
                first(obj, "favorite")
        );
    }

    private static String first(JSONObject obj, String... keys) {
        for (String key : keys) {
            String value = obj.getString(key);
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String canonicalAuthorId(JSONObject obj) {
        String platform = first(obj, "platform", "videoplatform").toLowerCase();
        boolean douyin = "douyin".equals(platform) || platform.contains("抖音");
        String secUid = first(obj, "secuid");
        if (secUid.startsWith("MS4")) return secUid;
        String authorId = first(obj, "authorId", "authoruid");
        return !douyin || authorId.startsWith("MS4") ? authorId : "";
    }

    private static NativeVideoItem normalize(NativeVideoItem item, String serveraddr, String serverport, String token) {
        return new NativeVideoItem(
                item.id,
                item.title,
                item.desc,
                item.author,
                item.authorId,
                UrlUtils.normalizePath(item.authorAvatarUrl, serveraddr, serverport, token),
                item.authorDesc,
                item.publishTime,
                UrlUtils.normalizePath(item.coverUrl, serveraddr, serverport, token),
                UrlUtils.normalizePath(item.mp4Url, serveraddr, serverport, token),
                UrlUtils.normalizePath(item.hlsUrl, serveraddr, serverport, token),
                item.originalUrl,
                item.sourceMode,
                item.favorite
        );
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append('&');
            out.append(part);
        }
        return out.toString();
    }

    private static String enc(String raw) {
        try {
            return URLEncoder.encode(value(raw), "UTF-8");
        } catch (Exception e) {
            return value(raw);
        }
    }

    private static String value(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String trimRight(String value, String suffix) {
        String out = value(value);
        while (out.endsWith(suffix)) out = out.substring(0, out.length() - suffix.length());
        return out;
    }
}
