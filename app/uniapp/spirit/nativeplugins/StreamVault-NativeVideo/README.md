# StreamVault-NativeVideo

Build the native plugin AAR with:

```powershell
$env:JAVA_HOME = "F:\\opencode\\Project\\streamV\\tools\\jdk-17.0.18+8"
app\\android-native-plugin\\gradlew.bat -p app\\android-native-plugin :streamvault-native-video:copyReleaseAarToUniPlugin
```

The task copies `streamvault-native-video-release.aar` into this plugin's `android` directory for HBuilderX custom base / APK packaging.
