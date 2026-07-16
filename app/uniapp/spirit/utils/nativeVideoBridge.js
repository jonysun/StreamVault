function isAndroidAppPlus() {
    // #ifdef APP-PLUS
    const info = uni.getSystemInfoSync()
    return String(info.platform || '').toLowerCase() === 'android'
    // #endif
    return false
}

function resolvePlugin() {
    if (!isAndroidAppPlus()) return null
    if (typeof uni.requireNativePlugin !== 'function') return null
    try {
        return uni.requireNativePlugin('StreamVault-NativeVideo')
    } catch (e) {
        console.log('native video plugin unavailable', e)
        return null
    }
}

function callNativeWithCallback(invoker, timeoutMs, timeoutReason) {
    return new Promise((resolve) => {
        let settled = false
        const done = (res) => {
            if (settled) return
            settled = true
            resolve(res || { ok: false, reason: 'empty_result' })
        }
        const timer = setTimeout(() => done({ ok: false, reason: timeoutReason }), timeoutMs)
        const finish = (res) => {
            clearTimeout(timer)
            done(res)
        }
        try {
            const syncRes = invoker((res) => finish(res || { ok: true }))
            if (syncRes && typeof syncRes === 'object') {
                finish(syncRes)
            }
        } catch (e) {
            clearTimeout(timer)
            console.log('native call failed', e)
            done({ ok: false, reason: 'open_failed' })
        }
    })
}

export default {
    isAvailable() {
        const plugin = resolvePlugin()
        return !!(plugin && typeof plugin.openFeed === 'function')
    },
    async openNativeVideoFeed(options) {
        console.log('native video open request', {
            count: options && options.videos ? options.videos.length : 0,
            currentIndex: options && options.currentIndex
        })
        const plugin = resolvePlugin()
        if (!plugin || typeof plugin.openFeed !== 'function') {
            console.log('native video plugin unavailable or missing openFeed')
            return Promise.resolve({ ok: false, reason: 'plugin_unavailable' })
        }
        if (typeof plugin.ping !== 'function') {
            console.log('native video plugin missing ping')
            return { ok: false, reason: 'ping_unavailable' }
        }
        const ping = await callNativeWithCallback((callback) => {
            console.log('native video ping request')
            return plugin.ping({}, (res) => {
                console.log('native video ping callback', res)
                callback(res || { ok: true })
            })
        }, 1200, 'native_no_callback')
        if (!(ping && ping.ok)) {
            console.log('native video ping failed', ping)
            return ping || { ok: false, reason: 'ping_failed' }
        }
        return callNativeWithCallback((callback) => {
            const syncRes = plugin.openFeed(options || {}, (res) => {
                console.log('native video open callback', res)
                callback(res || { ok: true })
            })
            if (syncRes && typeof syncRes === 'object') {
                console.log('native video open sync result', syncRes)
            }
            return syncRes
        }, 2000, 'native_no_callback')
    }
}
