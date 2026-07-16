$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$videolist = Join-Path $root 'app\uniapp\spirit\pages\video\videolist.vue'
$fallsVideo = Join-Path $root 'app\uniapp\spirit\pages\video\fallsVideo.vue'
$bridge = Join-Path $root 'app\uniapp\spirit\utils\nativeVideoBridge.js'
$module = Join-Path $root 'app\android-native-plugin\streamvault-native-video\src\main\java\com\streamvault\nativefeed\StreamVaultNativeVideoModule.java'
$aar = Join-Path $root 'app\uniapp\spirit\nativeplugins\StreamVault-NativeVideo\android\streamvault-native-video-release.aar'

function Assert-Contains($Path, $Pattern, $Message) {
    $text = [System.IO.File]::ReadAllText($Path)
    if ($text -notmatch $Pattern) {
        throw $Message
    }
}

function Assert-NotContains($Path, $Pattern, $Message) {
    $text = [System.IO.File]::ReadAllText($Path)
    if ($text -match $Pattern) {
        throw $Message
    }
}

function Find-JavaTool($Name) {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME "bin\$Name.exe"
    }
    $candidates += Join-Path $env:LOCALAPPDATA "Temp\opencode\jdk17\jdk-17.0.18+8\bin\$Name.exe"
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "Unable to find $Name. Set JAVA_HOME to a JDK."
}

Assert-Contains $bridge 'openNativeVideoFeed\s*\(' 'nativeVideoBridge.js must expose openNativeVideoFeed(options).'
Assert-Contains $bridge 'plugin\.ping\s*\(\s*\{\s*\}' 'nativeVideoBridge.js must call native ping with an options object before openFeed.'
Assert-Contains $bridge 'native_no_callback' 'nativeVideoBridge.js must not assume success when native callback is absent.'
Assert-Contains $videolist 'navigateToVideo\(item\)' 'videolist.vue item taps must use the normal videoPlay fallback path.'
Assert-Contains $videolist 'playSrc\s*=\s*payload\.playSrc\s*\|\|\s*payload\.videounrealaddr\s*\|\|\s*payload\.playurl' 'videolist.vue fallback payload must include playSrc.'
Assert-NotContains $videolist 'nativeVideoBridge' 'videolist.vue must not use native playback for single item taps.'
Assert-NotContains $videolist 'openNativeVideoFeed' 'videolist.vue must not call openNativeVideoFeed.'
Assert-NotContains $videolist '原生测试' 'videolist.vue must not expose native test controls.'
Assert-Contains $fallsVideo 'nativeVideoBridge\.openNativeVideoFeed\s*\(' 'fallsVideo.vue must call nativeVideoBridge.openNativeVideoFeed(options).'
Assert-Contains $fallsVideo 'SV_NATIVE_FEED_MANUAL' 'fallsVideo.vue must expose the temporary manual native diagnostic entry.'
Assert-Contains $fallsVideo 'SV_NATIVE_FEED_CALL' 'fallsVideo.vue must show a diagnostic before calling native from feed.'
Assert-Contains $module 'public\s+void\s+ping\s*\(\s*JSONObject\s+options\s*,\s*UniJSCallback\s+callback\s*\)' 'StreamVaultNativeVideoModule must expose ping(JSONObject options, UniJSCallback callback).'
Assert-Contains $module 'public\s+void\s+openFeed\s*\(\s*JSONObject\s+options\s*,\s*UniJSCallback\s+callback\s*\)' 'StreamVaultNativeVideoModule openFeed must be callback-only void.'

if (Test-Path $aar) {
    $jar = Find-JavaTool 'jar'
    $javapTool = Find-JavaTool 'javap'
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('streamvault-aar-check-' + [System.Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Push-Location $tmp
        & $jar xf $aar classes.jar
        $javap = & $javapTool -classpath classes.jar -c -p com.streamvault.nativefeed.StreamVaultNativeVideoModule 2>&1 | Out-String
        if ($javap -notmatch 'mUniSDKInstance:Lio/dcloud/feature/uniapp/UniSDKInstance;') {
            throw 'AAR must reference mUniSDKInstance with io.dcloud.feature.uniapp.UniSDKInstance.'
        }
        if ($javap -match 'mUniSDKInstance:Lio/dcloud/feature/uniapp/common/UniSDKInstance;') {
            throw 'AAR must not reference mUniSDKInstance with io.dcloud.feature.uniapp.common.UniSDKInstance.'
        }
    } finally {
        Pop-Location
        Remove-Item -LiteralPath $tmp -Recurse -Force
    }
}

'native video wiring verified'
