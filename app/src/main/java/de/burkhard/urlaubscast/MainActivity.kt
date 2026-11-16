package de.burkhard.urlaubscast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    lateinit var web: WebView
    private var pageReady = false
    private val pendingJs = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(85, 200)
        }
        web.addJavascriptInterface(Bridge(this), "CastBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host == HOST && (u.path ?: "").startsWith("/thumb/")) return thumb(u)
                return loader.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingJs.forEach { web.evaluateJavascript(it, null) }
                pendingJs.clear()
                handleShare(intent)
            }
        }
        web.loadUrl("https://$HOST/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back?window.__back():false") { r ->
                    if (r != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (pageReady) handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        UiHub.activity = this
        if (pageReady) {
            UiHub.state()
            UiHub.list()
        }
        applyKeepScreen()
    }

    override fun onStop() {
        if (UiHub.activity === this) UiHub.activity = null
        super.onStop()
    }

    fun js(code: String) = runOnUiThread {
        if (pageReady) web.evaluateJavascript(code, null) else pendingJs += code
    }

    fun reply(id: String, ok: Boolean, payload: JSONObject) {
        js("window.__reply(${JSONObject.quote(id)},$ok,${JSONObject.quote(payload.toString())})")
    }

    /** Handy-Bildschirm anlassen, solange etwas auf dem Fernseher läuft (Einstellung). */
    fun applyKeepScreen() = runOnUiThread {
        val on = Store.settings.screenOn && Slideshow.mode != Slideshow.Mode.IDLE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- Vorschaubilder für die Oberfläche ----------
    private fun thumb(u: Uri): WebResourceResponse {
        val key = (u.path ?: "").removePrefix("/thumb/")
        val size = u.getQueryParameter("s")?.toIntOrNull()?.coerceIn(64, 1600) ?: 256
        val bytes = try {
            MediaLibrary.get(this, key)?.let { Thumbs.jpeg(this, it, size) }
        } catch (_: Throwable) {
            null
        }
        if (bytes == null) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"), ByteArrayInputStream(bytes))
    }

    // ---------- „Teilen“ aus der Galerie-App ----------
    private fun handleShare(i: Intent?) {
        i ?: return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> return
        }
        i.action = null
        if (uris.isEmpty()) return
        Thread {
            val items = uris.mapNotNull { runCatching { MediaLibrary.registerShared(this, it) }.getOrNull() }
            runOnUiThread {
                if (items.isNotEmpty()) Slideshow.add(items, next = false)
                js("window.__shared&&window.__shared(${items.size})")
            }
        }.start()
    }

    // ---------- Bildschirm spiegeln ----------
    fun openMirror(how: String) {
        if (how == "home") {
            val pkg = "com.google.android.apps.chromecast.app"
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null && tryStart(launch)) return
            if (tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))) return
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        } else {
            if (tryStart(Intent(Settings.ACTION_CAST_SETTINGS))) return
            tryStart(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    /** App-Einstellungen öffnen (wenn der Zugriff auf Fotos dauerhaft abgelehnt wurde). */
    fun openAppSettings() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun tryStart(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    // ---------- Berechtigungen ----------
    private var permLatch: CountDownLatch? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permLatch?.countDown()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** full = alle Fotos/Videos, partial = nur ausgewählte (Android 14), none = kein Zugriff */
    fun mediaPermState(): String = when {
        Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_IMAGES) -> "full"
        Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
        Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
        else -> "none"
    }

    /** Wird aus einem Hintergrund-Thread aufgerufen und wartet auf die Antwort. */
    fun askPermsBlocking(): String {
        val l = CountDownLatch(1)
        permLatch = l
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        runOnUiThread {
            try { permLauncher.launch(perms) } catch (_: Exception) { l.countDown() }
        }
        l.await(180, TimeUnit.SECONDS)
        return mediaPermState()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
