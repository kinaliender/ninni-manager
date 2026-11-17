package com.ender.ninniplayer

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Pauses the music after a while. During the last part (the fade) the volume steps down to 0,
 * so the music fades out instead of stopping abruptly. Once the music is paused the volume is
 * put back, so the next play is not silent.
 *
 * Rule: this never raises the volume while music is playing (that could wake the baby).
 * Cancelling mid-fade leaves the volume where it is.
 *
 * Main thread only. Holds a partial wake lock while running so the steps happen on time
 * with the screen off.
 */
class SleepTimer(
    context: Context,
    private val handler: Handler,
    private val audio: AudioManager,
    private val pause: () -> Unit,
    /** Highest volume step allowed (the volume cap). */
    private val volumeLimit: () -> Int,
    private val onChange: () -> Unit,
) {
    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
        const val TICK = 5_000L
        /** Time for the music app to really stop before the volume goes back up. */
        const val RESTORE_DELAY = 2_000L
    }

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ninni:sleep-timer")
        .apply { setReferenceCounted(false) }

    /** elapsedRealtime when the music pauses; 0 = off. */
    private var endAt = 0L
    private var fadeMs = 0L
    /** Volume the fade goes down from, and since when. -1 until the fade starts. */
    private var fadeFrom = -1
    private var fadeFromAt = 0L
    /** Volume to put back after pausing; -1 = nothing to put back. */
    private var restoreTo = -1

    private val tick = Runnable { step() }
    private val restore = Runnable { restoreVolume() }

    val isActive get() = endAt != 0L

    /** Time until the music pauses; null when off. */
    fun remainingMs(): Long? = if (isActive) (endAt - now()).coerceAtLeast(0) else null

    val fadeLengthMs get() = fadeMs

    fun start(totalMs: Long, fadeMs: Long) {
        // A previous timer just ended and its volume is not back yet: put it back first.
        if (handler.hasCallbacks(restore)) {
            handler.removeCallbacks(restore)
            restoreVolume()
        }
        handler.removeCallbacks(tick)
        endAt = now() + totalMs
        this.fadeMs = fadeMs.coerceIn(0, totalMs)
        fadeFrom = -1
        restoreTo = -1
        wakeLock.acquire(totalMs + RESTORE_DELAY + 60_000)
        step()
        onChange()
    }

    fun cancel() {
        if (!isActive) return
        handler.removeCallbacks(tick)
        endAt = 0
        fadeMs = 0
        fadeFrom = -1
        restoreTo = -1
        if (!handler.hasCallbacks(restore)) releaseWakeLock()
        onChange()
    }

    /** Service is going away: stop without touching the volume. */
    fun release() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(restore)
        endAt = 0
        releaseWakeLock()
    }

    /**
     * The volume was set from a remote during the fade: fade on from the new level. Afterwards
     * the volume goes back to the level before the fade, or to this one if it is higher.
     */
    fun onVolumeSetByUser() {
        if (!isActive || fadeFrom < 0) return
        fadeFrom = audio.getStreamVolume(STREAM)
        fadeFromAt = now()
        restoreTo = maxOf(restoreTo, fadeFrom)
    }

    private fun step() {
        if (!isActive) return
        val t = now()
        if (t >= endAt) {
            finish()
            return
        }
        if (fadeMs > 0 && t >= endAt - fadeMs) {
            if (fadeFrom < 0) {
                fadeFrom = audio.getStreamVolume(STREAM)
                fadeFromAt = t
                restoreTo = fadeFrom
            }
            // Straight line from fadeFrom down to 0 at endAt, in whole volume steps.
            val target = (fadeFrom * (endAt - t).toDouble() / (endAt - fadeFromAt)).roundToInt()
            if (audio.getStreamVolume(STREAM) > target) audio.setStreamVolume(STREAM, target, 0)
        }
        handler.postDelayed(tick, TICK)
    }

    private fun finish() {
        val faded = fadeFrom >= 0
        endAt = 0
        fadeMs = 0
        fadeFrom = -1
        if (faded) audio.setStreamVolume(STREAM, 0, 0)
        pause()
        if (faded && restoreTo >= 0) {
            handler.postDelayed(restore, RESTORE_DELAY)
        } else {
            restoreTo = -1
            releaseWakeLock()
        }
        onChange()
    }

    private fun restoreVolume() {
        // Only once the music has really stopped: if pausing failed, keep it silent.
        if (restoreTo >= 0 && !audio.isMusicActive) {
            audio.setStreamVolume(STREAM, restoreTo.coerceAtMost(volumeLimit()), 0)
        }
        restoreTo = -1
        if (!isActive) releaseWakeLock()
        onChange()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun now() = SystemClock.elapsedRealtime()
}
