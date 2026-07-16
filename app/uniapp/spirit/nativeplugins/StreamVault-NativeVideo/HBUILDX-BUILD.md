# HBuilderX Android Build Checklist

The plugin package is already in the structure expected by HBuilderX:

- `nativeplugins/StreamVault-NativeVideo/package.json`
- `nativeplugins/StreamVault-NativeVideo/android/streamvault-native-video-release.aar`

Before building APK in HBuilderX:

1. Open the uni-app project at `app/uniapp/spirit`.
2. Open `manifest.json`.
3. In `App原生插件配置`, confirm local plugin `StreamVault-NativeVideo` is selected.
4. Build a custom base or Android APK.

The JS bridge calls `uni.requireNativePlugin('StreamVault-NativeVideo')`; the plugin package registers the same module name.

If HBuilderX reports an Android dependency conflict, adjust only `nativeplugins/StreamVault-NativeVideo/package.json` dependency declarations. The release AAR has been built successfully from `app/android-native-plugin`.
