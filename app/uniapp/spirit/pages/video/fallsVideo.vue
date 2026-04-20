<template>
	<view class="video-container">
		<swiper 
			class="video-swiper" 
			:vertical="true" 
			:current="currentIndex" 
			@change="onSwiperChange"
			:duration="300"
		>
			<swiper-item v-for="(video, index) in videoList" :key="video.id" class="swiper-item">
				<view class="video-wrapper">
					<video
						v-if="!video.hasError"
						:id="`video-${index}`"
						:src="video.videounrealaddr"
						:poster="video.videocover"
						:controls="false"
						:autoplay="false"
						:show-center-play-btn="false"
						:show-play-btn="false"
						:enable-progress-gesture="false"
						:object-fit="'cover'"
						class="video-player"
						@play="onVideoPlay(index)"
						@pause="onVideoPause(index)"
						@ended="onVideoEnded(index)"
						@error="onVideoError(index)"
					></video>
					
					<view v-if="video.hasError" class="video-error">
						<text class="error-text">视频加载失败</text>
						<text class="retry-btn" @click="retryLoadVideo(index)">重试</text>
					</view>
					
					<view 
						v-if="!video.isPlaying && !video.hasError" 
						class="play-btn"
						@click="togglePlay(index)"
					>
						<text class="play-icon">▶</text>
					</view>
					
					<view class="video-overlay">
						<view class="bottom-info">
							<view class="author-info">
								<text class="author-name">@{{ video.author || '未知作者' }}</text>
							</view>
							
							<view class="video-desc">
								<text class="desc-text">{{ video.videoname || video.videodesc || '' }}</text>
								<view v-if="video.videotag" class="tags">
									<text 
										v-for="(tag, tagIndex) in parseTags(video.videotag)" 
										:key="tagIndex"
										class="tag"
									>
										#{{ tag }}
									</text>
								</view>
							</view>
						</view>
					</view>
				</view>
			</swiper-item>
		</swiper>
		
		<view v-if="isLoading && videoList.length === 0" class="loading-container">
			<text class="loading-text">加载中...</text>
		</view>
		
		<view v-if="isLoading && videoList.length > 0" class="bottom-loading">
			<view class="loading-spinner"></view>
			<text class="loading-text">正在加载更多视频...</text>
		</view>
		
		<view v-if="!isLoading && videoList.length === 0" class="empty-container">
			<text class="empty-text">没有更多视频了</text>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				videoList: [],
				currentIndex: 0,
				isLoading: false,
				hasMore: true,
				pageNo: 1,
				pageSize: 1,
				videoContexts: {},
				playingIndex: -1,
				serveraddr: '',
				serverport: '',
				servertoken: ''
			}
		},

		onLoad() {
			this.getServerConfig();
			this.initVideoList();
		},
		onReady() {
			if (this.videoList.length > 0) {
				this.createVideoContext();
				this.playCurrentVideo();
			}
		},
		onShow() {
			this.playCurrentVideo();
		},
		onHide() {
			this.pauseCurrentVideo();
		},
		onUnload() {
			this.cleanup();
		},
		methods: {
			getServerConfig() {
				this.serveraddr = uni.getStorageSync('serveraddr') || '';
				this.serverport = uni.getStorageSync('serverport') || '';
				this.servertoken = uni.getStorageSync('servertoken') || '';
			},
			initVideoList() {
				this.loadVideos();
			},
			
			createVideoContext() {
				if (this.currentIndex >= 0 && this.currentIndex < this.videoList.length) {
					this.videoContexts[this.currentIndex] = uni.createVideoContext(`video-${this.currentIndex}`, this);
				}
			},
			
			onSwiperChange(e) {
				const newIndex = e.detail.current;
				const oldIndex = this.currentIndex;
				
				if (oldIndex !== -1 && this.videoContexts[oldIndex]) {
					this.videoContexts[oldIndex].pause();
					if (this.videoList[oldIndex]) {
						this.videoList[oldIndex].isPlaying = false;
					}
				}
				
				this.currentIndex = newIndex;
				
				if (newIndex >= this.videoList.length - 1 && this.hasMore && !this.isLoading) {
					this.loadVideos().then(() => {
						this.createVideoContext();
						this.playCurrentVideo();
					}).catch(error => {
						this.playCurrentVideo();
					});
				} else {
					this.createVideoContext();
					this.playCurrentVideo();
				}
			},
			
			playCurrentVideo() {
				const currentVideo = this.videoList[this.currentIndex];
				if (currentVideo && !currentVideo.hasError && !currentVideo.isLoading) {
					if (!this.videoContexts[this.currentIndex]) {
						this.videoContexts[this.currentIndex] = uni.createVideoContext(`video-${this.currentIndex}`, this);
					}
					
					if (this.videoContexts[this.currentIndex]) {
						this.videoContexts[this.currentIndex].play();
						currentVideo.isPlaying = true;
						this.playingIndex = this.currentIndex;
					}
				}
			},
			
			pauseCurrentVideo() {
				const currentVideo = this.videoList[this.currentIndex];
				if (currentVideo && this.videoContexts[this.currentIndex]) {
					this.videoContexts[this.currentIndex].pause();
					currentVideo.isPlaying = false;
					this.playingIndex = -1;
				}
			},
			
			togglePlay(index) {
				const video = this.videoList[index];
				if (!video) return;
				
				if (video.isPlaying) {
					if (this.videoContexts[index]) {
						this.videoContexts[index].pause();
						video.isPlaying = false;
						if (this.playingIndex === index) {
							this.playingIndex = -1;
						}
					}
				} else {
					this.pauseAllVideos();
					if (!this.videoContexts[index]) {
						this.videoContexts[index] = uni.createVideoContext(`video-${index}`, this);
					}
					if (this.videoContexts[index]) {
						this.videoContexts[index].play();
						video.isPlaying = true;
						this.playingIndex = index;
					}
				}
			},
			
			pauseAllVideos() {
				Object.keys(this.videoContexts).forEach(index => {
					if (this.videoContexts[index]) {
						this.videoContexts[index].pause();
					}
				});
				
				this.videoList.forEach(video => {
					video.isPlaying = false;
				});
				
				this.playingIndex = -1;
			},
			
			retryLoadVideo(index) {
				const video = this.videoList[index];
				if (video) {
					video.hasError = false;
					
					this.$nextTick(() => {
						if (this.videoContexts[index]) {
							this.videoContexts[index].src = video.videounrealaddr;
						}
					});
				}
			},
			
			onVideoPlay(index) {
				const video = this.videoList[index];
				if (video) {
					video.isPlaying = true;
					this.playingIndex = index;
				}
			},
			
			onVideoPause(index) {
				const video = this.videoList[index];
				if (video) {
					video.isPlaying = false;
					if (this.playingIndex === index) {
						this.playingIndex = -1;
					}
				}
			},
			
			onVideoEnded(index) {
				const video = this.videoList[index];
				if (video) {
					video.isPlaying = false;
					if (this.playingIndex === index) {
						this.playingIndex = -1;
					}
				}
				
				if (index < this.videoList.length - 1) {
					this.currentIndex = index + 1;
					this.playCurrentVideo();
				} else if (this.hasMore && !this.isLoading) {
					this.loadVideos().then(() => {
						this.currentIndex = index + 1;
						this.createVideoContext();
						this.playCurrentVideo();
					});
				}
			},
			
			onVideoError(index) {
				const video = this.videoList[index];
				if (video) {
					video.hasError = true;
					video.isPlaying = false;
					if (this.playingIndex === index) {
						this.playingIndex = -1;
					}
				}
			},
			
			loadVideos() {
				return new Promise((resolve, reject) => {
					if (this.isLoading) {
						resolve();
						return;
					}
					
					if (!this.hasMore) {
						resolve();
						return;
					}
					
					this.isLoading = true;
					
					const requestPageSize = 1;
					
					const option = {
						pageNo: this.pageNo,
						pageSize: requestPageSize
					};
					
					const api = `${this.serveraddr}:${this.serverport}/api/findVideos?token=${this.servertoken}`;
					
					if (this.videoList.length === 0) {
						uni.showLoading({
							title: '加载中...',
							mask: true
						});
					}
					
					uni.request({
						url: api,
						method: "POST",
						header: {
							'content-type': 'application/x-www-form-urlencoded'
						},
						data: option,
						success: (res) => {
							if (res.data.resCode == "000001" && res.data.record && res.data.record.content) {
								const content = res.data.record.content || [];
								
								if (content.length > 0) {
									content.forEach(video => {
										if (video.videounrealaddr) {
											video.videounrealaddr = this.normalizePath(video.videounrealaddr);
										}
										
										if (video.videocover) {
											video.videocover = this.normalizePath(video.videocover);
										}
										
										video.isPlaying = false;
										video.hasError = false;
										video.isLoading = false;
									});
									
									this.videoList = [...this.videoList, ...content];
									this.pageNo++;
									
									const page = res.data.record.page;
									this.hasMore = this.pageNo <= page.totalPages;
									
									this.$nextTick(() => {
										content.forEach((video, index) => {
											const actualIndex = this.videoList.length - content.length + index;
											this.videoContexts[actualIndex] = uni.createVideoContext(`video-${actualIndex}`, this);
										});
										
										if (this.videoList.length === 1 && this.hasMore) {
											this.loadVideos().then(() => {
												this.playCurrentVideo();
												resolve();
											});
										} else if (this.videoList.length === 1) {
											this.playCurrentVideo();
											resolve();
										} else {
											resolve();
										}
									});
								} else {
									this.hasMore = false;
									resolve();
								}
							} else {
								uni.showToast({
									title: res.data.resMsg || '获取数据失败',
									icon: 'none'
								});
								reject(new Error(res.data.resMsg || '获取数据失败'));
							}
						},
						fail: (error) => {
							uni.showToast({
								title: '网络请求失败',
								icon: 'none'
							});
							reject(error);
						},
						complete: () => {
							this.isLoading = false;
							if (this.videoList.length <= 2) {
								uni.hideLoading();
							}
						}
					});
				});
			},
			
			normalizePath(rawPath) {
				if (!rawPath) return '';
				let normalizedPath = rawPath.replace(/\\/g, '/');
				if (!normalizedPath.startsWith('/')) {
					normalizedPath = '/' + normalizedPath;
				}
				normalizedPath = normalizedPath.replace(/\/+/g, '/');
				// 对路径段进行URL编码，解决中文路径在真机请求中被后端拒绝的问题
				const encodedPath = normalizedPath.split('/').map(segment => encodeURIComponent(segment)).join('/');
				const baseUrl = this.serveraddr + ':' + this.serverport; 
				const tokenParam = 'apptoken=' + this.servertoken;
				return `${baseUrl}${encodedPath}?${tokenParam}`;
			},
			
			goBack() {
				uni.navigateBack();
			},
			
			parseTags(tagString) {
				if (!tagString) return [];
				return tagString.split(',').filter(tag => tag.trim());
			},
			
			formatCount(count) {
				if (!count || count === 0) return '';
				
				if (count < 1000) {
					return count.toString();
				} else if (count < 10000) {
					return (count / 1000).toFixed(1) + 'k';
				} else {
					return (count / 10000).toFixed(1) + 'w';
				}
			},
			
			cleanup() {
				this.pauseAllVideos();
				this.videoContexts = {};
			}
		}
	}
