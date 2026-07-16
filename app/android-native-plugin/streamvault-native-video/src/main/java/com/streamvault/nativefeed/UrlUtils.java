package com.streamvault.nativefeed;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public final class UrlUtils {
    private UrlUtils() {}

    public static String normalizePath(String rawPath, String server, String port, String token) {
        if (rawPath == null || rawPath.trim().isEmpty()) return "";
        if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) return rawPath;

        String path = rawPath.replace("\\", "/");
        if (!path.startsWith("/")) path = "/" + path;

        String[] parts = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) encoded.append('/');
            encoded.append(urlEncode(parts[i]));
        }

        return server + ":" + port + encoded + "?apptoken=" + token;
    }

    private static String urlEncode(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return segment;
        }
    }
}
