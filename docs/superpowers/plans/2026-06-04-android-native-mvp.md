# StreamVault Native Android MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first native Android app under `app/android-native` with a runnable Compose shell, server configuration, core utilities, home submit flow, video list, single player, and Douyin-style feed screen scaffold.

**Architecture:** Create a single Android application module with Kotlin + Jetpack Compose. Keep boundaries clear inside one module: `core` owns models, storage, network, URL/crypto/media utilities; `feature` owns screens and view models. Use TDD for pure logic first, then build UI around stable repositories and models.

**Tech Stack:** Kotlin, Android Gradle Plugin 8.7.3, compileSdk 35, minSdk 23, Jetpack Compose, Navigation Compose, DataStore, Retrofit, OkHttp, Media3 ExoPlayer, Coil, Coroutines, Flow, JUnit, MockWebServer.

---

## Scope

This plan implements the Phase 1 user-facing MVP from `docs/superpowers/specs/2026-06-04-android-native-app-design.md`.

In scope:

- Android app skeleton in `app/android-native`.
- Core model, storage, URL resolver, XOR crypto, network result handling.
- Main Compose navigation shell with four tabs.
- Server list and add/edit/import/share flows.
- Home screen with submit link and recent history.
- Video list and single player.
- Immersive feed screen scaffold with Douyin-style black surface, metadata overlay, and right action rail.

Out of scope for this plan:

- Full admin dashboard and admin management screens.
- Release signing.
- Automatic uni-app local storage migration.
- Comments, danmu, or copied code/assets from reference projects.

---

## File Structure

Create these files under `app/android-native`.

### Gradle and Android Configuration

- `app/android-native/settings.gradle.kts`: Gradle plugin repositories and module include.
- `app/android-native/build.gradle.kts`: root plugin versions.
- `app/android-native/gradle.properties`: AndroidX, Kotlin, JVM, and Compose settings.
- `app/android-native/local.properties`: local Android SDK path, generated locally only if needed.
- `app/android-native/app/build.gradle.kts`: app plugin, dependencies, SDK versions.
- `app/android-native/app/src/main/AndroidManifest.xml`: app manifest, permissions, cleartext config.
- `app/android-native/app/src/main/res/xml/network_security_config.xml`: allow cleartext backend access for user-configured servers.

### App Entrypoint and Navigation

- `app/android-native/app/src/main/java/com/streamvault/android/MainActivity.kt`: Android entrypoint and Compose host.
- `app/android-native/app/src/main/java/com/streamvault/android/StreamVaultApp.kt`: top-level theme and navigation shell.
- `app/android-native/app/src/main/java/com/streamvault/android/core/ui/AppRoute.kt`: route definitions.
- `app/android-native/app/src/main/java/com/streamvault/android/core/ui/AppScaffold.kt`: shared scaffold, bottom nav, snackbar host.
- `app/android-native/app/src/main/java/com/streamvault/android/core/ui/StreamVaultTheme.kt`: color, typography, shapes.
- `app/android-native/app/src/main/java/com/streamvault/android/core/ui/CommonStates.kt`: loading, empty, error UI.

### Core Models and Utilities

- `app/android-native/app/src/main/java/com/streamvault/android/core/model/ServerConfig.kt`: server config model.
- `app/android-native/app/src/main/java/com/streamvault/android/core/model/VideoItem.kt`: video item model.
- `app/android-native/app/src/main/java/com/streamvault/android/core/model/ProcessHistory.kt`: recent history model.
- `app/android-native/app/src/main/java/com/streamvault/android/core/model/ApiResponse.kt`: backend response wrapper.
- `app/android-native/app/src/main/java/com/streamvault/android/core/model/PlaybackSourceMode.kt`: MP4/HLS preference enum.
- `app/android-native/app/src/main/java/com/streamvault/android/core/model/PlaybackMode.kt`: auto-next/loop enum.
- `app/android-native/app/src/main/java/com/streamvault/android/core/model/AppError.kt`: typed UI error.
- `app/android-native/app/src/main/java/com/streamvault/android/core/util/VideoUrlResolver.kt`: URL normalization and source choice.
- `app/android-native/app/src/main/java/com/streamvault/android/core/crypto/XorCrypto.kt`: JS-compatible XOR crypto.

### Storage and Network

- `app/android-native/app/src/main/java/com/streamvault/android/core/storage/ServerConfigRepository.kt`: server list and default selection.
- `app/android-native/app/src/main/java/com/streamvault/android/core/storage/CacheSettingsRepository.kt`: basic cache settings.
- `app/android-native/app/src/main/java/com/streamvault/android/core/network/StreamVaultApi.kt`: Retrofit public API.
- `app/android-native/app/src/main/java/com/streamvault/android/core/network/ApiClientFactory.kt`: dynamic Retrofit/OkHttp creation.
- `app/android-native/app/src/main/java/com/streamvault/android/core/network/StreamVaultRepository.kt`: public backend operations.

### Media

- `app/android-native/app/src/main/java/com/streamvault/android/core/media/SinglePlayerView.kt`: Compose wrapper for PlayerView.

### Features

- `app/android-native/app/src/main/java/com/streamvault/android/feature/home/HomeScreen.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/home/HomeViewModel.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/server/ServerListScreen.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/server/ServerEditScreen.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/server/ServerViewModel.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/videolist/VideoListScreen.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/videolist/VideoListViewModel.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/player/VideoPlayerScreen.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/feed/FeedScreen.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/feed/FeedViewModel.kt`
- `app/android-native/app/src/main/java/com/streamvault/android/feature/settings/CacheSettingsScreen.kt`