</script>

<style>
	.video-container {
		width: 100%;
		height: 100vh;
		background-color: #000;
		position: relative;
		overflow: hidden;
	}
	
	.header-nav {
		position: fixed;
		top: 0;
		left: 0;
		right: 0;
		z-index: 100;
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 20rpx 30rpx;
		background: linear-gradient(to bottom, rgba(0,0,0,0.7), transparent);
		color: #fff;
	}
	
	.nav-left, .nav-right {
		width: 60rpx;
		height: 60rpx;
		display: flex;
		justify-content: center;
		align-items: center;
		font-size: 40rpx;
	}
	
	.nav-title {
		font-size: 32rpx;
		font-weight: bold;
	}
	
	.video-swiper {
		width: 100%;
		height: 100%;
	}
	
	.video-item {
		width: 100%;
		height: 100%;
	}
	
	.video-wrapper {
		width: 100%;
		height: 100%;
		position: relative;
	}
	
	.video-player {
		width: 100%;
		height: 100%;
		background-color: #000;
	}
	
	.video-error {
		position: absolute;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		display: flex;
		flex-direction: column;
		align-items: center;
		color: #fff;
	}
	
	.error-text {
		font-size: 28rpx;
		margin-bottom: 20rpx;
	}
	
	.retry-btn {
		font-size: 28rpx;
		padding: 10rpx 30rpx;
		background-color: rgba(255,255,255,0.2);
		border-radius: 30rpx;
	}
	
	.play-btn {
		position: absolute;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: 120rpx;
		height: 120rpx;
		background-color: rgba(0,0,0,0.5);
		border-radius: 50%;
		display: flex;
		justify-content: center;
		align-items: center;
	}
	
	.play-icon {
		color: #fff;
		font-size: 60rpx;
		margin-left: 10rpx;
	}
	
	.video-overlay {
		position: absolute;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		display: flex;
		flex-direction: column;
		justify-content: space-between;
		padding: 100rpx 30rpx 50rpx;
		pointer-events: none;
	}
	
	.action-bar {
		position: absolute;
		right: 20rpx;
		bottom: 200rpx;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 30rpx;
		pointer-events: auto;
	}
	
	.bottom-info {
		position: absolute;
		bottom: 50rpx;
		left: 30rpx;
		right: 30rpx;
		pointer-events: auto;
	}
	
	.author-info {
		display: flex;
		align-items: center;
		margin-bottom: 20rpx;
	}
	
	.author-name {
		font-size: 32rpx;
		font-weight: bold;
		color: #fff;
		text-shadow: 0 0 10rpx rgba(0,0,0,0.5);
	}
	
	.video-desc {
		margin-bottom: 20rpx;
	}
	
	.desc-text {
		font-size: 28rpx;
		color: #fff;
		line-height: 1.5;
		text-shadow: 0 0 10rpx rgba(0,0,0,0.5);
	}
	
	.tags {
		margin-top: 10rpx;
		display: flex;
		flex-wrap: wrap;
		gap: 10rpx;
	}
	
	.tag {
		font-size: 24rpx;
		color: #fe2c55;
	}
	
	.loading-container {
		position: absolute;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
	}
	
	.bottom-loading {
		position: absolute;
		bottom: 100rpx;
		left: 0;
		right: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		padding: 20rpx;
		background: linear-gradient(to top, rgba(0,0,0,0.7), transparent);
	}
	
	.loading-spinner {
		width: 40rpx;
		height: 40rpx;
		border: 4rpx solid rgba(255,255,255,0.3);
		border-top: 4rpx solid #fff;
		border-radius: 50%;
		animation: spin 1s linear infinite;
		margin-bottom: 10rpx;
	}
	
	@keyframes spin {
		0% { transform: rotate(0deg); }
		100% { transform: rotate(360deg); }
	}
	
	.loading-text {
		color: #fff;
		font-size: 28rpx;
		text-align: center;
	}
	
	.empty-container {
		position: absolute;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
	}
	
	.empty-text {
		color: #fff;
		font-size: 28rpx;
		text-align: center;
	}
</style>