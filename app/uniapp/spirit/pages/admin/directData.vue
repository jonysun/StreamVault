<template>
	<view class="container">
		<!-- 输入区域 -->
		<view class="input-card">
			<view class="card-header">
				<uni-icons type="compose" size="20" color="#16a34a"></uni-icons>
				<text class="card-title">直连解析</text>
			</view>
			<textarea
				class="input-area"
				placeholder="请输入视频/图文分享链接"
				placeholder-class="placeholder"
				v-model="directUrl"
				:auto-height="false"
			></textarea>
			<view class="type-row">
				<view class="type-btn" :class="{ active: directType === 2 }" @tap="directType = 2">
					<text class="type-text">仅解析</text>
				</view>
				<view class="type-btn" :class="{ active: directType === 1 }" @tap="directType = 1">
					<text class="type-text">解析并下载</text>
				</view>
			</view>
			<button class="submit-btn" @tap="directParse" :disabled="!directUrl">开始</button>
		</view>

		<!-- 解析结果 -->
		<view class="result-card" v-if="parseResult">
			<!-- 多视频 -->
			<view v-if="parseResult.type === 'multiple'">
				<view class="multi-tip">
					<text class="multi-tip-text">检测到 {{parseResult.totalCount || parseResult.videos.length}} 个视频</text>
					<text class="multi-tip-platform">平台：{{parseResult.platform || '未知'}}</text>
				</view>
				<view class="video-list" v-for="(video, idx) in parseResult.videos" :key="idx">
					<view class="video-item-row">
						<image v-if="video.coverUrl" :src="video.coverUrl" class="video-item-cover" mode="aspectFill" @error="onCoverError"></image>
						<view class="video-item-info">
							<text class="video-item-title">{{idx + 1}}. {{video.title || '无标题'}}</text>
							<view class="video-item-meta">
								<text class="meta-tag">{{video.author || '未知'}}</text>
								<text class="meta-tag">{{formatDuration(video.duration)}}</text>
								<text class="meta-tag tag-dash" v-if="video.isDash">DASH</text>
							</view>
							<view class="result-actions">
								<view class="action-btn" v-if="video.videoUrl" @tap="copyText(video.videoUrl)">
									<uni-icons type="paperplane" size="14" color="#fff"></uni-icons>
									<text class="action-text">复制</text>
								</view>
								<view class="action-btn btn-audio" v-if="video.audioUrl" @tap="copyText(video.audioUrl)">
									<uni-icons type="headphones" size="14" color="#fff"></uni-icons>
									<text class="action-text">音频</text>
								</view>
							</view>
						</view>
					</view>
				</view>
			</view>

			<!-- 单个视频 -->
			<view v-else-if="parseResult.mediaType === 'video'">
				<view class="result-media-row">
					<image v-if="parseResult.coverUrl" :src="parseResult.coverUrl" class="result-cover" mode="aspectFill" @error="onCoverError"></image>
					<view class="result-info">
						<text class="result-title">{{parseResult.title || '无标题'}}</text>
						<view class="result-meta">
							<text class="meta-tag tag-blue">{{parseResult.platform || '未知'}}</text>
							<text class="meta-tag">{{parseResult.author || '未知作者'}}</text>
							<text class="meta-tag">{{formatDuration(parseResult.duration)}}</text>
							<text class="meta-tag tag-dash" v-if="parseResult.isDash">DASH</text>
							<text class="meta-tag tag-warn" v-if="parseResult.needReferer">需Referer</text>
						</view>
						<view class="result-actions">
							<view class="action-btn" v-if="parseResult.videoUrl" @tap="copyText(parseResult.videoUrl)">
								<uni-icons type="paperplane" size="14" color="#fff"></uni-icons>
								<text class="action-text">复制视频</text>
							</view>
							<view class="action-btn btn-audio" v-if="parseResult.audioUrl" @tap="copyText(parseResult.audioUrl)">
								<uni-icons type="headphones" size="14" color="#fff"></uni-icons>
								<text class="action-text">复制音频</text>
							</view>
						</view>
					</view>
				</view>
				<view class="link-section" v-if="parseResult.videoUrl">
					<text class="link-label">视频链接</text>
					<view class="link-row">
						<text class="link-url" selectable>{{parseResult.videoUrl}}</text>
						<view class="link-copy" @tap="copyText(parseResult.videoUrl)">
							<uni-icons type="paperplane" size="14" color="#2563eb"></uni-icons>
						</view>
					</view>
				</view>
				<view class="link-section" v-if="parseResult.audioUrl">
					<text class="link-label">音频链接</text>
					<view class="link-row">
						<text class="link-url" selectable>{{parseResult.audioUrl}}</text>
						<view class="link-copy" @tap="copyText(parseResult.audioUrl)">
							<uni-icons type="paperplane" size="14" color="#2563eb"></uni-icons>
						</view>
					</view>
				</view>
			</view>

			<!-- 音频 -->
			<view v-else-if="parseResult.mediaType === 'audio'">
				<view class="result-media-row">
					<image v-if="parseResult.coverUrl" :src="parseResult.coverUrl" class="result-cover" mode="aspectFill" @error="onCoverError"></image>
					<view class="result-info">
						<text class="result-title">{{parseResult.title || '无标题'}}</text>
						<view class="result-meta">
							<text class="meta-tag tag-blue">{{parseResult.platform || '未知'}}</text>
							<text class="meta-tag">{{parseResult.author || '未知作者'}}</text>
							<text class="meta-tag">{{formatDuration(parseResult.duration)}}</text>
						</view>
						<view class="result-actions">
							<view class="action-btn" v-if="parseResult.videoUrl" @tap="copyText(parseResult.videoUrl)">
								<uni-icons type="paperplane" size="14" color="#fff"></uni-icons>
								<text class="action-text">复制链接</text>
							</view>
						</view>
					</view>
				</view>
				<view class="link-section" v-if="parseResult.videoUrl">
					<text class="link-label">音频链接</text>
					<view class="link-row">
						<text class="link-url" selectable>{{parseResult.videoUrl}}</text>
						<view class="link-copy" @tap="copyText(parseResult.videoUrl)">
							<uni-icons type="paperplane" size="14" color="#2563eb"></uni-icons>
						</view>
					</view>
				</view>
			</view>

			<!-- 图文 -->
			<view v-else-if="parseResult.mediaType === 'image'">
				<text class="result-title">{{parseResult.title || '无标题'}}</text>
				<view class="result-meta">
					<text class="meta-tag tag-green">{{parseResult.platform || '未知'}}</text>
					<text class="meta-tag">{{parseResult.author || '未知作者'}}</text>
					<text class="meta-tag">{{(parseResult.imageUrls && parseResult.imageUrls.length) || 0}}张</text>
				</view>
				<view class="image-grid" v-if="parseResult.imageUrls && parseResult.imageUrls.length">
					<view class="image-item" v-for="(img, idx) in parseResult.imageUrls" :key="idx">
						<image :src="img" class="image-thumb" mode="aspectFill" @tap="previewImage(img, parseResult.imageUrls)" @error="onCoverError"></image>
						<view class="image-copy" @tap="copyText(img)">
							<uni-icons type="paperplane" size="12" color="#fff"></uni-icons>
						</view>
					</view>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				directUrl: '',
				directType: 2,
				parseResult: null
			}
		},
		onShow() {
			const adminCookie = uni.getStorageSync('adminCookie');
			const expireTime = uni.getStorageSync('adminCookieExpire');
			const currentTime = new Date().getTime();
			if (!adminCookie || !expireTime || currentTime > expireTime) {
				uni.redirectTo({ url: '/pages/admin/login' });
			}
		},
		methods: {
			directParse() {
				if (!this.directUrl) return;
				const serveraddr = uni.getStorageSync('serveraddr');
				const serverport = uni.getStorageSync('serverport');
				const adminCookie = uni.getStorageSync('adminCookie');

				if (!serveraddr) {
					uni.showToast({ title: '请先配置服务器', icon: 'none' });
					return;
				}

				uni.showLoading({ title: '解析中...' });
				uni.request({
					url: `${serveraddr}:${serverport}/admin/api/directData?type=${this.directType}`,
					method: 'POST',
					header: {
						'Cookie': adminCookie,
						'content-type': 'application/x-www-form-urlencoded'
					},
					data: {
						originaladdress: this.directUrl
					},
					success: (res) => {
						uni.hideLoading();
						if (res.data && res.data.resCode === '000001') {
							if (this.directType === 1) {
								uni.showToast({ title: '已提交下载', icon: 'success' });
								this.parseResult = null;
							} else {
								const record = res.data.record;
								if (record) {
									if (record.type !== 'multiple' && !record.mediaType) {
										record.mediaType = 'video';
									}
									this.parseResult = record;
									uni.showToast({ title: '解析成功', icon: 'success' });
								} else {
									uni.showToast({ title: '解析结果为空', icon: 'none' });
								}
							}
						} else {
							uni.showToast({ title: res.data.resMsg || res.data.message || '解析失败', icon: 'none' });
						}
					},
					fail: () => {
						uni.hideLoading();
						uni.showToast({ title: '网络请求失败', icon: 'none' });
					}
				});
			},
			copyText(text) {
				uni.setClipboardData({
					data: text,
					success: () => {
						uni.showToast({ title: '已复制', icon: 'success' });
					}
				});
			},
			previewImage(current, urls) {
				uni.previewImage({
					current: current,
					urls: urls
				});
			},
			onCoverError() {},
			formatDuration(seconds) {
				if (!seconds) return '未知';
				const h = Math.floor(seconds / 3600);
				const m = Math.floor((seconds % 3600) / 60);
				const s = seconds % 60;
				if (h > 0) {
					return h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
				}
				return m + ':' + String(s).padStart(2, '0');
			}
		}
	}
