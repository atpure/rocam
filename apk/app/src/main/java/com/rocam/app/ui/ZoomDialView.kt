package com.rocam.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

class ZoomDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnZoomChangeListener {
        fun onZoomChanged(zoomRatio: Float)
        fun onZoomDragStart()
        fun onZoomDragEnd()
    }

    var listener: OnZoomChangeListener? = null
    var onDialVisibilityChange: ((Boolean) -> Unit)? = null

    private var currentZoom: Float = 1.0f
    private val minZoom = 0.5f
    private val maxZoom = 10.0f

    private var uiRotation: Float = 0f
    private var deviceRotation: Int = Surface.ROTATION_0
    private var rotationAnimator: ValueAnimator? = null

    // Paints
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }

    private val tickNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = dpToPx(11f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val centerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FACC15") // Apple Camera signature warm yellow accent
        textSize = dpToPx(20f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dpToPx(4f), 0f, dpToPx(2f), Color.argb(180, 0, 0, 0))
    }

    private val centerPointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FACC15")
        style = Paint.Style.FILL
        setShadowLayer(dpToPx(3f), 0f, dpToPx(1.5f), Color.argb(180, 0, 0, 0))
    }

    private val pointerPath = Path()

    // Precomputed tick values (0.5x to 10.0x)
    private val tickValues = mutableListOf<Float>()
    private val majorValues = setOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { fadeOut() }

    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var dragStartZoom = 1.0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var lastHapticTickIndex = -1

    var isDialExplicitlyOpened = false
        private set

    init {
        // Build tick values
        // 0.5 to 2.0 step 0.1
        var v = 0.5f
        while (v <= 2.05f) {
            tickValues.add(Math.round(v * 10f) / 10f)
            v += 0.1f
        }
        // 2.0 to 5.0 step 0.5
        v = 2.5f
        while (v <= 5.05f) {
            tickValues.add(Math.round(v * 10f) / 10f)
            v += 0.5f
        }
        // 5.0 to 10.0 step 1.0
        v = 6.0f
        while (v <= 10.05f) {
            tickValues.add(Math.round(v * 10f) / 10f)
            v += 1.0f
        }

        isDialExplicitlyOpened = false
        visibility = GONE
        alpha = 0f
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        isDialExplicitlyOpened = false
        visibility = GONE
        alpha = 0f
    }

    fun setUiRotation(rotation: Int, angle: Float) {
        deviceRotation = rotation
        if (uiRotation == angle) return

        rotationAnimator?.cancel()
        val startAngle = uiRotation
        var diff = angle - startAngle
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        val endAngle = startAngle + diff

        val anim = ValueAnimator.ofFloat(startAngle, endAngle).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                uiRotation = (it.animatedValue as Float) % 360f
                if (visibility == VISIBLE) {
                    invalidate()
                }
            }
        }
        rotationAnimator = anim
        anim.start()
    }

    fun setZoom(ratio: Float) {
        val clamped = (Math.round(ratio * 10f) / 10f).coerceIn(minZoom, maxZoom)
        if (clamped != currentZoom) {
            currentZoom = clamped
            if (isDialExplicitlyOpened && visibility == VISIBLE) {
                invalidate()
            }
        }
    }

    fun getZoom(): Float = currentZoom

    fun showDial(autoHideDelayMs: Long = 2000L) {
        isDialExplicitlyOpened = true
        mainHandler.removeCallbacks(autoHideRunnable)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 1.0f
            scaleY = 1.0f
            onDialVisibilityChange?.invoke(true)
        } else if (alpha < 1.0f) {
            animate().cancel()
            alpha = 1.0f
            scaleY = 1.0f
        }
        invalidate()
        resetAutoHide(autoHideDelayMs)
    }

    fun keepDialVisible() {
        isDialExplicitlyOpened = true
        mainHandler.removeCallbacks(autoHideRunnable)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 1.0f
            scaleY = 1.0f
            onDialVisibilityChange?.invoke(true)
        } else if (alpha < 1.0f) {
            animate().cancel()
            alpha = 1.0f
            scaleY = 1.0f
        }
        invalidate()
    }

    fun hideDialImmediately() {
        isDialExplicitlyOpened = false
        mainHandler.removeCallbacks(autoHideRunnable)
        animate().cancel()
        visibility = GONE
        alpha = 0f
        onDialVisibilityChange?.invoke(false)
    }

    fun resetAutoHide(delayMs: Long = 2000L) {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, delayMs)
    }

    private fun fadeOut() {
        if (isDragging) return
        if (visibility != VISIBLE) return
        onDialVisibilityChange?.invoke(false)
        animate()
            .alpha(0f)
            .scaleY(0.85f)
            .setDuration(240)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isDialExplicitlyOpened = false
                    visibility = GONE
                }
            })
            .start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragStartZoom = currentZoom
                isDragging = false
                keepDialVisible()
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val primaryDelta = when (deviceRotation) {
                    Surface.ROTATION_90 -> downY - event.rawY
                    Surface.ROTATION_270 -> event.rawY - downY
                    Surface.ROTATION_180 -> event.rawX - downX
                    else -> downX - event.rawX
                }

                if (!isDragging && Math.abs(primaryDelta) > touchSlop) {
                    isDragging = true
                    listener?.onZoomDragStart()
                }
                if (isDragging) {
                    val span = resources.displayMetrics.widthPixels.toFloat() * 0.32f
                    val factor = Math.pow(20.0, (primaryDelta / span).toDouble()).toFloat()
                    val targetZoom = (dragStartZoom * factor).coerceIn(minZoom, maxZoom)
                    val rounded = (Math.round(targetZoom * 10f) / 10f).coerceIn(minZoom, maxZoom)

                    if (rounded != currentZoom) {
                        currentZoom = rounded
                        listener?.onZoomChanged(rounded)
                        checkHaptic(rounded)
                        invalidate()
                    }
                }
                keepDialVisible()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    listener?.onZoomDragEnd()
                }
                isDragging = false
                resetAutoHide(2000L)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun checkHaptic(zoom: Float) {
        val nearestMajor = majorValues.firstOrNull { Math.abs(it - zoom) < 0.06f }
        if (nearestMajor != null) {
            val idx = (nearestMajor * 10).toInt()
            if (idx != lastHapticTickIndex) {
                lastHapticTickIndex = idx
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        } else {
            lastHapticTickIndex = -1
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!isDialExplicitlyOpened || visibility != VISIBLE || alpha <= 0.01f) return
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        drawPortraitDial(canvas, w, h)
    }

    private fun drawPortraitDial(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val arcRadius = dpToPx(240f)
        val apexY = dpToPx(48f) // Top apex of the radial curve
        val cy = apexY + arcRadius // Center of circle well below the view

        // 1. Draw Center Value Readout (e.g., "1.8×" or "0.5×") rotating in-place
        val zoomText = String.format(Locale.US, "%.1f×", currentZoom)
        val textY = apexY - dpToPx(16f)
        canvas.save()
        canvas.rotate(uiRotation, cx, textY - dpToPx(6f))
        centerValuePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(zoomText, cx, textY, centerValuePaint)
        canvas.restore()

        // 2. Draw Center Pointer Triangle pointing down to the apex
        pointerPath.reset()
        val triWidth = dpToPx(5f)
        val triHeight = dpToPx(6f)
        val triBottom = apexY - dpToPx(2f)
        pointerPath.moveTo(cx, triBottom)
        pointerPath.lineTo(cx - triWidth, triBottom - triHeight)
        pointerPath.lineTo(cx + triWidth, triBottom - triHeight)
        pointerPath.close()
        canvas.drawPath(pointerPath, centerPointerPaint)

        // 3. Draw Radial Ticks & Labels along the curve
        val anglePerOctave = Math.toRadians(28.0)
        val currentLog = ln(currentZoom.toDouble()) / ln(2.0)
        val maxVisibleAngle = Math.toRadians(38.0)

        for (tick in tickValues) {
            val tickLog = ln(tick.toDouble()) / ln(2.0)
            val deltaAngle = (tickLog - currentLog) * anglePerOctave

            if (Math.abs(deltaAngle) > maxVisibleAngle) continue

            // Angle in polar coordinates (straight up is -PI / 2)
            val angle = -Math.PI / 2.0 + deltaAngle
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()

            // Calculate fade out factor at edge
            val distRatio = (Math.abs(deltaAngle) / maxVisibleAngle).toFloat()
            val alphaFactor = (1.0f - distRatio * distRatio).coerceIn(0f, 1f)

            val isMajor = majorValues.contains(tick)
            val tickLen = if (isMajor) dpToPx(12f) else dpToPx(6f)

            val p1X = cx + arcRadius * cosA
            val p1Y = cy + arcRadius * sinA
            val p2X = cx + (arcRadius + tickLen) * cosA
            val p2Y = cy + (arcRadius + tickLen) * sinA

            val paint = if (isMajor) {
                tickMajorPaint.apply { alpha = (255 * alphaFactor).toInt() }
            } else {
                tickNormalPaint.apply { alpha = (160 * alphaFactor).toInt() }
            }

            canvas.drawLine(p1X, p1Y, p2X, p2Y, paint)

            // Draw Major Label (e.g. 0.5, 1, 2, 5, 10) - rotating in place
            if (isMajor) {
                val labelDist = arcRadius + tickLen + dpToPx(13f)
                val labelX = cx + labelDist * cosA
                val labelY = cy + labelDist * sinA + dpToPx(4f)
                val labelStr = if (tick == 0.5f) "0.5" else tick.toInt().toString()
                labelPaint.alpha = (230 * alphaFactor).toInt()
                labelPaint.textAlign = Paint.Align.CENTER

                canvas.save()
                canvas.rotate(uiRotation, labelX, labelY - dpToPx(3f))
                canvas.drawText(labelStr, labelX, labelY, labelPaint)
                canvas.restore()
            }
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
