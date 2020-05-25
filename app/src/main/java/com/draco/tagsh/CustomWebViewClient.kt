package com.draco.tagsh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat.startActivity

class CustomWebViewClient(private val context: Context) : WebViewClient() {
    /* If we try to navigate to a non-network URL, consider it an intent */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request != null) {
            val url = request.url.toString()
            if (!URLUtil.isNetworkUrl(url)) {
                val pm: PackageManager = context.packageManager
                try {
                    /* If this doesn't throw an exception, we can open the activity */
                    pm.getPackageInfo(url, PackageManager.GET_ACTIVITIES)

                    /* Handle the intent in userspace */
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(context, intent, null)
                    return true
                } catch (e: PackageManager.NameNotFoundException) {}
            }
        }

        /* Otherwise, handle it as usual */
        return super.shouldOverrideUrlLoading(view, request)
    }
}