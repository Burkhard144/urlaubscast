package de.burkhard.urlaubscast

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ShowSettings(
    val interval: Int = 8,          // Sekunden pro Foto
    val loop: Boolean = true,       // Ablauf endlos wiederholen
    val videoMax: Int = 0,          // 0 = Videos ganz abspielen, sonst höchstens so viele Sekunden
    val uhd: Boolean = false,       // Fotos in 4K statt Full HD an den Fernseher senden
    val screenOn: Boolean = true,   // Handy-Bildschirm während der Wiedergabe anlassen
) {
    fun toJson(): JSONObject = JSONObject()
        .put("interval", interval).put("loop", loop).put("videoMax", videoMax)
        .put("uhd", uhd).put("screenOn", screenOn)

    companion object {
        fun from(j: JSONObject?): ShowSettings {
            if (j == null) return ShowSettings()
            return ShowSettings(
                interval = j.optInt("interval", 8).coerceIn(2, 300),
                loop = j.optBoolean("loop", true),
                videoMax = j.optInt("videoMax", 0).coerceIn(0, 3600),
                uhd = j.optBoolean("uhd", false),
                screenOn = j.optBoolean("screenOn", true),
            )
        }
    }
}

/** Einstellungen, aktueller Ablauf und gespeicherte Abläufe (alles lokal auf dem Handy). */
object Store {
    private lateinit var prefs: SharedPreferences

    @Volatile var settings: ShowSettings = ShowSettings()
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("urlaubscast", Context.MODE_PRIVATE)
        settings = ShowSettings.from(prefs.getString("settings", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
    }

    @Synchronized
    fun updateSettings(patch: JSONObject): ShowSettings {
        val merged = settings.toJson()
        for (k in patch.keys()) merged.put(k, patch.get(k))
        settings = ShowSettings.from(merged)
        prefs.edit().putString("settings", settings.toJson().toString()).apply()
        return settings
    }

    fun loadList(): List<ShowItem> = ShowItem.listFrom(prefs.getString("list", null))

    fun saveList(items: List<ShowItem>) {
        prefs.edit().putString("list", ShowItem.listToJson(items).toString()).apply()
    }

    private fun showsArray(): JSONArray =
        runCatching { JSONArray(prefs.getString("shows", null) ?: "[]") }.getOrElse { JSONArray() }

    @Synchronized
    fun saveShow(name: String, items: List<ShowItem>) {
        val old = showsArray()
        val out = JSONArray()
        out.put(JSONObject().put("name", name).put("created", System.currentTimeMillis()).put("items", ShowItem.listToJson(items)))
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("name") != name) out.put(o)
        }
        prefs.edit().putString("shows", out.toString()).apply()
    }

    fun loadShow(name: String): List<ShowItem>? {
        val arr = showsArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) return ShowItem.listFrom(o.optJSONArray("items")?.toString())
        }
        return null
    }

    @Synchronized
    fun deleteShow(name: String) {
        val old = showsArray()
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("name") != name) out.put(o)
        }
        prefs.edit().putString("shows", out.toString()).apply()
    }

    /** Kurzfassung für die Liste „Abläufe“ (ohne alle Einträge). */
    fun showsSummary(): JSONArray {
        val arr = showsArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val items = o.optJSONArray("items") ?: JSONArray()
            var videos = 0
            var videoSecs = 0L
            val thumbs = JSONArray()
            for (k in 0 until items.length()) {
                val it = items.optJSONObject(k) ?: continue
                if (it.optBoolean("v")) { videos++; videoSecs += it.optLong("du") / 1000 }
                if (thumbs.length() < 4) thumbs.put(it.optString("k"))
            }
            out.put(
                JSONObject().put("name", o.optString("name")).put("created", o.optLong("created"))
                    .put("count", items.length()).put("videos", videos).put("videoSecs", videoSecs).put("thumbs", thumbs)
            )
        }
        return out
    }
}
