package com.auxnon.booxnote

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    /** One independently-configured pen: brush type, ink color and stroke width. */
    private data class ToolPreset(
        var style: HardwarePenStyle,
        var color: Int,
        var widthPx: Float,
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "boox_note_prefs"
        private const val KEY_LAST_OPEN_URI = "last_open_uri"
        private const val UI_TOUCH_WATCHDOG_MS = 4000L
        private const val TOOL_SLOT_COUNT = 3
        private const val TOOLBAR_ANIM_DURATION_MS = 220L
        private const val TOOLBAR_TAP_MAX_MOVEMENT_DP = 12f
        private const val TOOLBAR_TAP_MAX_DURATION_MS = 300L
        private const val TOOLBAR_EDGE_MARGIN_DP = 8f
        private const val POPUP_MARGIN_DP = 10f
    }

    private lateinit var penView: HardwarePenSurfaceView
    private lateinit var rootFrame: View
    private lateinit var toolbarPill: View
    private lateinit var toolbarHandle: ImageButton
    private lateinit var toolbarContentGroup: View
    private lateinit var toolSlotButtons: List<ImageButton>
    private lateinit var zoomValueLabel: TextView
    private lateinit var toolModalPanel: View
    private lateinit var modalColorButton: ImageButton
    private lateinit var modalSizeButton: TextView
    private lateinit var modalColorSection: View
    private lateinit var modalSizeSection: View
    private lateinit var modalColorPickerView: CircularColorPickerView
    private lateinit var modalColorHexValue: TextView
    private lateinit var modalSizeSeekBar: SeekBar
    private lateinit var modalSizeValueLabel: TextView
    private lateinit var layerPanel: View
    private lateinit var layerDragHandle: View
    private lateinit var layerRecycler: RecyclerView
    private lateinit var fileMenuPanel: View
    private lateinit var buttonLayers: ImageButton
    private lateinit var buttonMenu: ImageButton
    private lateinit var buttonEraser: ImageButton
    private lateinit var buttonAddLayer: ImageButton
    private lateinit var buttonRemoveLayer: ImageButton
    private lateinit var layerAdapter: LayerListAdapter
    private val brushGridButtons = LinkedHashMap<HardwarePenStyle, ImageButton>(HardwarePenStyle.entries.size)

    /**
     * Handheld e-ink panels (e.g. Boox Palma Pro 2, ~6.1") are physically small but often very
     * high density, so dp-based "is this a phone" checks are unreliable - a Palma's smallest
     * width in dp is actually larger than the sw600dp tablet threshold. Physical diagonal size
     * is a more reliable signal, and it's what actually matters here: a full-screen GU flash is
     * proportionally far more disruptive on a small handheld panel than on a 10"+ tablet like the
     * Note Air/Go "4C" line, so handheld panels get a lighter/faster toolbar refresh mode.
     */
    private val isHandheldEinkPanel: Boolean by lazy {
        val dm = resources.displayMetrics
        if (dm.xdpi <= 0f || dm.ydpi <= 0f) {
            false
        } else {
            val wIn = dm.widthPixels / dm.xdpi
            val hIn = dm.heightPixels / dm.ydpi
            sqrt((wIn * wIn + hIn * hIn).toDouble()) < 7.5
        }
    }

    /**
     * Three independently-configured pens ("tool groups"), each remembering its own brush type,
     * color and width. Defaults spread across a few common styles so the three slots aren't
     * identical out of the box.
     */
    private val toolPresets = arrayOf(
        ToolPreset(HardwarePenStyle.PENCIL, Color.BLACK, HardwarePenStyle.PENCIL.defaultWidthPx),
        ToolPreset(HardwarePenStyle.FOUNTAIN, Color.BLACK, HardwarePenStyle.FOUNTAIN.defaultWidthPx),
        ToolPreset(HardwarePenStyle.MARKER, Color.BLACK, HardwarePenStyle.MARKER.defaultWidthPx),
    )
    private var selectedToolIndex: Int = 0

    // Floating toolbar: drag-to-move, tap-handle-to-minimize/expand, snap-to-edge on release.
    private var toolbarMinimized = false
    private var toolbarExpandedWidth = 0
    private var toolbarExpandedHeight = 0
    private var toolbarCollapsedWidth = 0
    private var toolbarCollapsedHeight = 0
    private var toolbarDragStartRawX = 0f
    private var toolbarDragStartRawY = 0f
    private var toolbarDragStartViewX = 0f
    private var toolbarDragStartViewY = 0f
    private var toolbarDragMaxMovement = 0f
    private var toolbarDragStartTimeMs = 0L
    private var uiEinkRefreshScheduled = false

    private var pickerInFlight: Boolean = false
    private var activityPaused: Boolean = false
    private var uiTouchDepth: Int = 0
    private var aboutDialogVisible: Boolean = false
    private var layerDragDx: Float = 0f
    private var layerDragDy: Float = 0f
    private var currentDocumentBaseName: String = "drawing"
    private var manualEraserMode: Boolean = false
    private var eraserWasActive: Boolean = false
    private var eraserUiTransitionInFlight: Boolean = false
    private var pendingEraserUiTransitionReset: Runnable? = null
    private var pendingIncomingViewUri: Uri? = null
    private var pendingIncomingViewFlags: Int = 0
    private var pendingIncomingViewAttempts: Int = 0
    private val openMimeTypes = arrayOf("image/*", "application/json", "text/plain", "application/octet-stream")

    private val savePngLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { savePng(it) }
        pickerInFlight = false
        updateRawSuppression()
    }

    private val saveDpaintLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { saveDpaint(it) }
        pickerInFlight = false
        updateRawSuppression()
    }

    private val loadDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            val flags = result.data?.flags ?: 0
            val readFlags = flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            runCatching { contentResolver.takePersistableUriPermission(uri, readFlags) }
            rememberLastOpenUri(uri)
            loadDocument(uri)
        }
        pickerInFlight = false
        updateRawSuppression()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootFrame = findViewById(R.id.rootFrame)
        toolbarPill = findViewById(R.id.toolbarPill)
        toolbarHandle = findViewById(R.id.toolbarHandle)
        toolbarContentGroup = findViewById(R.id.toolbarContentGroup)
        penView = findViewById(R.id.penSurfaceView)
        toolSlotButtons = listOf(
            findViewById(R.id.toolSlot0),
            findViewById(R.id.toolSlot1),
            findViewById(R.id.toolSlot2),
        )
        zoomValueLabel = findViewById(R.id.zoomValueLabel)
        toolModalPanel = findViewById(R.id.toolModalPanel)
        modalColorButton = findViewById(R.id.modalColorButton)
        modalSizeButton = findViewById(R.id.modalSizeButton)
        modalColorSection = findViewById(R.id.modalColorSection)
        modalSizeSection = findViewById(R.id.modalSizeSection)
        modalColorPickerView = findViewById(R.id.modalColorPickerView)
        modalColorHexValue = findViewById(R.id.modalColorHexValue)
        modalSizeSeekBar = findViewById(R.id.modalSizeSeekBar)
        modalSizeValueLabel = findViewById(R.id.modalSizeValueLabel)
        layerPanel = findViewById(R.id.layerPanel)
        layerDragHandle = findViewById(R.id.layerDragHandle)
        layerRecycler = findViewById(R.id.layerRecycler)
        fileMenuPanel = findViewById(R.id.fileMenuPanel)
        buttonLayers = findViewById(R.id.buttonLayers)
        buttonMenu = findViewById(R.id.buttonMenu)
        buttonEraser = findViewById(R.id.buttonEraser)
        buttonAddLayer = findViewById(R.id.buttonAddLayer)
        buttonRemoveLayer = findViewById(R.id.buttonRemoveLayer)

        val loadBtn = findViewById<View>(R.id.buttonLoad)
        val clearLayerBtn = findViewById<View>(R.id.buttonClearLayer)
        val clearFileBtn = findViewById<View>(R.id.buttonClearFile)
        val saveBtn = findViewById<View>(R.id.buttonSave)
        val saveFileBtn = findViewById<View>(R.id.buttonSaveFile)
        val shareBtn = findViewById<View>(R.id.buttonShare)
        val resetViewBtn = findViewById<View>(R.id.buttonResetView)
        val aboutBtn = findViewById<View>(R.id.buttonAbout)

        setupToolbarPill()
        setupToolSlots()
        setupToolModal()
        setupBrushGrid()
        setupLayerPanel()
        setupLayerPanelDrag()
        penView.setOnViewportChangedListener { scale ->
            runOnUiThread { updateZoomLabel(scale) }
        }
        zoomValueLabel.setOnClickListener { resetViewport() }

        guardRawMode(zoomValueLabel)
        guardRawMode(loadBtn)
        guardRawMode(clearLayerBtn)
        guardRawMode(clearFileBtn)
        guardRawMode(saveBtn)
        guardRawMode(saveFileBtn)
        guardRawMode(shareBtn)
        guardRawMode(resetViewBtn)
        guardRawMode(aboutBtn)
        guardRawMode(buttonLayers)
        guardRawMode(buttonEraser)
        guardRawMode(buttonAddLayer)
        guardRawMode(buttonRemoveLayer)
        guardRawMode(layerRecycler)
        guardRawMode(fileMenuPanel)
        guardRawMode(toolModalPanel)
        guardRawMode(modalColorPickerView)
        toolSlotButtons.forEach { guardRawMode(it) }

        loadBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            pickerInFlight = true
            updateRawSuppression()
            loadDocumentLauncher.launch(buildOpenDocumentIntent())
        }
        clearLayerBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            val ok = penView.clearCurrentLayer()
            if (!ok) {
                Toast.makeText(this, "No active layer", Toast.LENGTH_SHORT).show()
            }
            refreshLayerPanel()
            updateRawSuppression()
        }
        clearFileBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            penView.clearFile()
            currentDocumentBaseName = "drawing"
            refreshLayerPanel()
            updateRawSuppression()
        }
        saveBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            pickerInFlight = true
            updateRawSuppression()
            savePngLauncher.launch("${currentDocumentBaseName}.png")
        }
        saveFileBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            pickerInFlight = true
            updateRawSuppression()
            saveDpaintLauncher.launch("${currentDocumentBaseName}.json")
        }
        shareBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            sharePng()
            updateRawSuppression()
        }
        resetViewBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            resetViewport()
            updateRawSuppression()
        }
        aboutBtn.setOnClickListener {
            fileMenuPanel.visibility = View.GONE
            showAboutDialog()
        }
        buttonLayers.setOnClickListener { toggleLayerPanel() }
        bindImmediateDownAction(buttonMenu) { toggleFileMenu() }
        buttonEraser.setOnClickListener { toggleManualEraserMode() }
        penView.setOnEraserModeChangedListener { active ->
            runOnUiThread { applyEraserModeUiTransition(active) }
        }
        penView.setOnStylusHoverButtonChangedListener { state ->
            Log.i(
                TAG,
                "stylusHoverButtons hovering=${state.hovering} primary=${state.stylusPrimaryPressed} " +
                    "secondary=${state.stylusSecondaryPressed} raw=0x${state.buttonState.toString(16)}"
            )
        }
        penView.setOnTwoFingerTapListener {
            runOnUiThread {
                if (!penView.undoLastStroke()) {
                    Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        applyActiveToolToPenView()
        refreshToolSlotVisuals()
        refreshLayerPanel()
        ensureOverlayOrder()
        updateRawSuppression()
        updateZoomLabel(penView.getViewScale())
        handleIncomingViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingViewIntent(intent)
    }

    // ─── Floating toolbar: drag, minimize/expand, edge-snap ───────────────────

    private fun setupToolbarPill() {
        // Collapsed (minimized) size is computed analytically from the handle's own fixed size
        // plus the pill's padding, rather than by actually hiding content and re-measuring -
        // avoids any visibility-timing flicker since nothing here depends on runtime content.
        toolbarPill.post {
            toolbarExpandedWidth = toolbarPill.width
            toolbarExpandedHeight = toolbarPill.height
            toolbarCollapsedWidth = toolbarHandle.width + toolbarPill.paddingStart + toolbarPill.paddingEnd
            toolbarCollapsedHeight = toolbarHandle.height + toolbarPill.paddingTop + toolbarPill.paddingBottom
        }

        toolbarHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginUiTouch()
                    toolbarDragStartRawX = event.rawX
                    toolbarDragStartRawY = event.rawY
                    toolbarDragStartViewX = toolbarPill.x
                    toolbarDragStartViewY = toolbarPill.y
                    toolbarDragMaxMovement = 0f
                    toolbarDragStartTimeMs = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - toolbarDragStartRawX
                    val dy = event.rawY - toolbarDragStartRawY
                    toolbarDragMaxMovement = maxOf(toolbarDragMaxMovement, sqrt(dx * dx + dy * dy))
                    moveToolbarPill(toolbarDragStartViewX + dx, toolbarDragStartViewY + dy)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    endUiTouch(false)
                    val elapsed = System.currentTimeMillis() - toolbarDragStartTimeMs
                    val wasTap = toolbarDragMaxMovement < dpF(TOOLBAR_TAP_MAX_MOVEMENT_DP) &&
                        elapsed < TOOLBAR_TAP_MAX_DURATION_MS
                    if (wasTap) toggleToolbarMinimized() else snapToolbarToNearestEdge()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    endUiTouch(true)
                    snapToolbarToNearestEdge()
                    true
                }

                else -> false
            }
        }
    }

    /** Live 1:1 drag tracking - no animation here, direct manipulation should track the finger
     *  exactly. Clamped to the current (collapsed or expanded) size so it can't be dragged
     *  partially off-screen mid-drag. */
    private fun moveToolbarPill(targetX: Float, targetY: Float) {
        val parentW = rootFrame.width
        val parentH = rootFrame.height
        if (parentW <= 0 || parentH <= 0) return
        val maxX = (parentW - toolbarPill.width).coerceAtLeast(0)
        val maxY = (parentH - toolbarPill.height).coerceAtLeast(0)
        toolbarPill.x = targetX.coerceIn(0f, maxX.toFloat())
        toolbarPill.y = targetY.coerceIn(0f, maxY.toFloat())
    }

    /** Animates to whichever of the 4 screen edges is nearest the toolbar's current center,
     *  flush against it (minus a small margin), clamping the other axis to stay on-screen. */
    private fun snapToolbarToNearestEdge() {
        val parentW = rootFrame.width.toFloat()
        val parentH = rootFrame.height.toFloat()
        if (parentW <= 0f || parentH <= 0f) return
        val w = toolbarPill.width.toFloat()
        val h = toolbarPill.height.toFloat()
        val margin = dpF(TOOLBAR_EDGE_MARGIN_DP)
        val centerX = toolbarPill.x + w / 2f
        val centerY = toolbarPill.y + h / 2f

        val distLeft = centerX
        val distRight = parentW - centerX
        val distTop = centerY
        val distBottom = parentH - centerY
        val minDist = minOf(distLeft, distRight, distTop, distBottom)

        var targetX = toolbarPill.x.coerceIn(0f, (parentW - w).coerceAtLeast(0f))
        var targetY = toolbarPill.y.coerceIn(0f, (parentH - h).coerceAtLeast(0f))
        when (minDist) {
            distLeft -> targetX = margin
            distRight -> targetX = (parentW - w - margin).coerceAtLeast(margin)
            distTop -> targetY = margin
            else -> targetY = (parentH - h - margin).coerceAtLeast(margin)
        }
        toolbarPill.animate().x(targetX).y(targetY).setDuration(TOOLBAR_ANIM_DURATION_MS).start()
    }

    private fun toggleToolbarMinimized() {
        if (toolbarMinimized) expandToolbar() else minimizeToolbar()
    }

    private fun minimizeToolbar() {
        if (toolbarMinimized) return
        toolbarMinimized = true
        repositionForSize(toolbarCollapsedWidth, toolbarCollapsedHeight)
        toolbarContentGroup.animate().alpha(0f).setDuration(TOOLBAR_ANIM_DURATION_MS).start()
        animatePillSize(toolbarPill.width, toolbarPill.height, toolbarCollapsedWidth, toolbarCollapsedHeight) {
            toolbarContentGroup.visibility = View.GONE
            toolbarContentGroup.alpha = 1f
        }
    }

    /** Reveals content BEFORE the size grows (so it fades in as the pill widens, rather than
     *  popping in once fully expanded), and proactively slides the pill so the full expanded
     *  bounds stay on-screen throughout the animation instead of only clamping at the end. */
    private fun expandToolbar() {
        if (!toolbarMinimized) return
        toolbarMinimized = false
        repositionForSize(toolbarExpandedWidth, toolbarExpandedHeight)
        toolbarContentGroup.visibility = View.VISIBLE
        toolbarContentGroup.alpha = 0f
        toolbarContentGroup.animate().alpha(1f).setDuration(TOOLBAR_ANIM_DURATION_MS).start()
        animatePillSize(toolbarPill.width, toolbarPill.height, toolbarExpandedWidth, toolbarExpandedHeight) {}
    }

    /** Slides the pill (animated) so a box of [targetW]x[targetH] anchored at its current
     *  top-left stays fully within the screen - used before growing so expansion never runs off
     *  an edge, even transiently mid-animation. */
    private fun repositionForSize(targetW: Int, targetH: Int) {
        val parentW = rootFrame.width
        val parentH = rootFrame.height
        if (parentW <= 0 || parentH <= 0) return
        val maxX = (parentW - targetW).coerceAtLeast(0)
        val maxY = (parentH - targetH).coerceAtLeast(0)
        val targetX = toolbarPill.x.coerceIn(0f, maxX.toFloat())
        val targetY = toolbarPill.y.coerceIn(0f, maxY.toFloat())
        if (targetX != toolbarPill.x || targetY != toolbarPill.y) {
            toolbarPill.animate().x(targetX).y(targetY).setDuration(TOOLBAR_ANIM_DURATION_MS).start()
        }
    }

    private fun animatePillSize(fromW: Int, fromH: Int, toW: Int, toH: Int, onEnd: () -> Unit) {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = TOOLBAR_ANIM_DURATION_MS
        animator.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            val lp = toolbarPill.layoutParams
            lp.width = (fromW + (toW - fromW) * t).roundToInt()
            lp.height = (fromH + (toH - fromH) * t).roundToInt()
            toolbarPill.layoutParams = lp
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = onEnd()
        })
        animator.start()
    }

    /**
     * Positions [popup] just below the toolbar (left-aligned with it), flipping above and/or
     * clamping horizontally if there isn't room - called once, synchronously, right before making
     * a panel visible so there's no flash at a stale position. Popups keep their own size (fixed
     * width, wrap_content height) regardless of the toolbar's minimized/expanded state.
     */
    private fun positionPopupNearToolbar(popup: View) {
        val parentW = rootFrame.width
        val parentH = rootFrame.height
        if (parentW <= 0 || parentH <= 0) return
        popup.measure(
            View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(parentH, View.MeasureSpec.AT_MOST)
        )
        val popupW = popup.measuredWidth
        val popupH = popup.measuredHeight
        val margin = dpF(POPUP_MARGIN_DP)

        val toolbarTop = toolbarPill.y
        val toolbarBottom = toolbarTop + toolbarPill.height

        var targetY = toolbarBottom + margin
        if (targetY + popupH > parentH) {
            val above = toolbarTop - margin - popupH
            targetY = if (above >= 0f) above else (parentH - popupH).toFloat().coerceAtLeast(0f)
        }
        var targetX = toolbarPill.x
        if (targetX + popupW > parentW) targetX = (parentW - popupW - margin).coerceAtLeast(margin)
        if (targetX < 0f) targetX = margin

        popup.x = targetX
        popup.y = targetY
    }

    /**
     * Coalesced e-ink refresh for UI-only changes made while a panel stays open (e.g. picking a
     * color or brush in the tool modal) - those already update the underlying Views correctly,
     * but on e-ink nothing reaches the physical panel without an explicit EpdController refresh,
     * same class of bug as the brush-switch/panel-dismiss blanking fixed earlier. Refreshes the
     * whole decor view (toolbar + modal both need it) rather than one region, and coalesces bursts
     * (e.g. dragging the color wheel, which fires onColorChanged continuously) into one call.
     */
    private fun refreshUiEinkThrottled(mode: UpdateMode = UpdateMode.GC) {
        if (uiEinkRefreshScheduled) return
        uiEinkRefreshScheduled = true
        rootFrame.post {
            uiEinkRefreshScheduled = false
            runCatching {
                val decor = window?.decorView ?: rootFrame
                EpdController.invalidate(decor, mode)
                EpdController.refreshScreen(decor, mode)
            }
        }
    }

    // ─── Tool slots (3 independently-configured pens) ─────────────────────────

    private fun setupToolSlots() {
        toolSlotButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener { onToolSlotClicked(index) }
            btn.setOnLongClickListener {
                onToolSlotLongPressed(index)
                true
            }
        }
    }

    /**
     * Tapping an unselected slot makes it the active tool (and exits eraser mode, same as brush
     * selection always did). Tapping the already-selected slot (while not erasing) instead opens
     * the edit modal for it - tap again to close it.
     */
    private fun onToolSlotClicked(index: Int) {
        if (makeToolSlotActive(index)) {
            closeToolModal()
            return
        }
        if (toolModalPanel.visibility == View.VISIBLE) closeToolModal() else openToolModal()
    }

    /** Long-pressing any slot - selected or not - jumps straight to editing it, skipping the
     *  "tap to select, tap again to edit" two-step for slots that aren't already active. */
    private fun onToolSlotLongPressed(index: Int) {
        makeToolSlotActive(index)
        openToolModal()
    }

    /** Switches the active tool to [index] if it wasn't already (also exiting eraser mode, since
     *  any brush selection always does). Returns true if a switch actually happened. */
    private fun makeToolSlotActive(index: Int): Boolean {
        val wasErasing = penView.isEraseModeActive()
        if (selectedToolIndex == index && !wasErasing) return false
        selectedToolIndex = index
        if (wasErasing) {
            manualEraserMode = false
            // deactivateEraserMode() synchronously invokes the eraser-mode listener below, which
            // calls applyActiveToolToPenView() for the newly selected index.
            penView.deactivateEraserMode()
        } else {
            applyActiveToolToPenView()
        }
        refreshToolSlotVisuals()
        return true
    }

    private fun applyActiveToolToPenView() {
        val preset = toolPresets[selectedToolIndex]
        penView.setStyle(preset.style)
        penView.setStrokeColor(preset.color)
        penView.setStrokeWidthPx(preset.widthPx)
    }

    private fun openToolModal() {
        layerPanel.visibility = View.GONE
        fileMenuPanel.visibility = View.GONE
        positionPopupNearToolbar(toolModalPanel)
        toolModalPanel.visibility = View.VISIBLE
        refreshModalContents()
        ensureOverlayOrder()
        updateRawSuppression()
    }

    private fun closeToolModal() {
        if (toolModalPanel.visibility != View.VISIBLE) return
        toolModalPanel.visibility = View.GONE
        hideToolModalSections()
        updateRawSuppression()
    }

    private fun hideToolModalSections() {
        modalColorSection.visibility = View.GONE
        modalSizeSection.visibility = View.GONE
    }

    private fun refreshModalContents() {
        val preset = toolPresets[selectedToolIndex]
        modalColorButton.background = createSwatchDrawable(preset.color, selected = false)
        modalSizeButton.text = "Size: ${preset.widthPx.roundToInt()} px"
        modalSizeSeekBar.progress = widthToProgress(preset.widthPx)
        modalSizeValueLabel.text = "${preset.widthPx.roundToInt()} px"
        modalColorPickerView.setColor(preset.color)
        modalColorHexValue.text = formatHex(preset.color)
        refreshBrushGridHighlight()
    }

    private fun refreshBrushGridHighlight() {
        val preset = toolPresets[selectedToolIndex]
        brushGridButtons.forEach { (style, btn) ->
            val selected = style == preset.style
            btn.alpha = if (selected) 1f else 0.55f
            btn.background = toolSlotBackground(active = selected)
        }
    }

    /**
     * Each toolbar slot shows the icon for its own brush, tinted to its own ink color - doubles
     * as the "current state" indicator the whole feature is built around. Always keeps a faint
     * outline so the button stays visible even if a slot's color happens to be near-white.
     */
    private fun refreshToolSlotVisuals() {
        val eraseActive = penView.isEraseModeActive()
        toolSlotButtons.forEachIndexed { index, btn ->
            val preset = toolPresets[index]
            val active = !eraseActive && index == selectedToolIndex
            btn.setImageResource(brushIconRes(preset.style))
            btn.imageTintList = ColorStateList.valueOf(preset.color)
            btn.alpha = if (active) 1f else 0.55f
            btn.background = toolSlotBackground(active = active)
        }
        updateEraserButtonVisual(eraseActive)
    }

    private fun toolSlotBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpF(8f)
        setColor(if (active) Color.parseColor("#15000000") else Color.TRANSPARENT)
        setStroke(dp(1), if (active) Color.parseColor("#55000000") else Color.parseColor("#22000000"))
    }

    // ─── Tool modal: size/color quick controls + brush grid ───────────────────

    private fun setupToolModal() {
        modalColorButton.setOnClickListener {
            modalSizeSection.visibility = View.GONE
            modalColorSection.visibility = if (modalColorSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        modalSizeButton.setOnClickListener {
            modalColorSection.visibility = View.GONE
            modalSizeSection.visibility = if (modalSizeSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        modalColorPickerView.onColorChanged = { color ->
            toolPresets[selectedToolIndex].color = color
            penView.setStrokeColor(color)
            modalColorButton.background = createSwatchDrawable(color, selected = false)
            modalColorHexValue.text = formatHex(color)
            refreshToolSlotVisuals()
            refreshUiEinkThrottled()
        }
        modalSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val width = progressToWidth(progress)
                toolPresets[selectedToolIndex].widthPx = width
                modalSizeButton.text = "Size: ${width.roundToInt()} px"
                modalSizeValueLabel.text = "${width.roundToInt()} px"
                penView.setStrokeWidthPx(width)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        guardRawMode(modalSizeSeekBar)
    }

    private fun setupBrushGrid() {
        val row1 = findViewById<LinearLayout>(R.id.brushGridRow1)
        val row2 = findViewById<LinearLayout>(R.id.brushGridRow2)
        val perRow = (HardwarePenStyle.entries.size + 1) / 2
        HardwarePenStyle.entries.forEachIndexed { index, style ->
            val btn = ImageButton(this).apply {
                setImageResource(brushIconRes(style))
                setColorFilter(Color.BLACK)
                background = toolSlotBackground(active = false)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = style.label
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { onGridBrushSelected(style) }
            }
            guardRawMode(btn)
            brushGridButtons[style] = btn
            val row = if (index < perRow) row1 else row2
            row.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(56), 1f).also {
                    if (index % perRow != 0) it.marginStart = dp(4)
                }
            )
        }
    }

    private fun onGridBrushSelected(style: HardwarePenStyle) {
        val preset = toolPresets[selectedToolIndex]
        preset.style = style
        preset.widthPx = style.defaultWidthPx
        penView.setStyle(style)
        penView.setStrokeWidthPx(preset.widthPx)
        refreshModalContents()
        refreshToolSlotVisuals()
        refreshUiEinkThrottled()
    }

    private fun brushIconRes(style: HardwarePenStyle): Int = when (style) {
        HardwarePenStyle.PENCIL -> R.drawable.ic_brush_pencil_tip
        HardwarePenStyle.FOUNTAIN -> R.drawable.ic_brush_fountain_tip
        HardwarePenStyle.MARKER -> R.drawable.ic_brush_marker_tip
        HardwarePenStyle.NEO_BRUSH -> R.drawable.ic_brush_neo_tip
        HardwarePenStyle.CHARCOAL -> R.drawable.ic_brush_charcoal_tip
        HardwarePenStyle.DASH -> R.drawable.ic_brush_dash_tip
        HardwarePenStyle.CHARCOAL_V2 -> R.drawable.ic_brush_charcoal_v2_tip
        HardwarePenStyle.SQUARE_PEN -> R.drawable.ic_brush_square_tip
    }

    private fun setupLayerPanel() {
        layerAdapter = LayerListAdapter(
            onSelect = { layerId ->
                if (penView.setActiveLayer(layerId)) refreshLayerPanel()
            },
            onToggleVisible = { layerId, visible ->
                if (penView.setLayerVisible(layerId, visible)) refreshLayerPanel()
            },
            onOpacityChanged = { layerId, opacity ->
                penView.setLayerOpacity(layerId, opacity)
            }
        )
        layerRecycler.layoutManager = LinearLayoutManager(this)
        layerRecycler.adapter = layerAdapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                layerAdapter.moveItem(from, to)
                penView.moveLayerByDisplayIndices(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                refreshLayerPanel()
            }
        })
        itemTouchHelper.attachToRecyclerView(layerRecycler)

        buttonAddLayer.setOnClickListener {
            penView.addLayer()
            refreshLayerPanel()
        }
        buttonRemoveLayer.setOnClickListener {
            val active = penView.getLayerInfos().firstOrNull { it.active }
            if (active == null) {
                Toast.makeText(this, "No active layer", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val removed = penView.removeLayer(active.id)
            if (!removed) {
                Toast.makeText(this, "Cannot remove last layer", Toast.LENGTH_SHORT).show()
            }
            refreshLayerPanel()
        }
    }

    private fun setupLayerPanelDrag() {
        layerDragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginUiTouch()
                    layerDragDx = layerPanel.x - event.rawX
                    layerDragDy = layerPanel.y - event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    moveLayerPanel(event.rawX + layerDragDx, event.rawY + layerDragDy)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    endUiTouch(false)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    endUiTouch(true)
                    true
                }

                else -> false
            }
        }
    }

    private fun moveLayerPanel(targetX: Float, targetY: Float) {
        val parentW = rootFrame.width
        val parentH = rootFrame.height
        if (parentW <= 0 || parentH <= 0) return
        val maxX = (parentW - layerPanel.width).coerceAtLeast(0)
        val maxY = (parentH - layerPanel.height).coerceAtLeast(0)
        layerPanel.x = targetX.coerceIn(0f, maxX.toFloat())
        layerPanel.y = targetY.coerceIn(0f, maxY.toFloat())
        ensureOverlayOrder()
    }

    private fun toggleLayerPanel() {
        val willShow = layerPanel.visibility != View.VISIBLE
        if (willShow) {
            closeToolModal()
            positionPopupNearToolbar(layerPanel)
        }
        layerPanel.visibility = if (willShow) View.VISIBLE else View.GONE
        if (willShow) refreshLayerPanel()
        ensureOverlayOrder()
        updateRawSuppression()
    }

    private fun toggleFileMenu() {
        val willShow = fileMenuPanel.visibility != View.VISIBLE
        if (willShow) {
            closeToolModal()
            positionPopupNearToolbar(fileMenuPanel)
        }
        fileMenuPanel.visibility = if (willShow) View.VISIBLE else View.GONE
        ensureOverlayOrder()
        updateRawSuppression()
    }

    private fun ensureOverlayOrder() {
        layerPanel.bringToFront()
        toolModalPanel.bringToFront()
        fileMenuPanel.bringToFront()
    }

    private fun refreshLayerPanel() {
        layerAdapter.submit(penView.getLayerInfos())
    }

    private fun toggleManualEraserMode() {
        if (!manualEraserMode) {
            manualEraserMode = true
            penView.setManualEraserMode(true)
            return
        }
        manualEraserMode = false
        penView.setManualEraserMode(false)
    }

    private fun updateEraserButtonVisual(active: Boolean) {
        buttonEraser.alpha = if (active) 1f else 0.45f
        buttonEraser.translationY = if (active) dp(4).toFloat() else 0f
    }

    /**
     * Whatever caused eraser mode to end (a tool slot tap, the stylus tip flipping back from its
     * eraser end, toggling the eraser button off), the currently-selected tool preset already
     * reflects what should now be active - re-applying it is simpler and equally correct in every
     * case than the old per-cause "restore the previous brush/color" bookkeeping.
     */
    private fun onEraserModeChanged(active: Boolean) {
        if (active == eraserWasActive) return
        eraserWasActive = active
        if (!active) {
            manualEraserMode = false
            applyActiveToolToPenView()
        }
    }

    private fun applyEraserModeUiTransition(active: Boolean) {
        // Force a short pause of hardware preview so toolbar state changes become visible immediately on e-ink.
        eraserUiTransitionInFlight = true
        updateRawSuppression()

        onEraserModeChanged(active)
        refreshToolSlotVisuals()
        refreshToolbarEinkImmediately()

        pendingEraserUiTransitionReset?.let { rootFrame.removeCallbacks(it) }
        val reset = Runnable {
            eraserUiTransitionInFlight = false
            updateRawSuppression()
        }
        pendingEraserUiTransitionReset = reset
        rootFrame.postDelayed(reset, 48L)
    }

    private fun createSwatchDrawable(fill: Int, selected: Boolean): GradientDrawable {
        val ringColor = when {
            selected && fill == Color.WHITE -> Color.BLACK
            selected -> Color.WHITE
            fill == Color.WHITE -> Color.DKGRAY
            else -> Color.LTGRAY
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(if (selected) dp(2) else dp(1), ringColor)
        }
    }

    private fun formatHex(color: Int): String =
        String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))

    private fun refreshToolbarEinkImmediately() {
        toolbarPill.invalidate()
        buttonEraser.invalidate()
        // Refresh only the toolbar pill, not the full decor view - refreshing the whole window
        // also flashes the drawing canvas underneath and doubles the work for no visual benefit.
        // Handheld panels (Palma-class) get DU: it's faster and the toolbar is near-monochrome,
        // so DU's lack of gray levels isn't visible, while GU's full flash there is proportionally
        // much more jarring on a small screen than on a tablet-class panel.
        val toolbarUpdateMode = if (isHandheldEinkPanel) UpdateMode.DU else UpdateMode.GU
        toolbarPill.post {
            runCatching {
                EpdController.invalidate(toolbarPill, toolbarUpdateMode)
                EpdController.refreshScreen(toolbarPill, toolbarUpdateMode)
            }
        }
    }

    private fun progressToWidth(progress: Int): Float {
        val t = progress / 99f
        return 1f + t * t * 79f
    }

    private fun updateZoomLabel(scale: Float) {
        val pct = (scale * 100f).roundToInt().coerceAtLeast(100)
        zoomValueLabel.text = "${pct}%"
    }

    private fun resetViewport() {
        penView.resetViewport()
        updateZoomLabel(penView.getViewScale())
    }

    private fun widthToProgress(width: Float): Int {
        val t = ((width - 1f) / 79f).coerceIn(0f, 1f)
        return (sqrt(t.toDouble()) * 99).roundToInt()
    }

    private fun savePng(uri: Uri) {
        val bmp = penView.exportBitmap() ?: run {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = runCatching {
            contentResolver.openOutputStream(uri, "w")?.use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } ?: false
        }.getOrDefault(false)
        Toast.makeText(this, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
    }

    private fun saveDpaint(uri: Uri) {
        val snapshot = penView.snapshotDocumentForExport() ?: run {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        val name = documentDisplayName(uri)?.substringBeforeLast('.')?.ifBlank { "Untitled" } ?: "Untitled"
        val ok = runCatching {
            val json = buildDpaintJson(snapshot, name)
            contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                true
            } ?: false
        }.getOrDefault(false)
        snapshot.layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        if (ok) {
            currentDocumentBaseName = normalizeDocumentBaseName(documentDisplayName(uri))
        }
        Toast.makeText(this, if (ok) "Saved" else "Save failed", Toast.LENGTH_SHORT).show()
    }

    private fun sharePng() {
        val bmp = penView.exportBitmap() ?: run {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val safeName = normalizeDocumentBaseName(currentDocumentBaseName)
        val sharedDir = File(cacheDir, "shared")
        if (!sharedDir.exists() && !sharedDir.mkdirs()) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show()
            return
        }

        val target = File(sharedDir, "$safeName.png")
        val ok = runCatching {
            FileOutputStream(target).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        }.getOrDefault(false)
        if (!ok) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show()
            return
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, safeName)
            putExtra(Intent.EXTRA_TITLE, safeName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "$safeName.png", uri)
        }
        val chooser = Intent.createChooser(sendIntent, "Share drawing")
        startActivity(chooser)
    }

    private fun loadDocument(uri: Uri) {
        val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
        val name = documentDisplayName(uri)?.lowercase().orEmpty()
        val looksJson = mime.contains("json") || name.endsWith(".json")
        val looksImage = mime.startsWith("image/") ||
            name.endsWith(".png") || name.endsWith(".jpg") ||
            name.endsWith(".jpeg") || name.endsWith(".webp")

        if (looksJson) {
            val ok = loadDpaintDocument(uri)
            if (ok) {
                currentDocumentBaseName = normalizeDocumentBaseName(documentDisplayName(uri))
            }
            Toast.makeText(this, if (ok) "Loaded" else "Load failed", Toast.LENGTH_SHORT).show()
            return
        }

        if (looksImage) {
            val ok = loadImageIntoCurrentLayer(uri)
            if (ok) {
                currentDocumentBaseName = normalizeDocumentBaseName(documentDisplayName(uri))
            }
            Toast.makeText(this, if (ok) "Loaded" else "Load failed", Toast.LENGTH_SHORT).show()
            return
        }

        val loadedDpaint = loadDpaintDocument(uri)
        if (loadedDpaint) {
            currentDocumentBaseName = normalizeDocumentBaseName(documentDisplayName(uri))
            Toast.makeText(this, "Loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val loadedImage = loadImageIntoCurrentLayer(uri)
        if (loadedImage) {
            currentDocumentBaseName = normalizeDocumentBaseName(documentDisplayName(uri))
        }
        Toast.makeText(this, if (loadedImage) "Loaded" else "Load failed", Toast.LENGTH_SHORT).show()
    }

    private fun handleIncomingViewIntent(incoming: Intent?) {
        val action = incoming?.action ?: return
        if (action != Intent.ACTION_VIEW) return
        val uri = incoming.data ?: return
        pendingIncomingViewUri = uri
        pendingIncomingViewFlags = incoming.flags
        pendingIncomingViewAttempts = 0
        processPendingIncomingViewIntent()
    }

    private fun processPendingIncomingViewIntent() {
        val uri = pendingIncomingViewUri ?: return
        if (penView.width <= 0 || penView.height <= 0) {
            if (pendingIncomingViewAttempts >= 40) {
                Log.w(TAG, "Incoming VIEW uri dropped: pen surface never became ready: $uri")
                pendingIncomingViewUri = null
                pendingIncomingViewFlags = 0
                pendingIncomingViewAttempts = 0
                Toast.makeText(this, "Load failed", Toast.LENGTH_SHORT).show()
                return
            }
            pendingIncomingViewAttempts += 1
            penView.postDelayed({ processPendingIncomingViewIntent() }, 32L)
            return
        }

        val flags = pendingIncomingViewFlags
        pendingIncomingViewUri = null
        pendingIncomingViewFlags = 0
        pendingIncomingViewAttempts = 0

        val readFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching { contentResolver.takePersistableUriPermission(uri, readFlags) }

        rememberLastOpenUri(uri)
        loadDocument(uri)
    }

    private fun loadImageIntoCurrentLayer(uri: Uri): Boolean {
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        }.getOrNull()

        if (bitmap == null) return false
        val ok = penView.loadCanvasBitmap(bitmap)
        bitmap.recycle()
        if (ok) refreshLayerPanel()
        return ok
    }

    private fun loadDpaintDocument(uri: Uri): Boolean {
        val text = readUriText(uri) ?: return false
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (!root.optString("type").equals("dpaint", ignoreCase = true)) return false

        val image = root.optJSONObject("image") ?: return false
        val sourceWidth = image.optInt("width", 0)
        val sourceHeight = image.optInt("height", 0)
        val frames = image.optJSONArray("frames") ?: return false
        if (frames.length() <= 0) return false

        val frame = frames.optJSONObject(0) ?: return false
        val imageActiveIndex = image.optInt("activeLayerIndex", 0)
        val requestedActiveIndex = frame.optInt("activeLayerIndex", imageActiveIndex).coerceAtLeast(0)
        val layers = frame.optJSONArray("layers") ?: return false
        if (layers.length() <= 0) return false

        val importedLayers = ArrayList<HardwarePenSurfaceView.LayerSnapshot>(layers.length())
        var mappedActiveIndex = 0
        for (i in 0 until layers.length()) {
            val layerObj = layers.optJSONObject(i) ?: continue
            val canvasData = layerObj.optString("canvas", "")
            if (canvasData.isBlank()) continue
            val bitmap = decodeDataUrlBitmap(canvasData) ?: continue
            importedLayers.add(
                HardwarePenSurfaceView.LayerSnapshot(
                    name = layerObj.optString("name", "Layer ${i + 1}"),
                    visible = layerObj.optBoolean("visible", true),
                    opacity = (layerObj.optDouble("opacity", 100.0) / 100.0).toFloat(),
                    bitmap = bitmap,
                )
            )
            if (i == requestedActiveIndex) {
                mappedActiveIndex = importedLayers.lastIndex
            }
        }

        if (importedLayers.isEmpty()) return false
        val ok = penView.replaceFileWithLayers(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceLayers = importedLayers,
            activeLayerIndex = mappedActiveIndex,
        )
        importedLayers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        if (ok) {
            refreshLayerPanel()
            resetViewport()
        }
        return ok
    }

    private fun buildDpaintJson(
        snapshot: HardwarePenSurfaceView.DocumentSnapshot,
        imageName: String,
    ): JSONObject {
        val layersArray = JSONArray()
        snapshot.layers.forEach { layer ->
            layersArray.put(
                JSONObject()
                    .put("name", layer.name)
                    .put("blendMode", "normal")
                    .put("opacity", (layer.opacity.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100))
                    .put("visible", layer.visible)
                    .put("hasMask", false)
                    .put("canvas", bitmapToDataUrl(layer.bitmap))
            )
        }

        val frame = JSONObject()
            .put("activeLayerIndex", snapshot.activeLayerIndex)
            .put("layers", layersArray)

        val image = JSONObject()
            .put("name", imageName)
            .put("width", snapshot.width)
            .put("height", snapshot.height)
            .put("activeLayerIndex", snapshot.activeLayerIndex)
            .put("activeFrameIndex", 0)
            .put("frames", JSONArray().put(frame))
            .put("colorRange", JSONArray())

        val palette = JSONArray()
            .put(JSONArray().put(0).put(0).put(0))
            .put(JSONArray().put(255).put(255).put(255))

        return JSONObject()
            .put("type", "dpaint")
            .put("version", "1")
            .put("image", image)
            .put("palette", palette)
            .put("paletteList", JSONArray().put(palette))
            .put("paletteIndex", 0)
            .put("errorCount", 0)
    }

    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$b64"
    }

    private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? {
        val marker = "base64,"
        val start = dataUrl.indexOf(marker)
        val payload = if (start >= 0) dataUrl.substring(start + marker.length) else dataUrl
        return runCatching {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun readUriText(uri: Uri): String? =
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()

    private fun documentDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0) return@use null
                cursor.getString(idx)
            }
        }.getOrNull()

    private fun normalizeDocumentBaseName(displayName: String?): String {
        val cleaned = displayName
            ?.substringBeforeLast('.', displayName)
            ?.trim()
            ?.replace(Regex("""[\\/:*?"<>|]"""), "_")
            ?.ifBlank { null }
        return cleaned ?: "drawing"
    }

    private fun buildOpenDocumentIntent(): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, openMimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        val initialUri = lastOpenUri()
        if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        return intent
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun rememberLastOpenUri(uri: Uri) {
        prefs().edit().putString(KEY_LAST_OPEN_URI, uri.toString()).apply()
    }

    private fun lastOpenUri(): Uri? {
        val raw = prefs().getString(KEY_LAST_OPEN_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    /**
     * uiTouchDepth gates ALL stylus input to the drawing surface (see updateRawSuppression /
     * penView.setRawInputSuppressed) - if it's ever left stuck above zero (a DOWN whose matching
     * UP/CANCEL never arrives - confirmed via logcat: onTouchEvent receives DOWN/UP but
     * touchHelper never sees them, meaning rawInputSuppressed was stuck true), drawing silently
     * stops reaching the pen chip for the rest of the process's life - every future stroke just
     * gets dropped with no error, which looks exactly like "the canvas forgets strokes." This
     * watchdog self-heals: if a begin is never matched by an end within a few seconds (longer
     * than any real button press), force the counter back down.
     */
    private var uiTouchWatchdogReset: Runnable? = null

    private fun beginUiTouch() {
        uiTouchDepth += 1
        updateRawSuppression()
        uiTouchWatchdogReset?.let { rootFrame.removeCallbacks(it) }
        val watchdog = Runnable {
            if (uiTouchDepth > 0) {
                Log.w(TAG, "uiTouchDepth watchdog: stuck at $uiTouchDepth after ${UI_TOUCH_WATCHDOG_MS}ms, forcing reset")
                uiTouchDepth = 0
                updateRawSuppression()
            }
        }
        uiTouchWatchdogReset = watchdog
        rootFrame.postDelayed(watchdog, UI_TOUCH_WATCHDOG_MS)
    }

    private fun endUiTouch(reset: Boolean) {
        uiTouchDepth = if (reset) 0 else (uiTouchDepth - 1).coerceAtLeast(0)
        updateRawSuppression()
        if (uiTouchDepth == 0) {
            uiTouchWatchdogReset?.let { rootFrame.removeCallbacks(it) }
            uiTouchWatchdogReset = null
        }
    }

    private fun guardRawMode(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> beginUiTouch()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> endUiTouch(false)
                MotionEvent.ACTION_CANCEL -> endUiTouch(true)
            }
            false
        }
    }

    private fun bindImmediateDownAction(view: View, onDown: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginUiTouch()
                    v.isPressed = true
                    onDown()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    endUiTouch(false)
                    v.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    endUiTouch(true)
                    true
                }

                else -> true
            }
        }
    }

    private fun updateRawSuppression() {
        val suppress = activityPaused ||
            pickerInFlight ||
            aboutDialogVisible ||
            eraserUiTransitionInFlight ||
            uiTouchDepth > 0 ||
            layerPanel.visibility == View.VISIBLE ||
            toolModalPanel.visibility == View.VISIBLE ||
            fileMenuPanel.visibility == View.VISIBLE
        penView.setRawInputSuppressed(suppress)
    }

    private fun showAboutDialog() {
        aboutDialogVisible = true
        updateRawSuppression()

        val content = layoutInflater.inflate(R.layout.dialog_about, null)
        val imageView = content.findViewById<android.widget.ImageView>(R.id.aboutImage)
        val textColumn = content.findViewById<LinearLayout>(R.id.aboutTextColumn)
        val linkView = content.findViewById<TextView>(R.id.aboutLink)
        val okButton = content.findViewById<TextView>(R.id.aboutOkButton)
        val linkText = SpannableStringBuilder("Open source - fork me on Github").apply {
            val start = lastIndexOf("Github")
            if (start >= 0) {
                val end = start + "Github".length
                setSpan(
                    URLSpan("https://github.com/Auxnon/boox-note"),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        linkView.text = linkText
        linkView.movementMethod = LinkMovementMethod.getInstance()

        val dialog = Dialog(this, R.style.Theme_BooxNote_AboutDialog).apply {
            setContentView(content)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        okButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            aboutDialogVisible = false
            updateRawSuppression()
        }
        dialog.show()
        content.post {
            val side = textColumn.height.coerceAtLeast(dp(96))
            val lp = imageView.layoutParams
            if (lp.width != side || lp.height != side) {
                lp.width = side
                lp.height = side
                imageView.layoutParams = lp
            }
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if (dismissPanelsIfTappedOutside(ev.rawX, ev.rawY)) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isStylusKey = when (event.keyCode) {
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
            -> true
            else -> false
        }
        if (isStylusKey) {
            val action = when (event.action) {
                KeyEvent.ACTION_DOWN -> "DOWN"
                KeyEvent.ACTION_UP -> "UP"
                else -> "ACTION_${event.action}"
            }
            Log.i(
                TAG,
                "stylusKey action=$action key=${KeyEvent.keyCodeToString(event.keyCode)} " +
                    "repeat=${event.repeatCount} source=0x${event.source.toString(16)} device=${event.device?.name}"
            )
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dismissPanelsIfTappedOutside(rawX: Float, rawY: Float): Boolean {
        var dismissed = false

        if (layerPanel.visibility == View.VISIBLE &&
            !isPointInsideView(layerPanel, rawX, rawY) &&
            !isPointInsideView(buttonLayers, rawX, rawY)
        ) {
            layerPanel.visibility = View.GONE
            dismissed = true
        }

        if (fileMenuPanel.visibility == View.VISIBLE &&
            !isPointInsideView(fileMenuPanel, rawX, rawY) &&
            !isPointInsideView(buttonMenu, rawX, rawY)
        ) {
            fileMenuPanel.visibility = View.GONE
            dismissed = true
        }

        if (toolModalPanel.visibility == View.VISIBLE &&
            !isPointInsideView(toolModalPanel, rawX, rawY) &&
            toolSlotButtons.none { isPointInsideView(it, rawX, rawY) }
        ) {
            toolModalPanel.visibility = View.GONE
            hideToolModalSections()
            dismissed = true
        }

        if (dismissed) {
            updateRawSuppression()
            refreshUiAfterOverlayDismiss()
        }
        return dismissed
    }

    private fun refreshUiAfterOverlayDismiss() {
        rootFrame.post {
            rootFrame.invalidate()
            penView.invalidate()
            // On e-ink, invalidate() alone only redraws into the Android-level buffer - the
            // physical panel doesn't repaint until an actual EpdController refresh is issued (same
            // class of bug as the earlier brush-switch blanking). Without this, a dismissed panel
            // (e.g. the color picker) leaves its stale pixels sitting on screen indefinitely.
            rootFrame.post {
                runCatching {
                    val decor = window?.decorView ?: rootFrame
                    EpdController.invalidate(decor, UpdateMode.GC)
                    EpdController.refreshScreen(decor, UpdateMode.GC)
                }
            }
        }
    }

    private fun isPointInsideView(view: View, rawX: Float, rawY: Float): Boolean {
        val r = Rect()
        view.getGlobalVisibleRect(r)
        return r.contains(rawX.roundToInt(), rawY.roundToInt())
    }

    override fun onPause() {
        super.onPause()
        activityPaused = true
        updateRawSuppression()
    }

    override fun onResume() {
        super.onResume()
        activityPaused = false
        // A UI touch gesture can never legitimately survive a pause - if uiTouchDepth was left
        // stuck above zero because some control's DOWN never got a matching UP/CANCEL delivered
        // before the activity paused, it would otherwise stay stuck forever, permanently
        // suppressing raw stylus input (see beginUiTouch's doc comment).
        uiTouchWatchdogReset?.let { rootFrame.removeCallbacks(it) }
        uiTouchWatchdogReset = null
        uiTouchDepth = 0
        updateRawSuppression()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density
}
