<template>
	<view class="container">
		<!-- 搜索头部 -->
		<view class="search-header">
			<view class="search-box">
				<view class="search-input-wrapper">
					<uni-icons type="search" size="18" color="#999"></uni-icons>
					<input class="search-input" v-model="searchKey" placeholder="搜索视频..."
						placeholder-class="placeholder" @confirm="handleSearch" @input="debounceSearch" />
					<view class="clear-btn" v-if="searchKey" @tap="clearSearch">
						<uni-icons type="clear" size="18" color="#ccc"></uni-icons>
					</view>
				</view>
			</view>
			<view class="filter-tabs">
				<view class="filter-tab" :class="{ active: !showFilter }" @tap="showFilter = false">视频</view>
				<view class="filter-tab" :class="{ active: showFilter }" @tap="toggleFilter">筛选</view>
			</view>

			<!-- 筛选面板 -->
			<view v-if="showFilter" class="filter-panel">
				<view class="filter-row">
					<text class="filter-label">视频平台</text>
					<input class="filter-input" v-model="selectedPlatform" placeholder="输入平台名称..."
						placeholder-class="placeholder" />
				</view>
				<view class="filter-row">
					<text class="filter-label">视频标签</text>
					<input class="filter-input" v-model="selectedTag" placeholder="输入标签名称..."
						placeholder-class="placeholder" />
				</view>
				<view class="filter-actions">
					<view class="filter-action-btn clear-btn" @click="clearFilters">清空</view>
					<view class="filter-action-btn apply-btn" @click="applyFilters">应用筛选</view>
				</view>
			</view>
		</view>

		<!-- 视频列表 -->
		<view class="content-wrap">
			<view class="video-list">
				<!-- 骨架屏 -->
				<view class="video-card" v-for="n in 6" :key="n" v-if="isLoading && list.length === 0">
					<view class="video-card-inner skeleton-card">
						<view class="skeleton-cover">
							<view class="skeleton-shimmer"></view>
						</view>
						<view class="skeleton-info">
							<view class="skeleton-title"><view class="skeleton-shimmer"></view></view>
							<view class="skeleton-meta"><view class="skeleton-shimmer"></view></view>
						</view>
					</view>
				</view>

				<!-- 视频卡片 -->
				<view class="video-card" v-for="(item, index) in list" :key="index" @tap="playVideo(item)">
					<view class="video-card-inner">
						<view class="video-cover" @click.stop="togglePlayBtn(index)" @touchstart="showPlayButton(index)"
							@touchend="hidePlayButton(index)">
							<image class="cover-image" :src="item.videocover" mode="aspectFill" lazy-load="true"
								@load="onImageLoad(index)" @error="onImageError(index)"
								v-show="!item.imageLoading && !item.imageError"></image>
							<view class="privacy-mask" v-if="item.videoprivacy === '1'">
								<uni-icons type="locked" size="18" color="#fff"></uni-icons>
							</view>
							<view class="image-error" v-if="item.imageError && !item.imageLoading">
								<text class="error-text">加载失败</text>
							</view>
							<view class="image-skeleton" v-if="item.imageLoading">
								<view class="skeleton-shimmer"></view>
							</view>
							<view class="play-btn" :class="{ 'show': item.showPlayBtn }" @click.stop="playVideo(item)">
								<text class="play-icon">▶</text>
							</view>
						</view>
						<view class="video-info">
							<text class="video-title">{{ item.videoname }}</text>
							<view class="video-meta">
								<view class="meta-tags">
									<view class="tag platform" v-if="item.videoplatform">{{ item.videoplatform }}</view>
									<view class="tag videotype" v-if="item.videotag">{{ item.videotag }}</view>
								</view>
								<text class="video-time">{{ formatTime(item.createtime) }}</text>
							</view>
						</view>
					</view>
				</view>
			</view>

			<!-- 加载更多 -->
			<view class="loading-more" v-if="list.length > 0 && isLoading">
				<view class="loading-spinner"></view>
				<text class="loading-text">加载中...</text>
			</view>

			<!-- 空状态 -->
			<view class="empty-state" v-if="!isLoading && list.length === 0">
				<uni-icons type="videocam" size="48" color="#ccc"></uni-icons>
				<text class="empty-text">暂无视频内容</text>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				list: [],
				fetchPageNum: 1,
				isLoading: false,
				searchKey: '',
				isSearching: false,
				showFilter: false,
				selectedPlatform: '',
				selectedTag: '',
				searchRequestId: 0,
				searchTimer: null
			}
		},
		onLoad() {
			this.getData(this.fetchPageNum);
		},
		onUnload() {
			if (this.searchTimer) {
				clearTimeout(this.searchTimer);
				this.searchTimer = null;
			}
		},
		onPullDownRefresh() {
			this.fetchPageNum = 1;
			this.list = [];
			this.getData(this.fetchPageNum);
			setTimeout(() => { uni.stopPullDownRefresh(); }, 1000);
		},
		onReachBottom() {
			if (!this.isLoading) {
				this.getData(this.fetchPageNum);
			}
		},
		methods: {
			normalizePath(rawPath,server,port,token) {
				if (!rawPath) return '';
				let normalizedPath = rawPath.replace(/\\/g, '/');
				if (!normalizedPath.startsWith('/')) {
					normalizedPath = '/' + normalizedPath;
				}
				normalizedPath = normalizedPath.replace(/\/+/g, '/');
				// 对路径段进行URL编码，解决中文路径在真机请求中被后端拒绝的问题
				const encodedPath = normalizedPath.split('/').map(segment => encodeURIComponent(segment)).join('/');
				const baseUrl = server+':'+port; 
				const tokenParam = 'apptoken='+token;
				return `${baseUrl}${encodedPath}?${tokenParam}`;
			},
			debounceSearch() {
				if (this.searchTimer) clearTimeout(this.searchTimer);
				this.searchTimer = setTimeout(() => { this.handleSearch(); }, 300);
			},
			clearSearch() {
				if (this.searchTimer) { clearTimeout(this.searchTimer); this.searchTimer = null; }
				this.searchKey = '';
				this.isSearching = false;
				this.isLoading = false;
				this.fetchPageNum = 1;
				this.list = [];
				this.getData(this.fetchPageNum);
			},
			toggleFilter() { this.showFilter = !this.showFilter; },
			clearFilters() {
				this.selectedPlatform = '';
				this.selectedTag = '';
				this.applyFilters();
			},
			applyFilters() {
				this.fetchPageNum = 1;
				this.isSearching = true;
				this.isLoading = false;
				this.showFilter = false;
				this.list = [];
				this.getData(this.fetchPageNum);
			},
			onImageLoad(index) {
				if (this.list[index]) {
					this.$set(this.list[index], 'imageLoading', false);
					this.$set(this.list[index], 'imageError', false);
				}
			},
			onImageError(index) {
				if (this.list[index]) {
					this.$set(this.list[index], 'imageLoading', false);
					this.$set(this.list[index], 'imageError', true);
				}
			},
			togglePlayBtn(index) {
				if (this.list[index]) {
					const s = this.list[index].showPlayBtn || false;
					this.$set(this.list[index], 'showPlayBtn', !s);
				}
			},
			showPlayButton(index) {
				if (this.list[index]) this.$set(this.list[index], 'showPlayBtn', true);
			},
			hidePlayButton(index) {
				if (this.list[index]) {
					setTimeout(() => { this.$set(this.list[index], 'showPlayBtn', false); }, 2000);
				}
			},
			formatTime(timeStr) {
				if (!timeStr) return '';
				try {
					const date = new Date(timeStr);
					const now = new Date();
					const diff = now - date;
					const days = Math.floor(diff / (1000 * 60 * 60 * 24));
					if (days === 0) return '今天';
					if (days === 1) return '昨天';
					if (days < 7) return `${days}天前`;
					return date.toLocaleDateString();
				} catch (e) { return timeStr; }
			},
			handleSearch() {
				if (this.isLoading) return;
				this.searchRequestId++;
				const currentRequestId = this.searchRequestId;
				this.isSearching = true;
				this.isLoading = true;
				this.fetchPageNum = 1;
				this.list = [];

				var that = this;
				var serveraddr = uni.getStorageSync('serveraddr');
				var serverport = uni.getStorageSync('serverport');
				var servertoken = uni.getStorageSync('servertoken');
				var api = `${serveraddr}:${serverport}/api/findVideos?token=${servertoken}`;

				uni.showLoading({ title: '搜索中...' });

				var searchData = {
					videodesc: that.searchKey,
					videoname: that.searchKey
				};
				if (this.selectedPlatform) searchData.videoplatform = this.selectedPlatform;
				if (this.selectedTag) searchData.videotag = this.selectedTag;

				uni.request({
					url: api, method: "POST",
					header: { 'content-type': 'application/x-www-form-urlencoded' },
					data: searchData,
					success(res) {
						if (currentRequestId !== that.searchRequestId) return;
						that.isLoading = false;
						if (res.data.resCode == "000001") {
							var content = res.data.record.content;
							for (var i = 0; i < content.length; i++) {
								content[i].videounrealaddr = that.normalizePath(content[i].videounrealaddr,serveraddr,serverport,servertoken);
								content[i].videocover = that.normalizePath(content[i].videocover,serveraddr,serverport,servertoken);
								content[i].imageLoading = true;
								content[i].imageError = false;
								content[i].showPlayBtn = false;
							}
							that.list = content;
							that.fetchPageNum = 2;
						} else {
							uni.showToast({ title: res.data.resMsg || '搜索失败', icon: 'none' });
						}
					},
					fail(error) {
						if (currentRequestId !== that.searchRequestId) return;
						that.isLoading = false;
						uni.showToast({ title: '搜索失败', icon: 'none' });
					},
					complete() {
						if (currentRequestId !== that.searchRequestId) return;
						uni.hideLoading();
					}
				});
			},
			getData(page) {
				if (this.isLoading) return;
				this.isLoading = true;

				var that = this;
				var option = { pageNo: that.fetchPageNum };
				if (this.selectedPlatform) option.videoplatform = this.selectedPlatform;
				if (this.selectedTag) option.videotag = this.selectedTag;

				var serveraddr = uni.getStorageSync('serveraddr');
				var serverport = uni.getStorageSync('serverport');
				var servertoken = uni.getStorageSync('servertoken');
				var api = `${serveraddr}:${serverport}/api/findVideos?token=${servertoken}`;

				uni.request({
					url: api, method: "POST",
					header: { 'content-type': 'application/x-www-form-urlencoded' },
					data: option,
					success(res) {
						that.isLoading = false;
						if (res.data.resCode == "000001") {
							var temp = that.list;
							if (that.fetchPageNum === 1) {
								that.list = [];
								uni.showToast({ icon: 'none', title: '刷新成功' });
							}
							var content = res.data.record.content;
							for (var i = 0; i < content.length; i++) {
								content[i].videounrealaddr = that.normalizePath(content[i].videounrealaddr,serveraddr,serverport,servertoken);
								content[i].videocover = that.normalizePath(content[i].videocover,serveraddr,serverport,servertoken);
								content[i].imageLoading = true;
								content[i].imageError = false;
								content[i].showPlayBtn = false;
							}
							that.list = temp.concat(content);
							that.fetchPageNum = that.fetchPageNum + 1;
						} else {
							uni.showToast({ title: res.data.resMsg || '获取数据失败', icon: 'none' });
						}
					},
					fail(error) {
						that.isLoading = false;
						uni.showToast({ title: '网络请求失败', icon: 'none' });
					}
				});
			},
			playVideo(item) {
				if (item.videoprivacy === 1) {
					uni.showModal({
						title: '请输入密码', editable: true, placeholderText: '请输入访问密码',
						success: (res) => {
							if (res.confirm) {
								this.navigateToVideo(item);
							}
						}
					});
				} else {
					this.navigateToVideo(item);
				}
			},
			navigateToVideo(videoInfo) {
				uni.navigateTo({
					url: `/pages/video/videoPlay?videoInfo=${encodeURIComponent(JSON.stringify(videoInfo))}`
				});
			},
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
	top: 0; left: 0; right: 0;
	z-index: 100;
	background: #fff;
	padding: 16rpx 24rpx 0;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06);
}

