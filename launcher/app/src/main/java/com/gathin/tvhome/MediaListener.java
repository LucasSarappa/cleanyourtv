package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets NowPlaying list the active media sessions: MediaSessionManager.getActiveSessions
 * needs an enabled notification listener of the calling app. It never reads a notification. Enabled over ADB with
 * {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener}.
 */
public class MediaListener extends NotificationListenerService {
}
