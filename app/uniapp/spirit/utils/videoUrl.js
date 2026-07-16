export function normalizeVideoPath(rawPath, serveraddr, serverport, token) {
	if (!rawPath) return ''
	if (/^https?:\/\//i.test(rawPath)) return rawPath
	let p = String(rawPath).replace(/\\/g, '/')
	if (!p.startsWith('/')) p = '/' + p
	p = p.replace(/\/+/g, '/')
	const encodedPath = p.split('/').map(segment => encodeURIComponent(segment)).join('/')
	return `${serveraddr}:${serverport}${encodedPath}?apptoken=${token}`
}

export function resolvePlayableSource(video, playbackSourceMode = 'prefer_mp4') {
	if (!video) return ''
	const playurl = video.playurl || video.hlsUrl || ''
	const mp4 = video.videounrealaddr || video.mp4Url || video.playSrc || ''
	const isHls = /\.m3u8(\?|$)/i.test(playurl)
	if (playbackSourceMode === 'mp4_only') return mp4 || ''
	if (playbackSourceMode === 'hls_only') return (isHls ? playurl : '') || playurl || ''
	if (playbackSourceMode === 'prefer_hls') return (isHls ? playurl : '') || mp4 || playurl || ''
	return mp4 || playurl || ''
}
