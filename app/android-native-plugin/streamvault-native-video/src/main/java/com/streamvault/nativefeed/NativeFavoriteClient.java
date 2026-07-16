package com.streamvault.nativefeed;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NativeFavoriteClient {
    public interface Callback {
        void onResult(boolean ok, String favorite, String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void update(String serveraddr, String serverport, String token, String id, String favorite, Callback callback) {
        executor.execute(() -> {
            try {
                String endpoint = serveraddr + ":" + serverport + "/api/updateVideoFavorite?token=" + enc(token);
                String body = "id=" + enc(id) + "&favorite=" + enc(favorite);
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                String resCode = root.optString("resCode", "");
                String status = root.optString("status", "");
                boolean ok = "000001".equals(resCode) || "success".equals(status) || "成功".equals(status);
                JSONObject data = root.optJSONObject("record");
                if (data == null) data = root.optJSONObject("data");
                String actual = data == null ? favorite : data.optString("favorite", favorite);
                String message = root.optString("message", root.optString("resMsg", root.optString("msg", ok ? "操作成功" : "操作失败")));
                post(callback, ok, actual, message);
            } catch (Exception e) {
                post(callback, false, favorite, e.getMessage() == null ? "收藏失败" : e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void post(Callback callback, boolean ok, String favorite, String message) {
        mainHandler.post(() -> callback.onResult(ok, favorite, message));
    }

    private String enc(String raw) throws Exception {
        return URLEncoder.encode(raw == null ? "" : raw, "UTF-8");
    }
}
