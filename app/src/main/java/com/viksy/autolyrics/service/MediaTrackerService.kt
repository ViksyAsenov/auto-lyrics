package com.viksy.autolyrics.service

import android.content.ComponentName
import android.graphics.Bitmap
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
    val lastUpdateTime: Long
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerMediaSession()
    }

    private fun registerMediaSession() {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaTrackerService::class.java)
        val sessions = sessionManager.getActiveSessions(component)

        val ytMusicSession = sessions.firstOrNull {
            it.packageName.contains("com.google.android.apps.youtube.music")
        } ?: sessions.firstOrNull()

        activeController?.unregisterCallback(callback)
        activeController = ytMusicSession?.apply {
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

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val isPlaying = state.state == PlaybackState.STATE_PLAYING

        _currentTrack.value = ActiveTrack(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            positionMs = state.position,
            lastUpdateTime = state.lastPositionUpdateTime
        )
    }
}