package com.ender.ninnimanager

import android.app.Application

/** Holds the single player connection so it survives screen rotation. */
class NinniApp : Application() {
    val connection by lazy { PlayerConnection(this) }
}
