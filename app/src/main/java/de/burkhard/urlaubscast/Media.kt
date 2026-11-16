package de.burkhard.urlaubscast

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Ein Foto oder Video, das gezeigt werden kann. key: "i123" (Foto), "v456" (Video) oder "s…" (über „Teilen“ erhalten). */
data class ShowItem(
    val key: String,
    val uri: Uri,
    val video: Boolean,
    val mime: String,
    val date: Long,        // Aufnahmezeit in ms
    val durationMs: Long,  // nur bei Videos
    val name: String,
    val bucket: String,    // Album / Ordner
) {
    /** Was der Fernseher geliefert bekommt: Fotos immer als JPEG (auch HEIC), Videos möglichst als MP4. */
    val castMime: String
        get() = if (!video) "image/jpeg" else when (mime.lowercase()) {
            "video/mp4", "video/webm" -> mime.lowercase()
            else -> "video/mp4"
        }

    val title: String
        get() = if (date > 0) SimpleDateFormat("EEEE, d. MMMM yyyy · HH:mm", Locale.GERMANY).format(Date(date)) else name

    fun toJson(): JSONObject = JSONObject()
        .put("k", key).put("u", uri.toString()).put("v", video).put("m", mime)
        .put("d", date).put("du", durationMs).put("n", name).put("b", bucket)

    fun uiJson(): JSONObject = JSONObject()
        .put("k", key).put("v", video).put("d", date / 1000).put("du", durationMs / 1000).put("n", name).put("b", bucket)

    companion object {
        fun from(o: JSONObject): ShowItem? {
            val k = o.optString("k")
            val u = o.optString("u")
            if (k.isEmpty() || u.isEmpty()) return null
            return ShowItem(k, Uri.parse(u), o.optBoolean("v"), o.optString("m", ""), o.optLong("d"), o.optLong("du"), o.optString("n", ""), o.optString("b", ""))
        }

        fun listToJson(items: List<ShowItem>): JSONArray {
            val a = JSONArray()
            for (it in items) a.put(it.toJson())
            return a
        }

        fun listFrom(s: String?): List<ShowItem> {
            if (s.isNullOrEmpty()) return emptyList()
            val a = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
            val out = ArrayList<ShowItem>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                from(o)?.let { out.add(it) }
            }
            return out
        }
    }
}

/** Liest Fotos und Videos aus der Galerie des Handys (MediaStore). */
object MediaLibrary {
    private val known = ConcurrentHashMap<String, ShowItem>()

    fun remember(items: Collection<ShowItem>) {
        for (it in items) known[it.key] = it
    }

