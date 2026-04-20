<template>
	<view class="container">
		<view class="login-box">
			<!-- 顶部装饰 -->
			<view class="deco-circle c1"></view>
			<view class="deco-circle c2"></view>

			<view class="header">
				<view class="logo-wrap">
					<text class="logo-emoji">🔐</text>
				</view>
				<text class="title">管理员登录</text>
				<text class="subtitle">StreamVault 管理后台</text>
			</view>
			
			<view class="form">
				<view class="input-group">
					<view class="input-icon">
						<uni-icons type="person" size="20" color="#3b82f6"></uni-icons>
					</view>
					<input 
						class="input" 
						type="text" 
						v-model="username" 
						placeholder="请输入账号"
						placeholder-class="placeholder"
					/>
				</view>
				
				<view class="input-group">
					<view class="input-icon">
						<uni-icons type="locked" size="20" color="#3b82f6"></uni-icons>
					</view>
					<input 
						class="input" 
						type="password" 
						v-model="password" 
						placeholder="请输入密码"
						placeholder-class="placeholder"
					/>
				</view>

				<view class="remember-group">
					<checkbox-group @change="onRememberChange">
						<label class="remember-label">
							<checkbox value="remember" :checked="rememberMe" color="#2563eb" />
							<text class="remember-text">记住密码</text>
						</label>
					</checkbox-group>
				</view>
				
				<button class="login-btn" @tap="handleLogin" :disabled="!username || !password">
					登 录
				</button>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				username: '',
				password: '',
				rememberMe: false
			}
		},
		onLoad() {
			try {
				const remembered = uni.getStorageSync('adminRemember');
				if (remembered) {
					const savedUsername = uni.getStorageSync('adminUsername');
					const savedPassword = uni.getStorageSync('adminPassword');
					if (savedUsername) this.username = savedUsername;
					if (savedPassword) this.password = savedPassword;
					this.rememberMe = true;
				}
			} catch (e) {}
		},
		methods: {
			onRememberChange(e) {
				const values = e?.detail?.value || [];
				this.rememberMe = values.includes('remember');
			},
			handleLogin() {
				if (!this.username || !this.password) {
					uni.showToast({ title: '请输入账号和密码', icon: 'none' });
					return;
				}
				
				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				
				if (!serveraddr || !serverport) {
					uni.showToast({ title: '请先设置服务器地址', icon: 'none' });
					setTimeout(() => {
						uni.switchTab({ url: '../index/index' });
					}, 1500);
					return;
				}
				
				uni.showLoading({ title: '登录中...' });
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/login`,
					method: 'POST',
					header: { 'content-type': 'application/x-www-form-urlencoded' },
					data: { username: this.username, password: this.password },
					success: (res) => {
						uni.hideLoading();
						if (res.data.resCode === '000001') {
							const cookies = res.header['Set-Cookie'] || res.header['set-cookie'];
							if (cookies) {
								const expireTime = new Date().getTime() + 24 * 60 * 60 * 1000;
								uni.setStorageSync('adminCookie', cookies);
								uni.setStorageSync('adminCookieExpire', expireTime);
							}
							try {
								if (this.rememberMe) {
									uni.setStorageSync('adminRemember', true);
									uni.setStorageSync('adminUsername', this.username);
									uni.setStorageSync('adminPassword', this.password);
								} else {
									uni.removeStorageSync('adminRemember');
									uni.removeStorageSync('adminUsername');
									uni.removeStorageSync('adminPassword');
								}
							} catch (e) {}
							
							uni.showToast({ title: '登录成功', icon: 'success' });
							setTimeout(() => {
								uni.switchTab({ url: '/pages/admin/admin' });
							}, 1500);
						} else {
							uni.showToast({ title: res.data.message || '登录失败', icon: 'none' });
						}
					},
					fail: () => {
						uni.hideLoading();
						uni.showToast({ title: '网络错误', icon: 'none' });
					}
				});
			}
		}
	}
</script>

<style>
.container {
	min-height: 100vh;
	background: linear-gradient(160deg, #2563eb, #3b82f6, #60a5fa);
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 48rpx;
}

.login-box {
	width: 100%;
	background: #fff;
	border-radius: 32rpx;
	padding: 56rpx 40rpx 48rpx;
	box-shadow: 0 16rpx 48rpx rgba(0, 0, 0, 0.15);
	position: relative;
	overflow: hidden;
}

.deco-circle {
	position: absolute;
	border-radius: 50%;
	opacity: 0.08;
}

.c1 {
	width: 240rpx; height: 240rpx;
	background: #2563eb;
	top: -80rpx; right: -80rpx;
}

.c2 {
	width: 160rpx; height: 160rpx;
	background: #3b82f6;
	bottom: -40rpx; left: -40rpx;
}

.header {
	display: flex;
	flex-direction: column;
	align-items: center;
	margin-bottom: 48rpx;
}

.logo-wrap {
	width: 120rpx;
	height: 120rpx;
	border-radius: 32rpx;
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	display: flex;
	align-items: center;
	justify-content: center;
	margin-bottom: 24rpx;
	box-shadow: 0 8rpx 24rpx rgba(37, 99, 235, 0.3);
}

.logo-emoji {
	font-size: 60rpx;
}

.title {
	font-size: 38rpx;
	font-weight: 700;
	color: #1a1a1a;
	margin-bottom: 8rpx;
}

.subtitle {
	font-size: 24rpx;
	color: #999;
}

.form {
	display: flex;
	flex-direction: column;
	gap: 24rpx;
}

.input-group {
	display: flex;
	align-items: center;
	background: #f8f9fb;
	border-radius: 16rpx;
	padding: 0 24rpx;
	height: 96rpx;
	gap: 16rpx;
	border: 2rpx solid transparent;
	transition: all 0.3s ease;
}

.input-group:focus-within {
	background: #fff;
	border-color: #2563eb;
	box-shadow: 0 0 0 4rpx rgba(37, 99, 235, 0.1);
}

.input-icon {
	display: flex;
	align-items: center;
	justify-content: center;
}

.input {
	flex: 1;
	height: 48rpx;
	font-size: 28rpx;
	color: #333;
}

.placeholder { color: #bbb; }

.remember-group {
	display: flex;
	align-items: center;
	padding: 0 8rpx;
}

.remember-label {
	display: flex;
	align-items: center;
	gap: 10rpx;
}

.remember-text {
	font-size: 26rpx;
	color: #666;
}

.login-btn {
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	height: 96rpx;
	border-radius: 48rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	margin-top: 16rpx;
	box-shadow: 0 8rpx 24rpx rgba(37, 99, 235, 0.3);
	transition: all 0.3s ease;
	color: #fff;
	font-size: 32rpx;
	font-weight: 600;
	letter-spacing: 4rpx;
}

.login-btn:active {
	transform: translateY(2rpx);
	box-shadow: 0 4rpx 12rpx rgba(37, 99, 235, 0.2);
}

.login-btn[disabled] {
	background: #d1d5db;
	box-shadow: none;
	transform: none;
}
</style>
