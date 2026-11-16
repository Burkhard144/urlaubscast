package de.burkhard.urlaubscast

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Verbindung zwischen Oberfläche (JavaScript) und App (Kotlin).
 * JavaScript ruft CastBridge.call(id, json) auf, die Antwort kommt über window.__reply(id, ok, json).
 * Laufende Änderungen (Zustand, Ablauf, Geräte) schickt UiHub über window.__push(art, json).
 */
class Bridge(private val act: MainActivity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JavascriptInterface
    fun call(id: String, json: String) {
        scope.launch {
            val res = try {
                true to handle(JSONObject(json))
            } catch (e: UserError) {
                false to JSONObject().put("error", e.message ?: "Fehler")
            } catch (e: Throwable) {
                false to JSONObject().put("error", "Fehler: " + (e.message ?: e.javaClass.simpleName))
            }
            act.reply(id, res.first, res.second)
        }
    }

    @JavascriptInterface
    fun isApp(): Boolean = true

    private fun ok() = JSONObject().put("ok", true)

    private suspend fun <T> onMain(f: () -> T): T = withContext(Dispatchers.Main) { f() }

    private fun keys(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun resolve(a: JSONArray?): List<ShowItem> = keys(a).mapNotNull { MediaLibrary.get(act, it) }

    private suspend fun handle(b: JSONObject): JSONObject {
        val s = Slideshow
        return when (b.optString("action")) {
            "init" -> onMain {
                JSONObject()
                    .put("settings", Store.settings.toJson())
                    .put("state", s.stateJson())
                    .put("list", s.listJson())
                    .put("shows", Store.showsSummary())
                    .put("perm", act.mediaPermState())
                    .put("version", BuildConfig.VERSION_NAME)
            }
            "perm" -> JSONObject().put("perm", act.mediaPermState())
            "askPerm" -> JSONObject().put("perm", act.askPermsBlocking())
            "media" -> MediaLibrary.uiJson(MediaLibrary.scan(act))

            // Fernseher
            "devices" -> {
                if (b.optBoolean("on")) CastControl.startDiscovery() else CastControl.stopDiscovery()
                ok()
            }
            "connect" -> { CastControl.connect(b.getString("id")); ok() }
            "disconnect" -> { CastControl.disconnect(); ok() }
            "mirror" -> { onMain { act.openMirror(b.optString("how")) }; ok() }
            "appSettings" -> { onMain { act.openAppSettings() }; ok() }

            // Wiedergabe
            "single" -> {
                val ctx = keys(b.optJSONArray("ctx"))
                onMain { s.single(b.getString("key"), ctx); ok() }
            }
            "play" -> onMain { s.play(if (b.has("from")) b.getInt("from") else null); ok() }
            "toggle" -> onMain { s.toggle(); ok() }
            "next" -> onMain { s.next(); ok() }
            "prev" -> onMain { s.prev(); ok() }
            "stop" -> onMain { s.stop(); ok() }
            "jump" -> onMain { s.jump(b.getInt("pos")); ok() }

            // Ablauf
            "add" -> {
                val items = resolve(b.optJSONArray("keys"))
                if (items.isEmpty()) throw UserError("Keine Fotos oder Videos gefunden")
                onMain { s.add(items, b.optBoolean("next")); ok().put("added", items.size) }
            }
            "setList" -> {
                val items = resolve(b.optJSONArray("keys"))
                if (items.isEmpty()) throw UserError("Keine Fotos oder Videos gefunden")
                onMain { s.setList(items, b.optBoolean("start")); ok() }
            }
            "remove" -> onMain { s.remove(b.getInt("pos")); ok() }
            "move" -> onMain { s.move(b.getInt("from"), b.getInt("to")); ok() }
            "clear" -> onMain { s.clear(); ok() }
            "shuffle" -> onMain { s.shuffle(); ok() }

            // Gespeicherte Abläufe
            "saveShow" -> {
                val name = b.optString("name").trim().take(60)
                if (name.isEmpty()) throw UserError("Bitte einen Namen eingeben")
                val items = onMain { s.list.toList() }
                if (items.isEmpty()) throw UserError("Der Ablauf ist leer")
                Store.saveShow(name, items)
                JSONObject().put("shows", Store.showsSummary())
            }
            "loadShow" -> {
                val items = Store.loadShow(b.getString("name")) ?: throw UserError("Ablauf nicht gefunden")
                MediaLibrary.remember(items)
                onMain { s.setList(items, b.optBoolean("start")); ok() }
            }
            "deleteShow" -> {
                Store.deleteShow(b.getString("name"))
                JSONObject().put("shows", Store.showsSummary())
            }

            // Einstellungen
            "settings" -> {
                val before = Store.settings
                val after = Store.updateSettings(b.getJSONObject("patch"))
                onMain { s.settingsChanged(before.uhd != after.uhd) }
                JSONObject().put("settings", after.toJson())
            }
            else -> throw UserError("Unbekannte Aktion")
        }
    }
}
