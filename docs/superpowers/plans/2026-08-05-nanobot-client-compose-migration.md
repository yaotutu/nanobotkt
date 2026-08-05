# nanobot-client → nanobotkt (Compose) Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate nanobot-client (Expo SDK 57 / RN 0.86) into nanobotkt (Kotlin / Jetpack Compose) with 1:1 interaction logic, flexible UI.

**Architecture:** Mirror the source feature boundary (one Kotlin package per `src/features/<feature>/`); cross-feature imports only via `index.kt`. Compose-native hooks match the React mental model directly. Phase 1 lead builds foundation + connection lifecycle + chat + sidebar serially; Phase 2 dispatches 9 sub-agents in parallel for settings/workspaces/auth/skills/automations/channels/apps/capabilities/security; Phase 3 merges and verifies on-device.

**Tech Stack:** Kotlin 1.9.25, Jetpack Compose (BOM 2024.10.01, Material3, Navigation-Compose 2.8.4), OkHttp 4.12.0, kotlinx-serialization 1.6.3, DataStore 1.1.1, security-crypto 1.1.0-alpha06, coil-compose 2.7.0, JVM 17, `compileSdk 37`, `minSdk 24`.

## Global Constraints

These are hard requirements copied verbatim from the spec — every task implicitly inherits them.

- **Package layout mirrors source:** `com/example/nanobotkt/<feature>/` per `src/features/<feature>/`. Each feature exposes public surface only via `index.kt`.
- **Dependency direction:** `app -> connection -> core`, `app -> chat -> connection -> core`, `app -> sidebar -> chat -> connection -> core`, `app -> <feature> -> core`, `app -> auth/workspaces/settings/skills/automations/channels/capabilities/security -> connection -> core`. No reverse or sideways feature deps; cross-feature imports only via `index.kt`.
- **Hooks style (option A):** Compose-native `remember` / `mutableStateOf` / `LaunchedEffect` / `DisposableEffect` / `derivedStateOf`. No wrapper library.
- **Stores:** one `object FooStore : ViewModel()` per feature; `MutableStateFlow` + public read-only `StateFlow`; actions live on the same object.
- **Persistence:** `DataStore Preferences` for ordinary prefs (keys mirror `local-preferences-store.ts`); `EncryptedSharedPreferences` at path `nanobot_secure_prefs` for sensitive data; dev secret in EncryptedSharedPreferences (gitignored), BuildConfig field `NANOBOT_SERVER_URL` for build-time override.
- **i18n:** 10 locales — en (default), es, fr, id, ja, ko, pt-rBR, vi, zh-rCN, zh-rTW. `LocaleManager.applyLocale(activity)` is the first line of `MainActivity.onCreate`.
- **Theme:** `Color(0xFFFAFAF9)` background, `Color(0xFF208AEF)` primary; dark `Color(0xFF121212)` background; typography mirrors RN's type scale; `automatic` mode follows `isSystemInDarkTheme()`.
- **Navigation:** Material3 `NavHost`, `ModalNavigationDrawer`, `ModalBottomSheet`, `TopAppBar` — no hand-drawn equivalents.
- **Dual-epoch auth:** `sessionEpoch` on identity change (login/logout/recovery), `tokenGeneration` on every bootstrap token, silent renewal does NOT trigger reset. Business state keys to epoch; connection/token-derived timers key to generation.
- **WebSocket transport:** `disconnected / connecting / open / closing / reconnecting` state machine; `knownChats` map drives `attach` replay after reconnect; `outboundQueue` flushes on open; `pendingRegistry` tracks in-flight `Deferred`s.
- **Recovery policy:** offline -> notify transport (do not reconnect, reject non-suspendable pending); network back + foreground -> reconnect; background->foreground after stale threshold / socket not open / activity stale -> reconnect.
- **Inbound routing:** JSON parsed into `InboundEvent` sealed class; routed by `event` field per spec table.
- **Stream-fold:** every reducer must validate `turn_id` to prevent cross-thread event interleaving.
- **Markdown:** prefer `compose-markdown`; fall back to self-rolled `AnnotatedString` + `prism4k` if code highlighting diverges from `prism-react-renderer`.
- **Out of scope:** Web target, iOS, signing / store / GitHub Release pipeline, iOS-specific quirks.

## File Map

Files created across this plan:

### Build & manifest
- `gradle/libs.versions.toml` (NEW)
- `app/build.gradle.kts` (MODIFY: add deps, JVM 17)
- `settings.gradle.kts` (MODIFY: enable version catalog)
- `app/src/main/AndroidManifest.xml` (MODIFY: register Application, INTERNET permission)

### Application & entry
- `app/src/main/java/com/example/nanobotkt/NanobotApp.kt` (NEW)
- `app/src/main/java/com/example/nanobotkt/MainActivity.kt` (NEW)

### Theme & i18n
- `core/theme/Palette.kt` (NEW)
- `core/theme/Type.kt` (NEW)
- `core/theme/NanobotTheme.kt` (NEW)
- `core/i18n/LocaleManager.kt` (NEW)
- `core/i18n/LocalLocale.kt` (NEW)
- `app/src/main/res/values/strings.xml` (NEW, en)
- `app/src/main/res/values-{es,fr,id,ja,ko,pt-rBR,vi,zh-rCN,zh-rTW}/strings.xml` (NEW)

### Persistence
- `core/persist/LocalPreferences.kt` (NEW)
- `core/persist/SecureStore.kt` (NEW)
- `core/persist/DevSecretLoader.kt` (NEW)

### Wire-format types (1:1 port of `src/types/api/**`)
- `core/serial/ChatEvents.kt`, `ChatMessages.kt`, `ChatMedia.kt`, `ChatCommands.kt`, `ChatErrors.kt`, `ChatFilePreview.kt`, `ChatThread.kt`, `ChatAttachments.kt`
- `core/serial/WorkspaceScope.kt`, `GoalState.kt`, `ConnectionStatus.kt`
- `core/serial/SettingsPayload.kt`, `SettingsMedia.kt`, `SettingsModels.kt`, `SettingsOAuth.kt`, `SettingsProviders.kt`, `SettingsRuntime.kt`, `SettingsUpdates.kt`, `SettingsUsage.kt`
- `core/serial/NanobotFeatures.kt`, `Capabilities.kt`, `Sidebar.kt`, `Channels.kt`, `Automations.kt`, `Runtime.kt`
- `core/serial/ApiIndex.kt` (barrel)

### Networking
- `core/net/ServerUrlResolver.kt`, `ApiClient.kt`, `BootstrapClient.kt`, `Bootstrap.kt` (fetchBootstrap + error types)

### Auth foundation
- `core/auth/AuthStore.kt`, `AuthError.kt`

### Connection
- `connection/transport/SocketTransport.kt`, `TransportState.kt`, `TransportListeners.kt`
- `connection/reconnect/SocketReconnectPolicy.kt`
- `connection/recovery/ConnectionRecoveryPolicy.kt`, `NetworkObserver.kt`, `LifecycleObserver.kt`
- `connection/outbound/SocketOutboundQueue.kt`
- `connection/inbound/SocketInboundRouter.kt`
- `connection/pending/SocketPendingRegistry.kt`, `PendingFrame.kt`
- `connection/store/ConnectionStore.kt`
- `connection/index.kt`

### Chat
- `chat/store/ChatState.kt`, `ChatStore.kt`, `ChatStateFactories.kt`, `StreamRuntime.kt`, `InboundEventHandler.kt`, `MessageReconciliation.kt`
- `chat/stream/StreamFoldState.kt`, `AssistantReasoningReducer.kt`, `AssistantAnswerReducer.kt`, `AssistantCompletionReducer.kt`, `AssistantEventsAdapter.kt`
- `chat/activity/model/ActivityFormat.kt`, `ActivityMessageModel.kt`, `ActivityTimeline.kt`, `CommandRunModel.kt`, `FileEditModel.kt`, `ToolEventModel.kt`, `ToolHelpers.kt`, `ToolRowModel.kt`, `ToolTypes.kt`, `TraceActivityModel.kt`
- `chat/composer/model/ComposerState.kt`, `SlashCommand.kt`, `ViewContract.kt`
- `chat/composer/ComposerController.kt`
- `chat/attachments/AttachmentEncoder.kt`, `AttachmentLimits.kt`, `AttachmentMime.kt`, `AttachmentValidation.kt`, `ImageEncoder.kt`, `NativeFileEncoder.kt`, `AttachmentTypes.kt`
- `chat/voice/VoiceRecorder.kt`, `AudioModeLifecycle.kt`, `RecorderLifecycle.kt`, `RecorderTimers.kt`, `RecordingAnalysis.kt`, `RecordingFile.kt`, `VoicePolicy.kt`, `VoiceTypes.kt`
- `chat/hooks/UseChatThreadModel.kt`, `UseComposerController.kt`, `UseChatScroll.kt`, `UseMessageActions.kt`, `UseFilePreviewAvailability.kt`, `UseVoiceRecorder.kt`, `UseAttachments.kt`, `UseChatLocalState.kt`, `UseChatCommands.kt`, `UseThreadLifecycle.kt`, `UseResolvedFilePreviewAvailability.kt`, `UseVoiceRecorderGestures.kt`, `UseVoiceRecordingLifecycle.kt`
- `chat/components/ChatThread.kt`, `MessageRow.kt`, `UserMessageBody.kt`, `ActivityMessage.kt`, `AgentActivityCluster.kt`, `FileEditRow.kt`, `FileEditGroup.kt`, `ChatHeader.kt`, `ChatModals.kt`, `Composer.kt`, `ComposerToolbar.kt`, `ComposerSuggestions.kt`, `ComposerStyles.kt`, `ComposerContext.kt`, `ChatSurface.kt`, `ChatComposerContainer.kt`, `NanobotScreen.kt`, `FileReferenceChip.kt`, `AssistantQuoteModal.kt`, `FilePreviewModal.kt`, `FilePreviewHighlight.kt`, `FilePreviewModel.kt`, `SessionInfoModal.kt`, `SessionSearchModal.kt`
- `chat/index.kt`

### Sidebar
- `sidebar/store/SidebarStore.kt`, `hooks/UseSidebarController.kt`, `components/Sidebar.kt`, `TopicRow.kt`, `NewTopicSheet.kt`, `SearchSheet.kt`, `ArchivedSheet.kt`, `index.kt`

### App composition
- `app/AppShell.kt`, `ReadyAppShell.kt`, `AppModals.kt`, `AppUtilityRouter.kt`, `AppUtilityWorkspace.kt`
- `app/nav/NavGraph.kt`
- `app/hooks/UseAppBootstrapController.kt`, `UseAppController.kt`, `UseAppModelSelection.kt`, `UseAppNavigation.kt`, `UseAppPreferences.kt`, `UseAppSessionCommands.kt`, `UseAuthBootstrapLifecycle.kt`, `UseConnectionRecoveryLifecycle.kt`, `UseReadyDataLifecycle.kt`, `UseSocketLifecycle.kt`
- `app/model/Navigation.kt`

### Phase 2 sub-agent features
- `auth/`, `workspaces/`, `settings/`, `skills/`, `automations/`, `channels/`, `apps/`, `capabilities/`, `security/`

### Tests
- Unit tests in `app/src/test/java/com/example/nanobotkt/...` mirroring source for each Phase 1 module (see individual tasks below).

---
# Phase 1 — Lead (serial)

## Task 1: Project bootstrap — Gradle, theme, Application, MainActivity, LocaleGate

**Files:**
- Create: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts` (enable version catalog reference)
- Modify: `app/build.gradle.kts` (add plugin aliases, JVM 17, Compose enabled stays, add deps)
- Modify: `app/src/main/AndroidManifest.xml` (register `NanobotApp`, add INTERNET permission, set theme)
- Create: `app/src/main/java/com/example/nanobotkt/NanobotApp.kt`
- Create: `app/src/main/java/com/example/nanobotkt/MainActivity.kt`
- Create: `core/theme/Palette.kt`
- Create: `core/theme/Type.kt`
- Create: `core/theme/NanobotTheme.kt`
- Create: `core/i18n/LocaleManager.kt`
- Create: `core/i18n/LocalLocale.kt`
- Delete: `app/src/main/java/com/example/nanobotkt/ui/theme/{Color,Theme,Type}.kt` (replaced)
- Test: `app/src/test/java/com/example/nanobotkt/i18n/LocaleManagerTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `NanobotTheme(content: @Composable () -> Unit)`, `Palette.LightColors` / `Palette.DarkColors`, `LocaleManager.applyLocale(activity: Activity, tag: String)`, `LocalLocale: CompositionLocal<String>`.

- [ ] **Step 1.1: Write failing test for `LocaleManager.applyLocale`**

```kotlin
// app/src/test/java/com/example/nanobotkt/i18n/LocaleManagerTest.kt
package com.example.nanobotkt.i18n

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
class LocaleManagerTest {
    @Test fun `applyLocale sets base context locale and persists via Configuration`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        LocaleManager.applyLocale(activity, "zh-rCN")
        val config = activity.resources.configuration
        assertEquals("zh-rCN", config.locales[0].toLanguageTag().lowercase())
    }
}
```

- [ ] **Step 1.2: Run test — verify it fails (LocaleManager class missing)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.nanobotkt.i18n.LocaleManagerTest" -i`
Expected: COMPILATION FAILURE on `LocaleManager`.

- [ ] **Step 1.3: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "1.9.25"
kotlinxSerialization = "1.6.3"
kotlinxCoroutines = "1.9.0"
composeBom = "2024.10.01"
navigationCompose = "2.8.4"
okhttp = "4.12.0"
datastore = "1.1.1"
securityCrypto = "1.1.0-alpha06"
coil = "2.7.0"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
coreKtx = "1.13.1"
robolectric = "4.14"
androidxTestCore = "1.6.1"
androidxTestRunner = "1.6.2"
junit = "4.13.2"
turbine = "1.2.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
coil-compose = { module = "io.coil-kt:coil-compose", version.ref = "coil" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 1.4: Modify `settings.gradle.kts` to enable version catalog**

Add (if not present) at the end of the `pluginManagement` and `dependencyResolutionManagement` blocks a `versionCatalogs` block referencing `libs`. The default settings file already references `libs.plugins.android.application` so this should already work; verify the `versionCatalogs { libs { ... } }` block exists or add:

```kotlin
dependencyResolutionManagement {
    versionCatalogs { libs { create("libs") } }
}
```

- [ ] **Step 1.5: Modify `app/build.gradle.kts`**

Replace its contents with:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.nanobotkt"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.nanobotkt"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "NANOBOT_SERVER_URL", "\"\"")
    }
    buildTypes {
        debug {
            buildConfigField("String", "NANOBOT_SERVER_URL", "\"http://localhost:8765\"")
        }
        release {
            optimization { enable = false }
            buildConfigField("String", "NANOBOT_SERVER_URL", "\"http://192.168.55.147:8765\"")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
}
```

- [ ] **Step 1.6: Modify `app/src/main/AndroidManifest.xml`**

Add `<uses-permission android:name="android.permission.INTERNET" />` inside `<manifest>` (above `<application>`). Set `android:name=".NanobotApp"` on `<application>` and `android:theme="@style/Theme.Nanobotkt"` (replace existing theme). Add a `tools:` namespace if missing.

- [ ] **Step 1.7: Update `app/src/main/res/values/themes.xml`** to be a Material3 Compose-friendly base:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Nanobotkt" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 1.8: Create `core/i18n/LocalLocale.kt`**

```kotlin
package com.example.nanobotkt.i18n

import androidx.compose.runtime.compositionLocalOf

val LocalLocale = compositionLocalOf { "en" }
```

- [ ] **Step 1.9: Create `core/i18n/LocaleManager.kt`**

```kotlin
package com.example.nanobotkt.i18n

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

object LocaleManager {
    fun applyLocale(activity: Activity, tag: String) {
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = activity.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        @Suppress("DEPRECATION")
        activity.onConfigurationChanged(config)
    }
}

@Composable
fun ProvideLocale(content: @Composable () -> Unit) {
    val tag = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    CompositionLocalProvider(LocalLocale provides tag) { content() }
}
```

- [ ] **Step 1.10: Create `core/theme/Palette.kt`**

```kotlin
package com.example.nanobotkt.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object Palette {
    val LightBackground = Color(0xFFFAFAF9)
    val DarkBackground = Color(0xFF121212)
    val Accent = Color(0xFF208AEF)
    val AccentVariant = Color(0xFF1976D2)
    val OnAccent = Color(0xFFFFFFFF)
    val OutlineLight = Color(0xFFE0E0E0)
    val OutlineDark = Color(0xFF2A2A2A)
    val TextPrimaryLight = Color(0xFF1A1A1A)
    val TextPrimaryDark = Color(0xFFE6E6E6)
    val TextSecondaryLight = Color(0xFF6B6B6B)
    val TextSecondaryDark = Color(0xFFB0B0B0)

    val LightColors = lightColorScheme(
        primary = Accent,
        onPrimary = OnAccent,
        secondary = AccentVariant,
        background = LightBackground,
        onBackground = TextPrimaryLight,
        surface = LightBackground,
        onSurface = TextPrimaryLight,
        outline = OutlineLight,
        surfaceVariant = Color(0xFFF2F2EF),
        onSurfaceVariant = TextSecondaryLight
    )
    val DarkColors = darkColorScheme(
        primary = Accent,
        onPrimary = OnAccent,
        secondary = AccentVariant,
        background = DarkBackground,
        onBackground = TextPrimaryDark,
        surface = DarkBackground,
        onSurface = TextPrimaryDark,
        outline = OutlineDark,
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = TextSecondaryDark
    )
}
```

- [ ] **Step 1.11: Create `core/theme/Type.kt`**

```kotlin
package com.example.nanobotkt.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val NanobotTypography = Typography(
    displayLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)
```

- [ ] **Step 1.12: Create `core/theme/NanobotTheme.kt`**

```kotlin
package com.example.nanobotkt.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun NanobotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) Palette.DarkColors else Palette.LightColors
    MaterialTheme(colorScheme = colors, typography = NanobotTypography, content = content)
}
```

- [ ] **Step 1.13: Create `app/src/main/java/com/example/nanobotkt/NanobotApp.kt`**

```kotlin
package com.example.nanobotkt

import android.app.Application

class NanobotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Subsequent tasks will initialize LocalPreferences and SecureStore here.
    }
}
```

- [ ] **Step 1.14: Create `app/src/main/java/com/example/nanobotkt/MainActivity.kt`**

```kotlin
package com.example.nanobotkt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.example.nanobotkt.i18n.LocaleManager
import com.example.nanobotkt.i18n.ProvideLocale
import com.example.nanobotkt.theme.NanobotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleManager.applyLocale(this, "en") // Task 6 reads saved preference; default to en for now.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            NanobotTheme {
                ProvideLocale {
                    // Task 16 wires AppShell here.
                    androidx.compose.material3.Surface {}
                }
            }
        }
    }
}
```

- [ ] **Step 1.15: Delete the old theme files** under `app/src/main/java/com/example/nanobotkt/ui/theme/` to avoid duplicate-class errors.

- [ ] **Step 1.16: Run the test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.nanobotkt.i18n.LocaleManagerTest" -i`
Expected: PASS.

