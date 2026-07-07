# Chat WebView Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Chat" drawer entry that opens llama-server's built-in web UI (`http://127.0.0.1:<port>/`) in an in-app, retained WebView panel.

**Architecture:** New `Panel.CHAT` full-screen panel following the existing Settings/Update/HF pattern in `MenuOverlay`. A singleton `ChatWebViewHolder` keeps one WebView alive for the Activity's life so leaving the panel never cuts off a streaming reply; a network-security-config permits cleartext for `127.0.0.1` only (required at targetSdk 28).

**Tech Stack:** Kotlin, Jetpack Compose (`AndroidView`), `android.webkit.WebView`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-07-chat-webview-design.md`

## Global Constraints

- No new Gradle dependencies (WebView is a platform API).
- Cleartext HTTP permitted for domain `127.0.0.1` ONLY; `base-config cleartextTrafficPermitted="false"` keeps it denied everywhere else.
- Chat URL is exactly `http://127.0.0.1:<port>/` (loopback, trailing slash) built by `chatUrl(port: Int)`.
- Drawer row is the FIRST row (above Settings). Running subtitle: `Talk to the running model`. Stopped subtitle: `start the server first` (row dimmed, tap no-op).
- "Running" means `state.serverStatus == LlamaServerService.Status.RUNNING`.
- WebView: `javaScriptEnabled = true`, `domStorageEnabled = true`, plain `WebViewClient()`. NEVER call `onPause()` on it (background streaming must continue). Destroyed only in `MainActivity.onDestroy`.
- Package namespace is `com.iguar.armoredllama` (NOT armedllama).
- Work on branch `feat/chat-webview` off `main`.

---

