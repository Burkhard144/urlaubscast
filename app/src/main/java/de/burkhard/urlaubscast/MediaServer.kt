package de.burkhard.urlaubscast

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Collections

/**
 * Kleiner Webserver im Handy. Der Fernseher (Chromecast) holt sich darüber die Fotos und Videos.
 * Ausgeliefert wird nur, was gerade für die Show freigegeben wurde (zufällige Kennung pro Datei).
 */
class MediaServer private constructor(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    private val tokens = object : LinkedHashMap<String, ShowItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShowItem>?): Boolean = size > 400
    }

    fun urlFor(item: ShowItem, host: String): String {
        val token = synchronized(tokens) {
            tokens.entries.firstOrNull { it.value.key == item.key }?.key ?: newToken().also { tokens[it] = item }
        }
        val ext = if (item.video) "mp4" else "jpg"
        return "http://$host:$listeningPort/m/$token.$ext"
    }

    private fun newToken(): String {
        val b = ByteArray(9)
        rnd.nextBytes(b)
        return b.joinToString("") { String.format("%02x", it) }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: ""
        val item = if (path.startsWith("/m/")) {
            val token = path.removePrefix("/m/").substringBefore('.')
            synchronized(tokens) { tokens[token] }
        } else null
        val resp = try {
            when {
                item == null -> text(Status.NOT_FOUND, "Nicht gefunden")
                item.video -> video(session, item)
                else -> image(item)
            }
        } catch (e: Throwable) {
            text(Status.INTERNAL_ERROR, "Fehler")
        }
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache")
        return resp
    }

    private fun text(s: Status, msg: String): Response = NanoHTTPD.newFixedLengthResponse(s, NanoHTTPD.MIME_PLAINTEXT, msg)

    private fun image(item: ShowItem): Response {
        val bytes = Renderer.jpeg(ctx, item) ?: return text(Status.NOT_FOUND, "Bild nicht lesbar")
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    /** Videos mit „Range“-Unterstützung, damit der Fernseher spulen und puffern kann. */
    private fun video(session: IHTTPSession, item: ShowItem): Response {
        val pfd = ctx.contentResolver.openFileDescriptor(item.uri, "r") ?: return text(Status.NOT_FOUND, "Video nicht lesbar")
        val mime = item.castMime
        val total = pfd.statSize
        if (total <= 0) {
            return NanoHTTPD.newChunkedResponse(Status.OK, mime, ParcelFileDescriptor.AutoCloseInputStream(pfd))
        }
        var start = 0L
        var end = total - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            if (a.isEmpty()) {
                val n = b.toLongOrNull() ?: 0L
                start = (total - n).coerceAtLeast(0L)
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(total - 1)
            }
            if (start >= total || start > end) {
                pfd.close()
                val r = text(Status.RANGE_NOT_SATISFIABLE, "")
                r.addHeader("Content-Range", "bytes */$total")
                return r
            }
            partial = true
        }
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (start > 0) input.channel.position(start)
        val len = end - start + 1
        val resp = NanoHTTPD.newFixedLengthResponse(if (partial) Status.PARTIAL_CONTENT else Status.OK, mime, input, len)
        resp.addHeader("Accept-Ranges", "bytes")
        if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$total")
        return resp
    }

    companion object {
        @Volatile private var instance: MediaServer? = null
        private val rnd = SecureRandom()

        @Synchronized
        fun ensure(ctx: Context): MediaServer? {
            instance?.let { if (it.isAlive) return it }
            for (port in 8686..8699) {
                try {
                    val s = MediaServer(ctx.applicationContext, port)
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    instance = s
                    return s
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

/** Findet die eigene IP-Adresse im selben Netz wie der Fernseher (WLAN oder Handy-Hotspot). */
object Net {
    fun localIpFor(ctx: Context, target: InetAddress?): String? =
        fromInterfaces(target) ?: fromActiveNetwork(ctx)

    /** Notlösung: Adresse des aktiven Netzes laut Android. */
    private fun fromActiveNetwork(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun fromInterfaces(target: InetAddress?): String? {
        val cands = ArrayList<Pair<String, InterfaceAddress>>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in Collections.list(all)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) cands.add(ni.name to ia)
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (target is Inet4Address) {
            val hit = cands.firstOrNull { (_, ia) ->
                sameNet(ia.address as Inet4Address, target, ia.networkPrefixLength.toInt())
            }
            if (hit != null) return hit.second.address.hostAddress
        }
        val best = cands.minByOrNull { (name, ia) ->
            when {
                name.startsWith("wlan") -> 0
                name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 1
                name.startsWith("eth") -> 2
                ia.address.isSiteLocalAddress -> 3
                else -> 4
            }
        }
        return best?.second?.address?.hostAddress
    }

    private fun sameNet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        if (prefix !in 1..32) return false
        val x = ByteBuffer.wrap(a.address).int
        val y = ByteBuffer.wrap(b.address).int
        val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        return (x and mask) == (y and mask)
    }
}