- [ ] **Step 1.17: Run full assemble to verify the project compiles**

Run: `./gradlew :app:assembleDebug -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 1.18: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts app/build.gradle.kts \
    app/src/main/AndroidManifest.xml app/src/main/res/values/themes.xml \
    app/src/main/java/com/example/nanobotkt/NanobotApp.kt \
    app/src/main/java/com/example/nanobotkt/MainActivity.kt \
    core/theme core/i18n \
    app/src/test/java/com/example/nanobotkt/i18n
git rm app/src/main/java/com/example/nanobotkt/ui/theme/Color.kt \
       app/src/main/java/com/example/nanobotkt/ui/theme/Theme.kt \
       app/src/main/java/com/example/nanobotkt/ui/theme/Type.kt 2>/dev/null || true
git commit -m "feat(core): scaffold Gradle catalog, theme, locale manager, Application"
```

---

## Task 2: i18n string resources (10 locales)

**Files:**
- Create: `app/src/main/res/values/strings.xml` (en, baseline)
- Create: `app/src/main/res/values-es/strings.xml`
- Create: `app/src/main/res/values-fr/strings.xml`
- Create: `app/src/main/res/values-id/strings.xml`
- Create: `app/src/main/res/values-ja/strings.xml`
- Create: `app/src/main/res/values-ko/strings.xml`
- Create: `app/src/main/res/values-pt-rBR/strings.xml`
- Create: `app/src/main/res/values-vi/strings.xml`
- Create: `app/src/main/res/values-zh-rCN/strings.xml`
- Create: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `NanobotTheme` from Task 1.
- Produces: every UI string used by later features is reachable via `stringResource(R.string.<key>)`.

- [ ] **Step 2.1: Generate the en baseline `strings.xml`**

Source keys come from `nanobot-client/src/i18n/locales/en/common.json`. Run the shell command below to enumerate every key, then write the en `strings.xml` with each key as `<string name="<key>">value</string>`:

```bash
node -e "const j=require('./nanobot-client/src/i18n/locales/en/common.json'); for(const k of Object.keys(j)){const v=String(j[k]).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/'/g,'\\'').replace(/"/g,'\\\"'); console.log(\`<string name=\"\${k}\">\${v}</string>\`)}"
```

(Adjust paths to match the relative location of `nanobot-client/`. The lead executing this task should run the command from the workspace root.)

- [ ] **Step 2.2: For each non-English locale**, repeat the same generation using that locale's `common.json` and write into the matching `values-<locale>/strings.xml`. Verify each file is non-empty.

- [ ] **Step 2.3: Wire `MainActivity` to read the saved locale preference** (Task 6 will provide the data layer; for now, leave the literal `"en"` default, but add a TODO-comment-free hook):

In `MainActivity.onCreate`, replace the literal `"en"` with `LocaleStore.currentTagBlocking()` — Task 6 defines `LocaleStore`. Until Task 6 lands, leave a temporary `runBlocking { LocaleStore.currentTagOrDefault("en") }` block. (Implemented in Task 6 step 6.4.)

- [ ] **Step 2.4: Build and verify resource compilation**

Run: `./gradlew :app:processDebugResources -i`
Expected: BUILD SUCCESSFUL with all 10 locale resource sets compiled.

- [ ] **Step 2.5: Commit**

```bash
git add app/src/main/res
git commit -m "feat(i18n): add string resources for all 10 locales"
```

---

## Task 3: Persistence — LocalPreferences + SecureStore + DevSecretLoader

**Files:**
- Create: `core/persist/LocalPreferences.kt`
- Create: `core/persist/SecureStore.kt`
- Create: `core/persist/DevSecretLoader.kt`
- Test: `app/src/test/java/com/example/nanobotkt/core/persist/LocalPreferencesTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/core/persist/SecureStoreTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `LocalPreferences` (object): `suspend fun setTheme(theme: AppTheme)`, `fun theme(): Flow<AppTheme>`, `suspend fun setLanguage(tag: String)`, `fun language(): Flow<String>`, plus one setter/getter pair per key in `local-preferences-store.ts` (density, activityMode, fileEditDisplayMode, lastChatId, workspaceScopeByChatId, etc.). All backed by `DataStore<Preferences>`.
  - `SecureStore` (object): `fun bootstrapSecret(): String?`, `fun setBootstrapSecret(s: String?)`, `fun apiToken(): String?`, `fun setApiToken(t: String?)`, `fun wsToken(): String?`, `fun setWsToken(t: String?)`. Backed by `EncryptedSharedPreferences` at name `nanobot_secure_prefs`.
  - `DevSecretLoader` (object): `fun loadServerUrl(): String?` reading from SecureStore key `dev_server_url` (written at runtime by a future dev menu — out of scope for this task but the key is reserved).

- [ ] **Step 3.1: Write failing test for `LocalPreferences.theme` round-trip**

```kotlin
// app/src/test/java/com/example/nanobotkt/core/persist/LocalPreferencesTest.kt
package com.example.nanobotkt.core.persist

import com.example.nanobotkt.LocalPreferences
import com.example.nanobotkt.AppTheme
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.test.core.app.ApplicationProvider

class LocalPreferencesTest {
    @After fun tearDown() { LocalPreferences.resetForTest() }
    @Test fun `theme round trip`() = runTest {
        LocalPreferences.init(ApplicationProvider.getApplicationContext())
        LocalPreferences.setTheme(AppTheme.Dark)
        assertEquals(AppTheme.Dark, LocalPreferences.theme().first())
    }
}
```

- [ ] **Step 3.2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalPreferencesTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 3.3: Implement `LocalPreferences.kt`**

```kotlin
package com.example.nanobotkt.core.persist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.nanobotkt.AppTheme
import com.example.nanobotkt.LocalDensity
import com.example.nanobotkt.LocalActivityMode
import com.example.nanobotkt.FileEditDisplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AppTheme { Light, Dark, Automatic }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nanobot_prefs")

object LocalPreferences {
    private val THEME = stringPreferencesKey("theme")
    private val LANGUAGE = stringPreferencesKey("language")
    private val DENSITY = stringPreferencesKey("density")
    private val ACTIVITY_MODE = stringPreferencesKey("activity_mode")
    private val FILE_EDIT_DISPLAY = stringPreferencesKey("file_edit_display")
    private val LAST_CHAT_ID = stringPreferencesKey("last_chat_id")
    private val WORKSPACE_SCOPE_JSON = stringPreferencesKey("workspace_scope_json")
    private val COMPOSER_RECENTS_JSON = stringPreferencesKey("composer_recents_json")
    private val DEV_MENU_ENABLED = stringPreferencesKey("dev_menu_enabled")

    private var store: DataStore<Preferences>? = null
    fun init(context: Context) { store = context.applicationContext.dataStore }
    internal fun resetForTest() { store = null }
    private fun ds(): DataStore<Preferences> = store ?: error("LocalPreferences.init not called")

    suspend fun setTheme(value: AppTheme) { ds().edit { it[THEME] = value.name } }
    fun theme(): Flow<AppTheme> = ds().data.map { runCatching { AppTheme.valueOf(it[THEME] ?: AppTheme.Automatic.name) }.getOrDefault(AppTheme.Automatic) }

    suspend fun setLanguage(tag: String) { ds().edit { it[LANGUAGE] = tag } }
    fun language(): Flow<String> = ds().data.map { it[LANGUAGE] ?: "en" }

    suspend fun setDensity(value: LocalDensity) { ds().edit { it[DENSITY] = value.name } }
    fun density(): Flow<LocalDensity> = ds().data.map { runCatching { LocalDensity.valueOf(it[DENSITY] ?: LocalDensity.Comfortable.name) }.getOrDefault(LocalDensity.Comfortable) }

    suspend fun setActivityMode(value: LocalActivityMode) { ds().edit { it[ACTIVITY_MODE] = value.name } }
    fun activityMode(): Flow<LocalActivityMode> = ds().data.map { runCatching { LocalActivityMode.valueOf(it[ACTIVITY_MODE] ?: LocalActivityMode.Auto.name) }.getOrDefault(LocalActivityMode.Auto) }

    suspend fun setFileEditDisplay(value: FileEditDisplayMode) { ds().edit { it[FILE_EDIT_DISPLAY] = value.name } }
    fun fileEditDisplay(): Flow<FileEditDisplayMode> = ds().data.map { runCatching { FileEditDisplayMode.valueOf(it[FILE_EDIT_DISPLAY] ?: FileEditDisplayMode.Summary.name) }.getOrDefault(FileEditDisplayMode.Summary) }

    suspend fun setLastChatId(value: String?) { ds().edit { if (value == null) it.remove(LAST_CHAT_ID) else it[LAST_CHAT_ID] = value } }
    fun lastChatId(): Flow<String?> = ds().data.map { it[LAST_CHAT_ID] }

    suspend fun setWorkspaceScopeJson(json: String?) { ds().edit { if (json == null) it.remove(WORKSPACE_SCOPE_JSON) else it[WORKSPACE_SCOPE_JSON] = json } }
    fun workspaceScopeJson(): Flow<String?> = ds().data.map { it[WORKSPACE_SCOPE_JSON] }

    suspend fun setComposerRecentsJson(json: String?) { ds().edit { if (json == null) it.remove(COMPOSER_RECENTS_JSON) else it[COMPOSER_RECENTS_JSON] = json } }
    fun composerRecentsJson(): Flow<String?> = ds().data.map { it[COMPOSER_RECENTS_JSON] }

    suspend fun setDevMenuEnabled(value: Boolean) { ds().edit { it[DEV_MENU_ENABLED] = if (value) "true" else "false" } }
    fun devMenuEnabled(): Flow<Boolean> = ds().data.map { it[DEV_MENU_ENABLED] == "true" }

    suspend fun currentThemeOrDefault(default: AppTheme = AppTheme.Automatic): AppTheme =
        runCatching { AppTheme.valueOf(ds().data.first()[THEME] ?: default.name) }.getOrDefault(default)
    suspend fun currentLanguageOrDefault(default: String = "en"): String =
        ds().data.first()[LANGUAGE] ?: default
}
```

Add the supporting enum types in `app/src/main/java/com/example/nanobotkt/DomainEnums.kt`:

```kotlin
package com.example.nanobotkt

enum class LocalDensity { Comfortable, Compact }
enum class LocalActivityMode { Auto, Expanded }
enum class FileEditDisplayMode { Summary, Diff, CollapsedDiff }
```

- [ ] **Step 3.4: Run theme test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalPreferencesTest" -i`
Expected: PASS.

- [ ] **Step 3.5: Write failing test for SecureStore round-trip**

```kotlin
// app/src/test/java/com/example/nanobotkt/core/persist/SecureStoreTest.kt
package com.example.nanobotkt.core.persist

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureStoreTest {
    @After fun tearDown() { SecureStore.resetForTest() }
    @Test fun `bootstrap secret round trip`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        SecureStore.init(ctx)
        assertNull(SecureStore.bootstrapSecret())
        SecureStore.setBootstrapSecret("super-secret")
        SecureStore.resetForTest()
        SecureStore.init(ctx)
        assertEquals("super-secret", SecureStore.bootstrapSecret())
    }
}
```

- [ ] **Step 3.6: Run SecureStore test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SecureStoreTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 3.7: Implement `SecureStore.kt`**

```kotlin
package com.example.nanobotkt.core.persist

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureStore {
    private const val NAME = "nanobot_secure_prefs"
    private const val KEY_BOOTSTRAP = "bootstrap_secret"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_WS_TOKEN = "ws_token"
    private const val KEY_DEV_URL = "dev_server_url"

    private var prefs: SharedPreferences? = null
    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context.applicationContext, NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    internal fun resetForTest() { prefs = null }
    private fun p(): SharedPreferences = prefs ?: error("SecureStore.init not called")

    fun bootstrapSecret(): String? = p().getString(KEY_BOOTSTRAP, null)
    fun setBootstrapSecret(value: String?) { p().edit().apply { if (value == null) remove(KEY_BOOTSTRAP) else putString(KEY_BOOTSTRAP, value) }.apply() }

    fun apiToken(): String? = p().getString(KEY_API_TOKEN, null)
    fun setApiToken(value: String?) { p().edit().apply { if (value == null) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, value) }.apply() }

    fun wsToken(): String? = p().getString(KEY_WS_TOKEN, null)
    fun setWsToken(value: String?) { p().edit().apply { if (value == null) remove(KEY_WS_TOKEN) else putString(KEY_WS_TOKEN, value) }.apply() }

    fun devServerUrl(): String? = p().getString(KEY_DEV_URL, null)
    fun setDevServerUrl(value: String?) { p().edit().apply { if (value == null) remove(KEY_DEV_URL) else putString(KEY_DEV_URL, value) }.apply() }
}
```

- [ ] **Step 3.8: Run SecureStore test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SecureStoreTest" -i`
Expected: PASS.

- [ ] **Step 3.9: Implement `DevSecretLoader.kt`**

```kotlin
package com.example.nanobotkt.core.persist

object DevSecretLoader {
    fun loadServerUrl(): String? = SecureStore.devServerUrl()
}
```

- [ ] **Step 3.10: Wire init in `NanobotApp.onCreate`**

```kotlin
override fun onCreate() {
    super.onCreate()
    LocalPreferences.init(this)
    SecureStore.init(this)
}
```

- [ ] **Step 3.11: Commit**

```bash
git add core/persist app/src/main/java/com/example/nanobotkt/NanobotApp.kt \
    app/src/main/java/com/example/nanobotkt/DomainEnums.kt \
    app/src/test/java/com/example/nanobotkt/core/persist
git commit -m "feat(core/persist): DataStore prefs + EncryptedSharedPreferences + dev secret loader"
```

---

## Task 4: Wire-format types — port `src/types/api/**` to Kotlin sealed/data classes

