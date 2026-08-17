package com.iguar.armoredllama.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
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
    private var loadedEpoch = 0

    /** Create-or-reuse the WebView, (re)loading [url] if it changed since the last load. */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun obtain(context: Context, url: String, epoch: Int): WebView {
        val view = webView ?: WebView(context).apply {
            // Explicit MATCH_PARENT height: a WRAP_CONTENT WebView puts Chromium in a content-growing
            // mode where CSS `100vh`/`100dvh` resolve to 0, collapsing the web UI's viewport-height
            // layout (blank centered content + blank sidebar) while innerHeight still reads correctly.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true // the llama-server web UI is a JS SPA
            settings.domStorageEnabled = true // conversations persist via IndexedDB
            webViewClient = WebViewClient()   // keep navigation in-place, not an external browser
            // The Compose host intercepts touch-move after the initial down, so drag gestures never
            // reach the WebView (taps work, scrolling doesn't — touchstart fires but touchmove never
            // does). Claim the gesture on touch-down so scroll moves stay with the WebView.
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false // don't consume — let the WebView handle the scroll itself
            }
            webView = this
        }
        if (loadedUrl != url) {
            loadedUrl = url
            loadedEpoch = epoch      // seed: a fresh page is already current for this epoch
            // Load only once the WebView has a real size. Loading before Compose lays it out means the
            // SvelteKit app does its first layout at height 0, latching wrong scroll-height/measurement
            // values — the message list then has almost no scroll range and its last content hides
            // behind the input bar until a manual scroll forces a reflow.
            loadWhenSized(view, url)
        }
        // AndroidView's factory must return a parentless view; detach from any previous host.
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    /** Reload if the server has (re)started since the page loaded — keeps the UI on the fresh backend. */
    fun onEpoch(epoch: Int) {
        if (epoch != loadedEpoch) {
            loadedEpoch = epoch
            webView?.reload()
        }
    }

    /** Manual reload (Chat header button). */
    fun reload() {
        webView?.reload()
    }

    private fun loadWhenSized(view: WebView, url: String) {
        if (view.width > 0 && view.height > 0) {
            view.loadUrl(url)
            return
        }
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int,
            ) {
                if (v.width > 0 && v.height > 0) {
                    v.removeOnLayoutChangeListener(this)
                    (v as WebView).loadUrl(url)
                }
            }
        })
    }

    /** Tear down for real — only from MainActivity.onDestroy. */
    fun destroy() {
        webView?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        webView = null
        loadedUrl = null
        loadedEpoch = 0
    }
}
