package com.ender.ninniplayer

import android.content.Context
import java.security.SecureRandom

object Prefs {
    private const val FILE = "ninni"
    private const val KEY_PIN = "pin"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOLUME_CAP = "volume_cap"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 4-digit pairing code shown on this phone and typed once into the manager. */
    fun pin(context: Context): String =
        prefs(context).getString(KEY_PIN, null) ?: newPin(context)

    fun newPin(context: Context): String {
        val pin = "%04d".format(SecureRandom().nextInt(10_000))
        prefs(context).edit().putString(KEY_PIN, pin).apply()
        return pin
    }

    /** Whether the user wants the service running (restored after reboot / app update). */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Highest music volume step allowed; null = no limit. */
    fun volumeCap(context: Context): Int? = prefs(context).getInt(KEY_VOLUME_CAP, -1).takeIf { it >= 0 }

    fun setVolumeCap(context: Context, value: Int?) {
        prefs(context).edit().putInt(KEY_VOLUME_CAP, value ?: -1).apply()
    }
}
