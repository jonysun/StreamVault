<template>
	<view class="container">
		<!-- 搜索栏 -->
		<view class="search-header">
			<view class="search-box">
				<uni-icons type="search" size="18" color="#999"></uni-icons>
				<input 
					class="search-input" 
					v-model="searchKey"
					placeholder="搜索视频" 
					placeholder-class="placeholder"
					confirm-type="search"
					@confirm="handleSearch"
				/>
				<view v-if="searchKey" class="clear-btn" @tap="clearSearch">
					<uni-icons type="clear" size="18" color="#ccc"></uni-icons>
				</view>
			</view>
		</view>

		<!-- 视频列表 -->
		<view class="content-wrap">
			<view class="video-list">
				<view class="video-card" v-for="(item, index) in list" :key="index">
					<image class="video-cover" :src="item.videocover" mode="aspectFill" lazy-load="true"></image>
					<view class="video-info">
						<view class="info-content">
							<text class="video-title">{{item.videoname}}</text>
							<text class="video-desc">{{item.videodesc}}</text>
							<text class="video-time">{{item.createtime}}</text>
						</view>
						<view class="action-buttons">
							<button class="action-btn play" @tap="playVideo(item)">
								<uni-icons type="play-filled" size="14" color="#fff"></uni-icons>
							</button>
							<button class="action-btn delete" @tap="deleteVideo(item)">
								<uni-icons type="trash" size="14" color="#fff"></uni-icons>
							</button>
						</view>
					</view>
				</view>
			</view>

			<view class="loading-more" v-if="isLoading && list.length > 0">
				<view class="loading-spinner"></view>
				<text class="loading-text">加载中...</text>
			</view>

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
				searchKey: '',
				isSearching: false,
				isLoading: false
			}
		},
		onLoad() { this.checkLogin(); },
		onPullDownRefresh() {
			if (this.isSearching) { this.handleSearch(); }
			else { this.fetchPageNum = 1; this.list = []; this.getData(this.fetchPageNum); }
		},
		onReachBottom() {
			if (!this.isSearching && !this.isLoading) { this.getData(this.fetchPageNum); }
		},
		methods: {
			checkLogin() {
				const adminCookie = uni.getStorageSync('adminCookie');
				if (!adminCookie) {
					uni.showToast({ title: '请先登录', icon: 'none', duration: 1500 });
					setTimeout(() => { uni.redirectTo({ url: '/pages/admin/login' }); }, 1500);
				} else { this.getData(1); }
			},
			clearSearch() {
				this.searchKey = '';
				this.isSearching = false;
				this.fetchPageNum = 1;
				this.list = [];
				this.getData(this.fetchPageNum);
			},
			handleSearch() {
				if (!this.searchKey.trim()) return;
				this.isSearching = true;
				this.fetchPageNum = 1;
				this.list = [];
				this.getData(this.fetchPageNum);
			},
			getData(page) {
				if (this.isLoading) return;
				this.isLoading = true;

				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');
				const servertoken = uni.getStorageSync('servertoken');
				
				if (!serveraddr || !serverport || !adminCookie) { this.isLoading = false; return; }
				
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/findVideoDataList`,
					method: 'POST',
					header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': adminCookie },
					data: { pageNo: page, videodesc: this.searchKey, videoname: this.searchKey },
					success: (res) => {
						this.isLoading = false;
						if (res.data.resCode === '000001') {
							if (page === 1) this.list = [];
							const content = res.data.record.content;
							for (let i = 0; i < content.length; i++) {
								content[i].videounrealaddr = `${serveraddr}:${serverport}${content[i].videounrealaddr}?apptoken=${servertoken}`;
								content[i].videocover = `${serveraddr}:${serverport}${content[i].videocover}?apptoken=${servertoken}`;
							}
							this.list = this.list.concat(content);
							this.fetchPageNum++;
						} else {
							uni.showToast({ title: res.data.message || '获取数据失败', icon: 'none' });
							uni.removeStorageSync('adminCookie');
							uni.removeStorageSync('adminCookieExpire');
							setTimeout(() => { uni.redirectTo({ url: '/pages/admin/login' }); }, 1500);
						}
					},
					fail: () => {
						this.isLoading = false;
						uni.showToast({ title: '网络错误', icon: 'none' });
						uni.removeStorageSync('adminCookie');
						uni.removeStorageSync('adminCookieExpire');
						setTimeout(() => { uni.redirectTo({ url: '/pages/admin/login' }); }, 1500);
					},
					complete: () => { uni.stopPullDownRefresh(); }
				});
			},
			playVideo(item) {
				uni.navigateTo({ url: `/pages/video/videoPlay?videoInfo=${encodeURIComponent(JSON.stringify(item))}` });
			},
			deleteVideo(item) {
				uni.showModal({
					title: '确认删除', content: '确定要删除这个视频吗？',
					success: (res) => { if (res.confirm) this.confirmDelete(item); }
				});
			},
			confirmDelete(item) {
				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');
				
				uni.showLoading({ title: '删除中...' });
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/deleteVideoData?id=${item.id}`,
					method: 'GET',
					header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': adminCookie },
					success: (res) => {
						if (res.data.resCode === '000001') {
							uni.showToast({ title: '删除成功', icon: 'success' });
							const index = this.list.findIndex(v => v.id === item.id);
							if (index > -1) this.list.splice(index, 1);
						} else {
							uni.showToast({ title: res.data.message || '删除失败', icon: 'none' });
						}
					},
					fail: () => { uni.showToast({ title: '网络错误', icon: 'none' }); },
					complete: () => { uni.hideLoading(); }
				});
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
	top: 0; left: 0; right: 0;
	z-index: 100;
	background: #fff;
	padding: 16rpx 24rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.search-box {
	display: flex;
	align-items: center;
	background: #f5f5f5;
	border-radius: 36rpx;
	padding: 14rpx 24rpx;
	gap: 12rpx;
}

