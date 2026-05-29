<template>
	<view class="container">
		<view class="header">
			<text class="title">收藏任务</text>
			<button class="add-btn" @tap="openAddTask">添加任务</button>
		</view>

		<view class="fav-list">
			<view class="fav-card" v-for="item in list" :key="item.id">
				<view class="fav-info">
					<view class="fav-row">
						<view class="fav-platform">{{item.platform || '未知'}}</view>
						<text class="fav-taskname">{{item.taskname}}</text>
					</view>
					<view class="fav-meta">
						<view class="meta-row"><text class="meta-label">状态</text><text class="meta-value">{{item.taskstatus}}</text></view>
						<view class="meta-row"><text class="meta-label">创建</text><text class="meta-value">{{formatDate(item.createtime)}}</text></view>
						<view class="meta-row"><text class="meta-label">数量</text><text class="meta-value">{{item.count}} / 已执行 {{item.carriedout}}</text></view>
					</view>
				</view>
				<view class="fav-actions">
					<button class="action-btn exec" @tap="execTaskById(item)">执行</button>
					<button class="action-btn delete" @tap="deleteFav(item)">删除</button>
				</view>
			</view>
			<view class="empty-state" v-if="list.length === 0">
				<text class="empty-text">暂无收藏任务</text>
			</view>
		</view>

		<uni-popup ref="taskPopup" type="center" background-color="#fff">
			<view class="popup-wrap">
				<view class="popup-title">添加收藏任务</view>
				<view class="form-row">
					<text class="form-label">任务名称</text>
					<input class="form-input" v-model="taskForm.taskname" placeholder="例如：某作者作品" />
				</view>
				<view class="form-row">
					<text class="form-label">来源平台</text>
					<picker mode="selector" :range="platformOptions" :value="platformIndex" @change="onPlatformChange">
						<view class="picker-text">{{ taskForm.platform }}</view>
					</picker>
				</view>
				<view class="form-row" v-if="taskForm.platform==='抖音'">
					<text class="form-label">抖音类型</text>
					<picker mode="selector" :range="douyinTypeLabels" :value="douyinTypeIndex" @change="onDouyinTypeChange">
						<view class="picker-text">{{ douyinTypeLabels[douyinTypeIndex] }}</view>
					</picker>
				</view>
				<view class="form-row" v-if="taskForm.platform==='哔哩'">
					<text class="form-label">哔哩类型</text>
					<picker mode="selector" :range="biliTypeLabels" :value="biliTypeIndex" @change="onBiliTypeChange">
						<view class="picker-text">{{ biliTypeLabels[biliTypeIndex] }}</view>
					</picker>
				</view>

				<view class="form-row" v-if="taskForm.platform==='抖音'">
					<text class="form-label">抖音用户链接</text>
					<textarea class="form-textarea" v-model="douyinShareText" placeholder="粘贴抖音分享文案或短链" @input="handleDouyinShareInput"></textarea>
					<button class="parse-btn" @tap="resolveDouyinLink">解析抖音用户链接</button>
				</view>

				<view class="form-row">
					<text class="form-label">用户ID / 收藏ID</text>
					<input class="form-input" v-model="taskForm.originaladdress" :placeholder="addressPlaceholder" />
				</view>
				<view class="form-row" v-if="taskForm.platform==='哔哩' && taskForm.biliType==='seaarc'">
					<text class="form-label">合集ID</text>
					<input class="form-input" v-model="taskForm.seaarcid" placeholder="请输入合集ID" />
				</view>

				<view class="form-row">
					<text class="form-label">首次最大获取数</text>
					<input class="form-input" type="number" v-model="taskForm.omaxcur" placeholder="默认80/300" />
				</view>
				<view class="form-row">
					<text class="form-label">监控最大获取数</text>
					<input class="form-input" type="number" v-model="taskForm.maxcur" placeholder="默认80/300" />
				</view>
				<view class="form-row">
					<text class="form-label">是否监控</text>
					<picker mode="selector" :range="monitoringLabels" :value="monitoringIndex" @change="onMonitoringChange">
						<view class="picker-text">{{ monitoringLabels[monitoringIndex] }}</view>
					</picker>
				</view>
				<view class="form-row" v-if="taskForm.monitoring==='Y'">
					<text class="form-label">独立Cron</text>
					<input class="form-input" v-model="taskForm.taskcron" placeholder="例如 0 0 */6 * * ?" />
				</view>

				<view class="popup-actions">
					<button class="btn cancel" @tap="closeAddTask">取消</button>
					<button class="btn submit" @tap="submitTask">保存</button>
				</view>
			</view>
		</uni-popup>
	</view>