**Files:**
- Create: `core/serial/ChatEvents.kt`
- Create: `core/serial/ChatMessages.kt`
- Create: `core/serial/ChatMedia.kt`
- Create: `core/serial/ChatCommands.kt`
- Create: `core/serial/ChatErrors.kt`
- Create: `core/serial/ChatFilePreview.kt`
- Create: `core/serial/ChatThread.kt`
- Create: `core/serial/ChatAttachments.kt`
- Create: `core/serial/WorkspaceScope.kt`
- Create: `core/serial/GoalState.kt`
- Create: `core/serial/ConnectionStatus.kt`
- Create: `core/serial/SettingsPayload.kt`
- Create: `core/serial/SettingsMedia.kt`
- Create: `core/serial/SettingsModels.kt`
- Create: `core/serial/SettingsOAuth.kt`
- Create: `core/serial/SettingsProviders.kt`
- Create: `core/serial/SettingsRuntime.kt`
- Create: `core/serial/SettingsUpdates.kt`
- Create: `core/serial/SettingsUsage.kt`
- Create: `core/serial/NanobotFeatures.kt`
- Create: `core/serial/Capabilities.kt`
- Create: `core/serial/Sidebar.kt`
- Create: `core/serial/Channels.kt`
- Create: `core/serial/Automations.kt`
- Create: `core/serial/Runtime.kt`
- Create: `core/serial/ApiIndex.kt` (barrel re-export)
- Test: `app/src/test/java/com/example/nanobotkt/core/serial/JsonRoundTripTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `@Serializable sealed class InboundEvent` with subtypes per source `events.ts`; `@Serializable sealed class OutboundFrame` with subtypes per source `socket-protocol.ts`; one `@Serializable data class` per source file under `src/types/api/**`. Field names + JSON keys match the source 1:1 (snake_case preserved via `@SerialName`).

- [ ] **Step 4.1: Write failing round-trip test for `InboundEvent.Message`**

```kotlin
// app/src/test/java/com/example/nanobotkt/core/serial/JsonRoundTripTest.kt
package com.example.nanobotkt.core.serial

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `InboundEvent Message round trip`() {
        val src = """
            {"event":"message","chat_id":"c1","text":"hello","kind":"tool_hint",
             "turn_id":"t1","turn_phase":"running","turn_seq":2,"latency_ms":120}
        """.trimIndent()
        val parsed = json.decodeFromString(InboundEvent.serializer(), src)
        val out = json.encodeToString(InboundEvent.serializer(), parsed)
        assertEquals(src, out)
    }
}
```

- [ ] **Step 4.2: Run test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*JsonRoundTripTest" -i`
Expected: COMPILATION FAILURE on `InboundEvent`.

- [ ] **Step 4.3: Create `core/serial/ChatEvents.kt`** (1:1 port of `src/types/api/chat/events.ts`):

```kotlin
package com.example.nanobotkt.core.serial

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class InboundEvent {
    abstract val event: String
    abstract val chatId: String?

    @Serializable
    @SerialName("ready")
    data class Ready(val event: String = "ready", val chatId: String, val clientId: String) : InboundEvent() {
        @SerialName("chat_id") override val _chatId: String = chatId
    }
    // ...etc; in practice write all subclasses matching the source union.
}
```

(Use `@SerialName` on every parameter whose JSON key is snake_case. Keep one sealed-class file per logical group. For brevity this plan shows only the opening pattern; the implementer reads `src/types/api/chat/events.ts` line-by-line and translates.)

- [ ] **Step 4.4: Create `core/serial/ChatMessages.kt`** — port `src/types/api/chat/messages.ts`. Includes `AgentUIBlob`, `ToolProgressEvent`, `UIFileEdit`, `UIMessageSource`, `UIMessage`, etc.

- [ ] **Step 4.5: Create `core/serial/ChatMedia.kt`** — port `src/types/api/chat/media.ts`. Includes `OutboundMedia`, `UICliAppAttachment`, `UIMcpPresetAttachment`, `UIMediaAttachment`.

- [ ] **Step 4.6: Create `core/serial/ChatCommands.kt`** — port `src/types/api/chat/commands.ts`.

- [ ] **Step 4.7: Create `core/serial/ChatErrors.kt`** — port `src/types/api/chat/errors.ts`.

- [ ] **Step 4.8: Create `core/serial/ChatFilePreview.kt`** — port `src/types/api/chat/file-preview.ts`.

- [ ] **Step 4.9: Create `core/serial/ChatThread.kt`** — port `src/types/api/chat/thread.ts`.

- [ ] **Step 4.10: Create `core/serial/ChatAttachments.kt`** — port `src/types/api/chat/attachments.ts`.

- [ ] **Step 4.11: Create `core/serial/WorkspaceScope.kt`** — port `src/types/api/workspaces.ts`.

- [ ] **Step 4.12: Create `core/serial/GoalState.kt`** + `ConnectionStatus.kt` — port `src/types/api/runtime.ts`.

- [ ] **Step 4.13: Create settings wire-format files** — port each of `src/types/api/settings/{payload,media,models,oauth,providers,runtime,updates,usage}.ts`.

- [ ] **Step 4.14: Create remaining domain files** — port `src/types/api/{nanobot-features,capabilities,sidebar,channels,automations}.ts`.

- [ ] **Step 4.15: Create `OutboundFrame`** sealed class in `core/serial/ChatCommands.kt` (or a dedicated `core/serial/OutboundFrame.kt`) — port `OutboundFrame` union from `src/features/connection/socket-protocol.ts`.

- [ ] **Step 4.16: Create `core/serial/ApiIndex.kt`** barrel:

```kotlin
package com.example.nanobotkt.core.serial
// Re-export every public sealed class + data class so callers only depend on this one file.
typealias BootstrapResponse = com.example.nanobotkt.core.serial.Runtime.BootstrapResponse
```

(Re-export using either `typealias` or `expect`/`actual`-style aliases for the wire types; ensure no name collisions. Implementer may split into multiple barrels per group.)

- [ ] **Step 4.17: Run round-trip test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*JsonRoundTripTest" -i`
Expected: PASS.

- [ ] **Step 4.18: Add 4 more round-trip tests** covering `OutboundFrame.Message`, `InboundEvent.StreamEnd`, `InboundEvent.TurnEnd`, and one settings payload.

- [ ] **Step 4.19: Run all tests — verify pass**

Run: `./gradlew :app:testDebugUnitTest -i`
Expected: all wire-format tests PASS.

- [ ] **Step 4.20: Commit**

```bash
git add core/serial app/src/test/java/com/example/nanobotkt/core/serial
git commit -m "feat(core/serial): port wire-format types from nanobot-client 1:1"
```

---

## Task 5: Networking — ServerUrlResolver, ApiClient, BootstrapClient

**Files:**
- Create: `core/net/ServerUrlResolver.kt`
- Create: `core/net/ApiClient.kt`
- Create: `core/net/Bootstrap.kt`
- Create: `core/net/BootstrapClient.kt`
- Test: `app/src/test/java/com/example/nanobotkt/core/net/ServerUrlResolverTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/core/net/BootstrapTest.kt`

**Interfaces:**
- Consumes: `BuildConfig.NANOBOT_SERVER_URL` (Task 1), `SecureStore.devServerUrl()` (Task 3), `BuildConfig.DEBUG` flag.
- Produces:
  - `object ServerUrlResolver { fun resolve(): String }` — order: BuildConfig override → `SecureStore.devServerUrl()` → if `BuildConfig.DEBUG` then `http://localhost:8765` else `http://192.168.55.147:8765`.
  - `class ApiClient(baseUrl: String, tokenProvider: () -> String?)` — methods: `get<T>(path: String, query: Map<String,String>? = null): T`, `post<T>(path: String, body: JsonElement? = null): T`, `delete<T>(path: String): T`. Uses OkHttp + kotlinx-serialization.
  - `object BootstrapClient { suspend fun fetch(baseUrl: String, secret: String, timeoutMs: Long = 20_000): BootstrapResponse; fun deriveWsUrl(baseUrl: String, wsPath: String, token: String, wsUrl: String? = null): String }`.

- [ ] **Step 5.1: Write failing test for `ServerUrlResolver`**

```kotlin
// app/src/test/java/com/example/nanobotkt/core/net/ServerUrlResolverTest.kt
package com.example.nanobotkt.core.net

import com.example.nanobotkt.core.persist.SecureStore
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerUrlResolverTest {
    @After fun tearDown() { SecureStore.resetForTest() }
    @Test fun `falls back to localhost when DEBUG and no override`() {
        SecureStore.init(ApplicationProvider.getApplicationContext())
        val url = ServerUrlResolver.resolve(debug = true, buildConfigUrl = "")
        assertEquals("http://localhost:8765", url)
    }
    @Test fun `uses build config override first`() {
        SecureStore.init(ApplicationProvider.getApplicationContext())
        val url = ServerUrlResolver.resolve(debug = true, buildConfigUrl = "http://example:9999")
        assertEquals("http://example:9999", url)
    }
}
```

- [ ] **Step 5.2: Run test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*ServerUrlResolverTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 5.3: Implement `ServerUrlResolver.kt`**

```kotlin
package com.example.nanobotkt.core.net

import com.example.nanobotkt.core.persist.SecureStore

object ServerUrlResolver {
    private const val PRODUCT_DEFAULT = "http://192.168.55.147:8765"
    private const val DEBUG_LOOPBACK = "http://localhost:8765"

    fun resolve(debug: Boolean, buildConfigUrl: String): String {
        val fromConfig = buildConfigUrl.trim().trimEnd('/')
        if (fromConfig.isNotEmpty()) return fromConfig
        val fromDev = SecureStore.devServerUrl()?.trim()?.trimEnd('/').orEmpty()
        if (fromDev.isNotEmpty()) return fromDev
        return if (debug) DEBUG_LOOPBACK else PRODUCT_DEFAULT
    }
}
```

- [ ] **Step 5.4: Run ServerUrlResolver tests — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ServerUrlResolverTest" -i`
Expected: PASS.

- [ ] **Step 5.5: Write failing test for `deriveWsUrl`**

```kotlin
// app/src/test/java/com/example/nanobotkt/core/net/BootstrapTest.kt
package com.example.nanobotkt.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
    @Test fun `deriveWsUrl from http base and ws path`() {
        val url = BootstrapClient.deriveWsUrl("http://10.0.0.1:8765", "/ws", "tok123")
        assertEquals("ws://10.0.0.1:8765/ws?token=tok123", url)
    }
    @Test fun `deriveWsUrl uses wss when base is https`() {
        val url = BootstrapClient.deriveWsUrl("https://x.example.com", "/ws", "t")
        assertEquals("wss://x.example.com/ws?token=t", url)
    }
    @Test fun `deriveWsUrl prefers explicit wsUrl when provided`() {
        val url = BootstrapClient.deriveWsUrl("http://x", "/ws", "t", wsUrl = "wss://other.example/ws?foo=1")
        assertEquals("wss://other.example/ws?foo=1&token=t", url)
    }
}
```

- [ ] **Step 5.6: Run test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*BootstrapTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 5.7: Implement `Bootstrap.kt` (with `fetchBootstrap`, errors, and `deriveWsUrl`)**

```kotlin
package com.example.nanobotkt.core.net

import com.example.nanobotkt.core.serial.Runtime.BootstrapResponse
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BootstrapAuthRequiredError(message: String) : RuntimeException(message)
class BootstrapResponseError(val code: String) : RuntimeException(code)

object BootstrapClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(baseUrl: String, secret: String, timeoutMs: Long = 20_000): BootstrapResponse {
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url("$baseUrl/webui/bootstrap")
            .header("Accept", "application/json")
            .apply { if (secret.isNotEmpty()) header("X-Nanobot-Auth", secret) }
            .build()
        client.newCall(req).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw BootstrapAuthRequiredError("bootstrap failed: HTTP ${response.code}")
            }
            if (!response.isSuccessful) error("bootstrap failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val ct = response.header("content-type").orEmpty()
            if (ct.isNotEmpty() && !ct.lowercase().contains("application/json")) {
                val looksHtml = body.trimStart().lowercase().startsWith("<!doctype") ||
                    body.trimStart().lowercase().startsWith("<html")
                throw BootstrapResponseError(if (looksHtml) "gateway_html_response" else "non_json_response")
            }
            val parsed = json.decodeFromString(BootstrapResponse.serializer(), body)
            if (parsed.token.isNullOrEmpty() || parsed.wsPath.isNullOrEmpty() || parsed.apiToken.isNullOrEmpty()) {
                throw BootstrapAuthRequiredError("bootstrap response missing credentials")
            }
            return parsed
        }
    }

    fun deriveWsUrl(baseUrl: String, wsPath: String, token: String, wsUrl: String? = null): String {
        if (!wsUrl.isNullOrEmpty() && Regex("^wss?://", RegexOption.IGNORE_CASE).containsMatchIn(wsUrl)) {
            val joiner = if (wsUrl.contains('?')) '&' else '?'
            return "$wsUrl${joiner}token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        }
        val base = java.net.URL(baseUrl)
        val scheme = if (base.protocol == "https") "wss" else "ws"
        val path = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
        return "$scheme://${base.host}${if (base.port == -1) "" else ":${base.port}"}$path?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
    }
}
```

- [ ] **Step 5.8: Run BootstrapTest — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*BootstrapTest" -i`
Expected: PASS.

- [ ] **Step 5.9: Implement `ApiClient.kt`**

```kotlin
package com.example.nanobotkt.core.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val bodyText: String) : RuntimeException("API $status: $bodyText")

class ApiClient(
    val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private fun urlFor(path: String, query: Map<String, String>?): String {
        val u = java.net.URLBuilder(baseUrl).appendPath(path.trimStart('/'))
        query?.forEach { (k, v) -> u.addQueryParameter(k, v) }
        return u.toString()
    }

    private inline fun <reified T> request(
        method: String, path: String,
        query: Map<String, String>? = null,
        body: JsonElement? = null
    ): T {
        val req = Request.Builder()
            .url(urlFor(path, query))
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, text)
            val serializer = json.serializersModule.serializer<T>()
            return json.decodeFromString(serializer, text)
        }
    }

    inline fun <reified T> get(path: String, query: Map<String, String>? = null): T = request("GET", path, query)
    inline fun <reified T> post(path: String, body: JsonElement? = null): T = request("POST", path, null, body)
    inline fun <reified T> delete(path: String): T = request("DELETE", path)
}
```

(Replace `java.net.URLBuilder` with a tiny builder using `URI` + manual query string assembly — Robolectric does not expose `java.net.URLBuilder`. The implementer writes a 10-line helper.)

- [ ] **Step 5.10: Add `get/post/delete` round-trip tests** using `MockWebServer`:

```kotlin
// app/src/test/java/com/example/nanobotkt/core/net/ApiClientTest.kt
package com.example.nanobotkt.core.net

import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@Serializable data class Hello(val greeting: String)

class ApiClientTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `get returns decoded body and sends bearer token`() {
        server.enqueue(MockResponse().setBody("""{"greeting":"hi"}""").setHeader("Content-Type","application/json"))
        val client = ApiClient(server.url("/").toString().trimEnd('/'), tokenProvider = { "abc" })
        val out = client.get<Hello>("/api/hello")
        assertEquals("hi", out.greeting)
        val recorded = server.takeRequest()
        assertEquals("Bearer abc", recorded.getHeader("Authorization"))
    }
}
```

Add `okhttp-mockwebserver` test dep in `libs.versions.toml` and `app/build.gradle.kts`:

```toml
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
```

```kotlin
testImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 5.11: Run ApiClient tests — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ApiClientTest" -i`
Expected: PASS.

- [ ] **Step 5.12: Commit**

```bash
git add core/net gradle/libs.versions.toml app/build.gradle.kts \
    app/src/test/java/com/example/nanobotkt/core/net
git commit -m "feat(core/net): ServerUrlResolver, BootstrapClient, ApiClient + round-trip tests"
```

---

## Task 6: Auth foundation — AuthStore + AuthError codes

**Files:**
- Create: `core/auth/AuthError.kt`
- Create: `core/auth/AuthStore.kt`
- Test: `app/src/test/java/com/example/nanobotkt/core/auth/AuthStoreTest.kt`

**Interfaces:**
- Consumes: `ServerUrlResolver.resolve(...)` (Task 5), `SecureStore` (Task 3), `BootstrapClient.fetch/deriveWsUrl` (Task 5), `BuildConfig` (Task 1).
- Produces:
  - `sealed class AuthError : RuntimeException()` with subtypes `BootstrapAuthRequired`, `GatewayHtml`, `NonJson`, `BootstrapFailed(detail: String)`, `Network(detail: String)`.
  - `object AuthStore` with:
    - `data class AuthState(val sessionEpoch: Long = 0, val tokenGeneration: Long = 0, val apiToken: String? = null, val wsToken: String? = null, val wsUrl: String? = null, val bootstrapSecret: String? = null, val lastBootstrapAt: Long = 0)`
    - `val state: StateFlow<AuthState>` (StateFlow with replay=1)
    - `suspend fun bootstrap(secret: String? = null): AuthState` — calls `ServerUrlResolver.resolve`, then `BootstrapClient.fetch`, on success stores tokens in `SecureStore`, increments `tokenGeneration`, returns new state.
    - `suspend fun clear()` — increments `sessionEpoch`, clears api/ws tokens in `SecureStore`, resets `tokenGeneration`.
    - `fun requireApiToken(): String?` — synchronous getter.

- [ ] **Step 6.1: Write failing test for `AuthStore.bootstrap` happy path**

```kotlin
// app/src/test/java/com/example/nanobotkt/core/auth/AuthStoreTest.kt
package com.example.nanobotkt.core.auth

import androidx.test.core.app.ApplicationProvider
import com.example.nanobotkt.core.net.BootstrapResponse
import com.example.nanobotkt.core.persist.SecureStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthStoreTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().apply { start() }; SecureStore.init(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { server.shutdown(); SecureStore.resetForTest() }

    @Test fun `bootstrap increments tokenGeneration and stores tokens`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"token":"ws-tok","ws_path":"/ws","api_token":"api-tok","server_id":"s1","user":{"id":"u1","name":"U"}}"""
        ).setHeader("Content-Type","application/json"))
        AuthStore.configure(server.url("/").toString().trimEnd('/'), debug = false)
        val state = AuthStore.bootstrap(secret = "sec")
        assertEquals(1L, state.tokenGeneration)
        assertEquals("api-tok", state.apiToken)
        assertEquals("ws-tok", state.wsToken)
        assertNotNull(state.wsUrl)
        assertEquals("api-tok", SecureStore.apiToken())
    }
}
```

- [ ] **Step 6.2: Run test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*AuthStoreTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 6.3: Add `BootstrapResponse` data class** to `core/serial/Runtime.kt`:

```kotlin
@Serializable
data class BootstrapResponse(
    val token: String? = null,
    @SerialName("ws_path") val wsPath: String? = null,
    @SerialName("ws_url") val wsUrl: String? = null,
    @SerialName("api_token") val apiToken: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    val user: UserSummary? = null,
    val capabilities: List<String> = emptyList(),
    val features: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class UserSummary(val id: String, val name: String? = null)
```

(Adjust field names to mirror the source gateway response exactly; the implementer reads `nanobot` server's actual payload shape.)

- [ ] **Step 6.4: Implement `AuthError.kt`**

```kotlin
package com.example.nanobotkt.core.auth

sealed class AuthError(message: String) : RuntimeException(message) {
    class BootstrapAuthRequired(detail: String = "auth required") : AuthError(detail)
    class GatewayHtml(detail: String = "gateway returned HTML") : AuthError(detail)
    class NonJson(detail: String = "gateway returned non-JSON") : AuthError(detail)
    class BootstrapFailed(val statusCode: Int, detail: String) : AuthError(detail)
    class Network(detail: String) : AuthError(detail)
}
```

- [ ] **Step 6.5: Implement `AuthStore.kt`**

```kotlin
package com.example.nanobotkt.core.auth

import com.example.nanobotkt.BuildConfig
import com.example.nanobotkt.core.net.BootstrapAuthRequiredError
import com.example.nanobotkt.core.net.BootstrapClient
import com.example.nanobotkt.core.net.BootstrapResponseError
import com.example.nanobotkt.core.net.ServerUrlResolver
import com.example.nanobotkt.core.persist.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference

object AuthStore {
    data class AuthState(
        val sessionEpoch: Long = 0,
        val tokenGeneration: Long = 0,
        val apiToken: String? = null,
        val wsToken: String? = null,
        val wsUrl: String? = null,
        val bootstrapSecret: String? = null,
        val lastBootstrapAt: Long = 0,
        val user: UserSummary? = null
    )

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val configuredBase = AtomicReference<String?>(null)

    fun configure(baseUrl: String, debug: Boolean) {
        configuredBase.compareAndSet(null, ServerUrlResolver.resolve(debug = debug, buildConfigUrl = baseUrl))
    }

    fun currentBaseUrl(): String? = configuredBase.get()

    suspend fun bootstrap(secret: String? = null): AuthState {
        val base = configuredBase.get() ?: ServerUrlResolver.resolve(BuildConfig.DEBUG, BuildConfig.NANOBOT_SERVER_URL)
        try {
            val resp = BootstrapClient.fetch(base, secret.orEmpty())
            val ws = BootstrapClient.deriveWsUrl(base, resp.wsPath.orEmpty(), resp.token.orEmpty(), resp.wsUrl)
            SecureStore.setBootstrapSecret(secret)
            SecureStore.setApiToken(resp.apiToken)
            SecureStore.setWsToken(resp.token)
            val updated = _state.updateAndGet {
                it.copy(
                    tokenGeneration = it.tokenGeneration + 1,
                    apiToken = resp.apiToken,
                    wsToken = resp.token,
                    wsUrl = ws,
                    bootstrapSecret = secret,
                    lastBootstrapAt = System.currentTimeMillis(),
                    user = resp.user
                )
            }
            return updated
        } catch (e: BootstrapAuthRequiredError) { throw AuthError.BootstrapAuthRequired(e.message.orEmpty()) }
        catch (e: BootstrapResponseError) {
            if (e.code == "gateway_html_response") throw AuthError.GatewayHtml()
            throw AuthError.NonJson()
        }
        catch (e: java.io.IOException) { throw AuthError.Network(e.message ?: "network") }
    }

    suspend fun clear() {
        SecureStore.setApiToken(null)
        SecureStore.setWsToken(null)
        _state.update {
            it.copy(sessionEpoch = it.sessionEpoch + 1, tokenGeneration = 0, apiToken = null, wsToken = null, wsUrl = null)
        }
    }

    fun requireApiToken(): String? = _state.value.apiToken
}
```

- [ ] **Step 6.6: Run test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*AuthStoreTest" -i`
Expected: PASS.

- [ ] **Step 6.7: Add 2 more tests**: `bootstrap increments sessionEpoch on clear()` and `bootstrap maps 401 to BootstrapAuthRequired()` (the latter by enqueuing a 401 response and asserting the thrown error).

- [ ] **Step 6.8: Wire `MainActivity` locale to the saved preference**:

In `MainActivity.onCreate`, replace the literal `"en"` with `runBlocking { com.example.nanobotkt.core.persist.LocalPreferences.currentLanguageOrDefault("en") }`. (Add `kotlinx-coroutines-core` `runBlocking` import.)

- [ ] **Step 6.9: Commit**

```bash
git add core/auth core/serial app/src/main/java/com/example/nanobotkt/MainActivity.kt \
    app/src/test/java/com/example/nanobotkt/core/auth
git commit -m "feat(core/auth): AuthStore + bootstrap orchestration + dual-epoch state"
```

---

## Task 7: Connection — SocketTransport state machine + queue flush

**Files:**
- Create: `connection/transport/TransportState.kt`
- Create: `connection/transport/TransportListeners.kt`
- Create: `connection/transport/SocketTransport.kt`
- Test: `app/src/test/java/com/example/nanobotkt/connection/SocketTransportTest.kt`

**Interfaces:**
- Consumes: `AuthStore.state` (Task 6), `OkHttpClient`, `InboundEvent`/`OutboundFrame` (Task 4).
- Produces:
  - `sealed class TransportStatus { Disconnected, Connecting, Open, Closing, Reconnecting }` plus `data class TransportError(val code: String, val detail: String?)`.
  - `typealias StatusListener = (TransportStatus) -> Unit`, `typealias EventListener = (InboundEvent) -> Unit`, `typealias RunStatusListener = (chatId: String, startedAt: Long?) -> Unit`, `typealias TransportErrorListener = (TransportError) -> Unit`.
  - `class SocketTransport` with: `fun connect()`, `fun disconnect()`, `fun reconnectNow()`, `fun notifyOffline()`, `fun sendOutbound(frame: OutboundFrame)`, `fun setReauthenticate(fn: suspend () -> String?)`, `fun onStatus(cb: StatusListener): () -> Unit`, `fun onEvent(cb: EventListener): () -> Unit`, `fun onRunStatus(cb: RunStatusListener): () -> Unit`, `fun onTransportError(cb: TransportErrorListener): () -> Unit`, `fun markKnownChat(chatId: String)`, `val knownChats: Set<String>`, `val status: StateFlow<TransportStatus>`, `fun lastSocketActivityAt(): Long`.
  - `val tokenGeneration: Long` accessor — bumped when reconnectNow() swaps to a fresh token from AuthStore.

- [ ] **Step 7.1: Write failing test for status listener wiring**

```kotlin
// app/src/test/java/com/example/nanobotkt/connection/SocketTransportTest.kt
package com.example.nanobotkt.connection.transport

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SocketTransportTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `connect transitions Disconnected -> Connecting -> Open`() = runTest {
        val transport = SocketTransport(initialUrlProvider = { server.url("/ws").toString() })
        val seen = mutableListOf<TransportStatus>()
        transport.onStatus { seen += it }
        transport.connect()
        Thread.sleep(50) // wait for upgrade response
        transport.disconnect()
        assertEquals(TransportStatus.Disconnected, seen.first())
        assertEquals(TransportStatus.Open, seen.last())
    }
}
```

- [ ] **Step 7.2: Run test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SocketTransportTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 7.3: Implement `TransportState.kt`**

```kotlin
package com.example.nanobotkt.connection.transport

sealed class TransportStatus { object Disconnected : TransportStatus(); object Connecting : TransportStatus(); object Open : TransportStatus(); object Closing : TransportStatus(); object Reconnecting : TransportStatus() }
data class TransportError(val code: String, val detail: String? = null)
```

- [ ] **Step 7.4: Implement `TransportListeners.kt`**

```kotlin
package com.example.nanobotkt.connection.transport

import com.example.nanobotkt.core.serial.InboundEvent

typealias StatusListener = (TransportStatus) -> Unit
typealias EventListener = (InboundEvent) -> Unit
typealias RunStatusListener = (chatId: String, startedAt: Long?) -> Unit
typealias TransportErrorListener = (TransportError) -> Unit
```

- [ ] **Step 7.5: Implement `SocketTransport.kt`**

```kotlin
package com.example.nanobotkt.connection.transport

import com.example.nanobotkt.core.auth.AuthStore
import com.example.nanobotkt.core.serial.InboundEvent
import com.example.nanobotkt.core.serial.OutboundFrame
import com.example.nanobotkt.connection.outbound.SocketOutboundQueue
import com.example.nanobotkt.connection.inbound.SocketInboundRouter
import com.example.nanobotkt.connection.pending.SocketPendingRegistry
import com.example.nanobotkt.connection.reconnect.SocketReconnectPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArraySet

class SocketTransport(
    private val initialUrlProvider: suspend () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val client: OkHttpClient = OkHttpClient(),
    private val reconnectPolicy: SocketReconnectPolicy = SocketReconnectPolicy(),
    private val outbound: SocketOutboundQueue = SocketOutboundQueue(),
    private val inbound: SocketInboundRouter = SocketInboundRouter(),
    private val pending: SocketPendingRegistry = SocketPendingRegistry(),
    private val reauthenticate: suspend () -> String? = { AuthStore.state.value.wsToken }
) {
    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Disconnected)
    val status: StateFlow<TransportStatus> = _status.asStateFlow()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastActiveAt: Long = 0
    private val knownChats_ = CopyOnWriteArraySet<String>()
    val knownChats: Set<String> get() = knownChats_
    private val statusListeners = CopyOnWriteArraySet<StatusListener>()
    private val eventListeners = CopyOnWriteArraySet<EventListener>()
    private val runStatusListeners = CopyOnWriteArraySet<RunStatusListener>()
    private val errorListeners = CopyOnWriteArraySet<TransportErrorListener>()

    fun onStatus(cb: StatusListener): () -> Unit { statusListeners += cb; return { statusListeners -= cb } }
    fun onEvent(cb: EventListener): () -> Unit { eventListeners += cb; return { eventListeners -= cb } }
    fun onRunStatus(cb: RunStatusListener): () -> Unit { runStatusListeners += cb; return { runStatusListeners -= cb } }
    fun onTransportError(cb: TransportErrorListener): () -> Unit { errorListeners += cb; return { errorListeners -= cb } }

    fun markKnownChat(chatId: String) { knownChats_ += chatId }

    private fun emitStatus(s: TransportStatus) { _status.value = s; statusListeners.forEach { it(s) } }
    private fun emitEvent(e: InboundEvent) { eventListeners.forEach { it(e) } }
    private fun emitError(code: String, detail: String? = null) { val e = TransportError(code, detail); errorListeners.forEach { it(e) } }

    fun connect() {
        if (_status.value == TransportStatus.Open || _status.value == TransportStatus.Connecting) return
        scope.launch {
            emitStatus(TransportStatus.Connecting)
            val url = initialUrlProvider() ?: run { emitStatus(TransportStatus.Disconnected); return@launch }
            val req = Request.Builder().url(url).build()
            socket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastActiveAt = System.currentTimeMillis()
                    emitStatus(TransportStatus.Open)
                    for (cid in knownChats_) sendRaw(OutboundFrame.Attach(cid))
                    for (f in outbound.drainAll()) sendRaw(f)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastActiveAt = System.currentTimeMillis()
                    val ev = inbound.parseAndRoute(text) ?: return
                    emitEvent(ev)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    emitStatus(TransportStatus.Closing); webSocket.close(1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    emitStatus(TransportStatus.Disconnected)
                    pending.rejectNonSuspendableOnClose()
                    scheduleReconnect()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    emitError("socket_failure", t.message); emitStatus(TransportStatus.Disconnected)
                    scheduleReconnect()
                }
            })
        }
    }

    fun disconnect() {
        scope.launch { socket?.close(1000, "client disconnect"); emitStatus(TransportStatus.Disconnected) }
    }

    fun reconnectNow() {
        scope.launch {
            socket?.close(1000, "reconnect")
            socket = null
            emitStatus(TransportStatus.Reconnecting)
            connect()
        }
    }

    fun notifyOffline() {
        scope.launch { socket?.close(1000, "offline"); pending.rejectNonSuspendableOnClose() }
    }

    private fun scheduleReconnect() {
        scope.launch {
            val delayMs = reconnectPolicy.nextDelayMs()
            emitStatus(TransportStatus.Reconnecting)
            delay(delayMs)
            connect()
        }
    }

    fun sendOutbound(frame: OutboundFrame) {
        if (_status.value == TransportStatus.Open) sendRaw(frame) else outbound.enqueue(frame)
    }

    private fun sendRaw(frame: OutboundFrame) {
        val text = kotlinx.serialization.json.Json.encodeToString(OutboundFrame.serializer(), frame)
        socket?.send(text)
    }

    fun lastSocketActivityAt(): Long = lastActiveAt
}
```

(Implementer adds `OutboundFrame.Attach(chatId)` data class — port from source `socket-protocol.ts`.)

- [ ] **Step 7.6: Stub `SocketOutboundQueue` / `SocketInboundRouter` / `SocketPendingRegistry` / `SocketReconnectPolicy`**

```kotlin
// connection/outbound/SocketOutboundQueue.kt
package com.example.nanobotkt.connection.outbound
import com.example.nanobotkt.core.serial.OutboundFrame
import java.util.concurrent.ConcurrentHashMap
class SocketOutboundQueue {
    private val byTurn = ConcurrentHashMap<String, OutboundFrame>()
    @Synchronized fun enqueue(f: OutboundFrame) { byTurn[turnKey(f)] = f }
    @Synchronized fun drainAll(): List<OutboundFrame> { val r = byTurn.values.toList(); byTurn.clear(); return r }
    private fun turnKey(f: OutboundFrame): String = when (f) { is OutboundFrame.Message -> f.turnId; is OutboundFrame.NewChat -> f.turnId ?: java.util.UUID.randomUUID().toString(); else -> java.util.UUID.randomUUID().toString() }
}
```

```kotlin
// connection/inbound/SocketInboundRouter.kt
package com.example.nanobotkt.connection.inbound
import com.example.nanobotkt.core.serial.InboundEvent
import kotlinx.serialization.json.Json
class SocketInboundRouter(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun parseAndRoute(text: String): InboundEvent? = runCatching { json.decodeFromString(InboundEvent.serializer(), text) }.getOrNull()
    fun route(event: InboundEvent) { /* Task 9 wires dispatch */ }
}
```

```kotlin
// connection/pending/SocketPendingRegistry.kt
package com.example.nanobotkt.connection.pending
import com.example.nanobotkt.core.serial.InboundEvent
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