### Tests

- `app/android-native/app/src/test/java/com/streamvault/android/core/util/VideoUrlResolverTest.kt`
- `app/android-native/app/src/test/java/com/streamvault/android/core/crypto/XorCryptoTest.kt`
- `app/android-native/app/src/test/java/com/streamvault/android/core/storage/ServerConfigRepositoryTest.kt`
- `app/android-native/app/src/test/java/com/streamvault/android/core/network/StreamVaultRepositoryTest.kt`

---

## Verification Commands

Use these commands from the repo root unless a task says otherwise.

- Build native app: `app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:assembleDebug`
- Unit tests: `app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest`
- Specific test: `app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.util.VideoUrlResolverTest"`

Set `JAVA_HOME` first if needed:

```powershell
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"
```

---

### Task 1: Scaffold Native Android Project

**Files:**

- Create: `app/android-native/settings.gradle.kts`
- Create: `app/android-native/build.gradle.kts`
- Create: `app/android-native/gradle.properties`
- Create: `app/android-native/app/build.gradle.kts`
- Create: `app/android-native/app/src/main/AndroidManifest.xml`
- Create: `app/android-native/app/src/main/res/xml/network_security_config.xml`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/MainActivity.kt`

- [ ] **Step 1: Create Gradle settings**

Write `app/android-native/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StreamVaultAndroid"
include(":app")
```

- [ ] **Step 2: Create root Gradle build file**

Write `app/android-native/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
```

- [ ] **Step 3: Create Gradle properties**

Write `app/android-native/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 4: Create app module Gradle build file**

Write `app/android-native/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.streamvault.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.streamvault.android"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
}
```

- [ ] **Step 5: Create manifest and network config**

Write `app/android-native/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="true"
        android:label="StreamVault"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Write `app/android-native/app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

Also create `app/android-native/app/src/main/res/values/styles.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowActionBar">false</item>
        <item name="android:windowNoTitle">true</item>
    </style>
</resources>
```

- [ ] **Step 6: Create minimal MainActivity**

Write `app/android-native/app/src/main/java/com/streamvault/android/MainActivity.kt`:

```kotlin
package com.streamvault.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("StreamVault Android")
                }
            }
        }
    }
}
```

- [ ] **Step 7: Copy Gradle wrapper**

Copy `gradlew.bat`, `gradlew`, and `gradle/wrapper/*` from `app/android-native-plugin` into `app/android-native`.

Run:

```powershell
Copy-Item -LiteralPath "app\android-native-plugin\gradlew.bat" -Destination "app\android-native\gradlew.bat"
Copy-Item -LiteralPath "app\android-native-plugin\gradlew" -Destination "app\android-native\gradlew"
New-Item -ItemType Directory -Path "app\android-native\gradle\wrapper" -Force | Out-Null
Copy-Item -LiteralPath "app\android-native-plugin\gradle\wrapper\gradle-wrapper.jar" -Destination "app\android-native\gradle\wrapper\gradle-wrapper.jar"
Copy-Item -LiteralPath "app\android-native-plugin\gradle\wrapper\gradle-wrapper.properties" -Destination "app\android-native\gradle\wrapper\gradle-wrapper.properties"
```

- [ ] **Step 8: Build debug APK**

Run:

```powershell
$env:JAVA_HOME="C:\Users\Jonysun\AppData\Local\Temp\opencode\jdk17\jdk-17.0.18+8"
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add app/android-native
git commit -m "feat: scaffold native android app"
```

---

### Task 2: Add Core Models and URL Resolver with Tests

**Files:**

- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/model/ServerConfig.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/model/VideoItem.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/model/ProcessHistory.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/model/ApiResponse.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/model/PlaybackSourceMode.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/model/PlaybackMode.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/model/AppError.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/util/VideoUrlResolver.kt`
- Test: `app/android-native/app/src/test/java/com/streamvault/android/core/util/VideoUrlResolverTest.kt`

- [ ] **Step 1: Write failing URL resolver tests**

Write `VideoUrlResolverTest.kt`:

```kotlin
package com.streamvault.android.core.util

