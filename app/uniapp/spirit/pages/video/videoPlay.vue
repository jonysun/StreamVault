<template>
	<view class="container">
		<!-- 视频播放区域 -->
		<view class="video-section">
			<video 
				:src="encodeURI(videoInfo.videounrealaddr)" 
				class="video-player"
				controls
				:show-center-play-btn="true"
				:enable-progress-gesture="true"
				@error="handleVideoError"
			></video>
		</view>

		<!-- 视频信息区域 -->
		<view class="video-info">
			<view class="info-header">
				<view class="title-section">
					<text class="video-title">{{videoInfo.videoname}}</text>
				</view>
				<view class="video-stats">
					<text class="upload-time">{{formatTime(videoInfo.createTime)}}</text>
				</view>
			</view>

			<!-- 操作栏 -->
			<view class="action-bar">
				<view class="action-item" @tap="copyVideoLink">
					<uni-icons type="link" size="20" color="#666"></uni-icons>
					<text class="action-text">复制链接</text>
				</view>
				<button class="action-item share-btn" open-type="share">
					<uni-icons type="redo" size="20" color="#666"></uni-icons>
					<text class="action-text">分享</text>
				</button>
			</view>

			<!-- 视频描述 -->
			<view class="video-desc" v-if="videoInfo.videodesc">
				<text class="desc-text">{{videoInfo.videodesc}}</text>
			</view>

			<!-- 平台和标签 -->
			<view class="tags-row" v-if="videoInfo.videoplatform || videoInfo.videotag">
				<view class="tag platform" v-if="videoInfo.videoplatform">{{videoInfo.videoplatform}}</view>
				<view class="tag videotype" v-if="videoInfo.videotag">{{videoInfo.videotag}}</view>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				videoInfo: {},
				isShare: false
			}
		},
		onLoad(options) {
			if (options.share === 'true') {
				this.isShare = true;
				this.videoInfo = {
					videounrealaddr: decodeURIComponent(options.url || ''),
					videoname: decodeURIComponent(options.title || ''),
					videodesc: decodeURIComponent(options.desc || ''),
					createTime: decodeURIComponent(options.time || '')
				};
			} else if (options.videoInfo) {
				try {
					this.videoInfo = JSON.parse(decodeURIComponent(options.videoInfo));
				} catch (e) {
					uni.showToast({ title: '视频信息加载失败', icon: 'none' });
				}
			}
		},
		onShareAppMessage(res) {
			return {
				title: this.videoInfo.videoname,
				path: `/pages/video/videoPlay?share=true&url=${encodeURIComponent(this.videoInfo.videounrealaddr)}&title=${encodeURIComponent(this.videoInfo.videoname)}&desc=${encodeURIComponent(this.videoInfo.videodesc)}&time=${encodeURIComponent(this.videoInfo.createTime)}`,
				imageUrl: this.videoInfo.videocover
			};
		},
		onShareTimeline() {
			return {
				title: this.videoInfo.videoname,
				query: `share=true&url=${encodeURIComponent(this.videoInfo.videounrealaddr)}&title=${encodeURIComponent(this.videoInfo.videoname)}&desc=${encodeURIComponent(this.videoInfo.videodesc)}&time=${encodeURIComponent(this.videoInfo.createTime)}`,
				imageUrl: this.videoInfo.videocover
			};
		},
		methods: {
			handleVideoError(err) {
				uni.showToast({ title: '视频加载失败', icon: 'none' });
			},
			formatTime(timestamp) {
				if (!timestamp) return '';
				const date = new Date(timestamp);
				return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
			},
			copyVideoLink() {
				if (this.videoInfo.videounrealaddr) {
					uni.setClipboardData({
						data: this.videoInfo.videounrealaddr,
						success: () => {
							uni.showToast({ title: '链接已复制', icon: 'success' });
						}
					});
				}
			}
		}
	}
</script>

<style>
.container {
	min-height: 100vh;
	background: #f6f7f8;
}

.video-section {
	width: 100%;
	height: 56.25vw;
	background: #000;
	position: relative;
}

.video-player {
	width: 100%;
	height: 100%;
}

.video-info {
	background: #fff;
	padding: 28rpx;
}

.info-header {
	margin-bottom: 20rpx;
}

.video-title {
	font-size: 34rpx;
	font-weight: 600;
	color: #1a1a1a;
	line-height: 1.5;
	display: block;
	margin-bottom: 12rpx;
}

.video-stats {
	display: flex;
	gap: 16rpx;
}

.upload-time {
	font-size: 24rpx;
	color: #999;
}

.action-bar {
	display: flex;
	justify-content: flex-end;
	padding: 20rpx 0;
	border-top: 1rpx solid #f5f5f5;
	border-bottom: 1rpx solid #f5f5f5;
	gap: 32rpx;
}

.action-item {
	display: flex;
	align-items: center;
	gap: 8rpx;
	background: none;
	border: none;
	padding: 8rpx 0;
	margin: 0;
	line-height: 1;
}

.action-item::after { border: none; }

.share-btn {
	display: flex;
	align-items: center;
	gap: 8rpx;
	background: none;
	border: none;
	padding: 8rpx 0;
	margin: 0;
	line-height: 1;
	font-size: 24rpx;
	color: #666;
}

.share-btn::after { border: none; }

.action-text {
	font-size: 24rpx;
	color: #666;
}

.video-desc {
	padding: 20rpx 0;
}

.desc-text {
	font-size: 28rpx;
	color: #555;
	line-height: 1.8;
}

.tags-row {
	display: flex;
	gap: 12rpx;
	padding: 16rpx 0 0;
}

.tag {
	padding: 6rpx 16rpx;
	border-radius: 8rpx;
	font-size: 22rpx;
	font-weight: 500;
}

.tag.platform { background: #dbeafe; color: #2563eb; }
.tag.videotype { background: #fef3c7; color: #d97706; }
</style>