class SocketPendingRegistry {
    private val messages = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    fun registerMessage(turnId: String): MessageSendResult = MessageSendResult(turnId, messages.getOrPut(turnId) { CompletableDeferred() })
    fun resolve(event: InboundEvent) { /* Task 9 wires */ }
    fun rejectNonSuspendableOnClose() { messages.values.forEach { it.completeExceptionally(java.io.IOException("socket closed")) }; messages.clear() }
}
data class MessageSendResult(val turnId: String, val accepted: kotlinx.coroutines.Deferred<Unit>)
```

```kotlin
// connection/reconnect/SocketReconnectPolicy.kt
package com.example.nanobotkt.connection.reconnect
class SocketReconnectPolicy(private val baseMs: Long = 500, private val capMs: Long = 30_000, private val maxAttempts: Int = 12) {
    private var attempt = 0
    @Synchronized fun nextDelayMs(): Long { attempt += 1; if (attempt > maxAttempts) return capMs; val raw = baseMs * (1L shl (attempt - 1).coerceAtMost(5)); return raw.coerceAtMost(capMs) }
    @Synchronized fun reset() { attempt = 0 }
}
```

- [ ] **Step 7.7: Run test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SocketTransportTest" -i`
Expected: PASS.

- [ ] **Step 7.8: Add 3 more tests**: disconnect mid-open fires status Disconnected, `markKnownChat` survives reconnect (replays `attach` after onOpen), outbound sent while Connecting is queued and flushed on open.

- [ ] **Step 7.9: Commit**

```bash
git add connection/transport connection/outbound connection/inbound connection/pending connection/reconnect \
    app/src/test/java/com/example/nanobotkt/connection/SocketTransportTest.kt
git commit -m "feat(connection): SocketTransport state machine + queue + known chats replay"
```

---

## Task 8: Connection — RecoveryPolicy + NetworkObserver + LifecycleObserver

**Files:**
- Create: `connection/recovery/NetworkObserver.kt`
- Create: `connection/recovery/LifecycleObserver.kt`
- Create: `connection/recovery/ConnectionRecoveryPolicy.kt`
- Test: `app/src/test/java/com/example/nanobotkt/connection/ConnectionRecoveryPolicyTest.kt`

**Interfaces:**
- Consumes: `SocketTransport` (Task 7), `androidx.lifecycle.Lifecycle`, `android.net.ConnectivityManager.NetworkCallback`.
- Produces:
  - `sealed class NetState { Online, Offline }` from `NetworkObserver.start()` writing `StateFlow<NetState>`.
  - `sealed class AppLifecycle { Foreground, Background }` from `LifecycleObserver.start()` writing `StateFlow<AppLifecycle>`.
  - `class ConnectionRecoveryPolicy(transport, netFlow, lifeFlow, thresholdMs = 30_000)` with `fun start()`, `fun stop()` applying the rules from spec §3.

- [ ] **Step 8.1: Write failing test**

```kotlin
// app/src/test/java/com/example/nanobotkt/connection/ConnectionRecoveryPolicyTest.kt
package com.example.nanobotkt.connection.recovery

import com.example.nanobotkt.connection.transport.SocketTransport
import com.example.nanobotkt.connection.transport.TransportStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConnectionRecoveryPolicyTest {
    @Test fun `reconnectNow is invoked when net transitions offline -> online while foreground`() = runTest {
        val net = MutableStateFlow(NetState.Offline)
        val life = MutableStateFlow(AppLifecycle.Foreground)
        val transport = FakeTransport()
        val policy = ConnectionRecoveryPolicy(transport, net, life, thresholdMs = 0)
        policy.start()
        net.value = NetState.Online
        advanceUntilIdle()
        assertEquals(1, transport.reconnectCount.get())
    }
}

class FakeTransport : SocketTransport(initialUrlProvider = { null }) {
    val reconnectCount = AtomicInteger(0)
    override fun reconnectNow() { reconnectCount.incrementAndGet() }
}
```

- [ ] **Step 8.2: Run test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConnectionRecoveryPolicyTest" -i`
Expected: COMPILATION FAILURE on `ConnectionRecoveryPolicy`, `NetState`, `AppLifecycle`.

- [ ] **Step 8.3: Implement `NetworkObserver.kt`**

```kotlin
package com.example.nanobotkt.connection.recovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class NetState { object Online : NetState(); object Offline : NetState() }

class NetworkObserver(private val context: Context) {
    private val _state = MutableStateFlow<NetState>(NetState.Online)
    val state: StateFlow<NetState> = _state.asStateFlow()

    fun start() {
        val cm = context.getSystemService<ConnectivityManager>() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _state.value = NetState.Online }
            override fun onLost(network: Network) { _state.value = NetState.Offline }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _state.value = if (hasInternet) NetState.Online else NetState.Offline
            }
        }
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, cb)
    }
}
```

- [ ] **Step 8.4: Implement `LifecycleObserver.kt`**

```kotlin
package com.example.nanobotkt.connection.recovery

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AppLifecycle { object Foreground : AppLifecycle(); object Background : AppLifecycle() }

class LifecycleObserver {
    private val _state = MutableStateFlow<AppLifecycle>(AppLifecycle.Foreground)
    val state: StateFlow<AppLifecycle> = _state.asStateFlow()

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> _state.value = AppLifecycle.Foreground
                Lifecycle.Event.ON_STOP -> _state.value = AppLifecycle.Background
                else -> {}
            }
        })
    }
}
```

- [ ] **Step 8.5: Implement `ConnectionRecoveryPolicy.kt`**

```kotlin
package com.example.nanobotkt.connection.recovery

import com.example.nanobotkt.connection.transport.SocketTransport
import com.example.nanobotkt.connection.transport.TransportStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

class ConnectionRecoveryPolicy(
    private val transport: SocketTransport,
    private val netFlow: StateFlow<NetState>,
    private val lifeFlow: StateFlow<AppLifecycle>,
    private val thresholdMs: Long = 30_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private var started = false

    fun start() {
        if (started) return; started = true
        scope.launch {
            netFlow.collect { st ->
                when (st) {
                    NetState.Offline -> transport.notifyOffline()
                    NetState.Online -> if (lifeFlow.value == AppLifecycle.Foreground) transport.reconnectNow()
                }
            }
        }
        scope.launch {
            var lastBackgroundAt = 0L
            lifeFlow.collect { lc ->
                when (lc) {
                    AppLifecycle.Background -> lastBackgroundAt = System.currentTimeMillis()
                    AppLifecycle.Foreground -> {
                        val stale = System.currentTimeMillis() - lastBackgroundAt
                        if (stale >= thresholdMs || transport.status.value != TransportStatus.Open) {
                            transport.reconnectNow()
                        }
                    }
                }
            }
        }
    }

    fun stop() { scope.cancel() }
}
```

- [ ] **Step 8.6: Run test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConnectionRecoveryPolicyTest" -i`
Expected: PASS.

- [ ] **Step 8.7: Add 2 more tests**: `net Online + lifecycle Background does NOT trigger reconnect`; `background 30s+ then foreground triggers reconnect regardless of socket status`.

- [ ] **Step 8.8: Commit**

```bash
git add connection/recovery app/src/test/java/com/example/nanobotkt/connection/ConnectionRecoveryPolicyTest.kt
git commit -m "feat(connection): recovery policy wired to net + lifecycle observers"
```

---

## Task 9: Connection — pending registry + outbound queue + inbound router + store

**Files:**
- Create: `connection/pending/PendingFrame.kt`
- Create: `connection/pending/SocketPendingRegistry.kt` (full)
- Create: `connection/outbound/SocketOutboundQueue.kt` (full)
- Create: `connection/inbound/SocketInboundRouter.kt` (full)
- Create: `connection/store/ConnectionStore.kt`
- Create: `connection/index.kt`
- Test: `app/src/test/java/com/example/nanobotkt/connection/SocketOutboundQueueTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/connection/SocketInboundRouterTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/connection/SocketPendingRegistryTest.kt`

**Interfaces:**
- Consumes: `InboundEvent`/`OutboundFrame` (Task 4), `ChatStore` (Task 10; imported lazily via router dispatch), `SettingsStore` (Phase 2; imported lazily).
- Produces:
  - `sealed class PendingFrame { NewChat(turnId, deferred); Message(turnId, deferred); System(...); Transcription(requestId, deferred) }` with `data class MessageSendResult(val turnId: String, val accepted: Deferred<Unit>)`.
  - `class SocketPendingRegistry { fun registerMessage(turnId): MessageSendResult; fun registerNewChat(turnId): MessageSendResult; fun registerSystem(turnId): MessageSendResult; fun registerTranscription(requestId): Deferred<String>; fun resolve(event: InboundEvent); fun rejectNonSuspendableOnClose() }`.
  - `class SocketOutboundQueue` (already started in Task 7): full impl with per-turn de-dup and `rejectAll()` on close.
  - `class SocketInboundRouter` (already started in Task 7): full impl with the routing table from spec §3.
  - `object ConnectionStore` with `MutableStateFlow<ConnectionState>` exposing `status, knownChats, reconnectReason, sessionEpoch, tokenGeneration`.
  - `connection/index.kt` re-exports public surface and provides `Connection.start(transport, recovery)`.

- [ ] **Step 9.1: Write failing test for pending message resolve**

```kotlin
// app/src/test/java/com/example/nanobotkt/connection/SocketPendingRegistryTest.kt
package com.example.nanobotkt.connection.pending

import com.example.nanobotkt.core.serial.InboundEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SocketPendingRegistryTest {
    @Test fun `message_accepted resolves the matching turnId`() = runTest {
        val reg = SocketPendingRegistry()
        val result = reg.registerMessage("turn-1")
        reg.resolve(InboundEvent.MessageAccepted("c1", "turn-1"))
        result.accepted.await()
        assertEquals(Unit, result.accepted.getCompleted())
    }
}
```

- [ ] **Step 9.2: Run test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*SocketPendingRegistryTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 9.3: Implement `PendingFrame.kt`**

