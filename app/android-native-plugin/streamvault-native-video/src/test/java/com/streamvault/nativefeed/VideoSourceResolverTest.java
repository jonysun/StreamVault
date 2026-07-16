package com.streamvault.nativefeed;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VideoSourceResolverTest {
    @Test
    public void preferHlsAcceptsM3u8WithQuery() {
        NativeVideoItem item = item("prefer_hls", "http://cdn/video.mp4", "http://cdn/hls/index.m3u8?apptoken=abc");

        assertEquals("http://cdn/hls/index.m3u8?apptoken=abc", VideoSourceResolver.resolve(item));
    }

    @Test
    public void hlsOnlyDoesNotUseMp4Fallback() {
        NativeVideoItem item = item("hls_only", "http://cdn/video.mp4", "");

        assertEquals("", VideoSourceResolver.resolve(item));
    }

    @Test
    public void preferMp4FallsBackToHlsWhenMp4Missing() {
        NativeVideoItem item = item("prefer_mp4", "", "http://cdn/hls/index.m3u8");

        assertEquals("http://cdn/hls/index.m3u8", VideoSourceResolver.resolve(item));
    }

    @Test
    public void fallbackReturnsAlternateSourceOnlyForPreferModes() {
        NativeVideoItem preferHls = item("prefer_hls", "http://cdn/video.mp4", "http://cdn/hls/index.m3u8");
        NativeVideoItem hlsOnly = item("hls_only", "http://cdn/video.mp4", "http://cdn/hls/index.m3u8");

        assertEquals("http://cdn/video.mp4", VideoSourceResolver.fallbackSource(preferHls, "http://cdn/hls/index.m3u8"));
        assertEquals("", VideoSourceResolver.fallbackSource(hlsOnly, "http://cdn/hls/index.m3u8"));
    }

    private NativeVideoItem item(String mode, String mp4, String hls) {
        return new NativeVideoItem("1", "Title", "", "Author", "", "", "", "", "", mp4, hls, "", mode, "0");
    }
}
