package com.ender.ninniplayer

import android.service.notification.NotificationListenerService

/**
 * Exists only so the system lets this app read other apps' media sessions
 * (MediaSessionManager.getActiveSessions). Notifications themselves are ignored.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        PlayerService.instance?.refresh()
    }

    override fun onListenerDisconnected() {
        PlayerService.instance?.refresh()
    }
}