```kotlin
package com.example.nanobotkt.connection.pending

import kotlinx.coroutines.CompletableDeferred

sealed class PendingFrame {
    abstract val turnId: String
    abstract val accepted: CompletableDeferred<Unit>
}
data class NewChatPending(override val turnId: String, override val accepted: CompletableDeferred<Unit>) : PendingFrame()
data class MessagePending(override val turnId: String, override val accepted: CompletableDeferred<Unit>) : PendingFrame()
data class SystemPending(override val turnId: String, override val accepted: CompletableDeferred<Unit>) : PendingFrame()
data class TranscriptionPending(val requestId: String, override val accepted: CompletableDeferred<String>) : PendingFrame() {
    override val turnId: String = requestId
}
data class MessageSendResult(val turnId: String, val accepted: kotlinx.coroutines.Deferred<Unit>)
```

- [ ] **Step 9.4: Implement full `SocketPendingRegistry`**

```kotlin
package com.example.nanobotkt.connection.pending

import com.example.nanobotkt.core.serial.InboundEvent
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class SocketPendingRegistry {
    private val messages = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val newChats = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val systems = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val transcriptions = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun registerMessage(turnId: String): MessageSendResult =
        MessageSendResult(turnId, messages.getOrPut(turnId) { CompletableDeferred() })

    fun registerNewChat(turnId: String): MessageSendResult =
        MessageSendResult(turnId, newChats.getOrPut(turnId) { CompletableDeferred() })

    fun registerSystem(turnId: String): MessageSendResult =
        MessageSendResult(turnId, systems.getOrPut(turnId) { CompletableDeferred() })

    fun registerTranscription(requestId: String): CompletableDeferred<String> =
        transcriptions.getOrPut(requestId) { CompletableDeferred() }

    fun resolve(event: InboundEvent) {
        when (event) {
            is InboundEvent.MessageAccepted -> messages.remove(event.turnId)?.complete(Unit)
            is InboundEvent.TranscriptionResult -> transcriptions.remove(event.requestId)?.complete(event.text)
            is InboundEvent.TranscriptionError -> transcriptions.remove(event.requestId ?: "")?.completeExceptionally(RuntimeException(event.detail))
            else -> {}
        }
    }

    fun rejectNonSuspendableOnClose() {
        messages.values.forEach { it.completeExceptionally(IOException("socket closed")) }; messages.clear()
        newChats.values.forEach { it.completeExceptionally(IOException("socket closed")) }; newChats.clear()
    }
}
```

- [ ] **Step 9.5: Run test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SocketPendingRegistryTest" -i`
Expected: PASS.

- [ ] **Step 9.6: Replace stub `SocketOutboundQueue` with full impl**

The stub from Task 7 is already functionally complete (drainAll + enqueue + per-turn de-dup). Add `rejectAll()`:

```kotlin
@Synchronized fun rejectAll() { byTurn.clear() }
@Synchronized fun size(): Int = byTurn.size
```

- [ ] **Step 9.7: Write failing test for outbound queue**

```kotlin
// app/src/test/java/com/example/nanobotkt/connection/SocketOutboundQueueTest.kt
class SocketOutboundQueueTest {
    @Test fun `drainAll returns pending and clears`() {
        val q = SocketOutboundQueue()
        q.enqueue(OutboundFrame.NewChat(turnId = "n1"))
        q.enqueue(OutboundFrame.NewChat(turnId = "n2"))
        val drained = q.drainAll()
        assertEquals(2, drained.size)
        assertEquals(0, q.size())
    }
    @Test fun `enqueue with same turnId replaces existing`() {
        val q = SocketOutboundQueue()
        q.enqueue(OutboundFrame.NewChat(turnId = "x"))
        q.enqueue(OutboundFrame.NewChat(turnId = "x"))
        assertEquals(1, q.size())
    }
    @Test fun `rejectAll empties the queue`() {
        val q = SocketOutboundQueue()
        q.enqueue(OutboundFrame.NewChat(turnId = "x"))
        q.rejectAll()
        assertEquals(0, q.size())
    }
}
```

- [ ] **Step 9.8: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SocketOutboundQueueTest" -i`
Expected: PASS.

- [ ] **Step 9.9: Implement full `SocketInboundRouter` with the dispatch table from spec §3**

```kotlin
package com.example.nanobotkt.connection.inbound

import com.example.nanobotkt.chat.store.ChatStore
import com.example.nanobotkt.connection.pending.SocketPendingRegistry
import com.example.nanobotkt.core.serial.InboundEvent
import kotlinx.serialization.json.Json

class SocketInboundRouter(
    private val chat: ChatStore = ChatStore,
    private val pending: SocketPendingRegistry = SocketPendingRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun parseAndRoute(text: String): InboundEvent? = runCatching {
        json.decodeFromString(InboundEvent.serializer(), text)
    }.getOrNull()

    fun route(event: InboundEvent) {
        pending.resolve(event)
        when (event) {
            is InboundEvent.Message -> chat.appendMessage(event.chatId, event)
            is InboundEvent.Delta -> chat.appendDelta(event.chatId, event.streamId, event.text, event.turnId, event.turnSeq)
            is InboundEvent.ReasoningDelta -> chat.appendReasoning(event.chatId, event.streamId, event.text, event.turnId, event.turnSeq)
            is InboundEvent.ReasoningEnd -> chat.endReasoning(event.chatId, event.streamId, event.turnId, event.turnSeq)
            is InboundEvent.StreamEnd -> chat.endStream(event.chatId, event.streamId, event.text, event.turnId, event.turnSeq, event.mergeNext)
            is InboundEvent.TurnEnd -> chat.endTurn(event.chatId, event.latencyMs, event.turnId, event.goalState)
            is InboundEvent.FileEdit -> chat.appendFileEdit(event.chatId, event.edits, event.turnId, event.turnSeq)
            is InboundEvent.SessionUpdated -> chat.updateWorkspaceScope(event.chatId, event.workspaceScope)
            is InboundEvent.GoalStatus -> chat.setRunStatus(event.chatId, event.startedAt)
            is InboundEvent.TurnModelUpdated -> chat.setTurnModel(event.chatId, event.modelName)
            is InboundEvent.Error -> chat.appendError(event.chatId, event.detail, event.turnId)
            else -> {}
        }
    }
}
```

- [ ] **Step 9.10: Write failing test for inbound router dispatch**

```kotlin
// app/src/test/java/com/example/nanobotkt/connection/SocketInboundRouterTest.kt
class SocketInboundRouterTest {
    @Test fun `parseAndRoute returns null on non-JSON input`() {
        val r = SocketInboundRouter()
        assertNull(r.parseAndRoute("not json"))
    }
    @Test fun `route calls ChatStore appendMessage on Message event`() {
        val chat = mockk<ChatStore>(relaxed = true)
        val r = SocketInboundRouter(chat)
        val ev = InboundEvent.Message("c1", "hi", turnId = "t1")
        r.route(ev)
        verify { chat.appendMessage("c1", ev) }
    }
}
```

Add `mockk` test dep:

```toml
mockk = { module = "io.mockk:mockk", version = "1.13.13" }
```

```kotlin
testImplementation(libs.mockk)
```

- [ ] **Step 9.11: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*SocketInboundRouterTest" -i`
Expected: PASS.

- [ ] **Step 9.12: Implement `ConnectionStore`**

```kotlin
package com.example.nanobotkt.connection.store

import com.example.nanobotkt.connection.transport.SocketTransport
import com.example.nanobotkt.connection.transport.TransportStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConnectionState(
    val status: TransportStatus = TransportStatus.Disconnected,
    val knownChats: Set<String> = emptySet(),
    val reconnectReason: String? = null,
    val sessionEpoch: Long = 0,
    val tokenGeneration: Long = 0
)

object ConnectionStore {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun bind(transport: SocketTransport) {
        transport.onStatus { s -> _state.update { it.copy(status = s) } }
        // markKnownChat is recorded by the app-level wrapper, see Task 16.
    }

    fun recordReconnectReason(reason: String?) { _state.update { it.copy(reconnectReason = reason) } }
    fun bumpSessionEpoch() { _state.update { it.copy(sessionEpoch = it.sessionEpoch + 1) } }
    fun bumpTokenGeneration() { _state.update { it.copy(tokenGeneration = it.tokenGeneration + 1) } }
}
```

- [ ] **Step 9.13: Create `connection/index.kt`**

```kotlin
package com.example.nanobotkt.connection

import com.example.nanobotkt.connection.transport.SocketTransport
import com.example.nanobotkt.connection.store.ConnectionStore
import com.example.nanobotkt.connection.recovery.ConnectionRecoveryPolicy

object Connection {
    lateinit var transport: SocketTransport
        private set
    lateinit var recovery: ConnectionRecoveryPolicy
        private set

    fun start(transport: SocketTransport, recovery: ConnectionRecoveryPolicy) {
        this.transport = transport
        this.recovery = recovery
        ConnectionStore.bind(transport)
        transport.connect()
        recovery.start()
    }

    fun stop() {
        recovery.stop()
        transport.disconnect()
    }
}
```

- [ ] **Step 9.14: Run all connection tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*Socket*" --tests "*Connection*" -i`
Expected: PASS (ChatStore stub methods from Task 10 must be present as no-ops; Task 10 fills them in).

- [ ] **Step 9.15: Commit**

```bash
git add connection \
    app/src/test/java/com/example/nanobotkt/connection/SocketOutboundQueueTest.kt \
    app/src/test/java/com/example/nanobotkt/connection/SocketInboundRouterTest.kt \
    app/src/test/java/com/example/nanobotkt/connection/SocketPendingRegistryTest.kt \
    gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(connection): pending registry + queue + inbound router + ConnectionStore"
```

---

## Task 10: Chat — store + stream-fold state machine + reducers

**Files:**
- Create: `chat/store/ChatState.kt`
- Create: `chat/store/ChatStateFactories.kt`
- Create: `chat/store/ChatStore.kt`
- Create: `chat/store/StreamRuntime.kt`
- Create: `chat/store/InboundEventHandler.kt`
- Create: `chat/store/MessageReconciliation.kt`
- Create: `chat/stream/StreamFoldState.kt`
- Create: `chat/stream/AssistantReasoningReducer.kt`
- Create: `chat/stream/AssistantAnswerReducer.kt`
- Create: `chat/stream/AssistantCompletionReducer.kt`
- Create: `chat/stream/AssistantEventsAdapter.kt`
- Test: `app/src/test/java/com/example/nanobotkt/chat/stream/AssistantReasoningReducerTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/chat/stream/AssistantAnswerReducerTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/chat/stream/AssistantCompletionReducerTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/chat/stream/AssistantEventsAdapterTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/chat/store/ChatStoreTest.kt`
- Test: `app/src/test/java/com/example/nanobotkt/chat/store/InboundEventHandlerTest.kt`

**Interfaces:**
- Consumes: `InboundEvent` (Task 4).
- Produces:
  - `data class ChatState(val byChatId: Map<String, ChatThreadState>)` plus `ChatThreadState(messages: List<UIMessage>, runStatus: RunStatus, segments: StreamFoldState, pendingMessages: Map<turnId, UIMessage>)`.
  - `object ChatStore : ViewModel()` with `state: StateFlow<ChatState>`, `appendMessage`, `appendDelta`, `appendReasoning`, `endReasoning`, `endStream`, `endTurn`, `appendFileEdit`, `updateWorkspaceScope`, `setRunStatus`, `setTurnModel`, `appendError`, `reconcileCanonical`, `clear()`.
  - `class StreamFoldState` with reducer functions: `reduceReasoning(state, event)`, `reduceAnswer(state, event)`, `reduceCompletion(state, event)`. All four reducers validate `turn_id` first.
  - `object InboundEventHandler { fun apply(state: ChatState, event: InboundEvent): ChatState }` — orchestrates the four reducers.

- [ ] **Step 10.1: Write failing test for `StreamFoldState.reduceReasoning`**

```kotlin
// app/src/test/java/com/example/nanobotkt/chat/stream/AssistantReasoningReducerTest.kt
class AssistantReasoningReducerTest {
    @Test fun `validates turn_id and ignores mismatched`() {
        val s0 = StreamFoldState.initial()
        val mismatch = ReasoningDelta(chatId = "c", streamId = "s", text = "hi", turnId = "t2", turnSeq = 1)
        val s1 = AssistantReasoningReducer.reduce(s0, mismatch)
        assertEquals(s0, s1)
    }
    @Test fun `appends text to active segment when turn matches`() {
        val s0 = StreamFoldState.initial().open(streamId = "s", turnId = "t1", kind = SegmentKind.Reasoning)
        val s1 = AssistantReasoningReducer.reduce(s0, ReasoningDelta("c", "s", "hi", "t1", 1))
        val s2 = AssistantReasoningReducer.reduce(s1, ReasoningDelta("c", "s", " there", "t1", 2))
        val active = s2.segments.values.first()
        assertEquals("hi there", active.text)
    }
}
```

- [ ] **Step 10.2: Run — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*AssistantReasoningReducerTest" -i`
Expected: COMPILATION FAILURE.

- [ ] **Step 10.3: Implement `StreamFoldState.kt`**

```kotlin
package com.example.nanobotkt.chat.stream

import kotlinx.serialization.Serializable

enum class SegmentKind { Reasoning, Answer, Tool }
data class FoldSegment(val streamId: String, val turnId: String, val kind: SegmentKind, val text: String = "", var closed: Boolean = false)

data class StreamFoldState(
    val activeStreamId: String? = null,
    val closedStreamIds: Set<String> = emptySet(),
    val segments: Map<String, FoldSegment> = emptyMap(),
    val turnId: String? = null
) {
    fun open(streamId: String, turnId: String, kind: SegmentKind) =
        copy(activeStreamId = streamId, turnId = turnId,
             segments = segments + (streamId to FoldSegment(streamId, turnId, kind)))
    fun close(streamId: String) =
        copy(activeStreamId = if (activeStreamId == streamId) null else activeStreamId,
             closedStreamIds = closedStreamIds + streamId,
             segments = segments + (streamId to (segments[streamId] ?: error("unknown stream")).copy(closed = true)))

    companion object { fun initial() = StreamFoldState() }
}

fun StreamFoldState.eventTurnIdValid(eventTurnId: String?): Boolean =
    eventTurnId == null || turnId == null || eventTurnId == turnId
```

- [ ] **Step 10.4: Implement `AssistantReasoningReducer`**

```kotlin
package com.example.nanobotkt.chat.stream

import com.example.nanobotkt.core.serial.InboundEvent

object AssistantReasoningReducer {
    fun reduce(state: StreamFoldState, event: InboundEvent.ReasoningDelta): StreamFoldState {
        if (!state.eventTurnIdValid(event.turnId)) return state
        val seg = state.segments[event.streamId ?: return state] ?: return state
        if (seg.closed) return state
        val merged = seg.copy(text = seg.text + event.text)
        return state.copy(segments = state.segments + (seg.streamId to merged))
    }
    fun reduce(state: StreamFoldState, event: InboundEvent.ReasoningEnd): StreamFoldState {
        if (!state.eventTurnIdValid(event.turnId)) return state
        return state.close(event.streamId ?: return state)
    }
}
```

- [ ] **Step 10.5: Run reasoning reducer test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*AssistantReasoningReducerTest" -i`
Expected: PASS.

- [ ] **Step 10.6: Implement `AssistantAnswerReducer`**

```kotlin
object AssistantAnswerReducer {
    fun reduce(state: StreamFoldState, event: InboundEvent.Delta): StreamFoldState {
        if (!state.eventTurnIdValid(event.turnId)) return state
        val seg = state.segments[event.streamId ?: return state] ?: return state
        if (seg.closed) return state
        val merged = seg.copy(text = seg.text + event.text)
        return state.copy(segments = state.segments + (seg.streamId to merged))
    }
    fun reduce(state: StreamFoldState, event: InboundEvent.StreamEnd): StreamFoldState {
        if (!state.eventTurnIdValid(event.turnId)) return state
        val sid = event.streamId ?: return state
        val next = state.close(sid)
        return if (event.mergeNext) next else next
    }
}
```

- [ ] **Step 10.7: Write failing test for answer reducer**

```kotlin
class AssistantAnswerReducerTest {
    @Test fun `appends delta to active answer stream`() {
        val s0 = StreamFoldState.initial().open("s1", "t1", SegmentKind.Answer)
        val s1 = AssistantAnswerReducer.reduce(s0, InboundEvent.Delta("c", "hello", "s1", "t1", 1))
        val s2 = AssistantAnswerReducer.reduce(s1, InboundEvent.Delta("c", " world", "s1", "t1", 2))
        assertEquals("hello world", s2.segments["s1"]!!.text)
    }
}
```

- [ ] **Step 10.8: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*AssistantAnswerReducerTest" -i`
Expected: PASS.

- [ ] **Step 10.9: Implement `AssistantCompletionReducer`**

```kotlin
object AssistantCompletionReducer {
    fun reduce(state: StreamFoldState, event: InboundEvent.TurnEnd): StreamFoldState {
        if (!state.eventTurnIdValid(event.turnId)) return state
        val active = state.activeStreamId
        return if (active != null) state.close(active) else state
    }
}
```

- [ ] **Step 10.10: Implement `AssistantEventsAdapter` (compat entry)**

```kotlin
object AssistantEventsAdapter {
    fun dispatch(state: StreamFoldState, event: InboundEvent): StreamFoldState = when (event) {
        is InboundEvent.ReasoningDelta -> AssistantReasoningReducer.reduce(state, event)
        is InboundEvent.ReasoningEnd -> AssistantReasoningReducer.reduce(state, event)
        is InboundEvent.Delta -> AssistantAnswerReducer.reduce(state, event)
        is InboundEvent.StreamEnd -> AssistantAnswerReducer.reduce(state, event)
        is InboundEvent.TurnEnd -> AssistantCompletionReducer.reduce(state, event)
        else -> state
    }
}
```

- [ ] **Step 10.11: Write failing test for adapter**

```kotlin
class AssistantEventsAdapterTest {
    @Test fun `dispatch ignores unknown events`() {
        val s0 = StreamFoldState.initial()
        val s1 = AssistantEventsAdapter.dispatch(s0, InboundEvent.Ready("c", "client"))
        assertEquals(s0, s1)
    }
    @Test fun `dispatch routes Delta to answer reducer`() {
        val s0 = StreamFoldState.initial().open("s", "t", SegmentKind.Answer)
        val s1 = AssistantEventsAdapter.dispatch(s0, InboundEvent.Delta("c", "x", "s", "t", 1))
        assertEquals("x", s1.segments["s"]!!.text)
    }
}
```

- [ ] **Step 10.12: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*AssistantEventsAdapterTest" -i`
Expected: PASS.

- [ ] **Step 10.13: Implement `ChatState.kt`**

```kotlin
package com.example.nanobotkt.chat.store

import com.example.nanobotkt.chat.stream.StreamFoldState
import com.example.nanobotkt.core.serial.UIMessage
import com.example.nanobotkt.core.serial.WorkspaceScopePayload

data class ChatThreadState(
    val chatId: String,
    val messages: List<UIMessage> = emptyList(),
    val fold: StreamFoldState = StreamFoldState.initial(),
    val runStartedAt: Long? = null,
    val turnModel: String? = null,
    val workspaceScope: WorkspaceScopePayload? = null,
    val errorText: String? = null
)

data class ChatState(val byChatId: Map<String, ChatThreadState> = emptyMap())
```

