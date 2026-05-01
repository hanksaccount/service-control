package com.litman.servicecontrol

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

class SafeStreamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: "https://www.playimdb.com/title/tt0371746/"

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                SafeWebView(url)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SafeWebView(url: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                
                webViewClient = object : WebViewClient() {
                    private val blockedDomains = listOf(
                        "brightpathsignals.com",
                        "histats.com",
                        "a.nel.cloudflare.com",
                        "google-analytics.com",
                        "doubleclick.net"
                    )

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString() ?: return null
                        
                        // Logga för debugging (syns i logcat)
                        // Log.d("SafeStream", "Laddar: $requestUrl")

                        // 1. Blockera kända trackers/annonser
                        if (blockedDomains.any { requestUrl.contains(it) }) {
                            Log.w("SafeStream", "BLOCKERAD DOMÄN: $requestUrl")
                            return WebResourceResponse("text/plain", "UTF-8", null)
                        }

                        // 2. Surgical blocking inuti ryska domänen
                        if (requestUrl.contains("streamimdb.ru")) {
                            // Tillåt bara nödvändiga filer för videospelaren
                            val isAllowed = requestUrl.contains(".m3u8") || 
                                            requestUrl.contains(".ts") || 
                                            requestUrl.contains("/embed/") ||
                                            requestUrl.contains("player") ||
                                            requestUrl.contains(".js") // Vissa scripts behövs för spelaren
                            
                            if (!isAllowed) {
                                Log.w("SafeStream", "BLOCKERAD RYSS FIL: $requestUrl")
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                loadUrl(url)
            }
        }
    )
}
