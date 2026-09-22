package com.viksy.autolyrics.data

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    private val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")

    fun parse(lrcContent: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()

        lrcContent.lineSequence().forEach { rawLine ->
            val match = regex.find(rawLine.trim()) ?: return@forEach

            val (min, sec, sub, text) = match.destructured
            val totalMs = calculateMs(min, sec, sub)

            if (text.isBlank()) {
                return@forEach
            }

            lines.add(LrcLine(totalMs, text.trim()))
        }

        return lines.sortedBy { it.timeMs }
    }

    private fun calculateMs(min: String, sec: String, sub: String): Long {
        val minutes = min.toLongOrNull() ?: 0L
        val seconds = sec.toLongOrNull() ?: 0L
        val millis = if (sub.length == 2) sub.toLong() * 10 else sub.toLong()

        return (minutes * 60 * 1000) + (seconds * 1000) + millis
    }
}