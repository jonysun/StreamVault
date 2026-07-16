import { resolvePlayableSource } from './videoUrl.js'

export function buildNativeFeedOptions({
	videos,
	currentIndex,
	serveraddr,
	serverport,
	servertoken,
	selectedAuthor = '',
	activeOrderMode = 'desc',
	playbackSourceMode = 'prefer_mp4',
	playbackMode = 'autonext',
	isMuted = true,
	currentVideoId = '',
	randomSeed = ''
}) {
	const safeVideos = Array.isArray(videos) ? videos : []
	return {
		serveraddr,
		serverport,
		servertoken,
		currentIndex: Math.max(0, Number(currentIndex || 0)),
		selectedAuthor,
		activeOrderMode,
		playbackSourceMode,
		playbackMode,
		isMuted,
		currentVideoId,
		randomSeed,
		videos: safeVideos.map(v => {
			const mp4Url = v.videounrealaddr || v.mp4Url || ''
			const hlsUrl = v.playurl || v.hlsUrl || ''
			return {
				id: v.id || v.videoid || '',
				videoid: v.videoid || '',
				title: v.videoname || v.title || '',
				desc: v.videodesc || v.desc || '',
				author: v.videoauthor || v.author || v.authorusername || '',
				publishTime: v.publishTime || v.publishtime || v.createTime || v.createtime || '',
				coverUrl: v.videocover || v.coverUrl || '',
				mp4Url,
				hlsUrl,
				playSrc: v.playSrc || resolvePlayableSource(v, playbackSourceMode),
				favorite: v.favorite || '0',
				authorId: v.authoruid || v.authorId || '',
				authorAvatarUrl: v.authoravatar || v.authorAvatarUrl || '',
				authorDesc: v.authorDesc || '',
				originalUrl: v.sourceurl || v.originaladdress || '',
				videoname: v.videoname || '',
				videodesc: v.videodesc || '',
				videoauthor: v.videoauthor || '',
				publishtime: v.publishtime || '',
				videocover: v.videocover || '',
				videounrealaddr: mp4Url,
				playurl: hlsUrl,
				sourceurl: v.sourceurl || '',
				originaladdress: v.originaladdress || '',
				authoruid: v.authoruid || '',
				authoravatar: v.authoravatar || ''
			}
		})
	}
}
