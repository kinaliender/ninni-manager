package com.ender.ninnimanager

import android.content.Context

object Prefs {
    private const val FILE = "ninni"
    private const val KEY_PIN = "pin"
    private const val KEY_FADE = "sleep_fade"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Pairing code of the player, null until the user typed it once. */
    fun pin(context: Context): String? = prefs(context).getString(KEY_PIN, null)

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN, pin).apply()
    }

    /** Sleep timer fades the music out during its last minutes. */
    fun fade(context: Context): Boolean = prefs(context).getBoolean(KEY_FADE, true)

    fun setFade(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FADE, value).apply()
    }
}
