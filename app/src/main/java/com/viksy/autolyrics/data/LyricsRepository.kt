package com.viksy.autolyrics.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class LyricsRepository {
    private val client = OkHttpClient()

    suspend fun fetchLyrics(artist: String, track: String, duration: Int): LyricsState = withContext(Dispatchers.IO) {
        if (artist.isBlank() || track.isBlank()) {
            return@withContext LyricsState.NotFound
        }

        val urlBuilder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", track)
            
        if (duration > 0) {
            urlBuilder.addQueryParameter("duration", duration.toString())
        }

        val url = urlBuilder.build()

        val request = Request.Builder()
            .url(url)
            .header("Lrclib-App-Id", "VvV49FKIondtf7dYc23_XmW2DkrIHgkgSrSET8hKRuY")
            .header("User-Agent", "AutoLyrics/1.0")
            .header("X-User-Agent", "AutoLyrics/1.0")
            .header("Lrclib-Client", "AutoLyrics/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> return@withContext LyricsState.NotFound
                    429 -> return@withContext LyricsState.RateLimited
                }

                if (!response.isSuccessful) {
                    return@withContext LyricsState.UnknownError("HTTP ${response.code}: ${response.message}")
                }

                val body = response.body.string()

                if(body.isBlank()) {
                    return@withContext LyricsState.NotFound
                }

                val json = JSONObject(body)
                val syncedLrc = json.optString("syncedLyrics", "")

                if (syncedLrc.isNotBlank()) {
                    return@withContext LyricsState.Success(LyricsParser.parse(syncedLrc))
                } else {
                    return@withContext LyricsState.NotFound
                }
            }
        } catch (e: IOException) {
            return@withContext LyricsState.NetworkFailure
        } catch (e: Exception) {
            return@withContext LyricsState.UnknownError(e.message)
        }
    }
}