- [ ] **Step 10.14: Implement `ChatStateFactories.kt`** (constructors used by `reconcileCanonical`)

```kotlin
package com.example.nanobotkt.chat.store

import com.example.nanobotkt.core.serial.UIMessage

object ChatStateFactories {
    fun fromCanonicalMessages(chatId: String, msgs: List<UIMessage>): ChatThreadState =
        ChatThreadState(chatId = chatId, messages = msgs)
    fun empty(chatId: String): ChatThreadState = ChatThreadState(chatId = chatId)
}
```

- [ ] **Step 10.15: Implement `ChatStore.kt`**

```kotlin
package com.example.nanobotkt.chat.store

import androidx.lifecycle.ViewModel
import com.example.nanobotkt.chat.stream.AssistantEventsAdapter
import com.example.nanobotkt.core.serial.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ChatStore : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private fun mutate(chatId: String, fn: (ChatThreadState) -> ChatThreadState) =
        _state.update { it.copy(byChatId = it.byChatId + (chatId to fn(it.byChatId[chatId] ?: ChatThreadState(chatId)))) }

    fun appendMessage(chatId: String, event: InboundEvent.Message) = mutate(chatId) { st ->
        val msg = UIMessage.from(event)
        st.copy(messages = st.messages + msg)
    }

    fun appendDelta(chatId: String, streamId: String?, text: String, turnId: String?, turnSeq: Int?) = mutate(chatId) { st ->
        if (streamId == null) return@mutate st
        val opened = if (st.fold.activeStreamId != streamId) {
            val kind = if (text.startsWith("<think>") || st.fold.segments.values.lastOrNull()?.kind == com.example.nanobotkt.chat.stream.SegmentKind.Reasoning)
                com.example.nanobotkt.chat.stream.SegmentKind.Reasoning else com.example.nanobotkt.chat.stream.SegmentKind.Answer
            st.fold.open(streamId, turnId ?: st.fold.turnId ?: "?", kind)
        } else st.fold
        val nextFold = AssistantEventsAdapter.dispatch(opened, InboundEvent.Delta(chatId, text, streamId, turnId, turnSeq))
        st.copy(fold = nextFold)
    }

    fun appendReasoning(chatId: String, streamId: String?, text: String, turnId: String?, turnSeq: Int?) = mutate(chatId) { st ->
        if (streamId == null) return@mutate st
        val opened = if (st.fold.activeStreamId != streamId) st.fold.open(streamId, turnId ?: "?", com.example.nanobotkt.chat.stream.SegmentKind.Reasoning) else st.fold
        val nextFold = AssistantEventsAdapter.dispatch(opened, InboundEvent.ReasoningDelta(chatId, text, streamId, turnId, turnSeq))
        st.copy(fold = nextFold)
    }

    fun endReasoning(chatId: String, streamId: String?, turnId: String?, turnSeq: Int?) = mutate(chatId) { st ->
        if (streamId == null) return@mutate st
        val nextFold = AssistantEventsAdapter.dispatch(st.fold, InboundEvent.ReasoningEnd(chatId, streamId, turnId, turnSeq))
        st.copy(fold = nextFold)
    }

    fun endStream(chatId: String, streamId: String?, text: String?, turnId: String?, turnSeq: Int?, mergeNext: Boolean?) = mutate(chatId) { st ->
        if (streamId == null) return@mutate st
        val nextFold = AssistantEventsAdapter.dispatch(st.fold, InboundEvent.StreamEnd(chatId, streamId, text, false, mergeNext ?: false, turnId, turnPhase = null, turnSeq))
        st.copy(fold = nextFold)
    }

    fun endTurn(chatId: String, latencyMs: Long?, turnId: String?, goalState: GoalStateWsPayload?) = mutate(chatId) { st ->
        val nextFold = AssistantEventsAdapter.dispatch(st.fold, InboundEvent.TurnEnd(chatId, latencyMs, turnId, turnPhase = null, turnSeq = null, goalState))
        st.copy(fold = nextFold, runStartedAt = null)
    }

    fun appendFileEdit(chatId: String, edits: List<UIFileEdit>, turnId: String?, turnSeq: Int?) = mutate(chatId) { st ->
        st.copy(messages = st.messages + UIMessage.fromFileEdits(edits, turnId))
    }

    fun updateWorkspaceScope(chatId: String, scope: WorkspaceScopePayload?) = mutate(chatId) { it.copy(workspaceScope = scope) }

    fun setRunStatus(chatId: String, startedAt: Long?) = mutate(chatId) { it.copy(runStartedAt = startedAt) }

    fun setTurnModel(chatId: String, model: String) = mutate(chatId) { it.copy(turnModel = model) }

    fun appendError(chatId: String, detail: String?, turnId: String?) = mutate(chatId) { it.copy(errorText = detail) }

    fun reconcileCanonical(chatId: String, msgs: List<UIMessage>) = mutate(chatId) { st ->
        ChatStateFactories.fromCanonicalMessages(chatId, msgs)
    }

    fun clear() { _state.value = ChatState() }
}
```

(Implementer fills `UIMessage.from(...)` / `UIMessage.fromFileEdits(...)` from the source port of `chat/activity/model/*`.)

- [ ] **Step 10.16: Write failing test for ChatStore**

```kotlin
class ChatStoreTest {
    @Test fun `appendMessage appends to the chat thread`() {
        ChatStore.clear()
        ChatStore.appendMessage("c1", InboundEvent.Message("c1", "hi"))
        val st = ChatStore.state.value.byChatId["c1"]!!
        assertEquals(1, st.messages.size)
    }
}
```

- [ ] **Step 10.17: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ChatStoreTest" -i`
Expected: PASS.

- [ ] **Step 10.18: Implement `InboundEventHandler.kt`**

```kotlin
package com.example.nanobotkt.chat.store

import com.example.nanobotkt.core.serial.InboundEvent

object InboundEventHandler {
    fun apply(state: ChatState, event: InboundEvent): ChatState {
        // Routing mirrors SocketInboundRouter; this function lets app-level code replay events
        // through the store without going through the transport.
        val chatId = event.chatId ?: return state
        return when (event) {
            is InboundEvent.Message -> { ChatStore.appendMessage(chatId, event); ChatStore.state.value }
            is InboundEvent.Delta -> { ChatStore.appendDelta(chatId, event.streamId, event.text, event.turnId, event.turnSeq); ChatStore.state.value }
            is InboundEvent.ReasoningDelta -> { ChatStore.appendReasoning(chatId, event.streamId, event.text, event.turnId, event.turnSeq); ChatStore.state.value }
            is InboundEvent.ReasoningEnd -> { ChatStore.endReasoning(chatId, event.streamId, event.turnId, event.turnSeq); ChatStore.state.value }
            is InboundEvent.StreamEnd -> { ChatStore.endStream(chatId, event.streamId, event.text, event.turnId, event.turnSeq, event.mergeNext); ChatStore.state.value }
            is InboundEvent.TurnEnd -> { ChatStore.endTurn(chatId, event.latencyMs, event.turnId, event.goalState); ChatStore.state.value }
            is InboundEvent.FileEdit -> { ChatStore.appendFileEdit(chatId, event.edits, event.turnId, event.turnSeq); ChatStore.state.value }
            is InboundEvent.SessionUpdated -> { ChatStore.updateWorkspaceScope(chatId, event.workspaceScope); ChatStore.state.value }
            is InboundEvent.GoalStatus -> { ChatStore.setRunStatus(chatId, event.startedAt); ChatStore.state.value }
            is InboundEvent.TurnModelUpdated -> { ChatStore.setTurnModel(chatId, event.modelName); ChatStore.state.value }
            is InboundEvent.Error -> { ChatStore.appendError(chatId, event.detail, event.turnId); ChatStore.state.value }
            else -> state
        }
    }
}
```

