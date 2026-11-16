package de.burkhard.urlaubscast

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Schickt Zustandsänderungen an die Oberfläche (WebView), solange sie sichtbar ist. Alles läuft im Haupt-Thread. */
object UiHub {
    @Volatile var activity: MainActivity? = null
    private val main = Handler(Looper.getMainLooper())

    private fun onMain(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post { f() }
    }

    fun push(kind: String, payload: String) {
        val a = activity ?: return
        a.js("window.__push&&window.__push(${JSONObject.quote(kind)},${JSONObject.quote(payload)})")
    }

    fun state() = onMain {
        val a = activity ?: return@onMain
        push("state", Slideshow.stateJson().toString())
        a.applyKeepScreen()
    }

    fun list() = onMain { if (activity != null) push("list", Slideshow.listJson().toString()) }

    fun progress(p: Float) = onMain { if (activity != null) push("prog", JSONObject().put("p", p.toDouble()).toString()) }

    fun devices() = onMain { if (activity != null) push("devices", CastControl.devicesJson().toString()) }

    fun shows() = onMain { if (activity != null) push("shows", Store.showsSummary().toString()) }
}
