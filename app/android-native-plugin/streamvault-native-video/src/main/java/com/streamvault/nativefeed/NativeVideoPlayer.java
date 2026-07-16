package com.streamvault.nativefeed;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.InputStream;

public class NativeVideoPlayer {
    private static final String TAG = "SVNativePoC";
    private static final long MAX_CACHE_BYTES = 200L * 1024L * 1024L;

    public interface Callback {
        void onEnded();
        void onError(String failedUrl, String errorCode);
    }

    private final Context context;
    private final ExoPlayer player;
    private final PlayerView playerView;
    private final SimpleCache cache;
    private Player.Listener currentListener;
    private Callback callback;

    public NativeVideoPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.player = new ExoPlayer.Builder(context).build();
        this.player.setRepeatMode(Player.REPEAT_MODE_ONE);
        this.playerView = new PlayerView(context);
        this.playerView.setUseController(false);
        this.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        this.playerView.setPlayer(player);
        this.playerView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        File cacheDir = new File(context.getCacheDir(), "streamvault_native_video_cache");
        this.cache = new SimpleCache(
                cacheDir,
                new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                new StandaloneDatabaseProvider(context)
        );
    }

    public void attachTo(FrameLayout container) {
        ViewGroup parent = (ViewGroup) playerView.getParent();
        if (parent != null) parent.removeView(playerView);
        container.removeAllViews();
        container.addView(playerView);
    }

    public void play(NativeVideoItem item, ImageView coverView, ImageView previewView, TextView errorText, TextView statusText) {
        playUrl(VideoSourceResolver.resolve(item), coverView, previewView, errorText, statusText);
    }

    public void playUrl(String url, ImageView coverView, ImageView previewView, TextView errorText, TextView statusText) {
        Log.d(TAG, "play url=" + url);
        showStatus(statusText, "准备播放\n" + url);
        if (url.isEmpty()) {
            showError(errorText, "没有可播放地址");
            return;
        }

        coverView.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);
        statusText.setVisibility(View.VISIBLE);
        if (currentListener != null) {
            player.removeListener(currentListener);
        }
        currentListener = new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                showStatus(statusText, "首帧已渲染");
                coverView.setVisibility(View.GONE);
                previewView.setVisibility(View.GONE);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    showStatus(statusText, "缓冲中");
                } else if (playbackState == Player.STATE_READY) {
                    showStatus(statusText, "播放器就绪");
                } else if (playbackState == Player.STATE_ENDED) {
                    showStatus(statusText, "播放结束");
                    if (callback != null) callback.onEnded();
                } else if (playbackState == Player.STATE_IDLE) {
                    showStatus(statusText, "播放器空闲");
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                showStatus(statusText, "视频尺寸 " + videoSize.width + "x" + videoSize.height);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "player error", error);
                showError(errorText, "视频加载失败\n" + error.getErrorCodeName());
                showStatus(statusText, "错误：" + error.getErrorCodeName());
                if (callback != null) callback.onError(url, error.getErrorCodeName());
            }
        };
        player.addListener(currentListener);
        player.setMediaSource(buildMediaSource(url));
        player.prepare();
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public void resume() {
        player.play();
    }

    public long duration() {
        long duration = player.getDuration();
        return duration < 0 ? 0 : duration;
    }

    public long position() {
        long position = player.getCurrentPosition();
        return position < 0 ? 0 : position;
    }

    public void seekTo(long positionMs) {
        player.seekTo(Math.max(0, positionMs));
    }

    public void setMuted(boolean muted) {
        player.setVolume(muted ? 0f : 1f);
    }

    public void setLoopOne(boolean loopOne) {
        player.setRepeatMode(loopOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void prewarm(String url) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            try {
                CacheDataSource.Factory factory = new CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context));
                try (InputStream input = new DataSourceInputStream(factory.createDataSource(), new DataSpec(Uri.parse(url)))) {
                    byte[] buffer = new byte[64 * 1024];
                    input.read(buffer);
                }
            } catch (Exception e) {
                Log.w(TAG, "prewarm failed", e);
            }
        }).start();
    }

    public void release() {
        if (currentListener != null) player.removeListener(currentListener);
        player.release();
        cache.release();
    }

    private MediaSource buildMediaSource(String url) {
        CacheDataSource.Factory dataSourceFactory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context));
        if (url.toLowerCase().contains(".m3u8")) {
            return new HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        }
        return new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
    }

    private void showError(TextView errorText, String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private void showStatus(TextView statusText, String message) {
        statusText.setText(message);
        statusText.setVisibility(View.VISIBLE);
    }
}