</template>

<script>
export default {
	data() {
		return {
			list: [],
			pageNo: 1,
			loading: false,
			finished: false,
			platformOptions: ['抖音', '哔哩'],
			platformIndex: 0,
			douyinTypeLabels: ['作品', '喜欢', '收藏夹(仅自己)'],
			douyinTypeValues: ['post', 'like', 'collect'],
			douyinTypeIndex: 0,
			biliTypeLabels: ['投稿', '合集', '收藏夹'],
			biliTypeValues: ['arc', 'seaarc', 'fav'],
			biliTypeIndex: 0,
			monitoringLabels: ['否', '是'],
			monitoringIndex: 1,
			douyinShareText: '',
			taskForm: {
				platform: '抖音',
				taskname: '',
				originaladdress: '',
				monitoring: 'Y',
				taskcron: '',
				omaxcur: '20',
				maxcur: '20',
				douyinType: 'post',
				biliType: 'arc',
				seaarcid: ''
			}
		}
	},
	computed: {
		addressPlaceholder() {
			if (this.taskForm.platform === '抖音') return '请输入抖音用户ID(sec_user_id)'
			if (this.taskForm.biliType === 'fav') return '请输入哔哩收藏夹ID'
			if (this.taskForm.biliType === 'arc') return '请输入UP主ID'
			return '请输入创作者ID'
		}
	},
	onLoad() { this.getList(1) },
	methods: {
		formatDate(timeStr) {
			if (!timeStr) return ''
			return String(timeStr)
		},
		serverInfo() {
			return {
				addr: uni.getStorageSync('serveraddr'),
				port: uni.getStorageSync('serverport'),
				cookie: uni.getStorageSync('adminCookie')
			}
		},
		getList(page) {
			if (this.loading) return
			this.loading = true
			const s = this.serverInfo()
			uni.request({
				url: `${s.addr}:${s.port}/admin/api/findCollectDataList`,
				method: 'POST',
				header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': s.cookie },
				data: { pageNo: page },
				success: (res) => {
					if (res.data && res.data.resCode === '000001') {
						const content = (res.data.record && res.data.record.content) || []
						this.list = page === 1 ? content : this.list.concat(content)
						this.pageNo = page + 1
						this.finished = !!(res.data.record && res.data.record.last)
					}
				},
				complete: () => { this.loading = false }
			})
		},
		openAddTask() {
			this.resetTaskForm()
			this.$refs.taskPopup.open()
		},
		handleDouyinShareInput(e) {
			const text = (e && e.detail && e.detail.value) || this.douyinShareText || ''
			if (text.indexOf('打开抖音搜索，查看TA的更多作品。') !== -1) {
				this.taskForm.platform = '抖音'
				this.platformIndex = 0
				this.taskForm.douyinType = 'post'
				this.douyinTypeIndex = 0
				this.douyinShareText = text
			}
		},
		closeAddTask() {
			this.$refs.taskPopup.close()
		},
		resetTaskForm() {
			this.platformIndex = 0
			this.douyinTypeIndex = 0
			this.biliTypeIndex = 0
			this.monitoringIndex = 1
			this.douyinShareText = ''
			this.taskForm = {
				platform: '抖音',
				taskname: '抖音作品任务',
				originaladdress: '',
				monitoring: 'Y',
				taskcron: '',
				omaxcur: '20',
				maxcur: '20',
				douyinType: 'post',
				biliType: 'arc',
				seaarcid: ''
			}
		},
		onPlatformChange(e) {
			this.platformIndex = Number(e.detail.value || 0)
			this.taskForm.platform = this.platformOptions[this.platformIndex]
			if (this.taskForm.platform === '抖音') {
				this.taskForm.omaxcur = this.taskForm.omaxcur || '80'
				this.taskForm.maxcur = this.taskForm.maxcur || '80'
			} else {
				this.taskForm.omaxcur = this.taskForm.omaxcur || '300'
				this.taskForm.maxcur = this.taskForm.maxcur || '300'
			}
		},
		onDouyinTypeChange(e) {
			this.douyinTypeIndex = Number(e.detail.value || 0)
			this.taskForm.douyinType = this.douyinTypeValues[this.douyinTypeIndex]
		},
		onBiliTypeChange(e) {
			this.biliTypeIndex = Number(e.detail.value || 0)
			this.taskForm.biliType = this.biliTypeValues[this.biliTypeIndex]
		},
		onMonitoringChange(e) {
			this.monitoringIndex = Number(e.detail.value || 0)
			this.taskForm.monitoring = this.monitoringIndex === 1 ? 'Y' : 'N'
		},
		resolveDouyinLink() {
			if (!this.douyinShareText.trim()) {
				uni.showToast({ title: '请先粘贴抖音分享文本', icon: 'none' })
				return
			}
			const s = this.serverInfo()
			uni.showLoading({ title: '解析中...' })
			uni.request({
				url: `${s.addr}:${s.port}/admin/api/resolveDouyinUserLink`,
				method: 'POST',
				header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': s.cookie },
				data: { text: this.douyinShareText },
				success: (res) => {
					if (res.data && res.data.resCode === '000001' && res.data.record) {
						this.taskForm.platform = '抖音'
						this.platformIndex = 0
						this.taskForm.douyinType = 'post'
						this.douyinTypeIndex = 0
						this.taskForm.originaladdress = res.data.record.secUserId || ''
						const nickname = ((res.data.record.nickname || res.data.record.authorName || this.extractAuthorNameFromShareText(this.douyinShareText) || '') + '').trim()
						if (nickname) {
							this.taskForm.taskname = `${nickname}的作品`
						} else if (!this.taskForm.taskname || this.taskForm.taskname === '抖音作品任务') {
							this.taskForm.taskname = '抖音作者的作品'
						}
						uni.showToast({ title: '解析成功', icon: 'success' })
					} else {
						uni.showToast({ title: (res.data && res.data.message) || '解析失败', icon: 'none' })
					}
				},
				complete: () => uni.hideLoading()
			})
		},
		extractAuthorNameFromShareText(text) {
			const raw = (text || '').toString()
			if (!raw) return ''
			const patterns = [
				/来自\s*([^\s，。！？!?:：]+)\s*的作品/,
				/抖音号\s*[:：]?\s*([^\s，。！？!?:：]+)/,
				/@([^\s，。！？!?:：]+)/
			]
			for (let i = 0; i < patterns.length; i++) {
				const m = raw.match(patterns[i])
				if (m && m[1]) {
					return m[1].trim()
				}
			}
			return ''
		},
		buildOriginalAddress() {
			const base = (this.taskForm.originaladdress || '').trim()
			if (!base) return ''
			if (this.taskForm.platform === '抖音') {
				if (this.taskForm.douyinType === 'post') return 'post' + base
				if (this.taskForm.douyinType === 'like') return 'like' + base
				return 'fav-' + base + '-fav'
			}
			if (this.taskForm.biliType === 'fav') return 'bili-fav-' + base
			if (this.taskForm.biliType === 'arc') return 'bili-arc-' + base
			return 'bili-seaarc-' + base + '#' + (this.taskForm.seaarcid || '').trim()
		},
		submitTask() {
			const originaladdress = this.buildOriginalAddress()
			if (!this.taskForm.taskname.trim() || !originaladdress) {
				uni.showToast({ title: '请完善任务信息', icon: 'none' })
				return
			}
			const s = this.serverInfo()
			const option = {
				platform: this.taskForm.platform,
				taskname: this.taskForm.taskname.trim(),
				originaladdress,
				monitoring: this.taskForm.monitoring,
				taskcron: this.taskForm.taskcron || '',
				omaxcur: Number(this.taskForm.omaxcur || 0) || undefined,
				maxcur: Number(this.taskForm.maxcur || 0) || undefined
			}
			uni.showLoading({ title: '保存中...' })
			uni.request({
				url: `${s.addr}:${s.port}/admin/api/submitCollectData`,
				method: 'POST',
				header: { 'content-type': 'application/x-www-form-urlencoded', 'Cookie': s.cookie },
				data: option,
				success: (res) => {
					if (res.data && res.data.resCode === '000001') {
						uni.showToast({ title: '任务已保存', icon: 'success' })
						this.closeAddTask()
						this.pageNo = 1
						this.list = []
						this.finished = false
						this.getList(1)
					} else {
						uni.showToast({ title: (res.data && res.data.message) || '保存失败', icon: 'none' })
					}
				},
				complete: () => uni.hideLoading()
			})
		},
		execTaskById(item) {
			const s = this.serverInfo()
			uni.request({
				url: `${s.addr}:${s.port}/admin/api/execCollectData?id=${item.id}`,
				method: 'GET',
				header: { 'Cookie': s.cookie },
				success: (res) => {
					uni.showToast({ title: (res.data && res.data.message) || '已触发', icon: 'none' })
				}
			})
		},
		deleteFav(item) {
			const s = this.serverInfo()
			uni.showModal({
				title: '确认删除',
				content: '确定删除该任务吗？',
				success: (r) => {
					if (!r.confirm) return
					uni.request({
						url: `${s.addr}:${s.port}/admin/api/deleteCollectData?id=${item.id}`,
						method: 'GET',
						header: { 'Cookie': s.cookie },
						success: () => {
							this.pageNo = 1
							this.list = []
							this.finished = false
							this.getList(1)
						}
					})
				}
			})
		}
	}
}
</script>

