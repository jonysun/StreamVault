<template>
	<view class="container">
		<view class="header">
			<text class="title">收藏任务</text>
		</view>
		<view class="fav-list">
			<view class="fav-card" v-for="item in list" :key="item.id">
				<view class="fav-info">
					<view class="fav-row">
						<view class="fav-platform" :class="item.platform">
							{{item.platform || '未知'}}
						</view>
						<text class="fav-taskname">{{item.taskname}}</text>
					</view>
					<view class="fav-meta">
						<view class="meta-row">
							<text class="meta-label">状态</text>
							<text class="meta-value" :class="item.taskstatus === '已完成' ? 'success' : 'pending'">{{item.taskstatus}}</text>
						</view>
						<view class="meta-row">
							<text class="meta-label">创建</text>
							<text class="meta-value">{{formatDate(item.createtime)}}</text>
						</view>
						<view class="meta-row">
							<text class="meta-label">完成</text>
							<text class="meta-value">{{item.endtime || '--'}}</text>
						</view>
						<view class="meta-row">
							<text class="meta-label">数量</text>
							<text class="meta-value">{{item.count}} / 已执行 {{item.carriedout}}</text>
						</view>
					</view>
				</view>
				<view class="fav-actions">
					<button class="action-btn exec" @tap="execTaskById(item)">
						<uni-icons type="play-filled" size="14" color="#fff"></uni-icons>
					</button>
					<button class="action-btn delete" @tap="deleteFav(item)">
						<uni-icons type="trash" size="14" color="#fff"></uni-icons>
					</button>
				</view>
			</view>
			<view class="empty-state" v-if="list.length === 0">
				<uni-icons type="star" size="48" color="#ccc"></uni-icons>
				<text class="empty-text">暂无收藏任务</text>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				list: [],
				pageNo: 1,
				loading: false,
				finished: false
			}
		},
		onLoad() { this.getList(1); },
		onPullDownRefresh() {
			this.pageNo = 1;
			this.finished = false;
			this.list = [];
			this.getList(1);
		},
		onReachBottom() {
			if (!this.finished && !this.loading) { this.getList(this.pageNo); }
		},
		methods: {
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
					return `${date.getMonth() + 1}/${date.getDate()}`;
				} catch (e) { return timeStr; }
			},
			getList(page) {
				if (this.loading) return;
				this.loading = true;
				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/findCollectDataList`,
					method: 'POST',
					header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': adminCookie },
					data: { pageNo: page },
					success: (res) => {
						if (res.data.resCode === '000001') {
							const content = res.data.record.content || [];
							if (page === 1) { this.list = content; }
							else { this.list = this.list.concat(content); }
							this.pageNo++;
							this.finished = res.data.record.last;
						} else {
							uni.showToast({title: res.data.message || '获取失败', icon: 'none'});
							uni.removeStorageSync('adminCookie');
							uni.removeStorageSync('adminCookieExpire');
							setTimeout(() => { uni.redirectTo({ url: '/pages/admin/login' }); }, 1500);
						}
					},
					fail: () => {
						uni.showToast({title: '网络错误', icon: 'none'});
						uni.removeStorageSync('adminCookie');
						uni.removeStorageSync('adminCookieExpire');
						setTimeout(() => { uni.redirectTo({ url: '/pages/admin/login' }); }, 1500);
					},
					complete: () => {
						this.loading = false;
						uni.stopPullDownRefresh();
					}
				});
			},
			execTaskById(item) {
				uni.showModal({
					title: '执行', content: '确定要执行单次该任务吗？',
					success: (res) => { if (res.confirm) this.confirmExecTaskById(item); }
				});
			},
			confirmExecTaskById(item) {
				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');
				uni.showLoading({title: '调度中...'});
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/execCollectData?id=${item.id}`,
					method: 'GET',
					header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': adminCookie },
					success: (res) => {
						if (res.data.resCode === '000001') {
							uni.showToast({title: '执行成功', icon: 'success'});
							this.pageNo = 1;
							this.list = [];
							this.getList(1);
						} else {
							uni.showToast({title: res.data.message || '执行失败', icon: 'none'});
						}
					},
					fail: () => { uni.showToast({title: '网络错误', icon: 'none'}); },
					complete: () => { uni.hideLoading(); }
				});
			},
			deleteFav(item) {
				uni.showModal({
					title: '确认删除', content: '确定要删除该收藏任务吗？',
					success: (res) => { if (res.confirm) this.confirmDelete(item); }
				});
			},
			confirmDelete(item) {
				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');
				uni.showLoading({title: '删除中...'});
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/deleteCollectData?id=${item.id}`,
					method: 'GET',
					header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': adminCookie },
					success: (res) => {
						if (res.data.resCode === '000001') {
							uni.showToast({title: '删除成功', icon: 'success'});
							const idx = this.list.findIndex(v => v.id === item.id);
							if (idx > -1) this.list.splice(idx, 1);
						} else {
							uni.showToast({title: res.data.message || '删除失败', icon: 'none'});
						}
					},
					fail: () => { uni.showToast({title: '网络错误', icon: 'none'}); },
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
	padding: 24rpx;
}

.header {
	padding: 12rpx 8rpx 20rpx;
}

.title {
	font-size: 36rpx;
	font-weight: 700;
	color: #1a1a1a;
}

.fav-list {
	display: flex;
	flex-direction: column;
	gap: 16rpx;
}

.fav-card {
	background: #fff;
	border-radius: 20rpx;
	padding: 24rpx;
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 16rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.fav-info {
	flex: 1;
	min-width: 0;
}

.fav-row {
	display: flex;
	align-items: center;
	gap: 12rpx;
	margin-bottom: 16rpx;
}

.fav-platform {
	padding: 4rpx 14rpx;
	border-radius: 8rpx;
	font-size: 20rpx;
	color: #fff;
	font-weight: 500;
	flex-shrink: 0;
	background: #2563eb;
}

.fav-taskname {
	font-size: 26rpx;
	font-weight: 500;
	color: #333;
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
}

.fav-meta {
	display: flex;
	flex-direction: column;
	gap: 8rpx;
}

.meta-row {
	display: flex;
	align-items: center;
	gap: 8rpx;
}

.meta-label {
	font-size: 22rpx;
	color: #aaa;
	width: 56rpx;
	flex-shrink: 0;
}

.meta-value {
	font-size: 22rpx;
	color: #666;
}

.meta-value.success { color: #22c55e; }
.meta-value.pending { color: #f59e0b; }

.fav-actions {
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

.action-btn.exec { background: linear-gradient(135deg, #2563eb, #3b82f6); }
.action-btn.delete { background: linear-gradient(135deg, #ef4444, #f87171); }

.empty-state {
	display: flex;
	flex-direction: column;
	align-items: center;
	padding: 120rpx 0;
	gap: 16rpx;
}

.empty-text {
	font-size: 28rpx;
	color: #999;
}
</style>