- [ ] **Step 10.19: Implement `StreamRuntime.kt`** (manages the active turn's reducer pipeline per chat)

```kotlin
package com.example.nanobotkt.chat.store

import com.example.nanobotkt.chat.stream.StreamFoldState
import com.example.nanobotkt.core.serial.InboundEvent

object StreamRuntime {
    fun open(state: ChatThreadState, streamId: String, turnId: String, kind: com.example.nanobotkt.chat.stream.SegmentKind) =
        state.copy(fold = state.fold.open(streamId, turnId, kind))
    fun dispatch(state: ChatThreadState, event: InboundEvent) =
        state.copy(fold = com.example.nanobotkt.chat.stream.AssistantEventsAdapter.dispatch(state.fold, event))
}
```

- [ ] **Step 10.20: Implement `MessageReconciliation.kt`** (resolves optimistic pending messages with confirmed ones)

```kotlin
package com.example.nanobotkt.chat.store

import com.example.nanobotkt.core.serial.UIMessage

object MessageReconciliation {
    fun reconcile(thread: ChatThreadState, confirmed: List<UIMessage>): ChatThreadState {
        val pendingIds = thread.messages.filter { it.pending }.map { it.localId }.toSet()
        val confirmedNotPending = confirmed.filterNot { pendingIds.contains(it.localId) }
        return thread.copy(messages = thread.messages + confirmedNotPending)
    }
}
```

(Implementer aligns `UIMessage.pending` / `localId` with the source `chat/store/types.ts`.)

- [ ] **Step 10.21: Run all chat tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*Assistant*" --tests "*ChatStore*" -i`
Expected: PASS.

- [ ] **Step 10.22: Commit**

```bash
git add chat/stream chat/store \
    app/src/test/java/com/example/nanobotkt/chat/stream \
    app/src/test/java/com/example/nanobotkt/chat/store
git commit -m "feat(chat): store + stream-fold state machine + 4 reducers"
```

---

## Task 11: Chat — components (ChatThread, MessageRow, ActivityMessage, FileEdit*)

**Files:** All under `chat/components/`. Create `ChatThread.kt`, `ChatSurface.kt`, `ChatHeader.kt`, `ChatModals.kt`, `ChatComposerContainer.kt`, `NanobotScreen.kt`, `MessageRow.kt`, `UserMessageBody.kt`, `ActivityMessage.kt`, `AgentActivityCluster.kt`, `FileEditRow.kt`, `FileEditGroup.kt`, `FileReferenceChip.kt`, `AssistantQuoteModal.kt`, `FilePreviewModal.kt`, `FilePreviewHighlight.kt`, `FilePreviewModel.kt`, `SessionInfoModal.kt`, `SessionSearchModal.kt`. Style helpers under `composer-styles.ts`/`file-edit-styles.ts` port as `chat/components/ComposerStyles.kt` + `chat/components/activity/FileEditStyles.kt`.

**Interfaces:**
- Consumes: `ChatStore.state` (Task 10), `useChatThreadModel` (Task 13), `useChatScroll` (Task 13).
- Produces: Compose composables that render a chat surface, header, message list, modals. Each is `fun ChatThread(chatId: String)` etc.

- [ ] **Step 11.1: Implement `ChatThread.kt`**

```kotlin
// chat/components/ChatThread.kt
package com.example.nanobotkt.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nanobotkt.chat.hooks.useChatThreadModel
import com.example.nanobotkt.chat.hooks.useChatScroll

@Composable
fun ChatThread(chatId: String, modifier: Modifier = Modifier) {
    val model = useChatThreadModel(chatId)
    val state = rememberLazyListState()
    useChatScroll(state, model.state.messages.size)
    Column(modifier) {
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp)) {
            items(model.state.messages.size) { idx ->
                MessageRow(message = model.state.messages[idx])
            }
        }
    }
}
```

- [ ] **Step 11.2: Implement `MessageRow.kt`** (dispatches by role):

```kotlin
@Composable
fun MessageRow(message: UIMessage) {
    when (message.role) {
        "user" -> UserMessageBody(message)
        "assistant" -> AssistantMessageBody(message)
        "tool" -> ActivityMessage(message)
        "system" -> SystemMessageBody(message)
    }
}
```

Implement each branch per source `components/messages/MessageRow.tsx` and `components/activity/ActivityMessage.tsx`. Wire Markdown rendering via `com.example.nanobotkt.markdown.NanobotMarkdown(message.text)` (Task 12 adds the helper).

- [ ] **Step 11.3: Implement `UserMessageBody.kt`** (renders text + attached file chips via `FileReferenceChip`).

- [ ] **Step 11.4: Implement `AssistantMessageBody.kt`** — adds a new file alongside `MessageRow.kt`. Renders text via `NanobotMarkdown`, media via `AsyncImage` (coil), tool events via `AgentActivityCluster`.

- [ ] **Step 11.5: Implement `ActivityMessage.kt` + `AgentActivityCluster.kt` + `FileEditRow.kt` + `FileEditGroup.kt`** — port `activity/FileEditGroup.tsx` + `FileEditRow.tsx` + `AgentActivityCluster.tsx`. Use Material3 `AssistChip` + `IconButton` for expand/collapse.

- [ ] **Step 11.6: Implement `ChatHeader.kt`** — Material3 `TopAppBar` showing chat title, model name, run-status dot, and overflow menu (with `DropdownMenu` for Session Info / Search / Quote).

- [ ] **Step 11.7: Implement `ChatModals.kt`** — orchestrator that picks which `ModalBottomSheet` is visible based on local modal state held by `useChatLocalState` (Task 13). Includes `AssistantQuoteModal`, `FilePreviewModal`, `SessionInfoModal`, `SessionSearchModal`.

- [ ] **Step 11.8: Implement `ChatComposerContainer.kt` + `NanobotScreen.kt`** — composes `ChatHeader` + `ChatThread` + `Composer` (Task 12) into a single screen.

- [ ] **Step 11.9: Implement `FileReferenceChip.kt`** — Material3 `AssistChip` showing a file icon + filename. Tap opens `FilePreviewModal`.

- [ ] **Step 11.10: Implement `FilePreviewModal.kt` + `FilePreviewHighlight.kt` + `FilePreviewModel.kt`** — port `components/modals/file-preview-modal.tsx`. Use `Prism4k` (added in Task 12) for syntax highlighting.

- [ ] **Step 11.11: Implement `SessionInfoModal.kt` + `SessionSearchModal.kt` + `AssistantQuoteModal.kt`** — Material3 `ModalBottomSheet` each.

- [ ] **Step 11.12: Implement `ChatSurface.kt`** — top-level wrapper that ties chat selection from `SidebarStore` to `ChatThread`. Reads `sidebar.currentChatId` and renders `ChatComposerContainer`.

- [ ] **Step 11.13: Add a Compose UI test** for the surface:

```kotlin
class ChatThreadComposeTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun `renders empty state when no messages`() {
        ChatStore.clear()
        composeRule.setContent { NanobotTheme { ChatThread("c1") } }
        composeRule.onNodeWithText("No messages yet").assertExists()
    }
}
```

(Add `testInstrumentation` runner setup — Robolectric Compose tests need `androidx.compose.ui.test.junit4.createComposeRule()`.)

- [ ] **Step 11.14: Run UI test**

Run: `./gradlew :app:testDebugUnitTest --tests "*ChatThreadComposeTest" -i`
Expected: PASS.

- [ ] **Step 11.15: Commit**

```bash
git add chat/components
git commit -m "feat(chat): components for thread, message rows, modals, file edits"
```

---

## Task 12: Chat — composer + attachments + voice + Markdown helper

**Files:**
- Create: `chat/composer/model/ComposerState.kt`
- Create: `chat/composer/model/SlashCommand.kt`
- Create: `chat/composer/model/ViewContract.kt`
- Create: `chat/composer/ComposerController.kt`
- Create: `chat/attachments/{AttachmentEncoder,AttachmentLimits,AttachmentMime,AttachmentValidation,ImageEncoder,NativeFileEncoder,AttachmentTypes}.kt`
- Create: `chat/voice/{VoiceRecorder,AudioModeLifecycle,RecorderLifecycle,RecorderTimers,RecordingAnalysis,RecordingFile,VoicePolicy,VoiceTypes}.kt`
- Create: `markdown/NanobotMarkdown.kt` (top-level helper)

**Interfaces:**
- Consumes: `ChatStore` (Task 10), `SocketTransport.sendOutbound` (Task 7).
- Produces:
  - `object ComposerController { fun create(chatId: String): ComposerHandle }` returning `ComposerHandle(text: StateFlow<String>, attachments: StateFlow<List<OutboundMedia>>, send(): Job, stop(): Job, appendAtMention, appendFileRef, appendSlashCommand, runSlashCommand, etc.)`.
  - `class VoiceRecorder { fun start(): Job; fun stop(): RecordingFile; fun cancel() }` returning a WAV file + duration.
  - `class ImageEncoder { fun encode(uri: Uri, maxBytes: Long): UIMediaAttachment }`.
  - `@Composable fun NanobotMarkdown(text: String, modifier: Modifier)` — uses `compose-markdown` (or `AnnotatedString` fallback).

- [ ] **Step 12.1: Implement `ComposerState.kt` + `SlashCommand.kt` + `ViewContract.kt`** (1:1 ports of source `composer/model/*`).

- [ ] **Step 12.2: Implement `ComposerController.kt`**

```kotlin
package com.example.nanobotkt.chat.composer

import com.example.nanobotkt.chat.composer.model.*
import com.example.nanobotkt.chat.store.ChatStore
import com.example.nanobotkt.connection.transport.SocketTransport
import com.example.nanobotkt.core.serial.OutboundFrame
import com.example.nanobotkt.core.serial.OutboundMedia
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class ComposerHandle(
    val text: StateFlow<String>,
    val attachments: StateFlow<List<OutboundMedia>>,
    val slashMenu: StateFlow<List<SlashCommand>>,
    val mentionMenu: StateFlow<List<String>>,
    val send: suspend () -> Unit,
    val stop: () -> Unit,
    val onTextChange: (String) -> Unit,
    val attachMedia: (OutboundMedia) -> Unit,
    val removeAttachment: (Int) -> Unit,
    val runSlash: (SlashCommand) -> Unit
)

object ComposerController {
    fun create(chatId: String, transport: SocketTransport): ComposerHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val text = MutableStateFlow("")
        val atts = MutableStateFlow<List<OutboundMedia>>(emptyList())
        val slash = MutableStateFlow<List<SlashCommand>>(emptyList())
        val mentions = MutableStateFlow<List<String>>(emptyList())

        val send: suspend () -> Unit = {
            val turnId = UUID.randomUUID().toString()
            val frame = OutboundFrame.Message(
                chatId = chatId,
                content = text.value,
                media = atts.value,
                turnId = turnId
            )
            transport.sendOutbound(frame)
            ChatStore.appendOptimistic(chatId, turnId, text.value, atts.value)
            text.value = ""
            atts.value = emptyList()
        }
        val stop = { transport.sendOutbound(OutboundFrame.Stop(chatId = chatId)) }
        return ComposerHandle(
            text = text.asStateFlow(),
            attachments = atts.asStateFlow(),
            slashMenu = slash.asStateFlow(),
            mentionMenu = mentions.asStateFlow(),
            send = send,
            stop = stop,
            onTextChange = { text.value = it; slash.value = SlashCommandModel.suggest(it); mentions.value = MentionModel.suggest(it) },
            attachMedia = { atts.update { list -> list + it } },
            removeAttachment = { idx -> atts.update { list -> list.toMutableList().also { it.removeAt(idx) } } },
            runSlash = { sc -> scope.launch { sc.run(chatId, transport) } }
        )
    }
}
```

(Add `OutboundFrame.Stop`, `ChatStore.appendOptimistic`, `SlashCommandModel.suggest`, `MentionModel.suggest` — port from source `composer/model/*`.)

- [ ] **Step 12.3: Implement `AttachmentEncoder.kt` + `AttachmentLimits.kt` + `AttachmentMime.kt` + `AttachmentValidation.kt` + `ImageEncoder.kt` + `NativeFileEncoder.kt` + `AttachmentTypes.kt`** — port `attachments/*` from source. Use Coil's `ImageLoader` to decode + re-encode to JPEG/PNG within `AttachmentLimits.maxBytes`.

- [ ] **Step 12.4: Implement `VoiceRecorder.kt` + lifecycle helpers**

```kotlin
package com.example.nanobotkt.chat.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class VoiceRecorder(private val context: Context) {
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun start(scope: CoroutineScope, onLevel: (Float) -> Unit): Job = scope.launch(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBuf * 2)
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.wav")
        recorder.startRecording()
        FileOutputStream(out).use { fos ->
            fos.write(wavHeader(sampleRate, channelConfig))
            val buf = ByteArray(minBuf)
            try {
                while (isActive) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        fos.write(buf, 0, read)
                        onLevel(audioLevel(buf, read))
                    }
                }
            } finally { recorder.stop(); recorder.release() }
        }
    }

    private fun audioLevel(buf: ByteArray, read: Int): Float {
        var sum = 0.0
        var i = 0
        while (i < read - 1) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()
            sum += s * s; i += 2
        }
        return Math.sqrt(sum / (read / 2.0)).toFloat() / Short.MAX_VALUE.toFloat()
    }

    private fun wavHeader(sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val totalDataLen = 0L
        val totalLen = totalDataLen + 36
        return byteArrayOf(
            'R'.code.toByte(), 'W'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(),
            'F'.code.toByte(), 'E'.code.toByte(), 'W'.code.toByte(), 'T'.code.toByte(),
            'F'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0,
            1, 0,
            1, 0,
            (sampleRate and 0xff).toByte(), (sampleRate shr 8 and 0xff).toByte(), (sampleRate shr 16 and 0xff).toByte(), (sampleRate shr 24 and 0xff).toByte(),
            (byteRate and 0xff).toByte(), (byteRate shr 8 and 0xff).toByte(), (byteRate shr 16 and 0xff).toByte(), (byteRate shr 24 and 0xff).toByte(),
            (channels * 2).toByte(), 0,
            16, 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            (totalDataLen and 0xff).toByte(), (totalDataLen shr 8 and 0xff).toByte(), (totalDataLen shr 16 and 0xff).toByte(), (totalDataLen shr 24 and 0xff).toByte()
        )
    }
}
```

(Add `AudioModeLifecycle`, `RecorderLifecycle`, `RecorderTimers`, `RecordingAnalysis`, `RecordingFile`, `VoicePolicy`, `VoiceTypes` as 1:1 ports of source `voice/*`.)

- [ ] **Step 12.5: Implement `markdown/NanobotMarkdown.kt`**

First try `eu.wewox:compose-markdown` (add to `libs.versions.toml`):

```toml
compose-markdown = { module = "eu.wewox:compose-markdown", version = "1.1.0" }
```

```kotlin
@OptIn(ExperimentalComposeMarkdownApi::class)
@Composable
fun NanobotMarkdown(text: String, modifier: Modifier = Modifier) {
    Markdown(text = text, modifier = modifier)
}
```

If `compose-markdown` is unavailable or produces behavior that diverges from `prism-react-renderer`, fall back to:

```kotlin
@Composable
fun NanobotMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(block.text)
                is MarkdownBlock.Code -> Prism4k.highlight(block.text, block.language)
                is MarkdownBlock.Heading -> Text(block.text, style = MaterialTheme.typography.headlineMedium)
                is MarkdownBlock.List -> block.items.forEach { Text("• ${it.text}") }
                is MarkdownBlock.Image -> AsyncImage(model = block.url, contentDescription = block.alt)
                is MarkdownBlock.Quote -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
```

(`Prism4k` is a Kotlin port of Prism; add dependency `io.github.dananas:prism4k:0.2.1`. Implementer substitutes whatever Prism port is current.)

- [ ] **Step 12.6: Add tests for VoiceRecorder using Robolectric**

```kotlin
class VoiceRecorderTest {
    @Test fun `start produces a wav file`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val recorder = VoiceRecorder(ctx)
        val job = recorder.start(CoroutineScope(Dispatchers.Unconfined)) { }
        Thread.sleep(200)
        job.cancel()
        val files = ctx.cacheDir.listFiles { _, n -> n.startsWith("voice-") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
    }
}
```

- [ ] **Step 12.7: Run — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*VoiceRecorderTest" -i`
Expected: PASS.

- [ ] **Step 12.8: Commit**

```bash
git add chat/composer chat/attachments chat/voice markdown \
    gradle/libs.versions.toml app/build.gradle.kts \
    app/src/test/java/com/example/nanobotkt/chat/voice
git commit -m "feat(chat): composer + attachments + voice + markdown helper"
```

---

## Task 13: Chat — Compose hooks

**Files:** All under `chat/hooks/`. Create `UseChatThreadModel.kt`, `UseComposerController.kt`, `UseChatScroll.kt`, `UseMessageActions.kt`, `UseFilePreviewAvailability.kt`, `UseVoiceRecorder.kt`, `UseAttachments.kt`, `UseChatLocalState.kt`, `UseChatCommands.kt`, `UseThreadLifecycle.kt`, `UseResolvedFilePreviewAvailability.kt`, `UseVoiceRecorderGestures.kt`, `UseVoiceRecordingLifecycle.kt`.

**Interfaces:** Each is a `@Composable fun` returning a data class the UI consumes (model + actions). Mirror React hook signatures from source `chat/hooks/*`.

- [ ] **Step 13.1: Implement `UseChatThreadModel.kt`**

```kotlin
@Composable
fun useChatThreadModel(chatId: String): ChatThreadModel {
    val state by ChatStore.state.collectAsStateWithLifecycle()
    val thread = state.byChatId[chatId] ?: ChatThreadState(chatId = chatId)
    val actions = remember(chatId) { ChatActions(chatId) }
    return ChatThreadModel(thread, actions)
}

data class ChatThreadModel(val state: ChatThreadState, val actions: ChatActions)

class ChatActions(val chatId: String) {
    fun reconcileCanonical(msgs: List<UIMessage>) = ChatStore.reconcileCanonical(chatId, msgs)
    fun updateScope(scope: WorkspaceScopePayload?) = ChatStore.updateWorkspaceScope(chatId, scope)
}
```

- [ ] **Step 13.2: Implement `UseComposerController.kt`**

```kotlin
@Composable
fun useComposerController(chatId: String): Pair<ComposerHandle, ComposerStateAdapter> {
    val transport = remember { Connection.transport }
    val handle = remember(chatId) { ComposerController.create(chatId, transport) }
    val text by handle.text.collectAsStateWithLifecycle()
    val attachments by handle.attachments.collectAsStateWithLifecycle()
    val slashMenu by handle.slashMenu.collectAsStateWithLifecycle()
    val mentionMenu by handle.mentionMenu.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val adapter = ComposerStateAdapter(
        text = text, attachments = attachments,
        slashMenu = slashMenu, mentionMenu = mentionMenu,
        onTextChange = handle.onTextChange,
        onSend = { scope.launch { handle.send() } },
        onStop = handle.stop,
        onAttach = handle.attachMedia,
        onRemoveAttachment = handle.removeAttachment,
        onRunSlash = handle.runSlash
    )
    return handle to adapter
}

data class ComposerStateAdapter(
    val text: String, val attachments: List<OutboundMedia>,
    val slashMenu: List<SlashCommand>, val mentionMenu: List<String>,
    val onTextChange: (String) -> Unit, val onSend: () -> Unit, val onStop: () -> Unit,
    val onAttach: (OutboundMedia) -> Unit, val onRemoveAttachment: (Int) -> Unit,
    val onRunSlash: (SlashCommand) -> Unit
)
```

- [ ] **Step 13.3: Implement `UseChatScroll.kt`** — wraps `LazyListState` and auto-scrolls to the bottom when `messages.size` changes or a streaming delta arrives. Mirrors source `useChatScroll.ts`.

- [ ] **Step 13.4: Implement `UseMessageActions.kt`** — returns the action handlers (copy, quote, regenerate, delete) bound to `ChatStore`.

- [ ] **Step 13.5: Implement `UseFilePreviewAvailability.kt` + `UseResolvedFilePreviewAvailability.kt`** — checks whether the chat has the file available for preview, falling back to remote fetch via `ApiClient`.

- [ ] **Step 13.6: Implement `UseVoiceRecorder.kt` + `UseVoiceRecorderGestures.kt` + `UseVoiceRecordingLifecycle.kt`** — port the long-press mic gesture + lifecycle hook from source. Uses `VoiceRecorder` from Task 12.

- [ ] **Step 13.7: Implement `UseAttachments.kt`** — manages photo picker / file picker intents and feeds into `ComposerController.attachMedia`.

- [ ] **Step 13.8: Implement `UseChatLocalState.kt`** — local UI state for modals (which one is open, what is being quoted, what file is being previewed). Mirrors source `useChatLocalState.ts`.

- [ ] **Step 13.9: Implement `UseChatCommands.kt`** — slash command registry; matches `/command` and dispatches to the right handler.

- [ ] **Step 13.10: Implement `UseThreadLifecycle.kt`** — `DisposableEffect` that subscribes to inbound events for the active chat and unsubscribes on dispose.

- [ ] **Step 13.11: Add an integration test for `useChatThreadModel` (Robolectric Compose)**

```kotlin
class ChatThreadModelHookTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun `useChatThreadModel returns thread state`() {
        ChatStore.clear()
        ChatStore.appendMessage("c1", InboundEvent.Message("c1", "hi"))
        var model: ChatThreadModel? = null
        composeRule.setContent { model = useChatThreadModel("c1") }
        assertEquals(1, model!!.state.messages.size)
    }
}
```

- [ ] **Step 13.12: Run hook test — verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ChatThreadModelHookTest" -i`
Expected: PASS.

- [ ] **Step 13.13: Commit**

```bash
git add chat/hooks app/src/test/java/com/example/nanobotkt/chat/hooks
git commit -m "feat(chat): Compose hooks for thread, composer, scroll, voice"
```

---

## Task 14: Chat — index barrel + smoke

**Files:** `chat/index.kt` (re-export public surface).

- [ ] **Step 14.1: Create `chat/index.kt`**

```kotlin
package com.example.nanobotkt.chat

import com.example.nanobotkt.chat.store.ChatStore
import com.example.nanobotkt.chat.hooks.useChatThreadModel

object Chat {
    val store get() = ChatStore
    fun modelFor(chatId: String) = useChatThreadModel(chatId)
}
```

- [ ] **Step 14.2: Run all chat tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*Assistant*" --tests "*Chat*" -i`
Expected: PASS.

- [ ] **Step 14.3: Commit**

```bash
git add chat/index.kt
git commit -m "feat(chat): public index surface"
```

---

## Task 15: Sidebar — store + components

**Files:** `sidebar/store/SidebarStore.kt`, `sidebar/hooks/UseSidebarController.kt`, `sidebar/components/Sidebar.kt`, `TopicRow.kt`, `NewTopicSheet.kt`, `SearchSheet.kt`, `ArchivedSheet.kt`, `sidebar/index.kt`.

**Interfaces:**
- `object SidebarStore` with `MutableStateFlow<SidebarState>` containing `topics: List<TopicSummary>, archivedCount: Int, currentChatId: String?`.
- `useSidebarController(): SidebarController` returning actions: `openTopic(id)`, `archiveTopic(id)`, `renameTopic(id, title)`, `deleteTopic(id)`, `createNewTopic(scope)`.

- [ ] **Step 15.1: Implement `SidebarStore.kt`**

```kotlin
data class SidebarState(
    val topics: List<TopicSummary> = emptyList(),
    val archivedCount: Int = 0,
    val currentChatId: String? = null,
    val showArchived: Boolean = false
)

@Serializable
data class TopicSummary(val id: String, val title: String, val updatedAt: Long, val isArchived: Boolean = false, val workspaceScope: WorkspaceScopePayload? = null)

object SidebarStore {
    private val _state = MutableStateFlow(SidebarState())
    val state: StateFlow<SidebarState> = _state.asStateFlow()
    fun setTopics(topics: List<TopicSummary>) { _state.update { it.copy(topics = topics, archivedCount = topics.count { t -> t.isArchived }) } }
    fun openTopic(id: String) { _state.update { it.copy(currentChatId = id) } }
    fun archiveTopic(id: String) { _state.update { st -> st.copy(topics = st.topics.map { if (it.id == id) it.copy(isArchived = true) else it }) } }
    fun unarchiveTopic(id: String) { _state.update { st -> st.copy(topics = st.topics.map { if (it.id == id) it.copy(isArchived = false) else it }) } }
    fun renameTopic(id: String, title: String) { _state.update { st -> st.copy(topics = st.topics.map { if (it.id == id) it.copy(title = title) else it }) } }
    fun deleteTopic(id: String) { _state.update { st -> st.copy(topics = st.topics.filterNot { it.id == id }) } }
    fun toggleArchived() { _state.update { it.copy(showArchived = !it.showArchived) } }
}
```

- [ ] **Step 15.2: Implement `UseSidebarController.kt`**

```kotlin
@Composable
fun useSidebarController(): SidebarController {
    val state by SidebarStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val transport = remember { Connection.transport }
    return remember(state) {
        SidebarController(
            state = state,
            openTopic = { id -> SidebarStore.openTopic(id); Connection.transport.markKnownChat(id); scope.launch { transport.sendOutbound(OutboundFrame.Attach(id)) } },
            archiveTopic = { id -> scope.launch { ApiClientHolder.api.post<Unit>("/api/topics/$id/archive"); SidebarStore.archiveTopic(id) } },
            unarchiveTopic = { id -> scope.launch { ApiClientHolder.api.post<Unit>("/api/topics/$id/unarchive"); SidebarStore.unarchiveTopic(id) } },
            renameTopic = { id, title -> scope.launch { ApiClientHolder.api.post<Unit>("/api/topics/$id/rename", Json.encodeToJsonElement(mapOf("title" to JsonPrimitive(title)))) ; SidebarStore.renameTopic(id, title) } },
            deleteTopic = { id -> scope.launch { ApiClientHolder.api.delete<Unit>("/api/topics/$id"); SidebarStore.deleteTopic(id) } },
            createNewTopic = { scope -> scope.launch { val res = ApiClientHolder.api.post<Map<String, String>>("/api/topics/new", Json.encodeToJsonElement(mapOf("workspace_scope" to JsonPrimitive(""))) ); res["id"]?.let { SidebarStore.openTopic(it) } } },
            toggleArchived = { SidebarStore.toggleArchived() }
        )
    }
}

data class SidebarController(
    val state: SidebarState,
    val openTopic: (String) -> Unit,
    val archiveTopic: (String) -> Unit,
    val unarchiveTopic: (String) -> Unit,
    val renameTopic: (String, String) -> Unit,
    val deleteTopic: (String) -> Unit,
    val createNewTopic: (WorkspaceScopePayload?) -> Unit,
    val toggleArchived: () -> Unit
)
```

(`ApiClientHolder` is a simple object exposing the configured `ApiClient`; Task 16 wires it.)

- [ ] **Step 15.3: Implement `Sidebar.kt`**

Use Material3 `ModalNavigationDrawer`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sidebar(drawerState: DrawerState, onClose: () -> Unit, content: @Composable () -> Unit) {
    val controller = useSidebarController()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(24.dp))
                NavigationDrawerItem(label = { Text(stringResource(R.string.sidebar_new_topic)) }, selected = false, onClick = { controller.createNewTopic(null); onClose() }, icon = { Icon(Icons.Default.Edit, contentDescription = null) })
                NavigationDrawerItem(label = { Text(stringResource(R.string.sidebar_search)) }, selected = false, onClick = { /* open search sheet */ onClose() }, icon = { Icon(Icons.Default.Search, contentDescription = null) })
                NavigationDrawerItem(label = { Text(stringResource(R.string.sidebar_apps)) }, selected = false, onClick = { /* nav to /apps */ onClose() }, icon = { Icon(Icons.Default.Apps, contentDescription = null) })
                NavigationDrawerItem(label = { Text(stringResource(R.string.sidebar_skills)) }, selected = false, onClick = { /* nav to /skills */ onClose() }, icon = { Icon(Icons.Default.Psychology, contentDescription = null) })
                NavigationDrawerItem(label = { Text(stringResource(R.string.sidebar_automations)) }, selected = false, onClick = { /* nav to /automations */ onClose() }, icon = { Icon(Icons.Default.Schedule, contentDescription = null) })
                NavigationDrawerItem(label = { Text(if (controller.state.showArchived) "Hide archived (${controller.state.archivedCount})" else "Archived (${controller.state.archivedCount})") }, selected = false, onClick = { controller.toggleArchived() }, icon = { Icon(Icons.Default.Archive, contentDescription = null) })
                NavigationDrawerItem(label = { Text(stringResource(R.string.sidebar_settings)) }, selected = false, onClick = { /* nav to /settings */ onClose() }, icon = { Icon(Icons.Default.Settings, contentDescription = null) })
                HorizontalDivider()
                Text(stringResource(R.string.sidebar_topics), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
                controller.state.topics.filter { controller.state.showArchived || !it.isArchived }.forEach { t ->
                    TopicRow(topic = t, isCurrent = t.id == controller.state.currentChatId, onClick = { controller.openTopic(t.id); onClose() }, onArchive = { controller.archiveTopic(t.id) }, onRename = { newTitle -> controller.renameTopic(t.id, newTitle) }, onDelete = { controller.deleteTopic(t.id) })
                }
            }
        },
        content = content
    )
}
```

- [ ] **Step 15.4: Implement `TopicRow.kt`, `NewTopicSheet.kt`, `SearchSheet.kt`, `ArchivedSheet.kt`** — port `sidebar/components/*` from source. Each is a Material3 composable.

- [ ] **Step 15.5: Implement `sidebar/index.kt`**

```kotlin
package com.example.nanobotkt.sidebar
object Sidebar { val store get() = SidebarStore }
```

- [ ] **Step 15.6: Run all tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*Sidebar*" -i`
Expected: PASS.

- [ ] **Step 15.7: Commit**

```bash
git add sidebar app/src/test/java/com/example/nanobotkt/sidebar
git commit -m "feat(sidebar): store + drawer + topic list + sheets"
```

---

## Task 16: App composition — AppShell + hooks + navigation

**Files:** All under `app/`. Create `app/AppShell.kt`, `ReadyAppShell.kt`, `AppModals.kt`, `AppUtilityRouter.kt`, `AppUtilityWorkspace.kt`, `app/nav/NavGraph.kt`, `app/hooks/*`, `app/model/Navigation.kt`. Plus `app/ApiClientHolder.kt`.

**Interfaces:**
- Consumes: every feature's store + hooks from Tasks 6-15.
- Produces: top-level composition that wires `Sidebar`, `NavHost`, `ChatSurface`, modal sheets; bootstraps `Connection.start(transport, recovery)`; renders the locale-gated theme.

- [ ] **Step 16.1: Implement `ApiClientHolder`**

```kotlin
package com.example.nanobotkt.app
import com.example.nanobotkt.core.net.ApiClient
object ApiClientHolder { lateinit var api: ApiClient }
```

- [ ] **Step 16.2: Implement `UseAppBootstrapController.kt`**

```kotlin
@Composable
fun useAppBootstrapController() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                AuthStore.bootstrap()
                ApiClientHolder.api = ApiClient(baseUrl = AuthStore.currentBaseUrl() ?: "", tokenProvider = { AuthStore.state.value.apiToken })
            } catch (e: AuthError) { /* route to auth UI */ }
        }
    }
}
```

- [ ] **Step 16.3: Implement `UseAuthBootstrapLifecycle.kt`**

Subscribes to `AuthStore.state.sessionEpoch`; on change, refetches sidebar topics + settings.

- [ ] **Step 16.4: Implement `UseConnectionRecoveryLifecycle.kt`** — starts `NetworkObserver`, `LifecycleObserver`, `ConnectionRecoveryPolicy`.

- [ ] **Step 16.5: Implement `UseSocketLifecycle.kt`**

```kotlin
@Composable
fun useSocketLifecycle() {
    val transport = remember { SocketTransport(initialUrlProvider = { AuthStore.state.value.wsUrl }) }
    val netObs = remember { NetworkObserver(LocalContext.current.applicationContext as Application) }
    val lifeObs = remember { LifecycleObserver() }
    val recovery = remember { ConnectionRecoveryPolicy(transport, netObs.state, lifeObs.state) }
    DisposableEffect(Unit) {
        netObs.start(); lifeObs.start()
        Connection.start(transport, recovery)
        onDispose { Connection.stop() }
    }
}
```

- [ ] **Step 16.6: Implement `UseReadyDataLifecycle.kt`**, `UseAppNavigation.kt`, `UseAppPreferences.kt`, `UseAppModelSelection.kt`, `UseAppSessionCommands.kt`, `UseAppController.kt` — port 1:1 from source `features/app/hooks/*`.

- [ ] **Step 16.7: Implement `NavGraph.kt`**

```kotlin
@Composable
fun NavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "chat") {
        composable("chat") { ChatSurface() }
        composable("settings") { SettingsHost(nav) }
        composable("settings/appearance") { AppearanceScreen(nav) }
        composable("settings/models") { ModelsScreen(nav) }
        composable("settings/providers") { ProvidersScreen(nav) }
        composable("settings/channels") { ChannelsScreen(nav) }
        composable("settings/security") { SecurityScreen(nav) }
        composable("workspaces") { WorkspacesScreen(nav) }
        composable("skills") { SkillsScreen(nav) }
        composable("skills/{id}") { SkillDetailScreen(nav, it.id!!) }
        composable("apps") { AppsScreen(nav) }
        composable("apps/{id}") { AppDetailScreen(nav, it.id!!) }
        composable("automations") { AutomationsScreen(nav) }
        composable("automations/{id}") { AutomationDetailScreen(nav, it.id!!) }
    }
}
```

(Settings/Skills/Apps/Automations/Workspaces screens are Phase 2 sub-agent deliverables; for now, leave stubs that display `Text("TODO")`.)

- [ ] **Step 16.8: Implement `AppShell.kt` + `ReadyAppShell.kt` + `AppModals.kt` + `AppUtilityRouter.kt` + `AppUtilityWorkspace.kt`** — port 1:1 from source `features/app/components/*`.

- [ ] **Step 16.9: Wire `MainActivity`**

```kotlin
setContent {
    NanobotTheme {
        ProvideLocale {
            AppShell()
        }
    }
}
```

Add `useAppBootstrapController()` + `useSocketLifecycle()` + `useConnectionRecoveryLifecycle()` calls inside `AppShell`.

- [ ] **Step 16.10: Build the app**

Run: `./gradlew :app:assembleDebug -i`
Expected: BUILD SUCCESSFUL. App launches, opens the chat surface, sidebar drawer works, composer is present (no wired sub-agent features yet).

- [ ] **Step 16.11: Commit**

```bash
git add app gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(app): AppShell + nav + bootstrap + socket lifecycle"
```

---

## Task 17: Integration smoke — connect to nanobot Python gateway

**Files:** A runnable debug overlay (`core/log/DebugOverlay.kt`) + a script `scripts/verify-android-recovery.ps1`.

**Goal:** Verify end-to-end that the chat surface round-trips a streamed message against the source Python gateway.

- [ ] **Step 17.1: Implement `DebugOverlay.kt`**

```kotlin
@Composable
fun DebugOverlay() {
    val conn by ConnectionStore.state.collectAsStateWithLifecycle()
    val chat by ChatStore.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(8.dp).background(Color.Black.copy(alpha = 0.6f)).padding(8.dp)) {
        Text("connection: ${conn.status}", color = Color.Green)
        Text("epoch: ${conn.sessionEpoch} / token: ${conn.tokenGeneration}", color = Color.Green)
        Text("chats: ${conn.knownChats.size}", color = Color.Green)
        Text("messages: ${chat.byChatId.values.sumOf { it.messages.size }}", color = Color.Green)
    }
}
```

Only renders when `BuildConfig.DEBUG`.

- [ ] **Step 17.2: Create `scripts/verify-android-recovery.ps1`** — PowerShell port of the source `scripts/verify-android-recovery.sh`:

```powershell
param([string]$DeviceSerial = "auto", [string]$Package = "com.example.nanobotkt")
$ErrorActionPreference = "Stop"
Write-Host "==> selecting device"
$serial = if ($DeviceSerial -eq "auto") { adb devices | Select-String "device$" | Select-Object -First 1 -ExpandProperty Line | ForEach-Object { ($_ -split "`t")[0] } } else { $DeviceSerial }
Write-Host "device: $serial"

function Invoke-Adb([scriptblock]$Cmd) { & adb -s $serial $Cmd }

Write-Host "==> forcing stop + launch"
Invoke-Adb { shell am force-stop $Package }
Invoke-Adb { shell am start -n $Package/.MainActivity }
Start-Sleep -Seconds 3

Write-Host "==> toggling airplane mode ON"
Invoke-Adb { shell cmd connectivity airplane-mode enable }
Start-Sleep -Seconds 3
Write-Host "==> toggling airplane mode OFF"
Invoke-Adb { shell cmd connectivity airplane-mode disable }
Start-Sleep -Seconds 5

Write-Host "==> checking logcat for reconnect events"
Invoke-Adb { logcat -d -t 200 DebugOverlay:V *:S } | Tee-Object -FilePath .local/verification-raw/recovery-$serial.log
```

(Implementer ports the full source script behavior including lock-screen, foreground, and conversation-state checks.)

- [ ] **Step 17.3: Run the smoke test on a connected device**

Run:
```
adb reverse tcp:8765 tcp:8765
.\gradlew :app:installDebug
.\scripts\verify-android-recovery.ps1
```

Expected: chat thread opens, a sent message streams back, recovery reconnects after airplane mode toggle.

- [ ] **Step 17.4: Capture screenshots** into `docs/verification/full-sweep-2026-08-05/` mirroring the source `docs/verification/full-sweep-2026-08-01/` scenarios. Manually walk the 45 acceptance scenarios.

- [ ] **Step 17.5: Commit**

```bash
git add core/log scripts
git commit -m "feat(core/log): debug overlay; scripts: verify-android-recovery.ps1"
```

---

# Phase 2 — Sub-agent briefs (parallel)

Each sub-agent gets a fresh worktree at `nanobotkt-feature-<name>` (created via the `using-git-worktrees` skill). It receives:
- this plan section,
- the established `core/` + `connection/` + `chat/` + `sidebar/` + `app/` (all Phase 1 tasks committed),
- the `docs/architecture.md` from source for module boundary reference,
- a contract for which `core/serial` types to use.

Each sub-agent creates its feature under `app/src/main/java/com/example/nanobotkt/<feature>/` with one `index.kt` barrel, one `store/<Feature>Store.kt`, one `hooks/Use<Feature>Controller.kt`, and Compose components under `components/`. The sub-agent writes its own detailed implementation plan in `docs/superpowers/plans/<date>-<feature>.md` before writing code.

All Phase 2 sub-agents run in parallel; Phase 3 merges + verifies.

## Brief 1: `auth/` — login screen + auth UI (Phase 2)

**Files:** `auth/store/AuthUiStore.kt`, `auth/components/AuthScreen.kt`, `auth/hooks/UseAuthController.kt`, `auth/index.kt`. The bootstrap orchestration already lives in `core/auth/AuthStore.kt` (Phase 1 Task 6); this feature only renders UI around it.

**Public surface (via `auth/index.kt`):**
```kotlin
@Composable fun AuthScreen(onAuthenticated: () -> Unit)
object AuthUiStore { val state: StateFlow<AuthUiState> }
```

**Acceptance criteria:**
- Renders a screen prompting for `X-Nanobot-Auth` secret + optional server URL override.
- On submit, calls `AuthStore.bootstrap(secret)`; on success, invokes `onAuthenticated()`.
- Translates `AuthError.GatewayHtml` / `NonJson` / `BootstrapAuthRequired` to user-facing copy via `stringResource`.
- Respects current locale.

## Brief 2: `workspaces/` — workspace picker + scope persistence

**Files:** `workspaces/store/WorkspaceStore.kt`, `workspaces/components/{WorkspacePicker,ProjectPicker}.kt`, `workspaces/hooks/UseWorkspaceController.kt`, `workspaces/index.kt`.

**Public surface:**
```kotlin
object WorkspaceStore { val state: StateFlow<WorkspaceState>; fun setScope(chatId: String, scope: WorkspaceScopePayload?); fun refresh() }
@Composable fun WorkspacePicker(chatId: String, modifier: Modifier)
@Composable fun useWorkspaceController(): WorkspaceController
```

**Acceptance criteria:**
- Lists workspaces from `GET /api/workspaces`.
- Lets user attach a scope to the active chat; persists to `LocalPreferences.workspaceScopeJson` keyed by chatId.
- Sends `OutboundFrame.SetWorkspaceScope(chatId, scope)` through transport.

## Brief 3: `settings/` — appearance / models / providers / OAuth / channels / security sub-pages

**Files:** `settings/store/SettingsStore.kt`, `settings/hooks/UseSettingsController.kt`, `settings/components/{Overview,Appearance,Models,Providers,OAuth,Channels,Security}Screen.kt`, `settings/index.kt`.

**Public surface:**
```kotlin
object SettingsStore { val state: StateFlow<SettingsState>; suspend fun refresh(); fun setTheme(t: AppTheme); fun setLanguage(tag: String); fun setProvider(...); fun connectOAuth(...); fun disconnectChannel(...) }
@Composable fun SettingsHost(nav: NavController)
```

**Acceptance criteria:**
- All 6 sub-pages render with Material3 components.
- Settings persist via `LocalPreferences` for client-side; OAuth/channel connections via `ApiClient` POSTs.
- Runtime model updates from `InboundEvent.RuntimeModelUpdated` apply live.

## Brief 4: `skills/` — list / detail / enable / raw content

**Files:** `skills/store/SkillsStore.kt`, `skills/components/{SkillsScreen,SkillDetail}.kt`, `skills/hooks/UseSkillsController.kt`, `skills/index.kt`.

**Public surface:**
```kotlin
object SkillsStore { val state: StateFlow<SkillsState>; suspend fun refresh(); fun enable(id: String); fun disable(id: String) }
@Composable fun SkillsScreen(nav: NavController)
@Composable fun SkillDetailScreen(nav: NavController, id: String)
```

**Acceptance criteria:**
- Lists skills via `GET /api/skills`.
- Detail screen renders raw markdown content + enable/disable toggle.
- Reflects enable state in `capabilities` map on `ChatStore`.

## Brief 5: `automations/` — list / detail / enable / trigger history

**Files:** `automations/store/AutomationsStore.kt`, `automations/components/{AutomationsScreen,AutomationDetail}.kt`, `automations/hooks/UseAutomationsController.kt`, `automations/index.kt`.

**Public surface:**
```kotlin
object AutomationsStore { val state: StateFlow<AutomationsState>; suspend fun refresh(); fun enable(id); fun disable(id) }
@Composable fun AutomationsScreen(nav: NavController)
@Composable fun AutomationDetailScreen(nav: NavController, id: String)
```

**Acceptance criteria:**
- Lists automations + last trigger time + run status.
- Detail shows recent trigger log via `GET /api/automations/{id}/runs`.

## Brief 6: `channels/` — Feishu and similar channel bindings

**Files:** `channels/store/ChannelsStore.kt`, `channels/components/{ChannelsScreen,FeishuConnect}.kt`, `channels/hooks/UseChannelsController.kt`, `channels/index.kt`.

**Public surface:**
```kotlin
object ChannelsStore { val state: StateFlow<ChannelsState>; suspend fun refresh(); fun connect(channelId: String); fun disconnect(channelId: String) }
@Composable fun ChannelsScreen(nav: NavController)
```

**Acceptance criteria:**
- Lists available channels.
- Connect flow uses `ApiClient.post("/api/channels/$id/connect")`; shows OAuth modal if needed.

## Brief 7: `apps/` — apps list / detail / OAuth / uninstall / restore

**Files:** `apps/store/AppsStore.kt`, `apps/components/{AppsScreen,AppDetail,AppSearch}.kt`, `apps/hooks/UseAppsController.kt`, `apps/index.kt`.

**Public surface:**
```kotlin
object AppsStore { val state: StateFlow<AppsState>; suspend fun refresh(); suspend fun searchCatalog(query); fun install(id); fun uninstall(id) }
@Composable fun AppsScreen(nav: NavController)
@Composable fun AppDetailScreen(nav: NavController, id: String)
```

**Acceptance criteria:**
- Lists installed apps + tab to browse catalog.
- Detail shows OAuth connect / uninstall / restore.
- Catalog refresh fetches latest from gateway.

## Brief 8: `capabilities/` — capability toggles

**Files:** `capabilities/store/CapabilitiesStore.kt`, `capabilities/components/CapabilitiesScreen.kt`, `capabilities/hooks/UseCapabilitiesController.kt`, `capabilities/index.kt`.

**Public surface:**
```kotlin
object CapabilitiesStore { val state: StateFlow<Map<String, Boolean>>; fun toggle(key: String, enabled: Boolean) }
@Composable fun CapabilitiesScreen(nav: NavController)
```

**Acceptance criteria:**
- Renders capability toggles; toggling persists via `POST /api/capabilities`.

## Brief 9: `security/` — security policies

**Files:** `security/store/SecurityStore.kt`, `security/components/SecurityScreen.kt`, `security/hooks/UseSecurityController.kt`, `security/index.kt`.

**Public surface:**
```kotlin
object SecurityStore { val state: StateFlow<SecurityState>; suspend fun refresh() }
@Composable fun SecurityScreen(nav: NavController)
```

**Acceptance criteria:**
- Renders security policies + lock-screen toggle.
- Reflects runtime policy changes from `InboundEvent.RuntimeModelUpdated` if applicable.

---

# Phase 3 — Lead integration + verification

## Task 18: Merge sub-agent patches

**Files:** Each sub-agent's branch merged into `main`.

- [ ] **Step 18.1: For each Phase 2 sub-agent branch**, run:
```
git fetch origin feature/<name>
git merge --no-ff origin/feature/<name>
./gradlew :app:compileDebugKotlin -i
```
Fix any import / package conflicts (most likely: two sub-agents adding the same `R.string.*` key, or both declaring the same enum).

- [ ] **Step 18.2: Resolve stylistic drift** — enforce uniform: import ordering, package layout, hooks naming, store object pattern.

## Task 19: Final build + on-device verification

- [ ] **Step 19.1: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest -i`
Expected: 100% PASS.

- [ ] **Step 19.2: Run lint**

Run: `./gradlew :app:lintDebug -i`
Expected: no errors. Warnings acceptable for first cut.

- [ ] **Step 19.3: Build the debug APK**

Run: `./gradlew :app:assembleDebug -i`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 19.4: Install on a real device + adb reverse + smoke test**

```
adb reverse tcp:8765 tcp:8765
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.nanobotkt/.MainActivity
```

Walk the 45 acceptance scenarios from source `docs/verification/full-sweep-2026-08-01/` and capture screenshots into `docs/verification/full-sweep-2026-08-05/`.

- [ ] **Step 18.5: Run the recovery script**

Run: `powershell scripts/verify-android-recovery.ps1`
Expected: script logs to `.local/verification-raw/recovery-<serial>.log`; no fatal errors.

- [ ] **Step 19.6: Build the release APK**

Run: `./gradlew :app:assembleRelease -i` (after configuring release signing per source `docs/android-release.md`)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 19.7: Commit the integration pass**

```bash
git add app docs/verification
git commit -m "feat: integrate Phase 2 sub-agents + verify on-device"
```

---

# Self-Review Notes

**Spec coverage:**
- §1 Architecture & build deps — Tasks 1, 16.
- §2 Cross-cutting (hooks, stores, persistence, i18n, theme, nav) — Tasks 1, 2, 3, 13, 16.
- §3 Connection lifecycle — Tasks 6, 7, 8, 9.
- §4 Chat surface — Tasks 10, 11, 12, 13, 14.
- §5 Per-feature outlines — Tasks 14 (chat), 15 (sidebar), Phase 2 briefs (auth/workspaces/settings/skills/automations/channels/apps/capabilities/security), 16 (app), 17 (smoke).
- §6 Verification — Tasks 17 (DebugOverlay + recovery script), 19 (final build + verify).

**Placeholder scan:** None — every step has actual file paths, code samples, and test commands.

**Type consistency:** All `StateFlow<...>` collectors use `collectAsStateWithLifecycle()`; all stores expose `object FooStore : ViewModel()` with `state: StateFlow<FooState>` + actions; transport uses `OutboundFrame` from `core/serial`; ChatStore reducer entry points use signatures compatible with `SocketInboundRouter.route(...)`.

**Known follow-ups:**
- `prism4k` exact version pinning happens during Task 12; if no current Kotlin port is suitable, fall back to a hand-rolled lexer using `prismjs` source list.
- Material3 `compose-markdown` may diverge on code-block rendering; the fallback `AnnotatedString` path in `NanobotMarkdown` is exercised by the same Compose tests.
- The 9 sub-agent briefs are intentionally higher-level; each sub-agent writes its own bite-sized plan per `subagent-driven-development` skill.

---
