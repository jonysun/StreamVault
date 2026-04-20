<template>
	<view class="container">
		<!-- 未登录状态 -->
		<view v-if="!isLoggedIn" class="login-guide">
			<view class="guide-icon">
				<text class="guide-emoji">🔐</text>
			</view>
			<text class="guide-title">管理员中心</text>
			<text class="guide-desc">登录后可管理视频、图文和收藏内容</text>
			<button class="guide-btn" @tap="goLogin">立即登录</button>
		</view>

		<!-- 已登录状态 -->
		<view v-else>
			<!-- 顶部渐变头部 -->
			<view class="header-bg">
				<view class="user-card">
					<view class="avatar-wrap">
						<text class="avatar-emoji">👤</text>
					</view>
					<view class="user-info">
						<text class="username">{{username}}</text>
						<text class="role-badge">管理员</text>
					</view>
				</view>
			</view>

			<!-- 数据概览 -->
			<view class="stats-row">
				<view class="stat-item">
					<text class="stat-num">{{videoTotal}}</text>
					<text class="stat-label">视频</text>
					<text class="stat-today" v-if="videoTodayAdded > 0">+{{videoTodayAdded}}</text>
				</view>
				<view class="stat-divider"></view>
				<view class="stat-item">
					<text class="stat-num">{{graphicTotal}}</text>
					<text class="stat-label">图文</text>
					<text class="stat-today" v-if="graphicTodayAdded > 0">+{{graphicTodayAdded}}</text>
				</view>
				<view class="stat-divider"></view>
				<view class="stat-item">
					<text class="stat-num">{{collectDataTotal}}</text>
					<text class="stat-label">收藏</text>
				</view>
			</view>

			<!-- 平台分布 -->
			<view class="platform-section" v-if="hasPlatformStats">
				<view class="section-title">
					<text class="section-text">平台分布</text>
				</view>
				<view class="platform-row">
					<view class="platform-group" v-if="videoPlatformList.length > 0">
						<text class="platform-group-label">视频</text>
						<view class="platform-tags">
							<view class="platform-tag tag-blue" v-for="(count, name) in videoPlatformStats" :key="name">
								{{name}} <text class="tag-count">{{count}}</text>
							</view>
						</view>
					</view>
					<view class="platform-group" v-if="graphicPlatformList.length > 0">
						<text class="platform-group-label">图文</text>
						<view class="platform-tags">
							<view class="platform-tag tag-green" v-for="(count, name) in graphicPlatformStats" :key="name">
								{{name}} <text class="tag-count">{{count}}</text>
							</view>
						</view>
					</view>
				</view>
			</view>

			<!-- 功能菜单 -->
			<view class="section-title">
				<text class="section-text">内容管理</text>
			</view>
			<view class="menu-grid">
				<view class="menu-card" @tap="navigateTo('/pages/admin/videoData')">
					<view class="menu-card-inner">
						<view class="menu-icon-wrap gradient-blue">
							<uni-icons type="videocam" size="28" color="#fff"></uni-icons>
						</view>
						<text class="menu-title">视频列表</text>
						<text class="menu-desc">管理视频内容</text>
					</view>
				</view>

				<view class="menu-card" @tap="navigateTo('/pages/admin/graphicData')">
					<view class="menu-card-inner">
						<view class="menu-icon-wrap gradient-purple">
							<uni-icons type="image" size="28" color="#fff"></uni-icons>
						</view>
						<text class="menu-title">图文内容</text>
						<text class="menu-desc">管理图文素材</text>
					</view>
				</view>

				<view class="menu-card" @tap="navigateTo('/pages/admin/favData')">
					<view class="menu-card-inner">
						<view class="menu-icon-wrap gradient-orange">
							<uni-icons type="star" size="28" color="#fff"></uni-icons>
						</view>
						<text class="menu-title">收藏管理</text>
						<text class="menu-desc">管理收藏任务</text>
					</view>
				</view>

				<view class="menu-card" @tap="navigateTo('/pages/admin/directData')">
					<view class="menu-card-inner">
						<view class="menu-icon-wrap gradient-green">
							<uni-icons type="compose" size="28" color="#fff"></uni-icons>
						</view>
						<text class="menu-title">直连解析</text>
						<text class="menu-desc">解析/下载链接</text>
					</view>
				</view>
			</view>

			<!-- 快捷操作 -->
			<view class="section-title">
				<text class="section-text">快捷操作</text>
			</view>
			<view class="quick-list">
				<view class="quick-item" @tap="navigateTo('/pages/server/serverlist')">
					<uni-icons type="settings" size="20" color="#2563eb"></uni-icons>
					<text class="quick-text">服务器管理</text>
					<uni-icons type="right" size="14" color="#ccc"></uni-icons>
				</view>
				<view class="quick-item" @tap="handleLogout">
					<uni-icons type="undo" size="20" color="#ef4444"></uni-icons>
					<text class="quick-text quick-text-red">退出登录</text>
					<uni-icons type="right" size="14" color="#ccc"></uni-icons>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				username: '管理员',
				isLoggedIn: false,
				videoTotal: '--',
				graphicTotal: '--',
				collectDataTotal: '--',
				videoTodayAdded: 0,
				graphicTodayAdded: 0,
				videoPlatformStats: {},
				graphicPlatformStats: {}
			}
		},
		onShow() {
			this.checkLoginStatus();
		},
		onLoad() {},
		computed: {
			hasPlatformStats() {
				return Object.keys(this.videoPlatformStats).length > 0 || Object.keys(this.graphicPlatformStats).length > 0;
			},
			videoPlatformList() {
				return Object.keys(this.videoPlatformStats);
			},
			graphicPlatformList() {
				return Object.keys(this.graphicPlatformStats);
			}
		},
		methods: {
			checkLoginStatus() {
				const adminCookie = uni.getStorageSync('adminCookie');
				const expireTime = uni.getStorageSync('adminCookieExpire');
				const currentTime = new Date().getTime();
				
				if (adminCookie && expireTime && currentTime <= expireTime) {
					this.isLoggedIn = true;
					this.fetchStatistics();
				} else {
					this.isLoggedIn = false;
					uni.removeStorageSync('adminCookie');
					uni.removeStorageSync('adminCookieExpire');
				}
			},
			fetchStatistics() {
				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');
				
				if (!serveraddr) return;
				
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/getDataStatistics`,
					method: 'GET',
					header: {
						'Cookie': adminCookie
					},
					success: (res) => {
						if (res.data && res.data.resCode === '000001' && res.data.record) {
							const record = res.data.record;
							this.videoTotal = Object.values(record.videoPlatformStats || {}).reduce((a, b) => a + b, 0);
							this.graphicTotal = Object.values(record.graphicPlatformStats || {}).reduce((a, b) => a + b, 0);
							this.collectDataTotal = record.collectDataTotal || 0;
							this.videoTodayAdded = record.videoTodayAdded || 0;
							this.graphicTodayAdded = record.graphicTodayAdded || 0;
							this.videoPlatformStats = record.videoPlatformStats || {};
							this.graphicPlatformStats = record.graphicPlatformStats || {};
						}
					},
					fail: () => {}
				});
			},
			goLogin() {
				uni.navigateTo({
					url: '/pages/admin/login'
				});
			},
			navigateTo(url) {
				uni.navigateTo({
					url: url
				});
			},
			showNotOpen() {
				uni.showToast({
					title: '暂未开放',
					icon: 'none'
				});
			},
			handleLogout() {
				uni.showModal({
					title: '提示',
					content: '确定要退出登录吗？',
					success: (res) => {
						if (res.confirm) {
							uni.removeStorageSync('adminCookie');
							uni.removeStorageSync('adminCookieExpire');
							this.isLoggedIn = false;
							this.videoTotal = '--';
							this.graphicTotal = '--';
							this.collectDataTotal = '--';
							this.videoTodayAdded = 0;
							this.graphicTodayAdded = 0;
							this.videoPlatformStats = {};
							this.graphicPlatformStats = {};
							uni.showToast({ title: '已退出登录', icon: 'success' });
						}
					}
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

.header-bg {
	background: linear-gradient(135deg, #2563eb, #3b82f6, #60a5fa);
	padding: 40rpx 32rpx 60rpx;
	border-radius: 0 0 40rpx 40rpx;
}

.user-card {
	display: flex;
	align-items: center;
}

.avatar-wrap {
	width: 100rpx;
	height: 100rpx;
	border-radius: 50rpx;
	background: rgba(255, 255, 255, 0.2);
	display: flex;
	align-items: center;
	justify-content: center;
	margin-right: 24rpx;
	border: 3rpx solid rgba(255, 255, 255, 0.4);
}

.avatar-emoji {
	font-size: 56rpx;
}

.user-info {
	flex: 1;
}

.username {
	font-size: 36rpx;
	font-weight: 700;
	color: #fff;
	display: block;
	margin-bottom: 8rpx;
}

.role-badge {
	font-size: 22rpx;
	color: rgba(255, 255, 255, 0.85);
	background: rgba(255, 255, 255, 0.2);
	padding: 4rpx 16rpx;
	border-radius: 16rpx;
	display: inline-block;
}

.stats-row {
	display: flex;
	align-items: center;
	background: #fff;
	border-radius: 20rpx;
	padding: 28rpx 0;
	margin: -36rpx 32rpx 0;
	box-shadow: 0 4rpx 20rpx rgba(0, 0, 0, 0.08);
	position: relative;
	z-index: 10;
}

.stat-item {
	flex: 1;
	display: flex;
	flex-direction: column;
	align-items: center;
}

.stat-num {
	font-size: 36rpx;
	font-weight: 700;
	color: #333;
	margin-bottom: 4rpx;
}

.stat-label {
	font-size: 22rpx;
	color: #999;
}

.stat-today {
	font-size: 20rpx;
	color: #fff;
	background: #3b82f6;
	padding: 2rpx 10rpx;
	border-radius: 12rpx;
	margin-top: 4rpx;
}

.platform-section {
	margin-top: 16rpx;
}

.platform-row {
	display: flex;
	padding: 0 32rpx;
	gap: 24rpx;
}

.platform-group {
	flex: 1;
	background: #fff;
	border-radius: 20rpx;
	padding: 24rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.platform-group-label {
	font-size: 24rpx;
	font-weight: 600;
	color: #666;
	display: block;
	margin-bottom: 16rpx;
}

.platform-tags {
	display: flex;
	flex-wrap: wrap;
	gap: 12rpx;
}

.platform-tag {
	font-size: 22rpx;
	padding: 6rpx 16rpx;
	border-radius: 12rpx;
	display: inline-flex;
	align-items: center;
	gap: 6rpx;
}

.tag-blue {
	background: #eff6ff;
	color: #2563eb;
}

.tag-green {
	background: #f0fdf4;
	color: #16a34a;
}

.tag-count {
	font-weight: 700;
}

.stat-divider {
	width: 1rpx;
	height: 48rpx;
	background: #eee;
}

.section-title {
	padding: 36rpx 32rpx 16rpx;
}

.section-text {
	font-size: 28rpx;
	font-weight: 600;
	color: #333;
}

.menu-grid {
	display: flex;
	flex-wrap: wrap;
	padding: 0 32rpx;
}

.menu-card {
	width: 50%;
	background: transparent;
	padding: 10rpx;
	box-sizing: border-box;
}

.menu-card-inner {
	background: #fff;
	border-radius: 20rpx;
	padding: 28rpx;
	display: flex;
	flex-direction: column;
	align-items: center;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
	transition: transform 0.2s;
}

.menu-card-inner:active {
	transform: scale(0.97);
}

.menu-icon-wrap {
	width: 88rpx;
	height: 88rpx;
	border-radius: 24rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	margin-bottom: 16rpx;
}

.gradient-blue {
	background: linear-gradient(135deg, #3b82f6, #60a5fa);
}

.gradient-purple {
	background: linear-gradient(135deg, #2563eb, #60a5fa);
}

.gradient-orange {
	background: linear-gradient(135deg, #f59e0b, #fbbf24);
}

.gradient-gray {
	background: linear-gradient(135deg, #9ca3af, #d1d5db);
}

.gradient-green {
	background: linear-gradient(135deg, #16a34a, #4ade80);
}

.menu-title {
	font-size: 28rpx;
	font-weight: 600;
	color: #333;
	margin-bottom: 6rpx;
}

.menu-desc {
	font-size: 22rpx;
	color: #999;
}

.quick-list {
	margin: 0 32rpx;
	background: #fff;
	border-radius: 20rpx;
	overflow: hidden;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.quick-item {
	display: flex;
	align-items: center;
	padding: 28rpx 24rpx;
	border-bottom: 1rpx solid #f5f5f5;
}

.quick-item:last-child {
	border-bottom: none;
}

.quick-text {
	flex: 1;
	font-size: 28rpx;
	color: #333;
	margin-left: 16rpx;
}

.quick-text-red {
	color: #ef4444;
}

/* 登录引导 */
.login-guide {
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	min-height: 80vh;
	padding: 0 48rpx;
}

.guide-icon {
	width: 160rpx;
	height: 160rpx;
	border-radius: 48rpx;
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	display: flex;
	align-items: center;
	justify-content: center;
	margin-bottom: 32rpx;
	box-shadow: 0 12rpx 32rpx rgba(37, 99, 235, 0.2);
}

.guide-emoji {
	font-size: 80rpx;
}

.guide-title {
	font-size: 36rpx;
	font-weight: 700;
	color: #1a1a1a;
	margin-bottom: 12rpx;
}

.guide-desc {
	font-size: 26rpx;
	color: #999;
	text-align: center;
	margin-bottom: 48rpx;
	line-height: 1.6;
}

.guide-btn {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	color: #fff;
	font-size: 30rpx;
	font-weight: 600;
	width: 400rpx;
	height: 88rpx;
	border-radius: 44rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	box-shadow: 0 8rpx 24rpx rgba(37, 99, 235, 0.25);
	border: none;
}

.guide-btn::after { border: none; }

.guide-btn:active {
	transform: scale(0.98);
}
</style>
