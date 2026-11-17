package com.ender.ninniplayer

import com.ender.ninni.protocol.PlayerState

/** Live service status for the screen. Accessed on the main thread only. */
object Status {
    enum class Problem { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, ADVERTISING_UNSUPPORTED, ADVERTISING_FAILED, SERVER_FAILED }

    data class Snapshot(
        val running: Boolean = false,
        val advertising: Boolean = false,
        val problem: Problem? = null,
        /** Last advertising error code, when [problem] is ADVERTISING_FAILED. */
        val errorCode: Int = 0,
        val connected: Int = 0,
        val authorized: Int = 0,
        val state: PlayerState? = null,
    )

    var current = Snapshot()
        private set

    private val listeners = mutableSetOf<(Snapshot) -> Unit>()

    fun update(change: (Snapshot) -> Snapshot) {
        val next = change(current)
        if (next == current) return
        current = next
        listeners.toList().forEach { it(next) }
    }

    fun observe(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun remove(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}
