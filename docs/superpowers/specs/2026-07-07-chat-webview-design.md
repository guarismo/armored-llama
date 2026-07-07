# Design: Chat panel — in-app WebView for the llama-server web UI

**Date:** 2026-07-07
**Status:** Approved
**Component area:** `ui/chat/` (new), `ui/menu/MenuOverlay.kt`, `model/MonitorState.kt`,
`AndroidManifest.xml`, `res/xml/`

## 1. Summary

Add a **Chat** feature that opens llama-server's built-in web UI (`http://127.0.0.1:<port>/`)
inside the app, in a full-screen panel matching the existing Settings/Update/HF pattern. The
llama.cpp builds the app runs (b9775+) serve the SvelteKit web UI at `/` by default, so this is
pure surfacing — no server-side work.

Chosen approach: **in-app WebView panel** (over an external-browser intent or Chrome Custom
Tabs). It keeps the app a self-contained cockpit — Start → Chat → watch tok/s live — and matches
how every other feature navigates. Custom Tabs would add an `androidx.browser` dependency for no
benefit at a localhost URL.

## 2. Goals / Non-goals

**Goals**
- A **Chat** drawer row (first row, above Settings) that opens the web UI in-app.
- Clear affordance when the server is stopped: the row is dimmed with an explanatory subtitle,
  and tapping it does nothing.
- A streaming reply is **not** cut off by leaving the panel to look at the dashboard: the
  WebView is retained and keeps running in the background.
- Chat history survives panel exits and app restarts (the web UI persists conversations to
  IndexedDB; we enable DOM storage so that works).

**Non-goals (YAGNI)**
- No custom chat UI; the llama-server web UI is used as-is.
- No auto-starting the server from the Chat row.
- No WebView back-history chaining (`goBack()`): the web UI is a SPA; the panel's back arrow
  simply closes the panel.
- No exposing the chat to other devices (that is just the existing `host=0.0.0.0` behavior;
  Chat always targets loopback).

## 3. Components

### 3.1 `model/MonitorState.kt` (modified)
- Add `CHAT` to the `Panel` enum: `enum class Panel { MENU, CHAT, SETTINGS, RELEASE, HF }`.

### 3.2 `ui/menu/MenuOverlay.kt` (modified)
- `DrawerContent`: new **first** `DrawerRow` (above Settings):
  - Server RUNNING → icon tinted normally, title "Chat", subtitle "Talk to the running model",
    tap → `onNavigate(Panel.CHAT)`.
  - Otherwise → row rendered dimmed (muted tint/alpha), subtitle "start the server first",
    tap is a no-op. The hint is visible before tapping; no toast/snackbar machinery.
  - "Running" means `state.serverStatus == LlamaServerService.Status.RUNNING`.
- `MenuOverlay`: add `FullScreenPanel(visible = state.panel == Panel.CHAT) { ChatPanel(...) }`.

### 3.3 `ui/chat/ChatPanel.kt` (new)
- `PanelHeader("Chat", onBack)` on top; the retained WebView fills the rest via `AndroidView`.
- **Pure helper** `chatUrl(port: Int): String = "http://127.0.0.1:$port/"` — unit-tested.

### 3.4 `ui/chat/ChatWebViewHolder.kt` (new) — retained WebView lifecycle
- Holds a single lazily-created `WebView` for the life of the Activity.
- `obtain(context, url)`: creates the WebView on first call (settings: `javaScriptEnabled = true`,
  `domStorageEnabled = true`; a plain `WebViewClient` so links load in-place) and calls
  `loadUrl(url)`. On later calls, if `url` differs from the last-loaded URL (port changed),
  reloads with the new URL; otherwise returns the instance untouched.
- **Re-attach rule:** before returning, detach the WebView from any previous parent
  (`(webView.parent as? ViewGroup)?.removeView(webView)`) — `AndroidView`'s factory throws if
  the returned view already has a parent.
- **Background streaming:** leaving the panel only detaches the view. `onPause()` is deliberately
  never called, so JS timers and the in-flight SSE stream keep running and a generation
  completes while the user watches the dashboard.
- `destroy()`: detaches and destroys the WebView; called from `MainActivity.onDestroy`.

### 3.5 Cleartext-HTTP config (new + manifest edit)
At targetSdk 28, cleartext HTTP is blocked by default, so the WebView would refuse
`http://127.0.0.1`. Fix, scoped tightly:
- New `res/xml/network_security_config.xml`:
  ```xml
  <network-security-config>
      <base-config cleartextTrafficPermitted="false" />
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="false">127.0.0.1</domain>
      </domain-config>
  </network-security-config>
  ```
- `AndroidManifest.xml`: `android:networkSecurityConfig="@xml/network_security_config"` on
  `<application>`. Cleartext stays denied globally; HF/GitHub traffic is HTTPS and unaffected.

## 4. Data flow

1. Drawer open → Chat row state derived from `state.serverStatus`.
2. Tap (running) → `onNavigate(Panel.CHAT)` → `FullScreenPanel` shows `ChatPanel`.
3. `ChatPanel` → `ChatWebViewHolder.obtain(context, chatUrl(state.settings.port))` → attach.
4. Back arrow → existing `onBack` → panel closes; WebView stays alive, detached.
5. Re-enter → same instance re-attached; if the port changed, `obtain` reloads the new URL.
6. Activity destroyed → `destroy()`.

## 5. Error handling

| Condition | Behavior |
|---|---|
| Server stopped | Row dimmed ("start the server first"); tap no-op. |
| Server dies mid-chat | The web UI shows its own connection error; user backs out. No extra handling. |
| Port changed in Settings, server not restarted | Chat targets the configured port and won't connect until restart — same restart-to-apply caveat as every other setting. Documented, not special-cased. |
| WebView re-attach with existing parent | Prevented by the detach-before-return rule in `obtain`. |

## 6. Testing

- **Pure host-JVM:** `chatUrl(port)` formatting (e.g. `chatUrl(8080) == "http://127.0.0.1:8080/"`).
- **Manual on-device:** (1) server stopped → row dimmed, tap does nothing; (2) Start → row
  active → open Chat → web UI loads → send a message → reply streams while the TOK/S tile
  reacts; (3) back to dashboard mid-generation → re-enter Chat → the reply continued and is
  intact; (4) restart app → previous conversation still listed (IndexedDB).

## 7. File-by-file change list

**New:** `ui/chat/ChatPanel.kt`, `ui/chat/ChatWebViewHolder.kt`,
`res/xml/network_security_config.xml`, test `ChatUrlTest`.
**Modified:** `model/MonitorState.kt` (Panel.CHAT), `ui/menu/MenuOverlay.kt` (row + panel host),
`AndroidManifest.xml` (networkSecurityConfig), `MainActivity.kt` (destroy hook in `onDestroy`).

## 8. Risks / notes

- **Retained WebView memory** (~50–100 MB once Chat has been used) persists for the app's life.
  Accepted trade-off for uninterrupted generations; the fit estimator already leaves headroom.
- A detached WebView continuing JS/network is standard behavior but not a hard API contract;
  verified in the on-device pass (step 3 of testing).
- The web UI version follows the running llama-server binary — a downloaded runtime update also
  updates the chat UI for free.
