package com.viksy.autolyrics.service

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveTrack(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val lastUpdateTime: Long,
    val duration: Int
) {
    val currentEstimatedPositionMs: Long
        get() = if (isPlaying) {
            positionMs + (SystemClock.elapsedRealtime() - lastUpdateTime)
        } else {
            positionMs
        }
}

class MediaTrackerService : NotificationListenerService() {
    companion object {
        private val _currentTrack = MutableStateFlow<ActiveTrack?>(null)
        val currentTrack = _currentTrack.asStateFlow()
    }

    private var activeController: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateTrackInfo()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateTrackInfo()
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaTrackerService::class.java)
        sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
        updateActiveController(sessionManager.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        activeController?.unregisterCallback(callback)
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers == null) return

        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        val bestController = if (playingController != null) {
            playingController
        } else {
            val currentStillExists = controllers.firstOrNull { it.sessionToken == activeController?.sessionToken }
            currentStillExists
                ?: (controllers.firstOrNull {
                    it.packageName.contains("com.google.android.apps.youtube.music")
                } ?: controllers.firstOrNull {
                    it.packageName.contains("com.spotify.music")
                } ?: controllers.firstOrNull())
        }

        if (bestController == null) {
            activeController?.unregisterCallback(callback)
            activeController = null
            _currentTrack.value = _currentTrack.value?.copy(isPlaying = false)
            return
        }

        if (activeController?.sessionToken == bestController.sessionToken) {
            updateTrackInfo()
            return
        }

        activeController?.unregisterCallback(callback)
        activeController = bestController.apply {
            registerCallback(callback)
        }

        updateTrackInfo()
    }

    private fun updateTrackInfo() {
        val controller = activeController ?: return
        val metadata = controller.metadata
        val state = controller.playbackState

        if (metadata == null || state == null) {
            return
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Unknown Artist"
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown"
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val duration = (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000).toInt()

        _currentTrack.value = ActiveTrack(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            positionMs = state.position,
            lastUpdateTime = state.lastPositionUpdateTime,
            duration = duration
        )
    }
}