.search-input {
	flex: 1;
	height: 40rpx;
	font-size: 28rpx;
	color: #333;
}

.placeholder { color: #999; }

.clear-btn { display: flex; align-items: center; padding: 4rpx; }

.content-wrap { padding-top: 108rpx; }

.video-list { padding: 16rpx 24rpx; }

.video-card {
	background: #fff;
	border-radius: 16rpx;
	padding: 20rpx;
	margin-bottom: 16rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
	display: flex;
	gap: 20rpx;
	align-items: center;
	transition: transform 0.2s;
}

.video-card:active { transform: scale(0.99); }

.video-cover {
	width: 140rpx;
	height: 88rpx;
	border-radius: 12rpx;
	background: #f0f0f0;
	flex-shrink: 0;
}

.video-info {
	flex: 1;
	display: flex;
	align-items: center;
	gap: 12rpx;
	min-width: 0;
}

.info-content {
	flex: 1;
	min-width: 0;
}

.video-title {
	font-size: 24rpx;
	font-weight: 500;
	color: #333;
	line-height: 1.3;
	display: -webkit-box;
	-webkit-line-clamp: 1;
	-webkit-box-orient: vertical;
	overflow: hidden;
	margin-bottom: 6rpx;
}

.video-desc {
	font-size: 20rpx;
	color: #999;
	line-height: 1.3;
	display: -webkit-box;
	-webkit-line-clamp: 1;
	-webkit-box-orient: vertical;
	overflow: hidden;
	margin-bottom: 6rpx;
}

.video-time {
	font-size: 18rpx;
	color: #ccc;
}

.action-buttons {
	display: flex;
	flex-direction: column;
	gap: 10rpx;
	flex-shrink: 0;
}

.action-btn {
	width: 56rpx;
	height: 56rpx;
	border-radius: 14rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 0;
	margin: 0;
	border: none;
	line-height: 1;
}

.action-btn::after { border: none; }

.action-btn.play {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
}

.action-btn.delete {
	background: linear-gradient(135deg, #ef4444, #f87171);
}

.loading-more {
	display: flex;
	flex-direction: column;
	align-items: center;
	padding: 40rpx 0;
	gap: 12rpx;
}

.loading-spinner {
	width: 36rpx; height: 36rpx;
	border: 4rpx solid #e0e0e0; border-top-color: #2563eb;
	border-radius: 50%; animation: spin 0.8s linear infinite;
}

@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }

.loading-text { font-size: 24rpx; color: #999; }

.empty-state {
	display: flex;
	flex-direction: column;
	align-items: center;
	padding: 120rpx 0;
	gap: 16rpx;
}

.empty-text { font-size: 28rpx; color: #999; }
</style>
