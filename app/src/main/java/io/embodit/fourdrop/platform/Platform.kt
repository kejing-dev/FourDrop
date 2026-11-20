package io.embodit.fourdrop.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.fourdrop.data.FileGameStore
import io.embodit.fourdrop.data.SettingsStore
import io.embodit.fourdrop.domain.GameFlow
import io.embodit.fourdrop.domain.Sound
import io.embodit.fourdrop.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

const val TAG = "FourDrop"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        // board-input template: mirror input lines into files/input-log.txt (headset logcat drops lines)
        io.embodit.fourdrop.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        // D60 补 (gate back-key): controller B = system BACK. It never exits or sends the scene to the background: a
        // disc in the hand goes back to the tray, a closed window reopens, otherwise eaten (logged). Caught at the
        // earliest layer of every window (BackKeys); the OnBackPressedCallback stays as the last fallback.
        BackKeys.handler = { where -> graph.onBack(where) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                BackKeys.wrap(a)
                val ca = a as? androidx.activity.ComponentActivity
                if (ca == null) { Log.w(TAG, "BACK guard: ${a.javaClass.simpleName} is not a ComponentActivity"); return }
                ca.onBackPressedDispatcher.addCallback(ca, object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { BackKeys.handler("dispatcher:${a.javaClass.simpleName}") }
                })
                Log.i(TAG, "BACK guard on ${a.javaClass.simpleName}")
            }
            override fun onActivityPostCreated(a: android.app.Activity, b: android.os.Bundle?) { BackKeys.wrap(a); BackKeys.sweep("created") }
            override fun onActivityStarted(a: android.app.Activity) { BackKeys.sweep("started") }
            override fun onActivityResumed(a: android.app.Activity) { BackKeys.wrap(a); BackKeys.sweep("resumed") }
            override fun onActivityPostResumed(a: android.app.Activity) { BackKeys.sweep("post-resumed") }
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGameStore(context)
    val flow = GameFlow(
        scope = scope,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { snd, rate -> sounds.play(snd, rate) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    /** BACK / controller B from any window (BackKeys): put the disc in the hand back, else reopen a closed window. */
    fun onBack(where: String) {
        val st = flow.state.value
        when {
            st.grab != null -> { Log.i(TAG, "BACK $where -> grab back to tray"); flow.grabCancel() }
            st.aim != null -> { Log.i(TAG, "BACK $where -> aimed disc back to tray"); flow.tapTray(st.handSeat) }
            !window.panelOpen -> { Log.i(TAG, "BACK $where -> reopen window"); window.reopen() }
            else -> Log.i(TAG, "BACK $where -> eaten")
        }
    }

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = s.sound
                sounds.volumeLevel = s.volume
            }
        }
        // design lifecycle: S00 until the volume's scene is built (BoardScreen starts the flow); a volume that never
        // comes up must not leave the panel on the loading page forever
        scope.launch {
            kotlinx.coroutines.delay(8000)
            if (flow.state.value.phase == io.embodit.fourdrop.domain.Phase.LOADING) { Log.w(TAG, "FLOW start without the scene (8 s)"); flow.start() }
        }
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Volume percent of each snap stop (Settings.volume 0..2), default the middle one. The design asked for 35 / 65 / 100 %
 * (default 65); with the CC0 recordings already at -1 dBFS the 65 % stop measured -21.7 dBFS voiced RMS, under the B20
 * floor, so the stops are 45 / 80 / 100 % (deviation, tools/loudness.py).
 */
val VOLUME_PERCENT = intArrayOf(45, 80, 100)

