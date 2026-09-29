package com.viksy.autolyrics.car

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope
import com.viksy.autolyrics.data.LyricsLine
import com.viksy.autolyrics.data.LyricsRepository
import com.viksy.autolyrics.service.MediaTrackerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.withTranslation
import androidx.core.graphics.toColorInt
import kotlin.math.abs

data class LyricLineLayout(
    val activeLayout: android.text.StaticLayout,
    val inactiveLayout: android.text.StaticLayout,
    val baseY: Float,
    val totalHeight: Float
)

class LyricsScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val cleanTitleRegex = Regex("\\s*\\(.*?\\)|\\s*\\[.*?]|\\s+-\\s+.*")
    private val lyricsRepo = LyricsRepository()
    private var lyrics: List<LyricsLine> = emptyList()
    private var lastLoadedTrack: String = ""

    private var activeSurface: Surface? = null
    private var visibleArea = Rect()
    private var renderJob: Job? = null

    private val activeTextPaint = TextPaint().apply {
        color = "#FFFFFF".toColorInt()
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val inactiveTextPaint = TextPaint().apply {
        color = "#4A4A4A".toColorInt()
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val syncTextPaint = TextPaint().apply {
        color = "#1DB954".toColorInt()
        textSize = 14f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var manualTimeOffsetMs = 0L

    private var layoutCache: List<LyricLineLayout> = emptyList()
    private val lineSpacing = 40f

    var targetScrollY = 0f
    var currentScrollY = 0f

    private val backgroundColor = "#000000".toColorInt()

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        lifecycleScope.launch {
            MediaTrackerService.currentTrack.collect { track ->
                if(track == null) {
                    return@collect
                }

                val currentLoadedTrack = "${track.artist}-${track.title}"
                if (currentLoadedTrack == lastLoadedTrack) {
                    return@collect
                }

                lastLoadedTrack = currentLoadedTrack

                val cleanTitle = track.title.replace(cleanTitleRegex, "").trim()

                targetScrollY = 0f
                currentScrollY = 0f

                val fetchedLyrics = lyricsRepo.fetchLyrics(track.artist, cleanTitle).ifEmpty {
                    if (cleanTitle != track.title) {
                        lyricsRepo.fetchLyrics(track.artist, track.title)
                    }
                    else {
                        emptyList()
                    }
                }

                if (fetchedLyrics.isNotEmpty()) {
                    val modifiedLyrics = mutableListOf<LyricsLine>()

                    modifiedLyrics.add(LyricsLine(timeMs = -1000L, text = "♪"))

                    modifiedLyrics.addAll(fetchedLyrics)

                    lyrics = modifiedLyrics
                } else {
                    lyrics = listOf(LyricsLine(timeMs = 0L, text = "No lyrics found for this track"))
                }

                manualTimeOffsetMs = 0L

                buildLayoutCache()
            }
        }

        lifecycleScope.launch {
            MediaTrackerService.currentTrack.collect { track ->
                if (lyrics.isEmpty() || track == null) {
                    return@collect
                }

                val activeIndex = lyrics.indexOfLast { lrcLine ->
                    lrcLine.timeMs <= track.positionMs
                }

                if (activeIndex != -1) {
                    updatePlaybackPosition(activeIndex)
                }
            }
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        activeSurface = surfaceContainer.surface

        startRenderLoop()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea

        buildLayoutCache()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.visibleArea = stableArea

        buildLayoutCache()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        activeSurface = null

        renderJob?.cancel()
    }

    override fun onClick(x: Float, y: Float) {
        val halfWidth = visibleArea.width() / 2f

        if (x > halfWidth) {
            manualTimeOffsetMs += 50L
        } else {
            manualTimeOffsetMs -= 50L
        }
    }

    private fun startRenderLoop() {
        renderJob?.cancel()

        renderJob = lifecycleScope.launch {
            while (isActive) {
                drawFrame()
                delay(16.milliseconds)
            }
        }
    }

    private fun updatePlaybackPosition(newActiveIndex: Int) {
        targetScrollY = newActiveIndex * lineSpacing
    }

    private fun buildLayoutCache() {
        if (visibleArea.width() == 0 || lyrics.isEmpty()) {
            return
        }

        val maxWidth = (visibleArea.width() * 0.85f).toInt()
        val newCache = mutableListOf<LyricLineLayout>()
        var currentY = 0f

        for (line in lyrics) {
            val text = line.text

            val activeLayout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, activeTextPaint, maxWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .build()

            val inactiveLayout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, inactiveTextPaint, maxWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .build()

            val height = activeLayout.height.toFloat()

            newCache.add(LyricLineLayout(activeLayout, inactiveLayout, currentY, height))

            currentY += height + lineSpacing
        }

        layoutCache = newCache
    }

    private fun drawFrame() {
        val surface = activeSurface ?: return
        if (!surface.isValid) {
            return
        }

        val canvas = surface.lockCanvas(null) ?: return

        try {
            canvas.drawColor(backgroundColor)

            var activeIndex = -1

            val track = MediaTrackerService.currentTrack.value
            if (track != null) {
                val adjustedPositionMs = track.currentEstimatedPositionMs + manualTimeOffsetMs
                activeIndex = lyrics.indexOfLast { it.timeMs <= adjustedPositionMs }

                if (activeIndex != -1) {
                    val activeCache = layoutCache[activeIndex]
                    targetScrollY = activeCache.baseY + (activeCache.totalHeight / 2f)
                }
            }

            val distanceToScroll = targetScrollY - currentScrollY
            if (abs(distanceToScroll) > visibleArea.height()) {
                currentScrollY = targetScrollY
            } else {
                currentScrollY += distanceToScroll * 0.1f
            }

            val centerY = visibleArea.height() / 2f
            val maxWidth = (visibleArea.width() * 0.85f).toInt()
            val startX = (visibleArea.width() - maxWidth) / 2f

            for ((index, cache) in layoutCache.withIndex()) {
                val yPos = (centerY - currentScrollY) + cache.baseY

                if (yPos < -cache.totalHeight || yPos > visibleArea.height() + cache.totalHeight) {
                    continue
                }

                val layout = if (index == activeIndex) {
                    cache.activeLayout
                } else {
                    cache.inactiveLayout
                }

                canvas.withTranslation(startX, yPos) {
                    layout.draw(this)
                }
            }

            val sign = if (manualTimeOffsetMs > 0) "+" else ""
            val feedbackText = "$sign${manualTimeOffsetMs}ms"

            val leftMargin = 10f
            val bottomMargin = 10f

            val textX = visibleArea.left + leftMargin
            val textY = visibleArea.bottom - bottomMargin

            syncTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(feedbackText, textX, textY, syncTextPaint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.APP_ICON)
                    .build()
            )
            .build()
    }
}