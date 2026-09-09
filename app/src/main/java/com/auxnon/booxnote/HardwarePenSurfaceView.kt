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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Full-screen drawing surface backed by the Onyx hardware pen chip.
 *
 * Architecture:
 *  - TouchHelper drives zero-latency hardware preview (setRawDrawingRenderEnabled=false).
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
    private var lastConfiguredHardwareStyle: HardwarePenStyle? = null
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

    fun isEraseModeActive(): Boolean = manualEraserMode || stylusTipEraserMode

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
    fun setRawInputSuppressed(suppressed: Boolean) {
        rawInputSuppressed = suppressed
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
        logStylusMotionEvent(event, "touch")
        dispatchStylusHoverButtonState(event, "touch")
        if (rawInputSuppressed) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                Log.w(TAG, "onTouchEvent DOWN dropped: rawInputSuppressed=true")
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
        val style = activeStyle
        val widthPx = activeWidthPx
        val eraseMode = isEraseModeActive()
        val hardwareStyle = if (eraseMode) HardwarePenStyle.PENCIL else style

        runOnHelperThread {
            if (touchHelper !== helper) return@runOnHelperThread
            runCatching {
                // 1. Width
                helper.setStrokeWidth(widthPx)
                helper.enableFingerTouch(false)
                helper.onlyEnableFingerTouch(false)
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
                helper.setLimitRect(Rect(0, 0, w, h), emptyList())
                // 4. Open (resets chip)
                helper.openRawDrawing()
                // 5. Style after open
                helper.setStrokeStyle(hardwareStyle.hardwareStrokeStyle)
                // Re-apply params after style selection
                helper.setStrokeWidth(widthPx)
                helper.setStrokeColor(hardwareColor)
                // 6. Hardware draws live preview
                helper.setRawDrawingRenderEnabled(false)
                // 7. Enable unless suppressed by UI
                helper.setRawDrawingEnabled(!(rawInputSuppressed || viewportGestureSuppressRaw))
            }
            // openRawDrawing() above resets the chip, which tears down whatever the hardware's
            // own raw-preview overlay was showing. The actual drawing (our layer bitmaps) was
            // never touched, but on e-ink a plain postInvalidate() only redraws the Android-level
            // canvas into memory - the physical panel doesn't repaint until an actual
            // EpdController refresh is issued, so that alone left the panel showing nothing until
            // the next stroke's pen-up refresh. Only do the real hardware refresh when the
            // configured style actually changed (a real brush/eraser switch), not on every
            // reconfigure - width/color tweaks (e.g. dragging the width slider) reconfigure once
            // per debounce tick and forcing a hardware refresh there would reintroduce flicker.
            if (lastConfiguredHardwareStyle != hardwareStyle) {
                lastConfiguredHardwareStyle = hardwareStyle
                val mode = commitUpdateModeFor(hardwareStyle)
                post {
                    invalidateAndRefreshEpd(mode)
                    // e-ink refreshes are asynchronous at the hardware level, and openRawDrawing()
                    // resetting the chip may clear its drawing region on its own delayed timeline -
                    // if that clear lands after our refresh above already completed, it visually
                    // stomps it a moment later. Repeat the refresh once more shortly after as a
                    // safety net, same pattern as the existing pen-up fallback refresh.
                    postDelayed({ invalidateAndRefreshEpd(mode) }, 180L)
                }
            } else {
                postInvalidate()
            }
        }
    }

    // Rendering helpers

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
        val newScale = (pinchStartScale * (dist / pinchStartDistance)).coerceIn(minViewScale, maxViewScale)
        viewScale = newScale
        viewOffsetX = focusX - pinchAnchorWorldX * newScale
        viewOffsetY = focusY - pinchAnchorWorldY * newScale
        clampViewport()
        notifyViewportChanged()
        invalidateSurface()
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
            when (rawToolType) {
                MotionEvent.TOOL_TYPE_ERASER -> setStylusTipEraserMode(true, reconfigure = true)
                MotionEvent.TOOL_TYPE_STYLUS -> setStylusTipEraserMode(false, reconfigure = true)
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
        }

        override fun onRawDrawingTouchPointMoveReceived(pt: TouchPoint?) {
            logRawPoint("move", pt)
            appendPoint(mapStrokePoint(pt))
            appendRawMovePoint(mapStrokePoint(pt))
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
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

            val renderPts = chooseRenderPoints(strokeStyle)
            val layer = layerById(strokeLayerId)
            if (renderPts.size >= 2 && !hasRenderedThisStroke && layer != null) {
                hasRenderedThisStroke = true
                val copy = despike(ArrayList(renderPts), strokeWidthPx)
                val source = if (renderPts === rawMovePoints) "rawMove" else "authority"
                val sig = signalOf(renderPts)
                Log.d(
                    TAG,
                    "render: style=$strokeStyle erase=$strokeIsErase layer=$strokeLayerId source=$source pts=${copy.size} maxP=${sig.maxPressure} tiltNz=${sig.nonZeroTiltCount}"
                )
                val beforeBitmap = runCatching { layer.bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                runCatching {
                    if (strokeIsErase) {
                        OnyxStrokeRenderer.erase(strokeStyle, copy, strokeWidthPx, layer.canvas, maxPressure())
                    } else {
                        OnyxStrokeRenderer.render(strokeStyle, copy, strokeWidthPx, strokeColor, layer.canvas, maxPressure())
                    }
                }.onFailure { e ->
                    Log.e(TAG, "render threw: ${e.javaClass.simpleName}: ${e.message}", e)
                }
                if (beforeBitmap != null) pushUndoEntry(strokeLayerId, beforeBitmap)
                updateSnapshot(strokeLayerId)
            }

            strokePoints.clear()
            rawMovePoints.clear()
            receivedAuthorityList = false
            pendingPenUpRefresh = true

            // Fallback refresh if onPenUpRefresh does not fire in time.
            postDelayed({
                if (pendingPenUpRefresh) {
                    pendingPenUpRefresh = false
                    invalidateAndRefreshEpd(commitUpdateModeFor(strokeStyle))
                }
            }, 120L)
        }

        override fun onPenUpRefresh(rectF: RectF?) {
            if (!pendingPenUpRefresh) return
            pendingPenUpRefresh = false
            invalidateAndRefreshEpd(commitUpdateModeFor(strokeStyle))
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
