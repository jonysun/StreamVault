package com.streamvault.nativefeed;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class NativeFeedRepositoryTest {
    @Test
    public void buildFindAllRequestUsesSortFieldsForAscendingOrder() {
        NativeFeedRepository.Request request = NativeFeedRepository.buildFindAllRequest(
                "http://host", "28081", "tok 1", "alice", NativeFeedSortMode.ASC, "");

        assertEquals("http://host:28081/api/findAllVideos?token=tok+1", request.url);
        assertEquals("videoauthor=alice&sortField=publishtime&sortOrder=asc", request.body);
    }

    @Test
    public void buildFindAllRequestUsesRandomModeWithoutSortFields() {
        NativeFeedRepository.Request request = NativeFeedRepository.buildFindAllRequest(
                "http://host", "28081", "tok", "", NativeFeedSortMode.RANDOM, "seed-1");

        assertEquals("http://host:28081/api/findAllVideos?token=tok", request.url);
        assertEquals("randomMode=1&randomSeed=seed-1", request.body);
        assertFalse(request.body.contains("sortField"));
    }

    @Test
    public void parseDirectRecordArrayNormalizesMediaPaths() throws Exception {
        String json = "{\"resCode\":\"000001\",\"record\":[{\"id\":1,\"videoname\":\"A\",\"videounrealaddr\":\"video/a b.mp4\",\"playurl\":\"hls/index.m3u8\",\"videocover\":\"cover/a.jpg\"}]}";

        List<NativeVideoItem> videos = NativeFeedRepository.parseVideoList(json, "prefer_hls", "http://host", "28081", "tok");

        assertEquals(1, videos.size());
        assertEquals("1", videos.get(0).id);
        assertEquals("A", videos.get(0).title);
        assertEquals("http://host:28081/video/a%20b.mp4?apptoken=tok", videos.get(0).mp4Url);
        assertEquals("http://host:28081/hls/index.m3u8?apptoken=tok", videos.get(0).hlsUrl);
        assertEquals("http://host:28081/cover/a.jpg?apptoken=tok", videos.get(0).coverUrl);
    }

    @Test
    public void parsePageEnvelopeRecordContent() throws Exception {
        String json = "{\"record\":{\"content\":[{\"videoid\":\"v2\",\"title\":\"B\",\"mp4Url\":\"http://cdn/b.mp4\"}]}}";

        List<NativeVideoItem> videos = NativeFeedRepository.parseVideoList(json, "prefer_mp4", "http://host", "28081", "tok");

        assertEquals(1, videos.size());
        assertEquals("v2", videos.get(0).id);
        assertEquals("B", videos.get(0).title);
        assertEquals("http://cdn/b.mp4", videos.get(0).mp4Url);
    }

    @Test
    public void parseDouyinRecordRejectsNumericAuthorUid() throws Exception {
        String json = "{\"record\":[{\"id\":1,\"platform\":\"douyin\",\"authoruid\":\"84583932458\",\"uniqueid\":\"public_handle\"}]}";

        List<NativeVideoItem> videos = NativeFeedRepository.parseVideoList(json, "prefer_mp4", "http://host", "28081", "tok");

        assertEquals(1, videos.size());
        assertEquals("", videos.get(0).authorId);
    }

    @Test
    public void parseDouyinRecordPrefersCanonicalSecUid() throws Exception {
        String json = "{\"record\":[{\"id\":1,\"platform\":\"douyin\",\"authoruid\":\"84583932458\",\"secuid\":\"MS4wLjABAAAAstable\",\"uniqueid\":\"public_handle\"}]}";

        List<NativeVideoItem> videos = NativeFeedRepository.parseVideoList(json, "prefer_mp4", "http://host", "28081", "tok");

        assertEquals(1, videos.size());
        assertEquals("MS4wLjABAAAAstable", videos.get(0).authorId);
    }
}
