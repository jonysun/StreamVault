const CACHE_INDEX_KEY = 'SV_VIDEO_CACHE_INDEX'
const CACHE_SETTINGS_KEY = 'SV_CACHE_SETTINGS'

const DEFAULT_SETTINGS = {
	maxCount: 10,
	maxSizeMB: 1024,
	wifiOnly: true,
	allowPrivacy: true,
	enabled: true,
	feedSwipeDuration: 220,
	feedPreloadNeighbors: 1,
	feedPlayDelayMs: 40,
	appTheme: 'light',
	playbackMode: 'autonext'
}

function readIndex() {
	return uni.getStorageSync(CACHE_INDEX_KEY) || {}
}

function writeIndex(index) {
	uni.setStorageSync(CACHE_INDEX_KEY, index || {})
}

function readSettings() {
	const s = uni.getStorageSync(CACHE_SETTINGS_KEY) || {}
	return Object.assign({}, DEFAULT_SETTINGS, s)
}

function writeSettings(settings) {
	uni.setStorageSync(CACHE_SETTINGS_KEY, Object.assign({}, DEFAULT_SETTINGS, settings || {}))
}

function now() {
	return Date.now()
}

function toBytes(mb) {
	return Math.max(1, parseInt(mb || 0, 10)) * 1024 * 1024
}

function canCacheByPrivacy(video, settings) {
	if (!video) return false
	if (settings.allowPrivacy) return true
	return String(video.videoprivacy || '') !== '1'
}

function checkNetworkAllowed(settings) {
	return new Promise((resolve) => {
		if (!settings.wifiOnly) {
			resolve(true)
			return
		}
		uni.getNetworkType({
			success: (res) => resolve(res.networkType === 'wifi'),
			fail: () => resolve(false)
		})
	})
}

function getCacheKey(video) {
	if (!video) return ''
	if (video.id != null) return String(video.id)
	if (video.videoid) return String(video.videoid)
	return String(video.playurl || video.videounrealaddr || '')
}

function getTotalSize(index) {
	let t = 0
	Object.keys(index).forEach((k) => {
		const item = index[k]
		if (item && item.size) t += Number(item.size)
	})
	return t
}

function evictIfNeeded() {
	const settings = readSettings()
	let index = readIndex()
	const keys = Object.keys(index)
	if (keys.length === 0) return
	const maxCount = Math.max(1, parseInt(settings.maxCount || 10, 10))
	const maxSize = toBytes(settings.maxSizeMB || 1024)
	let total = getTotalSize(index)
	if (keys.length <= maxCount && total <= maxSize) return

	const list = keys.map((k) => ({ key: k, ...index[k] }))
	list.sort((a, b) => (a.lastAccess || 0) - (b.lastAccess || 0))

	for (let i = 0; i < list.length; i++) {
		const item = list[i]
		if (Object.keys(index).length <= maxCount && total <= maxSize) break
		if (item.path) {
			uni.removeSavedFile({ filePath: item.path })
		}
		total -= Number(item.size || 0)
		delete index[item.key]
	}
	writeIndex(index)
}

function getPlayableUrl(video) {
	const key = getCacheKey(video)
	if (!key) return video.playurl || video.videounrealaddr || ''
	const index = readIndex()
	const item = index[key]
	if (!item || !item.path) return video.playurl || video.videounrealaddr || ''
	item.lastAccess = now()
	index[key] = item
	writeIndex(index)
	return item.path
}

function prefetchOne(video) {
	return new Promise(async (resolve) => {
		const settings = readSettings()
		if (!settings.enabled) {
			resolve(false)
			return
		}
		if (!canCacheByPrivacy(video, settings)) {
			resolve(false)
			return
		}
		const allowed = await checkNetworkAllowed(settings)
		if (!allowed) {
			resolve(false)
			return
		}
		const key = getCacheKey(video)
		if (!key) {
			resolve(false)
			return
		}
		let index = readIndex()
		if (index[key] && index[key].path) {
			index[key].lastAccess = now()
			writeIndex(index)
			resolve(true)
			return
		}

		const remote = video.playurl || video.videounrealaddr
		if (!remote) {
			resolve(false)
			return
		}

		uni.downloadFile({
			url: remote,
			success: (res) => {
				if (res.statusCode !== 200 || !res.tempFilePath) {
					resolve(false)
					return
				}
				uni.saveFile({
					tempFilePath: res.tempFilePath,
					success: (saved) => {
						index = readIndex()
						index[key] = {
							path: saved.savedFilePath,
							size: Number(res.totalBytesWritten || 0),
							lastAccess: now(),
							createdAt: now()
						}
						writeIndex(index)
						evictIfNeeded()
						resolve(true)
					},
					fail: () => resolve(false)
				})
			},
			fail: () => resolve(false)
		})
	})
}

async function prefetchVideos(videos) {
	if (!videos || videos.length === 0) return
	const tasks = videos.slice(0, 6)
	// 下一条优先
	// eslint-disable-next-line no-await-in-loop
	await prefetchOne(tasks[0])
	const rest = tasks.slice(1)
	const concurrency = 2
	for (let i = 0; i < rest.length; i += concurrency) {
		const chunk = rest.slice(i, i + concurrency)
		// eslint-disable-next-line no-await-in-loop
		await Promise.all(chunk.map(v => prefetchOne(v)))
	}
}

function clearAll() {
	const index = readIndex()
	Object.keys(index).forEach((k) => {
		const item = index[k]
		if (item && item.path) {
			uni.removeSavedFile({ filePath: item.path })
		}
	})
	writeIndex({})
}

function getStats() {
	const index = readIndex()
	return {
		count: Object.keys(index).length,
		sizeBytes: getTotalSize(index)
	}
}

export default {
	readSettings,
	writeSettings,
	getPlayableUrl,
	prefetchOne,
	prefetchVideos,
	clearAll,
	getStats,
	evictIfNeeded,
	DEFAULT_SETTINGS
}