import com.streamvault.android.core.model.PlaybackSourceMode
import com.streamvault.android.core.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoUrlResolverTest {
    @Test
    fun fullHttpUrlPassesThrough() {
        assertEquals(
            "https://cdn.example.com/a.mp4?x=1",
            VideoUrlResolver.normalize("https://cdn.example.com/a.mp4?x=1", "http://host", "8080", "tok")
        )
    }

    @Test
    fun relativePathIsNormalizedEncodedAndTokenized() {
        assertEquals(
            "http://host:8080/video/%E4%B8%AD%E6%96%87/a%20b.mp4?apptoken=tok",
            VideoUrlResolver.normalize("video\\中文//a b.mp4", "http://host", "8080", "tok")
        )
    }

    @Test
    fun preferMp4ChoosesMp4BeforeHls() {
        val video = VideoItem(id = 1, playurl = "http://cdn/a.m3u8", videounrealaddr = "http://cdn/a.mp4")
        assertEquals("http://cdn/a.mp4", VideoUrlResolver.resolvePlayableSource(video, PlaybackSourceMode.PreferMp4))
    }

    @Test
    fun preferHlsChoosesM3u8BeforeMp4() {
        val video = VideoItem(id = 1, playurl = "http://cdn/a.m3u8", videounrealaddr = "http://cdn/a.mp4")
        assertEquals("http://cdn/a.m3u8", VideoUrlResolver.resolvePlayableSource(video, PlaybackSourceMode.PreferHls))
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.util.VideoUrlResolverTest"
```

Expected: FAIL because `VideoUrlResolver` and model classes do not exist.

- [ ] **Step 3: Implement model classes**

Write `PlaybackSourceMode.kt`:

```kotlin
package com.streamvault.android.core.model

enum class PlaybackSourceMode {
    PreferMp4,
    PreferHls,
    Mp4Only,
    HlsOnly
}
```

Write `PlaybackMode.kt`:

```kotlin
package com.streamvault.android.core.model

enum class PlaybackMode {
    AutoNext,
    LoopCurrent
}
```

Write `ServerConfig.kt`:

```kotlin
package com.streamvault.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val serverName: String,
    val server: String,
    val port: String,
    val token: String,
    val streaming: Boolean = false,
    val isDefault: Boolean = false
) {
    val baseUrl: String
        get() = "${server.trimEnd('/')}:${port}"
}
```

Write `VideoItem.kt`:

```kotlin
package com.streamvault.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoItem(
    val id: Int? = null,
    val videoid: String? = null,
    val videotitle: String? = null,
    val videodesc: String? = null,
    val videoauthor: String? = null,
    val authoravatar: String? = null,
    val videocover: String? = null,
    val playurl: String? = null,
    val hlsUrl: String? = null,
    val videounrealaddr: String? = null,
    val mp4Url: String? = null,
    val playSrc: String? = null,
    val favorite: String? = null,
    val videoprivacy: String? = null
) {
    val displayTitle: String
        get() = videotitle?.takeIf { it.isNotBlank() }
            ?: videodesc?.takeIf { it.isNotBlank() }
            ?: "未命名视频"

    val isFavorite: Boolean
        get() = favorite == "1"
}
```

Write `ProcessHistory.kt`:

```kotlin
package com.streamvault.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ProcessHistory(
    val id: Int? = null,
    val originaladdress: String? = null,
    val status: String? = null,
    val tasklog: String? = null
)
```

Write `ApiResponse.kt`:

```kotlin
package com.streamvault.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val resCode: String? = null,
    val message: String? = null,
    val resMsg: String? = null,
    val record: T? = null
) {
    val isSuccess: Boolean
        get() = resCode == "000001"

    val displayMessage: String
        get() = message ?: resMsg ?: "请求失败"
}
```

Write `AppError.kt`:

```kotlin
package com.streamvault.android.core.model

sealed class AppError(val message: String) {
    data object NoServer : AppError("请先配置服务器")
    data class Network(val detail: String = "网络请求失败") : AppError(detail)
    data class Backend(val detail: String) : AppError(detail)
    data class InvalidInput(val detail: String) : AppError(detail)
}
```

- [ ] **Step 4: Implement URL resolver**

Write `VideoUrlResolver.kt`:

```kotlin
package com.streamvault.android.core.util

import com.streamvault.android.core.model.PlaybackSourceMode
import com.streamvault.android.core.model.VideoItem
import java.net.URLEncoder

object VideoUrlResolver {
    fun normalize(rawPath: String?, serverAddr: String, serverPort: String, token: String): String {
        if (rawPath.isNullOrBlank()) return ""
        if (rawPath.startsWith("http://", ignoreCase = true) || rawPath.startsWith("https://", ignoreCase = true)) {
            return rawPath
        }
        val normalized = rawPath.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment -> URLEncoder.encode(segment, "UTF-8").replace("+", "%20") }
        return "${serverAddr.trimEnd('/')}:$serverPort/$normalized?apptoken=$token"
    }

    fun resolvePlayableSource(video: VideoItem?, mode: PlaybackSourceMode = PlaybackSourceMode.PreferMp4): String {
        if (video == null) return ""
        val playUrl = video.playurl ?: video.hlsUrl ?: ""
        val mp4 = video.videounrealaddr ?: video.mp4Url ?: video.playSrc ?: ""
        val isHls = playUrl.contains(".m3u8", ignoreCase = true)
        return when (mode) {
            PlaybackSourceMode.Mp4Only -> mp4
            PlaybackSourceMode.HlsOnly -> if (isHls) playUrl else playUrl
            PlaybackSourceMode.PreferHls -> if (isHls && playUrl.isNotBlank()) playUrl else mp4.ifBlank { playUrl }
            PlaybackSourceMode.PreferMp4 -> mp4.ifBlank { playUrl }
        }
    }
}
```

- [ ] **Step 5: Run test to verify GREEN**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.util.VideoUrlResolverTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/android-native/app/src/main/java/com/streamvault/android/core app/android-native/app/src/test/java/com/streamvault/android/core
git commit -m "feat: add native core models and url resolver"
```

---

### Task 3: Add XOR Crypto with Compatibility Tests

**Files:**

- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/crypto/XorCrypto.kt`
- Test: `app/android-native/app/src/test/java/com/streamvault/android/core/crypto/XorCryptoTest.kt`

- [ ] **Step 1: Write failing crypto tests**

Write `XorCryptoTest.kt`:

```kotlin
package com.streamvault.android.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class XorCryptoTest {
    @Test
    fun encryptThenDecryptReturnsOriginalText() {
        val text = "{\"servername\":\"local\",\"server\":\"http://127.0.0.1\",\"port\":\"8080\",\"token\":\"abc\"}"
        val encrypted = XorCrypto.encrypt(text, "key123")
        assertEquals(text, XorCrypto.decrypt(encrypted, "key123"))
    }

    @Test
    fun decryptsKnownJsCompatibleCipherText() {
        assertEquals("hello", XorCrypto.decrypt("AwAVBwo=", "key"))
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.crypto.XorCryptoTest"
```

Expected: FAIL because `XorCrypto` does not exist.

- [ ] **Step 3: Implement XOR crypto**

Write `XorCrypto.kt`:

```kotlin
package com.streamvault.android.core.crypto

import java.util.Base64

object XorCrypto {
    fun encrypt(plainText: String, key: String): String {
        require(key.isNotEmpty()) { "key must not be empty" }
        val data = plainText.toByteArray(Charsets.UTF_8)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(xor(data, keyBytes))
    }

    fun decrypt(cipherBase64: String, key: String): String {
        require(key.isNotEmpty()) { "key must not be empty" }
        val encrypted = Base64.getDecoder().decode(cipherBase64)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        return xor(encrypted, keyBytes).toString(Charsets.UTF_8)
    }

    private fun xor(data: ByteArray, key: ByteArray): ByteArray {
        return ByteArray(data.size) { index ->
            (data[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }
}
```

- [ ] **Step 4: Run test to verify GREEN**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.crypto.XorCryptoTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/android-native/app/src/main/java/com/streamvault/android/core/crypto app/android-native/app/src/test/java/com/streamvault/android/core/crypto
git commit -m "feat: add server share crypto"
```

---

### Task 4: Add Server Repository and Tests

**Files:**

- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/storage/ServerConfigRepository.kt`
- Test: `app/android-native/app/src/test/java/com/streamvault/android/core/storage/ServerConfigRepositoryTest.kt`

- [ ] **Step 1: Write failing repository tests**

Write `ServerConfigRepositoryTest.kt`:

```kotlin
package com.streamvault.android.core.storage

import com.streamvault.android.core.model.ServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigRepositoryTest {
    @Test
    fun firstServerBecomesDefault() = runTest {
        val repository = ServerConfigRepository.InMemory()
        repository.save(ServerConfig("local", "http://127.0.0.1", "8080", "tok"))
        assertTrue(repository.servers().first().isDefault)
        assertEquals("local", repository.defaultServer()?.serverName)
    }

    @Test
    fun settingDefaultClearsOtherDefaults() = runTest {
        val repository = ServerConfigRepository.InMemory()
        repository.save(ServerConfig("one", "http://one", "1", "a"))
        repository.save(ServerConfig("two", "http://two", "2", "b"))
        repository.setDefault(1)
        assertEquals(listOf(false, true), repository.servers().map { it.isDefault })
    }

    @Test
    fun deletingDefaultPromotesFirstRemainingServer() = runTest {
        val repository = ServerConfigRepository.InMemory()
        repository.save(ServerConfig("one", "http://one", "1", "a"))
        repository.save(ServerConfig("two", "http://two", "2", "b"))
        repository.delete(0)
        assertEquals("two", repository.defaultServer()?.serverName)
        assertTrue(repository.servers().first().isDefault)
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.storage.ServerConfigRepositoryTest"
```

Expected: FAIL because `ServerConfigRepository` does not exist.

- [ ] **Step 3: Implement in-memory repository first**

Write `ServerConfigRepository.kt`:

```kotlin
package com.streamvault.android.core.storage

import com.streamvault.android.core.model.ServerConfig

interface ServerConfigRepository {
    suspend fun servers(): List<ServerConfig>
    suspend fun defaultServer(): ServerConfig?
    suspend fun save(server: ServerConfig, index: Int? = null)
    suspend fun delete(index: Int)
    suspend fun setDefault(index: Int)

    class InMemory(initial: List<ServerConfig> = emptyList()) : ServerConfigRepository {
        private val items = initial.toMutableList()

        override suspend fun servers(): List<ServerConfig> = items.toList()

        override suspend fun defaultServer(): ServerConfig? = items.firstOrNull { it.isDefault } ?: items.firstOrNull()

        override suspend fun save(server: ServerConfig, index: Int?) {
            val next = if (items.isEmpty()) server.copy(isDefault = true) else server
            if (index == null) items.add(next) else items[index] = next
            ensureSingleDefault()
        }

        override suspend fun delete(index: Int) {
            val removedDefault = items.getOrNull(index)?.isDefault == true
            if (index in items.indices) items.removeAt(index)
            if (removedDefault && items.isNotEmpty()) {
                items[0] = items[0].copy(isDefault = true)
            }
            ensureSingleDefault()
        }

        override suspend fun setDefault(index: Int) {
            if (index !in items.indices) return
            items.replaceAll { it.copy(isDefault = false) }
            items[index] = items[index].copy(isDefault = true)
        }

        private fun ensureSingleDefault() {
            if (items.isEmpty()) return
            val defaultIndex = items.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
            items.replaceAll { it.copy(isDefault = false) }
            items[defaultIndex] = items[defaultIndex].copy(isDefault = true)
        }
    }
}
```

- [ ] **Step 4: Run test to verify GREEN**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.storage.ServerConfigRepositoryTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/android-native/app/src/main/java/com/streamvault/android/core/storage app/android-native/app/src/test/java/com/streamvault/android/core/storage
git commit -m "feat: add server config repository"
```

---

### Task 5: Add App Theme, Routes, and Bottom Navigation Shell

**Files:**

- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/ui/AppRoute.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/ui/StreamVaultTheme.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/ui/AppScaffold.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/ui/CommonStates.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/StreamVaultApp.kt`
- Modify: `app/android-native/app/src/main/java/com/streamvault/android/MainActivity.kt`

- [ ] **Step 1: Implement route definitions**

Write `AppRoute.kt`:

```kotlin
package com.streamvault.android.core.ui

sealed class AppRoute(val route: String, val label: String) {
    data object Home : AppRoute("home", "推送")
    data object Feed : AppRoute("feed", "作品")
    data object VideoList : AppRoute("videos", "列表")
    data object Admin : AppRoute("admin", "管理")
    data object Servers : AppRoute("servers", "服务器")
    data object ServerEdit : AppRoute("server-edit", "编辑服务器")
    data object Player : AppRoute("player", "播放")
    data object CacheSettings : AppRoute("cache-settings", "缓存设置")
}
```

- [ ] **Step 2: Implement theme tokens**

Write `StreamVaultTheme.kt`:

```kotlin
package com.streamvault.android.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SvColors {
    val DouyinRed = Color(0xFFFE2C55)
    val DouyinCyan = Color(0xFF25F4EE)
    val FeedBlack = Color.Black
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    background = Color(0xFFF6F7F8),
    surface = Color.White,
    error = Color(0xFFEF4444)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    background = Color(0xFF050505),
    surface = Color(0xFF121212),
    error = Color(0xFFF87171)
)

@Composable
fun StreamVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
```

- [ ] **Step 3: Implement common states**

Write `CommonStates.kt`:

```kotlin
package com.streamvault.android.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoadingState(message: String = "加载中...") {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text(message, Modifier.padding(top = 12.dp))
    }
}

@Composable
fun EmptyState(title: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (actionText != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionText) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null) {
    EmptyState(title = message, actionText = if (onRetry == null) null else "重试", onAction = onRetry)
}
```

- [ ] **Step 4: Implement navigation shell**

Write `AppScaffold.kt`:

```kotlin
package com.streamvault.android.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

val TopLevelRoutes = listOf(AppRoute.Home, AppRoute.Feed, AppRoute.VideoList, AppRoute.Admin)

@Composable
fun SvBottomNav(currentRoute: String?, onNavigate: (AppRoute) -> Unit) {
    NavigationBar {
        TopLevelRoutes.forEach { route ->
            val icon = when (route) {
                AppRoute.Home -> Icons.Default.Home
                AppRoute.Feed -> Icons.Default.PlayCircle
                AppRoute.VideoList -> Icons.Default.VideoLibrary
                AppRoute.Admin -> Icons.Default.AdminPanelSettings
                else -> Icons.Default.Home
            }
            NavigationBarItem(
                selected = currentRoute == route.route,
                onClick = { onNavigate(route) },
                icon = { Icon(icon, contentDescription = route.label) },
                label = { Text(route.label) }
            )
        }
    }
}
```

- [ ] **Step 5: Implement app host with placeholder screens**

Write `StreamVaultApp.kt`:

```kotlin
package com.streamvault.android

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamvault.android.core.ui.AppRoute
import com.streamvault.android.core.ui.SvBottomNav
import com.streamvault.android.core.ui.StreamVaultTheme

@Composable
fun StreamVaultApp() {
    StreamVaultTheme {
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        Scaffold(bottomBar = { SvBottomNav(currentRoute) { navController.navigate(it.route) { launchSingleTop = true } } }) { padding ->
            NavHost(navController = navController, startDestination = AppRoute.Home.route, modifier = Modifier.padding(padding)) {
                composable(AppRoute.Home.route) { Text("推送") }
                composable(AppRoute.Feed.route) { Text("作品") }
                composable(AppRoute.VideoList.route) { Text("列表") }
                composable(AppRoute.Admin.route) { Text("管理") }
            }
        }
    }
}
```

Modify `MainActivity.kt`:

```kotlin
package com.streamvault.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StreamVaultApp() }
    }
}
```

- [ ] **Step 6: Build**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add app/android-native/app/src/main/java/com/streamvault/android
git commit -m "feat: add native app navigation shell"
```

---

### Task 6: Add Network Repository and Home Backend Calls

**Files:**

- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/network/StreamVaultApi.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/network/ApiClientFactory.kt`
- Create: `app/android-native/app/src/main/java/com/streamvault/android/core/network/StreamVaultRepository.kt`
- Test: `app/android-native/app/src/test/java/com/streamvault/android/core/network/StreamVaultRepositoryTest.kt`

- [ ] **Step 1: Write failing repository test with MockWebServer**

Write `StreamVaultRepositoryTest.kt`:

```kotlin
package com.streamvault.android.core.network

import com.streamvault.android.core.model.ServerConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamVaultRepositoryTest {
    @Test
    fun processingVideosPostsTokenAndVideo() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"resCode\":\"000001\",\"message\":\"ok\"}"))
        server.start()
        try {
            val base = server.url("/")
            val config = ServerConfig("test", "${base.scheme}://${base.host}", base.port.toString(), "tok")
            val repository = StreamVaultRepository(ApiClientFactory.create(config))
            val response = repository.processingVideos(config, "http://share")
            val request = server.takeRequest()
            assertEquals("/api/processingVideos", request.path?.substringBefore('?'))
            assertEquals(true, request.body.readUtf8().contains("token=tok"))
            assertEquals("ok", response.message)
        } finally {
            server.shutdown()
        }
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.network.StreamVaultRepositoryTest"
```

Expected: FAIL because network classes do not exist.

- [ ] **Step 3: Implement Retrofit API**

Write `StreamVaultApi.kt`:

```kotlin
package com.streamvault.android.core.network

import com.streamvault.android.core.model.ApiResponse
import com.streamvault.android.core.model.ProcessHistory
import com.streamvault.android.core.model.VideoItem
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface StreamVaultApi {
    @FormUrlEncoded
    @POST("api/processingVideos")
    suspend fun processingVideos(@Field("token") token: String, @Field("video") video: String): ApiResponse<Unit>

    @GET("api/recentProcessHistory")
    suspend fun recentProcessHistory(@Query("token") token: String, @Query("limit") limit: Int): ApiResponse<List<ProcessHistory>>

    @POST("api/findVideos")
    suspend fun findVideos(@Query("token") token: String): ApiResponse<List<VideoItem>>

    @GET("api/updateVideoFavorite")
    suspend fun updateVideoFavorite(@Query("token") token: String, @Query("id") id: Int, @Query("favorite") favorite: String): ApiResponse<VideoItem>
}
```

Write `ApiClientFactory.kt`:

```kotlin
package com.streamvault.android.core.network

import com.streamvault.android.core.model.ServerConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClientFactory {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun create(server: ServerConfig): StreamVaultApi {
        return Retrofit.Builder()
            .baseUrl(server.baseUrl.trimEnd('/') + "/")
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(StreamVaultApi::class.java)
    }
}
```

Write `StreamVaultRepository.kt`:

```kotlin
package com.streamvault.android.core.network

import com.streamvault.android.core.model.ApiResponse
import com.streamvault.android.core.model.ProcessHistory
import com.streamvault.android.core.model.ServerConfig
import com.streamvault.android.core.model.VideoItem

class StreamVaultRepository(private val api: StreamVaultApi) {
    suspend fun processingVideos(server: ServerConfig, video: String): ApiResponse<Unit> {
        return api.processingVideos(server.token, video)
    }

    suspend fun recentProcessHistory(server: ServerConfig, limit: Int = 8): ApiResponse<List<ProcessHistory>> {
        return api.recentProcessHistory(server.token, limit)
    }

    suspend fun findVideos(server: ServerConfig): ApiResponse<List<VideoItem>> {
        return api.findVideos(server.token)
    }

    suspend fun updateVideoFavorite(server: ServerConfig, id: Int, favorite: Boolean): ApiResponse<VideoItem> {
        return api.updateVideoFavorite(server.token, id, if (favorite) "1" else "0")
    }
}
```

- [ ] **Step 4: Run test to verify GREEN**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest --tests "com.streamvault.android.core.network.StreamVaultRepositoryTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/android-native/app/src/main/java/com/streamvault/android/core/network app/android-native/app/src/test/java/com/streamvault/android/core/network
git commit -m "feat: add native public api client"
```

---

### Task 7: Implement Server Screens and Home Screen MVP

**Files:**

- Create: `feature/server/ServerViewModel.kt`
- Create: `feature/server/ServerListScreen.kt`
- Create: `feature/server/ServerEditScreen.kt`
- Create: `feature/home/HomeViewModel.kt`
- Create: `feature/home/HomeScreen.kt`
- Modify: `StreamVaultApp.kt`

- [ ] **Step 1: Implement server view model using repository**

Write `ServerViewModel.kt`:

```kotlin
package com.streamvault.android.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.android.core.model.ServerConfig
import com.streamvault.android.core.storage.ServerConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServerViewModel(private val repository: ServerConfigRepository = ServerConfigRepository.InMemory()) : ViewModel() {
    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers

    init { refresh() }

    fun refresh() = viewModelScope.launch { _servers.value = repository.servers() }

    fun save(server: ServerConfig, index: Int? = null) = viewModelScope.launch {
        repository.save(server, index)
        refresh()
    }

    fun delete(index: Int) = viewModelScope.launch {
        repository.delete(index)
        refresh()
    }

    fun setDefault(index: Int) = viewModelScope.launch {
        repository.setDefault(index)
        refresh()
    }
}
```

- [ ] **Step 2: Implement server list screen**

Write `ServerListScreen.kt`:

```kotlin
package com.streamvault.android.feature.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.android.core.ui.EmptyState

@Composable
fun ServerListScreen(viewModel: ServerViewModel, onAdd: () -> Unit) {
    val servers by viewModel.servers.collectAsState()
    if (servers.isEmpty()) {
        EmptyState("暂无服务器，请先添加", "添加服务器", onAdd)
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("添加服务器") }
        LazyColumn(Modifier.padding(top = 12.dp)) {
            itemsIndexed(servers) { index, server ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(server.serverName, style = MaterialTheme.typography.titleMedium)
                        Text("${server.server}:${server.port}", style = MaterialTheme.typography.bodyMedium)
                        Text(if (server.isDefault) "默认服务器" else "非默认", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { viewModel.setDefault(index) }) { Text("设为默认") }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = { viewModel.delete(index) }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Implement server edit screen**

Write `ServerEditScreen.kt`:

```kotlin
package com.streamvault.android.feature.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.streamvault.android.core.model.ServerConfig

@Composable
fun ServerEditScreen(viewModel: ServerViewModel, onSaved: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && server.startsWith("http") && port.all { it.isDigit() } && port.isNotBlank() && token.isNotBlank()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("服务器名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(server, { server = it }, label = { Text("服务器地址") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("端口") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        OutlinedTextField(token, { token = it }, label = { Text("Token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Button(
            enabled = valid,
            onClick = {
                viewModel.save(ServerConfig(name, server.trimEnd('/'), port, token))
                onSaved()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) { Text("保存服务器") }
    }
}
```

- [ ] **Step 4: Implement home screen skeleton**

Write `HomeViewModel.kt`:

```kotlin
package com.streamvault.android.feature.home

import androidx.lifecycle.ViewModel
import com.streamvault.android.core.model.ProcessHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HomeUiState(val link: String = "", val history: List<ProcessHistory> = emptyList(), val message: String? = null)

class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    fun setLink(value: String) { _state.value = _state.value.copy(link = value) }
    fun submitPlaceholder() { _state.value = _state.value.copy(message = "后端提交将在网络接入任务中启用") }
}
```

Write `HomeScreen.kt`:

```kotlin
package com.streamvault.android.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(viewModel: HomeViewModel, onServers: () -> Unit, onFeed: () -> Unit, onVideos: () -> Unit) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("StreamVault", style = MaterialTheme.typography.headlineMedium)
        Text("智能视频管理平台", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(state.link, viewModel::setLink, label = { Text("请输入或粘贴视频分享链接") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(enabled = state.link.isNotBlank(), onClick = viewModel::submitPlaceholder, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("提交链接") }
                state.message?.let { Text(it, Modifier.padding(top = 8.dp)) }
            }
        }
        Button(onClick = onServers, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("服务器") }
        Button(onClick = onVideos, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("视频列表") }
        Button(onClick = onFeed, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("沉浸浏览") }
    }
}
```

- [ ] **Step 5: Wire screens into navigation**

Modify `StreamVaultApp.kt` so it creates `ServerViewModel`, `HomeViewModel`, and routes for home/server edit.

```kotlin
package com.streamvault.android

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamvault.android.core.ui.AppRoute
import com.streamvault.android.core.ui.SvBottomNav
import com.streamvault.android.core.ui.StreamVaultTheme
import com.streamvault.android.feature.home.HomeScreen
import com.streamvault.android.feature.home.HomeViewModel
import com.streamvault.android.feature.server.ServerEditScreen
import com.streamvault.android.feature.server.ServerListScreen
import com.streamvault.android.feature.server.ServerViewModel

@Composable
fun StreamVaultApp() {
    StreamVaultTheme {
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        val serverViewModel = remember { ServerViewModel() }
        val homeViewModel = remember { HomeViewModel() }
        Scaffold(bottomBar = { SvBottomNav(currentRoute) { navController.navigate(it.route) { launchSingleTop = true } } }) { padding ->
            NavHost(navController = navController, startDestination = AppRoute.Home.route, modifier = Modifier.padding(padding)) {
                composable(AppRoute.Home.route) { HomeScreen(homeViewModel, { navController.navigate(AppRoute.Servers.route) }, { navController.navigate(AppRoute.Feed.route) }, { navController.navigate(AppRoute.VideoList.route) }) }
                composable(AppRoute.Feed.route) { Text("作品") }
                composable(AppRoute.VideoList.route) { Text("列表") }
                composable(AppRoute.Admin.route) { Text("管理") }
                composable(AppRoute.Servers.route) { ServerListScreen(serverViewModel) { navController.navigate(AppRoute.ServerEdit.route) } }
                composable(AppRoute.ServerEdit.route) { ServerEditScreen(serverViewModel) { navController.popBackStack() } }
            }
        }
    }
}
```

- [ ] **Step 6: Build**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add app/android-native/app/src/main/java/com/streamvault/android
git commit -m "feat: add native server and home screens"
```

---

### Task 8: Add Video List, Player, and Feed MVP Screens

**Files:**

- Create: `core/media/SinglePlayerView.kt`
- Create: `feature/videolist/VideoListViewModel.kt`
- Create: `feature/videolist/VideoListScreen.kt`
- Create: `feature/player/VideoPlayerScreen.kt`
- Create: `feature/feed/FeedViewModel.kt`
- Create: `feature/feed/FeedScreen.kt`
- Modify: `StreamVaultApp.kt`

- [ ] **Step 1: Implement PlayerView wrapper**

Write `SinglePlayerView.kt`:

```kotlin
package com.streamvault.android.core.media

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun SinglePlayerView(player: ExoPlayer, modifier: Modifier = Modifier, useController: Boolean = true) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                this.useController = useController
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { it.player = player }
    )
}
```

- [ ] **Step 2: Implement video list view model with placeholder data path**

Write `VideoListViewModel.kt`:

```kotlin
package com.streamvault.android.feature.videolist

import androidx.lifecycle.ViewModel
import com.streamvault.android.core.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class VideoListUiState(val videos: List<VideoItem> = emptyList(), val isLoading: Boolean = false, val error: String? = null)

class VideoListViewModel : ViewModel() {
    private val _state = MutableStateFlow(VideoListUiState())
    val state: StateFlow<VideoListUiState> = _state

    fun setVideos(videos: List<VideoItem>) { _state.value = VideoListUiState(videos = videos) }
}
```

Write `VideoListScreen.kt`:

```kotlin
package com.streamvault.android.feature.videolist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.android.core.model.VideoItem
import com.streamvault.android.core.ui.EmptyState

@Composable
fun VideoListScreen(viewModel: VideoListViewModel, onPlay: (VideoItem) -> Unit) {
    val state by viewModel.state.collectAsState()
    if (state.videos.isEmpty()) {
        EmptyState("暂无视频", "刷新") { }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(state.videos) { video ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onPlay(video) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(video.displayTitle, style = MaterialTheme.typography.titleMedium)
                    Text(video.videoauthor ?: "未知作者", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Implement single player screen**

Write `VideoPlayerScreen.kt`:

```kotlin
package com.streamvault.android.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.streamvault.android.core.media.SinglePlayerView

@Composable
fun VideoPlayerScreen(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SinglePlayerView(player = player, modifier = Modifier.fillMaxSize(), useController = true)
    }
}
```

- [ ] **Step 4: Implement feed MVP UI**

Write `FeedViewModel.kt`:

```kotlin
package com.streamvault.android.feature.feed

