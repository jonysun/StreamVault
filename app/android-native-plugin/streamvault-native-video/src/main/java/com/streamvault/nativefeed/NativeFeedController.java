package com.streamvault.nativefeed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

public class NativeFeedController {
    private final List<NativeVideoItem> videos = new ArrayList<>();
    private final TreeSet<String> knownAuthors = new TreeSet<>();
    private final Random random = new Random();
    private NativeFeedSortMode sortMode = NativeFeedSortMode.DESC;
    private String authorFilter = "";
    private int currentIndex;

    public void replaceVideos(List<NativeVideoItem> nextVideos) {
        String currentKey = key(currentVideo());
        videos.clear();
        appendUnique(nextVideos);
        rememberAuthors(nextVideos);
        applyOrder();
        restoreCurrentIndex(currentKey);
    }

    public void replaceVideosForAuthor(String author, List<NativeVideoItem> nextVideos) {
        authorFilter = author == null ? "" : author.trim();
        videos.clear();
        appendUnique(nextVideos);
        rememberAuthors(nextVideos);
        applyOrder();
        currentIndex = 0;
        clampIndex();
    }

    public List<NativeVideoItem> videos() {
        return new ArrayList<>(videos);
    }

    public NativeVideoItem currentVideo() {
        if (videos.isEmpty()) return null;
        clampIndex();
        return videos.get(currentIndex);
    }

    public int currentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int index) {
        currentIndex = index;
        clampIndex();
    }

    public NativeFeedSortMode sortMode() {
        return sortMode;
    }

    public void setSortMode(NativeFeedSortMode mode) {
        String currentKey = key(currentVideo());
        sortMode = mode == null ? NativeFeedSortMode.DESC : mode;
        applyOrder();
        restoreCurrentIndex(currentKey);
    }

    public void setSortMode(String mode) {
        setSortMode(NativeFeedSortMode.from(mode));
    }

    public NativeFeedSortMode cycleSortPreservingCurrent() {
        setSortMode(sortMode.next());
        return sortMode;
    }

    public String authorFilter() {
        return authorFilter;
    }

    public List<String> authorOptions() {
        return new ArrayList<>(knownAuthors);
    }

    public void updateFavorite(String id, String favorite) {
        for (NativeVideoItem item : videos) {
            if (sameKey(item.id, id)) {
                item.favorite = "1".equals(favorite) ? "1" : "0";
            }
        }
    }

    private void appendUnique(List<NativeVideoItem> nextVideos) {
        if (nextVideos == null) return;
        Set<String> seen = new HashSet<>();
        for (NativeVideoItem item : nextVideos) {
            if (item == null) continue;
            String key = key(item);
            if (seen.add(key)) videos.add(item);
        }
    }

    private void rememberAuthors(List<NativeVideoItem> nextVideos) {
        if (nextVideos == null) return;
        for (NativeVideoItem item : nextVideos) {
            if (item != null && item.author != null && !item.author.trim().isEmpty()) {
                knownAuthors.add(item.author.trim());
            }
        }
    }

    private void applyOrder() {
        if (sortMode == NativeFeedSortMode.ASC) {
            Collections.sort(videos, byPublishTime(false));
        } else if (sortMode == NativeFeedSortMode.DESC) {
            Collections.sort(videos, byPublishTime(true));
        } else {
            Collections.shuffle(videos, random);
        }
        clampIndex();
    }

    private Comparator<NativeVideoItem> byPublishTime(boolean desc) {
        return (a, b) -> {
            int time = value(a.publishTime).compareTo(value(b.publishTime));
            if (time != 0) return desc ? -time : time;
            int title = value(a.title).compareTo(value(b.title));
            if (title != 0) return title;
            return key(a).compareTo(key(b));
        };
    }

    private void restoreCurrentIndex(String currentKey) {
        if (currentKey == null || currentKey.isEmpty()) {
            clampIndex();
            return;
        }
        for (int i = 0; i < videos.size(); i++) {
            if (currentKey.equals(key(videos.get(i)))) {
                currentIndex = i;
                return;
            }
        }
        clampIndex();
    }

    private void clampIndex() {
        if (videos.isEmpty()) {
            currentIndex = 0;
            return;
        }
        currentIndex = Math.max(0, Math.min(currentIndex, videos.size() - 1));
    }

    private boolean sameKey(String left, String right) {
        return value(left).equals(value(right));
    }

    private String key(NativeVideoItem item) {
        if (item == null) return "";
        if (!value(item.id).isEmpty()) return "id:" + item.id;
        if (!value(item.originalUrl).isEmpty()) return "original:" + item.originalUrl;
        return "title:" + value(item.title);
    }

    private String value(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
