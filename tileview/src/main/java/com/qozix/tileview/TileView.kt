package com.qozix.tileview

import android.content.Context
import android.graphics.*
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import com.qozix.tileview.geom.CoordinateTranslator
import com.qozix.tileview.widgets.ZoomPanLayout
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ExecutorService
import kotlin.properties.Delegates

interface Ref<T> {
    val value: T?
}

final class SoftRef<T>(value: T) : Ref<T> {
    private val ref = SoftReference(value)
    override val value = ref.get()
}

final class StrongRef<T>(private val ref: T) : Ref<T> {
    override val value = ref
}

internal class Tile(val scale: Float, val row: Int, val column: Int, val rect: Rect, var bitmapRef: Ref<Bitmap>? = null, var task: BitmapLoadTask? = null) {
    override fun toString(): String {
        return "Tile(scale: $scale, row: $row, column: $column)"
    }
}

data class TileSize(val width: Int, val height: Int)

internal data class DetailLevel(val scale: Float, val rows: Int, val columns: Int, val scaledTileSize: TileSize, val tiles: List<List<Tile>>) : Comparable<DetailLevel> {
    override fun compareTo(other: DetailLevel) = scale.compareTo(other.scale)
}

interface BitmapProvider {
    fun loadBitmap(scale: Float, row: Int, column: Int): Bitmap?
}

internal class ClearPreviousDetailLevelTimer(timeout: Long, tileView: TileView) : CountDownTimer(timeout, timeout) {
    private val tileView = WeakReference(tileView)

    override fun onFinish() {
        tileView.get()?.clearPreviousDetailLevel()
    }

    override fun onTick(millisUntilFinished: Long) {}
}

class TileView : ZoomPanLayout, TileLogger {
    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var preview by Delegates.observable<Bitmap?>(null) { property, ol, new ->
        if (new != null) {
            previewSrcRect.right = new.width
            previewSrcRect.bottom = new.height
        }
    }

    var tileSize = TileSize(256, 256)
    var density = 1f
    var recycleBitmaps = true
    var bitmapProvider: BitmapProvider? = null
    var debug = false
    var executor: ExecutorService? = null
    var tileUpdateDebounceTime = 100L

    override var loggingEnabled = false

