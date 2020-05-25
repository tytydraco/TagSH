package com.draco.tagsh

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /* Instantiate fresh web view for this activity */
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.webViewClient = CustomWebViewClient(this)
        webView.webChromeClient = WebChromeClient()

        /* Get html content passed to this activity */
        val htmlContent = intent.getStringExtra("content")
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", null, null)

        /* Show web view */
        setContentView(webView)
    }

    /* Use back button to operate the web view */
    override fun onBackPressed() {
        if (webView.canGoBack())
            webView.goBack()
        else
            super.onBackPressed()
    }
}