<template>
	<view class="video-container" :class="themeClass">
		<cover-view class="top-controls">
			<cover-view class="icon-btn" @tap="toggleOrder" :title="orderTip">{{ orderShortText }}</cover-view>
			<cover-view class="icon-btn" @tap="openAuthorPopup">作</cover-view>
			<cover-view class="icon-btn" @tap="toggleMuted">{{ isMuted ? '静' : '声' }}</cover-view>
			<cover-view class="icon-btn" @tap="toggleInfoPanel">i</cover-view>
		</cover-view>

		<swiper class="video-swiper" :vertical="true" :current="currentIndex" @change="onSwiperChange" :duration="swipeDuration" :skip-hidden-item-layout="true" :disable-touch="true" @touchstart="onTouchStart" @touchend="onTouchEnd">
			<swiper-item v-for="(video, index) in playList" :key="video.id || video.videoid || index" class="swiper-cell">
				<view class="video-wrapper">
					<video
						:id="`video-${index}`"
						:src="getVideoSrc(index, video)"
						:poster="video.videocover"
						:controls="false"
						:autoplay="false"
						:muted="isMuted"
						:loop="false"
						:show-center-play-btn="false"
						:show-play-btn="false"
						:enable-progress-gesture="false"
						:object-fit="'contain'"
						:play-strategy="0"
						:codec="'hardware'"
						:http-cache="true"
						class="video-player"
						@play="onVideoPlay(index)"
						@pause="onVideoPause(index)"
						@ended="onVideoEnded(index)"
						@timeupdate="onTimeUpdate"
					></video>

					<cover-view class="touch-layer" @tap="onVideoTap"></cover-view>

					<cover-view class="video-overlay">
						<cover-view class="bottom-info">
							<cover-view class="author-name" @tap="selectAuthor(video.videoauthor)">@{{ video.videoauthor || '未知作者' }}</cover-view>
							<cover-view class="desc-text">{{ video.videoname || video.videodesc || '' }}</cover-view>
						</cover-view>
					</cover-view>

					<cover-view v-if="showControls" class="progress-wrap" @touchstart.stop.prevent="onProgressTouchStart" @touchmove.stop.prevent="onProgressTouchMove" @touchend.stop.prevent="onProgressTouchEnd">
						<cover-view class="progress-time">{{ currentTimeText }} / {{ durationText }}</cover-view>
						<cover-view class="progress-track">
							<cover-view class="progress-buffer" :style="{ width: bufferPercent + '%' }"></cover-view>
							<cover-view class="progress-played" :style="{ width: playedPercent + '%' }"></cover-view>
							<cover-view class="progress-thumb" :style="{ left: thumbLeft }"></cover-view>
						</cover-view>
					</cover-view>

					<cover-view v-if="!isPlaying && index === currentIndex" class="pause-indicator">暂停</cover-view>
				</view>
			</swiper-item>
		</swiper>

		<uni-popup ref="authorPopup" type="top" :background-color="isDark ? '#121212' : '#ffffff'">
			<view class="author-sheet" :class="themeClass">
				<view class="sheet-title">选择作者</view>
				<view class="author-list">
					<view class="author-chip" @tap="selectAuthor('')">全部作者</view>
					<view class="author-chip" v-for="a in authorOptions" :key="a" @tap="selectAuthor(a)">{{ a }}</view>
				</view>
			</view>
		</uni-popup>

		<uni-popup ref="infoPopup" type="bottom" :background-color="isDark ? '#0b0b0b' : '#ffffff'">
			<view class="info-sheet" :class="themeClass">
				<view class="sheet-title">播放信息</view>
				<view class="info-row">排序模式：{{ activeOrderLabel }}<text v-if="pendingOrderMode">（下一条将切换为{{ pendingOrderLabel }}）</text></view>
				<view class="info-row">播放索引：{{ currentIndex + 1 }} / {{ playList.length }}</view>
				<view class="info-row">首帧耗时：最近 {{ perfStats.lastMs }}ms，均值 {{ perfStats.avgMs }}ms（{{ perfStats.samples }}次）</view>
				<view class="info-row">播放状态：{{ isPlaying ? '播放中' : '暂停中' }}</view>
			</view>
		</uni-popup>
	</view>
