<template>
	<view class="container">
		<view class="card">
			<view class="card-header">
				<text class="title">{{isEdit ? '修改服务器' : '添加服务器'}}</text>
			</view>
			<view class="form-group">
				<view class="form-item">
					<text class="label">服务器名称</text>
					<input 
						class="input" 
						v-model="servername" 
						placeholder="给服务器起个名字" 
						placeholder-class="placeholder"
					/>
				</view>
				
				<view class="form-item">
					<text class="label">服务器地址</text>
					<input 
						class="input" 
						v-model="server" 
						placeholder="http://xxx.com 或 http://ip" 
						placeholder-class="placeholder"
					/>
				</view>
				
				<view class="form-item">
					<text class="label">端口</text>
					<input 
						class="input" 
						v-model="port" 
						placeholder="请输入端口号" 
						placeholder-class="placeholder"
						type="number"
					/>
				</view>
				
				<view class="form-item">
					<text class="label">Token</text>
					<input 
						class="input" 
						v-model="token" 
						placeholder="请输入授权token" 
						placeholder-class="placeholder"
					/>
				</view>
			</view>
		</view>
		
		<button class="submit-btn" @click="saveServer()">
			<text class="btn-text">保存服务器</text>
		</button>
		
		<button class="import-btn" @click="importServer()">
			<uni-icons type="download" size="18" color="#2563eb"></uni-icons>
			<text class="import-btn-text">从分享导入</text>
		</button>
	</view>
</template>

<script>
	import xorCrypto from '@/utils/xor-crypto.js'
	export default {
		data() {
			return {
				serverlist:[],
				servername:"",
				server:"",
				port:"",
				token:"",
				streaming: false,
				isEdit: false,
				editIndex: -1
			}
		},
		onLoad(options) {
			if(options.edit === 'true') {
				this.isEdit = true;
				this.editIndex = parseInt(options.index);
				this.servername = decodeURIComponent(options.servername);
				this.server = decodeURIComponent(options.server);
				this.port = options.port;
				this.token = decodeURIComponent(options.token);
				this.streaming = options.streaming === 'true';
			}
		},
		onShow() {
			var serverlist = uni.getStorageSync('serverlist')
			if(serverlist!=""){
				this.serverlist = serverlist;
			}
		},
		methods: {
			saveServer:function(){
				var that = this;
				if(that.servername != "" && that.server != "" && that.port != "" && that.token != "" ){
					var data = {
						servername:that.servername,
						server:that.server,
						port:that.port,
						token:that.token,
						streaming:false,
						default: that.isEdit ? that.serverlist[that.editIndex].default : (that.serverlist.length === 0 ? "y" : "n")
					}
					
					if(that.isEdit) {
						that.serverlist[that.editIndex] = data;
					} else if(that.serverlist.length < 9) {
						that.serverlist.push(data);
					} else {
						uni.showToast({ title: '服务器数量已达上限', icon: 'none' });
						return;
					}
					
					uni.setStorageSync('serverlist',that.serverlist)
					uni.showModal({
						content: that.isEdit ? '修改成功' : '保存成功',
						showCancel:false,
						success: function (res) {
							if (res.confirm) {
								uni.navigateBack({});
							} 
						}
					});
				} else {
					uni.showToast({ title: '请填写完整信息', icon: 'none' });
				}
			},
			importServer() {
				uni.showModal({
					title: '输入分享信息',
					editable: true,
					placeholderText: '请输入分享的加密信息',
					success: (res) => {
						if (res.confirm && res.content) {
							uni.showModal({
								title: '输入解密密钥',
								editable: true,
								placeholderText: '请输入解密密钥',
								success: (keyRes) => {
									if (keyRes.confirm && keyRes.content) {
										try {
											const decrypted = xorCrypto.decrypt(res.content, keyRes.content);
											const serverData = JSON.parse(decrypted);
											
											if(this.serverlist.length < 99) {
												const defaultstatus = this.serverlist.length === 0 ? "y" : "n";
												const newServer = {
													servername: serverData.servername,
													server: serverData.server,
													port: serverData.port,
													token: serverData.token,
													streaming: false,
													default: defaultstatus
												};
												
												const temp = [...this.serverlist, newServer];
												uni.setStorageSync('serverlist', temp);
												
												uni.showModal({
													content: '导入成功',
													showCancel: false,
													success: function (res) {
														if (res.confirm) { uni.navigateBack({}); }
													}
												});
											} else {
												uni.showToast({ title: '服务器数量已达上限', icon: 'none' });
											}
										} catch (error) {
											uni.showToast({ title: '解密失败，请检查密钥', icon: 'none' });
										}
									}
								}
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
	padding-bottom: 60rpx;
	background: #f6f7f8;
}

.card {
	background: #fff;
	border-radius: 24rpx;
	padding: 32rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.card-header {
	margin-bottom: 28rpx;
}

.title {
	font-size: 34rpx;
	font-weight: 700;
	color: #1a1a1a;
}

.form-group {
	display: flex;
	flex-direction: column;
	gap: 24rpx;
}

.form-item {
	display: flex;
	flex-direction: column;
	gap: 10rpx;
}

.label {
	font-size: 26rpx;
	color: #666;
	font-weight: 500;
}

.input {
	height: 92rpx;
	background: #f8f9fb;
	border-radius: 16rpx;
	padding: 0 24rpx;
	font-size: 28rpx;
	color: #333;
	border: 2rpx solid transparent;
	transition: all 0.3s;
}

.input:focus {
	border-color: #2563eb;
	background: #fff;
	box-shadow: 0 0 0 4rpx rgba(37, 99, 235, 0.08);
}

.placeholder { color: #bbb; }

.submit-btn {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	height: 96rpx;
	border-radius: 48rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	margin: 36rpx 0 0;
	box-shadow: 0 8rpx 24rpx rgba(37, 99, 235, 0.25);
	transition: all 0.3s;
}

.submit-btn:active {
	transform: scale(0.98);
	box-shadow: 0 4rpx 12rpx rgba(37, 99, 235, 0.15);
}

.btn-text {
	color: #fff;
	font-size: 30rpx;
	font-weight: 600;
}

.import-btn {
	background: #dbeafe;
	height: 92rpx;
	border-radius: 48rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 10rpx;
	margin: 20rpx 0 0;
	border: none;
}

.import-btn::after { border: none; }

.import-btn-text {
	color: #2563eb;
	font-size: 28rpx;
	font-weight: 500;
}

.import-btn:active {
	background: #bfdbfe;
}
</style>
