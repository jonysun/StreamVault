<template>
	<view class="container">
		<!-- 搜索栏 -->
		<view class="search-header">
			<view class="search-box">
				<uni-icons type="search" size="18" color="#999"></uni-icons>
				<input 
					class="search-input" 
					v-model="searchKey"
					placeholder="搜索标题/作者/平台" 
					placeholder-class="placeholder"
					confirm-type="search"
					@confirm="handleSearch"
				/>
				<view 
					v-if="searchKey" 
					class="clear-btn"
					@tap="clearSearch"
				>
					<uni-icons type="clear" size="18" color="#ccc"></uni-icons>
				</view>
			</view>
			<!-- 筛选标签 -->
			<view class="filter-tabs">
				<view 
					class="filter-tab" 
					:class="{ active: currentPlatform === '' }"
					@tap="filterByPlatform('')"
				>全部</view>
				<view 
					class="filter-tab" 
					:class="{ active: currentPlatform === 'douyin' }"
					@tap="filterByPlatform('douyin')"
				>抖音</view>
				<view 
					class="filter-tab" 
					:class="{ active: currentPlatform === 'instagram' }"
					@tap="filterByPlatform('instagram')"
				>Instagram</view>
			</view>
		</view>

		<!-- 图文列表 -->
		<view class="content-wrap">
			<view class="graphic-list">
				<view 
					class="graphic-card" 
					v-for="(item, index) in list" 
					:key="item.id"
					@tap="previewGraphic(item)"
				>
					<view class="graphic-card-inner">
						<!-- 图片预览区 -->
						<view class="card-images">
							<image 
								class="cover-image" 
								:src="getFirstImageUrl(item)" 
								mode="aspectFill"
								lazy-load="true"
							></image>
							<view class="image-count" v-if="getImageCount(item) > 1">
								<uni-icons type="image" size="12" color="#fff"></uni-icons>
								<text class="count-text">{{ getImageCount(item) }}</text>
							</view>
							<view class="video-badge" v-if="hasVideo(item)">
								<uni-icons type="videocam" size="12" color="#fff"></uni-icons>
							</view>
							<!-- 平台标签 -->
							<view class="platform-badge" :class="item.platform">
								{{ getPlatformName(item.platform) }}
							</view>
						</view>

						<!-- 内容信息 -->
						<view class="card-content">
							<text class="card-title">{{ item.title || '无标题' }}</text>
							<view class="card-meta">
								<view class="meta-left">
									<text class="author">@{{ item.author || '未知' }}</text>
								</view>
								<text class="create-time">{{ formatDate(item.createtime) }}</text>
							</view>
						</view>
					</view>
				</view>
			</view>

			<!-- 加载更多 -->
			<view class="loading-more" v-if="isLoading && list.length > 0">
				<view class="loading-spinner"></view>
				<text class="loading-text">加载中...</text>
			</view>

			<!-- 空状态 -->
			<view class="empty-state" v-if="!isLoading && list.length === 0">
				<uni-icons type="image" size="48" color="#ccc"></uni-icons>
				<text class="empty-text">暂无图文内容</text>
			</view>
		</view>

		<!-- 图文预览弹窗 -->
		<uni-popup ref="previewPopup" type="bottom" background-color="#fff">
			<view class="preview-container" :class="{ 'preview-fullscreen': isFullScreen }" v-if="previewItem">
				<view class="preview-header">
					<view class="preview-title-row">
						<text class="preview-platform" :class="previewItem.platform">{{ getPlatformName(previewItem.platform) }}</text>
						<text class="preview-author">@{{ previewItem.author }}</text>
					</view>
					<view class="preview-header-actions">
						<view class="preview-fullscreen-btn" @tap="toggleFullScreen">
							<uni-icons :type="isFullScreen ? 'minus' : 'redo'" size="18" color="#666"></uni-icons>
						</view>
						<view class="preview-close" @tap="closePreview">
							<uni-icons type="closeempty" size="20" color="#999"></uni-icons>
						</view>
					</view>
				</view>

				<!-- 图片轮播 -->
				<swiper 
					class="preview-swiper" 
					:class="{ 'preview-swiper-full': isFullScreen }"
					:indicator-dots="previewImages.length > 1" 
					indicator-color="rgba(255,255,255,0.4)" 
					indicator-active-color="#fff"
					:autoplay="false"
					circular
				>
					<swiper-item v-for="(img, idx) in previewImages" :key="idx">
						<view class="swiper-item-content">
							<video 
								v-if="isVideoUrl(img)" 
								:src="img" 
								class="preview-video"
								controls
								:show-center-play-btn="true"
							></video>
							<image 
								v-else
								:src="img" 
								class="preview-image" 
								mode="aspectFit"
								@tap="previewFullScreen(img)"
							></image>
						</view>
					</swiper-item>
				</swiper>

				<!-- 内容信息 -->
				<view class="preview-info">
					<text class="preview-desc">{{ previewItem.content || previewItem.title }}</text>
					<view class="preview-footer">
						<text class="preview-time">{{ formatDate(previewItem.createtime) }}</text>
						<view class="preview-actions">
							<view class="action-btn" @tap="copyOriginalLink(previewItem)">
								<uni-icons type="link" size="16" color="#666"></uni-icons>
								<text class="action-text">复制链接</text>
							</view>
						</view>
					</view>
				</view>
			</view>
		</uni-popup>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				list: [],
				pageNo: 1,
				isLoading: false,
				finished: false,
				searchKey: '',
				currentPlatform: '',
				previewItem: null,
				previewImages: [],
				isFullScreen: false
			}
		},
		onLoad() {
			this.checkLogin();
		},
		onPullDownRefresh() {
			this.pageNo = 1;
			this.finished = false;
			this.list = [];
			this.getList(1);
		},
		onReachBottom() {
			if (!this.finished && !this.isLoading) {
				this.getList(this.pageNo);
			}
		},
		methods: {
			checkLogin() {
				const adminCookie = uni.getStorageSync('adminCookie');
				if (!adminCookie) {
					uni.showToast({
						title: '请先登录',
						icon: 'none',
						duration: 1500
					});
					setTimeout(() => {
						uni.redirectTo({
							url: '/pages/admin/login'
						});
					}, 1500);
				} else {
					this.getList(1);
				}
			},
			clearSearch() {
				this.searchKey = '';
				this.pageNo = 1;
				this.finished = false;
				this.list = [];
				this.getList(1);
			},
			handleSearch() {
				this.pageNo = 1;
				this.finished = false;
				this.list = [];
				this.getList(1);
			},
			filterByPlatform(platform) {
				this.currentPlatform = platform;
				this.pageNo = 1;
				this.finished = false;
				this.list = [];
				this.getList(1);
			},
			getList(page) {
				if (this.isLoading) return;
				this.isLoading = true;

				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');
				const servertoken = uni.getStorageSync('servertoken');

				if (!serveraddr || !serverport || !adminCookie) {
					this.isLoading = false;
					return;
				}

				uni.showLoading({ title: '加载中...' });

				const data = { pageNo: page };
				if (this.searchKey) {
					data.title = this.searchKey;
					data.content = this.searchKey;
				}
				if (this.currentPlatform) {
					data.platform = this.currentPlatform;
				}

				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/findGraphicContentList`,
					method: 'POST',
					header: {
						'content-type': 'application/x-www-form-urlencoded',
						'Cookie': adminCookie
					},
					data: data,
					success: (res) => {
						if (res.data.resCode === '000001') {
							const content = res.data.record.content || [];
							content.forEach(item => {
								item._images = this.parseImages(item.images);
							});
							if (page === 1) {
								this.list = content;
							} else {
								this.list = this.list.concat(content);
							}
							this.pageNo++;
							this.finished = res.data.record.page ? (this.pageNo > res.data.record.page.totalPages) : (content.length < 15);
						} else {
							uni.showToast({ title: res.data.message || '获取失败', icon: 'none' });
							uni.removeStorageSync('adminCookie');
							uni.removeStorageSync('adminCookieExpire');
							setTimeout(() => {
								uni.redirectTo({ url: '/pages/admin/login' });
							}, 1500);
						}
					},
					fail: () => {
						uni.showToast({ title: '网络错误', icon: 'none' });
						uni.removeStorageSync('adminCookie');
						uni.removeStorageSync('adminCookieExpire');
						setTimeout(() => {
							uni.redirectTo({ url: '/pages/admin/login' });
						}, 1500);
					},
					complete: () => {
						this.isLoading = false;
						uni.hideLoading();
						uni.stopPullDownRefresh();
					}
				});
			},
			parseImages(imagesStr) {
				if (!imagesStr) return [];
				try {
					const parsed = JSON.parse(imagesStr);
					const serveraddr = uni.getStorageSync('serveraddr');
					const serverport = uni.getStorageSync('serverport');
					const servertoken = uni.getStorageSync('servertoken');
					return parsed.map(path => {
						let normalizedPath = path.replace(/\\/g, '/');
						if (!normalizedPath.startsWith('/')) {
							normalizedPath = '/' + normalizedPath;
						}
						normalizedPath = normalizedPath.replace(/\/+/g, '/');
						return `${serveraddr}:${serverport}${normalizedPath}?apptoken=${servertoken}`;
					});
				} catch (e) {
					return [];
				}
			},
			getFirstImageUrl(item) {
				if (item._images && item._images.length > 0) {
					const firstImg = item._images.find(img => !this.isVideoUrl(img)) || item._images[0];
					return firstImg;
				}
				return '';
			},
			getImageCount(item) {
				return (item._images && item._images.length) || 0;
			},
			hasVideo(item) {
				return item._images && item._images.some(img => this.isVideoUrl(img));
			},
			isVideoUrl(url) {
				if (!url) return false;
				return url.toLowerCase().endsWith('.mp4') || url.toLowerCase().includes('.mp4');
			},
			getPlatformName(platform) {
				const map = {
					'douyin': '抖音',
					'instagram': 'IG',
					'xiaohongshu': '小红书'
				};
				return map[platform] || platform;
			},
			formatDate(timeStr) {
				if (!timeStr) return '';
				try {
					const date = new Date(timeStr);
					const now = new Date();
					const diff = now - date;
					const days = Math.floor(diff / (1000 * 60 * 60 * 24));
					if (days === 0) return '今天';
					if (days === 1) return '昨天';
					if (days < 7) return `${days}天前`;
					if (days < 30) return `${Math.floor(days / 7)}周前`;
					const y = date.getFullYear();
					const m = String(date.getMonth() + 1).padStart(2, '0');
					const d = String(date.getDate()).padStart(2, '0');
					return `${y}-${m}-${d}`;
				} catch (e) {
					return timeStr;
				}
			},
			previewGraphic(item) {
				this.previewItem = item;
				this.previewImages = item._images || [];
				this.isFullScreen = false;
				this.$refs.previewPopup.open();
			},
			closePreview() {
				this.isFullScreen = false;
				this.$refs.previewPopup.close();
			},
			toggleFullScreen() {
				this.isFullScreen = !this.isFullScreen;
			},
			previewFullScreen(url) {
				const imageUrls = this.previewImages.filter(img => !this.isVideoUrl(img));
				if (imageUrls.length > 0) {
					uni.previewImage({
						urls: imageUrls,
						current: url
					});
				}
			},
			copyOriginalLink(item) {
				if (item.originaladdress) {
					uni.setClipboardData({
						data: item.originaladdress,
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

.search-header {
	position: fixed;
	top: 0;
	left: 0;
	right: 0;
	z-index: 100;
	background: #fff;
	padding: 16rpx 24rpx 0;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06);
}

.search-box {
	display: flex;
	align-items: center;
	background: #f5f5f5;
	border-radius: 36rpx;
	padding: 16rpx 24rpx;
	gap: 12rpx;
}

.search-input {
	flex: 1;
	height: 40rpx;
	font-size: 28rpx;
	color: #333;
}

.placeholder {
	color: #999;
}

.clear-btn {
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 4rpx;
}

.filter-tabs {
	display: flex;
	gap: 16rpx;
	padding: 20rpx 0 16rpx;
}

.filter-tab {
	padding: 8rpx 24rpx;
	border-radius: 24rpx;
	font-size: 24rpx;
	color: #666;
	background: #f5f5f5;
	transition: all 0.3s;
}

.filter-tab.active {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	color: #fff;
	font-weight: 500;
}

.content-wrap {
	padding-top: 180rpx;
	padding-bottom: 32rpx;
}

.graphic-list {
	display: flex;
	flex-wrap: wrap;
	padding: 0 16rpx;
}

.graphic-card {
	width: 50%;
	background: transparent;
	padding: 8rpx;
	box-sizing: border-box;
}

.graphic-card:active {
	transform: scale(0.98);
}

.graphic-card-inner {
	background: #fff;
	border-radius: 16rpx;
	overflow: hidden;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.05);
}

.card-images {
	position: relative;
	width: 100%;
	padding-top: 100%;
}

.cover-image {
	position: absolute;
	top: 0;
	left: 0;
	width: 100%;
	height: 100%;
}

.image-count {
	position: absolute;
	top: 12rpx;
	right: 12rpx;
	background: rgba(0, 0, 0, 0.6);
	border-radius: 16rpx;
	padding: 4rpx 12rpx;
	display: flex;
	align-items: center;
	gap: 4rpx;
}

.count-text {
	font-size: 20rpx;
	color: #fff;
}

.video-badge {
	position: absolute;
	bottom: 12rpx;
	left: 12rpx;
	background: rgba(0, 0, 0, 0.6);
	border-radius: 50%;
	width: 40rpx;
	height: 40rpx;
	display: flex;
	align-items: center;
	justify-content: center;
}

.platform-badge {
	position: absolute;
	bottom: 12rpx;
	right: 12rpx;
	padding: 4rpx 12rpx;
	border-radius: 8rpx;
	font-size: 20rpx;
	color: #fff;
	font-weight: 500;
}

.platform-badge.douyin {
	background: linear-gradient(135deg, #fe2c55, #ff6b81);
}

.platform-badge.instagram {
	background: linear-gradient(135deg, #833ab4, #fd1d1d, #fcb045);
}

.card-content {
	padding: 16rpx;
}

.card-title {
	font-size: 24rpx;
	color: #333;
	line-height: 1.4;
	display: -webkit-box;
	-webkit-line-clamp: 2;
	-webkit-box-orient: vertical;
	overflow: hidden;
	text-overflow: ellipsis;
	min-height: 68rpx;
}

.card-meta {
	display: flex;
	align-items: center;
	justify-content: space-between;
	margin-top: 10rpx;
}

.meta-left {
	display: flex;
	align-items: center;
	gap: 8rpx;
	flex: 1;
	overflow: hidden;
}

.author {
	font-size: 20rpx;
	color: #999;
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
}

.create-time {
	font-size: 20rpx;
	color: #ccc;
	flex-shrink: 0;
	margin-left: 8rpx;
}

.loading-more {
	display: flex;
	flex-direction: column;
	align-items: center;
	padding: 40rpx 0;
	gap: 12rpx;
}

.loading-spinner {
	width: 36rpx;
	height: 36rpx;
	border: 4rpx solid #e0e0e0;
	border-top-color: #2563eb;
	border-radius: 50%;
	animation: spin 0.8s linear infinite;
}

@keyframes spin {
	0% { transform: rotate(0deg); }
	100% { transform: rotate(360deg); }
}

.loading-text {
	font-size: 24rpx;
	color: #999;
}

.empty-state {
	display: flex;
	flex-direction: column;
	align-items: center;
	padding: 120rpx 0;
	gap: 20rpx;
}

.empty-text {
	font-size: 28rpx;
	color: #999;
}

/* 预览弹窗样式 */
.preview-container {
	max-height: 85vh;
	overflow-y: auto;
	border-radius: 24rpx 24rpx 0 0;
	transition: max-height 0.3s ease;
}

.preview-container.preview-fullscreen {
	max-height: 100vh;
	border-radius: 0;
}

.preview-header-actions {
	display: flex;
	align-items: center;
	gap: 16rpx;
}

.preview-fullscreen-btn {
	padding: 8rpx;
}

.preview-header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	padding: 24rpx;
	border-bottom: 1rpx solid #f0f0f0;
}

.preview-title-row {
	display: flex;
	align-items: center;
	gap: 12rpx;
}

.preview-platform {
	padding: 4rpx 16rpx;
	border-radius: 8rpx;
	font-size: 22rpx;
	color: #fff;
	font-weight: 500;
}

.preview-platform.douyin {
	background: linear-gradient(135deg, #fe2c55, #ff6b81);
}

.preview-platform.instagram {
	background: linear-gradient(135deg, #833ab4, #fd1d1d, #fcb045);
}

.preview-author {
	font-size: 28rpx;
	color: #333;
	font-weight: 500;
}

.preview-close {
	padding: 8rpx;
}

.preview-swiper {
	width: 100%;
	height: 600rpx;
	background: #000;
}

.preview-swiper-full {
	height: calc(100vh - 260rpx);
}

.swiper-item-content {
	width: 100%;
	height: 100%;
	display: flex;
	align-items: center;
	justify-content: center;
}

.preview-image {
	width: 100%;
	height: 100%;
}

.preview-video {
	width: 100%;
	height: 100%;
}

.preview-info {
	padding: 24rpx;
}

.preview-desc {
	font-size: 28rpx;
	color: #333;
	line-height: 1.6;
	margin-bottom: 20rpx;
}

.preview-footer {
	display: flex;
	align-items: center;
	justify-content: space-between;
}

.preview-time {
	font-size: 24rpx;
	color: #999;
}

.preview-actions {
	display: flex;
	gap: 24rpx;
}

.action-btn {
	display: flex;
	align-items: center;
	gap: 6rpx;
}

.action-text {
	font-size: 24rpx;
	color: #666;
}

@media (max-width: 750rpx) {
	.graphic-card {
		width: 100%;
	}
	
	.card-images {
		padding-top: 56.25%;
	}
}
</style>
