package de.burkhard.urlaubscast

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.cast.MediaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Steuerung von Einzelbild und Diashow. Läuft im Handy (nicht in der Oberfläche),
 * damit die Show auch bei ausgeschaltetem Handy-Bildschirm weiterläuft.
 * Alle Methoden im Haupt-Thread aufrufen.
 */
object Slideshow : CastControl.Listener {
    enum class Mode { IDLE, SINGLE, SHOW }

    private const val TICK = 250L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val items = mutableListOf<ShowItem>()
    val list: List<ShowItem> get() = items

    var mode = Mode.IDLE
        private set
    var paused = false
        private set
    var index = -1
        private set
    var current: ShowItem? = null
        private set

    private var progress = 0f
    private var message: String? = null
    private var finished = false
    private var singleKeys: List<String> = emptyList()
    private var singlePos = -1
    private var timer: Job? = null
    private var seq = 0
    private var videoStarted = false
    private var loadedAt = 0L
    private var serviceOn = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        items.addAll(Store.loadList())
        MediaLibrary.remember(items)
        CastControl.listener = this
    }

    // ================= Abspielen =================

    /** Einzelnes Foto/Video sofort zeigen. ctxKeys = Reihenfolge für „vor/zurück“ (z. B. die Galerie-Ansicht). */
    fun single(key: String, ctxKeys: List<String>) {
        val item = MediaLibrary.get(app, key) ?: return
        finished = false
        mode = Mode.SINGLE
        paused = false
        singleKeys = if (ctxKeys.contains(key)) ctxKeys else listOf(key)
        singlePos = singleKeys.indexOf(key)
        index = items.indexOfFirst { it.key == key }
        display(item)
        service()
    }

    /** Diashow starten (from = Position im Ablauf) bzw. fortsetzen. */
    fun play(from: Int? = null) {
        if (items.isEmpty()) {
            message = "Der Ablauf ist noch leer – in der Galerie Fotos und Videos auswählen"
            changed()
            return
        }
        if (mode == Mode.SHOW && from == null) {
            if (paused) resume()
            return
        }
        var start = from ?: index
        if (from == null && finished) start = 0
        if (start !in items.indices) start = 0
        finished = false
        mode = Mode.SHOW
        paused = false
        index = start
        display(items[index])
        service()
    }

    fun toggle() {
        when {
            mode == Mode.SHOW -> if (paused) resume() else pause()
            mode == Mode.SINGLE && current?.video == true -> if (paused) resume() else pause()
            else -> play()
        }
    }

    fun pause() {
        if (mode == Mode.IDLE || paused) return
        paused = true
        if (current?.video == true) CastControl.pause()
        changed()
    }

    fun resume() {
        if (!paused) return
        paused = false
        val c = current
        if (!CastControl.isConnected()) {
            message = "Bitte zuerst mit dem Fernseher verbinden"
            paused = true
        } else if (c != null && c.video) {
            CastControl.play()
        } else if (c != null && mode == Mode.SHOW && timer == null) {
            display(c)
        }
        changed()
    }

    fun next() {
        when (mode) {
            Mode.SHOW -> advance()
            Mode.SINGLE -> stepSingle(1)
            Mode.IDLE -> {}
        }
    }

    fun prev() {
        when (mode) {
            Mode.SHOW -> {
                if (items.isEmpty()) return
                index = when {
                    index > 0 -> index - 1
                    Store.settings.loop -> items.size - 1
                    else -> 0
                }
                paused = false
                display(items[index])
            }
            Mode.SINGLE -> stepSingle(-1)
            Mode.IDLE -> {}
        }
    }

    /** Spontan zu einem Eintrag im Ablauf springen. */
    fun jump(pos: Int) {
        if (pos !in items.indices) return
        index = pos
        finished = false
        if (mode == Mode.SHOW) {
            paused = false
            display(items[pos])
        } else {
            mode = Mode.SINGLE
            paused = false
            singleKeys = items.map { it.key }
            singlePos = pos
            display(items[pos])
            service()
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
        seq++
        if (mode != Mode.IDLE) CastControl.stopMedia()
        mode = Mode.IDLE
        paused = false
        current = null
        progress = 0f
        message = null
        finished = false
        service()
        changed()
    }

    private fun stepSingle(d: Int) {
        if (singleKeys.isEmpty()) return
        var p = singlePos + d
        while (p in singleKeys.indices) {
            val item = MediaLibrary.get(app, singleKeys[p])
            if (item != null) {
                singlePos = p
                index = items.indexOfFirst { it.key == item.key }
                paused = false
                display(item)
                return
            }
            p += d
        }
    }

    private fun advance() {
        if (items.isEmpty()) {
            stop()
            return
        }
        var n = index + 1
        if (n >= items.size) {
            if (Store.settings.loop) {
                n = 0
            } else {
                // Ende: letztes Bild bleibt stehen
                timer?.cancel()
                timer = null
                finished = true
                mode = Mode.SINGLE
                paused = false
                progress = 1f
                singleKeys = items.map { it.key }
                singlePos = index
                message = "Diashow beendet"
                changed()
                return
            }
        }
        index = n
        paused = false
        display(items[index])
    }

    private fun display(item: ShowItem) {
        timer?.cancel()
        timer = null
        val my = ++seq
        current = item
        progress = 0f
        videoStarted = false
        loadedAt = SystemClock.elapsedRealtime()

        if (!CastControl.isConnected()) {
            // Ohne Fernseher wartet alles, bis verbunden ist (siehe onSession)
            message = if (mode == Mode.SHOW) "Wartet auf den Fernseher – nach dem Verbinden geht's los"
                      else "Wird gezeigt, sobald der Fernseher verbunden ist"
            if (mode == Mode.SHOW) paused = true
            changed()
            return
        }
        val server = MediaServer.ensure(app)
        val host = Net.localIpFor(app, CastControl.deviceAddress())
        if (server == null || host == null) {
            message = "Kein WLAN gefunden – Handy und Stick müssen im selben Netz sein"
            if (mode == Mode.SHOW) paused = true
            changed()
            return
        }
        message = null
        CastControl.load(server.urlFor(item, host), item.castMime, item.video, item.title) { ok ->
            if (my != seq) return@load
            if (ok) {
                message = null
                if (mode == Mode.SHOW) startTimer(item, my)
            } else {
                message = "„${item.name.ifEmpty { "Medium" }}“ ließ sich nicht anzeigen"
                if (mode == Mode.SHOW) {
                    timer = scope.launch {
                        delay(1500)
                        if (my == seq) advance()
                    }
                }
            }
            changed()
        }
        prefetchNext()
        changed()
    }

    private fun startTimer(item: ShowItem, my: Int) {
        timer?.cancel()
        val s = Store.settings
        val capped = item.video && s.videoMax > 0 && (item.durationMs <= 0 || item.durationMs > s.videoMax * 1000L)
        val total: Long = when {
            !item.video -> s.interval * 1000L
            capped -> s.videoMax * 1000L
            item.durationMs > 0 -> item.durationMs + 20_000L   // Sicherheitsnetz, falls das Ende-Signal ausbleibt
            else -> 60 * 60_000L
        }
        timer = scope.launch {
            var elapsed = 0L
            while (elapsed < total) {
                delay(TICK)
                if (my != seq) return@launch
                if (!paused) elapsed += TICK
                val p = if (item.video && !capped) {
                    CastControl.videoProgress() ?: (elapsed.toFloat() / total)
                } else {
                    elapsed.toFloat() / total
                }
                progress = p.coerceIn(0f, 1f)
                UiHub.progress(progress)
            }
            if (my == seq) advance()
        }
    }

    private fun prefetchNext() {
        val nxt: ShowItem? = when (mode) {
            Mode.SHOW -> if (items.isEmpty()) null else items[(index + 1).mod(items.size)]
            Mode.SINGLE -> singleKeys.getOrNull(singlePos + 1)?.let { MediaLibrary.get(app, it) }
            Mode.IDLE -> null
        }
        Renderer.prefetch(app, nxt)
    }

    // ================= Rückmeldungen vom Fernseher =================

    override fun onRemoteStatus(playerState: Int, idleReason: Int) {
        val item = current ?: return
        if (mode == Mode.IDLE) return
        when (playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> if (item.video) {
                videoStarted = true
                if (paused) { paused = false; changed() }   // z. B. mit der Fernbedienung fortgesetzt
            }
            MediaStatus.PLAYER_STATE_BUFFERING -> if (item.video) videoStarted = true
            MediaStatus.PLAYER_STATE_PAUSED -> if (item.video && videoStarted && !paused) {
                paused = true                                  // mit der Fernbedienung angehalten
                changed()
            }
            MediaStatus.PLAYER_STATE_IDLE -> when (idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> if (item.video && videoStarted && mode == Mode.SHOW) {
                    videoStarted = false
                    advance()
                }
                MediaStatus.IDLE_REASON_ERROR -> if (SystemClock.elapsedRealtime() - loadedAt > 800) {
                    message = "Wiedergabefehler auf dem Fernseher"
                    if (mode == Mode.SHOW) {
                        val my = seq
                        timer?.cancel()
                        timer = scope.launch {
                            delay(1500)
                            if (my == seq) advance()
                        }
                    }
                    changed()
                }
            }
        }
    }

    override fun onSession(connected: Boolean) {
        val c = current
        if (connected) {
            if (mode != Mode.IDLE && c != null) {
                paused = false
                display(c)
            }
        } else if (mode == Mode.SHOW && !paused) {
            paused = true
            message = "Verbindung zum Fernseher getrennt – Diashow pausiert"
        }
        changed()
    }

    // ================= Ablauf bearbeiten =================

    fun add(newItems: List<ShowItem>, next: Boolean) {
        if (newItems.isEmpty()) return
        if (next) {
            // „Als Nächstes“: direkt hinter das aktuelle Bild (vorhandene Einträge werden verschoben)
            val keys = newItems.map { it.key }.toSet()
            val cur = items.getOrNull(index)
            items.removeAll { it.key in keys && it != cur }
            index = if (cur != null) items.indexOf(cur) else -1
            val at = if (index >= 0) index + 1 else 0
            items.addAll(at, newItems.filter { it.key != cur?.key })
        } else {
            val have = items.map { it.key }.toHashSet()
            items.addAll(newItems.filter { have.add(it.key) })
        }
        MediaLibrary.remember(newItems)
        listChanged()
    }

    fun setList(newItems: List<ShowItem>, start: Boolean) {
        if (mode == Mode.SHOW) stop()
        items.clear()
        val seen = HashSet<String>()
        items.addAll(newItems.filter { seen.add(it.key) })
        index = if (items.isEmpty()) -1 else 0
        finished = false
        MediaLibrary.remember(items)
        listChanged()
        if (start && items.isNotEmpty()) play(0)
    }

    fun remove(pos: Int) {
        if (pos !in items.indices) return
        val wasCurrent = pos == index && mode == Mode.SHOW
        items.removeAt(pos)
        if (pos < index) index--
        when {
            items.isEmpty() -> {
                index = -1
                if (mode == Mode.SHOW) stop()
            }
            wasCurrent -> {
                if (index >= items.size) index = 0
                display(items[index])
            }
            index >= items.size -> index = items.size - 1
        }
        listChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val cur = items.getOrNull(index)
        val it = items.removeAt(from)
        items.add(to, it)
        index = if (cur != null) items.indexOf(cur) else -1
        listChanged()
    }

    fun clear() {
        if (mode == Mode.SHOW) stop()
        items.clear()
        index = -1
        finished = false
        listChanged()
    }

    /** Reihenfolge mischen; das gerade gezeigte Bild bleibt vorne. */
    fun shuffle() {
        if (items.size < 2) return
        val cur = items.getOrNull(index)
        items.shuffle()
        if (cur != null) {
            items.remove(cur)
            items.add(0, cur)
            index = 0
        }
        listChanged()
    }

    fun settingsChanged(uhdChanged: Boolean) {
        if (uhdChanged) Renderer.clear()
        changed()
    }

    // ================= Zustand für Oberfläche & Benachrichtigung =================

    fun stateJson(): JSONObject = JSONObject()
        .put("mode", mode.name.lowercase())
        .put("paused", paused)
        .put("index", index)
        .put("count", items.size)
        .put("cur", current?.uiJson() ?: JSONObject.NULL)
        .put("msg", message ?: JSONObject.NULL)
        .put("prog", progress.toDouble())
        .put("finished", finished)
        .put("singlePos", singlePos)
        .put("singleCount", singleKeys.size)
        .put("conn", CastControl.connState)
        .put("dev", CastControl.deviceName ?: JSONObject.NULL)

    fun listJson(): JSONArray {
        val a = JSONArray()
        for (it in items) a.put(it.uiJson())
        return a
    }

    private fun listChanged() {
        Store.saveList(items.toList())
        UiHub.list()
        changed()
    }

    private fun changed() {
        UiHub.state()
        KeepAliveService.refresh()
    }

    /** Hintergrunddienst an, solange etwas auf dem Fernseher läuft (Show läuft auch bei dunklem Handy weiter). */
    private fun service() {
        val want = mode != Mode.IDLE
        if (want != serviceOn) {
            serviceOn = want
            KeepAliveService.sync(app, want)
        }
    }
}