.search-box {
	display: flex;
	align-items: center;
	gap: 12rpx;
}

.search-input-wrapper {
	flex: 1;
	display: flex;
	align-items: center;
	background: #f5f5f5;
	border-radius: 36rpx;
	padding: 14rpx 20rpx;
	gap: 10rpx;
}

.search-input {
	flex: 1;
	height: 40rpx;
	font-size: 28rpx;
	color: #333;
	border: none;
	outline: none;
	background: transparent;
}

.clear-btn {
	display: flex;
	align-items: center;
	padding: 4rpx;
}

.placeholder { color: #999; }

.filter-tabs {
	display: flex;
	gap: 16rpx;
	padding: 16rpx 0 12rpx;
}

.filter-tab {
	padding: 8rpx 24rpx;
	border-radius: 24rpx;
	font-size: 24rpx;
	color: #666;
	background: #f5f5f5;
}

.filter-tab.active {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	color: #fff;
	font-weight: 500;
}

.filter-panel {
	background: #fafafa;
	border-radius: 16rpx;
	padding: 20rpx;
	margin-top: 12rpx;
	border: 1rpx solid #f0f0f0;
	animation: slideDown 0.3s ease-out;
}

@keyframes slideDown {
	from { opacity: 0; transform: translateY(-16rpx); }
	to { opacity: 1; transform: translateY(0); }
}

.filter-row { margin-bottom: 16rpx; }
.filter-label { font-size: 24rpx; color: #666; margin-bottom: 8rpx; display: block; }
.filter-input {
	width: 100%; height: 68rpx; padding: 0 16rpx;
	background: #fff; border: 1rpx solid #e5e7eb; border-radius: 12rpx;
	font-size: 26rpx; color: #333; box-sizing: border-box;
}

.filter-actions { display: flex; gap: 16rpx; margin-top: 16rpx; }
.filter-action-btn {
	flex: 1; height: 68rpx;
	display: flex; align-items: center; justify-content: center;
	border-radius: 12rpx; font-size: 26rpx;
}
.filter-action-btn.clear-btn { background: #f0f0f0; color: #666; }
.filter-action-btn.apply-btn { background: linear-gradient(135deg, #2563eb, #3b82f6); color: #fff; }

.content-wrap { padding-top: 160rpx; }

/* 骨架屏 */
.skeleton-card .skeleton-cover {
	height: 200rpx; background: #f0f0f0; border-radius: 16rpx 16rpx 0 0;
	position: relative; overflow: hidden;
}
.skeleton-card .skeleton-info { padding: 16rpx; }
.skeleton-card .skeleton-title {
	height: 32rpx; background: #f0f0f0; border-radius: 8rpx;
	overflow: hidden; margin-bottom: 12rpx;
}
.skeleton-card .skeleton-meta {
	height: 24rpx; background: #f0f0f0; border-radius: 8rpx; overflow: hidden;
}
.skeleton-shimmer {
	position: absolute; top: 0; left: 0; width: 100%; height: 100%;
	background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
	background-size: 200% 100%;
	animation: loading 1.5s infinite;
}
@keyframes loading {
	0% { background-position: 200% 0; }
	100% { background-position: -200% 0; }
}

.video-list {
	display: flex;
	flex-wrap: wrap;
	padding: 0 24rpx;
}

.video-card {
	width: 50%;
	background: transparent;
	padding: 6rpx;
	box-sizing: border-box;
}

.video-card-inner {
	background: #fff;
	border-radius: 16rpx;
	overflow: hidden;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
	transition: transform 0.2s;
}

.video-card-inner:active { transform: scale(0.98); }

.video-cover {
	position: relative;
	width: 100%;
	padding-top: 56.25%;
}

.cover-image {
	position: absolute; top: 0; left: 0; width: 100%; height: 100%;
	object-fit: cover;
}

.image-skeleton {
	position: absolute; top: 0; left: 0; width: 100%; height: 100%;
	background: #f0f0f0; overflow: hidden;
}

.image-error {
	position: absolute; top: 0; left: 0; width: 100%; height: 100%;
	background: #f5f5f5; display: flex; align-items: center; justify-content: center;
}

.error-text { font-size: 20rpx; color: #999; }

.privacy-mask {
	position: absolute; top: 0; left: 0; width: 100%; height: 100%;
	background: rgba(0,0,0,0.3); backdrop-filter: blur(6px);
	display: flex; align-items: center; justify-content: center;
	z-index: 5;
}

.play-btn {
	position: absolute; top: 50%; left: 50%;
	transform: translate(-50%, -50%);
	width: 64rpx; height: 64rpx;
	background: rgba(0,0,0,0.5);
	border-radius: 50%;
	display: flex; align-items: center; justify-content: center;
	z-index: 10;
	opacity: 0; visibility: hidden;
	transition: all 0.3s ease; pointer-events: none;
}

.play-btn.show {
	opacity: 1; visibility: visible; pointer-events: auto;
	transform: translate(-50%, -50%) scale(1.1);
}

.play-icon { color: #fff; font-size: 28rpx; margin-left: 4rpx; }

.video-info { padding: 14rpx 16rpx; }

.video-title {
	font-size: 24rpx; font-weight: 500; color: #333;
	line-height: 1.4;
	display: -webkit-box; -webkit-line-clamp: 2;
	-webkit-box-orient: vertical; overflow: hidden;
	min-height: 68rpx;
}

.video-meta {
	display: flex; align-items: center; justify-content: space-between;
	margin-top: 8rpx;
}

.meta-tags { display: flex; gap: 6rpx; flex: 1; overflow: hidden; }

.tag {
	padding: 2rpx 10rpx; border-radius: 6rpx;
	font-size: 18rpx; flex-shrink: 0;
}

.tag.platform { background: #dbeafe; color: #2563eb; }
.tag.videotype { background: #fef3c7; color: #d97706; }

.video-time {
	font-size: 18rpx; color: #aaa;
	flex-shrink: 0; margin-left: 8rpx;
}

.loading-more {
	display: flex; flex-direction: column; align-items: center;
	padding: 40rpx 0; gap: 12rpx;
}

.loading-spinner {
	width: 36rpx; height: 36rpx;
	border: 4rpx solid #e0e0e0; border-top-color: #2563eb;
	border-radius: 50%; animation: spin 0.8s linear infinite;
}

@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }

.loading-text { font-size: 24rpx; color: #999; }

.empty-state {
	display: flex; flex-direction: column; align-items: center;
	padding: 120rpx 0; gap: 16rpx;
}

.empty-text { font-size: 28rpx; color: #999; }

@media (max-width: 750rpx) {
	.video-card { width: 100%; }
}
</style>
