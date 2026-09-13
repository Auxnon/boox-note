package com.auxnon.booxnote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoCharcoalPen
import com.onyx.android.sdk.pen.NeoCharcoalPenV2
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.NeoMarkerPenWrapper
import com.onyx.android.sdk.pen.NeoPen
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoPencilPen
import com.onyx.android.sdk.pen.NeoPenUtils
import com.onyx.android.sdk.pen.NeoRenderPoint
import com.onyx.android.sdk.pen.NeoSquarePen
import com.onyx.android.sdk.pen.PencilNeoPenRender
import com.onyx.android.sdk.pen.PenResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software-canvas renderer that faithfully replicates each Onyx hardware stroke style.
 *
 * Strategy per style:
 *  PENCIL     – NeoPencilPen (native texture); fallback: plain solid polyline
 *  FOUNTAIN   – NeoFountainPenWrapper (pressure-sensitive calligraphy outline)
 *  MARKER     – NeoMarkerPenWrapper on an off-screen layer composited at 50% alpha
 *  NEO_BRUSH  – NeoBrushPenWrapper (variable-width ink brush)
 *  CHARCOAL   – ring + dot cloud texture (V1 params)
 *  DASH       – DashPathEffect line
 *  CHARCOAL_V2– ring + dot cloud texture (V2 params, slightly larger halos)
 *  SQUARE_PEN – NeoSquarePen native path-result renderer (fallback: trig approximation)
 */
object OnyxStrokeRenderer {

    /**
     * Tilt-vector magnitudes that map to "upright" and "fully leaned" for the pencil.
     *
     * Measured, not assumed: sweeping the pen through its whole usable range reports magnitudes of
     * roughly 41 at the most upright a hand can hold it and 70 laid right over, with ordinary
     * strokes sitting around 59-70. Earlier guesses of 0..64 and 25..70 both started well below
     * anything reachable, so most of the curve was unreachable and the top of it - where all the
     * real strokes live - was compressed into a narrow band that looked like no response at all.
     */
    private const val PENCIL_TILT_MIN = 40f
    private const val PENCIL_TILT_MAX = 70f

    /** How far leaning the pen spreads the mark along the lean. Confirmed working at a deliberately
     *  absurd 12; settled here - stronger than the 3.2 that read as no response, without the smear. */
    private const val PENCIL_ALTITUDE_GAIN = 6.5f

    /** Stamp length at full upright, as a fraction of the brush radius. Has to stay near 1: at 0.25
     *  an upright pen drew a thin sliver regardless of how wide the brush was set, which is the
     *  "high width still has a small vertical pen size" problem - the floor was the width, not the
     *  setting. Keeping it close to `across` means upright reads as a full-width round point. */
    private const val PENCIL_UPRIGHT_LENGTH = 0.9f

    /** How much of the lean's growth also applies across it. Kept low so the stamp becomes a
     *  genuinely eccentric oval: let this rise and the perpendicular axis chases the long one and
     *  the mark just inflates into a bigger circle. It still grows a little, so a leaned pencil
     *  covers more paper rather than only changing shape. */
    private const val PENCIL_ACROSS_RATIO = 0.12f

    /** How much a fully leaned texture-stamped stroke (pencil, charcoal) lightens. */
    private const val LEAN_FADE = 0.45f

    fun erase(
        style: HardwarePenStyle,
        points: List<TouchPoint>,
        widthPx: Float,
        canvas: Canvas,
        maxPressure: Float,
    ) {
        if (points.isEmpty()) return
        val w = canvas.width
        val h = canvas.height
        if (w <= 0 || h <= 0) return
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val maskCanvas = Canvas(mask)
            render(style, points, widthPx, Color.BLACK, maskCanvas, maxPressure)
            normalizeMaskAlpha(mask)
            val clearPaint = Paint().apply {
                isFilterBitmap = false
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            canvas.drawBitmap(mask, 0f, 0f, clearPaint)
            clearPaint.xfermode = null
        } finally {
            mask.recycle()
        }
    }

    fun erase(
        points: List<TouchPoint>,
        widthPx: Float,
        canvas: Canvas,
    ) {
        if (points.isEmpty()) return
        val paint = Paint().apply {
            color = Color.TRANSPARENT
            strokeWidth = widthPx.coerceAtLeast(0.5f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, paint)
        } else {
            drawPolyline(points, canvas, paint)
        }
        paint.xfermode = null
    }

