package de.burkhard.urlaubscast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Hält die App wach, solange etwas auf dem Fernseher läuft:
 * Der Webserver muss die Bilder liefern und die Diashow weiterzählen – auch bei dunklem Handy-Bildschirm.
 * Zeigt dazu eine Benachrichtigung mit Pause / Weiter / Beenden.
 */
class KeepAliveService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Diashow auf dem Fernseher", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UrlaubsCast:diashow")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        } catch (_: Exception) {
        }
        try {
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "UrlaubsCast")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: Exception) {
        }
        when (intent?.action) {
            ACTION_TOGGLE -> Slideshow.toggle()
            ACTION_NEXT -> Slideshow.next()
            ACTION_STOP -> Slideshow.stop()
        }
        if (Slideshow.mode == Slideshow.Mode.IDLE) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun update() {
        val s = Slideshow
        val key = "${s.mode}|${s.paused}|${s.index}|${s.list.size}|${CastControl.deviceName}|${s.current?.key}"
        if (key == lastKey) return
        lastKey = key
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, build())
        } catch (_: Exception) {
        }
    }

    private fun build(): Notification {
        val s = Slideshow
        val title = when {
            s.mode == Slideshow.Mode.SHOW && s.paused -> "Diashow pausiert"
            s.mode == Slideshow.Mode.SHOW -> "Diashow läuft"
            else -> "Wird auf dem Fernseher gezeigt"
        }
        val text = buildString {
            if (s.mode == Slideshow.Mode.SHOW && s.list.isNotEmpty()) append("${s.index + 1} von ${s.list.size}")
            val dev = CastControl.deviceName
            if (dev != null) {
                if (isNotEmpty()) append(" · ")
                append(dev)
            }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { "UrlaubsCast" })
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (s.mode == Slideshow.Mode.SHOW || s.current?.video == true) {
            b.addAction(0, if (s.paused) "Weiter" else "Pause", action(ACTION_TOGGLE))
        }
        b.addAction(0, "Nächstes", action(ACTION_NEXT))
        b.addAction(0, "Beenden", action(ACTION_STOP))
        return b.build()
    }

    private fun action(a: String): PendingIntent = PendingIntent.getService(
        this, a.hashCode(),
        Intent(this, KeepAliveService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL = "diashow"
        private const val NOTIF_ID = 7
        const val ACTION_TOGGLE = "de.burkhard.urlaubscast.TOGGLE"
        const val ACTION_NEXT = "de.burkhard.urlaubscast.NEXT"
        const val ACTION_STOP = "de.burkhard.urlaubscast.STOP"

        @Volatile private var instance: KeepAliveService? = null

        fun sync(ctx: Context, on: Boolean) {
            val i = Intent(ctx, KeepAliveService::class.java)
            try {
                if (on) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
            } catch (_: Exception) {
            }
        }

        fun refresh() {
            instance?.update()
        }
    }
}
