<template>
	<view class="video-container">
		<view class="top-controls">
			<view class="ctrl" @tap="toggleOrder">{{ orderText }}</view>
			<view class="ctrl" @tap="openAuthorPopup">{{ authorText }}</view>
			<view class="ctrl" @tap="toggleMuted">{{ isMuted ? '静音' : '有声' }}</view>
		</view>

		<swiper class="video-swiper" :vertical="true" :current="currentIndex" @change="onSwiperChange" :duration="280">
			<swiper-item v-for="(video, index) in playList" :key="video.id || index">
				<view class="video-wrapper">
					<video
						:id="`video-${index}`"
						:src="video.playurl || video.videounrealaddr"
						:poster="video.videocover"
						:controls="false"
						:autoplay="false"
						:muted="isMuted"
						:show-center-play-btn="false"
						:show-play-btn="false"
						:enable-progress-gesture="false"
						:object-fit="'cover'"
						class="video-player"
						@play="onVideoPlay(index)"
						@pause="onVideoPause(index)"
						@ended="onVideoEnded(index)"
					></video>
					<view class="video-overlay">
						<view class="bottom-info">
							<text class="author-name" @tap="selectAuthor(video.videoauthor)">@{{ video.videoauthor || '未知作者' }}</text>
							<text class="desc-text">{{ video.videoname || video.videodesc || '' }}</text>
						</view>
					</view>
				</view>
			</swiper-item>
		</swiper>

		<uni-popup ref="authorPopup" type="top" background-color="#fff">
			<view class="author-sheet">
				<view class="sheet-title">选择作者</view>
				<view class="author-list">
					<view class="author-chip" @tap="selectAuthor('')">全部作者</view>
					<view class="author-chip" v-for="a in authorOptions" :key="a" @tap="selectAuthor(a)">{{ a }}</view>
				</view>
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
				orderMode: 'desc',
				selectedAuthor: '',
				isMuted: true,
				currentIndex: 0,
				pageNo: 1,
				isLoading: false,
				hasMore: true,
				serveraddr: '',
				serverport: '',
				servertoken: '',
				videoContexts: {},
				cacheSettings: cacheManager.readSettings()
			}
		},
		computed: {
			orderText() {
				if (this.orderMode === 'asc') return '顺序'
				if (this.orderMode === 'random') return '随机'
				return '倒序'
			},
			authorText() {
				return this.selectedAuthor && this.selectedAuthor.trim() ? this.selectedAuthor : '全部'
			}
		},
		onLoad() {
			this.ensureServerConfig()
			this.cacheSettings = cacheManager.readSettings()
			this.loadVideos()
		},
		onShow() {
			if (!this.serveraddr || !this.serverport || !this.servertoken) {
				this.ensureServerConfig()
			}
			if (this.playList.length === 0) {
				this.loadVideos()
			}
		},
		methods: {
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
								v.videounrealaddr = this.normalizePath(v.playurl || v.videounrealaddr)
								v.videocover = this.normalizePath(v.videocover)
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
			rebuildPlayList(keepCurrent) {
				let list = this.baseList.slice()
				if (this.selectedAuthor && this.selectedAuthor.trim()) {
					list = list.filter(v => (v.videoauthor || '').indexOf(this.selectedAuthor.trim()) >= 0)
				}
				if (this.orderMode === 'asc') list.reverse()
				if (this.orderMode === 'random') this.shuffle(list)
				this.playList = list
				if (!keepCurrent) this.currentIndex = 0
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
				this.currentIndex = e.detail.current
				this.playCurrent()
				if (this.currentIndex >= this.playList.length - 2) {
					if (this.hasMore) {
						this.loadVideos()
					} else if (this.orderMode === 'random' && this.playList.length > 1) {
						this.rebuildPlayList(false)
					}
				}
			},
			playCurrent() {
				this.pauseAll()
				const idx = this.currentIndex
				if (idx < 0 || idx >= this.playList.length) return
				const current = this.playList[idx]
				if (current) {
					const playable = cacheManager.getPlayableUrl(current)
					if (playable && playable !== current.videounrealaddr) {
						this.$set(current, 'videounrealaddr', playable)
					}
				}
				if (!this.videoContexts[idx]) {
					this.videoContexts[idx] = uni.createVideoContext(`video-${idx}`, this)
				}
				if (this.videoContexts[idx]) this.videoContexts[idx].play()
				this.prefetchAround(idx)
			},
			prefetchAround(idx) {
				const next = []
				for (let i = idx + 1; i < Math.min(this.playList.length, idx + 11); i++) {
					next.push(this.playList[i])
				}
				cacheManager.prefetchVideos(next)
			},
			pauseAll() {
				Object.keys(this.videoContexts).forEach(k => {
					const c = this.videoContexts[k]
					if (c) c.pause()
				})
			},
			onVideoPlay() {},
			onVideoPause() {},
			onVideoEnded(index) {
				if (index < this.playList.length - 1) {
					this.currentIndex = index + 1
					this.playCurrent()
				}
			},
			toggleOrder() {
				if (this.orderMode === 'desc') this.orderMode = 'asc'
				else if (this.orderMode === 'asc') this.orderMode = 'random'
				else this.orderMode = 'desc'
				this.rebuildPlayList(false)
			},
			toggleMuted() {
				this.isMuted = !this.isMuted
			},
			openAuthorPopup() {
				this.$refs.authorPopup.open()
			},
			selectAuthor(author) {
				this.selectedAuthor = author || ''
				this.$refs.authorPopup.close()
				this.rebuildPlayList(false)
			}
		}
	}
</script>

<style>
	.video-container { width: 100%; height: 100vh; background: #000; position: relative; }
	.video-swiper { width: 100%; height: 100%; }
	.video-wrapper { width: 100%; height: 100%; position: relative; }
	.video-player { width: 100%; height: 100%; background: #000; }
	.video-overlay { position: absolute; left: 0; right: 0; bottom: 0; padding: 24rpx; background: linear-gradient(to top, rgba(0,0,0,.55), transparent); }
	.bottom-info { color: #fff; }
	.author-name { font-size: 28rpx; font-weight: 600; display: block; margin-bottom: 10rpx; }
	.desc-text { font-size: 24rpx; }
	.top-controls { position: fixed; z-index: 99; top: 20rpx; right: 20rpx; display: flex; flex-direction: column; gap: 12rpx; }
	.ctrl { min-width: 84rpx; height: 56rpx; line-height: 56rpx; border-radius: 28rpx; text-align: center; background: rgba(0,0,0,.45); color: #fff; font-size: 22rpx; padding: 0 14rpx; }
	.author-sheet { padding: 24rpx; max-height: 60vh; overflow-y: auto; }
	.sheet-title { font-size: 28rpx; font-weight: 700; margin-bottom: 16rpx; }
	.author-list { display: flex; flex-wrap: wrap; }
	.author-chip { margin: 0 12rpx 12rpx 0; padding: 10rpx 18rpx; border-radius: 26rpx; background: #f3f4f6; color: #374151; font-size: 24rpx; }
</style>