    private val coordinateTranslator = CoordinateTranslator()
    private val cleanPreviousDetailLevelTimeout = 10000L
    private var clearPreviousDetailLevelTimer: ClearPreviousDetailLevelTimer? = null
    private val TILE_UPDATE_REQUEST = 1
    private val canvasClipBounds = Rect()
    private val dstRect = Rect(0, 0, 0, 0)
    private val previewSrcRect = Rect(0, 0, 0, 0)
    private val previewDstRect = Rect(0, 0, 0, 0)
    private val detailLevels = TreeSet<DetailLevel>()
    private var currentDetailLevel: DetailLevel? = null
    private var previousDetailLevel: DetailLevel? = null
    private val debugPaint by lazy { Paint().apply { color = Color.GREEN } }
    private val debounceHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message?) {
            updateTiles()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        requestTileUpdate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        determineDetailLevel(scale)
        requestTileUpdate()
    }

    private fun requestTileUpdate() {
        if (!debounceHandler.hasMessages(TILE_UPDATE_REQUEST)) {
            debounceHandler.sendEmptyMessageDelayed(TILE_UPDATE_REQUEST, tileUpdateDebounceTime)
        }
    }

    private fun updateTiles() {
        val detailLevel = currentDetailLevel ?: return
        val previousDetailLevel = previousDetailLevel

        val viewport = Rect(scrollX, scrollY, width + scrollX, height + scrollY)

        if (previousDetailLevel != null) {
            // If there is a previous detail level make sure to clear tiles
            // that are scrolled out of the viewport
            tiles(previousDetailLevel, viewport) { tile, visible ->
                if (!visible) {
                    clear(tile)
                }
            }
        }

        tiles(detailLevel, viewport) { tile, visible ->
            if (visible) {
                load(tile)
            } else {
                clear(tile)
            }
        }
    }

    internal fun invalidate(tile: Tile) {
        invalidate() // TODO invalidaterect??
    }

    private fun soften(detailLevel: DetailLevel) {
        info { "Softening detail level ${detailLevel.scale}" }
        detailLevel.tiles.forEach { it.forEach {
            it.bitmapRef = it.bitmapRef?.value?.let { SoftRef(it) }
        } }
    }

    private fun cancel(detailLevel: DetailLevel) {
        info { "Canceling detail level ${detailLevel.scale}" }
        detailLevel.tiles.forEach { it.forEach { cancel(it) } }
    }

    private fun clear(detailLevel: DetailLevel) {
        info { "Clearing detail level ${detailLevel.scale}" }
        detailLevel.tiles.forEach { it.forEach { clear(it) } }
    }

    internal fun clearPreviousDetailLevel() {
        clearPreviousDetailLevelTimer = null
        previousDetailLevel?.let { clear(it) }
        previousDetailLevel = null
    }

    private fun cancel(tile: Tile) {
        if (tile.task != null) {
            info { "Canceling task $tile" }
            tile.task?.cancel(true)
            tile.task = null
        }
    }

    private fun clear(tile: Tile) {
        cancel(tile)

        if(tile.bitmapRef != null) {
            debug { "Clearing bitmap $$tile" }
            if (recycleBitmaps) tile.bitmapRef?.value?.apply { recycle() }
            tile.bitmapRef = null
        }
    }

    private fun load(tile: Tile) {
        // When bitmap is already loaded or loading, return
        if (tile.bitmapRef != null || tile.task != null) return

        // Start async load task
        info { "Scheduling bitmap load $tile" }
        tile.task = BitmapLoadTask(this, tile, loggingEnabled).apply {
            val executor = executor
            if (executor != null) {
                executeOnExecutor(executor)
            } else {
                execute()
            }
        }
    }

    override fun onScaleChanged(currentScale: Float, previousScale: Float) {
        determineDetailLevel(currentScale)
        requestTileUpdate()
    }

    private fun determineDetailLevel(scale: Float) {
        val new = detailLevels.find { it.scale >= scale / density } ?: detailLevels.lastOrNull()

        val current = currentDetailLevel
        val previous = previousDetailLevel
        if (new != null && current != null && new.scale != current.scale) {
            info { "Changed detail level from ${current.scale} to ${new.scale}" }

            // If there is a timer running to cleanup the previous detail level then stop it
            // Since we're switching to a new detail level already
            clearPreviousDetailLevelTimer?.cancel()
            clearPreviousDetailLevelTimer = null

            // If there is a previous detail level, clear it
            if (previous != null) {
                clear(previous)
            }

            // Cancel pending tasks on current detail level
            cancel(current)

            // Make references to the bitmaps in the previous detail level soft
            soften(current)

            // Set previous detail level to prevent flickering
            previousDetailLevel = current

            clearPreviousDetailLevelTimer = ClearPreviousDetailLevelTimer(cleanPreviousDetailLevelTimeout, this)
        }

        currentDetailLevel = new
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.getClipBounds(canvasClipBounds)

        val translateX = Math.max(0f, canvasClipBounds.width() / 2f - scaledWidth / 2f)
        val translateY = Math.max(0f, canvasClipBounds.height() / 2f - scaledHeight / 2f)

        canvas.translate(translateX, translateY)
        canvas.scale(scale, scale)
        preview?.let {
            previewDstRect.right = baseWidth
            previewDstRect.bottom = baseHeight
            canvas.drawBitmap(it, previewSrcRect, previewDstRect, null)
        }

        previousDetailLevel?.let { drawTiles(canvas, it, false) }
        currentDetailLevel?.let { drawTiles(canvas, it, debug) }

        canvas.restore()
    }

    private fun drawTiles(canvas: Canvas, detailLevel: DetailLevel, debug: Boolean) {
        visibleTiles(detailLevel, canvasClipBounds) { tile ->
            tile.bitmapRef?.value?.let {
                dstRect.right = tileSize.width
                dstRect.bottom = tileSize.height
                canvas.drawBitmap(it, dstRect, tile.rect, null)
            }

            if (debug) drawDebug(canvas, tile)
        }
    }

    private fun drawDebug(canvas: Canvas, tile: Tile) {
        debugPaint.style = Paint.Style.STROKE
        debugPaint.strokeWidth = 1f / scale
        canvas.drawRect(tile.rect, debugPaint)

        debugPaint.style = Paint.Style.FILL
        debugPaint.textSize = 35f / scale
        canvas.drawText("${tile.row}, ${tile.column} - ${tile.scale}", tile.rect.left.toFloat() + debugPaint.textSize / 3, tile.rect.top.toFloat() + debugPaint.textSize * 1.1f, debugPaint)
    }

    private inline fun tiles(detailLevel: DetailLevel, viewPort: Rect, callback: (tile: Tile, visible: Boolean) -> Unit) {
        visibleRowsAndColumns(detailLevel, viewPort) { detailLevel, rowStart, rowEnd, columnStart, columnEnd ->
            for (row in 0..detailLevel.rows - 1) {
                for (column in 0..detailLevel.columns - 1) {
                    val visible = row >= rowStart && row <= rowEnd && column >= columnStart && column <= columnEnd
                    callback(detailLevel.tiles[row][column], visible)
                }
            }
        }
    }

    private inline fun visibleTiles(detailLevel: DetailLevel, viewPort: Rect, visible: (tile: Tile) -> Unit) {
        visibleRowsAndColumns(detailLevel, viewPort) { detailLevel, rowStart, rowEnd, columnStart, columnEnd ->
            for (row in rowStart..rowEnd) {
                for (column in columnStart..columnEnd) {
                    visible(detailLevel.tiles[row][column])
                }
            }
        }
    }

    private inline fun visibleRowsAndColumns(detailLevel: DetailLevel, viewPort: Rect, callback: (detailLevel: DetailLevel, rowStart: Int, rowEnd: Int, columnStart: Int, columnEnd: Int) -> Unit) {
        val rowStart = Math.floor(viewPort.top / (detailLevel.scaledTileSize.height * scale.toDouble())).toInt()
        val rowEnd = Math.min(Math.floor(viewPort.bottom / (detailLevel.scaledTileSize.height * scale.toDouble())).toInt(), detailLevel.rows - 1)
        val columnStart = Math.floor(viewPort.left / (detailLevel.scaledTileSize.width * scale.toDouble())).toInt()
        val columnEnd = Math.min(Math.floor(viewPort.right / (detailLevel.scaledTileSize.width * scale.toDouble())).toInt(), detailLevel.columns - 1)

        callback(detailLevel, rowStart, rowEnd, columnStart, columnEnd)
    }

    // TODO ceil floor??
    private fun TileSize.scale(scale: Float) = TileSize((width / scale).toInt(), (height / scale).toInt())

    fun addDetailLevel(scale: Float) {
        val scaledTileSize = tileSize.scale(scale)
        val columnsCount = Math.ceil(baseWidth / scaledTileSize.width.toDouble()).toInt()
        val rowsCount = Math.ceil(baseHeight / scaledTileSize.height.toDouble()).toInt()

        val tiles = (0..rowsCount - 1).map { row ->
            (0..columnsCount - 1).map { column ->
                val left = column * scaledTileSize.width
                val top = row * scaledTileSize.height
                val tileRect = Rect(left, top, Math.min(left + scaledTileSize.width, baseWidth), Math.min(top + scaledTileSize.height, baseHeight))
                Tile(scale, row, column, tileRect)
            }
        }

        detailLevels += DetailLevel(scale, rowsCount, columnsCount, scaledTileSize, tiles)
    }

    override fun setSize(width: Int, height: Int) {
        super.setSize(width, height)
        coordinateTranslator.setSize(width, height)
    }

    /**
     * Register a set of offset points to use when calculating position within the TileView.
     * Any type of coordinate system can be used (any type of lat/lng, percentile-based, etc),
     * and all positioned are calculated relatively.  If relative bounds are defined, position parameters
     * received by TileView methods will be translated to the the appropriate pixel value.
     * To remove this process, use undefineBounds.

     * @param left   The left edge of the rectangle used when calculating position.
     * *
     * @param top    The top edge of the rectangle used when calculating position.
     * *
     * @param right  The right edge of the rectangle used when calculating position.
     * *
     * @param bottom The bottom edge of the rectangle used when calculating position.
     */
    fun defineBounds(left: Double, top: Double, right: Double, bottom: Double) {
        coordinateTranslator.setBounds(left, top, right, bottom)
    }

    /**
     * Unregisters arbitrary bounds and coordinate system.  After invoking this method,
     * TileView methods that receive position method parameters will use pixel values,
     * relative to the TileView's registered size (at 1.0f scale).
     */
    fun undefineBounds() {
        coordinateTranslator.unsetBounds()
    }

    /**
     * Scrolls (instantly) the TileView to the x and y positions provided.  The is an overload
     * of scrollTo( int x, int y ) that accepts doubles; if the TileView has relative bounds defined,
     * those relative doubles will be converted to absolute pixel positions.

     * @param x The relative x position to move to.
     * *
     * @param y The relative y position to move to.
     */
    fun scrollTo(x: Double, y: Double) {
        scrollTo(
                coordinateTranslator.translateAndScaleX(x, scale),
                coordinateTranslator.translateAndScaleY(y, scale))
    }

    /**
     * Scrolls (instantly) the TileView to the x and y positions provided,
     * then centers the viewport to the position.

     * @param x The relative x position to move to.
     * *
     * @param y The relative y position to move to.
     */
    fun scrollToAndCenter(x: Double, y: Double) {
        scrollToAndCenter(
                coordinateTranslator.translateAndScaleX(x, scale),
                coordinateTranslator.translateAndScaleY(y, scale))
    }

    /**
     * Scrolls (with animation) the TileView to the relative x and y positions provided.

     * @param x The relative x position to move to.
     * *
     * @param y The relative y position to move to.
     */
    fun slideTo(x: Double, y: Double) {
        slideTo(
                coordinateTranslator.translateAndScaleX(x, scale),
                coordinateTranslator.translateAndScaleY(y, scale))
    }

    /**
     * Scrolls (with animation) the TileView to the x and y positions provided,
     * then centers the viewport to the position.

     * @param x The relative x position to move to.
     * *
     * @param y The relative y position to move to.
     */
    fun slideToAndCenter(x: Double, y: Double) {
        slideToAndCenter(
                coordinateTranslator.translateAndScaleX(x, scale),
                coordinateTranslator.translateAndScaleY(y, scale))
    }

    /**
     * Scrolls and scales (with animation) the TileView to the specified x, y and scale provided.
     * The TileView will be centered to the coordinates passed.

     * @param x     The relative x position to move to.
     * *
     * @param y     The relative y position to move to.
     * *
     * @param scale The scale the TileView should be at when the animation is complete.
     */
    fun slideToAndCenterWithScale(x: Double, y: Double, scale: Float) {
        slideToAndCenterWithScale(
                coordinateTranslator.translateAndScaleX(x, scale),
                coordinateTranslator.translateAndScaleY(y, scale),
                scale)
    }
}