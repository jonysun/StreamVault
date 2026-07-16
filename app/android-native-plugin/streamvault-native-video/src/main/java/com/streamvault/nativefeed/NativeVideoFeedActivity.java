package com.streamvault.nativefeed;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public class NativeVideoFeedActivity extends AppCompatActivity {
    private static final String TAG = "SVNativePoC";

    private ViewPager2 viewPager;
    private VideoFeedAdapter adapter;
    private NativeVideoPlayer player;
    private NativeFavoriteClient favoriteClient;
    private NativeFeedRepository feedRepository;
    private final NativeFeedController feedController = new NativeFeedController();
    private RecyclerView recyclerView;
    private final List<NativeVideoItem> items = new ArrayList<>();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private int currentIndex = -1;
    private int initialIndex = 0;
    private String serveraddr = "";
    private String serverport = "";
    private String servertoken = "";
    private boolean muted = true;
    private boolean loopOne = false;
    private boolean draggingProgress = false;
    private VideoFeedAdapter.VideoHolder currentHolder;
    private String orderMode = "sequence";
    private String activeAuthorFilter = "";
    private String playbackSourceMode = "prefer_mp4";
    private String initialVideoKey = "";
    private String randomSeed = String.valueOf(System.currentTimeMillis());
    private long feedLoadGeneration = 0L;
    private String fallbackTriedItemKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "NativeVideoFeedActivity onCreate");
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_native_video_feed);
        Toast.makeText(this, "原生播放器已启动", Toast.LENGTH_SHORT).show();

        viewPager = findViewById(R.id.videoPager);
        player = new NativeVideoPlayer(this);
        player.setCallback(new NativeVideoPlayer.Callback() {
            @Override
            public void onEnded() {
                playNextByOrder();
            }

            @Override
            public void onError(String failedUrl, String errorCode) {
                retryCurrentFallback(failedUrl, errorCode);
            }
        });
        favoriteClient = new NativeFavoriteClient();
        feedRepository = new NativeFeedRepository();
        List<NativeVideoItem> seedItems = loadItems();
        if (!activeAuthorFilter.isEmpty()) {
            feedController.replaceVideosForAuthor(activeAuthorFilter, seedItems);
        } else {
            feedController.replaceVideos(seedItems);
        }
        syncItemsFromController();
        if (items.isEmpty() && !hasServerConfig()) {
            return;
        }
        adapter = new VideoFeedAdapter(items);
        viewPager.setAdapter(adapter);
        viewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        viewPager.setOffscreenPageLimit(1);
        recyclerView = (RecyclerView) viewPager.getChildAt(0);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                playAt(position);
            }
        });

        if (!items.isEmpty()) {
            initialIndex = initialIndexFromOptions();
            feedController.setCurrentIndex(initialIndex);
            viewPager.setCurrentItem(initialIndex, false);
            viewPager.post(() -> playAt(initialIndex, true));
        } else {
            Toast.makeText(this, "Loading native feed", Toast.LENGTH_SHORT).show();
        }
        refreshFeedFromBackend(!items.isEmpty());
    }

    private List<NativeVideoItem> loadItems() {
        String options = getIntent().getStringExtra("options");
        if (options != null && !options.isEmpty()) {
            Log.d(TAG, "options length=" + options.length());
            try {
                return parseOptions(options);
            } catch (Exception ignored) {
                Log.e(TAG, "parse native options failed", ignored);
                Toast.makeText(this, "视频数据解析失败", Toast.LENGTH_SHORT).show();
                finish();
                return new ArrayList<>();
            }
        }
        Toast.makeText(this, "视频数据为空", Toast.LENGTH_SHORT).show();
        finish();
        return new ArrayList<>();
    }

    private List<NativeVideoItem> parseOptions(String options) throws Exception {
        JSONObject root = new JSONObject(options);
        initialIndex = root.optInt("currentIndex", 0);
        serveraddr = root.optString("serveraddr", "");
        serverport = root.optString("serverport", "");
        servertoken = root.optString("servertoken", "");
        muted = root.optBoolean("isMuted", true);
        loopOne = "loopone".equals(root.optString("playbackMode", "autonext"));
        playbackSourceMode = root.optString("playbackSourceMode", "prefer_mp4");
        activeAuthorFilter = root.optString("selectedAuthor", "");
        orderMode = root.optString("activeOrderMode", root.optString("orderMode", "desc"));
        initialVideoKey = root.optString("currentVideoId", root.optString("currentId", ""));
        randomSeed = root.optString("randomSeed", randomSeed);
        feedController.setSortMode(orderMode);
        JSONArray videos = root.optJSONArray("videos");
        List<NativeVideoItem> parsed = new ArrayList<>();
        if (videos == null) return parsed;
        for (int i = 0; i < videos.length(); i++) {
            parsed.add(NativeVideoItem.fromJson(videos.getJSONObject(i), playbackSourceMode));
        }
        Log.d(TAG, "parsed native videos=" + parsed.size() + ", initialIndex=" + initialIndex + ", sourceMode=" + playbackSourceMode);
        if (parsed.isEmpty() && !hasServerConfig()) {
            Toast.makeText(this, "没有可播放的视频", Toast.LENGTH_SHORT).show();
            finish();
        }
        return parsed;
    }

    private List<NativeVideoItem> sampleItems() {
        initialIndex = 0;
        List<NativeVideoItem> list = new ArrayList<>();
        list.add(new NativeVideoItem(
                "sample-1",
                "Sintel Trailer",
                "PoC sample video",
                "streamvault",
                "",
                "",
                "",
                "2026-06-01",
                "",
                "https://media.w3.org/2010/05/sintel/trailer.mp4",
                "",
                "",
                "prefer_mp4",
                "0"
        ));
        list.add(new NativeVideoItem(
                "sample-2",
                "Big Buck Bunny Trailer",
                "Swipe while holding to verify native ViewPager2 drag preview",
                "streamvault",
                "",
                "",
                "",
                "2026-06-01",
                "",
                "https://media.w3.org/2010/05/bunny/trailer.mp4",
                "",
                "",
                "prefer_mp4",
                "0"
        ));
        list.add(new NativeVideoItem(
                "sample-3",
                "For Bigger Blazes",
                "Adjacent pages should show cover/first frame while dragging",
                "streamvault",
                "",
                "",
                "",
                "2026-06-01",
                "",
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                "",
                "",
                "prefer_mp4",
                "0"
        ));
        return list;
    }

    private void refreshFeedFromBackend(boolean preserveCurrent) {
        if (!hasServerConfig()) return;
        long generation = ++feedLoadGeneration;
        String author = activeAuthorFilter;
        NativeFeedSortMode sort = feedController.sortMode();
        String seed = sort == NativeFeedSortMode.RANDOM ? randomSeed : "";
        feedRepository.findAll(serveraddr, serverport, servertoken, author, sort, seed, playbackSourceMode, (ok, videos, message) -> runOnUiThread(() -> {
            if (generation != feedLoadGeneration || isFinishing() || isDestroyed()) return;
            if (!ok) {
                Toast.makeText(this, message == null || message.isEmpty() ? "Native feed load failed" : message, Toast.LENGTH_SHORT).show();
                return;
            }
            if (videos == null || videos.isEmpty()) {
                Toast.makeText(this, "Native feed is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            if (author == null || author.isEmpty()) {
                feedController.replaceVideos(videos);
            } else {
                feedController.replaceVideosForAuthor(author, videos);
            }
            int target = preserveCurrent ? feedController.currentIndex() : 0;
            syncItemsFromController();
            if (items.isEmpty()) return;
            target = Math.max(0, Math.min(target, items.size() - 1));
            feedController.setCurrentIndex(target);
            currentIndex = -1;
            final int playTarget = target;
            viewPager.setCurrentItem(playTarget, false);
            viewPager.post(() -> playAt(playTarget, true));
        }));
    }

    private boolean hasServerConfig() {
        return !serveraddr.isEmpty() && !serverport.isEmpty() && !servertoken.isEmpty();
    }

    private void syncItemsFromController() {
        items.clear();
        items.addAll(feedController.videos());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private int initialIndexFromOptions() {
        if (!initialVideoKey.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                NativeVideoItem item = items.get(i);
                if (initialVideoKey.equals(item.id) || initialVideoKey.equals(item.originalUrl)) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(initialIndex, items.size() - 1));
    }

    private String itemKey(NativeVideoItem item) {
        if (item == null) return "";
        if (!item.id.isEmpty()) return "id:" + item.id;
        if (!item.originalUrl.isEmpty()) return "original:" + item.originalUrl;
        return "title:" + item.title;
    }

    private void playAt(int position) {
        playAt(position, false);
    }

    private void playAt(int position, boolean force) {
        if (position < 0 || position >= items.size()) return;
        if (!force && position == currentIndex && currentIndex >= 0) return;
        currentIndex = position;
        feedController.setCurrentIndex(position);
        fallbackTriedItemKey = "";
        VideoFeedAdapter.VideoHolder holder = adapter.findHolder(recyclerView, position);
        if (holder == null) {
            viewPager.post(() -> playAt(position, force));
            return;
        }
        if (currentHolder != null && currentHolder != holder) {
            currentHolder.coverView.setVisibility(View.VISIBLE);
            currentHolder.previewView.setVisibility(View.VISIBLE);
            currentHolder.playIndicator.setVisibility(View.GONE);
            currentHolder.progressBar.setVisibility(View.GONE);
        }
        currentHolder = holder;
        player.attachTo(holder.playerContainer);
        player.setMuted(muted);
        player.setLoopOne(loopOne);
        player.play(items.get(position), holder.coverView, holder.previewView, holder.errorText, holder.statusText);
        bindHolderActions(holder, position);
        adapter.prepareAround(position);
        prewarmAround(position);
        startProgressUpdates();
    }

    private void retryCurrentFallback(String failedUrl, String errorCode) {
        if (currentIndex < 0 || currentIndex >= items.size()) return;
        NativeVideoItem item = items.get(currentIndex);
        String key = itemKey(item);
        if (key.equals(fallbackTriedItemKey)) {
            Toast.makeText(this, "视频加载失败：" + errorCode, Toast.LENGTH_SHORT).show();
            return;
        }
        String failed = failedUrl == null || failedUrl.isEmpty() ? VideoSourceResolver.resolve(item) : failedUrl;
        String fallback = VideoSourceResolver.fallbackSource(item, failed);
        if (fallback.isEmpty() || fallback.equals(failed)) {
            Toast.makeText(this, "视频加载失败：" + errorCode, Toast.LENGTH_SHORT).show();
            return;
        }
        VideoFeedAdapter.VideoHolder holder = currentHolder != null ? currentHolder : adapter.findHolder(recyclerView, currentIndex);
        if (holder == null) {
            Toast.makeText(this, "视频加载失败：" + errorCode, Toast.LENGTH_SHORT).show();
            return;
        }
        fallbackTriedItemKey = key;
        Log.d(TAG, "retry fallback url=" + fallback + " failed=" + failed);
        player.playUrl(fallback, holder.coverView, holder.previewView, holder.errorText, holder.statusText);
    }

    private void bindHolderActions(VideoFeedAdapter.VideoHolder holder, int position) {
        NativeVideoItem item = items.get(position);
        holder.tapLayer.setOnClickListener(v -> togglePause(holder));
        holder.progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long duration = player.duration();
                    if (duration > 0) player.seekTo(duration * progress / 1000L);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                draggingProgress = true;
                seekBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                draggingProgress = false;
                scheduleProgressHide(seekBar);
            }
        });
        holder.avatarView.setOnClickListener(v -> showAuthorProfile(item.author));
        holder.authorText.setOnClickListener(v -> showAuthorProfile(item.author));
        holder.favoriteButton.setOnClickListener(v -> toggleFavorite(item, holder));
        holder.muteButton.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        holder.muteButton.setOnClickListener(v -> {
            muted = !muted;
            player.setMuted(muted);
            holder.muteButton.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        });
        holder.modeButton.setImageResource(R.drawable.ic_repeat);
        holder.modeButton.setOnClickListener(v -> {
            loopOne = !loopOne;
            player.setLoopOne(loopOne);
            Toast.makeText(this, loopOne ? "单条循环" : "自动下一条", Toast.LENGTH_SHORT).show();
        });
        holder.infoButton.setOnClickListener(v -> showInfo(item));
        holder.authorListButton.setOnClickListener(v -> showAuthorList());
        holder.orderButton.setImageResource(R.drawable.ic_order);
        holder.orderButton.setOnClickListener(v -> switchOrder(holder));
    }

    private void togglePause(VideoFeedAdapter.VideoHolder holder) {
        holder.progressBar.setVisibility(View.VISIBLE);
        if (player.isPlaying()) {
            player.pause();
            holder.playIndicator.setVisibility(View.VISIBLE);
        } else {
            player.resume();
            holder.playIndicator.setVisibility(View.GONE);
            scheduleProgressHide(holder.progressBar);
        }
    }

    private void startProgressUpdates() {
        progressHandler.removeCallbacksAndMessages(null);
        progressHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentHolder != null && !draggingProgress) {
                    long duration = player.duration();
                    if (duration > 0) {
                        currentHolder.progressBar.setProgress((int) Math.min(1000, player.position() * 1000L / duration));
                    }
                }
                progressHandler.postDelayed(this, 250);
            }
        });
    }

    private void scheduleProgressHide(SeekBar seekBar) {
        progressHandler.postDelayed(() -> {
            if (!draggingProgress && player.isPlaying()) seekBar.setVisibility(View.GONE);
        }, 2200);
    }

    private void toggleFavorite(NativeVideoItem item, VideoFeedAdapter.VideoHolder holder) {
        if (serveraddr.isEmpty() || serverport.isEmpty() || servertoken.isEmpty() || item.id.isEmpty()) {
            Toast.makeText(this, "收藏接口参数不足", Toast.LENGTH_SHORT).show();
            return;
        }
        String previous = item.favorite;
        String next = "1".equals(previous) ? "0" : "1";
        item.favorite = next;
        feedController.updateFavorite(item.id, next);
        holder.favoriteButton.setImageResource("1".equals(next) ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
        Log.d(TAG, "favorite update id=" + item.id + " favorite=" + next);
        favoriteClient.update(serveraddr, serverport, servertoken, item.id, next, (ok, favorite, message) -> {
            if (ok) {
                item.favorite = "1".equals(favorite) ? "1" : "0";
            } else {
                item.favorite = previous;
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
            feedController.updateFavorite(item.id, item.favorite);
            holder.favoriteButton.setImageResource("1".equals(item.favorite) ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
        });
    }

    private void prewarmAround(int position) {
        for (int i = Math.max(0, position - 1); i <= Math.min(items.size() - 1, position + 1); i++) {
            if (i == position) continue;
            player.prewarm(VideoSourceResolver.resolve(items.get(i)));
        }
    }

    private void showInfo(NativeVideoItem item) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = verticalBox(20);
        scroll.addView(box);
        box.addView(label("视频信息", 22, true));
        box.addView(label("标题：" + textOr(item.title, "未命名"), 16, false));
        box.addView(label("作者：@" + textOr(item.author, "未知作者"), 16, false));
        box.addView(label("发布时间：" + textOr(item.publishTime, "未知"), 14, false));
        box.addView(label("描述：" + textOr(item.desc, "暂无描述"), 14, false));
        box.addView(label("播放源：" + VideoSourceResolver.resolve(item), 12, false));
        if (!item.originalUrl.isEmpty()) box.addView(label("原作品：" + item.originalUrl, 12, false));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showAuthorProfile(String author) {
        String safeAuthor = author == null || author.isEmpty() ? "未知作者" : author;
        List<Integer> indexes = indexesForAuthor(author);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout root = verticalBox(18);
        scroll.addView(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 34, false);
        TextView titleBar = label("主页", 17, true);
        topBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        topBar.addView(titleBar, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(topBar);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView avatar = new ImageView(this);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(72)));
        NativeVideoItem first = indexes.isEmpty() ? null : items.get(indexes.get(0));
        if (first != null && !first.authorAvatarUrl.isEmpty()) {
            com.bumptech.glide.Glide.with(avatar).load(first.authorAvatarUrl).circleCrop().into(avatar);
        } else if (first != null && !first.coverUrl.isEmpty()) {
            com.bumptech.glide.Glide.with(avatar).load(first.coverUrl).circleCrop().into(avatar);
        }
        header.addView(avatar);
        LinearLayout meta = verticalBox(4);
        meta.setGravity(Gravity.CENTER_HORIZONTAL);
        meta.addView(label("@" + safeAuthor, 22, true));
        meta.addView(label((first == null || first.authorDesc.isEmpty()) ? "暂无简介" : first.authorDesc, 14, false));
        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(statLabel(String.valueOf(indexes.size()), "作品"));
        stats.addView(statLabel(String.valueOf(favoriteCount(indexes)), "收藏"));
        meta.addView(stats);
        header.addView(meta);
        root.addView(header);

        EditText search = new EditText(this);
        search.setHint("搜索作品标题/描述");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.rgb(140, 140, 140));
        search.setBackgroundColor(Color.rgb(28, 28, 28));
        root.addView(search);

        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER);
        root.addView(sortRow);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        root.addView(grid);

        final String[] sort = {"current"};
        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];
        addSortButton(sortRow, "当前", sort, "current", indexes, grid, search, dialogRef);
        addSortButton(sortRow, "最新", sort, "latest", indexes, grid, search, dialogRef);
        addSortButton(sortRow, "最早", sort, "earliest", indexes, grid, search, dialogRef);
        addSortButton(sortRow, "随机", sort, "random", indexes, grid, search, dialogRef);
        addSortButton(sortRow, "收藏", sort, "favorite", indexes, grid, search, dialogRef);
        renderProfileGrid(indexes, grid, search, sort[0], dialogRef);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderProfileGrid(indexes, grid, search, sort[0], dialogRef); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        dialogRef[0] = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(scroll)
                .show();
        back.setOnClickListener(v -> dialogRef[0].dismiss());
        dialogRef[0].setOnShowListener(dialog -> makeFullScreen(dialogRef[0]));
        makeFullScreen(dialogRef[0]);
    }

    private void showAuthorList() {
        List<String> authors = feedController.authorOptions();
        if (authors.isEmpty()) {
            LinkedHashSet<String> fallbackAuthors = new LinkedHashSet<>();
            for (NativeVideoItem item : items) {
                if (!item.author.isEmpty()) fallbackAuthors.add(item.author);
            }
            authors = new ArrayList<>(fallbackAuthors);
        }
        String[] labels = new String[authors.size() + 1];
        labels[0] = "全部作者";
        int i = 1;
        for (String author : authors) labels[i++] = author;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("作者列表")
                .setItems(labels, (dialog, which) -> {
                    activeAuthorFilter = which == 0 ? "" : labels[which];
                    Toast.makeText(this, activeAuthorFilter.isEmpty() ? "已显示全部作者" : "筛选 @" + activeAuthorFilter, Toast.LENGTH_SHORT).show();
                    if (hasServerConfig()) {
                        refreshFeedFromBackend(false);
                    } else {
                        int target = firstIndexForActiveAuthor();
                        if (target >= 0) viewPager.setCurrentItem(target, false);
                    }
                })
                .show();
    }

    private void switchOrder(VideoFeedAdapter.VideoHolder holder) {
        NativeFeedSortMode sort = feedController.cycleSortPreservingCurrent();
        orderMode = sort.value();
        syncItemsFromController();
        int target = feedController.currentIndex();
        currentIndex = -1;
        if (!items.isEmpty()) {
            viewPager.setCurrentItem(target, false);
            viewPager.post(() -> playAt(target, true));
        }
        holder.orderButton.setImageResource(R.drawable.ic_order);
        Toast.makeText(this, "播放顺序：" + orderLabel(), Toast.LENGTH_SHORT).show();
        refreshFeedFromBackend(true);
    }

    private void playNextByOrder() {
        if (loopOne || items.isEmpty()) return;
        int next = currentIndex >= items.size() - 1 ? 0 : currentIndex + 1;
        viewPager.setCurrentItem(next, true);
    }

    private String orderLabel() {
        NativeFeedSortMode sort = feedController.sortMode();
        if (sort == NativeFeedSortMode.ASC) return "最早";
        if (sort == NativeFeedSortMode.RANDOM) return "随机";
        return "最新";
    }

    private List<Integer> indexesForAuthor(String author) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            NativeVideoItem item = items.get(i);
            if ((author == null || author.isEmpty()) ? item.author.isEmpty() : author.equals(item.author)) result.add(i);
        }
        return result;
    }

    private int firstIndexForActiveAuthor() {
        if (activeAuthorFilter.isEmpty()) return 0;
        for (int i = 0; i < items.size(); i++) {
            if (activeAuthorFilter.equals(items.get(i).author)) return i;
        }
        return -1;
    }

    private void addSortButton(LinearLayout row, String text, String[] sort, String value, List<Integer> indexes, GridLayout grid, EditText search, androidx.appcompat.app.AlertDialog[] dialogRef) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(v -> {
            sort[0] = value;
            renderProfileGrid(indexes, grid, search, sort[0], dialogRef);
        });
        row.addView(button, new LinearLayout.LayoutParams(0, dp(40), 1));
    }

    private void renderProfileGrid(List<Integer> baseIndexes, GridLayout grid, EditText search, String sort, androidx.appcompat.app.AlertDialog[] dialogRef) {
        grid.removeAllViews();
        String q = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase();
        List<Integer> filtered = new ArrayList<>();
        for (Integer idx : baseIndexes) {
            NativeVideoItem item = items.get(idx);
            String haystack = (item.title + " " + item.desc).toLowerCase();
            if (q.isEmpty() || haystack.contains(q)) filtered.add(idx);
        }
        if ("latest".equals(sort)) filtered.sort((a, b) -> textOr(items.get(b).publishTime, "").compareTo(textOr(items.get(a).publishTime, "")));
        else if ("earliest".equals(sort)) filtered.sort(Comparator.comparing(a -> textOr(items.get(a).publishTime, "")));
        else if ("random".equals(sort)) Collections.shuffle(filtered);
        else if ("favorite".equals(sort)) filtered.sort((a, b) -> items.get(b).favorite.compareTo(items.get(a).favorite));
        for (Integer idx : filtered) {
            final int targetIndex = idx;
            NativeVideoItem item = items.get(idx);
            LinearLayout cell = verticalBox(4);
            cell.setPadding(4, 4, 4, 4);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(image, new LinearLayout.LayoutParams(dp(104), dp(138)));
            if (!item.coverUrl.isEmpty()) com.bumptech.glide.Glide.with(image).load(item.coverUrl).centerCrop().into(image);
            else com.bumptech.glide.Glide.with(image).asBitmap().load(VideoSourceResolver.resolve(item)).apply(new com.bumptech.glide.request.RequestOptions().frame(0)).centerCrop().into(image);
            TextView title = label(textOr(item.title, item.desc), 11, false);
            title.setMaxLines(2);
            cell.addView(title);
            cell.setOnClickListener(v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                viewPager.setCurrentItem(targetIndex, false);
                playAt(targetIndex);
            });
            grid.addView(cell);
        }
    }

    private LinearLayout verticalBox(int paddingDp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        box.setBackgroundColor(Color.BLACK);
        return box;
    }

    private TextView statLabel(String number, String label) {
        TextView view = label(number + "\n" + label, 13, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(8), dp(18), dp(8));
        return view;
    }

    private int favoriteCount(List<Integer> indexes) {
        int count = 0;
        for (Integer idx : indexes) if ("1".equals(items.get(idx).favorite)) count++;
        return count;
    }

    private void makeFullScreen(androidx.appcompat.app.AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(sp);
        view.setTextColor(Color.WHITE);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onPause() {
        super.onPause();
        progressHandler.removeCallbacksAndMessages(null);
        player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        progressHandler.removeCallbacksAndMessages(null);
        player.release();
        favoriteClient.shutdown();
        if (feedRepository != null) feedRepository.shutdown();
    }
}
