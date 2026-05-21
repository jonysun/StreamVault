<template>
	<view class="video-container" :class="themeClass">
		<cover-view class="top-controls">
			<cover-view class="icon-btn" @tap="toggleOrder" :title="orderTip">{{ orderShortText }}</cover-view>
			<cover-view class="icon-btn" @tap="openAuthorPopup">作</cover-view>
			<cover-view class="icon-btn" @tap="toggleMuted">{{ isMuted ? '静' : '声' }}</cover-view>
			<cover-view class="icon-btn" @tap="toggleInfoPanel">i</cover-view>
		</cover-view>

		<swiper class="video-swiper" :vertical="true" :current="currentIndex" @change="onSwiperChange" :duration="swipeDuration" :skip-hidden-item-layout="true">
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
						@error="onVideoError(index)"
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

					<cover-view v-if="showControls && index === currentIndex" class="progress-wrap" @touchstart.stop.prevent="onProgressTouchStart" @touchmove.stop.prevent="onProgressTouchMove" @touchend.stop.prevent="onProgressTouchEnd">
						<cover-view class="progress-time">{{ currentTimeText }} / {{ durationText }}</cover-view>
						<cover-view class="progress-track">
							<cover-view class="progress-buffer" :style="{ width: bufferPercent + '%' }"></cover-view>
							<cover-view class="progress-played" :style="{ width: playedPercent + '%' }"></cover-view>
							<cover-view class="progress-thumb" :style="{ left: thumbLeft }"></cover-view>
						</cover-view>
					</cover-view>

					<cover-view v-if="manualPaused && !isPlaying && index === currentIndex" class="pause-indicator">暂停</cover-view>
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
				<view class="info-row">播放源策略：{{ playbackSourceLabel }}</view>
				<view class="info-row">当前实际源：{{ activeSourceType }}</view>
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
				pageNo: 0,
				sessionRandomSeed: '',
				requestPageSize: 50,
				prefetchTriggerOffset: 5,
				prefetchCount: 6,
				isResettingFeed: false,
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
				playbackSourceMode: 'prefer_mp4',
				playbackMode: 'autonext',
				appTheme: 'light',
				isDraggingProgress: false,
				showControls: false,
				manualPaused: false,
				controlsHideTimer: null,
				durationSec: 0,
				currentSec: 0,
				bufferSec: 0,
				playRequestToken: 0,
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
			playbackSourceLabel() {
				if (this.playbackSourceMode === 'mp4_only') return '仅MP4'
				if (this.playbackSourceMode === 'hls_only') return '仅HLS'
				if (this.playbackSourceMode === 'prefer_hls') return 'HLS优先'
				return 'MP4优先'
			},
			activeSourceType() {
				const current = this.playList[this.currentIndex]
				if (!current || !current.playSrc) return '未知'
				return /\.m3u8(\?|$)/i.test(current.playSrc) ? 'HLS' : 'MP4'
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
			this.initFeedSession()
			this.resetAndLoadFeed({ keepOrder: true })
		},
		onShow() {
			this.cacheSettings = cacheManager.readSettings()
			this.applyFeedSettings()
			if (!this.serveraddr || !this.serverport || !this.servertoken) {
				this.ensureServerConfig()
			}
			if (this.playList.length === 0) {
				this.initFeedSession()
				this.resetAndLoadFeed({ keepOrder: true })
			}
		},
		methods: {
			createRandomSeed() {
				return `${Date.now()}_${Math.floor(Math.random() * 1000000)}`
			},
			initFeedSession() {
				if (!this.sessionRandomSeed || this.activeOrderMode === 'random') {
					this.sessionRandomSeed = this.createRandomSeed()
				}
			},
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
				this.playRequestToken++
				const c = this.videoContexts[this.currentIndex]
				if (c) c.pause()
				this.isPlaying = false
				this.manualPaused = true
			},
			resumeCurrent() {
				this.playRequestToken++
				const c = this.videoContexts[this.currentIndex]
				if (c) c.play()
				this.isPlaying = true
				this.manualPaused = false
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
			stepByDirection(direction) {
				if (direction !== 1 && direction !== -1) return
				this.showControls = false
				if (this.pendingOrderMode && this.pendingOrderMode !== this.activeOrderMode) {
					this.applyPendingOrder()
					return
				}
				this.stepTo(this.currentIndex + direction)
			},
			applyPendingOrder() {
				this.activeOrderMode = this.pendingOrderMode
				this.pendingOrderMode = ''
				this.resetAndLoadFeed({ resetSeed: this.activeOrderMode === 'random' }).then(() => {
					uni.showToast({ title: `已切换${this.activeOrderLabel}`, icon: 'none' })
				})
			},
			stepTo(nextIndex) {
				const safe = Math.max(0, Math.min(this.playList.length - 1, nextIndex))
				if (safe === this.currentIndex) return
				this.currentIndex = safe
				this.showControls = false
				this.playCurrent()
				if (this.hasMore && this.currentIndex >= this.playList.length - 1 - this.prefetchTriggerOffset) {
					this.loadVideos()
				}
			},
			applyFeedSettings() {
				const s = this.cacheSettings || {}
				this.swipeDuration = Math.max(120, parseInt(s.feedSwipeDuration || 220, 10))
				this.preloadNeighbors = Math.max(0, Math.min(2, parseInt(s.feedPreloadNeighbors || 1, 10)))
				this.prefetchCount = Math.max(1, Math.min(12, parseInt(s.feedPrefetchCount || 6, 10)))
				this.prefetchTriggerOffset = Math.max(1, Math.min(12, parseInt(s.feedPrefetchCount || 6, 10)))
				this.playDelayMs = Math.max(0, Math.min(300, parseInt(s.feedPlayDelayMs || 40, 10)))
				this.playbackSourceMode = ['prefer_mp4', 'prefer_hls', 'mp4_only', 'hls_only'].includes(s.playbackSourceMode) ? s.playbackSourceMode : 'prefer_mp4'
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
			buildFeedQuery(pageNo) {
				const query = {
					pageNo,
					pageSize: this.requestPageSize
				}
				if (this.activeOrderMode === 'random') {
					query.randomMode = '1'
					query.randomSeed = this.sessionRandomSeed
				} else {
					query.sortField = 'createtime'
					query.sortOrder = this.activeOrderMode === 'asc' ? 'asc' : 'desc'
				}
				if (this.selectedAuthor && this.selectedAuthor.trim()) {
					query.videoauthor = this.selectedAuthor.trim()
				}
				return query
			},
			resolvePlayableSource(video) {
				if (!video) return ''
				const playurl = video.playurl || ''
				const mp4 = video.videounrealaddr || ''
				const isHls = /\.m3u8(\?|$)/i.test(playurl)
				if (this.playbackSourceMode === 'mp4_only') {
					return mp4 || playurl || ''
				}
				if (this.playbackSourceMode === 'hls_only') {
					return (isHls ? playurl : '') || playurl || mp4 || ''
				}
				if (this.playbackSourceMode === 'prefer_hls') {
					return (isHls ? playurl : '') || mp4 || playurl || ''
				}
				return mp4 || playurl || ''
			},
			resolveFallbackSource(video) {
				if (!video) return ''
				const playurl = video.playurl || ''
				const mp4 = video.videounrealaddr || ''
				const preferred = this.resolvePlayableSource(video)
				if (preferred === mp4) return playurl || ''
				if (preferred === playurl) return mp4 || ''
				return ''
			},
			preparePlaybackWindow(centerIndex) {
				if (!Array.isArray(this.playList) || this.playList.length === 0) return
				const start = Math.max(0, centerIndex - this.preloadNeighbors)
				const end = Math.min(this.playList.length - 1, centerIndex + this.preloadNeighbors + 1)
				for (let i = start; i <= end; i++) {
					const item = this.playList[i]
					if (!item) continue
					const resolved = this.resolvePlayableSource(item)
					if (resolved && item.playSrc !== resolved) {
						this.$set(item, 'playSrc', resolved)
					}
				}
			},
			resetAndLoadFeed(options = {}) {
				if (this.isResettingFeed) {
					return Promise.resolve(false)
				}
				this.isResettingFeed = true
				this.pauseAll()
				this.playRequestToken++
				this.baseList = []
				this.playList = []
				this.pageNo = 0
				this.hasMore = true
				this.currentIndex = 0
				this.activePlayingIndex = -1
				this.videoContexts = {}
				this.currentSec = 0
				this.durationSec = 0
				this.bufferSec = 0
				this.showControls = false
				this.manualPaused = false
				this.switchPending = {}
				if (options.resetSeed || this.activeOrderMode === 'random') {
					this.sessionRandomSeed = this.createRandomSeed()
				}
				return new Promise((resolve) => {
					this.loadVideos(() => {
						this.isResettingFeed = false
						this.preparePlaybackWindow(0)
						if (this.playList.length > 0) {
							this.$nextTick(() => this.playCurrent())
						}
						resolve(true)
					})
				})
			},
			loadVideos(onDone) {
				if (this.isLoading || !this.hasMore) {
					if (typeof onDone === 'function') onDone()
					return
				}
				if (!this.serveraddr || !this.serverport || !this.servertoken) {
					uni.showToast({ title: '请先配置服务器', icon: 'none' })
					if (typeof onDone === 'function') onDone()
					return
				}
				this.isLoading = true
				uni.request({
					url: `${this.serveraddr}:${this.serverport}/api/findVideos?token=${this.servertoken}`,
					method: 'POST',
					header: { 'content-type': 'application/x-www-form-urlencoded' },
					data: this.buildFeedQuery(this.pageNo),
						success: (res) => {
						if (res.data && res.data.resCode === '000001' && res.data.record && res.data.record.content) {
							const list = res.data.record.content || []
							list.forEach(v => {
								v.playurl = this.normalizePath(v.playurl)
								v.videounrealaddr = this.normalizePath(v.videounrealaddr)
								v.videocover = this.normalizePath(v.videocover)
								v.playSrc = this.resolvePlayableSource(v)
							})
							this.baseList = this.baseList.concat(list)
							this.playList = this.baseList.slice()
							this.pageNo++
							this.hasMore = !res.data.record.last
							this.refreshAuthorOptions()
							this.preparePlaybackWindow(this.currentIndex)
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
						complete: () => {
							this.isLoading = false
							if (typeof onDone === 'function') onDone()
						}
				})
			},
			refreshAuthorOptions() {
				const s = new Set()
				this.baseList.forEach(v => {
					if (v.videoauthor && v.videoauthor.trim()) s.add(v.videoauthor.trim())
				})
				this.authorOptions = Array.from(s).sort()
			},
			onSwiperChange(e) {
				const next = Number(e && e.detail && e.detail.current)
				if (Number.isNaN(next)) return
				if (next === this.currentIndex) return
				this.showControls = false
				this.currentIndex = next
				if (this.pendingOrderMode && this.pendingOrderMode !== this.activeOrderMode) {
					this.applyPendingOrder()
					return
				}
				this.playCurrent()
				if (this.hasMore && this.currentIndex >= this.playList.length - 1 - this.prefetchTriggerOffset) {
					this.loadVideos()
				}
			},
			playCurrent() {
				const requestToken = ++this.playRequestToken
				this.pausePrevious()
				const idx = this.currentIndex
				if (idx < 0 || idx >= this.playList.length) return
				this.preparePlaybackWindow(idx)
				this.activePlayingIndex = idx
				this.isPlaying = false
				this.manualPaused = false
				this.currentSec = 0
				this.durationSec = 0
				this.bufferSec = 0
				this.switchPending[idx] = Date.now()
				const current = this.playList[idx]
				if (current) {
					this.$delete(current, '_fallbackTried')
					const playable = cacheManager.getPlayableUrl(current)
					if (playable && playable !== current.playSrc) {
						this.$set(current, 'playSrc', playable)
					} else {
						this.$set(current, 'playSrc', this.resolvePlayableSource(current))
					}
				}
				if (!this.videoContexts[idx]) {
					this.videoContexts[idx] = uni.createVideoContext(`video-${idx}`, this)
				}
				if (this.videoContexts[idx]) {
					setTimeout(() => {
						if (requestToken !== this.playRequestToken || idx !== this.currentIndex) return
						this.preparePlaybackWindow(idx)
						this.videoContexts[idx] && this.videoContexts[idx].play()
					}, this.playDelayMs)
				}
				this.prefetchAround(idx)
				this.showControls = false
			},
			getVideoSrc(index, video) {
				if (!video) return ''
				if (Math.abs(index - this.currentIndex) <= this.preloadNeighbors) {
					return video.playSrc || ''
				}
				return ''
			},
			prefetchAround(idx) {
				const prefetchCount = Math.max(1, this.prefetchCount)
				const queue = []
				if (idx + 1 < this.playList.length) {
					queue.push(this.playList[idx + 1])
				}
				if (idx - 1 >= 0) {
					queue.push(this.playList[idx - 1])
				}
				for (let i = idx + 2; i < Math.min(this.playList.length, idx + 1 + prefetchCount); i++) {
					queue.push(this.playList[i])
				}
				cacheManager.prefetchVideos(queue)
			},
			pauseAll() {
				this.playRequestToken++
				Object.keys(this.videoContexts).forEach(k => {
					const c = this.videoContexts[k]
					if (c) c.pause()
				})
				this.isPlaying = false
			},
			pausePrevious() {
				const prev = this.activePlayingIndex
				if (prev < 0 || prev === this.currentIndex) return
				const c = this.videoContexts[prev]
				if (c) c.pause()
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
				this.manualPaused = false
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
			onVideoError(index) {
				const item = this.playList[index]
				if (!item) return
				if (item._fallbackTried) {
					if (index === this.currentIndex) {
						uni.showToast({ title: '播放失败，请切换源策略', icon: 'none' })
					}
					return
				}
				const fallback = this.resolveFallbackSource(item)
				if (!fallback || fallback === item.playSrc) {
					if (index === this.currentIndex) {
						uni.showToast({ title: '播放失败，请切换源策略', icon: 'none' })
					}
					return
				}
				this.$set(item, '_fallbackTried', true)
				this.$set(item, 'playSrc', fallback)
				if (index === this.currentIndex) {
					this.$nextTick(() => this.playCurrent())
				}
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
				else if (this.hasMore) this.loadVideos(() => {
					if (this.currentIndex < this.playList.length - 1) this.stepByDirection(1)
				})
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
				this.resetAndLoadFeed()
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
