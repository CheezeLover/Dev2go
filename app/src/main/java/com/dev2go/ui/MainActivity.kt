package com.dev2go.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dev2go.ui.theme.OpenChamber as OC
import com.dev2go.ui.theme.Dev2goTheme
import java.io.ByteArrayInputStream

/**
 * Hosts OpenChamber's own mobile app in a WebView.
 *
 * This is the UI. OpenChamber ships a mobile surface (`mobile.html` -> `renderMobileApp`)
 * that is a direct opencode client, so shipping it means shipping the real interface rather
 * than an approximation of it.
 *
 * Three things had to be arranged for it to work on-device:
 *
 * - **It runs on loopback, not the filesystem.** Android gives a WebView no access to the
 *   APK's assets, so `WebUiServer` serves the built app over `127.0.0.1`. That also means
 *   same-origin with the file API.
 * - **Auth is Basic, not Bearer.** opencode rejects Bearer (verified: 401 vs 200), so the
 *   bundle is patched at build time by `scripts/patch-openchamber-auth.mjs`.
 * - **Nothing is proxied.** opencode serves its own API with CORS open, so the app's calls
 *   — including the long-lived `/api/event` SSE stream — go straight to it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var webUi: com.dev2go.runtime.WebUiServer? = null
    private var agent: com.dev2go.agent.AgentProcess? = null
    private var started = false
    private var storageRef: com.dev2go.runtime.PhoneStorage? = null

    private val mountFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        // Nothing downstream can read a folder the app was not granted, so persist the
        // grant now rather than at first use — otherwise it dies with the process and the
        // mount appears to work until the app is reopened.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            storageRef?.mount(uri)
        }
        webView?.reload()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storage = com.dev2go.runtime.PhoneStorage(this, filesDir)
        storage.restoreMounts()
        storageRef = storage

        val agentProcess = com.dev2go.agent.AgentProcess(this)
        agent = agentProcess
        agentProcess.installBundledIfPresent()

        val server = com.dev2go.runtime.WebUiServer(this, storage, agentProcess)
        webUi = server
        server.start()

        // The opencode process has to be up before the UI loads: the app pairs on mount and
        // has nowhere to go if there is nothing listening.
        if (!agentProcess.isRunning) {
            Thread({ agentProcess.start() }, "agent-boot").apply { isDaemon = true }.start()
        }

        setContent {
            Dev2goTheme {
                Column(
                    Modifier.fillMaxSize().background(OC.Surface),
                ) {
                    Box(Modifier.weight(1f)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                WebView(context).apply {
                                    webView = this
                                    configure(this)
                                    loadUrl(server.baseUrl + "/mobile.html")
                                }
                            },
                        )
                    }
                    if (agentProcess.isRunning) {
                        Text(
                            "agent on ${agentProcess.baseUrl}  ·  files at ${server.baseUrl}/d2go/roots",
                            color = OC.ForegroundMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.fillMaxWidth().padding(6.dp),
                        )
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The app keeps its pairing in localStorage; without this it re-pairs on every
            // launch and the user sees the connect screen each time.
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            // Everything the app loads is same-origin loopback or the on-device opencode
            // server. Mixed content is irrelevant here, but blocking it keeps an accidental
            // external http fetch from quietly working.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }
        view.setBackgroundColor(OC.Surface.toArgb())
        view.isVerticalScrollBarEnabled = true
        view.overScrollMode = WebView.OVER_SCROLL_NEVER
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // Stay inside. A link to an external site should not replace the app with a
                // web page the user did not ask for.
                val host = url.host
                if (host == "127.0.0.1" || host == "localhost") return false
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != "127.0.0.1" && url.host != "localhost") return null
                val path = url.path ?: return null
                if (!path.startsWith("/assets/")) return null
                // Assets are served by WebUiServer, which the WebView reaches directly;
                // intercepting them here would duplicate the MIME table for no gain.
                return null
            }
        }
    }

    override fun onDestroy() {
        webUi?.stop()
        agent?.stop()
        super.onDestroy()
    }
}