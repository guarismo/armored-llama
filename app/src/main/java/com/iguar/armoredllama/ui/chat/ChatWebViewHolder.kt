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
