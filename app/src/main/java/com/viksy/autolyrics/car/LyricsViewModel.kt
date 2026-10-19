package com.viksy.autolyrics.car

import androidx.lifecycle.ViewModel
import com.viksy.autolyrics.data.LyricsRepository
import com.viksy.autolyrics.data.LyricsState
import com.viksy.autolyrics.data.LyricsLine
import com.viksy.autolyrics.service.ActiveTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class LyricsViewModel(
    private val repository: LyricsRepository = LyricsRepository()
) : ViewModel() {

    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _lyricsState = MutableStateFlow<LyricsState>(LyricsState.Loading)
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()

    private var lastLoadedTrack: String = ""
    private val cleanTitleRegex = Regex("\\s*\\(.*?\\)|\\s*\\[.*?]|\\s+-\\s+.*")

    fun onTrackChanged(track: ActiveTrack?) {
        if (track == null) {
            _lyricsState.value = LyricsState.NotFound
            return
        }

        val currentLoadedTrack = "${track.artist}-${track.title}"
        if (currentLoadedTrack == lastLoadedTrack) {
            return
        }
        lastLoadedTrack = currentLoadedTrack

        _lyricsState.value = LyricsState.Loading

        viewModelScope.launch {
            val originalTitle = track.title.trim()
            val cleanTitle = originalTitle.replace(cleanTitleRegex, "").trim()

            var state = repository.fetchLyrics(track.artist, cleanTitle, track.duration)
            
            if (state is LyricsState.NotFound && cleanTitle != originalTitle) {
                state = repository.fetchLyrics(track.artist, originalTitle, track.duration)
            }

            if (state is LyricsState.Success) {
                val modifiedLyrics = mutableListOf<LyricsLine>()
                modifiedLyrics.add(LyricsLine(timeMs = -1000L, text = "♪"))
                modifiedLyrics.addAll(state.lyrics)
                _lyricsState.value = LyricsState.Success(modifiedLyrics)
            } else {
                _lyricsState.value = state
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
    }
}
