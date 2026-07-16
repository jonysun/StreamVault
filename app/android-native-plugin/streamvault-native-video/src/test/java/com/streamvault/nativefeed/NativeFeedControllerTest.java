package com.streamvault.nativefeed;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeFeedControllerTest {
    @Test
    public void replaceVideosPreservesCurrentVideoByStableKey() {
        NativeFeedController controller = new NativeFeedController();
        controller.replaceVideos(Arrays.asList(video("1", "A", "2026-01-01"), video("2", "B", "2026-01-02")));
        controller.setCurrentIndex(0);

        controller.replaceVideos(Arrays.asList(video("3", "C", "2026-01-03"), video("2", "B", "2026-01-02"), video("1", "A", "2026-01-01")));

        assertEquals("2", controller.currentVideo().id);
        assertEquals(1, controller.currentIndex());
    }

    @Test
    public void replaceVideosForAuthorResetsToFirstItemAndStoresAuthor() {
        NativeFeedController controller = new NativeFeedController();
        controller.replaceVideos(Arrays.asList(video("1", "A", "2026-01-01"), video("2", "B", "2026-01-02")));
        controller.setCurrentIndex(1);

        controller.replaceVideosForAuthor("alice", Arrays.asList(video("3", "C", "2026-01-03"), video("4", "D", "2026-01-04")));

        assertEquals("alice", controller.authorFilter());
        assertEquals(0, controller.currentIndex());
        assertEquals("4", controller.currentVideo().id);
    }

    @Test
    public void sortModeOrdersByPublishTimeAndPreservesCurrentVideo() {
        NativeFeedController controller = new NativeFeedController();
        controller.replaceVideos(Arrays.asList(video("1", "Old", "2026-01-01"), video("2", "New", "2026-01-03"), video("3", "Mid", "2026-01-02")));
        controller.setCurrentIndex(1);

        controller.setSortMode(NativeFeedSortMode.ASC);

        List<NativeVideoItem> videos = controller.videos();
        assertEquals("1", videos.get(0).id);
        assertEquals("3", videos.get(1).id);
        assertEquals("2", videos.get(2).id);
        assertEquals("3", controller.currentVideo().id);
        assertEquals(1, controller.currentIndex());
    }

    @Test
    public void authorOptionsUseKnownVideos() {
        NativeFeedController controller = new NativeFeedController();
        controller.replaceVideos(Arrays.asList(video("1", "Old", "2026-01-01", "bob"), video("2", "New", "2026-01-03", "alice")));
        controller.replaceVideosForAuthor("alice", Arrays.asList(video("2", "New", "2026-01-03", "alice")));

        assertTrue(controller.authorOptions().contains("alice"));
        assertTrue(controller.authorOptions().contains("bob"));
        assertFalse(controller.authorOptions().contains(""));
    }

    private NativeVideoItem video(String id, String title, String publishTime) {
        return video(id, title, publishTime, "alice");
    }

    private NativeVideoItem video(String id, String title, String publishTime, String author) {
        return new NativeVideoItem(id, title, "", author, "", "", "", publishTime, "", "http://cdn/" + id + ".mp4", "", "", "prefer_mp4", "0");
    }
}
