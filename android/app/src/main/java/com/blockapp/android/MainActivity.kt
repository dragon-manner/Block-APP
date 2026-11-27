package com.blockapp.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.sync.KeelBridge
import com.blockapp.android.ui.OnboardingScreen
import com.blockapp.android.ui.ReelEngineScreen
import com.blockapp.android.ui.RemoveProtectionScreen
import com.blockapp.android.ui.theme.BlockAppTheme

/**
 * The shared Keel UI (ui/, bundled into assets/ui by `make sync`) in a WebView, plus the three
 * screens that have to be native: setup (it walks Android's restricted-settings dance),
 * Remove protection (it drops Device Admin in-process and must always be reachable, invariant 1)
 * and the reel engine's counting settings.
 *
 * Every native screen goes back to the hub in one step, and the hub reaches Remove protection
 * from Devices → This phone, so back can never strand anyone on it.
 */
private enum class Screen { Hub, Setup, RemoveProtection, ReelEngine }

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private var screen by mutableStateOf(Screen.Hub)
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (DeviceAdminHelper.requiresSetup(this)) screen = Screen.Setup
        setContent {
            BlockAppTheme {
                Surface {
                    BackHandler(enabled = screen != Screen.Hub) { screen = Screen.Hub }
                    when (screen) {
                        Screen.Hub -> Hub()
                        Screen.Setup -> OnboardingScreen(
                            onBack = { screen = Screen.Hub },
                            onDone = { screen = Screen.Hub },
                            onRemoveProtection = { screen = Screen.RemoveProtection },
                        )
                        Screen.RemoveProtection -> RemoveProtectionScreen(onDone = { screen = Screen.Hub })
                        Screen.ReelEngine -> ReelEngineScreen(onBack = { screen = Screen.Hub })
                    }
                }
            }
        }
    }

    @Composable
    private fun Hub() {
        BackHandler(enabled = web?.canGoBack() == true) { web?.goBack() }
        AndroidView(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            factory = { obtainWebView().also { (it.parent as? ViewGroup)?.removeView(it) } },
        )
    }

    /** One WebView for the activity's life, so leaving for setup and back doesn't reload the UI. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        web?.let { return it }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val view = WebView(this)
        view.setBackgroundColor(Color.parseColor("#0E0F0C"))
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        // Debug builds only: lets the UI (an https asset origin) reach a dev keel-server over
        // plain http on the emulator host. Release keeps the default, which blocks it. Also
        // opens the WebView to chrome://inspect: a bridge or fetch failure in the UI is
        // otherwise invisible on the phone, since nothing surfaces the page's console.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            WebView.setWebContentsDebuggingEnabled(true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // Only the bundled UI runs in here, next to the bridge; any other page opens in the
            // browser, where it can't call KeelAndroid.
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ASSET_HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
        }
        view.addJavascriptInterface(
            KeelBridge(this) { target ->
                runOnUiThread {
                    screen = when (target) {
                        "removeProtection" -> Screen.RemoveProtection
                        "reelEngine" -> Screen.ReelEngine
                        else -> Screen.Setup
                    }
                }
            },
            "KeelAndroid",
        )
        view.loadUrl(Uri.parse("https://$ASSET_HOST/assets/ui/index.html").toString())
        web = view
        return view
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        (application as BlockApplication).onAppForeground()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
    }
}