/**
 * Short effects from assets/sounds/<name>.wav (+ _2 / _3 variants) through a SoundPool. Repeated sounds (dice, piece
 * landing, CC0 recordings) rotate their 2-4 variants with a random rate of 0.95-1.05 that never repeats back to back (design sound.variation).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    /** Sample id -> (duration ms, peak linear) read from the WAV, for the mix headroom below. */
    private val meta = HashMap<Int, Pair<Long, Float>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastRate = HashMap<Sound, Float>()
    private val lastVariant = HashMap<Sound, Int>()
    /** Physical (recorded) sounds: rotate variants with a +-5 % pitch (profile D51). */
    private val varied = setOf(Sound.PICK, Sound.TICK, Sound.LAND, Sound.BACK, Sound.SLIDE)

    /**
     * Playback gain per event. The design's balance (pick 0.5, tick 0.45, land 0.9 …) keeps its order (the landing
     * loudest) but is lifted toward unity so the default stop reaches the B20 floor; tools/loudness.py uses the same table.
     */
    private val gainOf = mapOf(
        Sound.PICK to 0.85f, Sound.TICK to 0.9f, Sound.LAND to 1.0f, Sound.BACK to 0.9f, Sound.FULL to 0.8f,
        Sound.SLIDE to 0.9f, Sound.WIN to 0.85f, Sound.LOSE to 0.85f,
    )
    /** The stream each event is playing right now (one instance per event, profile D51). */
    private val playing = HashMap<Sound, Int>()
    /** Streams still sounding: stream id -> (end time, peak contribution). */
    private val active = HashMap<Int, Pair<Long, Float>>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav", "${base}_4.wav")) {
                runCatching {
                    val id = context.assets.openFd("sounds/$name").use { fd -> pool.load(fd, 1) }
                    found += id
                    meta[id] = context.assets.open("sounds/$name").use { readMeta(it.readBytes()) }
                }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound for $s")
        }
    }

    /**
     * Plays [s] once. D51 (2026-11-06 headset feedback "the select sound blows up"): the same event never overlaps itself
     * (the previous stream of that event is stopped first), and the mix keeps headroom: the volumes of the streams still
     * sounding are summed by peak and a new stream is scaled down so the sum stays under -1 dBFS (a soft limiter in
     * front of SoundPool, which has no limiter of its own).
     */
    fun play(s: Sound, pitch: Float = 1f) {
        if (!enabled) return
        val variants = ids[s] ?: return
        val vol = VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f * (gainOf[s] ?: 1f)
        var rate = 1f
        var v = 0
        if (s in varied) {
            rate = 0.95f + random.nextFloat() * 0.10f
            val last = lastRate[s] ?: 1f
            if (abs(rate - last) < 0.01f) rate = if (rate < 1f) rate + 0.02f else rate - 0.02f
            lastRate[s] = rate
            v = random.nextInt(variants.size)
            if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
            lastVariant[s] = v
        }
        rate = (rate * pitch).coerceIn(0.5f, 2f)
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        val now = SystemClock.uptimeMillis()
        synchronized(active) {
            playing.remove(s)?.let { pool.stop(it); active.remove(it) }
            active.entries.removeAll { it.value.first <= now }
            val (durMs, peak) = meta[id] ?: (500L to 0.9f)
            val others = active.values.sumOf { it.second.toDouble() }.toFloat()
            val gain = MixHeadroom.gain(vol * peak, others)
            val stream = pool.play(id, vol * gain, vol * gain, 1, 0, rate)
            if (stream != 0) {
                playing[s] = stream
                active[stream] = (now + (durMs / rate).toLong()) to vol * gain * peak
            }
        }
    }

    fun release() = runCatching { pool.release() }

    companion object {
        const val MAX_STREAMS = 6

        /** Duration (ms) and peak (linear) of a 16-bit PCM WAV. */
        fun readMeta(b: ByteArray): Pair<Long, Float> {
            var i = 12
            var rate = 22050; var ch = 1
            while (i + 8 <= b.size) {
                val id = String(b, i, 4)
                val n = (b[i + 4].toInt() and 255) or ((b[i + 5].toInt() and 255) shl 8) or ((b[i + 6].toInt() and 255) shl 16) or ((b[i + 7].toInt() and 255) shl 24)
                if (id == "fmt ") {
                    ch = (b[i + 10].toInt() and 255) or ((b[i + 11].toInt() and 255) shl 8)
                    rate = (b[i + 12].toInt() and 255) or ((b[i + 13].toInt() and 255) shl 8) or ((b[i + 14].toInt() and 255) shl 16)
                }
                if (id == "data") {
                    var peak = 0
                    var j = i + 8
                    val end = minOf(b.size - 1, i + 8 + n)
                    while (j < end) { val v = abs(((b[j + 1].toInt() shl 8) or (b[j].toInt() and 255)).toShort().toInt()); if (v > peak) peak = v; j += 2 }
                    return (n / 2L / ch * 1000 / rate) to peak / 32768f
                }
                i += 8 + n + (n and 1)
            }
            return 500L to 0.9f
        }
    }
}

/** The mix limiter rule, kept pure for the unit test: the summed peaks of everything sounding stay <= -1 dBFS. */
object MixHeadroom {
    const val CEILING = 0.891f // -1 dBFS

    /** Gain (0..1) for a new stream whose peak at full gain is [newPeak], with [othersPeak] already sounding. */
    fun gain(newPeak: Float, othersPeak: Float): Float {
        if (newPeak <= 0f) return 1f
        val room = CEILING - othersPeak
        return (room / newPeak).coerceIn(0.15f, 1f)
    }
}