### Task 1: `chatUrl` + `Panel.CHAT` + back-handler wiring

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/ui/chat/ChatUrl.kt`
- Test: `app/src/test/java/com/iguar/armoredllama/ui/chat/ChatUrlTest.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt` (Panel enum, ~line 21)
- Modify: `app/src/main/java/com/iguar/armoredllama/MainActivity.kt` (back-handler `when`, ~line 24)

**Interfaces:**
- Consumes: nothing new.
- Produces: `fun chatUrl(port: Int): String` in package `com.iguar.armoredllama.ui.chat`; `Panel.CHAT` enum constant. Task 3 uses both.

Note: the spec placed `chatUrl` in `ChatPanel.kt`; it lives in its own file `ChatUrl.kt` so the host-JVM test never loads a class that imports WebView/Compose. Same package, same unit — accepted deviation.

- [ ] **Step 1: Create branch**

```bash
git checkout -b feat/chat-webview main
```

- [ ] **Step 2: Write the failing test**

`app/src/test/java/com/iguar/armoredllama/ui/chat/ChatUrlTest.kt`:

```kotlin
package com.iguar.armoredllama.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUrlTest {
    @Test fun chatUrl_buildsLoopbackUrlFromPort() {
        assertEquals("http://127.0.0.1:8080/", chatUrl(8080))
    }

    @Test fun chatUrl_usesGivenPortVerbatim() {
        assertEquals("http://127.0.0.1:9001/", chatUrl(9001))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armoredllama.ui.chat.ChatUrlTest"`
Expected: FAIL to compile — `unresolved reference: chatUrl`.

- [ ] **Step 4: Write minimal implementation**

`app/src/main/java/com/iguar/armoredllama/ui/chat/ChatUrl.kt`:

```kotlin
package com.iguar.armoredllama.ui.chat

/** The llama-server web UI address for a configured port — Chat always targets loopback. */
fun chatUrl(port: Int): String = "http://127.0.0.1:$port/"
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iguar.armoredllama.ui.chat.ChatUrlTest"`
Expected: BUILD SUCCESSFUL (2/2 pass).

- [ ] **Step 6: Add `Panel.CHAT`**

In `app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt` change:

```kotlin
/** Which menu surface is showing over the dashboard (null = dashboard only). */
enum class Panel { MENU, SETTINGS, RELEASE, HF }
```

to:

```kotlin
/** Which menu surface is showing over the dashboard (null = dashboard only). */
enum class Panel { MENU, CHAT, SETTINGS, RELEASE, HF }
```

- [ ] **Step 7: Fix the now-non-exhaustive back handler**

In `app/src/main/java/com/iguar/armoredllama/MainActivity.kt`, the `when` in `onCreate` no longer compiles (missing CHAT branch). Change:

```kotlin
            when (viewModel.state.panel) {
                Panel.MENU -> viewModel.closeMenu()
                Panel.SETTINGS, Panel.RELEASE, Panel.HF -> viewModel.backToMenu()
                null -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
```

to:

```kotlin
            when (viewModel.state.panel) {
                Panel.MENU -> viewModel.closeMenu()
                Panel.CHAT, Panel.SETTINGS, Panel.RELEASE, Panel.HF -> viewModel.backToMenu()
                null -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
```

- [ ] **Step 8: Full unit suite + build green**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL (all tests pass; no non-exhaustive-when errors).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/chat/ChatUrl.kt \
        app/src/test/java/com/iguar/armoredllama/ui/chat/ChatUrlTest.kt \
        app/src/main/java/com/iguar/armoredllama/model/MonitorState.kt \
        app/src/main/java/com/iguar/armoredllama/MainActivity.kt
git commit -m "feat(chat): chatUrl helper + Panel.CHAT + back-handler branch"
```

---

### Task 2: `ChatWebViewHolder` + cleartext-loopback config + destroy hook

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/ui/chat/ChatWebViewHolder.kt`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (`<application>` attribute, line 8-17)
- Modify: `app/src/main/java/com/iguar/armoredllama/MainActivity.kt` (add `onDestroy`)

**Interfaces:**
- Consumes: nothing from Task 1 (independent Android glue).
- Produces: `object ChatWebViewHolder` with `fun obtain(context: Context, url: String): WebView` and `fun destroy()`. Task 3's `ChatPanel` calls `obtain`.

This is Android glue (WebView, manifest) — no host-JVM test; verified by build here and on-device in Task 3.

- [ ] **Step 1: Write `ChatWebViewHolder`**

`app/src/main/java/com/iguar/armoredllama/ui/chat/ChatWebViewHolder.kt`:

```kotlin
package com.iguar.armoredllama.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Holds one lazily-created WebView for the life of the Activity so leaving the Chat panel does
 * not cut off an in-flight generation: the detached WebView keeps running its JS and the open
 * SSE stream (onPause is deliberately never called on it). Destroyed only from
 * MainActivity.onDestroy.
 */
object ChatWebViewHolder {
    private var webView: WebView? = null
    private var loadedUrl: String? = null

    /** Create-or-reuse the WebView, (re)loading [url] if it changed since the last load. */
    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context, url: String): WebView {
        val view = webView ?: WebView(context).apply {
            settings.javaScriptEnabled = true // the llama-server web UI is a JS SPA
            settings.domStorageEnabled = true // conversations persist via IndexedDB
            webViewClient = WebViewClient()   // keep navigation in-place, not an external browser
            webView = this
        }
        if (loadedUrl != url) {
            view.loadUrl(url)
            loadedUrl = url
        }
        // AndroidView's factory must return a parentless view; detach from any previous host.
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    /** Tear down for real — only from MainActivity.onDestroy. */
    fun destroy() {
        webView?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        webView = null
        loadedUrl = null
    }
}
```

- [ ] **Step 2: Write the network security config**

`app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Cleartext stays denied app-wide; only the local llama-server loopback is exempt.
         Required because targetSdk 28 blocks cleartext HTTP by default and the Chat WebView
         loads http://127.0.0.1:<port>/. -->
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 3: Reference it from the manifest**

In `app/src/main/AndroidManifest.xml`, add one attribute to `<application>`:

```xml
    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:extractNativeLibs="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ArmoredLlama">
```

- [ ] **Step 4: Destroy hook in MainActivity**

In `app/src/main/java/com/iguar/armoredllama/MainActivity.kt` add the import and override (after `onCreate`):

```kotlin
import com.iguar.armoredllama.ui.chat.ChatWebViewHolder
```

```kotlin
    override fun onDestroy() {
        ChatWebViewHolder.destroy()
        super.onDestroy()
    }
```

- [ ] **Step 5: Build green**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/chat/ChatWebViewHolder.kt \
        app/src/main/res/xml/network_security_config.xml \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/com/iguar/armoredllama/MainActivity.kt
git commit -m "feat(chat): retained ChatWebViewHolder + cleartext-loopback network security config"
```

---

### Task 3: `ChatPanel` + drawer row + panel host

**Files:**
- Create: `app/src/main/java/com/iguar/armoredllama/ui/chat/ChatPanel.kt`
- Modify: `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuOverlay.kt` (drawer rows ~line 140; panel hosts ~line 77; `DrawerRow` ~line 151)

**Interfaces:**
- Consumes: `chatUrl(port)` and `Panel.CHAT` (Task 1); `ChatWebViewHolder.obtain(context, url)` (Task 2); existing `PanelHeader(title, onBack)`, `FullScreenPanel`, `MonitorUiState`, `LlamaServerService.Status`.
- Produces: `@Composable fun ChatPanel(state: MonitorUiState, onBack: () -> Unit)`.

- [ ] **Step 1: Write `ChatPanel`**

`app/src/main/java/com/iguar/armoredllama/ui/chat/ChatPanel.kt`:

```kotlin
package com.iguar.armoredllama.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.iguar.armoredllama.model.MonitorUiState
import com.iguar.armoredllama.ui.menu.PanelHeader

/**
 * Full-screen Chat panel hosting the retained llama-server web UI WebView. The WebView instance
 * outlives this composable (see ChatWebViewHolder), so backing out mid-generation doesn't cut
 * the reply off. imePadding keeps the web UI's input above the keyboard.
 */
@Composable
fun ChatPanel(state: MonitorUiState, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        PanelHeader("Chat", onBack)
        AndroidView(
            factory = { ctx -> ChatWebViewHolder.obtain(ctx, chatUrl(state.settings.port)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
```

Note: `.weight(1f)` needs no import — it is a `ColumnScope` member available because
`AndroidView` is called inside the `Column` lambda.

- [ ] **Step 2: Add `enabled` support to `DrawerRow`**

In `app/src/main/java/com/iguar/armoredllama/ui/menu/MenuOverlay.kt`, replace the `DrawerRow` composable:

```kotlin
@Composable
private fun DrawerRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    badge: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = MonitorTheme.colors
    val iconTint = if (enabled) c.accent else c.muted
    val titleColor = if (enabled) c.text else c.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(c.tile),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            } else {
                Text("🤗", style = MonitorType.bodyLabel)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MonitorType.bodyLabel, color = titleColor)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.good.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(badge, style = MonitorType.monoCaption, color = c.good)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MonitorType.monoCaption, color = c.muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted)
    }
}
```

(Only three lines change vs the current version: the `enabled` parameter, the two tint/color
vals, and `clickable(enabled = enabled, ...)` — the rest is repeated verbatim for context.)

- [ ] **Step 3: Add the Chat row (first) and panel host**

Same file. Add imports:

```kotlin
import androidx.compose.material.icons.automirrored.filled.Chat
import com.iguar.armoredllama.server.LlamaServerService
import com.iguar.armoredllama.ui.chat.ChatPanel
```

In `DrawerContent`, insert the Chat row ABOVE the Settings row:

```kotlin
            val chatReady = state.serverStatus == LlamaServerService.Status.RUNNING
            DrawerRow(
                Icons.AutoMirrored.Filled.Chat,
                "Chat",
                if (chatReady) "Talk to the running model" else "start the server first",
                enabled = chatReady,
            ) { onNavigate(Panel.CHAT) }
            DrawerRow(Icons.Filled.Settings, "Settings", "Context, threads, optimizations") { onNavigate(Panel.SETTINGS) }
```

In `MenuOverlay`, add the panel host BEFORE the SETTINGS one (order of hosts doesn't matter
functionally; first keeps it grouped with the drawer order):

```kotlin
    FullScreenPanel(visible = state.panel == Panel.CHAT) {
        ChatPanel(state, onBack)
    }
```

- [ ] **Step 4: Build + full unit suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Install and launch on device**

Run: `./gradlew :app:installDebug`
then `adb shell monkey -p com.iguar.armoredllama -c android.intent.category.LAUNCHER 1`
Expected: Installed on 1 device; app launches.

- [ ] **Step 6: On-device manual verification (requires the user — agents cannot tap the screen)**

1. Server stopped → open drawer → Chat row is FIRST, dimmed, subtitle "start the server first"; tapping does nothing.
2. Tap Start, wait for "listening" in the log → drawer → Chat row active ("Talk to the running model") → tap → web UI loads.
3. Send a message → reply streams; back out mid-reply → TOK/S tile on the dashboard is non-zero → re-enter Chat → the reply continued/completed intact.
4. Hardware back inside Chat returns to the dashboard (panel closes, app stays).
5. Kill and relaunch the app → Chat again → previous conversation listed (IndexedDB survived).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/iguar/armoredllama/ui/chat/ChatPanel.kt \
        app/src/main/java/com/iguar/armoredllama/ui/menu/MenuOverlay.kt
git commit -m "feat(chat): Chat drawer row + in-app WebView panel"
```

---

## Final

After all tasks: whole-branch review, then `superpowers:finishing-a-development-branch`
(merge `feat/chat-webview` → `main`). Update `IMPLEMENTATION_NOTES.md` "Where things live"
table (`ui/chat/`) and Wiring Status with a Chat entry during finish-up.
