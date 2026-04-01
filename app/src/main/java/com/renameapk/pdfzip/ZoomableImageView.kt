package com.renameapk.pdfzip

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    enum class FitMode {
        FIT_PAGE,
        FIT_WIDTH
    }

    var onSingleTap: (() -> Unit)? = null
    var onSingleTapPoint: ((Float, Float) -> Boolean)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null
    var onScaleChanged: ((Float) -> Unit)? = null

    private val drawMatrix = Matrix()
    private val baseMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val matrixValues = FloatArray(9)

    private var normalizedScale = 1f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var currentFitMode = FitMode.FIT_PAGE

    init {
        super.setScaleType(ScaleType.MATRIX)
        imageMatrix = drawMatrix
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (drawable == null) {
            drawMatrix.reset()
            baseMatrix.reset()
            imageMatrix = drawMatrix
            normalizedScale = 1f
            updateZoomState()
        } else {
            post { resetZoom(currentFitMode, dispatchScaleChange = false) }
        }
    }

    fun setFitMode(fitMode: FitMode, dispatchScaleChange: Boolean = true) {
        if (currentFitMode == fitMode && drawable != null && width > 0 && height > 0) {
            resetZoom(fitMode, dispatchScaleChange = dispatchScaleChange)
            return
        }
        currentFitMode = fitMode
        if (drawable != null) {
            resetZoom(fitMode, dispatchScaleChange = dispatchScaleChange)
        }
    }

    fun resetZoom(
        fitMode: FitMode = currentFitMode,
        dispatchScaleChange: Boolean = true
    ) {
        val currentDrawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return
        }
        currentFitMode = fitMode

        val drawableWidth = currentDrawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val drawableHeight = currentDrawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val fitWidthScale = viewWidth / drawableWidth
        val fitHeightScale = viewHeight / drawableHeight
        val scale = if (fitMode == FitMode.FIT_WIDTH) {
            fitWidthScale
        } else {
            minOf(fitWidthScale, fitHeightScale)
        }
        val shouldTopAlignLandscapePage =
            fitMode == FitMode.FIT_WIDTH ||
                (drawableWidth > drawableHeight && viewHeight > viewWidth)
        val translateX = (viewWidth - drawableWidth * scale) / 2f
        val translateY = if (shouldTopAlignLandscapePage) {
            0f
        } else {
            (viewHeight - drawableHeight * scale) / 2f
        }

        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(translateX, translateY)

        drawMatrix.set(baseMatrix)
        imageMatrix = drawMatrix
        normalizedScale = 1f
        updateZoomState()
        if (dispatchScaleChange) {
            onScaleChanged?.invoke(normalizedScale)
        }
    }

    fun applySharedScale(scale: Float) {
        val currentDrawable = drawable ?: return
        if (width <= 0 || height <= 0 || currentDrawable.intrinsicWidth <= 0 || currentDrawable.intrinsicHeight <= 0) {
            return
        }
        val targetScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (abs(normalizedScale - targetScale) < SCALE_EPSILON) {
            return
        }
        scaleImage(
            scaleFactor = targetScale / normalizedScale,
            focusX = width / 2f,
            focusY = height / 2f,
            dispatchScaleChange = false
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            post { resetZoom(currentFitMode, dispatchScaleChange = false) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) {
            return super.onTouchEvent(event)
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && isZoomed()) {
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    val drawableRect = currentDrawableRect()
                    val canPanHorizontally =
                        drawableRect != null && drawableRect.width() > width.toFloat() + 1f
                    val canPanVertically =
                        drawableRect != null && drawableRect.height() > height.toFloat() + 1f
                    val panX = if (canPanHorizontally) deltaX else 0f
                    val panY = if (canPanVertically) deltaY else 0f
                    val shouldPanImage = abs(panX) > 1f || abs(panY) > 1f
                    if (shouldPanImage) {
                        drawMatrix.postTranslate(panX, panY)
                        fixTranslation()
                        imageMatrix = drawMatrix
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!isZoomed()) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                if (!isDragging) {
                    performClick()
                }
                isDragging = false
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun scaleImage(
        scaleFactor: Float,
        focusX: Float,
        focusY: Float,
        dispatchScaleChange: Boolean = true
    ) {
        val previousScale = normalizedScale
        normalizedScale = (normalizedScale * scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val appliedFactor = normalizedScale / previousScale
        drawMatrix.postScale(appliedFactor, appliedFactor, focusX, focusY)
        fixTranslation()
        imageMatrix = drawMatrix
        updateZoomState()
        if (dispatchScaleChange) {
            onScaleChanged?.invoke(normalizedScale)
        }
    }

    private fun fixTranslation() {
        val rect = currentDrawableRect() ?: return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= viewWidth) {
            deltaX = viewWidth / 2f - rect.centerX()
        } else {
            if (rect.left > 0f) {
                deltaX = -rect.left
            } else if (rect.right < viewWidth) {
                deltaX = viewWidth - rect.right
            }
        }

        if (rect.height() <= viewHeight) {
            deltaY = viewHeight / 2f - rect.centerY()
        } else {
            if (rect.top > 0f) {
                deltaY = -rect.top
            } else if (rect.bottom < viewHeight) {
                deltaY = viewHeight - rect.bottom
            }
        }

        drawMatrix.postTranslate(deltaX, deltaY)
    }

    private fun updateZoomState() {
        onZoomChanged?.invoke(isZoomed())
    }

    private fun isZoomed(): Boolean {
        val rect = currentDrawableRect()
        val fillsBeyondBounds = rect != null && (
            rect.width() > width.toFloat() + 1f ||
                rect.height() > height.toFloat() + 1f
            )
        return normalizedScale > 1.01f || fillsBeyondBounds
    }

    private fun currentDrawableRect(): RectF? {
        val currentDrawable = drawable ?: return null
        return RectF(
            0f,
            0f,
            currentDrawable.intrinsicWidth.toFloat(),
            currentDrawable.intrinsicHeight.toFloat()
        ).also { rect ->
            drawMatrix.mapRect(rect)
        }
    }

    fun mapPointToDrawableRatios(x: Float, y: Float): PointF? {
        val drawableRect = currentDrawableRect() ?: return null
        if (!drawableRect.contains(x, y)) {
            return null
        }
        val widthRatio = ((x - drawableRect.left) / drawableRect.width())
            .coerceIn(0f, 1f)
        val heightRatio = ((y - drawableRect.top) / drawableRect.height())
            .coerceIn(0f, 1f)
        return PointF(widthRatio, heightRatio)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleImage(detector.scaleFactor, detector.focusX, detector.focusY)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (!isZoomed()) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (onSingleTapPoint?.invoke(e.x, e.y) == true) {
                return true
            }
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed()) {
                resetZoom()
            } else {
                scaleImage(DOUBLE_TAP_SCALE, e.x, e.y)
            }
            return true
        }
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 4f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val SCALE_EPSILON = 0.01f
    }
}
