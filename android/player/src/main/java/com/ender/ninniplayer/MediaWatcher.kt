package com.ender.ninniplayer

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import com.ender.ninni.protocol.Queue
import com.ender.ninni.protocol.QueueItem
import com.ender.ninni.protocol.RepeatMode

/**
 * Follows the active media session of another app (Spotify) to read the title and send
 * transport controls. Reading sessions needs notification access; without it, controls
 * fall back to media key events, which still reach the last playing app.
 *
 * Shuffle and repeat are not part of the framework API; they go through the androidx
 * compat controller, which works with apps built on MediaSessionCompat or Media3 (Spotify).
 */
class MediaWatcher(
    private val context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
    /** The followed track's metadata changed (cover art); null when no session is followed. */
    private val onMetadata: (MediaMetadata?) -> Unit,
) {
    private companion object {
        /** Most "next" presses used to reach a queue item when the app cannot jump to it. */
        const val MAX_STEPS = 30
        const val STEP_DELAY = 400L
        /** Time the music app gets to jump to a queue item before "next" presses take over. */
        const val JUMP_CHECK_DELAY = 1_500L
    }

    data class Info(
        val title: String?,
        val artist: String?,
        val playing: Boolean?,
        val duration: Long? = null,
        val position: Long? = null,
        val shuffle: Boolean? = null,
        val repeat: RepeatMode? = null,
        /** [QueueItem.id] of the track playing now. */
        val queueActive: Long? = null,
    )

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private var controller: MediaController? = null
    private var compat: MediaControllerCompat? = null
    private var listening = false
    private var rawQueue: List<MediaSession.QueueItem> = emptyList()

    /** The music app's play queue; null when it shares none. Rebuilt only when it changes. */
    var queue: Queue? = null
        private set

    val hasAccess: Boolean
        get() = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        choose(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refresh()
            onChange()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            onMetadata(metadata)
            onChange()
        }

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            setQueue(queue, controller?.queueTitle)
            onChange()
        }

        override fun onQueueTitleChanged(title: CharSequence?) {
            setQueue(controller?.queue, title)
            onChange()
        }

        override fun onSessionDestroyed() = refresh()
    }

    private val compatCallback = object : MediaControllerCompat.Callback() {
        override fun onShuffleModeChanged(shuffleMode: Int) = onChange()

        override fun onRepeatModeChanged(repeatMode: Int) = onChange()

        /** Shuffle/repeat become readable once the compat controller has connected. */
        override fun onSessionReady() = onChange()
    }

    /** Safe to call repeatedly, e.g. after notification access was just granted. */
    fun start() {
        if (!listening && hasAccess) {
            try {
                sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
                listening = true
            } catch (e: SecurityException) {
                listening = false
            }
        }
        refresh()
    }

    fun stop() {
        if (listening) runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
        setController(null)
    }

    fun refresh() {
        val list = if (hasAccess) {
            try {
                sessions.getActiveSessions(listenerComponent)
            } catch (e: SecurityException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        choose(list)
    }

    fun info(): Info {
        val c = controller ?: return Info(null, null, null)
        val m = c.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val state = c.playbackState
        val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val position = state?.let(::positionOf)?.let { p -> duration?.let { p.coerceAtMost(it) } ?: p }
        val shuffle = compat?.shuffleMode?.takeIf { it >= 0 }?.let { it != PlaybackStateCompat.SHUFFLE_MODE_NONE }
        val repeat = when (compat?.repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_NONE -> RepeatMode.OFF
            PlaybackStateCompat.REPEAT_MODE_ONE -> RepeatMode.ONE
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> RepeatMode.ALL
            else -> null
        }
        return Info(title, artist, state?.state?.let(::isPlaying), duration, position, shuffle, repeat, activeQueueId(c))
    }

    fun play() {
        controller?.transportControls?.play() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause() {
        controller?.transportControls?.pause() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun toggle() {
        if (info().playing ?: audio.isMusicActive) pause() else play()
    }

    fun next() {
        controller?.transportControls?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** False when no followed session supports seeking. */
    fun seekTo(positionMs: Long): Boolean {
        val c = controller ?: return false
        val actions = c.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_SEEK_TO == 0L) return false
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        // Seeking right onto the end would skip the track; stay a second before it.
        val target = duration?.let { positionMs.coerceAtMost(it - 1_000) } ?: positionMs
        c.transportControls.seekTo(target.coerceAtLeast(0))
        return true
    }

    /** Jumps [deltaMs] forward (negative: back) from the current position. */
    fun skip(deltaMs: Long): Boolean {
        val position = info().position ?: return false
        return seekTo(position + deltaMs)
    }

    /**
     * Jumps to a queue item. Apps that do not support that (or say they don't) get "next"
     * presses instead, which only works forwards.
     */
    fun playItem(id: Long): Boolean {
        val c = controller ?: return false
        val target = rawQueue.indexOfFirst { it.queueId == id }
        if (target < 0) return false
        if (activeQueueId(c) == id) return true
        c.transportControls.skipToQueueItem(id)
        handler.postDelayed({
            if (controller === c && activeQueueId(c) != id) stepTo(c, id)
        }, JUMP_CHECK_DELAY)
        return true
    }

    private fun stepTo(c: MediaController, id: Long) {
        val from = activeQueueId(c)?.let { active -> rawQueue.indexOfFirst { it.queueId == active } } ?: return
        val to = rawQueue.indexOfFirst { it.queueId == id }
        val steps = to - from
        if (from < 0 || steps !in 1..MAX_STEPS) return
        repeat(steps) { i ->
            handler.postDelayed({ if (controller === c) c.transportControls.skipToNext() }, i * STEP_DELAY)
        }
    }

    fun setShuffle(on: Boolean): Boolean {
        val c = compat ?: return false
        if (c.shuffleMode < 0) return false
        c.transportControls.setShuffleMode(
            if (on) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE,
        )
        return true
    }

    fun setRepeat(mode: RepeatMode): Boolean {
        val c = compat ?: return false
        if (c.repeatMode < 0) return false
        c.transportControls.setRepeatMode(
            when (mode) {
                RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
                RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
                RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
            },
        )
        return true
    }

    /** Prefers a playing session, then the one already followed, then the top one. */
    private fun choose(list: List<MediaController>) {
        val current = controller
        val best = list.firstOrNull { it.playbackState?.state?.let(::isPlaying) == true }
            ?: list.firstOrNull { current != null && it.sessionToken == current.sessionToken }
            ?: list.firstOrNull()
        setController(best)
    }

    private fun setController(next: MediaController?) {
        val current = controller
        if (next?.sessionToken == current?.sessionToken) return
        current?.unregisterCallback(controllerCallback)
        compat?.let { c -> runCatching { c.unregisterCallback(compatCallback) } }
        controller = next
        compat = next?.let { c ->
            runCatching { MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(c.sessionToken)) }.getOrNull()
        }
        next?.registerCallback(controllerCallback, handler)
        compat?.registerCallback(compatCallback, handler)
        setQueue(next?.queue, next?.queueTitle)
        onMetadata(next?.metadata)
        onChange()
    }

    private fun setQueue(list: List<MediaSession.QueueItem>?, title: CharSequence?) {
        rawQueue = list.orEmpty()
        val items = rawQueue.map { item ->
            val d = item.description
            QueueItem(item.queueId, d.title?.toString().orEmpty(), d.subtitle?.toString()?.ifEmpty { null })
        }
        val next = if (items.isEmpty()) null else Queue(title?.toString()?.ifEmpty { null }, items)
        if (next != queue) queue = next
    }

    /**
     * The queue item playing now: the app's own answer, or else the first item with the
     * current title (some apps never set the active item).
     */
    private fun activeQueueId(c: MediaController): Long? {
        if (rawQueue.isEmpty()) return null
        val active = c.playbackState?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong()
        if (active != MediaSession.QueueItem.UNKNOWN_ID.toLong() && rawQueue.any { it.queueId == active }) return active
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        return rawQueue.firstOrNull { it.description.title?.toString() == title }?.queueId
    }

    /** Position now: the session reports it at its last update, so add the time played since. */
    private fun positionOf(state: PlaybackState): Long? {
        if (state.position < 0) return null
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0) return state.position
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return (state.position + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0)
    }

    private fun mediaKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun isPlaying(state: Int) =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
}
