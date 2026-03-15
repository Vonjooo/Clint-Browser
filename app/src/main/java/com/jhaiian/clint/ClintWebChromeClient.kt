package com.jhaiian.clint

import android.webkit.WebChromeClient
import android.webkit.WebView

class ClintWebChromeClient : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        (view.context as? MainActivity)?.onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        super.onReceivedTitle(view, title)
        (view.context as? MainActivity)?.updateAddressBar(view.url ?: "")
    }
}