</script>

<style>
.container {
	min-height: 100vh;
	background: #f6f7f8;
	padding: 24rpx 32rpx;
}

.input-card {
	background: #fff;
	border-radius: 24rpx;
	padding: 28rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.card-header {
	display: flex;
	align-items: center;
	margin-bottom: 20rpx;
}

.card-title {
	font-size: 30rpx;
	font-weight: 600;
	color: #333;
	margin-left: 10rpx;
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

.type-row {
	display: flex;
	margin-top: 20rpx;
}

.type-btn {
	flex: 1;
	height: 76rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	border: 2rpx solid #e5e7eb;
	background: #f9fafb;
	margin-right: 16rpx;
	border-radius: 12rpx;
}

.type-btn:last-child {
	margin-right: 0;
}

.type-btn.active {
	border-color: #16a34a;
	background: #f0fdf4;
}

.type-text {
	font-size: 26rpx;
	color: #666;
}

.type-btn.active .type-text {
	color: #16a34a;
	font-weight: 600;
}

.submit-btn {
	background: linear-gradient(135deg, #16a34a, #4ade80);
	height: 88rpx;
	border-radius: 44rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	margin-top: 24rpx;
	color: #fff;
	font-size: 30rpx;
	font-weight: 600;
	border: none;
	box-shadow: 0 4rpx 16rpx rgba(22, 163, 74, 0.25);
}

.submit-btn::after { border: none; }

.submit-btn[disabled] {
	background: #d1d5db;
	box-shadow: none;
}

.submit-btn:active {
	transform: scale(0.98);
}

/* 解析结果 */
.result-card {
	background: #fff;
	border-radius: 24rpx;
	padding: 28rpx;
	margin-top: 24rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
}

.result-media-row {
	display: flex;
}

.result-cover {
	width: 220rpx;
	height: 220rpx;
	border-radius: 16rpx;
	flex-shrink: 0;
	margin-right: 20rpx;
	background: #f0f0f0;
}

.result-info {
	flex: 1;
	min-width: 0;
	display: flex;
	flex-direction: column;
}

.result-title {
	font-size: 30rpx;
	font-weight: 600;
	color: #333;
	margin-bottom: 12rpx;
	display: -webkit-box;
	-webkit-box-orient: vertical;
	-webkit-line-clamp: 2;
	overflow: hidden;
}

.result-meta {
	display: flex;
	flex-wrap: wrap;
	margin-bottom: 16rpx;
}

.meta-tag {
	font-size: 22rpx;
	padding: 4rpx 12rpx;
	border-radius: 8rpx;
	background: #f3f4f6;
	color: #666;
	margin-right: 8rpx;
	margin-bottom: 8rpx;
}

.tag-blue {
	background: #eff6ff;
	color: #2563eb;
}

.tag-green {
	background: #f0fdf4;
	color: #16a34a;
}

.tag-dash {
	background: #fef3c7;
	color: #d97706;
}

.tag-warn {
	background: #fef2f2;
	color: #ef4444;
}

.result-actions {
	display: flex;
	flex-wrap: wrap;
}

.action-btn {
	display: flex;
	align-items: center;
	padding: 10rpx 24rpx;
	border-radius: 24rpx;
	background: linear-gradient(135deg, #2563eb, #3b82f6);
	margin-right: 12rpx;
	margin-bottom: 8rpx;
}

.action-btn:active {
	opacity: 0.8;
}

.btn-audio {
	background: linear-gradient(135deg, #7c3aed, #a78bfa);
}

.action-text {
	font-size: 24rpx;
	color: #fff;
	margin-left: 6rpx;
}

.link-section {
	margin-top: 20rpx;
	padding-top: 20rpx;
	border-top: 1rpx solid #f0f0f0;
}

.link-label {
	font-size: 24rpx;
	color: #999;
	margin-bottom: 8rpx;
	display: block;
}

.link-row {
	display: flex;
	align-items: center;
	background: #f8f9fb;
	border-radius: 12rpx;
	padding: 12rpx 16rpx;
}

.link-url {
	flex: 1;
	font-size: 24rpx;
	color: #333;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
	margin-right: 12rpx;
}

.link-copy {
	flex-shrink: 0;
	padding: 8rpx;
}

/* 图文结果 */
.image-grid {
	display: flex;
	flex-wrap: wrap;
	margin-top: 16rpx;
}

.image-item {
	width: calc(33.33% - 12rpx);
	margin-right: 16rpx;
	margin-bottom: 16rpx;
	position: relative;
	border-radius: 12rpx;
	overflow: hidden;
}

.image-item:nth-child(3n) {
	margin-right: 0;
}

.image-thumb {
	width: 100%;
	height: 200rpx;
	background: #f0f0f0;
}

.image-copy {
	position: absolute;
	bottom: 0;
	right: 0;
	background: rgba(0,0,0,0.5);
	padding: 6rpx 10rpx;
	border-radius: 8rpx 0 0 0;
}

/* 多视频结果 */
.multi-tip {
	background: #eff6ff;
	border-radius: 12rpx;
	padding: 16rpx 20rpx;
	margin-bottom: 20rpx;
}

.multi-tip-text {
	font-size: 26rpx;
	color: #2563eb;
	font-weight: 600;
	display: block;
}

.multi-tip-platform {
	font-size: 24rpx;
	color: #666;
	margin-top: 4rpx;
	display: block;
}

.video-list {
	margin-bottom: 16rpx;
}

.video-item-row {
	display: flex;
	padding-bottom: 16rpx;
	border-bottom: 1rpx solid #f5f5f5;
	margin-bottom: 16rpx;
}

.video-list:last-child .video-item-row {
	border-bottom: none;
	margin-bottom: 0;
}

.video-item-cover {
	width: 180rpx;
	height: 130rpx;
	border-radius: 12rpx;
	flex-shrink: 0;
	margin-right: 16rpx;
	background: #f0f0f0;
}

.video-item-info {
	flex: 1;
	min-width: 0;
	display: flex;
	flex-direction: column;
	justify-content: center;
}

.video-item-title {
	font-size: 26rpx;
	font-weight: 600;
	color: #333;
	margin-bottom: 8rpx;
	display: -webkit-box;
	-webkit-box-orient: vertical;
	-webkit-line-clamp: 1;
	overflow: hidden;
}

.video-item-meta {
	display: flex;
	flex-wrap: wrap;
	margin-bottom: 8rpx;
}
</style>
