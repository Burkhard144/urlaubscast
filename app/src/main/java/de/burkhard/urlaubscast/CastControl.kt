package de.burkhard.urlaubscast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Verbindung zum Fernseher über Google Cast (Chromecast built-in im waipu.tv Stick).
 * Geräte werden über den MediaRouter gesucht; wählt man eins aus, startet das Cast-Framework die Sitzung selbst.
 * Alle Methoden laufen im Haupt-Thread.
 */
object CastControl {
    interface Listener {
        fun onRemoteStatus(playerState: Int, idleReason: Int)
        fun onSession(connected: Boolean)
    }

    var listener: Listener? = null

    /** off | connecting | on | unavailable */
    @Volatile var connState: String = "off"
        private set
    @Volatile var deviceName: String? = null
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var cast: CastContext? = null
    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var discovering = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        try {
            @Suppress("DEPRECATION")
            val c = CastContext.getSharedInstance(app)
            cast = c
            c.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            val cur = c.sessionManager.currentCastSession
            if (cur != null && cur.isConnected) attach(cur)
        } catch (e: Throwable) {
            // z. B. keine Google-Play-Dienste
            connState = "unavailable"
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            connState = "connecting"
            deviceName = session.castDevice?.friendlyName
            changed()
        }
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = detach()
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionResuming(session: CastSession, sessionId: String) {
            connState = "connecting"
            changed()
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            connState = "connecting"
            changed()
        }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val st = client?.mediaStatus ?: return
            listener?.onRemoteStatus(st.playerState, st.idleReason)
        }
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = UiHub.devices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = UiHub.devices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = UiHub.devices()
    }

    private fun attach(s: CastSession) {
        client?.unregisterCallback(mediaCallback)
        session = s
        client = s.remoteMediaClient
        client?.registerCallback(mediaCallback)
        connState = "on"
        deviceName = s.castDevice?.friendlyName
        listener?.onSession(true)
        changed()
    }

    private fun detach() {
        client?.unregisterCallback(mediaCallback)
        client = null
        session = null
        connState = if (cast == null) "unavailable" else "off"
        listener?.onSession(false)
        changed()
    }

    private fun changed() {
        UiHub.state()
        if (discovering) UiHub.devices()
    }

    // ---------- Geräte suchen ----------

    fun startDiscovery() {
        main.post {
            val c = cast
            if (c == null) {
                UiHub.devices()
                return@post
            }
            val r = router ?: MediaRouter.getInstance(app).also { router = it }
            val sel = selector ?: (c.mergedSelector ?: MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()).also { selector = it }
            if (!discovering) {
                r.addCallback(sel, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
                discovering = true
            }
            UiHub.devices()
        }
    }

    fun stopDiscovery() {
        main.post {
            if (discovering) {
                router?.removeCallback(routerCallback)
                discovering = false
            }
        }
    }

    fun devicesJson(): JSONArray {
        val arr = JSONArray()
        val r = router ?: return arr
        val sel = selector ?: return arr
        for (rt in r.routes) {
            if (rt.isDefaultOrBluetooth || !rt.isEnabled || !rt.matchesSelector(sel)) continue
            arr.put(
                JSONObject().put("id", rt.id).put("name", rt.name).put("desc", rt.description ?: "")
                    .put("on", rt.isSelected)
                    .put("connecting", rt.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING)
            )
        }
        return arr
    }

    fun connect(id: String) {
        main.post { router?.routes?.firstOrNull { it.id == id }?.select() }
    }

    fun disconnect() {
        main.post { cast?.sessionManager?.endCurrentSession(true) }
    }

    // ---------- Wiedergabe ----------

    fun isConnected(): Boolean = session?.isConnected == true && client != null

    fun deviceAddress(): InetAddress? = try {
        session?.castDevice?.inetAddress
    } catch (_: Throwable) {
        null
    }

    fun load(url: String, mime: String, video: Boolean, title: String, done: (Boolean) -> Unit) {
        val c = client
        if (c == null) {
            done(false)
            return
        }
        val meta = MediaMetadata(if (video) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_PHOTO)
        meta.putString(MediaMetadata.KEY_TITLE, title)
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(meta)
            .build()
        val req = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()
        try {
            c.load(req).setResultCallback { r -> done(r.status.isSuccess) }
        } catch (_: Throwable) {
            done(false)
        }
    }

    fun pause() {
        try { client?.pause() } catch (_: Throwable) {}
    }

    fun play() {
        try { client?.play() } catch (_: Throwable) {}
    }

    fun stopMedia() {
        try { client?.stop() } catch (_: Throwable) {}
    }

    fun videoProgress(): Float? {
        val c = client ?: return null
        val d = c.streamDuration
        if (d <= 0) return null
        return (c.approximateStreamPosition.toFloat() / d).coerceIn(0f, 1f)
    }
}