import androidx.lifecycle.ViewModel
import com.streamvault.android.core.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FeedUiState(val videos: List<VideoItem> = emptyList(), val currentIndex: Int = 0, val muted: Boolean = false)

class FeedViewModel : ViewModel() {
    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state

    fun setMuted(muted: Boolean) { _state.value = _state.value.copy(muted = muted) }
    fun setVideos(videos: List<VideoItem>) { _state.value = _state.value.copy(videos = videos.filter { it.playurl != null || it.videounrealaddr != null || it.playSrc != null }) }
}
```

Write `FeedScreen.kt`:

```kotlin
package com.streamvault.android.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.streamvault.android.core.ui.EmptyState
import com.streamvault.android.core.ui.SvColors

@Composable
fun FeedScreen(viewModel: FeedViewModel) {
    val state by viewModel.state.collectAsState()
    val current = state.videos.getOrNull(state.currentIndex)
    if (current == null) {
        EmptyState("暂无可播放作品")
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Text("视频播放区域", color = Color.White, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("@${current.videoauthor ?: "未知作者"}", color = Color.White)
                Text(current.displayTitle, color = Color.White)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { }) { Icon(Icons.Default.Favorite, contentDescription = "收藏", tint = if (current.isFavorite) SvColors.DouyinRed else Color.White) }
                IconButton(onClick = { viewModel.setMuted(!state.muted) }) { Icon(Icons.Default.VolumeOff, contentDescription = "静音", tint = Color.White) }
                IconButton(onClick = { }) { Icon(Icons.Default.Info, contentDescription = "视频信息", tint = Color.White) }
            }
        }
    }
}
```

- [ ] **Step 5: Wire placeholder screens into navigation**

Modify `StreamVaultApp.kt` to use `VideoListScreen` and `FeedScreen`. Use a remembered sample list until backend integration is attached.

```kotlin
// Add imports
import com.streamvault.android.core.model.VideoItem
import com.streamvault.android.feature.feed.FeedScreen
import com.streamvault.android.feature.feed.FeedViewModel
import com.streamvault.android.feature.videolist.VideoListScreen
import com.streamvault.android.feature.videolist.VideoListViewModel