</template>

<script>
	import uniPopup from '@/uni_modules/uni-popup/components/uni-popup/uni-popup.vue'
	import cacheManager from '@/utils/cacheManager.js'
	export default {
		components: { uniPopup },
		data() {
			return {
				baseList: [],
				playList: [],
				authorOptions: [],
				activeOrderMode: 'desc',
				pendingOrderMode: '',
				selectedAuthor: '',
				isMuted: true,
				isPlaying: false,
				currentIndex: 0,
				activePlayingIndex: -1,
				pageNo: 1,
				isLoading: false,
				hasMore: true,
				serveraddr: '',
				serverport: '',
				servertoken: '',
				videoContexts: {},
				cacheSettings: cacheManager.readSettings(),
				swipeDuration: 220,
				preloadNeighbors: 1,
				playDelayMs: 40,
				playbackMode: 'autonext',
				appTheme: 'light',
				touchStartY: 0,
				touchStartTs: 0,
				isDraggingProgress: false,
				showControls: false,
				controlsHideTimer: null,
				durationSec: 0,
				currentSec: 0,
				bufferSec: 0,
				switchPending: {},
				perfStats: {
					lastMs: 0,
					avgMs: 0,
					samples: 0,
					totalMs: 0
				}
			}
		},
		computed: {
			isDark() {
				return this.appTheme === 'dark'
			},
			themeClass() {
				return this.isDark ? 'theme-dark' : 'theme-light'
			},
			activeOrderLabel() {
				if (this.activeOrderMode === 'asc') return '顺序'
				if (this.activeOrderMode === 'random') return '随机'
				return '倒序'
			},
			pendingOrderLabel() {
				if (this.pendingOrderMode === 'asc') return '顺序'
				if (this.pendingOrderMode === 'random') return '随机'
				return '倒序'
			},
			orderShortText() {
				if (this.activeOrderMode === 'asc') return '顺'
				if (this.activeOrderMode === 'random') return '随'
				return '倒'
			},
			orderTip() {
				return this.pendingOrderMode ? `当前${this.activeOrderLabel}，下一条切换${this.pendingOrderLabel}` : `当前${this.activeOrderLabel}`
			},
			authorText() {
				return this.selectedAuthor && this.selectedAuthor.trim() ? this.selectedAuthor : '全部'
			},
			durationText() {
				return this.toClock(this.durationSec)
			},
			currentTimeText() {
				return this.toClock(this.currentSec)
			},
			playedPercent() {
				if (!this.durationSec) return 0
				return Math.max(0, Math.min(100, (this.currentSec / this.durationSec) * 100))
			},
			bufferPercent() {
				if (!this.durationSec) return 0
				return Math.max(this.playedPercent, Math.min(100, (this.bufferSec / this.durationSec) * 100))
			},
			thumbLeft() {
				return `calc(${this.playedPercent}% - 10rpx)`
			}
		},
		onLoad() {
			this.ensureServerConfig()
			this.cacheSettings = cacheManager.readSettings()
			this.applyFeedSettings()
			this.loadVideos()
		},
		onShow() {
			this.cacheSettings = cacheManager.readSettings()
			this.applyFeedSettings()
			if (!this.serveraddr || !this.serverport || !this.servertoken) {
				this.ensureServerConfig()
			}
			if (this.playList.length === 0) {
				this.loadVideos()
			}
		},
		methods: {
			toClock(raw) {
				const n = Math.max(0, Math.floor(Number(raw || 0)))
				const m = Math.floor(n / 60)
				const s = n % 60
				return `${m}:${s < 10 ? '0' : ''}${s}`
			},
			showControlsBriefly() {
				this.showControls = true
				if (this.controlsHideTimer) clearTimeout(this.controlsHideTimer)
				this.controlsHideTimer = setTimeout(() => {
					if (!this.isDraggingProgress) this.showControls = false
				}, 2600)
			},
			onVideoTap() {
				if (this.isPlaying) this.pauseCurrent()
				else this.resumeCurrent()
				this.showControlsBriefly()
			},
			pauseCurrent() {
				const c = this.videoContexts[this.currentIndex]
				if (c) c.pause()
				this.isPlaying = false
			},
			resumeCurrent() {
				const c = this.videoContexts[this.currentIndex]
				if (c) c.play()
				this.isPlaying = true
			},
			onProgressTouchStart(e) {
				this.isDraggingProgress = true
				this.showControls = true
				this.updateProgressByTouch(e)
			},
			onProgressTouchMove(e) {
				if (!this.isDraggingProgress) return
				this.updateProgressByTouch(e)
			},
			onProgressTouchEnd() {
				this.isDraggingProgress = false
				this.showControlsBriefly()
			},
			updateProgressByTouch(e) {
				if (!this.durationSec) return
				const t = (e && e.touches && e.touches[0]) || (e && e.changedTouches && e.changedTouches[0])
				if (!t) return
				const sys = uni.getSystemInfoSync()
				const ww = Number(sys.windowWidth || 375)
				const x = Math.max(0, Math.min(ww, Number(t.pageX || 0)))
				const ratio = ww > 0 ? (x / ww) : 0
				const target = Math.max(0, Math.min(this.durationSec, this.durationSec * ratio))
				this.currentSec = target
				const c = this.videoContexts[this.currentIndex]
				if (c) c.seek(target)
			},
			onTouchStart(e) {
				if (this.isDraggingProgress) return
				const t = e && e.touches && e.touches[0]
				if (!t) return
				this.touchStartY = Number(t.pageY || 0)
				this.touchStartTs = Date.now()
			},
			onTouchEnd(e) {
				if (this.isDraggingProgress) return
				const t = e && e.changedTouches && e.changedTouches[0]
				if (!t) return
				const endY = Number(t.pageY || 0)
				const dy = endY - this.touchStartY
				const dt = Math.max(1, Date.now() - (this.touchStartTs || Date.now()))
				const velocity = Math.abs(dy) / dt
				const sys = uni.getSystemInfoSync()
				const minDistancePx = Math.max(40, Number(sys.windowHeight || 720) * 0.12)
				const passDistance = Math.abs(dy) >= minDistancePx
				const passVelocity = velocity >= 0.45
				if (!passDistance && !passVelocity) {
					return
				}
				if (dy < 0) {
					this.stepByDirection(1)
				} else {
					this.stepByDirection(-1)
				}
			},
			stepByDirection(direction) {
				if (direction !== 1 && direction !== -1) return
				if (this.pendingOrderMode && this.pendingOrderMode !== this.activeOrderMode) {
					this.applyPendingOrder(direction)
					return
				}
				this.stepTo(this.currentIndex + direction)
			},
			applyPendingOrder(direction) {
				const current = this.playList[this.currentIndex]
				const currentId = this.getVideoKey(current)
				this.activeOrderMode = this.pendingOrderMode
				this.pendingOrderMode = ''
				const list = this.getOrderedFilteredList(this.activeOrderMode)
				this.playList = list
				let idx = list.findIndex(v => this.getVideoKey(v) === currentId)
				if (idx < 0) idx = 0
				const target = Math.max(0, Math.min(list.length - 1, idx + direction))
				this.currentIndex = target
				this.$nextTick(() => this.playCurrent())
				uni.showToast({ title: `已切换${this.activeOrderLabel}`, icon: 'none' })
			},
			stepTo(nextIndex) {
				const safe = Math.max(0, Math.min(this.playList.length - 1, nextIndex))
				if (safe === this.currentIndex) return
				this.currentIndex = safe
				this.playCurrent()
				if (this.currentIndex >= this.playList.length - 2) {
					if (this.hasMore) {
						this.loadVideos()
					} else if (this.activeOrderMode === 'random' && this.playList.length > 1 && this.currentIndex >= this.playList.length - 1) {
						this.rebuildPlayList(true)
					}
				}
			},
			applyFeedSettings() {
				const s = this.cacheSettings || {}
				this.swipeDuration = Math.max(120, parseInt(s.feedSwipeDuration || 220, 10))
				this.preloadNeighbors = Math.max(0, Math.min(2, parseInt(s.feedPreloadNeighbors || 1, 10)))
				this.playDelayMs = Math.max(0, Math.min(300, parseInt(s.feedPlayDelayMs || 40, 10)))
				this.playbackMode = ['autonext', 'loopone', 'stop'].includes(s.playbackMode) ? s.playbackMode : 'autonext'
				this.appTheme = (s.appTheme === 'dark' ? 'dark' : 'light')
			},
			ensureServerConfig() {
				this.serveraddr = uni.getStorageSync('serveraddr') || ''
				this.serverport = uni.getStorageSync('serverport') || ''
				this.servertoken = uni.getStorageSync('servertoken') || ''
				if (this.serveraddr && this.serverport && this.servertoken) {
					return
				}
				const serverlist = uni.getStorageSync('serverlist') || []
				if (!serverlist.length) {
					return
				}
				let picked = serverlist.find(s => s && s.default === 'y')
				if (!picked) picked = serverlist[0]
				if (!picked) {
					return
				}
				this.serveraddr = picked.server || ''
				this.serverport = picked.port || ''
				this.servertoken = picked.token || ''
				if (this.serveraddr) uni.setStorageSync('serveraddr', this.serveraddr)
				if (this.serverport) uni.setStorageSync('serverport', this.serverport)
				if (this.servertoken) uni.setStorageSync('servertoken', this.servertoken)
			},
			normalizePath(rawPath) {
				if (!rawPath) return ''
				if (/^https?:\/\//i.test(rawPath)) return rawPath
				let p = rawPath.replace(/\\/g, '/')
				if (!p.startsWith('/')) p = '/' + p
				const encodedPath = p.split('/').map(s => encodeURIComponent(s)).join('/')
				return `${this.serveraddr}:${this.serverport}${encodedPath}?apptoken=${this.servertoken}`
			},
			loadVideos() {
				if (this.isLoading || !this.hasMore) return
				if (!this.serveraddr || !this.serverport || !this.servertoken) {
					uni.showToast({ title: '请先配置服务器', icon: 'none' })
					return
				}
				this.isLoading = true
				uni.request({
					url: `${this.serveraddr}:${this.serverport}/api/findVideos?token=${this.servertoken}`,
					method: 'POST',
					header: { 'content-type': 'application/x-www-form-urlencoded' },
					data: { pageNo: this.pageNo, pageSize: 15, sortField: 'createtime', sortOrder: 'desc' },
						success: (res) => {
						if (res.data && res.data.resCode === '000001' && res.data.record && res.data.record.content) {
							const list = res.data.record.content || []
							list.forEach(v => {
								v.playurl = this.normalizePath(v.playurl)
								v.videounrealaddr = this.normalizePath(v.videounrealaddr)
								v.videocover = this.normalizePath(v.videocover)
								v.playSrc = v.playurl || v.videounrealaddr
							})
							this.baseList = this.baseList.concat(list)
							this.pageNo++
							this.hasMore = !res.data.record.last
							this.refreshAuthorOptions()
							this.rebuildPlayList(false)
							cacheManager.prefetchVideos(list)
						} else {
							uni.showToast({
								title: (res.data && (res.data.message || res.data.resMsg)) || '获取视频失败',
								icon: 'none'
							})
						}
					},
					fail: () => {
						uni.showToast({ title: '网络异常，请检查服务器', icon: 'none' })
					},
					complete: () => { this.isLoading = false }
				})
			},
			refreshAuthorOptions() {
				const s = new Set()
				this.baseList.forEach(v => {
					if (v.videoauthor && v.videoauthor.trim()) s.add(v.videoauthor.trim())
				})
				this.authorOptions = Array.from(s).sort()
			},
			getOrderedFilteredList(mode) {
				let list = this.baseList.slice()
				if (this.selectedAuthor && this.selectedAuthor.trim()) list = list.filter(v => (v.videoauthor || '').indexOf(this.selectedAuthor.trim()) >= 0)
				if (mode === 'asc') list.reverse()
				if (mode === 'random') this.shuffle(list)
				return list
			},
			getVideoKey(v) {
				if (!v) return ''
				if (v.id != null) return String(v.id)
				if (v.videoid != null) return String(v.videoid)
				return String(v.playurl || v.videounrealaddr || v.videoname || '')
			},
			rebuildPlayList(keepCurrent) {
				const currentId = keepCurrent ? this.getVideoKey(this.playList[this.currentIndex]) : ''
				const list = this.getOrderedFilteredList(this.activeOrderMode)
				this.playList = list
				if (!keepCurrent) this.currentIndex = 0
				else {
					const idx = list.findIndex(v => this.getVideoKey(v) === currentId)
					this.currentIndex = idx >= 0 ? idx : 0
				}
				this.$nextTick(() => this.playCurrent())
			},
			shuffle(arr) {
				for (let i = arr.length - 1; i > 0; i--) {
					const j = Math.floor(Math.random() * (i + 1))
					const t = arr[i]
					arr[i] = arr[j]
					arr[j] = t
				}
			},
			onSwiperChange(e) {
				const next = Number(e && e.detail && e.detail.current)
				if (Number.isNaN(next)) return
				if (next > this.currentIndex) this.stepByDirection(1)
				else if (next < this.currentIndex) this.stepByDirection(-1)
			},
			playCurrent() {
				this.pauseAll()
				const idx = this.currentIndex
				if (idx < 0 || idx >= this.playList.length) return
				this.activePlayingIndex = idx
				this.isPlaying = false
				this.switchPending[idx] = Date.now()
				const current = this.playList[idx]
				if (current) {
					const playable = cacheManager.getPlayableUrl(current)
					if (playable && playable !== current.playSrc) {
						this.$set(current, 'playSrc', playable)
					} else if (!current.playSrc) {
						this.$set(current, 'playSrc', current.playurl || current.videounrealaddr || '')
					}
				}
				if (!this.videoContexts[idx]) {
					this.videoContexts[idx] = uni.createVideoContext(`video-${idx}`, this)
				}
				if (this.videoContexts[idx]) {
					setTimeout(() => {
						this.videoContexts[idx] && this.videoContexts[idx].play()
					}, this.playDelayMs)
				}
				this.prefetchAround(idx)
				this.showControlsBriefly()
			},
			getVideoSrc(index, video) {
				if (!video) return ''
				if (Math.abs(index - this.currentIndex) <= this.preloadNeighbors) {
					return video.playSrc || ''
				}
				return ''
			},
			prefetchAround(idx) {
				const queue = []
				if (idx + 1 < this.playList.length) {
					queue.push(this.playList[idx + 1])
				}
				if (idx - 1 >= 0) {
					queue.push(this.playList[idx - 1])
				}
				for (let i = idx + 2; i < Math.min(this.playList.length, idx + 6); i++) {
					queue.push(this.playList[i])
				}
				cacheManager.prefetchVideos(queue)
			},
			pauseAll() {
				Object.keys(this.videoContexts).forEach(k => {
					const c = this.videoContexts[k]
					if (c) c.pause()
				})
				this.isPlaying = false
			},
			onVideoPlay(index) {
				if (index !== this.currentIndex) {
					const c = this.videoContexts[index] || uni.createVideoContext(`video-${index}`, this)
					this.videoContexts[index] = c
					if (c) c.pause()
					return
				}
				this.activePlayingIndex = index
				this.isPlaying = true
				const start = this.switchPending[index]
				if (!start) return
				const ms = Math.max(0, Date.now() - start)
				this.$delete(this.switchPending, index)
				const nextSamples = (this.perfStats.samples || 0) + 1
				const nextTotal = (this.perfStats.totalMs || 0) + ms
				this.perfStats.lastMs = ms
				this.perfStats.samples = nextSamples
				this.perfStats.totalMs = nextTotal
				this.perfStats.avgMs = Math.round(nextTotal / nextSamples)
			},
			onVideoPause(index) {
				if (index === this.currentIndex) this.isPlaying = false
			},
			onTimeUpdate(e) {
				const d = e && e.detail ? e.detail : {}
				this.currentSec = Number(d.currentTime || 0)
				this.durationSec = Number(d.duration || this.durationSec || 0)
				this.bufferSec = Number(d.buffered || this.bufferSec || 0)
			},
			onVideoEnded(index) {
				if (index !== this.currentIndex) return
				if (this.playbackMode === 'loopone') {
					this.resumeCurrent()
					return
				}
				if (this.playbackMode === 'stop') {
					this.pauseCurrent()
					return
				}
				if (index < this.playList.length - 1) this.stepByDirection(1)
				else if (this.hasMore) this.loadVideos()
				else if (this.activeOrderMode === 'random' && this.playList.length > 1) {
					this.rebuildPlayList(false)
				}
			},
			toggleOrder() {
				let next = 'desc'
				const now = this.pendingOrderMode || this.activeOrderMode
				if (now === 'desc') next = 'asc'
				else if (now === 'asc') next = 'random'
				this.pendingOrderMode = next
				uni.showToast({ title: `下一条切换为${this.pendingOrderLabel}`, icon: 'none' })
			},
			toggleMuted() {
				this.isMuted = !this.isMuted
			},
			toggleInfoPanel() {
				this.$refs.infoPopup.open()
			},
			openAuthorPopup() {
				this.$refs.authorPopup.open()
			},
			selectAuthor(author) {
				this.selectedAuthor = author || ''
				this.$refs.authorPopup.close()
				this.rebuildPlayList(true)
			}
		},
		beforeDestroy() {
			if (this.controlsHideTimer) clearTimeout(this.controlsHideTimer)
		}
	}
</script>

<style>
	.video-container { width: 100%; height: 100vh; background: #000; position: relative; overflow: hidden; }
	.video-swiper { width: 100%; height: 100%; }
	.swiper-cell { background: #000; }
	.video-wrapper { width: 100%; height: 100%; position: relative; overflow: hidden; background: #000; }
	.video-player { position: absolute; left: 0; top: 0; width: 100%; height: 100%; background: #000; }
	.touch-layer { position: absolute; left: 0; top: 0; right: 0; bottom: 0; z-index: 5; }
	.video-overlay { position: absolute; left: 0; right: 0; bottom: 0; padding: 24rpx; background: linear-gradient(to top, rgba(0,0,0,.55), transparent); }
	.bottom-info { color: #fff; }
	.author-name { font-size: 28rpx; font-weight: 600; display: block; margin-bottom: 10rpx; }
	.desc-text { font-size: 24rpx; }
	.top-controls { position: fixed; z-index: 99; top: 28rpx; right: 20rpx; display: flex; flex-direction: column; gap: 12rpx; }
	.icon-btn { width: 60rpx; height: 60rpx; line-height: 60rpx; border-radius: 30rpx; text-align: center; background: rgba(0,0,0,.45); color: #fff; font-size: 24rpx; }
	.progress-wrap { position: absolute; z-index: 20; left: 0; right: 0; bottom: 140rpx; padding: 18rpx 22rpx 26rpx; background: rgba(0,0,0,.35); }
	.progress-time { color: #fff; font-size: 22rpx; margin-bottom: 12rpx; }
	.progress-track { position: relative; height: 14rpx; border-radius: 8rpx; background: rgba(255,255,255,.22); overflow: visible; }
	.progress-buffer { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 8rpx; background: rgba(255,255,255,.38); }
	.progress-played { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 8rpx; background: #ffffff; }
	.progress-thumb { position: absolute; top: -7rpx; width: 28rpx; height: 28rpx; border-radius: 14rpx; background: #fff; box-shadow: 0 2rpx 10rpx rgba(0,0,0,.45); }
	.pause-indicator { position: absolute; left: 50%; top: 46%; transform: translate(-50%, -50%); z-index: 15; background: rgba(0,0,0,.45); color: #fff; border-radius: 24rpx; padding: 10rpx 18rpx; font-size: 24rpx; }
	.author-sheet { padding: 24rpx; max-height: 60vh; overflow-y: auto; }
	.sheet-title { font-size: 28rpx; font-weight: 700; margin-bottom: 16rpx; }
	.author-list { display: flex; flex-wrap: wrap; }
	.author-chip { margin: 0 12rpx 12rpx 0; padding: 10rpx 18rpx; border-radius: 26rpx; background: #f3f4f6; color: #374151; font-size: 24rpx; }
	.info-sheet { padding: 24rpx 24rpx 36rpx; }
	.info-row { font-size: 24rpx; color: #1f2937; margin-top: 12rpx; line-height: 1.5; }
	.theme-dark { background: #000; color: #f3f4f6; }
	.theme-dark .sheet-title { color: #f3f4f6; }
	.theme-dark .author-chip { background: #1f2937; color: #e5e7eb; }
	.theme-dark .info-row { color: #e5e7eb; }
	.theme-light { background: #fff; color: #111827; }
</style>
