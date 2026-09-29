package com.viksy.autolyrics.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class LyricsRepository {
    private val client = OkHttpClient()

    suspend fun fetchLyrics(artist: String, track: String): List<LyricsLine> = withContext(Dispatchers.IO) {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", track)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AutoLyrics/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body.string()

                if(body.isBlank()) {
                    return@withContext emptyList()
                }

                val json = JSONObject(body)
                val syncedLrc = json.optString("syncedLyrics", "")

                if (syncedLrc.isNotBlank()) {
                    return@withContext LyricsParser.parse(syncedLrc)
                }
            }
        } catch (_: Exception) { }

        emptyList()
    }
}