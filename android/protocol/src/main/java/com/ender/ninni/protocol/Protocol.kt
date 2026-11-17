package com.ender.ninni.protocol

import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * BLE protocol between the player and the managers. See PROTOCOL.md at the repository root;
 * change this file and PROTOCOL.md together.
 */
object Protocol {
    /** 2 added position/duration, shuffle/repeat, volume cap and the sleep timer. */
    const val VERSION = 2

    /** How far [Command.Forward] / [Command.Rewind] jump. */
    const val SKIP_SECONDS = 15

    val SERVICE: UUID = UUID.fromString("8641a100-acc1-4dba-9086-98a6e53c4f74")
    /** Read + notify. UTF-8 JSON, see [encodeState]. */
    val STATE: UUID = UUID.fromString("8641a101-acc1-4dba-9086-98a6e53c4f74")
    /** Write with response. UTF-8 text command, see [Command.wire]. */
    val COMMAND: UUID = UUID.fromString("8641a102-acc1-4dba-9086-98a6e53c4f74")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OK = 0
    // Application-level ATT error codes returned for COMMAND writes (0x80-0x9F range).
    const val ERR_NOT_AUTHORIZED = 0x80
    const val ERR_WRONG_PIN = 0x81
    const val ERR_BAD_COMMAND = 0x82
    const val ERR_FAILED = 0x83

    /** Sent instead of the state when even a state without title/artist does not fit. */
    private val READ_ME = """{"x":1}""".toByteArray(Charsets.UTF_8)

    /** Player side. Shortens title/artist (and sets "x") until the JSON fits in [maxBytes]. */
    fun encodeState(s: PlayerState, maxBytes: Int = Int.MAX_VALUE): ByteArray {
        var title = s.title.orEmpty()
        var artist = s.artist.orEmpty()
        var truncated = false
        while (true) {
            val bytes = json(s, title, artist, truncated).toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            if (title.isEmpty() && artist.isEmpty()) {
                return if (READ_ME.size <= maxBytes) READ_ME else ByteArray(0)
            }
            truncated = true
            val excess = bytes.size - maxBytes
            if (artist.length >= title.length) {
                artist = artist.dropLast(excess.coerceIn(1, artist.length))
            } else {
                title = title.dropLast(excess.coerceIn(1, title.length))
            }
        }
    }

    private fun json(s: PlayerState, title: String, artist: String, truncated: Boolean): String =
        JSONObject().apply {
            put("pv", VERSION)
            put("p", if (s.playing) 1 else 0)
            if (title.isNotEmpty()) put("t", title)
            if (artist.isNotEmpty()) put("a", artist)
            put("v", s.volume)
            put("m", s.maxVolume)
            put("b", s.battery)
            put("c", if (s.charging) 1 else 0)
            put("n", if (s.mediaAccess) 1 else 0)
            s.duration?.let { put("d", it) }
            s.position?.let { put("o", it) }
            s.shuffle?.let { put("sh", if (it) 1 else 0) }
            s.repeat?.let { put("rp", it.wire) }
            s.volumeCap?.let { put("vc", it) }
            s.sleep?.let {
                put("sr", it.remainingSeconds)
                put("sf", it.fadeSeconds)
            }
            if (truncated) put("x", 1)
        }.toString()

