<template>
	<view class="container">
		<view class="card">
			<view class="row">
				<text class="label">启用缓存</text>
				<switch :checked="settings.enabled" @change="onSwitch('enabled', $event)" />
			</view>
			<view class="row">
				<text class="label">仅Wi-Fi预缓存</text>
				<switch :checked="settings.wifiOnly" @change="onSwitch('wifiOnly', $event)" />
			</view>
			<view class="row">
				<text class="label">允许隐私视频缓存</text>
				<switch :checked="settings.allowPrivacy" @change="onSwitch('allowPrivacy', $event)" />
			</view>
			<view class="row-input">
				<text class="label">缓存条数上限</text>
				<input type="number" v-model="settings.maxCount" class="ipt" />
			</view>
			<view class="row-input">
				<text class="label">缓存总大小(MB)</text>
				<input type="number" v-model="settings.maxSizeMB" class="ipt" />
			</view>
			<view class="row-input">
				<text class="label">滑动动画时长(ms)</text>
				<input type="number" v-model="settings.feedSwipeDuration" class="ipt" />
			</view>
			<view class="row-input">
				<text class="label">预加载相邻视频数</text>
				<input type="number" v-model="settings.feedPreloadNeighbors" class="ipt" />
			</view>
			<view class="row-input">
				<text class="label">切页后播放延迟(ms)</text>
				<input type="number" v-model="settings.feedPlayDelayMs" class="ipt" />
			</view>
		</view>

		<view class="card">
			<text class="stats">当前缓存: {{stats.count}} 条 / {{sizeMB}} MB</text>
			<view class="btn-row">
				<button class="btn save" @tap="save">保存设置</button>
				<button class="btn clear" @tap="clearAll">清理缓存</button>
			</view>
		</view>
	</view>
</template>

<script>
	import cacheManager from '@/utils/cacheManager.js'
	export default {
		data() {
			return {
				settings: cacheManager.readSettings(),
				stats: cacheManager.getStats()
			}
		},
		computed: {
			sizeMB() {
				return (Number(this.stats.sizeBytes || 0) / 1024 / 1024).toFixed(1)
			}
		},
		onShow() {
			this.settings = cacheManager.readSettings()
			this.stats = cacheManager.getStats()
		},
		methods: {
			onSwitch(key, e) {
				this.$set(this.settings, key, !!(e && e.detail && e.detail.value))
			},
			save() {
				const payload = {
					enabled: !!this.settings.enabled,
					wifiOnly: !!this.settings.wifiOnly,
					allowPrivacy: !!this.settings.allowPrivacy,
					maxCount: Math.max(1, parseInt(this.settings.maxCount || 10, 10)),
					maxSizeMB: Math.max(100, parseInt(this.settings.maxSizeMB || 1024, 10)),
					feedSwipeDuration: Math.max(120, parseInt(this.settings.feedSwipeDuration || 220, 10)),
					feedPreloadNeighbors: Math.max(0, Math.min(2, parseInt(this.settings.feedPreloadNeighbors || 1, 10))),
					feedPlayDelayMs: Math.max(0, Math.min(300, parseInt(this.settings.feedPlayDelayMs || 40, 10)))
				}
				cacheManager.writeSettings(payload)
				cacheManager.evictIfNeeded()
				this.stats = cacheManager.getStats()
				uni.showToast({ title: '已保存', icon: 'success' })
			},
			clearAll() {
				uni.showModal({
					title: '确认清理',
					content: '将删除所有本地缓存文件，是否继续？',
					success: (res) => {
						if (!res.confirm) return
						cacheManager.clearAll()
						this.stats = cacheManager.getStats()
						uni.showToast({ title: '已清理', icon: 'success' })
					}
				})
			}
		}
	}
</script>

<style>
.container { min-height: 100vh; background: #f6f7f8; padding: 24rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 20rpx; margin-bottom: 20rpx; }
.row { display:flex; align-items:center; justify-content:space-between; padding: 14rpx 0; border-bottom: 1px solid #f1f1f1; }
.row-input { padding: 14rpx 0; }
.label { font-size: 28rpx; color:#111827; }
.ipt { margin-top: 10rpx; border: 1px solid #e5e7eb; border-radius: 10rpx; height: 68rpx; padding: 0 18rpx; font-size: 26rpx; }
.stats { font-size: 26rpx; color:#374151; }
.btn-row { display:flex; gap: 16rpx; margin-top: 18rpx; }
.btn { flex:1; border-radius: 12rpx; font-size: 28rpx; }
.btn.save { background:#2563eb; color:#fff; }
.btn.clear { background:#ef4444; color:#fff; }
</style>
