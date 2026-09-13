package com.auxnon.booxnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Full-screen drawing surface backed by the Onyx hardware pen chip.
 *
 * Architecture:
 *  - TouchHelper drives zero-latency hardware preview (setRawDrawingRenderEnabled=true).
 *  - RawInputCallback accumulates points and renders to the active software layer.
 *  - On pen-up / onPenUpRefresh the hardware preview clears and composed layers are shown.
 */
class HardwarePenSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val TAG = "HardwarePenSurface"
        private const val RECONFIGURE_DEBOUNCE_MS = 32L
        private const val UNDO_STACK_MAX = 15
        private const val TWO_FINGER_TAP_MAX_DURATION_MS = 300L
        private const val TWO_FINGER_TAP_MAX_MOVEMENT_DP = 24f
        private const val OVERVIEW_PINCH_EXIT_RATIO = 0.72f

        /** No raw point for this long means the stroke is orphaned (its onEndRawDrawing is never
         *  coming), not merely long. Comfortably above the gap between points in a slow stroke. */
        private const val STROKE_STALE_MS = 900L

        /** Time for the pen chip to actually leave/re-enter its raw session around a repaint. */
        private const val RAW_SESSION_TOGGLE_SETTLE_MS = 32L

        /** Minimum gap between app-rendered preview repaints. Raw points arrive far faster than
         *  e-ink can repaint, so without this the refreshes queue up and fall behind the pen. */
        private const val PREVIEW_FRAME_MS = 40L

        private const val HOVER_BUTTON_MASK = MotionEvent.BUTTON_PRIMARY or
            MotionEvent.BUTTON_SECONDARY or
            MotionEvent.BUTTON_TERTIARY or
            MotionEvent.BUTTON_STYLUS_PRIMARY or
            MotionEvent.BUTTON_STYLUS_SECONDARY or
            MotionEvent.BUTTON_BACK or
            MotionEvent.BUTTON_FORWARD
    }

    data class LayerInfo(
        val id: Int,
        val name: String,
        val visible: Boolean,
        val opacity: Float,
        val active: Boolean,
    )

    data class LayerSnapshot(
        val name: String,
        val visible: Boolean,
        val opacity: Float,
        val bitmap: Bitmap,
    )

    data class DocumentSnapshot(
        val width: Int,
        val height: Int,
        val activeLayerIndex: Int,
        val layers: List<LayerSnapshot>,
    )

    data class StylusHoverButtonState(
        val hovering: Boolean,
        val buttonState: Int,
        val stylusPrimaryPressed: Boolean,
        val stylusSecondaryPressed: Boolean,
    )

    /** One entry per committed stroke/erase, holding the layer's bitmap just before it. Bounded
     *  ring buffer, not a full document-history undo system - scoped to "undo last stroke" only,
     *  triggered via a two-finger tap on the canvas. */
    private data class UndoEntry(val layerId: Int, val bitmap: Bitmap)

    private val undoStack = ArrayDeque<UndoEntry>()

    private data class LayerState(
        val id: Int,
        var name: String,
        var visible: Boolean,
        var opacity: Float,
        val bitmap: Bitmap,
        val canvas: Canvas,
        val snapshotBitmap: Bitmap,
        val snapshotCanvas: Canvas,
    )

    private enum class ViewGestureMode {
        NONE,
        PAN,
        PINCH,
    }

    // Active pen state
    private var activeStyle = HardwarePenStyle.PENCIL
    private var activeWidthPx = HardwarePenStyle.PENCIL.defaultWidthPx
    private var activeColor = Color.BLACK
    private var rawInputSuppressed = false
    private var manualEraserMode = false
    private var stylusTipEraserMode = false
    private var sideButtonEraserMode = false
    private var eraseModeListener: ((Boolean) -> Unit)? = null
    private var stylusHoverButtonListener: ((StylusHoverButtonState) -> Unit)? = null
    private var twoFingerTapListener: (() -> Unit)? = null

    // Viewport transform (software canvas only; hardware preview stays 1.0x)
    @Volatile
    private var viewScale = 1f
    @Volatile
    private var viewOffsetX = 0f
    @Volatile
    private var viewOffsetY = 0f
    private val minViewScale = 1f
    private val maxViewScale = 4f
    private var viewGestureMode = ViewGestureMode.NONE
    private var panLastX = 0f
    private var panLastY = 0f
    private var pinchStartDistance = 0f
    private var pinchStartScale = 1f
    private var pinchAnchorWorldX = 0f
    private var pinchAnchorWorldY = 0f
    private var pinchTapEligible = false
    private var pinchStartTimeMs = 0L
    private var pinchMaxMovementPx = 0f
    private var pinchP0StartX = 0f
    private var pinchP0StartY = 0f
    private var pinchP1StartX = 0f
    private var pinchP1StartY = 0f

    // Layers (index 0 = bottom, last = top)
    private val layers = ArrayList<LayerState>(8)
    private var nextLayerId = 1
    private var activeLayerId = -1

    // In-flight stroke
    private var strokeStyle = HardwarePenStyle.PENCIL
    private var strokeWidthPx = 5f
    private var strokeColor = Color.BLACK
    private var strokeLayerId = -1
    private val strokePoints = ArrayList<TouchPoint>(256)
    private val rawMovePoints = ArrayList<TouchPoint>(256)
    private var receivedAuthorityList = false
    private var pendingPenUpRefresh = false
    private var hasRenderedThisStroke = false
    // RawInputCallback methods (which set this) and performReconfigureTouchHelper (which now
    // reads it, to avoid resetting the chip mid-stroke) don't reliably run on the same thread -
    // postInvalidate() (not invalidate()) was already needed elsewhere for the same reason.
    @Volatile
    private var strokeInProgress = false
    private var strokeIsErase = false
    private var strokeViewScale = 1f
    private var strokeViewOffsetX = 0f
    private var strokeViewOffsetY = 0f

    // TouchHelper (configured on a dedicated background thread)
    @Volatile
    private var helperWorkerThread: Thread? = null
    private val helperThread = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            Thread {
                helperWorkerThread = Thread.currentThread()
                r.run()
            }.apply {
                name = "HardwarePenHelper"
                isDaemon = true
            }
        }
    )
    private var touchHelper: TouchHelper? = null
    @Volatile
    private var viewportListener: ((Float) -> Unit)? = null
    @Volatile
    private var viewportGestureSuppressRaw = false
    private var lastStylusButtonState = Int.MIN_VALUE
    private var lastRawToolType = Int.MIN_VALUE
    private var stylusHovering = false
    private var lastHoverButtonState = Int.MIN_VALUE
    private var lastHoveringState = false

    // Reused paint for layer-alpha composition
    private val layerPaint = Paint().apply { isFilterBitmap = true }

    private var epdRefreshScheduled = false
    @Volatile
    private var lastRawPointAtMs = 0L
    private var rawExclusionRects: List<Rect> = emptyList()

    // Public API

    fun setStyle(style: HardwarePenStyle) {
        activeStyle = style
        reconfigureTouchHelper()
    }

    fun setStrokeWidthPx(widthPx: Float) {
        activeWidthPx = widthPx.coerceIn(1f, 200f)
        reconfigureTouchHelper()
    }

    fun setStrokeColor(color: Int) {
        activeColor = color
        reconfigureTouchHelper()
    }

    fun setOnViewportChangedListener(listener: ((Float) -> Unit)?) {
        viewportListener = listener
        listener?.invoke(viewScale)
    }

    fun setOnEraserModeChangedListener(listener: ((Boolean) -> Unit)?) {
        eraseModeListener = listener
        listener?.invoke(isEraseModeActive())
    }

    fun setOnStylusHoverButtonChangedListener(listener: ((StylusHoverButtonState) -> Unit)?) {
        stylusHoverButtonListener = listener
    }

    fun setOnTwoFingerTapListener(listener: (() -> Unit)?) {
        twoFingerTapListener = listener
    }

    /** Fires once per pinch gesture when the user keeps pinching inward past the point where
     *  zoom-out is already maxed out - the same "rubber-band past the limit" gesture apps like
     *  Notability use to jump from a page to the page overview. */
    fun setOnOverviewPinchListener(listener: (() -> Unit)?) {
        overviewPinchListener = listener
    }

    private var overviewPinchListener: (() -> Unit)? = null
    private var overviewPinchFired = false

    /**
     * Reports where a stroke actually started, in screen coordinates (matching MotionEvent
     * rawX/rawY), so the Activity can dismiss overlays the pen has drawn past.
     *
     * This exists because a stylus touch never reaches the Activity's dispatchTouchEvent() while
     * the raw drawing session is open - the pen chip consumes it directly. (dispatchTouchEvent()
     * applies no tool-type filter, so if pen touches reached it they would already behave exactly
     * like finger taps; they don't, which is what proves the pen bypasses it.) It fires on the
     * stroke itself rather than on hover: hover-based dismissal made panels vanish from nothing
     * more than a pen passing overhead.
     */
    fun setOnStrokeStartListener(listener: ((screenX: Float, screenY: Float) -> Unit)?) {
        stylusPointerListener = listener
    }

    private var stylusPointerListener: ((Float, Float) -> Unit)? = null

    fun isStrokeInProgress(): Boolean = strokeInProgress

    /** Fires once per stroke, after the pen-up refresh has been issued. Lets the Activity run
     *  screen-level e-ink work that has to wait for the pen chip to be done with the display. */
    fun setOnStrokeFinishedListener(listener: (() -> Unit)?) {
        strokeFinishedListener = listener
    }

    private var strokeFinishedListener: (() -> Unit)? = null

    /**
     * Clears [regionInView] without leaving the pen chip's raw session, for use at the moment a
     * stroke begins - when a panel dismissed by the pen still has its pixels on the panel and the
     * stroke is about to be drawn over them. refreshScreenRegion() is swallowed there.
     */
    fun handwritingRepaintRegion(regionInView: Rect?) {
        val rect = regionInView ?: Rect(0, 0, width, height)
        if (rect.isEmpty) return
        invalidate()
        runCatching { EpdController.handwritingRepaint(this, rect) }
            .onFailure { Log.w(TAG, "handwritingRepaint failed: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            EpdController.invalidate(this, rect.left, rect.top, rect.right, rect.bottom, UpdateMode.DU)
        }.onFailure { Log.w(TAG, "region invalidate failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Repaints [regionInView] with the raw session switched off around the call. While raw drawing
     * is enabled the controller owns that area of the panel and swallows ordinary refreshes, which
     * is why a panel dismissed by drawing kept its pixels. Raw input is restored straight after.
     */
    fun refreshRegionOutsideRawSession(regionInView: Rect?) {
        val helper = touchHelper
        if (helper == null) {
            invalidateAndRefreshEpd(UpdateMode.GC)
            return
        }
        runOnHelperThread { runCatching { helper.setRawDrawingEnabled(false) } }
        postDelayed({
            invalidate()
            runCatching {
                EpdController.invalidate(this, UpdateMode.GC)
                if (regionInView != null) {
                    EpdController.refreshScreenRegion(
                        this, regionInView.left, regionInView.top,
                        regionInView.right, regionInView.bottom, UpdateMode.GC
                    )
                } else {
                    EpdController.refreshScreen(this, UpdateMode.GC)
                }
            }
            postDelayed({
                runOnHelperThread {
                    runCatching {
                        helper.setRawDrawingEnabled(!(rawInputSuppressed || viewportGestureSuppressRaw))
                    }
                }
            }, RAW_SESSION_TOGGLE_SETTLE_MS)
        }, RAW_SESSION_TOGGLE_SETTLE_MS)
    }

    /**
     * Previews the pencil using the chip's charcoal brush instead of its pencil one.
     *
     * A genuine app-drawn preview turned out to be unreachable: the only TouchHelper feature flag
     * that delivers pen input at all on this device is the one where SurfaceFlinger draws the
     * stroke, and setRawDrawingRenderEnabled(false) does not stop it (this project ran that way for
     * a long time and still had a preview). So the chip will draw - the only choice is which brush
     * it draws with, and that need not be the brush we bake with. Charcoal is the one whose
     * hardware preview has real texture, which is far closer to the grainy pencil bake than the
     * chip's own flat pencil dabs.
     */
    fun setTexturedPencilPreview(enabled: Boolean) {
        if (texturedPencilPreview == enabled) return
        texturedPencilPreview = enabled
        reconfigureTouchHelper()
    }

    fun isTexturedPencilPreview(): Boolean = texturedPencilPreview

    @Volatile
    private var texturedPencilPreview = false

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /** Reverts the most recently committed stroke or erase, on whichever layer it was drawn on. */
    fun undoLastStroke(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        val layer = layerById(entry.layerId)
        if (layer == null) {
            if (!entry.bitmap.isRecycled) entry.bitmap.recycle()
            return false
        }
        clearBitmap(layer.canvas)
        layer.canvas.drawBitmap(entry.bitmap, 0f, 0f, null)
        if (!entry.bitmap.isRecycled) entry.bitmap.recycle()
        updateSnapshot(layer.id)
        invalidateAndRefreshEpd(UpdateMode.GC)
        return true
    }

    private fun pushUndoEntry(layerId: Int, beforeBitmap: Bitmap) {
        undoStack.addLast(UndoEntry(layerId, beforeBitmap))
        while (undoStack.size > UNDO_STACK_MAX) {
            val dropped = undoStack.removeFirstOrNull() ?: break
            if (!dropped.bitmap.isRecycled) dropped.bitmap.recycle()
        }
    }

    private fun clearUndoStack() {
        undoStack.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        undoStack.clear()
    }

    private fun dropUndoEntriesForLayer(layerId: Int) {
        val it = undoStack.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.layerId == layerId) {
                if (!entry.bitmap.isRecycled) entry.bitmap.recycle()
                it.remove()
            }
        }
    }

    fun setManualEraserMode(enabled: Boolean) {
        val before = isEraseModeActive()
        manualEraserMode = enabled
        val after = isEraseModeActive()
        if (before != after) {
            eraseModeListener?.invoke(after)
            reconfigureTouchHelper()
        }
    }

    /**
     * Arms/disarms erase for the pen's side button, from whatever signal noticed it first.
     *
     * Deliberately the same path the eraser tool uses - the tool only ever worked because its mode
     * flips while the pen is idle, so the chip is reconfigured to the white erase preview before
     * the pen lands. The side button was instead being noticed in onBeginRawDrawing from the raw
     * tool type, i.e. after the chip had already been set up to draw ink: too late to preview, and
     * its reconfigure then landed mid-stroke, got deferred, and fired once the stroke ended - the
     * stray refresh. Anything that can see the button before contact should call this.
     */
    fun setStylusButtonEraserMode(enabled: Boolean) {
        setSideButtonEraserMode(enabled)
    }

    fun deactivateEraserMode() {
        val before = isEraseModeActive()
        manualEraserMode = false
        stylusTipEraserMode = false
        val after = isEraseModeActive()
        if (before != after) {
            eraseModeListener?.invoke(after)
            reconfigureTouchHelper()
        }
    }

    fun isEraseModeActive(): Boolean = manualEraserMode || stylusTipEraserMode || sideButtonEraserMode

    fun getViewScale(): Float = viewScale

    fun resetViewport() {
        viewScale = 1f
        viewOffsetX = 0f
        viewOffsetY = 0f
        viewGestureMode = ViewGestureMode.NONE
        setViewportGestureRawSuppressed(false)
        notifyViewportChanged()
        invalidateAndRefreshEpd()
    }

    /**
     * Suppress/restore hardware pen overlay while the user interacts with UI controls.
     */
    /**
     * Regions (in this view's coordinates) the pen chip must ignore - normally the bounds of any
     * floating panel currently covering the canvas.
     *
     * This is what lets a panel stay open without switching the pen off wholesale. Blanket
     * suppression looked equivalent but isn't: a disabled chip delivers no touch-down callback at
     * all and the pen doesn't reach normal touch dispatch either, so the only pen signal left is
     * hover - and a pen dropped straight onto the screen can reach contact before any hover sample
     * arrives, leaving the stroke silently dropped with the panel still up. Excluding the panel's
     * rect instead keeps the chip live everywhere else, so a stroke starting on the canvas is
     * reported immediately (and the Activity closes the panel from that), while taps landing on the
     * panel itself fall through to the normal view hierarchy and work as buttons.
     */
    fun setRawExclusionRects(rects: List<Rect>) {
        if (rects == rawExclusionRects) return
        rawExclusionRects = rects
        applyHandwritingRegionExclusions()
        reconfigureTouchHelper()
    }

    /**
     * Hands the panel areas back from the display controller's handwriting region - the
     * display-side counterpart to the pen exclusions above.
     *
     * These are two separate ownerships and we were only ever reclaiming one. TouchHelper's
     * exclusions stop the *pen* drawing over a panel, but the EPD controller still held those
     * pixels as part of its handwriting region, which is the likeliest reason every repaint aimed
     * at a dismissed panel was accepted and then silently did nothing while a stroke was live.
     */
    private fun applyHandwritingRegionExclusions() {
        runCatching {
            EpdController.setScreenHandWritingRegionExclude(this, rawExclusionRects.toTypedArray())
        }.onFailure {
            Log.w(TAG, "handwriting region exclude failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun setRawInputSuppressed(suppressed: Boolean) {
        rawInputSuppressed = suppressed
        if (suppressed && strokeInProgress) {
            // Disabling raw drawing mid-stroke means onEndRawDrawing never arrives, so the flag
            // would stay set forever and performReconfigureTouchHelper() would defer every
            // reconfigure from then on. Commit what was drawn before letting go of it - simply
            // clearing the flag threw the stroke away, because onEndRawDrawing returns early
            // without it and nothing else ever rasterises the points.
            abandonStrokeKeepingInk("raw input suppressed")
            reconfigureTouchHelper()
        }
        val helper = touchHelper ?: return
        runOnHelperThread {
            runCatching { helper.setRawDrawingEnabled(!(suppressed || viewportGestureSuppressRaw)) }
        }
    }

    fun getLayerInfos(): List<LayerInfo> {
        val activeId = activeLayerId
        return layers.asReversed().map { layer ->
            LayerInfo(
                id = layer.id,
                name = layer.name,
                visible = layer.visible,
                opacity = layer.opacity,
                active = layer.id == activeId,
            )
        }
    }

    fun addLayer(): Int {
        ensureLayerStack(width, height)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return activeLayerId
        val layer = createLayer(
            id = nextLayerId++,
            name = "Layer ${layers.size + 1}",
            w = w,
            h = h,
            visible = true,
            opacity = 1f,
        )
        layers.add(layer)
        activeLayerId = layer.id
        updateSnapshot(layer.id)
        invalidateAndRefreshEpd()
        return layer.id
    }

    fun removeLayer(id: Int): Boolean {
        if (layers.size <= 1) return false
        val idx = layers.indexOfFirst { it.id == id }
        if (idx < 0) return false

        val removed = layers.removeAt(idx)
        recycleLayer(removed)
        dropUndoEntriesForLayer(id)

        if (activeLayerId == id) {
            val nextIdx = idx.coerceAtMost(layers.lastIndex)
            activeLayerId = layers[nextIdx].id
        }

        updateSnapshot(activeLayerId)
        invalidateAndRefreshEpd()
        return true
    }

    fun setActiveLayer(id: Int): Boolean {
        if (layers.none { it.id == id }) return false
        activeLayerId = id
        updateSnapshot(id)
        return true
    }

    /**
     * Reorder layers from top-down display indices.
     */
    fun moveLayerByDisplayIndices(fromDisplayIndex: Int, toDisplayIndex: Int): Boolean {
        val n = layers.size
        if (fromDisplayIndex !in 0 until n || toDisplayIndex !in 0 until n) return false
        val fromInternal = n - 1 - fromDisplayIndex
        val toInternal = n - 1 - toDisplayIndex
        if (fromInternal == toInternal) return true

        val layer = layers.removeAt(fromInternal)
        layers.add(toInternal, layer)
        invalidateAndRefreshEpd()
        return true
    }

    fun setLayerVisible(id: Int, visible: Boolean): Boolean {
        val layer = layerById(id) ?: return false
        if (layer.visible == visible) return true
        layer.visible = visible
        invalidateAndRefreshEpd()
        return true
    }

    fun setLayerOpacity(id: Int, opacity: Float): Boolean {
        val layer = layerById(id) ?: return false
        val v = opacity.coerceIn(0f, 1f)
        if (abs(layer.opacity - v) < 0.0001f) return true
        layer.opacity = v
        invalidateAndRefreshEpd()
        return true
    }

    fun clearCurrentLayer(): Boolean {
        ensureLayerStack(width, height)
        val layer = activeLayer() ?: return false
        clearBitmap(layer.canvas)
        clearBitmap(layer.snapshotCanvas)
        if (strokeLayerId == layer.id) {
            strokePoints.clear()
            rawMovePoints.clear()
            receivedAuthorityList = false
            pendingPenUpRefresh = false
            hasRenderedThisStroke = false
            strokeInProgress = false
        }
        invalidateAndRefreshEpd()
        return true
    }

    fun clearFile() {
        clearUndoStack()
        strokePoints.clear()
        rawMovePoints.clear()
        receivedAuthorityList = false
        pendingPenUpRefresh = false
        hasRenderedThisStroke = false
        strokeInProgress = false
        strokeLayerId = -1

        layers.forEach { recycleLayer(it) }
        layers.clear()
        nextLayerId = 1

        val w = width
        val h = height
        if (w > 0 && h > 0) {
            val base = createLayer(
                id = nextLayerId++,
                name = "Layer 1",
                w = w,
                h = h,
                visible = true,
                opacity = 1f,
            )
            layers.add(base)
            activeLayerId = base.id
            updateSnapshot(base.id)
        } else {
            activeLayerId = -1
        }
        invalidateAndRefreshEpd()
    }

    fun clearCanvas() {
        clearFile()
    }

    /**
     * Loads the bitmap into the currently active layer.
     */
    fun loadCanvasBitmap(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || width <= 0 || height <= 0) return false
        ensureLayerStack(width, height)
        val layer = activeLayer() ?: return false

        clearBitmap(layer.canvas)
        val dst = fitCenterRect(bitmap.width, bitmap.height, width, height)
        layer.canvas.drawBitmap(bitmap, null, dst, null)
        updateSnapshot(layer.id)
        invalidateAndRefreshEpd()
        return true
    }

    /**
     * Snapshot all layers for structured export (bottom -> top).
     */
    fun snapshotDocumentForExport(): DocumentSnapshot? {
        ensureLayerStack(width, height)
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || layers.isEmpty()) return null
        val activeIndex = layers.indexOfFirst { it.id == activeLayerId }.coerceAtLeast(0)
        val snapshots = layers.map { layer ->
            LayerSnapshot(
                name = layer.name,
                visible = layer.visible,
                opacity = layer.opacity,
                bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false),
            )
        }
        return DocumentSnapshot(
            width = w,
            height = h,
            activeLayerIndex = activeIndex,
            layers = snapshots,
        )
    }

    /**
     * Replace the whole file with imported layers (bottom -> top).
     */
    fun replaceFileWithLayers(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceLayers: List<LayerSnapshot>,
        activeLayerIndex: Int,
    ): Boolean {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || sourceLayers.isEmpty()) return false

        clearUndoStack()
        strokePoints.clear()
        rawMovePoints.clear()
        receivedAuthorityList = false
        pendingPenUpRefresh = false
        hasRenderedThisStroke = false
        strokeInProgress = false
        strokeLayerId = -1

        layers.forEach { recycleLayer(it) }
        layers.clear()
        nextLayerId = 1

        val srcW = if (sourceWidth > 0) sourceWidth else sourceLayers.first().bitmap.width
        val srcH = if (sourceHeight > 0) sourceHeight else sourceLayers.first().bitmap.height
        val dst = fitCenterRect(srcW, srcH, w, h)

        sourceLayers.forEachIndexed { index, source ->
            val layer = createLayer(
                id = nextLayerId++,
                name = if (source.name.isBlank()) "Layer ${index + 1}" else source.name,
                w = w,
                h = h,
                visible = source.visible,
                opacity = source.opacity.coerceIn(0f, 1f),
            )
            clearBitmap(layer.canvas)
            layer.canvas.drawBitmap(source.bitmap, null, dst, null)
            clearBitmap(layer.snapshotCanvas)
            layer.snapshotCanvas.drawBitmap(layer.bitmap, 0f, 0f, null)
            layers.add(layer)
        }

        val idx = activeLayerIndex.coerceIn(0, layers.lastIndex)
        activeLayerId = layers[idx].id
        updateSnapshot(activeLayerId)
        invalidateAndRefreshEpd()
        return true
    }

    /** Wipes the document back to a single blank layer - for switching to a brand new canvas. */
    fun resetToBlankDocument() {
        clearUndoStack()
        strokePoints.clear()
        rawMovePoints.clear()
        receivedAuthorityList = false
        pendingPenUpRefresh = false
        hasRenderedThisStroke = false
        strokeInProgress = false
        strokeLayerId = -1

        layers.forEach { recycleLayer(it) }
        layers.clear()
        nextLayerId = 1
        activeLayerId = -1
        ensureLayerStack(width, height)
        invalidateAndRefreshEpd(UpdateMode.GC)
    }

    /**
     * Exports the visible composed result of all layers.
     */
    fun exportBitmap(): Bitmap? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        drawLayers(canvas)
        return out
    }

    // View lifecycle

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureLayerStack(w, h)
        clampViewport()
        notifyViewportChanged()
        ensureTouchHelper()
        reconfigureTouchHelper()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(viewOffsetX, viewOffsetY)
        canvas.scale(viewScale, viewScale)
        drawLayers(canvas)
        canvas.restore()
        // Do NOT call EpdController here. onDraw fires for every invalidate() (pan/pinch drags,
        // layout passes, system redraws) - forcing a hardware e-ink refresh on each one is what
        // caused the constant flicker/hangs. The hardware refresh is instead triggered explicitly,
        // once, from the specific call sites where content actually changed (see
        // invalidateAndRefreshEpd below).
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateStylusTipEraserMode(event)
        updateSideButtonEraserMode(event)
        logStylusMotionEvent(event, "touch")
        dispatchStylusHoverButtonState(event, "touch")
        if (rawInputSuppressed) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> Log.w(TAG, "onTouchEvent DOWN dropped: rawInputSuppressed=true")
                // Terminal events still have to release viewport-gesture state, which lives below
                // this early return in handleViewportGesture(). Suppression can switch on *during*
                // a gesture - pinching past the zoom-out limit opens the canvas overview, which
                // suppresses raw input while two fingers are still down - and then this return
                // swallowed the UP that would have cleared viewportGestureSuppressRaw. It stayed
                // set forever, so setRawDrawingEnabled(!(suppressed || viewportGestureSuppressRaw))
                // kept the pen disabled even after the overview closed: drawing dead for good.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseViewportGesture()
            }
            return false
        }

        // Finger-only stream is reserved for viewport gestures.
        if (!eventHasStylus(event)) {
            handleViewportGesture(event)
            return true
        }

        // Ignore stylus stream while pinch is active.
        if (viewGestureMode == ViewGestureMode.PINCH) return true

        val helper = touchHelper
        val helperResult = helper?.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // `touchHelper?.onTouchEvent(event) == true || isStylus(event)` below always reports
            // "handled" for a real stylus DOWN regardless of what touchHelper actually did with
            // it, which was masking whether touchHelper is null or silently rejecting the event -
            // log the real state directly instead of inferring it from absence of other logs.
            Log.i(TAG, "onTouchEvent DOWN: touchHelper=${if (helper == null) "NULL" else "present"} helperResult=$helperResult")
        }
        return helperResult == true || isStylus(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        updateStylusTipEraserMode(event)
        updateSideButtonEraserMode(event)
        logStylusMotionEvent(event, "generic")
        val handledStylus = dispatchStylusHoverButtonState(event, "generic")
        return handledStylus || super.onGenericMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(reconfigureRunnable)
        val helper = touchHelper
        touchHelper = null
        runOnHelperThread {
            runCatching {
                helper?.setRawDrawingEnabled(false)
                helper?.closeRawDrawing()
            }
        }
        helperThread.shutdown()

        layers.forEach { recycleLayer(it) }
        layers.clear()
        clearUndoStack()

        super.onDetachedFromWindow()
    }

    // Layer management

    private fun ensureLayerStack(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        if (layers.isEmpty()) {
            val base = createLayer(
                id = nextLayerId++,
                name = "Layer 1",
                w = w,
                h = h,
                visible = true,
                opacity = 1f,
            )
            layers.add(base)
            activeLayerId = base.id
            updateSnapshot(base.id)
            return
        }

        if (layers[0].bitmap.width == w && layers[0].bitmap.height == h) {
            if (activeLayerId < 0) activeLayerId = layers.last().id
            return
        }

        // Size changed: recreate while preserving existing pixels.
        val oldLayers = ArrayList(layers)
        layers.clear()
        for (old in oldLayers) {
            val recreated = createLayer(
                id = old.id,
                name = old.name,
                w = w,
                h = h,
                visible = old.visible,
                opacity = old.opacity,
            )
            val dst = fitCenterRect(old.bitmap.width, old.bitmap.height, w, h)
            recreated.canvas.drawBitmap(old.bitmap, null, dst, null)
            clearBitmap(recreated.snapshotCanvas)
            recreated.snapshotCanvas.drawBitmap(recreated.bitmap, 0f, 0f, null)
            layers.add(recreated)
            recycleLayer(old)
        }
        if (layers.none { it.id == activeLayerId }) {
            activeLayerId = layers.last().id
        }
    }

    private fun createLayer(
        id: Int,
        name: String,
        w: Int,
        h: Int,
        visible: Boolean,
        opacity: Float,
    ): LayerState {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val snapCanvas = Canvas(snap)
        clearBitmap(canvas)
        clearBitmap(snapCanvas)
        return LayerState(
            id = id,
            name = name,
            visible = visible,
            opacity = opacity,
            bitmap = bmp,
            canvas = canvas,
            snapshotBitmap = snap,
            snapshotCanvas = snapCanvas,
        )
    }

    private fun recycleLayer(layer: LayerState) {
        if (!layer.bitmap.isRecycled) layer.bitmap.recycle()
        if (!layer.snapshotBitmap.isRecycled) layer.snapshotBitmap.recycle()
    }

    private fun layerById(id: Int): LayerState? = layers.firstOrNull { it.id == id }

    private fun activeLayer(): LayerState? = layerById(activeLayerId)

    // TouchHelper management

    /** Which brush the chip previews with - not necessarily the one we bake with. */
    private fun previewStrokeStyleFor(style: HardwarePenStyle): Int =
        if (texturedPencilPreview && style == HardwarePenStyle.PENCIL) {
            HardwarePenStyle.CHARCOAL.hardwareStrokeStyle
        } else {
            style.hardwareStrokeStyle
        }

    private fun ensureTouchHelper() {
        if (touchHelper != null) return
        // Feature-flag values decoded via javap -constants on the SDK jar - no official docs for
        // what each bit does functionally, this is all empirical, confirmed on a Palma Pro 2:
        //  - FEATURE_ALL_TOUCH_RENDER (3 = FEATURE_APP_TOUCH_RENDER|FEATURE_SF_TOUCH_RENDER):
        //    delivered strokes to RawInputCallback, but also caused the original flicker/OS-freeze
        //    bug (captures ALL pointer types at the SurfaceFlinger level, including plain finger
        //    touches on a touch-first phone, bypassing onTouchEvent()'s finger/stylus routing).
        //  - FEATURE_APP_PEN_TOUCH_RENDER (4) alone, and combined with FEATURE_APP_TOUCH_RENDER
        //    (5): no flicker, but RawInputCallback never fired at all - confirmed via logcat
        //    (touchHelper present, onTouchEvent claims success, but zero onBeginRawDrawing/
        //    render: ever, exported PNGs blank). Ruled out FEATURE_APP_TOUCH_RENDER as relevant.
        //  - FEATURE_SF_TOUCH_RENDER (2) ALONE: the fix. No flicker/freeze, no OS lockup, and
        //    RawInputCallback fires correctly - strokes render and persist. Whatever FEATURE_ALL_
        //    TOUCH_RENDER's freeze issue actually was, it needed FEATURE_APP_TOUCH_RENDER(1)
        //    combined with FEATURE_SF_TOUCH_RENDER(2), not FEATURE_SF_TOUCH_RENDER on its own.
        touchHelper = TouchHelper.create(
            this,
            TouchHelper.FEATURE_SF_TOUCH_RENDER,
            rawInputCallback,
            false
        )
    }

    /**
     * Coalesces bursts of reconfigure requests (e.g. style+width+color set back-to-back at
     * startup, or one call per pixel while dragging the width slider) into a single hardware
     * chip reset. openRawDrawing() resets the pen chip - firing it many times in a fraction of
     * a second is what caused app/OS hangs and lockups.
     */
    private fun reconfigureTouchHelper() {
        removeCallbacks(reconfigureRunnable)
        postDelayed(reconfigureRunnable, RECONFIGURE_DEBOUNCE_MS)
    }

    /** Skips the debounce, for discrete mode flips that must reach the chip before the pen lands
     *  (erase on/off). Still declines to reset the chip mid-stroke - that guard lives inside. */
    private fun reconfigureTouchHelperNow() {
        removeCallbacks(reconfigureRunnable)
        performReconfigureTouchHelper()
    }

    private val reconfigureRunnable = Runnable { performReconfigureTouchHelper() }

    /**
     * Initialise / reconfigure the hardware chip.
     * The order here is critical.
     */
    private fun performReconfigureTouchHelper() {
        val helper = touchHelper ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        // Only a stroke that has gone quiet is treated as orphaned. The previous version gave up
        // after a fixed number of deferrals, which a genuinely long stroke reached while still
        // being drawn - and it then dropped that stroke on the floor.
        if (strokeInProgress && (System.currentTimeMillis() - lastRawPointAtMs) < STROKE_STALE_MS) {
            // openRawDrawing() below fully resets the pen chip. Firing that while the user is
            // mid-stroke (e.g. they changed width/color/brush right before touching down, so the
            // debounced reconfigure from that change lands after the new stroke has already
            // begun) corrupts the in-progress raw session: the hardware keeps previewing with
            // whatever was configured before the reset, and only picks up the real setting once
            // something else forces a repaint - which is exactly "draws thin, then redraws at the
            // right size after a full refresh". Defer instead of resetting mid-stroke; this just
            // re-arms the same debounce, so it fires again the moment the current stroke ends.
            reconfigureTouchHelper()
            return
        }
        abandonStrokeKeepingInk("stroke went stale before reconfigure")
        val style = activeStyle
        val widthPx = activeWidthPx
        val eraseMode = isEraseModeActive()
        val hardwareStyle = if (eraseMode) HardwarePenStyle.PENCIL else style
        val exclusions = ArrayList(rawExclusionRects)

        runOnHelperThread {
            if (touchHelper !== helper) return@runOnHelperThread
            runCatching {
                // 1. Width
                helper.setStrokeWidth(widthPx)
                helper.enableFingerTouch(false)
                helper.onlyEnableFingerTouch(false)
                // Let the firmware own side-button erase. Doing it app-side can't work: the button
                // isn't reported through buttonState (always 'none') and emits no key events, so
                // the first sign of it is TOOL_TYPE_ERASER on the touch-down itself - by which
                // point the chip is already set up to draw ink and can't be reconfigured until the
                // stroke ends. The firmware sees the button directly and previews the erase itself,
                // which is the same reason the eraser *tool* looks right: configured before contact.
                runCatching { helper.enableSideBtnErase(true) }
                runCatching { helper.setEraserRawDrawingEnabled(true) }
                // 2. Preview color
                val hardwareColor = if (eraseMode) {
                    withAlpha(Color.WHITE, 255)
                } else {
                    when (style) {
                        HardwarePenStyle.MARKER -> withAlpha(activeColor, 128)
                        HardwarePenStyle.CHARCOAL -> withAlpha(activeColor, 255)
                        HardwarePenStyle.CHARCOAL_V2 -> withAlpha(activeColor, 255)
                        else -> withAlpha(activeColor, 255)
                    }
                }
                helper.setStrokeColor(hardwareColor)
                // 3. Limits
                helper.setLimitRect(Rect(0, 0, w, h), exclusions)
                // 4. Open (resets chip)
                helper.openRawDrawing()
                // 5. Style after open
                helper.setStrokeStyle(previewStrokeStyleFor(hardwareStyle))
                // Re-apply params after style selection
                helper.setStrokeWidth(widthPx)
                helper.setStrokeColor(hardwareColor)
                // 6. Let the SDK render the live preview with the real brush.
                //
                // This was false, which is what makes the pencil preview a row of plain dabs while
                // the bake shows grain: false leaves the chip drawing a bare stroke rather than the
                // configured brush. The stock Notes app previews a textured pencil, so the hardware
                // is capable of it, and OpenInkBridge's Onyx backend sets this true and calls it
                // "enable hardware E-Ink preview rendering".
                //
                // If the old flicker or freeze ever returns, this is the first thing to put back -
                // but note it is a different knob from the FEATURE_* flags that caused those.
                helper.setRawDrawingRenderEnabled(true)
                Log.i(
                    TAG,
                    "chip configured: style=$hardwareStyle width=$widthPx renderEnabled=" +
                        runCatching { helper.isRawDrawingRenderEnabled }.getOrDefault("?")
                )
                // 7. Enable unless suppressed by UI
                helper.setRawDrawingEnabled(!(rawInputSuppressed || viewportGestureSuppressRaw))
            }
            // openRawDrawing() above resets the chip, which tears down whatever the hardware's
            // own raw-preview overlay was showing. The actual drawing (our layer bitmaps) was
            // never touched, but on e-ink a plain postInvalidate() only redraws the Android-level
            // canvas into memory - the physical panel doesn't repaint until an actual
            // EpdController refresh is issued. This used to fire that refresh immediately (twice,
            // as a timing safety net) whenever the style changed, but with tool presets driving
            // brush selection now, that fires on every tool/brush pick made through the modal -
            // one or two hardware refreshes per pick added up to its own flood. Same fix as the
            // panel-close case: just flag it dirty and let onBeginRawDrawing's lazy check do the
            // one real refresh that actually matters, right before the next stroke.
            postInvalidate()
        }
    }

    // Rendering helpers

    /**
     * What to treat as "full pressure" when re-rendering a finished stroke.
     *
     * The chip previews a stroke at the width it was configured with, but our own re-render feeds
     * the native pen engine a pressure ceiling to normalise against, and the digitizer advertises
     * 4096 - a value real strokes never come near (measured peaks run ~1700-2800). Normalising
     * against it committed every stroke at roughly 60% of the width the preview had just shown:
     * imperceptible at 5px, glaring at 80px, and only noticeable once a refresh replaced the
     * preview with the raster - which is why it read as "changing tools resizes my strokes".
     * Normalising against what the stroke actually reached puts its heaviest point at the
     * configured width, matching the preview. Pressure still shapes the stroke internally; the
     * floor keeps a genuinely feather-light stroke from being inflated to full width.
     */
    /**
     * The pressure ceiling to re-render a stroke against: the device's own reported maximum, which
     * is what the pen chip is configured with, so the committed raster is scaled the same way the
     * preview was.
     *
     * This previously tried a session running maximum, reasoning that real strokes never approach
     * the advertised 4096 so normalising against it renders them too thin. That was the wrong lever
     * - it made the raster disagree with the preview in the other direction, which is charcoal
     * suddenly growing on refresh. Whatever the firmware uses is by definition the right value, and
     * matching it is the only way preview and raster agree; if strokes then read as too thin for
     * the configured width, that belongs in the width or sensitivity, not in a divisor that only
     * this side of the pipeline knows about.
     */
    private fun strokePressureCeiling(@Suppress("UNUSED_PARAMETER") strokeMaxPressure: Float): Float =
        maxPressure()

    /**
     * Draws the in-progress stroke into its layer. Split out of onEndRawDrawing so a stroke that
     * gets interrupted - the chip being reconfigured or raw input suppressed underneath it - can
     * still be committed instead of silently thrown away. [hasRenderedThisStroke] keeps it to once.
     */
    private fun rasteriseCurrentStroke() {
        val renderPts = chooseRenderPoints(strokeStyle)
        val layer = layerById(strokeLayerId)
        if (renderPts.size < 2 || hasRenderedThisStroke || layer == null) return
        hasRenderedThisStroke = true
        val copy = despike(ArrayList(renderPts), strokeWidthPx)
        val source = if (renderPts === rawMovePoints) "rawMove" else "authority"
        val sig = signalOf(renderPts)
        val pressureCeiling = strokePressureCeiling(sig.maxPressure)
        Log.d(
            TAG,
            "render: style=$strokeStyle erase=$strokeIsErase layer=$strokeLayerId source=$source pts=${copy.size} maxP=${sig.maxPressure} tiltNz=${sig.nonZeroTiltCount} " +
                "rasterWidth=$strokeWidthPx chipWidth=$activeWidthPx viewScale=$strokeViewScale ceiling=$pressureCeiling"
        )
        val beforeBitmap = runCatching { layer.bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        runCatching {
            if (strokeIsErase) {
                OnyxStrokeRenderer.erase(strokeStyle, copy, strokeWidthPx, layer.canvas, pressureCeiling)
            } else {
                OnyxStrokeRenderer.render(strokeStyle, copy, strokeWidthPx, strokeColor, layer.canvas, pressureCeiling)
            }
        }.onFailure { e ->
            Log.e(TAG, "render threw: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        if (beforeBitmap != null) pushUndoEntry(strokeLayerId, beforeBitmap)
        updateSnapshot(strokeLayerId)
    }

    /** Ends a stroke that won't get its own onEndRawDrawing, keeping the ink already drawn. */
    private fun abandonStrokeKeepingInk(reason: String) {
        if (!strokeInProgress) return
        Log.w(TAG, "committing interrupted stroke ($reason)")
        rasteriseCurrentStroke()
        strokeInProgress = false
        pendingPenUpRefresh = false
        strokePoints.clear()
        rawMovePoints.clear()
        receivedAuthorityList = false
        invalidateAndRefreshEpd(commitUpdateModeFor(strokeStyle))
    }

    private fun maxPressure(): Float {
        val v = runCatching { EpdController.getMaxTouchPressure() }.getOrDefault(0f)
        return when {
            v > 0f -> v
            EpdController.MAX_TOUCH_PRESSURE > 0f -> EpdController.MAX_TOUCH_PRESSURE
            else -> 4096f
        }
    }

    private fun drawLayers(canvas: Canvas) {
        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            if (layer.opacity >= 0.999f) {
                canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
            } else {
                layerPaint.alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
                canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
            }
        }
        layerPaint.alpha = 255
    }

    private fun updateSnapshot(layerId: Int) {
        val layer = layerById(layerId) ?: return
        clearBitmap(layer.snapshotCanvas)
        layer.snapshotCanvas.drawBitmap(layer.bitmap, 0f, 0f, null)
    }

    private fun rebuildWithCurrentStroke(layerId: Int) {
        val layer = layerById(layerId) ?: return
        clearBitmap(layer.canvas)
        layer.canvas.drawBitmap(layer.snapshotBitmap, 0f, 0f, null)
    }

    private fun invalidateSurface() {
        // postInvalidate(), not invalidate(): some RawInputCallback methods (onPenUpRefresh in
        // particular) can fire off the main thread - MainActivity already defensively wraps other
        // penView listeners in runOnUiThread for the same reason. invalidate() requires the UI
        // thread and throws CalledFromWrongThreadException otherwise; postInvalidate() is the
        // thread-safe equivalent and is just as correct when already on the UI thread.
        postInvalidate()
    }

    /**
     * HAND_WRITING_REPAINT_MODE is a fast, low-gray-level refresh mode meant for realtime ink -
     * fine for binary/near-binary content (Pencil, Dash) but the anti-aliased/textured native
     * brushes (Fountain, Marker, Neo Brush, Charcoal x2, Square Pen) have many gray levels that a
     * fast partial refresh can't fully settle, which shows up as ghosting/streaking artifacts.
     * Those get a fuller-quality mode on pen-up commit instead - slightly slower, much cleaner.
     */
    private fun commitUpdateModeFor(style: HardwarePenStyle): UpdateMode = when (style) {
        HardwarePenStyle.PENCIL, HardwarePenStyle.DASH -> UpdateMode.HAND_WRITING_REPAINT_MODE
        else -> UpdateMode.GC
    }

    /**
     * Use only where content actually changed and the reader must see the swap immediately
     * (pen-up commit, layer edits, load/clear, viewport reset). Posted so it runs after the
     * pending draw pass instead of re-entering it, and coalesced so a burst of calls in the
     * same frame (e.g. several layer ops back-to-back) only reaches the hardware once.
     */
    private fun invalidateAndRefreshEpd(mode: UpdateMode = UpdateMode.HAND_WRITING_REPAINT_MODE) {
        postInvalidate()
        if (epdRefreshScheduled) return
        epdRefreshScheduled = true
        post {
            epdRefreshScheduled = false
            runCatching {
                EpdController.invalidate(this, mode)
                EpdController.refreshScreen(this, mode)
            }
        }
    }

    private fun fitCenterRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): RectF {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return RectF(0f, 0f, dstW.toFloat(), dstH.toFloat())
        }
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val dstAspect = dstW.toFloat() / dstH.toFloat()
        return if (srcAspect > dstAspect) {
            val drawH = dstW / srcAspect
            val top = (dstH - drawH) * 0.5f
            RectF(0f, top, dstW.toFloat(), top + drawH)
        } else {
            val drawW = dstH * srcAspect
            val left = (dstW - drawW) * 0.5f
            RectF(left, 0f, left + drawW, dstH.toFloat())
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun clearBitmap(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    private fun notifyViewportChanged() {
        viewportListener?.invoke(viewScale)
    }

    private fun setViewportGestureRawSuppressed(suppressed: Boolean) {
        if (viewportGestureSuppressRaw == suppressed) return
        viewportGestureSuppressRaw = suppressed
        val helper = touchHelper ?: return
        val shouldEnable = !(rawInputSuppressed || viewportGestureSuppressRaw)
        runOnHelperThread {
            if (touchHelper !== helper) return@runOnHelperThread
            runCatching { helper.setRawDrawingEnabled(shouldEnable) }
        }
    }

    private fun runOnHelperThread(block: () -> Unit) {
        if (Thread.currentThread() === helperWorkerThread) {
            block()
        } else {
            helperThread.execute(block)
        }
    }

    // Viewport gestures

    /** Drops any in-flight pan/pinch state and re-enables raw input. Safe to call at any time. */
    private fun releaseViewportGesture() {
        viewGestureMode = ViewGestureMode.NONE
        pinchTapEligible = false
        setViewportGestureRawSuppressed(false)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun handleViewportGesture(event: MotionEvent): Boolean {
        if (event.pointerCount <= 0) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (viewScale > 1.0001f) {
                    viewGestureMode = ViewGestureMode.PAN
                    setViewportGestureRawSuppressed(true)
                    panLastX = event.getX(0)
                    panLastY = event.getY(0)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                viewGestureMode = ViewGestureMode.NONE
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    beginPinch(event)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (viewGestureMode) {
                    ViewGestureMode.PINCH -> {
                        if (event.pointerCount >= 2) {
                            updatePinch(event)
                            return true
                        }
                    }

                    ViewGestureMode.PAN -> {
                        val x = event.getX(0)
                        val y = event.getY(0)
                        val dx = x - panLastX
                        val dy = y - panLastY
                        panLastX = x
                        panLastY = y
                        viewOffsetX += dx
                        viewOffsetY += dy
                        clampViewport()
                        invalidateSurface()
                        return true
                    }

                    ViewGestureMode.NONE -> Unit
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (viewGestureMode == ViewGestureMode.PINCH) {
                    val remaining = event.pointerCount - 1
                    if (remaining >= 2) {
                        pinchTapEligible = false
                        beginPinchFromRemainingPointers(event, event.actionIndex)
                    } else {
                        maybeFireTwoFingerTap()
                        viewGestureMode = ViewGestureMode.NONE
                        setViewportGestureRawSuppressed(false)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (viewGestureMode != ViewGestureMode.NONE) {
                    viewGestureMode = ViewGestureMode.NONE
                    setViewportGestureRawSuppressed(false)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (viewportGestureSuppressRaw) {
                    setViewportGestureRawSuppressed(false)
                }
            }
        }
        return viewGestureMode != ViewGestureMode.NONE
    }

    private fun beginPinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        val focusX = (x0 + x1) * 0.5f
        val focusY = (y0 + y1) * 0.5f
        pinchStartDistance = max(1f, distance(x0, y0, x1, y1))
        pinchStartScale = viewScale
        pinchAnchorWorldX = (focusX - viewOffsetX) / pinchStartScale
        pinchAnchorWorldY = (focusY - viewOffsetY) / pinchStartScale
        viewGestureMode = ViewGestureMode.PINCH
        overviewPinchFired = false
        setViewportGestureRawSuppressed(true)
        panLastX = focusX
        panLastY = focusY
        parent?.requestDisallowInterceptTouchEvent(true)
        // Two-finger-tap tracking: a clean 2-finger gesture (not one that passed through 3+
        // fingers) that stays short and nearly stationary counts as a tap on release.
        pinchTapEligible = true
        pinchStartTimeMs = System.currentTimeMillis()
        pinchMaxMovementPx = 0f
        pinchP0StartX = x0
        pinchP0StartY = y0
        pinchP1StartX = x1
        pinchP1StartY = y1
    }

    private fun beginPinchFromRemainingPointers(event: MotionEvent, liftedPointerIndex: Int) {
        if (event.pointerCount < 3) {
            viewGestureMode = ViewGestureMode.NONE
            return
        }
        val first = if (liftedPointerIndex == 0) 1 else 0
        val second = if (liftedPointerIndex <= 1) 2 else 1
        val x0 = event.getX(first)
        val y0 = event.getY(first)
        val x1 = event.getX(second)
        val y1 = event.getY(second)
        val focusX = (x0 + x1) * 0.5f
        val focusY = (y0 + y1) * 0.5f
        pinchStartDistance = max(1f, distance(x0, y0, x1, y1))
        pinchStartScale = viewScale
        pinchAnchorWorldX = (focusX - viewOffsetX) / pinchStartScale
        pinchAnchorWorldY = (focusY - viewOffsetY) / pinchStartScale
        viewGestureMode = ViewGestureMode.PINCH
        panLastX = focusX
        panLastY = focusY
    }

    private fun updatePinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        if (pinchTapEligible) {
            val move0 = distance(x0, y0, pinchP0StartX, pinchP0StartY)
            val move1 = distance(x1, y1, pinchP1StartX, pinchP1StartY)
            pinchMaxMovementPx = max(pinchMaxMovementPx, max(move0, move1))
        }
        val focusX = (x0 + x1) * 0.5f
        val focusY = (y0 + y1) * 0.5f
        val dist = max(1f, distance(x0, y0, x1, y1))
        val rawScale = pinchStartScale * (dist / pinchStartDistance)
        val newScale = rawScale.coerceIn(minViewScale, maxViewScale)
        viewScale = newScale
        viewOffsetX = focusX - pinchAnchorWorldX * newScale
        viewOffsetY = focusY - pinchAnchorWorldY * newScale
        clampViewport()
        notifyViewportChanged()
        invalidateSurface()

        // Already at the zoom-out limit (newScale clamped) and still squeezing further inward:
        // treat that overshoot past the limit as "give up on this canvas, show me all of them",
        // the same rubber-band-past-the-edge gesture Notability/Freeform use for their overview.
        if (!overviewPinchFired && rawScale < minViewScale * OVERVIEW_PINCH_EXIT_RATIO) {
            overviewPinchFired = true
            overviewPinchListener?.invoke()
        }
    }

    private fun maybeFireTwoFingerTap() {
        if (!pinchTapEligible) return
        val elapsed = System.currentTimeMillis() - pinchStartTimeMs
        val maxMovementPx = TWO_FINGER_TAP_MAX_MOVEMENT_DP * resources.displayMetrics.density
        if (elapsed in 0..TWO_FINGER_TAP_MAX_DURATION_MS && pinchMaxMovementPx <= maxMovementPx) {
            twoFingerTapListener?.invoke()
        }
        pinchTapEligible = false
    }

    private fun clampViewport() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (viewScale <= 1.0001f) {
            viewScale = 1f
            viewOffsetX = 0f
            viewOffsetY = 0f
            return
        }
        val scaledW = w * viewScale
        val scaledH = h * viewScale
        val minX = w - scaledW
        val minY = h - scaledH
        viewOffsetX = viewOffsetX.coerceIn(minX, 0f)
        viewOffsetY = viewOffsetY.coerceIn(minY, 0f)
    }

    private fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    // Point helpers

    private fun mapStrokePoint(pt: TouchPoint?): TouchPoint? {
        pt ?: return null
        val s = strokeViewScale.coerceAtLeast(0.0001f)
        val x = ((pt.x - strokeViewOffsetX) / s).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        val y = ((pt.y - strokeViewOffsetY) / s).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        return TouchPoint(pt).also {
            it.x = x
            it.y = y
        }
    }

    private fun mapStrokePoints(points: List<TouchPoint>): List<TouchPoint> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<TouchPoint>(points.size)
        for (p in points) {
            val mapped = mapStrokePoint(p)
            if (mapped != null) out.add(mapped)
        }
        return out
    }

    private fun appendPoint(pt: TouchPoint?) {
        pt ?: return
        val copy = TouchPoint(pt)
        val last = strokePoints.lastOrNull()
        if (last != null &&
            abs(last.x - copy.x) < 0.1f &&
            abs(last.y - copy.y) < 0.1f &&
            abs(last.pressure - copy.pressure) < 0.001f &&
            abs(last.size - copy.size) < 0.001f &&
            last.tiltX == copy.tiltX &&
            last.tiltY == copy.tiltY
        ) return
        strokePoints.add(copy)
    }

    private fun appendRawMovePoint(pt: TouchPoint?) {
        pt ?: return
        val copy = TouchPoint(pt)
        val last = rawMovePoints.lastOrNull()
        if (last != null &&
            abs(last.x - copy.x) < 0.1f &&
            abs(last.y - copy.y) < 0.1f &&
            abs(last.pressure - copy.pressure) < 0.001f &&
            abs(last.size - copy.size) < 0.001f &&
            last.tiltX == copy.tiltX &&
            last.tiltY == copy.tiltY
        ) return
        rawMovePoints.add(copy)
    }

    private data class PointSignal(
        val maxPressure: Float,
        val nonZeroTiltCount: Int,
    )

    private fun signalOf(points: List<TouchPoint>): PointSignal {
        var maxP = 0f
        var tiltNz = 0
        for (p in points) {
            if (p.pressure > maxP) maxP = p.pressure
            if (p.tiltX != 0 || p.tiltY != 0) tiltNz++
        }
        return PointSignal(maxP, tiltNz)
    }

    private fun chooseRenderPoints(style: HardwarePenStyle): List<TouchPoint> {
        if (strokePoints.size < 2) return rawMovePoints
        if (style != HardwarePenStyle.CHARCOAL && style != HardwarePenStyle.CHARCOAL_V2) {
            return strokePoints
        }
        if (rawMovePoints.size < 2) return strokePoints

        val authority = signalOf(strokePoints)
        val raw = signalOf(rawMovePoints)
        val hasRicherTilt = raw.nonZeroTiltCount > authority.nonZeroTiltCount
        val hasRicherPressure = raw.maxPressure > 1.5f && authority.maxPressure <= 1.05f
        return if (hasRicherTilt || hasRicherPressure) rawMovePoints else strokePoints
    }

    /**
     * Reconcile with authoritative list from hardware.
     * If it diverges, replace and rebuild current stroke layer from snapshot.
     */
    private fun mergeAuthorityList(pts: List<TouchPoint>) {
        val common = minOf(strokePoints.size, pts.size)
        var matchLen = 0
        while (matchLen < common) {
            val a = strokePoints[matchLen]
            val b = pts[matchLen]
            if (abs(a.x - b.x) > 0.35f || abs(a.y - b.y) > 0.35f) break
            matchLen++
        }

        if (matchLen == strokePoints.size && matchLen == pts.size) return

        if (matchLen == strokePoints.size) {
            // Authority list agrees with everything we have so far and adds more - just extend.
            for (i in matchLen until pts.size) appendPoint(pts[i])
            return
        }

        // Authority disagrees somewhere before the end of what we've drawn. Previously this
        // discarded the WHOLE stroke and replaced it with just this batch - if the batch isn't a
        // full from-the-start replay (or simply uses different smoothing than our live
        // accumulation), the rendered path would visibly jump/snap from wherever the discarded
        // points ended to wherever the new batch starts. That's the "sudden sharp line" glitch:
        // fast strokes produce more samples per callback, so the odds of some single point
        // differing by >0.35px somewhere in a long prefix (easily crossed by ordinary smoothing
        // differences) go up, and every such divergence used to blow away the whole stroke.
        // Instead, keep the prefix we already agree on and only replace the diverging tail, so
        // the visible path stays a continuous line through the point where correction begins.
        while (strokePoints.size > matchLen) strokePoints.removeAt(strokePoints.size - 1)
        for (i in matchLen until pts.size) appendPoint(pts[i])
        rebuildWithCurrentStroke(strokeLayerId)
    }

    /**
     * Drops single spurious points from the final render list before any style-specific
     * rendering runs. Confirmed (all styles, including Pencil's plain polyline with zero native
     * code) to show sudden sharp perpendicular spikes on fast strokes, baked into the saved
     * stroke - meaning one raw sample from the digitizer occasionally reports a wildly wrong
     * coordinate and the very next sample snaps back to the real trajectory. No renderer here
     * does outlier rejection, so that one bad sample becomes a visible "there and back" jump.
     * Detected by routing cost: if going prev -> point -> next is much longer than going straight
     * prev -> next, point is almost certainly a glitch, not an intentional sharp corner (a real
     * sharp corner doesn't roughly double the local path length AND require snapping back).
     */
    private fun despike(points: ArrayList<TouchPoint>, widthPx: Float): ArrayList<TouchPoint> {
        if (points.size < 3) return points
        val minSpikePx = max(6f, widthPx)
        val out = ArrayList<TouchPoint>(points.size)
        out.add(points[0])
        var i = 1
        while (i < points.size - 1) {
            val prev = out.last()
            val curr = points[i]
            val next = points[i + 1]
            val toCurr = distance(prev.x, prev.y, curr.x, curr.y)
            val toNext = distance(curr.x, curr.y, next.x, next.y)
            val direct = distance(prev.x, prev.y, next.x, next.y)
            val detour = toCurr + toNext
            val isSpike = direct > 0.5f &&
                detour > direct * 2.2f &&
                (toCurr > minSpikePx || toNext > minSpikePx)
            if (!isSpike) out.add(curr)
            i++
        }
        out.add(points.last())
        return out
    }

    private fun isStylus(event: MotionEvent): Boolean {
        if (event.pointerCount <= 0) return false
        val idx = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        return event.getToolType(idx) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
    }

    private fun eventHasStylus(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            val tool = event.getToolType(i)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                return true
            }
        }
        return false
    }

    private fun eventHasEraser(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) return true
        }
        return false
    }

    private fun updateStylusTipEraserMode(event: MotionEvent) {
        val hasStylusSource = (event.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        val hasStylusPointer = eventHasStylus(event)
        if (!hasStylusSource && !hasStylusPointer) return

        val shouldEnable = eventHasEraser(event)
        setStylusTipEraserMode(shouldEnable)
    }

    /**
     * Holding the stylus's side button acts as a temporary eraser, same idea as flipping to the
     * eraser tip. Read directly from MotionEvent.buttonState (not the hover-button dispatch
     * below) because hover tracking deliberately zeroes out once the pen is actually touching -
     * this needs to work while a stroke is in progress, not just while hovering. Not certain which
     * physical button Boox's stylus reports as - primary and secondary are both treated as the
     * erase button so either works; narrow this to one if it turns out to be too sensitive (e.g.
     * if one of the two is also used for something else).
     */
    private fun updateSideButtonEraserMode(event: MotionEvent) {
        val hasStylusSource = (event.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        val hasStylusPointer = eventHasStylus(event)
        if (!hasStylusSource && !hasStylusPointer) return

        val pressed = (event.buttonState and
            (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
        setSideButtonEraserMode(pressed)
    }

    private fun setSideButtonEraserMode(enabled: Boolean) {
        if (sideButtonEraserMode == enabled) return
        val before = isEraseModeActive()
        sideButtonEraserMode = enabled
        val after = isEraseModeActive()
        if (before != after) {
            eraseModeListener?.invoke(after)
            // Immediate, not the 32ms debounce: the button is usually pressed as the pen is already
            // coming down, and a debounced reconfigure that lands after touch-down gets deferred to
            // avoid resetting the chip mid-stroke. The chip therefore kept previewing ink while the
            // app erased for real at pen-up - erasing worked, but with no eraser preview under it.
            reconfigureTouchHelperNow()
        }
    }

    private fun setStylusTipEraserMode(enabled: Boolean, reconfigure: Boolean = true) {
        val before = isEraseModeActive()
        val tipChanged = stylusTipEraserMode != enabled
        if (!tipChanged) return
        if (!enabled) {
            // Per app spec: flipping from eraser tip to stylus tip always exits eraser mode.
            manualEraserMode = false
        }
        stylusTipEraserMode = enabled
        val after = isEraseModeActive()
        if (before != after) {
            eraseModeListener?.invoke(after)
            if (reconfigure) reconfigureTouchHelper()
        } else if (reconfigure) {
            reconfigureTouchHelper()
        }
    }

    private fun logStylusMotionEvent(event: MotionEvent, channel: String) {
        val hasStylusSource = (event.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        val hasStylusPointer = eventHasStylus(event)
        if (!hasStylusSource && !hasStylusPointer) return

        val action = event.actionMasked
        val buttonState = event.buttonState
        val isImportantAction = when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> true
            else -> false
        }
        val buttonChanged = buttonState != lastStylusButtonState
        if (!isImportantAction && !buttonChanged) return

        val pointerCount = event.pointerCount
        val idx = if (pointerCount > 0) event.actionIndex.coerceIn(0, pointerCount - 1) else -1
        val distance = if (idx >= 0) event.getAxisValue(MotionEvent.AXIS_DISTANCE, idx) else event.getAxisValue(MotionEvent.AXIS_DISTANCE)
        val tilt = if (idx >= 0) event.getAxisValue(MotionEvent.AXIS_TILT, idx) else event.getAxisValue(MotionEvent.AXIS_TILT)
        val orientation = if (idx >= 0) event.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx) else event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
        val pointerSummary = if (pointerCount <= 0) {
            "none"
        } else {
            buildString {
                for (i in 0 until pointerCount) {
                    if (i > 0) append("; ")
                    append("#")
                    append(event.getPointerId(i))
                    append(":")
                    append(toolTypeName(event.getToolType(i)))
                    append("@")
                    append(event.getX(i).toInt())
                    append(",")
                    append(event.getY(i).toInt())
                    append(" p=")
                    append("%.3f".format(event.getPressure(i)))
                }
            }
        }

        Log.i(
            TAG,
            "stylus[$channel] action=${actionName(action)} idx=$idx source=0x${event.source.toString(16)} " +
                "buttons=${buttonStateName(buttonState)} dist=${"%.3f".format(distance)} " +
                "tilt=${"%.3f".format(tilt)} orient=${"%.3f".format(orientation)} pointers=$pointerSummary"
        )
        lastStylusButtonState = buttonState
    }

    private fun dispatchStylusHoverButtonState(event: MotionEvent, channel: String): Boolean {
        val hasStylusSource = (event.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        val hasStylusPointer = eventHasStylus(event)
        if (!hasStylusSource && !hasStylusPointer) return false

        val action = event.actionMasked
        val idx = if (event.pointerCount > 0) event.actionIndex.coerceIn(0, event.pointerCount - 1) else -1
        val distance = if (idx >= 0) {
            event.getAxisValue(MotionEvent.AXIS_DISTANCE, idx)
        } else {
            event.getAxisValue(MotionEvent.AXIS_DISTANCE)
        }

        when (action) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            -> stylusHovering = true

            MotionEvent.ACTION_HOVER_EXIT -> stylusHovering = false

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
            -> {
                if (distance > 0f) stylusHovering = true
            }

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL,
            -> stylusHovering = false
        }

        val hoverButtons = if (stylusHovering) (event.buttonState and HOVER_BUTTON_MASK) else 0
        val hoveringChanged = stylusHovering != lastHoveringState
        val buttonsChanged = hoverButtons != lastHoverButtonState
        if (!hoveringChanged && !buttonsChanged) return true

        lastHoveringState = stylusHovering
        lastHoverButtonState = hoverButtons
        val state = StylusHoverButtonState(
            hovering = stylusHovering,
            buttonState = hoverButtons,
            stylusPrimaryPressed = (hoverButtons and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (hoverButtons and MotionEvent.BUTTON_SECONDARY) != 0,
            stylusSecondaryPressed = (hoverButtons and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
                (hoverButtons and MotionEvent.BUTTON_TERTIARY) != 0,
        )
        stylusHoverButtonListener?.invoke(state)
        Log.i(
            TAG,
            "stylusHover[$channel] action=${actionName(action)} hovering=${state.hovering} " +
                "buttons=${buttonStateName(state.buttonState)} dist=${"%.3f".format(distance)}"
        )
        return true
    }

    private fun logRawPoint(kind: String, pt: TouchPoint?) {
        pt ?: return
        val toolType = readTouchPointInt(pt, "getToolType")
        val action = readTouchPointInt(pt, "getAction")
        val toolChanged = toolType != null && toolType != lastRawToolType
        if (kind == "move" && !toolChanged) return
        Log.i(
            TAG,
            "stylusRaw[$kind] action=${action?.let(::actionName) ?: "n/a"} " +
                "tool=${toolType?.let(::toolTypeName) ?: "n/a"} " +
                "x=${"%.1f".format(pt.x)} y=${"%.1f".format(pt.y)} p=${"%.3f".format(pt.pressure)} " +
                "tiltX=${pt.tiltX} tiltY=${pt.tiltY}"
        )
        if (toolType != null) {
            lastRawToolType = toolType
        }
    }

    private fun readTouchPointInt(point: TouchPoint, getterName: String): Int? {
        return runCatching {
            val method = point.javaClass.getMethod(getterName)
            method.invoke(point) as? Int
        }.getOrNull()
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_SCROLL -> "SCROLL"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
        MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
        else -> "ACTION_$action"
    }

    private fun toolTypeName(toolType: Int): String = when (toolType) {
        MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        else -> "TOOL_$toolType"
    }

    private fun buttonStateName(buttonState: Int): String {
        if (buttonState == 0) return "none(0x00000000)"
        val names = ArrayList<String>(6)
        if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0) names.add("PRIMARY")
        if ((buttonState and MotionEvent.BUTTON_SECONDARY) != 0) names.add("SECONDARY")
        if ((buttonState and MotionEvent.BUTTON_TERTIARY) != 0) names.add("TERTIARY")
        if ((buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) names.add("STYLUS_PRIMARY")
        if ((buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) names.add("STYLUS_SECONDARY")
        if ((buttonState and MotionEvent.BUTTON_BACK) != 0) names.add("BACK")
        if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0) names.add("FORWARD")
        return names.joinToString("|") + "(0x" + buttonState.toUInt().toString(16).padStart(8, '0') + ")"
    }

    // RawInputCallback

    private val rawInputCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(success: Boolean, pt: TouchPoint?) {
            ensureLayerStack(width, height)
            logRawPoint("begin", pt)
            val rawToolType = pt?.let { readTouchPointInt(it, "getToolType") }
            // reconfigure = false: the chip cannot be reset mid-stroke anyway, so a reconfigure
            // scheduled from here only lands after the stroke has finished and shows up as a stray
            // refresh. The mode flip itself still counts - strokeIsErase below reads it - and the
            // next idle-time change reconfigures properly.
            when (rawToolType) {
                MotionEvent.TOOL_TYPE_ERASER -> setStylusTipEraserMode(true, reconfigure = false)
                MotionEvent.TOOL_TYPE_STYLUS -> setStylusTipEraserMode(false, reconfigure = false)
                // Some firmware does not expose raw tool type; keep the mode from MotionEvent stream.
                else -> Unit
            }
            strokeIsErase = isEraseModeActive()

            if (strokeInProgress) {
                // Some firmware versions emit duplicate begin events inside one gesture.
                appendPoint(mapStrokePoint(pt))
                appendRawMovePoint(mapStrokePoint(pt))
                Log.d(TAG, "duplicate onBeginRawDrawing style=$activeStyle")
                return
            }

            strokeInProgress = true
            lastRawPointAtMs = System.currentTimeMillis()
            strokePoints.clear()
            rawMovePoints.clear()
            strokeStyle = activeStyle
            strokeViewScale = viewScale
            strokeViewOffsetX = viewOffsetX
            strokeViewOffsetY = viewOffsetY
            strokeWidthPx = (activeWidthPx / strokeViewScale.coerceAtLeast(1f)).coerceAtLeast(0.5f)
            strokeColor = if (strokeIsErase) Color.WHITE else activeColor
            strokeLayerId = activeLayerId
            receivedAuthorityList = false
            pendingPenUpRefresh = false
            hasRenderedThisStroke = false
            appendPoint(mapStrokePoint(pt))
            appendRawMovePoint(mapStrokePoint(pt))
            Log.d(TAG, "onBeginRawDrawing style=$activeStyle width=$activeWidthPx layer=$strokeLayerId")

            // Deliberately after strokeInProgress is set: the Activity checks that to decide
            // whether it can refresh the screen now or has to wait for pen-up (the pen chip owns
            // the display mid-stroke), and reporting this first would make it refresh underneath
            // the stroke that is just starting.
            if (pt != null && stylusPointerListener != null) {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                stylusPointerListener?.invoke(loc[0] + pt.x, loc[1] + pt.y)
            }
        }

        /**
         * Non-abstract SDK hook, so it has simply never been overridden here. If it fires before
         * onBeginRawDrawing it is the missing piece for clearing a dismissed panel: the one thing
         * that reliably repaints is dropping out of the raw session, which is impossible once a
         * stroke owns the chip - but perfectly possible just before one starts. Logged rather than
         * acted on until we know whether it means "pen approaching" or "pen down".
         */
        override fun onPenActive(pt: TouchPoint?) {
            Log.i(
                TAG,
                "onPenActive at=${pt?.x},${pt?.y} strokeInProgress=$strokeInProgress " +
                    "sinceLastRawPoint=${System.currentTimeMillis() - lastRawPointAtMs}ms"
            )
        }

        override fun onRawDrawingTouchPointMoveReceived(pt: TouchPoint?) {
            logRawPoint("move", pt)
            lastRawPointAtMs = System.currentTimeMillis()
            appendPoint(mapStrokePoint(pt))
            appendRawMovePoint(mapStrokePoint(pt))
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            lastRawPointAtMs = System.currentTimeMillis()
            val pts = list?.points ?: return
            if (pts.isEmpty()) return
            mergeAuthorityList(mapStrokePoints(pts))
            receivedAuthorityList = true
        }

        override fun onEndRawDrawing(success: Boolean, pt: TouchPoint?) {
            logRawPoint("end", pt)
            if (!strokeInProgress) return
            strokeInProgress = false

            if (!receivedAuthorityList) {
                appendPoint(mapStrokePoint(pt))
            }
            appendRawMovePoint(mapStrokePoint(pt))

            rasteriseCurrentStroke()

            strokePoints.clear()
            rawMovePoints.clear()
            receivedAuthorityList = false
            pendingPenUpRefresh = true

            // Fallback refresh if onPenUpRefresh does not fire in time.
            postDelayed({
                if (pendingPenUpRefresh) {
                    pendingPenUpRefresh = false
                    invalidateAndRefreshEpd(commitUpdateModeFor(strokeStyle))
                    strokeFinishedListener?.invoke()
                }
            }, 120L)
        }

        override fun onPenUpRefresh(rectF: RectF?) {
            if (!pendingPenUpRefresh) return
            pendingPenUpRefresh = false
            invalidateAndRefreshEpd(commitUpdateModeFor(strokeStyle))
            strokeFinishedListener?.invoke()
        }

        override fun onBeginRawErasing(success: Boolean, pt: TouchPoint?) {
            setStylusTipEraserMode(true, reconfigure = true)
            onBeginRawDrawing(success, pt)
            strokeIsErase = true
        }

        override fun onEndRawErasing(success: Boolean, pt: TouchPoint?) {
            strokeIsErase = true
            onEndRawDrawing(success, pt)
        }

        override fun onRawErasingTouchPointMoveReceived(pt: TouchPoint?) {
            onRawDrawingTouchPointMoveReceived(pt)
        }

        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            onRawDrawingTouchPointListReceived(list)
        }
    }
}
