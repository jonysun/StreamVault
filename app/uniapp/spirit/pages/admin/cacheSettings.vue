<template>
	<view class="container">
		<view class="card">
			<view class="row-input">
				<text class="label">App主题</text>
				<picker class="picker" :range="themeOptions" range-key="label" :value="themeIndex" @change="onThemeChange">
					<view class="picker-value">{{ themeLabel }}</view>
				</picker>
			</view>
			<view class="row-input">
				<text class="label">播放结束策略</text>
				<picker class="picker" :range="playbackOptions" range-key="label" :value="playbackIndex" @change="onPlaybackModeChange">
					<view class="picker-value">{{ playbackLabel }}</view>
				</picker>
			</view>
			<view class="row-input">
				<text class="label">播放源策略</text>
				<picker class="picker" :range="sourceOptions" range-key="label" :value="sourceIndex" @change="onSourceModeChange">
					<view class="picker-value">{{ sourceLabel }}</view>
				</picker>
			</view>
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
				<text class="label">预取视频数</text>
				<input type="number" v-model="settings.feedPrefetchCount" class="ipt" />
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
				stats: cacheManager.getStats(),
				themeOptions: [
					{ label: '浅色', value: 'light' },
					{ label: '夜间', value: 'dark' }
				],
				playbackOptions: [
					{ label: '自动下一条', value: 'autonext' },
					{ label: '本条循环', value: 'loopone' },
					{ label: '播放后停止', value: 'stop' }
				],
				sourceOptions: [
					{ label: 'MP4优先', value: 'prefer_mp4' },
					{ label: 'HLS优先', value: 'prefer_hls' },
					{ label: '仅MP4', value: 'mp4_only' },
					{ label: '仅HLS', value: 'hls_only' }
				]
			}
		},
		computed: {
			sizeMB() {
				return (Number(this.stats.sizeBytes || 0) / 1024 / 1024).toFixed(1)
			},
			themeIndex() {
				const mode = this.settings.appTheme || 'light'
				const idx = this.themeOptions.findIndex(x => x.value === mode)
				return idx >= 0 ? idx : 0
			},
			themeLabel() {
				const i = this.themeIndex
				return (this.themeOptions[i] && this.themeOptions[i].label) || '浅色'
			},
			playbackIndex() {
				const mode = this.settings.playbackMode || 'autonext'
				const idx = this.playbackOptions.findIndex(x => x.value === mode)
				return idx >= 0 ? idx : 0
			},
			playbackLabel() {
				const i = this.playbackIndex
				return (this.playbackOptions[i] && this.playbackOptions[i].label) || '自动下一条'
			},
			sourceIndex() {
				const mode = this.settings.playbackSourceMode || 'prefer_mp4'
				const idx = this.sourceOptions.findIndex(x => x.value === mode)
				return idx >= 0 ? idx : 0
			},
			sourceLabel() {
				const i = this.sourceIndex
				return (this.sourceOptions[i] && this.sourceOptions[i].label) || 'MP4优先'
			}
		},
		onShow() {
			this.settings = cacheManager.readSettings()
			this.stats = cacheManager.getStats()
		},
		methods: {
			onThemeChange(e) {
				const i = parseInt(e && e.detail && e.detail.value, 10)
				const picked = this.themeOptions[Number.isNaN(i) ? 0 : i] || this.themeOptions[0]
				this.$set(this.settings, 'appTheme', picked.value)
			},
			onPlaybackModeChange(e) {
				const i = parseInt(e && e.detail && e.detail.value, 10)
				const picked = this.playbackOptions[Number.isNaN(i) ? 0 : i] || this.playbackOptions[0]
				this.$set(this.settings, 'playbackMode', picked.value)
			},
			onSourceModeChange(e) {
				const i = parseInt(e && e.detail && e.detail.value, 10)
				const picked = this.sourceOptions[Number.isNaN(i) ? 0 : i] || this.sourceOptions[0]
				this.$set(this.settings, 'playbackSourceMode', picked.value)
			},
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
					feedPrefetchCount: Math.max(1, Math.min(12, parseInt(this.settings.feedPrefetchCount || 6, 10))),
					feedPlayDelayMs: Math.max(0, Math.min(300, parseInt(this.settings.feedPlayDelayMs || 40, 10))),
					playbackSourceMode: ['prefer_mp4', 'prefer_hls', 'mp4_only', 'hls_only'].includes(this.settings.playbackSourceMode) ? this.settings.playbackSourceMode : 'prefer_mp4',
					appTheme: (this.settings.appTheme === 'dark' ? 'dark' : 'light'),
					playbackMode: ['autonext', 'loopone', 'stop'].includes(this.settings.playbackMode) ? this.settings.playbackMode : 'autonext'
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
.picker { margin-top: 10rpx; }
.picker-value { border: 1px solid #e5e7eb; border-radius: 10rpx; height: 68rpx; line-height: 68rpx; padding: 0 18rpx; font-size: 26rpx; color: #111827; }
.ipt { margin-top: 10rpx; border: 1px solid #e5e7eb; border-radius: 10rpx; height: 68rpx; padding: 0 18rpx; font-size: 26rpx; }
.stats { font-size: 26rpx; color:#374151; }
.btn-row { display:flex; gap: 16rpx; margin-top: 18rpx; }
.btn { flex:1; border-radius: 12rpx; font-size: 28rpx; }
.btn.save { background:#2563eb; color:#fff; }
.btn.clear { background:#ef4444; color:#fff; }
</style>
