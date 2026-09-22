package com.viksy.autolyrics.car

import android.graphics.Color
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
import com.viksy.autolyrics.data.LrcLine
import com.viksy.autolyrics.data.LyricsRepository
import com.viksy.autolyrics.service.MediaTrackerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.withTranslation

data class LyricLineLayout(
    val activeLayout: android.text.StaticLayout,
    val inactiveLayout: android.text.StaticLayout,
    val baseY: Float,
    val totalHeight: Float
)

class LyricsScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val lyricsRepo = LyricsRepository()
    private var lyrics: List<LrcLine> = emptyList()
    private var lastLoadedTrack: String = ""

    private var activeSurface: Surface? = null
    private var visibleArea = Rect()
    private var renderJob: Job? = null

    private val activeTextPaint = TextPaint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val inactiveTextPaint = TextPaint().apply {
        color = Color.WHITE
        alpha = 100
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var backgroundColor = "#121212".toColorInt()
    private var layoutCache: List<LyricLineLayout> = emptyList()
    private val lineSpacing = 40f

    var targetScrollY = 0f
    var currentScrollY = 0f

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

                val cleanTitle = track.title.replace(Regex("\\(.*\\)|\\[.*]|-.*"), "").trim()

                targetScrollY = 0f
                currentScrollY = 0f

                val fetchedLyrics = lyricsRepo.fetchLyrics(track.artist, cleanTitle)

                val modifiedLyrics = mutableListOf<LrcLine>()

                if (fetchedLyrics.first().timeMs > 1000L) {
                    modifiedLyrics.add(LrcLine(timeMs = 0L, text = "🎵"))
                }

                modifiedLyrics.addAll(fetchedLyrics)

                lyrics = modifiedLyrics

                buildLayoutCache()
            }
        }

        lifecycleScope.launch {
            MediaTrackerService.currentTrack.collect { currentTrack ->
                if (lyrics.isEmpty() || currentTrack == null) return@collect

                val activeIndex = lyrics.indexOfLast { lrcLine ->
                    lrcLine.timeMs <= currentTrack.positionMs
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
        if (visibleArea.width() == 0 || lyrics.isEmpty()) return

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
        if (!surface.isValid) return

        val canvas = surface.lockCanvas(null) ?: return
        try {
            canvas.drawColor(backgroundColor)

            var activeIndex = -1

            val track = MediaTrackerService.currentTrack.value
            if (track != null) {
                val currentPositionMs = track.currentEstimatedPositionMs
                activeIndex = lyrics.indexOfLast { it.timeMs <= currentPositionMs }

                if (activeIndex != -1) {
                    val activeCache = layoutCache[activeIndex]
                    targetScrollY = activeCache.baseY + (activeCache.totalHeight / 2f)
                }
            }

            val distanceToScroll = targetScrollY - currentScrollY
            if (kotlin.math.abs(distanceToScroll) > visibleArea.height()) {
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
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
            .build()
    }
}