    private fun normalizeMaskAlpha(mask: Bitmap) {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0) return
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = if ((pixels[i] ushr 24) != 0) 0xFF000000.toInt() else 0
        }
        mask.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    // ─── Charcoal V2 rendering ────────────────────────────────────────────────

    fun render(
        style: HardwarePenStyle,
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        maxPressure: Float,
    ) {
        if (points.isEmpty()) return
        // Fast strokes deliver widely and unevenly spaced raw samples. The native pen engines
        // (everything below except Pencil/Dash, which just draw a plain polyline) fit a smooth
        // curve through the points and estimate tangent direction from point-to-point velocity -
        // so a big, uneven gap between two samples produces a large, noisy velocity estimate and
        // the curve's tangent handle overshoots past the actual path. That's the "sudden sharp
        // line to the side, like it multiplies velocity" artifact. Feeding the native engines
        // evenly, closely spaced points instead (by linearly interpolating extra ones into long
        // gaps) keeps no single segment "too fast" for that math to misbehave on.
        val pts = if (style == HardwarePenStyle.DASH) {
            points
        } else {
            // Cap scales with width, not fixed: Charcoal (and to a lesser extent the other
            // texture-stamp styles) allocates and blits one stamp bitmap per point here, and each
            // stamp is roughly as wide as the stroke. A fixed low cap (e.g. 10px) means a wide
            // brush gets the exact same stamp COUNT as a thin one despite each stamp costing far
            // more to rasterize - adjacent 80px-wide stamps spaced 10px apart overlap massively,
            // multiplying redraw cost for no visual benefit (this was the "80px is very slow"
            // Charcoal complaint - not spline overshoot, just wasted overlapping stamp work).
            // Scaling the cap with width keeps the same visual density ratio while cutting stamp
            // count proportionally for wide brushes.
            densifyForNativeSpline(points, maxStepPx = (widthPx * 0.5f).coerceIn(2f, 40f))
        }
        when (style) {
            HardwarePenStyle.PENCIL -> renderPencil(pts, widthPx, color, canvas, maxPressure)
            HardwarePenStyle.FOUNTAIN -> renderFountain(pts, widthPx, color, canvas, maxPressure)
            HardwarePenStyle.MARKER -> renderMarker(pts, widthPx, color, canvas, maxPressure)
            HardwarePenStyle.NEO_BRUSH -> renderNeoBrush(pts, widthPx, color, canvas, maxPressure)
            HardwarePenStyle.CHARCOAL -> renderCharcoal(pts, widthPx, color, canvas, v2 = false, maxPressure = maxPressure)
            HardwarePenStyle.DASH -> renderDash(pts, widthPx, color, canvas)
            HardwarePenStyle.CHARCOAL_V2 -> renderCharcoal(pts, widthPx, color, canvas, v2 = true, maxPressure = maxPressure)
            HardwarePenStyle.SQUARE_PEN -> renderSquarePen(pts, widthPx, color, canvas, maxPressure)
        }
    }

    private fun densifyForNativeSpline(points: List<TouchPoint>, maxStepPx: Float): List<TouchPoint> {
        if (points.size < 2) return points
        val out = ArrayList<TouchPoint>(points.size * 2)
        out.add(points[0])
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxStepPx) {
                val steps = ceil(dist / maxStepPx).toInt()
                for (s in 1 until steps) {
                    out.add(interpolateTouchPoint(prev, curr, s.toFloat() / steps))
                }
            }
            out.add(curr)
        }
        return out
    }

    private fun interpolateTouchPoint(a: TouchPoint, b: TouchPoint, t: Float): TouchPoint {
        val out = TouchPoint(b)
        out.x = lerp(a.x, b.x, t)
        out.y = lerp(a.y, b.y, t)
        out.pressure = lerp(a.pressure, b.pressure, t)
        out.size = lerp(a.size, b.size, t)
        out.tiltX = lerp(a.tiltX.toFloat(), b.tiltX.toFloat(), t).roundToInt()
        out.tiltY = lerp(a.tiltY.toFloat(), b.tiltY.toFloat(), t).roundToInt()
        return out
    }

    // ─── PENCIL: NeoPencilPen (native texture), fallback plain polyline ───────

    /**
     * Pencil, stamped from Onyx's own graphite mask but positioned by us.
     *
     * The native NeoPencilPen path is tried last rather than first now. It does run - it produces
     * real grain - but its stamps come out pinned near minimum size whatever width is configured,
     * and a rotated stamp that small shows no visible angle, which is why azimuth response
     * disappeared the moment we switched to it. Its sizing is decided inside libneo_pen.so from an
     * undocumented mix of config fields, so getting it right means guessing one field per build.
     *
     * Stamping the same texture ourselves keeps the authentic graphite (it is the exact 256x256
     * mask the engine uses, vendored from onyxsdk-penbrush) while leaving size, azimuth and
     * altitude as arithmetic we control and can reason about.
     */
    private fun renderPencil(points: List<TouchPoint>, widthPx: Float, color: Int, canvas: Canvas, maxPressure: Float) {
        // Our stamping first. Running the native engine ahead of it - on charcoal's exact config -
        // was tried as a direct comparison and came out rounder, not more oval: whatever the engine
        // does for type=7 it is not the elongated contact patch a leaned pencil should leave, and
        // its geometry is not ours to steer. Charcoal is left on the engine because there it works.
        val stamped = runCatching {
            renderPencilStamped(points, widthPx, color, canvas, maxPressure)
        }.getOrDefault(false)
        if (stamped) return
        val nativeDrawn = runCatching {
            renderPencilNative(points, widthPx, color, canvas, maxPressure)
        }.getOrDefault(false)
        if (nativeDrawn) return
        renderPencilGrain(points, widthPx, color, canvas, maxPressure)
    }

    /**
     * Paint that fades a texture-stamped stroke according to how far the pen was leaning.
     *
     * The native engines widen the mark as the pen tilts but lay it down at full strength, so a
     * leaned stroke reads as a broad dark band instead of shading. Their render points carry no
     * tilt back, so this is per stroke rather than per stamp: a stroke drawn leaning comes out
     * uniformly lighter, which is most of the effect without trying to unpick the engine's output.
     * Returns null when the pen was near upright, leaving those strokes exactly as they were.
     */
    private fun leanFadePaint(points: List<TouchPoint>): Paint? {
        if (points.isEmpty()) return null
        var total = 0f
        var counted = 0
        for (p in points) {
            if (p.tiltX == 0 && p.tiltY == 0) continue
            total += hypot(p.tiltX.toFloat(), p.tiltY.toFloat())
            counted++
        }
        if (counted == 0) return null
        val altitude = ((total / counted - PENCIL_TILT_MIN) / (PENCIL_TILT_MAX - PENCIL_TILT_MIN))
            .coerceIn(0f, 1f)
        if (altitude <= 0.02f) return null
        return Paint().apply {
            isFilterBitmap = true
            alpha = (255f * (1f - altitude * LEAN_FADE)).toInt().coerceIn(24, 255)
        }
    }

    private var pencilMask: Bitmap? = null

    private fun pencilMask(): Bitmap? {
        pencilMask?.let { if (!it.isRecycled) return it }
        val ctx = runCatching { com.onyx.android.sdk.base.utils.ResManager.getContext() }.getOrNull()
            ?: return null
        val bmp = runCatching {
            android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.onyx_pencil_brush)
        }.getOrNull()
        pencilMask = bmp
        return bmp
    }

    /**
     * Walks the stroke stamping the graphite mask: rotated to the tilt azimuth, stretched and
     * lightened by the tilt magnitude (altitude), sized by width with pressure only modulating it.
     */
    private fun renderPencilStamped(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        maxPressure: Float,
    ): Boolean {
        if (points.size < 2) return false
        val mask = pencilMask() ?: return false
        val safeMaxPressure = max(1f, maxPressure)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        val src = Rect(0, 0, mask.width, mask.height)
        val dst = RectF()
        val baseRadius = max(0.6f, widthPx * 0.5f)
        // Spacing follows the stamp, so speed changes density rather than darkness.
        val step = max(0.6f, baseRadius * 0.22f)
        var carry = 0f
        // Reports the tilt range this stroke actually spanned, so PENCIL_TILT_MIN/MAX can be
        // calibrated from measurements instead of estimates - the previous range was guessed and
        // was the reason altitude appeared to do nothing.
        var tiltSeenMin = Float.MAX_VALUE
        var tiltSeenMax = 0f

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val segLen = hypot(b.x - a.x, b.y - a.y)
            if (segLen <= 0.0001f) continue
            var t = carry
            while (t <= segLen) {
                val f = t / segLen
                val x = a.x + (b.x - a.x) * f
                val y = a.y + (b.y - a.y) * f

                // Pressure modulates, it does not define: a light stroke should still be the width
                // you asked for, just fainter.
                val press = ((a.pressure + (b.pressure - a.pressure) * f) / safeMaxPressure)
                    .coerceIn(0.05f, 1f)
                // Altitude: how far the pen is leaning. Upright is a tight dark point; leaned puts
                // the side of the lead down, so the mark spreads and softens.
                //
                // Mapped across the range a hand actually produces, not 0..64. The tilt vector's
                // magnitude measures roughly 34-72 in practice - nobody writes with the pen truly
                // vertical - so dividing by 64 squeezed every real stroke into the top half of the
                // scale, leaving the mark permanently near maximum spread and barely responding to
                // lean at all. Anchoring the range to observed values is what makes altitude read.
                val tiltMagnitude = hypot(a.tiltX.toFloat(), a.tiltY.toFloat())
                tiltSeenMin = min(tiltSeenMin, tiltMagnitude)
                tiltSeenMax = max(tiltSeenMax, tiltMagnitude)
                val altitude = ((tiltMagnitude - PENCIL_TILT_MIN) / (PENCIL_TILT_MAX - PENCIL_TILT_MIN))
                    .coerceIn(0f, 1f)
                // Azimuth: which way it leans, so the spread points the right way.
                val azimuth = atan2(a.tiltY.toFloat(), a.tiltX.toFloat())

                // Upright is a tight point; leaning spreads it along the lean, narrows it across,
                // and fades it - the same graphite doing a point or a broad shade.
                // Both axes grow with lean, the one along it far more - so the mark becomes a
                // bigger, increasingly elongated oval rather than a same-sized one squashed
                // sideways. Charcoal, which reads correctly, gets proportionally larger as it
                // tilts; shrinking `across` was working against that and made lean look like a
                // change of shape rather than of contact area.
                val sizeScale = 0.85f + press * 0.3f
                val along = baseRadius * sizeScale * (PENCIL_UPRIGHT_LENGTH + altitude * PENCIL_ALTITUDE_GAIN)
                val across = baseRadius * sizeScale *
                    (PENCIL_UPRIGHT_LENGTH + altitude * PENCIL_ALTITUDE_GAIN * PENCIL_ACROSS_RATIO)
                // The same graphite spread over a larger contact patch has to lay down lighter -
                // that fade is most of what makes a leaned pencil read as shading rather than as a
                // fat dark line.
                val coverage = (0.55f + press * 0.4f) * (1f - altitude * 0.5f)
                paint.alpha = (255f * coverage).toInt().coerceIn(6, 255)

                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(Math.toDegrees(azimuth.toDouble()).toFloat())
                dst.set(-along, -across, along, across)
                canvas.drawBitmap(mask, src, dst, paint)
                canvas.restore()
                t += step
            }
            carry = t - segLen
        }
        if (tiltSeenMax > 0f) {
            android.util.Log.d(
                "PencilNative",
                "pencil stamped: width=$widthPx tiltMagnitude=${"%.1f".format(tiltSeenMin)}..${"%.1f".format(tiltSeenMax)} " +
                    "(mapped over $PENCIL_TILT_MIN..$PENCIL_TILT_MAX)"
            )
        }
        return true
    }

    /**
     * Our own pencil: grain, pressure and tilt-direction side shading.
     *
     * The SDK's own pencil renderer is unreachable here - it needs classes (PathKt, MathUtils) that
     * moved packages between the pen AAR we have and the base AAR we have, and nothing ships the
     * names it asks for, so it dies on NoClassDefFoundError and silently falls through. The
     * fallback that caught it was a plain round-cap polyline, i.e. no grain, no tilt, no shading -
     * a featureless round brush wearing the pencil's name.
     *
     * What the real one does is recoverable from its own symbols: it bins the tilt azimuth into 36
     * buckets (MathUtils.normalizeAngleTo36) and stamps a correspondingly rotated brush mask. This
     * reproduces that idea directly - an elongated dab, oriented along the lean, widening as the
     * pen drops toward the page - which is the side shading a pencil is actually wanted for.
     */
    private fun renderPencilGrain(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        maxPressure: Float,
    ) {
        if (points.isEmpty()) return
        val safeMaxPressure = max(1f, maxPressure)
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val baseRadius = max(0.6f, widthPx * 0.5f)
        // Step along the path rather than per sample: sample density varies with pen speed, and
        // stamping per sample would make slow strokes darker purely for being slow.
        val step = max(0.7f, baseRadius * 0.28f)
        var carry = 0f
        var seed = 0x9E3779B9.toInt()

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val segLen = hypot(b.x - a.x, b.y - a.y)
            if (segLen <= 0.0001f) continue
            var t = carry
            while (t <= segLen) {
                val f = t / segLen
                val x = a.x + (b.x - a.x) * f
                val y = a.y + (b.y - a.y) * f

                val pressure = (a.pressure + (b.pressure - a.pressure) * f) / safeMaxPressure
                val press = pressure.coerceIn(0.05f, 1f)

                // Tilt vector: magnitude is how far the pen is leaning, atan2 its direction. This
                // is the azimuth the hardware never exposes via MotionEvent.AXIS_ORIENTATION.
                val tiltMag = (hypot(a.tiltX.toFloat(), a.tiltY.toFloat()) / 64f).coerceIn(0f, 1f)
                val azimuth = atan2(a.tiltY.toFloat(), a.tiltX.toFloat())

                // Leaning spreads the contact patch along the lean and lightens it, the way a
                // pencil shades with the side of the lead instead of the point.
                val along = baseRadius * (0.75f + press * 0.45f) * (1f + tiltMag * 2.4f)
                val across = baseRadius * (0.75f + press * 0.45f) * (1f - tiltMag * 0.35f)
                val coverage = (0.40f + press * 0.55f) * (1f - tiltMag * 0.35f)

                seed = seed * 1664525 + 1013904223
                val jitter = ((seed ushr 8) and 0xFF) / 255f
                paint.alpha = (255f * coverage * (0.55f + jitter * 0.45f)).toInt().coerceIn(8, 255)

                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(Math.toDegrees(azimuth.toDouble()).toFloat())
                // Graphite grain: a few offset dabs rather than one solid blob, so the stroke
                // breaks up over the texture of the page instead of reading as flat ink.
                var g = 0
                while (g < 3) {
                    seed = seed * 1664525 + 1013904223
                    val ox = (((seed ushr 4) and 0xFF) / 255f - 0.5f) * along * 0.55f
                    seed = seed * 1664525 + 1013904223
                    val oy = (((seed ushr 4) and 0xFF) / 255f - 0.5f) * across * 0.9f
                    canvas.drawOval(
                        ox - along * 0.5f, oy - across * 0.5f,
                        ox + along * 0.5f, oy + across * 0.5f,
                        paint
                    )
                    g++
                }
                canvas.restore()
                t += step
            }
            carry = t - segLen
        }
    }

    /**
     * NeoPencilPen is bundled in onyxsdk-pen-native-classes.jar (extracted from a Note-series
     * firmware, same as NeoCharcoalPen/NeoSquarePen below) but was never wired up - "Pencil" used
     * to be a plain solid polyline with no texture at all. NeoPencilPen extends NeoNativePen, the
     * same base class as NeoCharcoalPen, so this mirrors drawCharcoalWithHardwareLikeConfig's
     * onPenDown/onPenMove/onPenUp + readTextureResult pattern exactly.
     */
    private fun renderPencilNative(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        maxPressure: Float,
    ): Boolean {
        if (points.size < 2) return false
        val safeMaxPressure = max(1f, maxPressure)
        val screenMatrix = Matrix()
        val baseConfig = runCatching { NeoPencilPen.Companion.defaultPenConfig() }.getOrNull() ?: NeoPenConfig()
        val penConfig = baseConfig
            .setColor(color)
            .setWidth(widthPx)
            .setTiltEnabled(true)
            .setRotateAngle(0)
            .setMaxTouchPressure(safeMaxPressure)
        // pressureSensitivity and minWidth are deliberately left as defaultPenConfig() set them
        // (0.3 and 1.0). Forcing sensitivity to 1.0 - copied from the charcoal path, which builds a
        // bare config - makes stamp size track raw pressure almost entirely, so a normal press
        // (~0.4 of the 4096 ceiling) rendered an 80px pencil at roughly a third of its width and no
        // amount of widening helped. The pencil's own default keeps it near the configured width
        // and lets pressure modulate it rather than define it.
        // Tilt magnitude alone only broadens the stroke as the pen leans. This is what makes it
        // broaden *along the direction of the lean* - real side shading. The digitizer reports that
        // direction as a tilt vector on each TouchPoint (tiltX/tiltY, whose atan2 is the azimuth);
        // MotionEvent.AXIS_ORIENTATION reads a flat zero on this hardware, which is why azimuth
        // looked like it wasn't detected at all. The data was always arriving - the engine was
        // simply never told to use it, leaving tiltEnabled doing half the job.
        // directionEnabled/tiltScale are deliberately NOT set here any more, matching the charcoal
        // config - which is the one brush whose tilt shading behaves correctly. Charcoal sets only
        // colour, width, tiltEnabled, rotateAngle and maxTouchPressure on a bare config and leaves
        // everything else alone; pencil setting tiltScale to TILT_SCALE_VALUE (5.0) on top of that
        // was the main divergence between the two, and pencil is the one whose stamps came out
        // pinned near minimum size. Keeping the working brush's recipe is a better starting point
        // than a value we only guessed was right because a constant of that name existed.

        val pen: NeoPen = NeoPencilPen.Companion.create(penConfig) ?: return false

        val bitmaps = ArrayList<Bitmap>(512)
        return try {
            val mapped = NeoPenUtils.mapToPenCanvas(points, screenMatrix)
            if (mapped.size < 2) return false
            // Pressure is handed over raw. It used to be divided by safeMaxPressure here *as well
            // as* being declared via setMaxTouchPressure(safeMaxPressure), so the engine normalised
            // an already-normalised value: points arriving at ~0.4 were presented as 0.0001 of a
            // 4096 ceiling, i.e. no pressure at all, and every stamp came out at minimum size. That
            // is the pencil rendering "oddly small" however wide the brush was set, and the chip -
            // which sees the real pressures - disagreeing with the bake.
            // The azimuth only reaches the engine if the tilt vector survives mapToPenCanvas -
            // worth stating outright rather than assuming, since a mapping that rebuilt its points
            // would silently drop it and leave direction shading doing nothing at all.
            val tiltCarrying = mapped.count { it.tiltX != 0 || it.tiltY != 0 }
            android.util.Log.d(
                "PencilNative",
                "pencil native: pts=${mapped.size} withTilt=$tiltCarrying " +
                    "direction=${penConfig.directionEnabled} tiltScale=${penConfig.tiltScale} " +
                    "width=$widthPx maxP=$safeMaxPressure"
            )

            // PencilNeoPenRender is the SDK's own pencil renderer, and it is what turns the pen's
            // output into the brush-mask stamps that give pencil its grain and its tilt/direction
            // shading. The generic onPenDown/readTextureResult route below is the charcoal-style
            // texture path; driving the pencil through it returned renderPoints=0 bitmaps=0 on
            // every single stroke, so nothing was drawn and renderPencil quietly fell back to a
            // plain polyline - which is why "pencil" looked like a featureless round brush.
            val pencilDrawn = runCatching {
                @Suppress("UNCHECKED_CAST")
                val basePoints = mapped as List<com.onyx.android.sdk.base.data.TouchPoint>
                PencilNeoPenRender(pen as NeoPencilPen).render(canvas, solidPaint(color), basePoints)
                true
            }.onFailure {
                android.util.Log.w(
                    "PencilNative",
                    "PencilNeoPenRender failed: ${it.javaClass.simpleName}: ${it.message}"
                )
            }.getOrDefault(false)
            if (pencilDrawn) return true

            val renderPoints = ArrayList<NeoRenderPoint>(mapped.size * 3)
            invokePenDown(pen, mapped[0])?.let { NeoPenUtils.readTextureResult(it, bitmaps, renderPoints) }
            if (mapped.size > 2) {
                invokePenMove(pen, mapped.subList(1, mapped.size - 1))
                    ?.let { NeoPenUtils.readTextureResult(it, bitmaps, renderPoints) }
            }
            invokePenUp(pen, mapped[mapped.size - 1])?.let { NeoPenUtils.readTextureResult(it, bitmaps, renderPoints) }
            // Whether the engine produced any texture at all. Returning false below drops through
            // to renderPencil's fallback, which is a plain round-cap polyline - no grain, no tilt,
            // i.e. exactly the "MS Paint brush" look - so a silent bail here is indistinguishable
            // from the native path simply rendering badly unless it says so.
            android.util.Log.d(
                "PencilNative",
                "pencil result: renderPoints=${renderPoints.size} bitmaps=${bitmaps.size} " +
                    "dpi=${penConfig.dpi} scaleX=${penConfig.displayScaleX} scaleY=${penConfig.displayScaleY} " +
                    "shape=${penConfig.brushShape} spacing=${penConfig.brushSpacing}"
            )
            if (renderPoints.isEmpty() || bitmaps.isEmpty()) return false

            val inverse = Matrix()
            val mappedRenderPoints = if (screenMatrix.invert(inverse)) {
                NeoPenUtils.mapFromPenCanvas(renderPoints.toTypedArray(), bitmaps, inverse)
            } else {
                renderPoints.toTypedArray()
            }

            val tiltPaint = leanFadePaint(points)
            for (rp in mappedRenderPoints) {
                val idx = rp.bitmapIndex
                if (idx in 0 until bitmaps.size) {
                    canvas.drawBitmap(bitmaps[idx], rp.x, rp.y, tiltPaint)
                }
            }
            true
        } finally {
            runCatching { pen.destroy() }
            bitmaps.forEach { bmp ->
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
            }
        }
    }

    // ─── FOUNTAIN: NeoFountainPenWrapper ──────────────────────────────────────

    private fun renderFountain(points: List<TouchPoint>, widthPx: Float, color: Int, canvas: Canvas, maxPressure: Float) {
        val paint = solidPaint(color)
        val pts = points.toArrayList()
        try {
            val pressureDivisor = nativePressureDivisor(pts, maxPressure)
            // Signature: (points, displayScale, strokeWidth, maxTouchPressure)
            // The wrapper divides each point.pressure by maxTouchPressure internally.
            val result = NeoFountainPenWrapper.computeStrokePoints(pts, 1f, widthPx, pressureDivisor)
            com.onyx.android.sdk.pen.PenUtils.drawStrokeByPointSize(canvas, paint, result, false)
        } catch (_: Throwable) {
            fallbackPressureStroke(points, widthPx, color, canvas, HardwarePenStyle.FOUNTAIN, maxPressure)
        }
    }

    // ─── MARKER: NeoMarkerPenWrapper, 50 % alpha offscreen composite ──────────

    private fun renderMarker(points: List<TouchPoint>, widthPx: Float, color: Int, canvas: Canvas, maxPressure: Float) {
        android.util.Log.d("MarkerTest", "renderMarker: ${points.size} pts width=$widthPx")
        if (points.isEmpty()) return
        val pts = points.toArrayList()
        val paint = solidPaint(color).apply {
            strokeWidth = widthPx; isAntiAlias = true
            style = Paint.Style.FILL_AND_STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        try {
            // Try the native wrapper — libneo_pen.so is now bundled in jniLibs/arm64-v8a
            val result = NeoMarkerPenWrapper.computeStrokePoints(pts, widthPx, maxPressure)
            android.util.Log.d("MarkerTest", "native OK: ${result?.size} result pts")
            NeoMarkerPenWrapper.drawStroke(canvas, paint, result, widthPx, false)
        } catch (e: Throwable) {
            // Native still fails for some reason — use pure-Kotlin replica
            android.util.Log.w("MarkerTest", "native failed (${e.javaClass.simpleName}), using fallback")
            renderMarkerFallback(pts, widthPx, paint, canvas)
        }
    }

    /** Pure-Kotlin replica of NeoMarkerPenWrapper.drawStroke — no native library needed. */
    private fun renderMarkerFallback(pts: ArrayList<TouchPoint>, widthPx: Float, paint: Paint, canvas: Canvas) {
        // Set TouchPoint.size based on pressure (what the native engine computes)
        val stats = signalStats(pts)
        for (p in pts) {
            p.size = widthPx * (0.4f + 0.6f * normalizedSignal(p, stats))
        }
        // Draw into offscreen bitmap at 100% opacity
        val bmp = android.graphics.Bitmap.createBitmap(canvas.width, canvas.height, android.graphics.Bitmap.Config.ARGB_8888)
        val bmpCanvas = Canvas(bmp)
        com.onyx.android.sdk.pen.PenUtils.drawStrokeByPointSize(bmpCanvas, paint, pts, false)
        // Compute bounding rect and inset by half stroke-width (same as NeoMarkerPenWrapper)
        var rect: android.graphics.Rect? = null
        for (p in pts) {
            if (rect == null) rect = android.graphics.Rect(p.x.toInt(), p.y.toInt(), p.x.toInt(), p.y.toInt())
            else rect.union(p.x.toInt(), p.y.toInt())
        }
        if (rect == null) { bmp.recycle(); return }
        rect.inset(-(widthPx / 2f).toInt(), -(widthPx / 2f).toInt())
        // Composite at alpha=128 (50%) — exactly like NeoMarkerPenWrapper.drawStroke
        val savedAlpha = paint.alpha
        paint.alpha = 128
        canvas.drawBitmap(bmp, rect, rect, paint)
        paint.alpha = savedAlpha
        bmp.recycle()
        android.util.Log.d("MarkerTest", "fallback done")
    }

    // ─── NEO_BRUSH: NeoBrushPenWrapper ───────────────────────────────────────

    private fun renderNeoBrush(points: List<TouchPoint>, widthPx: Float, color: Int, canvas: Canvas, maxPressure: Float) {
        val pts = points.toArrayList()
        val paint = solidPaint(color)
        try {
            val pressureDivisor = nativePressureDivisor(pts, maxPressure)
            val result = NeoBrushPenWrapper.computeStrokePoints(pts, widthPx, pressureDivisor)
            if (!result.isNullOrEmpty()) {
                com.onyx.android.sdk.pen.PenUtils.drawStrokeByPointSize(canvas, paint, result, false)
            } else {
                fallbackPressureStroke(points, widthPx, color, canvas, HardwarePenStyle.NEO_BRUSH, maxPressure)
            }
        } catch (_: Throwable) {
            fallbackPressureStroke(points, widthPx, color, canvas, HardwarePenStyle.NEO_BRUSH, maxPressure)
        }
    }

    // ─── CHARCOAL / CHARCOAL_V2 ──────────────────────────────────────────────
    //
    // Uses native wrappers:
    //  - V1: NeoCharcoalPenWrapper.drawNormalStroke
    //  - V2: NeoCharcoalPenV2Wrapper.drawNormalStroke
    // A GC hint before each call encourages the JVM to collect Bitmap / PenResult
    // objects from the previous stroke before allocating new native stamps, preventing
    // heap exhaustion across many strokes.
    // Kotlin cloud-texture path is retained only as fallback.

    private fun renderCharcoal(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        v2: Boolean,
        maxPressure: Float,
    ) {
        if (points.size < 2) { charcoalCloudTexture(points, widthPx, color, canvas, v2); return }

        // NOTE: this used to force System.gc() here "to prevent heap exhaustion across many
        // strokes." A blocking full GC before every single charcoal stroke is a visible pause
        // (tens of ms on the calling thread) and is very likely why charcoal reads as laggy/
        // non-native compared to the other brushes. drawCharcoalWithHardwareLikeConfig already
        // recycles its stamp bitmaps explicitly in a finally block, so this shouldn't be needed;
        // if a real leak resurfaces, fix the leak rather than reintroducing a synchronous GC here.

        val prepared = prepareCharcoalPoints(points, maxPressure)
        val pressureInfo = prepared.second
        val penType = if (v2) {
            com.onyx.android.sdk.pen.NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL_V2
        } else {
            com.onyx.android.sdk.pen.NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL
        }
        val createArgs = com.onyx.android.sdk.data.note.ShapeCreateArgs()
            .setMaxPressure(pressureInfo.renderMaxPressure)

        val args = com.onyx.android.sdk.pen.PenRenderArgs()
            .setCanvas(canvas)
            .setPoints(prepared.first)
            .setStrokeWidth(widthPx)
            .setColor(color)
            .setContentRect(RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()))
            .setPenType(penType)
            .setScreenMatrix(android.graphics.Matrix())
            .setRenderMatrix(android.graphics.Matrix())
            .setTiltEnabled(true)
            .setErase(false)
            .setCreateArgs(createArgs)

        android.util.Log.d(
            "CharcoalTest",
            "v${if (v2) 2 else 1} pts=${prepared.first.size} pressure=[${"%.3f".format(pressureInfo.minPressure)},${"%.3f".format(pressureInfo.maxPressure)}] " +
                "renderMax=${"%.3f".format(pressureInfo.renderMaxPressure)} normalized=${pressureInfo.normalizedInput} " +
                charcoalTiltSummary(prepared.first)
        )

        runCatching {
            // Hardware preview path uses stronger pressure/min-width defaults than wrapper defaults.
            // Match those config values first; fallback to SDK wrapper if the custom path fails.
            val customDrawn = drawCharcoalWithHardwareLikeConfig(
                points = prepared.first,
                widthPx = widthPx,
                color = color,
                canvas = canvas,
                maxPressure = pressureInfo.renderMaxPressure,
                v2 = v2,
            )
            if (!customDrawn) {
                if (v2) {
                    com.onyx.android.sdk.pen.NeoCharcoalPenV2Wrapper.drawNormalStroke(args)
                } else {
                    com.onyx.android.sdk.pen.NeoCharcoalPenWrapper.drawNormalStroke(args)
                }
            }
        }.onFailure { e ->
            android.util.Log.w("CharcoalTest", "v${if (v2) 2 else 1} drawNormalStroke failed: ${e.message}")
            charcoalCloudTexture(points, widthPx, color, canvas, v2)
        }

        android.util.Log.d("CharcoalTest", "v${if (v2) 2 else 1} drawNormalStroke pts=${points.size}")
    }

    /**
     * Replica of the charcoal wrapper call chain with explicit pen config tuning to match
     * hardware-preview output (pressure response + minimum stamp width).
     */
    private fun drawCharcoalWithHardwareLikeConfig(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        maxPressure: Float,
        v2: Boolean,
    ): Boolean {
        if (points.size < 2) return false
        val safeMaxPressure = max(1f, maxPressure)
        val screenMatrix = Matrix()
        val penConfig = NeoPenConfig()
            .setColor(color)
            .setWidth(widthPx)
            .setTiltEnabled(true)
            .setRotateAngle(0)
            .setMaxTouchPressure(safeMaxPressure)
        penConfig.pressureSensitivity = 1.0f
        penConfig.minWidth = 1.0f

        val pen: NeoPen = if (v2) {
            NeoCharcoalPenV2.Companion.create(penConfig)
        } else {
            NeoCharcoalPen.Companion.create(penConfig)
        } ?: return false

        val bitmaps = ArrayList<Bitmap>(512)
        return try {
            val mapped = NeoPenUtils.mapToPenCanvas(points, screenMatrix)
            if (mapped.size < 2) return false
            // Pressure is handed over raw. It used to be divided by safeMaxPressure here *as well
            // as* being declared via setMaxTouchPressure(safeMaxPressure), so the engine normalised
            // an already-normalised value: points arriving at ~0.4 were presented as 0.0001 of a
            // 4096 ceiling, i.e. no pressure at all, and every stamp came out at minimum size. That
            // is the pencil rendering "oddly small" however wide the brush was set, and the chip -
            // which sees the real pressures - disagreeing with the bake.

            val renderPoints = ArrayList<NeoRenderPoint>(mapped.size * 3)
            invokePenDown(pen, mapped[0])?.let { NeoPenUtils.readTextureResult(it, bitmaps, renderPoints) }
            if (mapped.size > 2) {
                invokePenMove(pen, mapped.subList(1, mapped.size - 1))
                    ?.let { NeoPenUtils.readTextureResult(it, bitmaps, renderPoints) }
            }
            invokePenUp(pen, mapped[mapped.size - 1])?.let { NeoPenUtils.readTextureResult(it, bitmaps, renderPoints) }
            if (renderPoints.isEmpty() || bitmaps.isEmpty()) return false

            val inverse = Matrix()
            val mappedRenderPoints = if (screenMatrix.invert(inverse)) {
                NeoPenUtils.mapFromPenCanvas(renderPoints.toTypedArray(), bitmaps, inverse)
            } else {
                renderPoints.toTypedArray()
            }

            val tiltPaint = leanFadePaint(points)
            for (rp in mappedRenderPoints) {
                val idx = rp.bitmapIndex
                if (idx in 0 until bitmaps.size) {
                    canvas.drawBitmap(bitmaps[idx], rp.x, rp.y, tiltPaint)
                }
            }
            true
        } finally {
            runCatching { pen.destroy() }
            bitmaps.forEach { bmp ->
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokePenDown(
        pen: NeoPen,
        point: TouchPoint,
    ): Pair<PenResult?, PenResult?>? = runCatching {
        val basePoint = Class.forName("com.onyx.android.sdk.base.data.TouchPoint")
        val fn = pen.javaClass.getMethod("onPenDown", basePoint, Boolean::class.javaPrimitiveType)
        fn.invoke(pen, point, true) as? Pair<PenResult?, PenResult?>
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun invokePenMove(
        pen: NeoPen,
        points: List<TouchPoint>,
    ): Pair<PenResult?, PenResult?>? = runCatching {
        val basePoint = Class.forName("com.onyx.android.sdk.base.data.TouchPoint")
        val fn = pen.javaClass.getMethod("onPenMove", List::class.java, basePoint, Boolean::class.javaPrimitiveType)
        fn.invoke(pen, points, null, true) as? Pair<PenResult?, PenResult?>
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun invokePenUp(
        pen: NeoPen,
        point: TouchPoint,
    ): Pair<PenResult?, PenResult?>? = runCatching {
        val basePoint = Class.forName("com.onyx.android.sdk.base.data.TouchPoint")
        val fn = pen.javaClass.getMethod("onPenUp", basePoint, Boolean::class.javaPrimitiveType)
        fn.invoke(pen, point, true) as? Pair<PenResult?, PenResult?>
    }.getOrNull()

    private fun renderPenResultPairs(
        results: List<Pair<PenResult?, PenResult?>>,
        canvas: Canvas,
        paint: Paint,
    ) {
        for (pair in results) {
            pair.first?.draw(canvas, paint)
        }
        results.lastOrNull()?.second?.draw(canvas, paint)
    }

    private fun clearPenResultPairCache(results: List<Pair<PenResult?, PenResult?>>) {
        for (pair in results) {
            pair.first?.clearCache()
            pair.second?.clearCache()
        }
    }

    private data class PressureInfo(
        val minPressure: Float,
        val maxPressure: Float,
        val renderMaxPressure: Float,
        val normalizedInput: Boolean,
    )

    private fun charcoalTiltSummary(points: List<TouchPoint>): String {
        if (points.isEmpty()) return "tilt=none"
        var minTx = Int.MAX_VALUE
        var maxTx = Int.MIN_VALUE
        var minTy = Int.MAX_VALUE
        var maxTy = Int.MIN_VALUE
        var nonZero = 0
        for (p in points) {
            minTx = min(minTx, p.tiltX)
            maxTx = max(maxTx, p.tiltX)
            minTy = min(minTy, p.tiltY)
            maxTy = max(maxTy, p.tiltY)
            if (p.tiltX != 0 || p.tiltY != 0) nonZero++
        }
        return "tiltX=[$minTx,$maxTx] tiltY=[$minTy,$maxTy] nz=$nonZero"
    }

    /**
     * RawInput callbacks can deliver pressure in mixed units (0..1 or 0..MAX_TOUCH_PRESSURE).
     * The native charcoal wrapper always divides by createArgs.maxPressure, so we normalize
     * max-pressure per stroke to keep output consistent and avoid invisible strokes.
     */
    private fun prepareCharcoalPoints(points: List<TouchPoint>, deviceMaxPressure: Float): Pair<ArrayList<TouchPoint>, PressureInfo> {
        val out = ArrayList<TouchPoint>(points.size)
        var minP = Float.MAX_VALUE
        var maxP = 0f
        var hasPressure = false
        for (p in points) {
            val copy = TouchPoint(p)
            out.add(copy)
            if (copy.pressure > 0f) {
                hasPressure = true
                minP = min(minP, copy.pressure)
                maxP = max(maxP, copy.pressure)
            }
        }

        if (!hasPressure) {
            // Keep behavior deterministic even when pressure is missing entirely.
            val fallback = 0.35f
            for (p in out) p.pressure = fallback
            return out to PressureInfo(
                minPressure = fallback,
                maxPressure = fallback,
                renderMaxPressure = 1f,
                normalizedInput = true,
            )
        }

        val normalizedInput = maxP <= 1.5f
        val renderMax = if (normalizedInput) 1f else max(deviceMaxPressure, maxP)
        return out to PressureInfo(
            minPressure = minP,
            maxPressure = maxP,
            renderMaxPressure = renderMax,
            normalizedInput = normalizedInput,
        )
    }

    /**
     * Native pen wrappers normalize by dividing point.pressure by this divisor.
     * RawInput can deliver either already-normalized [0..1] or raw [0..MAX] pressure.
     */
    private fun nativePressureDivisor(points: List<TouchPoint>, deviceMaxPressure: Float): Float {
        var maxP = 0f
        for (p in points) {
            if (p.pressure > 0f) maxP = max(maxP, p.pressure)
        }
        if (maxP <= 0f) return 1f
        return if (maxP <= 1.5f) 1f else max(deviceMaxPressure, maxP)
    }

    /** Pure-Java fallback: ring + dot cloud stamps along the stroke path. */
    private fun charcoalCloudTexture(points: List<TouchPoint>, widthPx: Float, color: Int, canvas: Canvas, v2: Boolean) {
        val paint = Paint().apply {
            this.color = withAlpha(color, 160) // Proper charcoal opacity layer
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }
        val stats = signalStats(points)
        if (points.size == 1) {
            val p = points[0]
            charcoalStamp(p.x, p.y, widthPx, normalizedSignal(p, stats), 0, canvas, paint, v2)
            return
        }
        var prev = points[0]
        for (i in 1 until points.size) {
            val curr = points[i]
            val ps = normalizedSignal(prev, stats); val cs = normalizedSignal(curr, stats)
            val dx = curr.x - prev.x; val dy = curr.y - prev.y
            val dist = sqrt(dx * dx + dy * dy)
            val step = if (v2) 8.5f else 9f
            val n = max(1, ceil(dist / step).toInt())
            for (s in 0..n) {
                val t = s.toFloat() / n
                charcoalStamp(prev.x + dx * t, prev.y + dy * t, widthPx, lerp(ps, cs, t), i * 8192 + s, canvas, paint, v2)
            }
            prev = curr
        }
    }

    private fun charcoalStamp(cx: Float, cy: Float, baseW: Float, sig: Float, seed: Int, canvas: Canvas, paint: Paint, v2: Boolean) {
        val outerR = max(0.8f, baseW * if (v2) 0.68f else 0.58f)
        val innerR = outerR * if (v2) 0.9f else 0.88f
        val edgeD = if (v2) lerp(0.0015f, 0.005f, sig) else lerp(0.002f, 0.006f, sig)
        val dotD = if (v2) lerp(0.001f, 0.0035f, sig) else lerp(0.0014f, 0.004f, sig)
        dotCloud(cx, cy, innerR, outerR, edgeD, seed, canvas, paint)
        dotCloud(cx, cy, 0f, outerR * 0.72f, dotD, seed + 97, canvas, paint)
        if (v2) dotCloud(cx, cy, outerR * 1.02f, outerR * 1.22f, lerp(0.001f, 0.003f, sig), seed + 211, canvas, paint)
    }

    private fun dotCloud(cx: Float, cy: Float, innerR: Float, outerR: Float, density: Float, seed: Int, canvas: Canvas, paint: Paint) {
        val n = max(1, (outerR * outerR * density * 0.1f).toInt())
        val innerRatioSq = if (outerR > 0) (innerR / outerR).pow(2) else 0f
        var drawn = 0; var attempt = 0
        while (drawn < n && attempt < n * 3) {
            val rx = hashUnit(seed, attempt * 2) * 2f - 1f
            val ry = hashUnit(seed, attempt * 2 + 1) * 2f - 1f
            val dSq = rx * rx + ry * ry
            if (dSq <= 1f && dSq >= innerRatioSq) { canvas.drawPoint(cx + rx * outerR, cy + ry * outerR, paint); drawn++ }
            attempt++
        }
    }

    // ─── DASH: black+white dashed track (matches Onyx overlay style) ─────────

    private fun renderDash(points: List<TouchPoint>, widthPx: Float, color: Int, canvas: Canvas) {
        if (points.isEmpty()) return

        val dashPeriod = max(2f, widthPx * 3f)
        val phase = 0f
        val offset = max(0.5f, widthPx / 3f)

        val basePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = widthPx
            isAntiAlias = true
        }
        val whiteDash = Paint(basePaint).apply {
            this.color = Color.WHITE
            pathEffect = DashPathEffect(
                floatArrayOf(max(1f, dashPeriod - offset), dashPeriod + offset),
                phase,
            )
        }
        val blackDash = Paint(basePaint).apply {
            this.color = color
            pathEffect = DashPathEffect(floatArrayOf(dashPeriod, dashPeriod), phase)
        }

        if (points.size == 1) {
            val p = points[0]
            val radius = max(0.75f, widthPx * 0.5f)
            val whiteDot = Paint(basePaint).apply {
                style = Paint.Style.FILL
                this.color = Color.WHITE
                pathEffect = null
            }
            val blackDot = Paint(basePaint).apply {
                style = Paint.Style.FILL
                this.color = color
                pathEffect = null
            }
            canvas.drawCircle(p.x + offset, p.y + offset, radius, whiteDot)
            canvas.drawCircle(p.x, p.y, radius, blackDot)
            return
        }

        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        canvas.save()
        canvas.translate(offset, offset)
        canvas.drawPath(path, whiteDash)
        canvas.restore()
        canvas.drawPath(path, blackDash)
    }

    // ─── SQUARE_PEN: NeoSquarePen (reference path from neo-reader) ──────────

    /**
     * Matches neo-reader/knote software replay:
     *  - NeoSquarePen default config
     *  - width = strokeWidth * 2
     *  - brushRatio = min(strokeWidth, 10)
     *  - brushAngle = 45°
     */
    private fun renderSquarePen(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        maxPressure: Float,
    ) {
        if (points.isEmpty()) return
        val rendered = runCatching {
            renderSquarePenNative(points, widthPx, color, canvas, maxPressure)
        }.getOrDefault(false)
        if (!rendered) {
            renderSquarePenFallback(points, widthPx, color, canvas)
        }
    }

    private fun renderSquarePenNative(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        maxPressure: Float,
    ): Boolean {
        if (points.size < 2) {
            val paint = solidPaint(color).apply { style = Paint.Style.FILL }
            points.firstOrNull()?.let { canvas.drawCircle(it.x, it.y, widthPx / 2f, paint) }
            return true
        }

        val mapped = points.toArrayList()
        val pressureDivisor = nativePressureDivisor(mapped, maxPressure)
        for (p in mapped) {
            val normalized = if (pressureDivisor <= 1.5f) p.pressure else (p.pressure / pressureDivisor)
            p.pressure = normalized.coerceIn(0.01f, 1f)
        }

        val config = NeoSquarePen.Companion.defaultPenConfig()
            .setColor(color)
            .setWidth(widthPx * 2f)
            .setRotateAngle(0)
            .setMaxTouchPressure(1f)
            .setTiltEnabled(true)
        config.brushAngle = 45f
        config.brushRatio = min(widthPx, 10f)
        config.scalePrecision = 1f
        config.displayScaleX = 1f
        config.displayScaleY = 1f

        val pen = NeoSquarePen.Companion.create(config) ?: return false
        val results = ArrayList<Pair<PenResult?, PenResult?>>(4)
        return try {
            invokePenDown(pen, mapped.first())?.let(results::add)
            if (mapped.size > 2) {
                invokePenMove(pen, mapped.subList(1, mapped.size - 1))?.let(results::add)
            }
            invokePenUp(pen, mapped.last())?.let(results::add)
            if (results.isEmpty()) return false

            val paint = solidPaint(color).apply {
                style = Paint.Style.FILL
                strokeWidth = 0f
            }
            renderPenResultPairs(results, canvas, paint)
            clearPenResultPairCache(results)
            true
        } finally {
            runCatching { pen.destroy() }
        }
    }

    /**
     * Legacy approximation retained only if NeoSquarePen is unavailable.
     */
    private fun renderSquarePenFallback(points: List<TouchPoint>, widthPx: Float, color: Int, canvas: Canvas) {
        if (points.size < 2) {
            val paint = solidPaint(color).apply { style = Paint.Style.FILL }
            points.firstOrNull()?.let { canvas.drawCircle(it.x, it.y, widthPx / 2f, paint) }
            return
        }
        val nibAngle = (PI / 4.0).toFloat()
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            isAntiAlias = true
        }
        var prev = points[0]
        for (i in 1 until points.size) {
            val curr = points[i]
            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            if (abs(dx) < 0.01f && abs(dy) < 0.01f) {
                prev = curr
                continue
            }
            val angle = atan2(dy, dx)
            val factor = max(0.12f, abs(sin(angle - nibAngle)))
            paint.strokeWidth = widthPx * factor
            canvas.drawLine(prev.x, prev.y, curr.x, curr.y, paint)
            prev = curr
        }
    }

    // ─── Shared utilities ─────────────────────────────────────────────────────

    private fun drawPolyline(points: List<TouchPoint>, canvas: Canvas, paint: Paint) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            val p = points[0]; canvas.drawPoint(p.x, p.y, paint); return
        }
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        canvas.drawPath(path, paint)
    }

    /** Pressure-varying filled-circle fallback used when SDK wrappers are unavailable. */
    private fun fallbackPressureStroke(
        points: List<TouchPoint>,
        widthPx: Float,
        color: Int,
        canvas: Canvas,
        penStyle: HardwarePenStyle,
        maxPressure: Float,
    ) {
        val paint = solidPaint(color).apply { style = Paint.Style.FILL }
        val pressureDivisor = nativePressureDivisor(points, maxPressure)
        if (points.size == 1) {
            val p = points[0]
            canvas.drawCircle(p.x, p.y, max(0.5f, pressureToRadius(widthPx, normalizedSignalAbsolute(p, pressureDivisor), penStyle)), paint)
            return
        }
        var prev = points[0]
        for (i in 1 until points.size) {
            val curr = points[i]
            val pr = pressureToRadius(widthPx, normalizedSignalAbsolute(prev, pressureDivisor), penStyle)
            val cr = pressureToRadius(widthPx, normalizedSignalAbsolute(curr, pressureDivisor), penStyle)
            interpolateCircles(prev, curr, pr, cr, canvas, paint)
            prev = curr
        }
    }

    private fun interpolateCircles(start: TouchPoint, end: TouchPoint, sr: Float, er: Float, canvas: Canvas, paint: Paint) {
        val dx = end.x - start.x; val dy = end.y - start.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= 0.001f) { canvas.drawCircle(start.x, start.y, max(0.5f, sr), paint); return }
        val steps = max(1, ceil(dist / 0.8f).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            canvas.drawCircle(start.x + dx * t, start.y + dy * t, max(0.5f, lerp(sr, er, t)), paint)
        }
    }

    private fun solidPaint(color: Int) = Paint().apply {
        this.color = color
        isAntiAlias = true
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun pointBounds(points: List<TouchPoint>, padding: Float): Rect {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) { minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y) }
        val pad = ceil(padding).toInt() + 4
        return Rect(floor(minX).toInt() - pad, floor(minY).toInt() - pad, ceil(maxX).toInt() + pad, ceil(maxY).toInt() + pad)
    }

    private fun List<TouchPoint>.toArrayList(): ArrayList<TouchPoint> {
        val list = ArrayList<TouchPoint>(size)
        forEach { list.add(TouchPoint(it)) }
        return list
    }

    // ─── Signal / pressure helpers ────────────────────────────────────────────

    private data class SignalStats(val minP: Float, val maxP: Float, val hasP: Boolean)

    private fun signalStats(points: List<TouchPoint>): SignalStats {
        var minP = Float.MAX_VALUE; var maxP = 0f; var hasP = false
        for (p in points) {
            if (p.pressure > 0f) { minP = min(minP, p.pressure); maxP = max(maxP, p.pressure); hasP = true }
        }
        return SignalStats(if (hasP) minP else 0f, if (hasP) maxP else 0f, hasP)
    }

    private fun normalizedSignal(pt: TouchPoint, stats: SignalStats): Float {
        if (!stats.hasP || pt.pressure <= 0f) return 0.35f
        val norm = if (stats.maxP <= 1.05f) pt.pressure.coerceIn(0.01f, 1f)
                   else (pt.pressure / stats.maxP).coerceIn(0.01f, 1f)
        return norm.pow(0.85f).coerceIn(0.01f, 1f)
    }

    /**
     * Absolute pressure normalization (vs per-stroke relative), used by fallback
     * paths to avoid width spikes on short low-pressure strokes.
     */
    private fun normalizedSignalAbsolute(pt: TouchPoint, pressureDivisor: Float): Float {
        if (pt.pressure <= 0f) return 0.35f
        val norm = if (pressureDivisor <= 1.5f) {
            pt.pressure.coerceIn(0.01f, 1f)
        } else {
            (pt.pressure / pressureDivisor).coerceIn(0.01f, 1f)
        }
        return norm.pow(0.85f).coerceIn(0.01f, 1f)
    }

    private fun pressureToRadius(baseWidth: Float, signal: Float, style: HardwarePenStyle): Float {
        val (curve, minF, maxF) = when (style) {
            HardwarePenStyle.PENCIL -> Triple(0.5f, 0.12f, 1.0f)
            HardwarePenStyle.FOUNTAIN -> Triple(0.5f, 0.10f, 1.05f)
            HardwarePenStyle.NEO_BRUSH -> Triple(0.30f, 0.06f, 2.2f)
            HardwarePenStyle.CHARCOAL -> Triple(0.44f, 0.24f, 2.45f)
            HardwarePenStyle.CHARCOAL_V2 -> Triple(0.42f, 0.34f, 2.95f)
            else -> Triple(0.5f, 0.20f, 1.0f)
        }
        return max(1f, lerp(baseWidth * minF, baseWidth * maxF, signal.coerceIn(0f, 1f).pow(curve)))
    }

    // ─── Math helpers ─────────────────────────────────────────────────────────

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Deterministic pseudo-random float in [0, 1) — same inputs → same dot positions. */
    private fun hashUnit(seed: Int, salt: Int): Float {
        var v = seed * 1103515245 + 12345 + salt * 374761393
        v = v xor (v ushr 16); v *= 668265263; v = v xor (v ushr 15)
        return (v ushr 1).toUInt().toFloat() / Int.MAX_VALUE.toFloat()
    }
}