    fun scan(ctx: Context): List<ShowItem> {
        val out = ArrayList<ShowItem>()
        query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, null, null, out)
        query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, null, null, out)
        out.sortByDescending { it.date }
        remember(out)
        return out
    }

    fun get(ctx: Context, key: String): ShowItem? {
        known[key]?.let { return it }
        val video = when (key.firstOrNull()) {
            'i' -> false
            'v' -> true
            else -> return null
        }
        val id = key.drop(1).toLongOrNull() ?: return null
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<ShowItem>(1)
        query(ctx, base, video, "${BaseColumns._ID}=?", arrayOf(id.toString()), out)
        val item = out.firstOrNull() ?: return null
        known[item.key] = item
        return item
    }

    private fun query(ctx: Context, base: Uri, video: Boolean, sel: String?, args: Array<String>?, out: MutableList<ShowItem>) {
        val cols = mutableListOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        if (video) cols.add(MediaStore.MediaColumns.DURATION)
        try {
            ctx.contentResolver.query(base, cols.toTypedArray(), sel, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
                val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val iTaken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val iAdded = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val iDur = if (video) c.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val taken = if (iTaken >= 0 && !c.isNull(iTaken)) c.getLong(iTaken) else 0L
                    val added = if (iAdded >= 0 && !c.isNull(iAdded)) c.getLong(iAdded) * 1000L else 0L
                    out.add(
                        ShowItem(
                            key = (if (video) "v" else "i") + id,
                            uri = ContentUris.withAppendedId(base, id),
                            video = video,
                            mime = (if (iMime >= 0) c.getString(iMime) else null) ?: (if (video) "video/mp4" else "image/jpeg"),
                            date = if (taken > 0) taken else added,
                            durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L,
                            name = (if (iName >= 0) c.getString(iName) else null) ?: "",
                            bucket = (if (iBucket >= 0) c.getString(iBucket) else null) ?: "",
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // keine Berechtigung – die Oberfläche fragt danach
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Ein über „Teilen“ empfangenes Foto/Video aufnehmen. */
    fun registerShared(ctx: Context, uri: Uri): ShowItem? {
        val cr = ctx.contentResolver
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase(Locale.ROOT)
        val mime = cr.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: return null
        val video = mime.startsWith("video/")
        if (!video && !mime.startsWith("image/")) return null
        // Stammt es aus der Galerie, direkt den Galerie-Eintrag nehmen
        if (uri.authority == MediaStore.AUTHORITY) {
            val id = uri.lastPathSegment?.toLongOrNull()
            if (id != null) get(ctx, (if (video) "v" else "i") + id)?.let { return it }
        }
        var name = uri.lastPathSegment ?: "Geteilt"
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        } catch (_: Exception) {
        }
        var dur = 0L
        if (video) {
            try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    r.release()
                }
            } catch (_: Exception) {
            }
        }
        val item = ShowItem("s" + Integer.toHexString(uri.toString().hashCode()), uri, video, mime, System.currentTimeMillis(), dur, name, "Geteilt")
        known[item.key] = item
        return item
    }

    /** Kompakte Liste für die Galerie: [key, video(0/1), datum(s), dauer(s), albumIndex]. */
    fun uiJson(list: List<ShowItem>): JSONObject {
        val buckets = LinkedHashMap<String, Int>()
        val arr = JSONArray()
        for (it in list) {
            val b = buckets.getOrPut(it.bucket) { buckets.size }
            arr.put(JSONArray().put(it.key).put(if (it.video) 1 else 0).put(it.date / 1000).put(it.durationMs / 1000).put(b))
        }
        return JSONObject().put("buckets", JSONArray(buckets.keys.toList())).put("items", arr)
    }
}

/** Vorschaubilder für die Oberfläche. */
object Thumbs {
    private val cache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    fun jpeg(ctx: Context, item: ShowItem, size: Int): ByteArray? {
        val ck = "${item.key}@$size"
        cache.get(ck)?.let { return it }
        val bmp = load(ctx, item, size) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, if (size > 600) 88 else 80, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        cache.put(ck, bytes)
        return bytes
    }

    private fun load(ctx: Context, item: ShowItem, size: Int): Bitmap? {
        try {
            return ctx.contentResolver.loadThumbnail(item.uri, Size(size, size), null)
        } catch (_: Throwable) {
        }
        if (!item.video) return Renderer.decode(ctx, item.uri, size, size)
        return try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, item.uri)
                r.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
            } finally {
                r.release()
            }
        } catch (_: Throwable) {
            null
        }
    }
}

/** Bereitet Fotos für den Fernseher auf: richtig gedreht, verkleinert auf Full HD bzw. 4K, als JPEG (auch HEIC-Fotos). */
object Renderer {
    private val cache = object : LruCache<String, ByteArray>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val pool = Executors.newSingleThreadExecutor()

    fun jpeg(ctx: Context, item: ShowItem): ByteArray? {
        val uhd = Store.settings.uhd
        val ck = item.key + (if (uhd) "@uhd" else "@hd")
        cache.get(ck)?.let { return it }
        val bmp = (if (uhd) decode(ctx, item.uri, 3840, 2160) else decode(ctx, item.uri, 1920, 1080)) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        cache.put(ck, bytes)
        return bytes
    }

    /** Nächstes Foto schon vorbereiten, damit der Wechsel flott geht. */
    fun prefetch(ctx: Context, item: ShowItem?) {
        if (item == null || item.video) return
        pool.execute {
            try { jpeg(ctx, item) } catch (_: Throwable) {}
        }
    }

    fun clear() = cache.evictAll()

    fun decode(ctx: Context, uri: Uri, longMax: Int, shortMax: Int): Bitmap? = try {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width.coerceAtLeast(1)
            val h = info.size.height.coerceAtLeast(1)
            val scale = minOf(1f, longMax.toFloat() / maxOf(w, h), shortMax.toFloat() / minOf(w, h))
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Throwable) {
        null
    }
}