<style>
.container { min-height: 100vh; background: #f6f7f8; padding: 24rpx; }
.header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.title { font-size: 36rpx; font-weight: 700; color: #1a1a1a; }
.add-btn { height: 64rpx; line-height: 64rpx; padding: 0 22rpx; border-radius: 14rpx; background: #2563eb; color: #fff; font-size: 24rpx; }
.fav-list { display: flex; flex-direction: column; gap: 16rpx; }
.fav-card { background: #fff; border-radius: 20rpx; padding: 24rpx; display: flex; justify-content: space-between; gap: 16rpx; }
.fav-info { flex: 1; min-width: 0; }
.fav-row { display: flex; align-items: center; gap: 12rpx; margin-bottom: 12rpx; }
.fav-platform { padding: 4rpx 12rpx; border-radius: 8rpx; background: #2563eb; color: #fff; font-size: 20rpx; }
.fav-taskname { font-size: 26rpx; color: #333; font-weight: 600; }
.fav-meta { display: flex; flex-direction: column; gap: 6rpx; }
.meta-row { display: flex; gap: 8rpx; }
.meta-label { color: #9ca3af; font-size: 22rpx; width: 58rpx; }
.meta-value { color: #4b5563; font-size: 22rpx; }
.fav-actions { display: flex; flex-direction: column; gap: 10rpx; }
.action-btn { min-width: 96rpx; height: 52rpx; line-height: 52rpx; border-radius: 10rpx; font-size: 22rpx; color: #fff; border: none; }
.action-btn.exec { background: #16a34a; }
.action-btn.delete { background: #ef4444; }
.empty-state { padding: 120rpx 0; text-align: center; }
.empty-text { color: #9ca3af; font-size: 28rpx; }

.popup-wrap { width: 680rpx; max-height: 84vh; background: #fff; border-radius: 16rpx; padding: 22rpx; overflow-y: auto; }
.popup-title { font-size: 30rpx; font-weight: 700; color: #111827; margin-bottom: 16rpx; }
.form-row { margin-bottom: 14rpx; }
.form-label { font-size: 24rpx; color: #4b5563; display: block; margin-bottom: 8rpx; }
.form-input { border: 1px solid #e5e7eb; border-radius: 10rpx; height: 68rpx; padding: 0 16rpx; font-size: 24rpx; }
.form-textarea { width: 100%; height: 130rpx; border: 1px solid #e5e7eb; border-radius: 10rpx; padding: 12rpx 16rpx; font-size: 24rpx; }
.picker-text { border: 1px solid #e5e7eb; border-radius: 10rpx; min-height: 68rpx; padding: 16rpx; font-size: 24rpx; color: #111827; }
.parse-btn { margin-top: 10rpx; height: 58rpx; line-height: 58rpx; border-radius: 10rpx; font-size: 22rpx; background: #0ea5e9; color: #fff; border: none; }
.popup-actions { display: flex; gap: 12rpx; margin-top: 16rpx; }
.btn { flex: 1; height: 70rpx; line-height: 70rpx; border-radius: 10rpx; border: none; font-size: 26rpx; }
.btn.cancel { background: #e5e7eb; color: #374151; }
.btn.submit { background: #2563eb; color: #fff; }
</style>
