package com.flower.spirit.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flower.spirit.config.Global;

import okhttp3.*;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KuaishouParser {
	public static final String USER_AGENT = Global.useragent ==null? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36":Global.useragent;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient client = new OkHttpClient();

    public static class VideoInfo {
        private String title;
        private String author;
        private String authorId;
        private String authorAvatar;
        private String authorHomepage;
        private String videoId;
        private Integer duration;
        private Long views;
        private Long likes;
        private String coverUrl;
        private String videoUrl;
        private String h265Url;
        private Long timestamp;
        private String sourceUrl;

        // Getters and Setters
        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public String getAuthorId() {
            return authorId;
        }

        public void setAuthorId(String authorId) {
            this.authorId = authorId;
        }

        public String getAuthorAvatar() {
            return authorAvatar;
        }

        public void setAuthorAvatar(String authorAvatar) {
            this.authorAvatar = authorAvatar;
        }

        public String getAuthorHomepage() {
            return authorHomepage;
        }

        public void setAuthorHomepage(String authorHomepage) {
            this.authorHomepage = authorHomepage;
        }

        public String getVideoId() {
            return videoId;
        }

        public void setVideoId(String videoId) {
            this.videoId = videoId;
        }

        public Integer getDuration() {
            return duration;
        }

        public void setDuration(Integer duration) {
            this.duration = duration;
        }

        public Long getViews() {
            return views;
        }

        public void setViews(Long views) {
            this.views = views;
        }

        public Long getLikes() {
            return likes;
        }

        public void setLikes(Long likes) {
            this.likes = likes;
        }

        public String getCoverUrl() {
            return coverUrl;
        }

        public void setCoverUrl(String coverUrl) {
            this.coverUrl = coverUrl;
        }

        public String getVideoUrl() {
            return videoUrl;
        }

        public void setVideoUrl(String videoUrl) {
            this.videoUrl = videoUrl;
        }

        public String getH265Url() {
            return h265Url;
        }

        public void setH265Url(String h265Url) {
            this.h265Url = h265Url;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public void setSourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
        }

        @Override
        public String toString() {
            try {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
            } catch (Exception e) {
                return super.toString();
            }
        }
    }

    @SuppressWarnings("deprecation")
	public static VideoInfo parseVideo(String url,String cookie) throws IOException {
        if (!url.startsWith("https://v.kuaishou.com/") && !url.startsWith("https://www.kuaishou.com/")) {
            throw new IllegalArgumentException("无效的快手视频链接");
        }

        // 获取重定向后的真实URL
        String realUrl = getRedirectUrl(url);
//        System.out.println("Real URL: " + realUrl);

        // 提取视频ID
        String photoId = extractPhotoId(realUrl);
        if (photoId == null) {
            throw new IllegalArgumentException("无法从URL中提取视频ID");
        }

        // 构造GraphQL请求
        String graphqlQuery = "query visionVideoDetail($photoId: String, $page: String) {"
                + "visionVideoDetail(photoId: $photoId, page: $page) {"
                + "status type photo {"
                + "id duration caption likeCount realLikeCount viewCount coverUrl photoUrl photoH265Url "
                + "manifest { adaptationSet { id duration representation { id url qualityType qualityLabel } } } "
                + "timestamp"
                + "} tags { type name } author { id name following headerUrl __typename }"
                + "} }";

        Map<String, Object> variables = new HashMap<>();
        variables.put("photoId", photoId);
        variables.put("page", "detail");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("operationName", "visionVideoDetail");
        requestBody.put("variables", variables);
        requestBody.put("query", graphqlQuery);

        // 发送GraphQL请求
        Request.Builder requestBuilder = new Request.Builder()
                .url("https://www.kuaishou.com/graphql")
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        objectMapper.writeValueAsString(requestBody)))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.kuaishou.com")
                .header("Referer", "https://www.kuaishou.com/");

        // 如果提供了cookie，则添加到请求头
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.header("Cookie", cookie);
        }
        Request request = requestBuilder.build();
        String responseBody;
        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) {
                throw new IOException("Kuaishou returned an empty response");
            }
            responseBody = response.body().string();
        }

        return parseResponse(responseBody, realUrl);
    }

    public static VideoInfo parseResponse(String responseBody, String canonicalUrl) throws IOException {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new IOException("Kuaishou returned no response data");
        }
        JsonNode data = objectMapper.readTree(responseBody);

        // 检查是否需要验证码
        if (data.has("errors")) {
            JsonNode errors = data.get("errors");
            for (JsonNode error : errors) {
                if ("Need captcha".equals(error.get("message").asText())) {
                    String captchaUrl = data.path("data").path("captcha").path("url").asText();
                    throw new IOException("需要验证码，请访问链接完成验证：" + captchaUrl);
                }
            }
        }

        JsonNode visionVideoDetail = data.path("data").path("visionVideoDetail");
        if (visionVideoDetail.isNull()) {
            throw new IOException("无法获取视频信息，可能需要验证码");
        }

        if (visionVideoDetail.path("status").asInt() != 1) {
            throw new IOException("无法获取视频信息");
        }

        // 解析视频信息
        JsonNode photo = visionVideoDetail.get("photo");
        JsonNode author = visionVideoDetail.get("author");

        VideoInfo videoInfo = new VideoInfo();
        videoInfo.setTitle(photo.get("caption").asText());
        videoInfo.setAuthor(author.get("name").asText());
        videoInfo.setAuthorId(author.get("id").asText());
        videoInfo.setAuthorAvatar(author.path("headerUrl").asText(null));
        videoInfo.setAuthorHomepage("https://www.kuaishou.com/profile/" + videoInfo.getAuthorId());
        videoInfo.setVideoId(photo.get("id").asText());
        videoInfo.setDuration(photo.get("duration").asInt());
        videoInfo.setViews(photo.get("viewCount").asLong());
        videoInfo.setLikes(
                photo.has("realLikeCount") ? photo.get("realLikeCount").asLong() : photo.get("likeCount").asLong());
        videoInfo.setCoverUrl(photo.get("coverUrl").asText());
        videoInfo.setVideoUrl(getBestVideoUrl(photo));
        videoInfo.setH265Url(photo.has("photoH265Url") ? photo.get("photoH265Url").asText() : null);
        videoInfo.setTimestamp(photo.get("timestamp").asLong());
        videoInfo.setSourceUrl(canonicalUrl);

        return videoInfo;
    }

    private static String getRedirectUrl(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        String realUrl;
        try (Response response = client.newCall(request).execute()) {
            realUrl = response.request().url().toString();
        }

        if (!realUrl.contains("short-video")) {
            request = new Request.Builder()
                    .url(realUrl)
                    .header("User-Agent", USER_AGENT)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                realUrl = response.request().url().toString();
            }
        }

        return realUrl;
    }

    private static String extractPhotoId(String url) {
        try {
            URL parsedUrl = new URL(url);
            String query = parsedUrl.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && "photoId".equals(pair[0])) {
                        return pair[1];
                    }
                }
            }

            Pattern pattern = Pattern.compile("/short-video/([a-zA-Z0-9]+)(?:\\?|$)");
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String getBestVideoUrl(JsonNode photo) {
        String videoUrl = photo.get("photoUrl").asText();
        JsonNode manifest = photo.path("manifest");

        if (!manifest.isMissingNode() && manifest.has("adaptationSet")) {
            try {
                JsonNode representations = manifest.path("adaptationSet").get(0).path("representation");
                int maxQualityType = 0;
                String bestUrl = videoUrl;

                for (JsonNode rep : representations) {
                    int qualityType = rep.get("qualityType").asInt();
                    if (qualityType > maxQualityType) {
                        maxQualityType = qualityType;
                        bestUrl = rep.get("url").asText();
                    }
                }
                return bestUrl;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return videoUrl;
    }

    public static void main(String[] args) {
        try {
            VideoInfo videoInfo = parseVideo("https://v.kuaishou.com/2vsYZuu",null);
            System.out.println(videoInfo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
