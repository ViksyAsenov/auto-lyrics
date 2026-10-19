package com.viksy.autolyrics.car

import android.content.Context
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
import com.viksy.autolyrics.data.LyricsState
import com.viksy.autolyrics.service.MediaTrackerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import androidx.core.content.edit
import kotlin.math.abs

data class LyricLineLayout(
    val activeLayout: android.text.StaticLayout,
    val inactiveLayout: android.text.StaticLayout,
    val baseY: Float,
    val totalHeight: Float
)

const val whiteColor = "#FFFFFF"
const val grayColor = "#4A4A4A"
const val greenColor = "#1DB954"
const val blackColor = "#000000"

const val activeTextSize = 36f
const val inactiveTextSize = 28f
const val syncTextSize = 16f
const val lineSpacingSize = 40f

class LyricsScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val viewModel = LyricsViewModel()
    private var lyrics: List<LyricsLine> = emptyList()

    private var activeSurface: Surface? = null
    private var visibleArea = Rect()
    private var renderJob: Job? = null

    private val prefs = carContext.getSharedPreferences("lyrics_prefs", Context.MODE_PRIVATE)
    private var zoomMultiplier = prefs.getFloat("zoom_multiplier", 1.0f)

    private val activeTextPaint = TextPaint().apply {
        color = whiteColor.toColorInt()
        textSize = activeTextSize * zoomMultiplier
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val inactiveTextPaint = TextPaint().apply {
        color = grayColor.toColorInt()
        textSize = inactiveTextSize * zoomMultiplier
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val syncTextPaint = TextPaint().apply {
        color = greenColor.toColorInt()
        textSize = syncTextSize
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var manualTimeOffsetMs = 0L

    private var layoutCache: List<LyricLineLayout> = emptyList()
    private val currentLineSpacing: Float
        get() = lineSpacingSize * zoomMultiplier

    var targetScrollY = 0f
    var currentScrollY = 0f

    private val backgroundColor = blackColor.toColorInt()

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        lifecycleScope.launch {
            MediaTrackerService.currentTrack.collect { track ->
                viewModel.onTrackChanged(track)
            }
        }

        lifecycleScope.launch {
            viewModel.lyricsState.collect { state ->
                when (state) {
                    is LyricsState.Loading -> {
                        lyrics = listOf(LyricsLine(timeMs = 0L, text = "Loading lyrics..."))
                    }
                    is LyricsState.Success -> {
                        lyrics = state.lyrics
                    }
                    is LyricsState.RateLimited -> {
                        lyrics = listOf(LyricsLine(timeMs = 0L, text = "Rate limit exceeded, please wait a moment."))
                    }
                    is LyricsState.NotFound -> {
                        lyrics = listOf(LyricsLine(timeMs = 0L, text = "Lyrics not found for this track."))
                    }
                    is LyricsState.NetworkFailure -> {
                        lyrics = listOf(LyricsLine(timeMs = 0L, text = "Network error. Please check your connection."))
                    }
                    is LyricsState.UnknownError -> {
                        lyrics = listOf(LyricsLine(timeMs = 0L, text = "Error: ${state.message ?: "Unknown error"}"))
                    }
                }

                targetScrollY = 0f
                currentScrollY = 0f
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
        val halfHeight = visibleArea.height() / 2f

        if(y > halfHeight) {
            if (x > halfWidth) {
                manualTimeOffsetMs += 50L
            } else {
                manualTimeOffsetMs -= 50L
            }
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
        if (newActiveIndex >= 0 && newActiveIndex < layoutCache.size) {
            val activeCache = layoutCache[newActiveIndex]
            targetScrollY = activeCache.baseY + (activeCache.totalHeight / 2f)
        }
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

            currentY += height + currentLineSpacing
        }

        layoutCache = newCache
    }

    private fun drawFrame() {
        val surface = activeSurface ?: return
        if (!surface.isValid) {
            return
        }

        val canvas = try {
            surface.lockCanvas(null) ?: return
        } catch (_: Exception) {
            return
        }

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
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    private fun setZoomMultiplier(multiplier: Float) {
        zoomMultiplier = multiplier.coerceIn(0.1f, 10.0f)
        prefs.edit { putFloat("zoom_multiplier", zoomMultiplier) }
        
        activeTextPaint.textSize = activeTextSize * zoomMultiplier
        inactiveTextPaint.textSize = inactiveTextSize * zoomMultiplier
        
        buildLayoutCache()
        invalidate()
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("-")
                            .setOnClickListener {
                                setZoomMultiplier(zoomMultiplier - 0.2f)
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("+")
                            .setOnClickListener {
                                setZoomMultiplier(zoomMultiplier + 0.2f)
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}