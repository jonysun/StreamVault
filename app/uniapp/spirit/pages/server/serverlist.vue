<template>
	<view class="container">
		<view class="header">
			<text class="title">服务器列表</text>
			<text class="subtitle" v-if="serverlist.length">已配置 {{serverlist.length}} 个服务器</text>
		</view>
		
		<view class="server-list">
			<view class="server-card" v-for="(item,index) in serverlist" :key="index">
				<view class="card-top">
					<view class="server-avatar" :class="{ active: item.default === 'y' }">
						<text class="avatar-letter">{{(item.servername || 'S').charAt(0)}}</text>
					</view>
					<view class="server-info">
						<view class="name-row">
							<text class="server-name">{{item.servername}}</text>
							<view class="badge default-badge" v-if="item.default === 'y'">默认</view>
							<view class="badge stream-badge" v-if="item.streaming">流式</view>
						</view>
						<text class="server-addr">{{item.server}}:{{item.port}}</text>
					</view>
				</view>
				
				<view class="card-actions">
					<button 
						class="action-btn primary" 
						:class="{ active: item.default === 'y' }"
						@click="swichServer(index)"
					>
						{{item.default === 'y' ? '当前默认' : '设为默认'}}
					</button>
					<button class="action-btn icon-btn" @click="editServer(index)">
						<uni-icons type="compose" size="18" color="#666"></uni-icons>
					</button>
					<button class="action-btn icon-btn" @click="shareServer(index)">
						<uni-icons type="redo" size="18" color="#666"></uni-icons>
					</button>
					<button class="action-btn icon-btn danger" @click="deleteServer(index)">
						<uni-icons type="trash" size="18" color="#ef4444"></uni-icons>
					</button>
				</view>
			</view>
		</view>
		
		<view class="empty-state" v-if="serverlist.length === 0">
			<uni-icons type="server" size="48" color="#ccc"></uni-icons>
			<text class="empty-text">暂无服务器，请添加</text>
		</view>
		
		<view class="bottom-btn" @click="pageAddServer()">
			<uni-icons type="plusempty" size="20" color="#fff"></uni-icons>
			<text class="btn-text">添加新服务器</text>
		</view>
	</view>
</template>

<script>
	import xorCrypto from '@/utils/xor-crypto.js'
	export default {
		data() {
			return {
				serverlist:[]
			}
		},
		onShow() {
			var serverlist = uni.getStorageSync('serverlist')
			this.serverlist = serverlist || [];
		},
		methods: {
			swichServer:function(index){
				var temp = this.serverlist;
				for(var i = 0;i<temp.length;i++){
					temp[i]['default'] = i == index ? 'y' : 'n';
				}
				this.serverlist = temp;
				uni.setStorageSync('serverlist',this.serverlist)
			},
			deleteServer:function(index){
				uni.showModal({
					title: '确认删除',
					content: '确定删除该服务器配置吗？',
					success: (res) => {
						if (res.confirm) {
							this.serverlist.splice(index, 1);
							uni.setStorageSync('serverlist',this.serverlist);
						}
					}
				});
			},
			pageAddServer:function(){
				uni.navigateTo({ url:"/pages/server/addserver" })
			},
			editServer:function(index){
				const server = this.serverlist[index];
				uni.navigateTo({
					url: `/pages/server/addserver?edit=true&index=${index}&servername=${encodeURIComponent(server.servername)}&server=${encodeURIComponent(server.server)}&port=${server.port}&token=${encodeURIComponent(server.token)}&streaming=${server.streaming}`
				})
			},
			shareServer(index) {
				const server = this.serverlist[index];
				const data = {
					servername: server.servername,
					server: server.server,
					port: server.port,
					token: server.token,
					streaming: server.streaming
				};
				
				uni.showModal({
					title: '输入加密密钥',
					editable: true,
					placeholderText: '请输入至少3位的密钥',
					success: (res) => {
						if (res.confirm && res.content) {
							if (res.content.length < 3) {
								uni.showToast({ title: '密钥长度必须大于3位', icon: 'none' });
								return;
							}
							const encrypted = xorCrypto.encrypt(JSON.stringify(data), res.content);
							uni.setClipboardData({
								data: encrypted,
								success: () => { uni.showToast({ title: '已复制加密信息', icon: 'success' }); }
							});
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
	padding: 24rpx;
	padding-bottom: 160rpx;
	background: #f6f7f8;
}

.header {
	padding: 16rpx 8rpx 24rpx;
}

.title {
	font-size: 36rpx;
	font-weight: 700;
	color: #1a1a1a;
	display: block;
}

.subtitle {
	font-size: 24rpx;
	color: #999;
	margin-top: 6rpx;
	display: block;
}

.server-list {
	display: flex;
	flex-direction: column;
	gap: 16rpx;
}

.server-card {
	background: #fff;
	border-radius: 20rpx;
	padding: 24rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
	transition: transform 0.2s;
}

.server-card:active {
	transform: scale(0.99);
}

.card-top {
	display: flex;
	align-items: center;
	gap: 16rpx;
	margin-bottom: 20rpx;
}

.server-avatar {
	width: 72rpx;
	height: 72rpx;
	border-radius: 20rpx;
	background: #f0f0f0;
	display: flex;
	align-items: center;
	justify-content: center;
	flex-shrink: 0;
}

.server-avatar.active {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
}

.avatar-letter {
	font-size: 32rpx;
	font-weight: 700;
	color: #999;
}

.server-avatar.active .avatar-letter {
	color: #fff;
}

.server-info {
	flex: 1;
	min-width: 0;
}

.name-row {
	display: flex;
	align-items: center;
	gap: 10rpx;
	margin-bottom: 6rpx;
}

.server-name {
	font-size: 28rpx;
	font-weight: 600;
	color: #333;
}

.badge {
	padding: 2rpx 12rpx;
	border-radius: 8rpx;
	font-size: 20rpx;
	font-weight: 500;
}

.default-badge {
	background: #dbeafe;
	color: #2563eb;
}

.stream-badge {
	background: #dcfce7;
	color: #16a34a;
}

.server-addr {
	font-size: 24rpx;
	color: #999;
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
}

.card-actions {
	display: flex;
	align-items: center;
	gap: 12rpx;
	border-top: 1rpx solid #f5f5f5;
	padding-top: 20rpx;
}

.action-btn {
	flex: 1;
	height: 68rpx;
	border-radius: 16rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	font-size: 24rpx;
	background: #f8f9fb;
	color: #666;
	border: none;
	padding: 0;
	margin: 0;
	line-height: 1;
}

.action-btn::after { border: none; }

.action-btn.primary {
	background: #dbeafe;
	color: #2563eb;
	font-weight: 500;
}

.action-btn.primary.active {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	color: #fff;
}

.action-btn.icon-btn {
	flex: 0 0 68rpx;
}

.action-btn.icon-btn.danger {
	background: #fef2f2;
}

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

.bottom-btn {
	position: fixed;
	bottom: 0;
	left: 0;
	right: 0;
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	height: 100rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 12rpx;
	box-shadow: 0 -4rpx 20rpx rgba(37, 99, 235, 0.2);
}

.bottom-btn .btn-text {
	color: #fff;
	font-size: 30rpx;
	font-weight: 600;
}
</style>
