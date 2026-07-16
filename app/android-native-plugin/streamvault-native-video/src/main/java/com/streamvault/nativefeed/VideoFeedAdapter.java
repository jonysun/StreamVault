package com.streamvault.nativefeed;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;

public class VideoFeedAdapter extends RecyclerView.Adapter<VideoFeedAdapter.VideoHolder> {
    private final List<NativeVideoItem> items;

    public VideoFeedAdapter(List<NativeVideoItem> items) {
        this.items = items;
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public VideoHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_native_video, parent, false);
        return new VideoHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoHolder holder, int position) {
        NativeVideoItem item = items.get(position);
        holder.authorText.setText("@" + (item.author.isEmpty() ? "未知作者" : item.author));
        holder.publishTimeText.setText(item.publishTime.isEmpty() ? "" : "发布时间：" + item.publishTime);
        holder.descText.setText(!item.title.isEmpty() ? item.title : item.desc);
        holder.coverView.setVisibility(View.VISIBLE);
        holder.previewView.setVisibility(View.VISIBLE);
        holder.errorText.setVisibility(View.GONE);
        holder.statusText.setVisibility(View.GONE);
        holder.playIndicator.setVisibility(View.GONE);
        holder.progressBar.setVisibility(View.GONE);
        holder.favoriteButton.setImageResource("1".equals(item.favorite) ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);

        if (!item.coverUrl.isEmpty()) {
            Glide.with(holder.coverView)
                    .load(item.coverUrl)
                    .placeholder(android.R.color.black)
                    .error(android.R.color.black)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .into(holder.coverView);
            Glide.with(holder.previewView)
                    .load(item.coverUrl)
                    .placeholder(android.R.color.black)
                    .error(android.R.color.black)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .into(holder.previewView);
        } else {
            String source = VideoSourceResolver.resolve(item);
            Glide.with(holder.coverView)
                    .asBitmap()
                    .load(source)
                    .apply(new RequestOptions().frame(0))
                    .placeholder(android.R.color.black)
                    .error(android.R.color.black)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .into(holder.coverView);
            Glide.with(holder.previewView)
                    .asBitmap()
                    .load(source)
                    .apply(new RequestOptions().frame(0))
                    .placeholder(android.R.color.black)
                    .error(android.R.color.black)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .into(holder.previewView);
        }
        if (!item.authorAvatarUrl.isEmpty()) {
            Glide.with(holder.avatarView)
                    .load(item.authorAvatarUrl)
                    .circleCrop()
                    .into(holder.avatarView);
        } else if (!item.coverUrl.isEmpty()) {
            Glide.with(holder.avatarView)
                    .load(item.coverUrl)
                    .circleCrop()
                    .into(holder.avatarView);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        NativeVideoItem item = items.get(position);
        if (!item.id.isEmpty()) return item.id.hashCode();
        return position;
    }

    public VideoHolder findHolder(RecyclerView recyclerView, int position) {
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        if (holder instanceof VideoHolder) return (VideoHolder) holder;
        return null;
    }

    public void prepareAround(int position) {
        int start = Math.max(0, position - 1);
        int end = Math.min(items.size() - 1, position + 1);
        for (int i = start; i <= end; i++) {
            if (i == position) continue;
            notifyItemChanged(i, "prepare");
        }
    }

    public static class VideoHolder extends RecyclerView.ViewHolder {
        public final FrameLayout playerContainer;
        public final ImageView coverView;
        public final ImageView previewView;
        public final TextView authorText;
        public final TextView publishTimeText;
        public final TextView descText;
        public final TextView errorText;
        public final TextView statusText;
        public final View tapLayer;
        public final TextView playIndicator;
        public final SeekBar progressBar;
        public final ImageView avatarView;
        public final ImageView favoriteButton;
        public final ImageView muteButton;
        public final ImageView modeButton;
        public final ImageView infoButton;
        public final ImageView authorListButton;
        public final ImageView orderButton;

        public VideoHolder(@NonNull View itemView) {
            super(itemView);
            playerContainer = itemView.findViewById(R.id.playerContainer);
            coverView = itemView.findViewById(R.id.coverView);
            previewView = itemView.findViewById(R.id.previewView);
            authorText = itemView.findViewById(R.id.authorText);
            publishTimeText = itemView.findViewById(R.id.publishTimeText);
            descText = itemView.findViewById(R.id.descText);
            errorText = itemView.findViewById(R.id.errorText);
            statusText = itemView.findViewById(R.id.statusText);
            tapLayer = itemView.findViewById(R.id.tapLayer);
            playIndicator = itemView.findViewById(R.id.playIndicator);
            progressBar = itemView.findViewById(R.id.progressBar);
            avatarView = itemView.findViewById(R.id.avatarView);
            favoriteButton = itemView.findViewById(R.id.favoriteButton);
            muteButton = itemView.findViewById(R.id.muteButton);
            modeButton = itemView.findViewById(R.id.modeButton);
            infoButton = itemView.findViewById(R.id.infoButton);
            authorListButton = itemView.findViewById(R.id.authorListButton);
            orderButton = itemView.findViewById(R.id.orderButton);
        }
    }
}