    /**
     * Manager side. Returns null for a value that is not JSON. When [StateMessage.truncated]
     * is set, read STATE to get the full value; [StateMessage.state] is null for `{"x":1}`.
     */
    fun decodeState(bytes: ByteArray): StateMessage? {
        val o = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            return null
        }
        val truncated = o.optInt("x") == 1
        if (!o.has("v")) return StateMessage(null, truncated)
        val state = PlayerState(
            playing = o.optInt("p") == 1,
            title = o.optString("t").ifEmpty { null },
            artist = o.optString("a").ifEmpty { null },
            volume = o.optInt("v"),
            maxVolume = o.optInt("m", 15),
            battery = o.optInt("b", -1),
            charging = o.optInt("c") == 1,
            mediaAccess = o.optInt("n") == 1,
            duration = if (o.has("d")) o.optLong("d") else null,
            position = if (o.has("o")) o.optLong("o") else null,
            shuffle = if (o.has("sh")) o.optInt("sh") == 1 else null,
            repeat = if (o.has("rp")) RepeatMode.fromWire(o.optInt("rp")) else null,
            volumeCap = if (o.has("vc")) o.optInt("vc") else null,
            sleep = if (o.has("sr")) Sleep(o.optInt("sr"), o.optInt("sf")) else null,
            version = o.optInt("pv", 1),
        )
        return StateMessage(state, truncated)
    }

    /** Player side. */
    fun parseCommand(bytes: ByteArray): Command? {
        val text = String(bytes, Charsets.UTF_8).trim().lowercase()
        val arg = text.substringAfter(':', "").trim()
        return when {
            text.startsWith("auth:") -> Command.Auth(arg)
            text.startsWith("vol:") -> arg.toIntOrNull()?.let { Command.SetVolume(it) }
            text.startsWith("seek:") -> arg.toLongOrNull()?.takeIf { it >= 0 }?.let { Command.Seek(it) }
            text.startsWith("shuffle:") -> when (arg) {
                "1" -> Command.SetShuffle(true)
                "0" -> Command.SetShuffle(false)
                else -> null
            }
            text.startsWith("repeat:") -> arg.toIntOrNull()?.let(RepeatMode::fromWire)?.let { Command.SetRepeat(it) }
            text.startsWith("cap:") -> when (arg) {
                "off" -> Command.SetVolumeCap(null)
                else -> arg.toIntOrNull()?.takeIf { it >= 0 }?.let { Command.SetVolumeCap(it) }
            }
            text == "sleep:off" -> Command.CancelSleep
            text.startsWith("sleep:") -> {
                val parts = arg.split(':')
                val seconds = parts.getOrNull(0)?.toIntOrNull()
                val fade = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (seconds != null && seconds > 0 && fade in 0..seconds) Command.StartSleep(seconds, fade) else null
            }
            text == "play" -> Command.Play
            text == "pause" -> Command.Pause
            text == "toggle" -> Command.Toggle
            text == "next" -> Command.Next
            text == "prev" -> Command.Previous
            text == "fwd" -> Command.Forward
            text == "rew" -> Command.Rewind
            text == "vol+" -> Command.VolumeUp
            text == "vol-" -> Command.VolumeDown
            text == "ping" -> Command.Ping
            else -> null
        }
    }
}

data class PlayerState(
    val playing: Boolean,
    val title: String?,
    val artist: String?,
    val volume: Int,
    val maxVolume: Int,
    /** Battery %, -1 when unknown. */
    val battery: Int,
    val charging: Boolean,
    /** Notification access granted on the player, i.e. title/artist can be read. */
    val mediaAccess: Boolean,
    /** Track length in ms; null when unknown. */
    val duration: Long? = null,
    /** Playback position in ms at the moment the state was sent; null when unknown. */
    val position: Long? = null,
    /** Null when the music app does not report it. */
    val shuffle: Boolean? = null,
    /** Null when the music app does not report it. */
    val repeat: RepeatMode? = null,
    /** Highest volume step allowed on the player; null = no limit. */
    val volumeCap: Int? = null,
    /** Null when the sleep timer is off. */
    val sleep: Sleep? = null,
    /** Protocol version of the player that sent this state. */
    val version: Int = Protocol.VERSION,
)

enum class RepeatMode(val wire: Int) {
    OFF(0), ONE(1), ALL(2);

    /** Order of the repeat button: off → all → one → off (like Spotify). */
    val next: RepeatMode
        get() = when (this) {
            OFF -> ALL
            ALL -> ONE
            ONE -> OFF
        }

    companion object {
        fun fromWire(value: Int): RepeatMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Sleep timer: the music pauses in [remainingSeconds]; the volume fades out during the last [fadeSeconds]. */
data class Sleep(val remainingSeconds: Int, val fadeSeconds: Int)

data class StateMessage(val state: PlayerState?, val truncated: Boolean)

sealed interface Command {
    data class Auth(val pin: String) : Command
    data class SetVolume(val level: Int) : Command
    data class Seek(val positionMs: Long) : Command
    data class SetShuffle(val on: Boolean) : Command
    data class SetRepeat(val mode: RepeatMode) : Command
    /** Null removes the limit; so does a level at or above the maximum step. */
    data class SetVolumeCap(val level: Int?) : Command
    data class StartSleep(val seconds: Int, val fadeSeconds: Int) : Command
    data object CancelSleep : Command
    data object Play : Command
    data object Pause : Command
    data object Toggle : Command
    data object Next : Command
    data object Previous : Command
    data object Forward : Command
    data object Rewind : Command
    data object VolumeUp : Command
    data object VolumeDown : Command
    data object Ping : Command

    /** Text written to COMMAND (manager side). */
    val wire: String
        get() = when (this) {
            is Auth -> "auth:$pin"
            is SetVolume -> "vol:$level"
            is Seek -> "seek:$positionMs"
            is SetShuffle -> "shuffle:${if (on) 1 else 0}"
            is SetRepeat -> "repeat:${mode.wire}"
            is SetVolumeCap -> "cap:${level ?: "off"}"
            is StartSleep -> "sleep:$seconds:$fadeSeconds"
            CancelSleep -> "sleep:off"
            Play -> "play"
            Pause -> "pause"
            Toggle -> "toggle"
            Next -> "next"
            Previous -> "prev"
            Forward -> "fwd"
            Rewind -> "rew"
            VolumeUp -> "vol+"
            VolumeDown -> "vol-"
            Ping -> "ping"
        }
}
