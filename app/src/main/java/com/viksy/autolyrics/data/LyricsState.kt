package com.viksy.autolyrics.data

sealed class LyricsState {
    object Loading : LyricsState()
    data class Success(val lyrics: List<LyricsLine>) : LyricsState()
    object RateLimited : LyricsState()
    object NotFound : LyricsState()
    object NetworkFailure : LyricsState()
    data class UnknownError(val message: String?) : LyricsState()
}
