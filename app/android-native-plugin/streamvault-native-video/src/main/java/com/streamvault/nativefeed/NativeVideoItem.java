package com.streamvault.nativefeed;

import org.json.JSONException;
import org.json.JSONObject;

public class NativeVideoItem {
    public final String id;
    public final String title;
    public final String desc;
    public final String author;
    public final String authorId;
    public final String authorAvatarUrl;
    public final String authorDesc;
    public final String publishTime;
    public final String coverUrl;
    public final String mp4Url;
    public final String hlsUrl;
    public final String originalUrl;
    public final String sourceMode;
    public String favorite;

    public NativeVideoItem(
            String id,
            String title,
            String desc,
            String author,
            String authorId,
            String authorAvatarUrl,
            String authorDesc,
            String publishTime,
            String coverUrl,
            String mp4Url,
            String hlsUrl,
            String originalUrl,
            String sourceMode,
            String favorite
    ) {
        this.id = value(id);
        this.title = value(title);
        this.desc = value(desc);
        this.author = value(author);
        this.authorId = value(authorId);
        this.authorAvatarUrl = value(authorAvatarUrl);
        this.authorDesc = value(authorDesc);
        this.publishTime = value(publishTime);
        this.coverUrl = value(coverUrl);
        this.mp4Url = value(mp4Url);
        this.hlsUrl = value(hlsUrl);
        this.originalUrl = value(originalUrl);
        this.sourceMode = value(sourceMode).isEmpty() ? "prefer_mp4" : value(sourceMode);
        this.favorite = "1".equals(value(favorite)) ? "1" : "0";
    }

    public static NativeVideoItem fromJson(JSONObject obj, String sourceMode) throws JSONException {
        String id = first(obj, "id", "videoid");
        String title = first(obj, "title", "videoname");
        String desc = first(obj, "desc", "videodesc");
        String author = first(obj, "author", "videoauthor");
        String authorId = canonicalAuthorId(obj);
        String authorAvatarUrl = first(obj, "authorAvatarUrl", "authoravatar");
        String authorDesc = first(obj, "authorDesc");
        String publishTime = first(obj, "publishTime", "publishtime", "createTime");
        String cover = first(obj, "coverUrl", "videocover");
        String mp4 = first(obj, "mp4Url", "videounrealaddr", "playSrc");
        String hls = first(obj, "hlsUrl", "playurl");
        String originalUrl = first(obj, "originalUrl", "sourceurl", "originaladdress");
        String favorite = first(obj, "favorite");
        String itemSourceMode = first(obj, "sourceMode", "playbackSourceMode");
        return new NativeVideoItem(id, title, desc, author, authorId, authorAvatarUrl, authorDesc, publishTime, cover, mp4, hls, originalUrl, itemSourceMode.isEmpty() ? sourceMode : itemSourceMode, favorite);
    }

    private static String first(JSONObject obj, String... keys) {
        for (String key : keys) {
            String v = obj.optString(key, "");
            if (!v.isEmpty()) return v;
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

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }
}