// Inside StreamVaultApp after view models
val videoListViewModel = remember { VideoListViewModel() }
val feedViewModel = remember { FeedViewModel() }

// Before Scaffold, seed sample only if empty in current MVP shell
val sampleVideos = listOf(VideoItem(id = 1, videotitle = "示例视频", videoauthor = "StreamVault", playurl = ""))
videoListViewModel.setVideos(sampleVideos)
feedViewModel.setVideos(sampleVideos)

// Replace route composables
composable(AppRoute.Feed.route) { FeedScreen(feedViewModel) }
composable(AppRoute.VideoList.route) { VideoListScreen(videoListViewModel) { } }
```

- [ ] **Step 6: Build**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add app/android-native/app/src/main/java/com/streamvault/android
git commit -m "feat: add native video screens skeleton"
```

---

### Task 9: Final MVP Verification Pass

**Files:**

- Modify as needed only if verification reveals compile or test failures.

- [ ] **Step 1: Run all unit tests**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:testDebugUnitTest
```

Expected: all tests PASS.

- [ ] **Step 2: Build debug APK**

Run:

```powershell
app\android-native\gradlew.bat -p app\android-native --no-daemon --console=plain :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and APK exists at:

```text
app/android-native/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Inspect git status**

Run:

```powershell
git status --short
```

Expected: only intended Android native app files and plan/spec files are modified or untracked. Do not stage unrelated runtime/tool files such as `backstage/runlogs/`, `tools/`, `jdk17.zip`, or `maven.zip`.

- [ ] **Step 4: Commit verification fixes if any**

If fixes were needed:

```powershell
git add app/android-native docs/superpowers/plans/2026-06-04-android-native-mvp.md docs/superpowers/specs/2026-06-04-android-native-app-design.md
git commit -m "chore: verify native android mvp scaffold"
```

If no fixes were needed, do not create an empty commit.

---

## Follow-Up Plans

Create separate plans after this MVP scaffold is verified:

- `android-native-persistence-plan`: replace in-memory server storage with DataStore-backed storage and migration-safe serialization.
- `android-native-real-api-plan`: connect Home, VideoList, and Feed to real backend repositories and remove sample data.
- `android-native-feed-player-plan`: replace feed scaffold with real vertical pager + ExoPlayer manager + progress seek + favorite rollback.
- `android-native-admin-plan`: admin login, dashboard, video data, graphic content, collect tasks, and direct parsing.
- `android-native-release-plan`: signing, R8, release APK verification, and uni-app replacement strategy.

This decomposition keeps each plan small enough to test and review independently.

---

## Self-Review Notes

- Spec coverage: this plan covers app skeleton, core URL/crypto/server logic, navigation shell, server/home UI, video list/player/feed shell. Full admin, real feed player completion, and release replacement are intentionally deferred to follow-up plans because the approved spec spans multiple subsystems.
- Placeholder scan: no unresolved markers are intentionally present. Follow-up plans are named explicitly instead of leaving ambiguous work.
- Type consistency: route names, package names, and model names match across tasks.
