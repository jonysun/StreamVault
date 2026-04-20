<template>
	<view class="container">
		<!-- 顶部品牌区 -->
		<view class="hero-section">
			<view class="brand">
				<text class="brand-name">StreamVault</text>
				<text class="brand-tagline">智能视频管理平台</text>
			</view>
		</view>

		<!-- 推送卡片 -->
		<view class="push-card">
			<view class="card-label">
				<uni-icons type="paperplane" size="18" color="#2563eb"></uni-icons>
				<text class="label-text">推送链接</text>
			</view>
			<textarea 
				class="input-area" 
				placeholder="请输入或粘贴视频分享链接" 
				placeholder-class="placeholder"
				v-model="originaladdress"
				:auto-height="false"
			></textarea>
			<button class="submit-btn" @click="pushMessage()" :disabled="!originaladdress">
				<text class="btn-text">提交链接</text>
			</button>
		</view>

		<!-- 服务器选择 -->
		<view class="server-card" @click="serverList()" v-if="servername">
			<view class="server-left">
				<view class="server-dot" :class="{ active: serveraddr }"></view>
				<view class="server-info">
					<text class="server-name">{{servername || '未选择'}}</text>
					<text class="server-addr" v-if="serveraddr">{{serveraddr}}:{{serverport}}</text>
				</view>
			</view>
			<uni-icons type="right" size="16" color="#ccc"></uni-icons>
		</view>

		<!-- 未配置服务器 -->
		<view class="server-card empty" @click="serverList()" v-else>
			<view class="server-left">
				<uni-icons type="settings" size="22" color="#2563eb"></uni-icons>
				<view class="server-info">
					<text class="server-name">配置服务器</text>
					<text class="server-addr">点击添加服务器地址</text>
				</view>
			</view>
			<uni-icons type="right" size="16" color="#ccc"></uni-icons>
		</view>

		<!-- 快捷入口 -->
		<view class="quick-section">
			<view class="quick-card" @tap="goToVideoList">
				<view class="quick-icon gradient-blue">
					<uni-icons type="videocam" size="22" color="#fff"></uni-icons>
				</view>
				<text class="quick-title">视频列表</text>
			</view>
			<view class="quick-card" @tap="goToFallsVideo">
				<view class="quick-icon gradient-red">
					<uni-icons type="fire" size="22" color="#fff"></uni-icons>
				</view>
				<text class="quick-title">沉浸浏览</text>
			</view>
			<view class="quick-card" @tap="serverList()">
				<view class="quick-icon gradient-purple">
					<uni-icons type="gear-filled" size="22" color="#fff"></uni-icons>
				</view>
				<text class="quick-title">服务器</text>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				originaladdress:"",
				servername:"",
				serveraddr:"",
				serverport:"",
				servertoken:""
			}
		},
		onLoad() {},
		onShow() {
			this.loadServer();
			uni.getClipboardData({
				success: (res) => {
					if (res.data && (res.data.includes('http') || res.data.includes('douyin') || res.data.includes('instagram'))) {
						this.originaladdress = res.data;
					}
				}
			});
		},
		methods: {
			loadServer:function(){
				var that = this;
				var serverlist = uni.getStorageSync('serverlist');
				if (!serverlist || !serverlist.length) {
					that.servername = '';
					that.serveraddr = '';
					that.serverport = '';
					that.servertoken = '';
					return;
				}
				for(var i =0;i<serverlist.length;i++){
					if(serverlist[i].default == 'y'){
						that.servername = serverlist[i].servername
						that.serveraddr = serverlist[i].server
						that.serverport = serverlist[i].port
						that.servertoken = serverlist[i].token
					}
				}
				if(that.servername=="" && serverlist.length !=0){
					that.servername = serverlist[0].servername
					that.serveraddr = serverlist[0].server
					that.serverport = serverlist[0].port
					that.servertoken = serverlist[0].token
				}
				uni.setStorageSync('serveraddr',that.serveraddr)
				uni.setStorageSync('serverport',that.serverport)
				uni.setStorageSync('servertoken',that.servertoken)
			},
			serverList:function(){
				uni.navigateTo({
					url:"/pages/server/serverlist"
				})
			},
			goToVideoList() {
				uni.switchTab({
					url: '/pages/video/videolist'
				});
			},
			goToFallsVideo() {
				uni.navigateTo({
					url: '/pages/video/fallsVideo'
				});
			},
			pushMessage:function(){
				if(this.originaladdress != "" && this.serveraddr != "" && this.serverport != "" && this.servertoken != ""){
					var api =this.serveraddr+":"+this.serverport+"/api/processingVideos";
					var option ={
						token:this.servertoken,
						video:this.originaladdress
					}
					uni.showLoading({
						title:"正在提交..."
					})
					uni.request({
						url: api,
						method: "POST",
						header: {
							'content-type': 'application/x-www-form-urlencoded'
						},
						data:option,
						success(res) {
							uni.hideLoading();
							if(res.data.resCode =="000001" && res.data.message != null){
								uni.showToast({
									title: res.data.message,
									duration: 2000,
									icon: 'success'
								});
							} else {
								uni.showToast({
									title: res.data.resMsg || '提交失败',
									icon: 'none'
								});
							}
						},
						fail() {
							uni.hideLoading();
							uni.showToast({
								title: '网络请求失败',
								icon: 'none'
							});
						}
					})
				} else if (!this.serveraddr) {
					uni.showToast({
						title: '请先配置服务器',
						icon: 'none'
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

.hero-section {
	background: linear-gradient(135deg, #2563eb, #3b82f6, #60a5fa);
	padding: 48rpx 32rpx 64rpx;
	border-radius: 0 0 40rpx 40rpx;
}

.brand {
	display: flex;
	flex-direction: column;
}

.brand-name {
	font-size: 44rpx;
	font-weight: 800;
	color: #fff;
	letter-spacing: 2rpx;
	margin-bottom: 8rpx;
}

.brand-tagline {
	font-size: 24rpx;
	color: rgba(255, 255, 255, 0.75);
}

.push-card {
	background: #fff;
	border-radius: 24rpx;
	padding: 28rpx;
	margin: -36rpx 24rpx 0;
	box-shadow: 0 8rpx 32rpx rgba(0, 0, 0, 0.08);
	position: relative;
	z-index: 10;
}

.card-label {
	display: flex;
	align-items: center;
	gap: 8rpx;
	margin-bottom: 20rpx;
}

.label-text {
	font-size: 28rpx;
	font-weight: 600;
	color: #333;
}

.input-area {
	width: 100%;
	height: 160rpx;
	background: #f8f9fb;
	border-radius: 16rpx;
	padding: 20rpx;
	font-size: 28rpx;
	color: #333;
	line-height: 1.5;
	box-sizing: border-box;
}

.placeholder {
	color: #bbb;
	font-size: 28rpx;
}

.submit-btn {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	height: 88rpx;
	border-radius: 44rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	margin-top: 24rpx;
	box-shadow: 0 6rpx 20rpx rgba(37, 99, 235, 0.3);
	transition: all 0.3s;
}

.submit-btn:active {
	transform: scale(0.98);
	box-shadow: 0 3rpx 10rpx rgba(37, 99, 235, 0.2);
}

.submit-btn[disabled] {
	background: #d1d5db;
	box-shadow: none;
}

.btn-text {
	color: #fff;
	font-size: 30rpx;
	font-weight: 600;
}

.server-card {
	background: #fff;
	border-radius: 20rpx;
	padding: 24rpx 28rpx;
	margin: 24rpx 24rpx 0;
	display: flex;
	align-items: center;
	justify-content: space-between;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
	transition: transform 0.2s;
}

.server-card:active {
	transform: scale(0.99);
}

.server-left {
	display: flex;
	align-items: center;
	gap: 16rpx;
	flex: 1;
	min-width: 0;
}

.server-dot {
	width: 16rpx;
	height: 16rpx;
	border-radius: 50%;
	background: #d1d5db;
	flex-shrink: 0;
}

.server-dot.active {
	background: #22c55e;
	box-shadow: 0 0 8rpx rgba(34, 197, 94, 0.4);
}

.server-info {
	display: flex;
	flex-direction: column;
	gap: 4rpx;
	overflow: hidden;
}

.server-name {
	font-size: 28rpx;
	font-weight: 600;
	color: #333;
}

.server-addr {
	font-size: 22rpx;
	color: #999;
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
}

.server-card.empty .server-name {
	color: #2563eb;
}

.server-card.empty .server-addr {
	color: #bbb;
}

.quick-section {
	display: flex;
	justify-content: space-between;
	padding: 32rpx 24rpx;
	gap: 20rpx;
}

.quick-card {
	flex: 1;
	background: #fff;
	border-radius: 20rpx;
	padding: 28rpx 0;
	display: flex;
	flex-direction: column;
	align-items: center;
	gap: 16rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
	transition: transform 0.2s;
}

.quick-card:active {
	transform: scale(0.96);
}

.quick-icon {
	width: 72rpx;
	height: 72rpx;
	border-radius: 20rpx;
	display: flex;
	align-items: center;
	justify-content: center;
}

.gradient-blue {
	background: linear-gradient(135deg, #3b82f6, #60a5fa);
}

.gradient-red {
	background: linear-gradient(135deg, #ef4444, #f87171);
}

.gradient-purple {
	background: linear-gradient(135deg, #3b82f6, #60a5fa);
}

.quick-title {
	font-size: 24rpx;
	color: #333;
	font-weight: 500;
}
</style>
