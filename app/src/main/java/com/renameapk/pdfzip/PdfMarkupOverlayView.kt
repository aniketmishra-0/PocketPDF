package com.renameapk.pdfzip

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MIN_IMAGE_SIDE_RATIO: Float = 0.04f

private fun calculateImageHeightRatio(imageBytes: ByteArray, widthRatio: Float): Float {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        return widthRatio
    }
    val aspectRatio = options.outHeight.toFloat() / options.outWidth.toFloat().coerceAtLeast(1f)
    return (widthRatio * aspectRatio).coerceIn(MIN_IMAGE_SIDE_RATIO, 0.92f)
}

sealed class MarkupOperation {
    data class Stroke(
        val points: MutableList<PointF>,
        val color: Int = DEFAULT_STROKE_COLOR,
        val widthRatio: Float = DEFAULT_STROKE_WIDTH_RATIO
    ) : MarkupOperation()

    data class Text(
        val text: String,
        var xRatio: Float,
        var yRatio: Float,
        val color: Int = DEFAULT_TEXT_COLOR,
        var textSizeRatio: Float = DEFAULT_TEXT_SIZE_RATIO,
        var isBold: Boolean = false
    ) : MarkupOperation()

    data class Link(
        val text: String,
        val url: String,
        var xRatio: Float,
        var yRatio: Float,
        val color: Int = DEFAULT_LINK_COLOR,
        var textSizeRatio: Float = DEFAULT_TEXT_SIZE_RATIO,
        var isBold: Boolean = false
    ) : MarkupOperation()

    data class Image(
        val bytes: ByteArray,
        var xRatio: Float,
        var yRatio: Float,
        var widthRatio: Float = DEFAULT_IMAGE_WIDTH_RATIO,
        var heightRatio: Float = DEFAULT_IMAGE_HEIGHT_RATIO
    ) : MarkupOperation()

    data class Cover(
        var xRatio: Float,
        var yRatio: Float,
        var widthRatio: Float = DEFAULT_COVER_WIDTH_RATIO,
        var heightRatio: Float = DEFAULT_COVER_HEIGHT_RATIO,
        val color: Int = DEFAULT_WHITEOUT_COLOR
    ) : MarkupOperation()

    companion object {
        const val DEFAULT_STROKE_COLOR: Int = 0xFFE55A44.toInt()
        const val DEFAULT_TEXT_COLOR: Int = 0xFF1F2A44.toInt()
        const val DEFAULT_STROKE_WIDTH_RATIO: Float = 0.0075f
        const val DEFAULT_HIGHLIGHT_COLOR: Int = 0x88F7D94C.toInt()
        const val DEFAULT_HIGHLIGHT_WIDTH_RATIO: Float = 0.024f
        const val DEFAULT_LINK_COLOR: Int = 0xFF2563EB.toInt()
        const val DEFAULT_IMAGE_WIDTH_RATIO: Float = 0.32f
        const val DEFAULT_IMAGE_HEIGHT_RATIO: Float = 0.24f
        const val DEFAULT_TEXT_SIZE_RATIO: Float = 0.034f
        const val DEFAULT_WHITEOUT_COLOR: Int = Color.WHITE
        const val DEFAULT_REDACT_COLOR: Int = 0xFF111111.toInt()
        const val DEFAULT_COVER_WIDTH_RATIO: Float = 0.32f
        const val DEFAULT_COVER_HEIGHT_RATIO: Float = 0.11f
    }
}

data class PageMarkupState(
    val operations: MutableList<MarkupOperation> = mutableListOf()
) {
    fun isEmpty(): Boolean = operations.isEmpty()
}

class PdfMarkupOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class ToolMode {
        MOVE,
        DRAW,
        HIGHLIGHT,
        TEXT
    }

    var toolMode: ToolMode = ToolMode.DRAW
        set(value) {
            field = value
            activeStroke = null
            if (value != ToolMode.MOVE) {
                activeDragIndex = -1
                activeResizeIndex = -1
                isScalingSelection = false
                pendingCanvasGesture = false
            }
            invalidate()
        }

    var onTextPlacementRequested: ((Float, Float) -> Unit)? = null
    var onMarkupChanged: (() -> Unit)? = null
    var onSelectionChanged: ((MarkupOperation?) -> Unit)? = null
    var onCanvasScaleRequested: ((Float, Float, Float) -> Boolean)? = null
    var preferCanvasPinchZoom: Boolean = true

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT
        isFakeBoldText = false
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = 0xCC1A8CFF.toInt()
    }
    private val resizeHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val resizeHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = 0xCC1A8CFF.toInt()
    }

    private var pageMarkupState: PageMarkupState = PageMarkupState()
    private var activeStroke: MarkupOperation.Stroke? = null
    private var selectedOperationIndex: Int = -1
    private var activeDragIndex: Int = -1
    private var activeResizeIndex: Int = -1
    private var lastTouchXRatio: Float = 0f
    private var lastTouchYRatio: Float = 0f
    private var pendingCanvasGesture = false
    private var pendingCanvasTouchX = 0f
    private var pendingCanvasTouchY = 0f
    private var hasDraggedSelection: Boolean = false
    private var isScalingSelection: Boolean = false
    private var isScalingCanvas: Boolean = false
    private val touchPaddingPx = ViewConfiguration.get(context).scaledTouchSlop * 2f
    private val resizeHandleRadiusPx = resources.displayMetrics.density * 10f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (preferCanvasPinchZoom) {
                    return false
                }
                if (toolMode != ToolMode.MOVE) {
                    return false
                }
                val targetIndex = when {
                    operationBoundsForSelection(selectedOperationIndex)
                        ?.contains(detector.focusX, detector.focusY) == true -> {
                        selectedOperationIndex
                    }

                    else -> findTopmostOperationAt(detector.focusX, detector.focusY)
                }
                if (targetIndex < 0 || !isOperationResizable(targetIndex)) {
                    return false
                }
                setSelectedOperationIndex(targetIndex)
                activeDragIndex = -1
                isScalingSelection = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!isScalingSelection || toolMode != ToolMode.MOVE) {
                    return false
                }
                return adjustSelectedElementScale(detector.scaleFactor)
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScalingSelection = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    )
    private val canvasScaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                val canScaleCanvas = onCanvasScaleRequested != null &&
                    !shouldPrioritizeSelectionScaling(detector.focusX, detector.focusY)
                if (canScaleCanvas) {
                    isScalingCanvas = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return canScaleCanvas
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!isScalingCanvas) {
                    return false
                }
                return onCanvasScaleRequested?.invoke(
                    detector.scaleFactor,
                    detector.focusX,
                    detector.focusY
                ) == true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScalingCanvas = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    )

    fun bindMarkupState(state: PageMarkupState) {
        pageMarkupState = state
        activeStroke = null
        activeDragIndex = -1
        activeResizeIndex = -1
        pendingCanvasGesture = false
        setSelectedOperationIndex(-1)
    }

    fun addText(
        text: String,
        xRatio: Float,
        yRatio: Float,
        color: Int = MarkupOperation.DEFAULT_TEXT_COLOR,
        isBold: Boolean = false
    ) {
        pageMarkupState.operations += MarkupOperation.Text(
            text = text,
            xRatio = xRatio.coerceIn(0.05f, 0.95f),
            yRatio = yRatio.coerceIn(0.08f, 0.95f),
            color = color,
            isBold = isBold
        )
        setSelectedOperationIndex(pageMarkupState.operations.lastIndex)
        onMarkupChanged?.invoke()
    }

    fun addLink(
        text: String,
        url: String,
        xRatio: Float,
        yRatio: Float,
        isBold: Boolean = false
    ) {
        pageMarkupState.operations += MarkupOperation.Link(
            text = text,
            url = url,
            xRatio = xRatio.coerceIn(0.05f, 0.95f),
            yRatio = yRatio.coerceIn(0.08f, 0.95f),
            isBold = isBold
        )
        setSelectedOperationIndex(pageMarkupState.operations.lastIndex)
        onMarkupChanged?.invoke()
    }

    fun addImage(
        imageBytes: ByteArray,
        xRatio: Float = 0.12f,
        yRatio: Float = 0.12f
    ) {
        val imageWidthRatio = MarkupOperation.DEFAULT_IMAGE_WIDTH_RATIO
        pageMarkupState.operations += MarkupOperation.Image(
            bytes = imageBytes,
            xRatio = xRatio.coerceIn(0.02f, 0.88f),
            yRatio = yRatio.coerceIn(0.02f, 0.88f),
            widthRatio = imageWidthRatio,
            heightRatio = calculateImageHeightRatio(imageBytes, imageWidthRatio)
        )
        constrainSelectedImage(pageMarkupState.operations.lastIndex)
        setSelectedOperationIndex(pageMarkupState.operations.lastIndex)
        onMarkupChanged?.invoke()
    }

    fun addCover(
        color: Int,
        xRatio: Float = 0.12f,
        yRatio: Float = 0.18f
    ) {
        pageMarkupState.operations += MarkupOperation.Cover(
            xRatio = xRatio.coerceIn(0.02f, 0.88f),
            yRatio = yRatio.coerceIn(0.02f, 0.88f),
            color = color
        )
        constrainSelectedCover(pageMarkupState.operations.lastIndex)
        setSelectedOperationIndex(pageMarkupState.operations.lastIndex)
        onMarkupChanged?.invoke()
    }

    fun selectLastOperation() {
        setSelectedOperationIndex(pageMarkupState.operations.lastIndex)
    }

    fun hasSelectedResizableOperation(): Boolean {
        return when (selectedOperation()) {
            is MarkupOperation.Text,
            is MarkupOperation.Link,
            is MarkupOperation.Image,
            is MarkupOperation.Cover -> true

            else -> false
        }
    }

    fun hasSelectedTextOperation(): Boolean {
        return when (selectedOperation()) {
            is MarkupOperation.Text,
            is MarkupOperation.Link -> true

            else -> false
        }
    }

    fun clearSelection(): Boolean {
        val hadSelection =
            selectedOperationIndex >= 0 || activeDragIndex >= 0 || activeResizeIndex >= 0 || isScalingSelection
        if (!hadSelection) {
            return false
        }
        activeStroke = null
        activeDragIndex = -1
        activeResizeIndex = -1
        pendingCanvasGesture = false
        hasDraggedSelection = false
        isScalingSelection = false
        parent?.requestDisallowInterceptTouchEvent(false)
        setSelectedOperationIndex(-1)
        return true
    }

    fun adjustSelectedElementScale(multiplier: Float): Boolean {
        if (multiplier <= 0f) {
            return false
        }
        val operation = selectedOperation() ?: return false
        when (operation) {
            is MarkupOperation.Text -> {
                operation.textSizeRatio =
                    (operation.textSizeRatio * multiplier).coerceIn(0.018f, 0.14f)
                constrainSelectedTextLike(selectedOperationIndex)
            }

            is MarkupOperation.Link -> {
                operation.textSizeRatio =
                    (operation.textSizeRatio * multiplier).coerceIn(0.018f, 0.14f)
                constrainSelectedTextLike(selectedOperationIndex)
            }

            is MarkupOperation.Image -> {
                operation.widthRatio =
                    (operation.widthRatio * multiplier).coerceIn(MIN_IMAGE_SIDE_RATIO, 1f)
                operation.heightRatio =
                    (operation.heightRatio * multiplier).coerceIn(MIN_IMAGE_SIDE_RATIO, 1f)
                constrainSelectedImage(selectedOperationIndex)
            }

            is MarkupOperation.Cover -> {
                operation.widthRatio =
                    (operation.widthRatio * multiplier).coerceIn(MIN_IMAGE_SIDE_RATIO, 1f)
                operation.heightRatio =
                    (operation.heightRatio * multiplier).coerceIn(MIN_IMAGE_SIDE_RATIO, 1f)
                constrainSelectedCover(selectedOperationIndex)
            }

            is MarkupOperation.Stroke -> return false
        }
        onMarkupChanged?.invoke()
        invalidate()
        return true
    }

    fun toggleSelectedTextBold(): Boolean {
        when (val operation = selectedOperation()) {
            is MarkupOperation.Text -> {
                operation.isBold = !operation.isBold
            }

            is MarkupOperation.Link -> {
                operation.isBold = !operation.isBold
            }

            else -> return false
        }
        onMarkupChanged?.invoke()
        invalidate()
        return true
    }

    fun undoLast() {
        if (pageMarkupState.operations.isEmpty()) {
            return
        }
        pageMarkupState.operations.removeAt(pageMarkupState.operations.lastIndex)
        setSelectedOperationIndex(pageMarkupState.operations.lastIndex)
        onMarkupChanged?.invoke()
    }

    fun clearPage() {
        if (pageMarkupState.operations.isEmpty()) {
            return
        }
        pageMarkupState.operations.clear()
        activeDragIndex = -1
        pendingCanvasGesture = false
        setSelectedOperationIndex(-1)
        onMarkupChanged?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }

        if (event.pointerCount > 1 || isScalingCanvas || isScalingSelection) {
            val canvasHandled = canvasScaleDetector.onTouchEvent(event)
            val selectionHandled = if (!preferCanvasPinchZoom && toolMode == ToolMode.MOVE) {
                scaleDetector.onTouchEvent(event)
            } else {
                false
            }
            if (
                canvasHandled ||
                selectionHandled ||
                event.pointerCount > 1 ||
                isScalingCanvas ||
                isScalingSelection
            ) {
                return true
            }
        }

        return when (toolMode) {
            ToolMode.MOVE -> {
                val scaleHandled = scaleDetector.onTouchEvent(event)
                val moveHandled = handleMoveTouch(event)
                scaleHandled || moveHandled
            }

            ToolMode.DRAW,
            ToolMode.HIGHLIGHT -> handleDrawTouch(event)

            ToolMode.TEXT -> handleTextTouch(event)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawMarkupOperations(
            canvas = canvas,
            operations = pageMarkupState.operations,
            targetWidth = width.toFloat(),
            targetHeight = height.toFloat(),
            strokePaint = strokePaint,
            textPaint = textPaint
        )
        val selectedBounds = operationBoundsForSelection(selectedOperationIndex)
        if (toolMode == ToolMode.MOVE && selectedBounds != null) {
            canvas.drawRoundRect(selectedBounds, 14f, 14f, selectionPaint)
            resizeHandleCenter(selectedOperationIndex)?.let { handleCenter ->
                canvas.drawCircle(
                    handleCenter.x,
                    handleCenter.y,
                    resizeHandleRadiusPx,
                    resizeHandleFillPaint
                )
                canvas.drawCircle(
                    handleCenter.x,
                    handleCenter.y,
                    resizeHandleRadiusPx,
                    resizeHandleStrokePaint
                )
            }
        }
    }

    private fun handleDrawTouch(event: MotionEvent): Boolean {
        val xRatio = (event.x / width.toFloat()).coerceIn(0f, 1f)
        val yRatio = (event.y / height.toFloat()).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeStroke = when (toolMode) {
                    ToolMode.HIGHLIGHT -> MarkupOperation.Stroke(
                        points = mutableListOf(PointF(xRatio, yRatio)),
                        color = MarkupOperation.DEFAULT_HIGHLIGHT_COLOR,
                        widthRatio = MarkupOperation.DEFAULT_HIGHLIGHT_WIDTH_RATIO
                    )

                    else -> MarkupOperation.Stroke(
                        points = mutableListOf(PointF(xRatio, yRatio))
                    )
                }.also { pageMarkupState.operations += it }
                setSelectedOperationIndex(pageMarkupState.operations.lastIndex)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                activeStroke?.points?.add(PointF(xRatio, yRatio))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                activeStroke?.points?.let { points ->
                    if (points.size == 1) {
                        points += PointF(xRatio, yRatio)
                    }
                }
                activeStroke = null
                parent?.requestDisallowInterceptTouchEvent(false)
                onMarkupChanged?.invoke()
                invalidate()
                return true
            }
        }
        return false
    }

    private fun handleMoveTouch(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || isScalingSelection) {
            return selectedOperationIndex >= 0
        }

        val xRatio = (event.x / width.toFloat()).coerceIn(0f, 1f)
        val yRatio = (event.y / height.toFloat()).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingCanvasGesture = false
                val resizeTarget = findResizeHandleTarget(event.x, event.y)
                if (resizeTarget >= 0) {
                    setSelectedOperationIndex(resizeTarget)
                    activeResizeIndex = resizeTarget
                    activeDragIndex = -1
                    hasDraggedSelection = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val targetIndex = findTopmostOperationAt(event.x, event.y)
                setSelectedOperationIndex(targetIndex)
                activeDragIndex = targetIndex
                activeResizeIndex = -1
                lastTouchXRatio = xRatio
                lastTouchYRatio = yRatio
                hasDraggedSelection = false
                if (targetIndex >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (preferCanvasPinchZoom && onCanvasScaleRequested != null) {
                    pendingCanvasGesture = true
                    pendingCanvasTouchX = event.x
                    pendingCanvasTouchY = event.y
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeResizeIndex >= 0) {
                    resizeOperation(activeResizeIndex, xRatio, yRatio)
                    hasDraggedSelection = true
                    onMarkupChanged?.invoke()
                    invalidate()
                    return true
                }
                if (activeDragIndex < 0) {
                    if (pendingCanvasGesture) {
                        val dragDistance = hypot(
                            event.x - pendingCanvasTouchX,
                            event.y - pendingCanvasTouchY
                        )
                        if (dragDistance > touchPaddingPx) {
                            pendingCanvasGesture = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        }
                        return true
                    }
                    return false
                }
                val deltaX = xRatio - lastTouchXRatio
                val deltaY = yRatio - lastTouchYRatio
                moveOperation(activeDragIndex, deltaX, deltaY)
                lastTouchXRatio = xRatio
                lastTouchYRatio = yRatio
                hasDraggedSelection = true
                onMarkupChanged?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val hadSelection =
                    activeDragIndex >= 0 ||
                        activeResizeIndex >= 0 ||
                        selectedOperationIndex >= 0 ||
                        pendingCanvasGesture
                if (!hasDraggedSelection && event.actionMasked == MotionEvent.ACTION_UP) {
                    setSelectedOperationIndex(findTopmostOperationAt(event.x, event.y))
                }
                activeDragIndex = -1
                activeResizeIndex = -1
                pendingCanvasGesture = false
                hasDraggedSelection = false
                invalidate()
                return hadSelection
            }
        }
        return false
    }

    private fun handleTextTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val xRatio = (event.x / width.toFloat()).coerceIn(0.05f, 0.95f)
                val yRatio = (event.y / height.toFloat()).coerceIn(0.05f, 0.95f)
                parent?.requestDisallowInterceptTouchEvent(false)
                onTextPlacementRequested?.invoke(xRatio, yRatio)
                return true
            }
        }
        return true
    }

    private fun setSelectedOperationIndex(index: Int) {
        selectedOperationIndex = if (index in pageMarkupState.operations.indices) index else -1
        onSelectionChanged?.invoke(selectedOperation())
        invalidate()
    }

    private fun selectedOperation(): MarkupOperation? {
        return pageMarkupState.operations.getOrNull(selectedOperationIndex)
    }

    private fun isOperationResizable(index: Int): Boolean {
        return when (pageMarkupState.operations.getOrNull(index)) {
            is MarkupOperation.Text,
            is MarkupOperation.Link,
            is MarkupOperation.Image,
            is MarkupOperation.Cover -> true

            else -> false
        }
    }

    private fun shouldPrioritizeSelectionScaling(x: Float, y: Float): Boolean {
        if (preferCanvasPinchZoom) {
            return false
        }
        if (toolMode != ToolMode.MOVE) {
            return false
        }
        val selectedIndex = selectedOperationIndex
        if (
            selectedIndex >= 0 &&
            isOperationResizable(selectedIndex) &&
            operationBoundsForSelection(selectedIndex)?.contains(x, y) == true
        ) {
            return true
        }
        val targetIndex = findTopmostOperationAt(x, y)
        return targetIndex >= 0 && isOperationResizable(targetIndex)
    }

    private fun findTopmostOperationAt(x: Float, y: Float): Int {
        for (index in pageMarkupState.operations.lastIndex downTo 0) {
            val bounds = operationBoundsForSelection(index) ?: continue
            if (bounds.contains(x, y)) {
                return index
            }
        }
        return -1
    }

    private fun operationBoundsForSelection(index: Int): RectF? {
        val operation = pageMarkupState.operations.getOrNull(index) ?: return null
        val targetWidth = width.toFloat()
        val targetHeight = height.toFloat()
        if (targetWidth <= 0f || targetHeight <= 0f) {
            return null
        }
        return computeOperationBounds(
            operation = operation,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            textPaint = textPaint,
            touchPadding = touchPaddingPx
        )
    }

    private fun operationBoundsWithoutPadding(index: Int): RectF? {
        val operation = pageMarkupState.operations.getOrNull(index) ?: return null
        val targetWidth = width.toFloat().coerceAtLeast(1f)
        val targetHeight = height.toFloat().coerceAtLeast(1f)
        return computeOperationBounds(
            operation = operation,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            textPaint = textPaint,
            touchPadding = 0f
        )
    }

    private fun resizeHandleCenter(index: Int): PointF? {
        if (pageMarkupState.operations.getOrNull(index) !is MarkupOperation.Image) {
            if (pageMarkupState.operations.getOrNull(index) !is MarkupOperation.Cover) {
                return null
            }
        }
        val bounds = operationBoundsWithoutPadding(index) ?: return null
        return PointF(bounds.right, bounds.bottom)
    }

    private fun findResizeHandleTarget(x: Float, y: Float): Int {
        val candidateIndex = selectedOperationIndex
        if (candidateIndex < 0) {
            return -1
        }
        val handleCenter = resizeHandleCenter(candidateIndex) ?: return -1
        val distance = hypot(x - handleCenter.x, y - handleCenter.y)
        return if (distance <= resizeHandleRadiusPx * 1.8f) {
            candidateIndex
        } else {
            -1
        }
    }

    private fun moveOperation(index: Int, deltaXRatio: Float, deltaYRatio: Float) {
        when (val operation = pageMarkupState.operations.getOrNull(index)) {
            is MarkupOperation.Text -> {
                operation.xRatio += deltaXRatio
                operation.yRatio += deltaYRatio
                constrainSelectedTextLike(index)
            }

            is MarkupOperation.Link -> {
                operation.xRatio += deltaXRatio
                operation.yRatio += deltaYRatio
                constrainSelectedTextLike(index)
            }

            is MarkupOperation.Image -> {
                operation.xRatio += deltaXRatio
                operation.yRatio += deltaYRatio
                constrainSelectedImage(index)
            }

            is MarkupOperation.Cover -> {
                operation.xRatio += deltaXRatio
                operation.yRatio += deltaYRatio
                constrainSelectedCover(index)
            }

            is MarkupOperation.Stroke -> {
                if (operation.points.isEmpty()) {
                    return
                }
                val minX = operation.points.minOf { it.x }
                val maxX = operation.points.maxOf { it.x }
                val minY = operation.points.minOf { it.y }
                val maxY = operation.points.maxOf { it.y }
                val safeDeltaX = deltaXRatio.coerceIn(-minX, 1f - maxX)
                val safeDeltaY = deltaYRatio.coerceIn(-minY, 1f - maxY)
                operation.points.replaceAll { point ->
                    PointF(point.x + safeDeltaX, point.y + safeDeltaY)
                }
            }

            null -> return
        }
    }

    private fun resizeOperation(index: Int, touchXRatio: Float, touchYRatio: Float) {
        when (val operation = pageMarkupState.operations.getOrNull(index)) {
            is MarkupOperation.Image -> {
                val maxWidthRatio = (1f - operation.xRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
                val maxHeightRatio = (1f - operation.yRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
                operation.widthRatio = (touchXRatio - operation.xRatio)
                    .coerceIn(MIN_IMAGE_SIDE_RATIO, maxWidthRatio)
                operation.heightRatio = (touchYRatio - operation.yRatio)
                    .coerceIn(MIN_IMAGE_SIDE_RATIO, maxHeightRatio)
                constrainSelectedImage(index)
            }

            is MarkupOperation.Cover -> {
                val maxWidthRatio = (1f - operation.xRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
                val maxHeightRatio = (1f - operation.yRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
                operation.widthRatio = (touchXRatio - operation.xRatio)
                    .coerceIn(MIN_IMAGE_SIDE_RATIO, maxWidthRatio)
                operation.heightRatio = (touchYRatio - operation.yRatio)
                    .coerceIn(MIN_IMAGE_SIDE_RATIO, maxHeightRatio)
                constrainSelectedCover(index)
            }

            is MarkupOperation.Text,
            is MarkupOperation.Link,
            is MarkupOperation.Stroke,
            null -> return
        }
    }

    private fun constrainSelectedTextLike(index: Int) {
        val operation = pageMarkupState.operations.getOrNull(index)
        val xRatio = when (operation) {
            is MarkupOperation.Text -> operation.xRatio
            is MarkupOperation.Link -> operation.xRatio
            else -> return
        }
        val yRatio = when (operation) {
            is MarkupOperation.Text -> operation.yRatio
            is MarkupOperation.Link -> operation.yRatio
            else -> return
        }
        val bounds = computeOperationBounds(
            operation = operation,
            targetWidth = width.toFloat().coerceAtLeast(1f),
            targetHeight = height.toFloat().coerceAtLeast(1f),
            textPaint = textPaint,
            touchPadding = 0f
        ) ?: return
        val widthValue = width.toFloat().coerceAtLeast(1f)
        val heightValue = height.toFloat().coerceAtLeast(1f)
        if (bounds.left < 0f) {
            when (operation) {
                is MarkupOperation.Text -> operation.xRatio += (-bounds.left / widthValue)
                is MarkupOperation.Link -> operation.xRatio += (-bounds.left / widthValue)
                else -> Unit
            }
        } else if (bounds.right > widthValue) {
            when (operation) {
                is MarkupOperation.Text -> operation.xRatio -= ((bounds.right - widthValue) / widthValue)
                is MarkupOperation.Link -> operation.xRatio -= ((bounds.right - widthValue) / widthValue)
                else -> Unit
            }
        }
        if (bounds.top < 0f) {
            when (operation) {
                is MarkupOperation.Text -> operation.yRatio += (-bounds.top / heightValue)
                is MarkupOperation.Link -> operation.yRatio += (-bounds.top / heightValue)
                else -> Unit
            }
        } else if (bounds.bottom > heightValue) {
            when (operation) {
                is MarkupOperation.Text -> operation.yRatio -= ((bounds.bottom - heightValue) / heightValue)
                is MarkupOperation.Link -> operation.yRatio -= ((bounds.bottom - heightValue) / heightValue)
                else -> Unit
            }
        }
        when (operation) {
            is MarkupOperation.Text -> {
                operation.xRatio = operation.xRatio.coerceIn(0.01f, 0.99f)
                operation.yRatio = operation.yRatio.coerceIn(0.04f, 0.99f)
            }

            is MarkupOperation.Link -> {
                operation.xRatio = operation.xRatio.coerceIn(0.01f, 0.99f)
                operation.yRatio = operation.yRatio.coerceIn(0.04f, 0.99f)
            }

            else -> Unit
        }
    }

    private fun constrainSelectedImage(index: Int) {
        val operation = pageMarkupState.operations.getOrNull(index) as? MarkupOperation.Image ?: return
        val maxWidthRatio = (1f - operation.xRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
        val maxHeightRatio = (1f - operation.yRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
        operation.widthRatio = operation.widthRatio.coerceIn(MIN_IMAGE_SIDE_RATIO, maxWidthRatio)
        operation.heightRatio = operation.heightRatio.coerceIn(MIN_IMAGE_SIDE_RATIO, maxHeightRatio)
        operation.xRatio = operation.xRatio.coerceIn(0f, (1f - operation.widthRatio).coerceAtLeast(0f))
        operation.yRatio = operation.yRatio.coerceIn(0f, (1f - operation.heightRatio).coerceAtLeast(0f))
    }

    private fun constrainSelectedCover(index: Int) {
        val operation = pageMarkupState.operations.getOrNull(index) as? MarkupOperation.Cover ?: return
        val maxWidthRatio = (1f - operation.xRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
        val maxHeightRatio = (1f - operation.yRatio).coerceAtLeast(MIN_IMAGE_SIDE_RATIO)
        operation.widthRatio = operation.widthRatio.coerceIn(MIN_IMAGE_SIDE_RATIO, maxWidthRatio)
        operation.heightRatio = operation.heightRatio.coerceIn(MIN_IMAGE_SIDE_RATIO, maxHeightRatio)
        operation.xRatio = operation.xRatio.coerceIn(0f, (1f - operation.widthRatio).coerceAtLeast(0f))
        operation.yRatio = operation.yRatio.coerceIn(0f, (1f - operation.heightRatio).coerceAtLeast(0f))
    }

    companion object {
        fun drawMarkupOperations(
            canvas: Canvas,
            operations: List<MarkupOperation>,
            targetWidth: Float,
            targetHeight: Float,
            strokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            },
            textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                textAlign = Paint.Align.LEFT
            }
        ) {
            val minDimension = min(targetWidth, targetHeight).coerceAtLeast(1f)
            operations.forEach { operation ->
                when (operation) {
                    is MarkupOperation.Stroke -> {
                        if (operation.points.isEmpty()) {
                            return@forEach
                        }
                        val path = Path()
                        operation.points.forEachIndexed { index, point ->
                            val x = point.x * targetWidth
                            val y = point.y * targetHeight
                            if (index == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        strokePaint.color = operation.color
                        strokePaint.strokeWidth =
                            (minDimension * operation.widthRatio).coerceAtLeast(4f)
                        canvas.drawPath(path, strokePaint)
                    }

                    is MarkupOperation.Text -> {
                        configureTextPaint(
                            textPaint = textPaint,
                            color = operation.color,
                            textSizeRatio = operation.textSizeRatio,
                            isBold = operation.isBold,
                            minDimension = minDimension,
                            underline = false
                        )
                        val textX = operation.xRatio * targetWidth
                        val textY = operation.yRatio * targetHeight
                        canvas.drawText(operation.text, textX, textY, textPaint)
                    }

                    is MarkupOperation.Link -> {
                        configureTextPaint(
                            textPaint = textPaint,
                            color = operation.color,
                            textSizeRatio = operation.textSizeRatio,
                            isBold = operation.isBold,
                            minDimension = minDimension,
                            underline = true
                        )
                        val textX = operation.xRatio * targetWidth
                        val textY = operation.yRatio * targetHeight
                        canvas.drawText(operation.text, textX, textY, textPaint)
                    }

                    is MarkupOperation.Image -> {
                        val bitmap = BitmapFactory.decodeByteArray(
                            operation.bytes,
                            0,
                            operation.bytes.size
                        ) ?: return@forEach
                        try {
                            val targetImageWidth =
                                (targetWidth * operation.widthRatio).coerceAtLeast(40f)
                            val targetImageHeight =
                                (targetHeight * operation.heightRatio).coerceAtLeast(40f)
                            val left = (operation.xRatio * targetWidth)
                                .coerceAtMost(targetWidth - targetImageWidth)
                                .coerceAtLeast(0f)
                            val top = (operation.yRatio * targetHeight)
                                .coerceAtMost(targetHeight - targetImageHeight)
                                .coerceAtLeast(0f)
                            val destination = RectF(
                                left,
                                top,
                                left + targetImageWidth,
                                top + targetImageHeight
                            )
                            canvas.drawBitmap(bitmap, null, destination, null)
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    is MarkupOperation.Cover -> {
                        val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.FILL
                            color = operation.color
                        }
                        val left = (operation.xRatio * targetWidth)
                            .coerceAtLeast(0f)
                        val top = (operation.yRatio * targetHeight)
                            .coerceAtLeast(0f)
                        val right = (left + targetWidth * operation.widthRatio)
                            .coerceAtMost(targetWidth)
                        val bottom = (top + targetHeight * operation.heightRatio)
                            .coerceAtMost(targetHeight)
                        canvas.drawRect(left, top, right, bottom, coverPaint)
                    }
                }
            }
        }

        private fun computeOperationBounds(
            operation: MarkupOperation,
            targetWidth: Float,
            targetHeight: Float,
            textPaint: Paint,
            touchPadding: Float
        ): RectF? {
            val minDimension = min(targetWidth, targetHeight).coerceAtLeast(1f)
            return when (operation) {
                is MarkupOperation.Stroke -> {
                    if (operation.points.isEmpty()) {
                        null
                    } else {
                        val minX = operation.points.minOf { it.x * targetWidth }
                        val maxX = operation.points.maxOf { it.x * targetWidth }
                        val minY = operation.points.minOf { it.y * targetHeight }
                        val maxY = operation.points.maxOf { it.y * targetHeight }
                        RectF(
                            minX - touchPadding,
                            minY - touchPadding,
                            maxX + touchPadding,
                            maxY + touchPadding
                        )
                    }
                }

                is MarkupOperation.Text -> {
                    configureTextPaint(
                        textPaint = textPaint,
                        color = operation.color,
                        textSizeRatio = operation.textSizeRatio,
                        isBold = operation.isBold,
                        minDimension = minDimension,
                        underline = false
                    )
                    val textWidth = max(textPaint.measureText(operation.text), touchPadding * 2f)
                    val fontMetrics = textPaint.fontMetrics
                    val x = operation.xRatio * targetWidth
                    val y = operation.yRatio * targetHeight
                    RectF(
                        x - touchPadding,
                        y + fontMetrics.ascent - touchPadding,
                        x + textWidth + touchPadding,
                        y + fontMetrics.descent + touchPadding
                    )
                }

                is MarkupOperation.Link -> {
                    configureTextPaint(
                        textPaint = textPaint,
                        color = operation.color,
                        textSizeRatio = operation.textSizeRatio,
                        isBold = operation.isBold,
                        minDimension = minDimension,
                        underline = true
                    )
                    val textWidth = max(textPaint.measureText(operation.text), touchPadding * 2f)
                    val fontMetrics = textPaint.fontMetrics
                    val x = operation.xRatio * targetWidth
                    val y = operation.yRatio * targetHeight
                    RectF(
                        x - touchPadding,
                        y + fontMetrics.ascent - touchPadding,
                        x + textWidth + touchPadding,
                        y + fontMetrics.descent + touchPadding
                    )
                }

                is MarkupOperation.Image -> {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(
                        operation.bytes,
                        0,
                        operation.bytes.size,
                        options
                    )
                    if (options.outWidth <= 0 || options.outHeight <= 0) {
                        null
                    } else {
                        val imageWidth = (targetWidth * operation.widthRatio).coerceAtLeast(40f)
                        val imageHeight = (targetHeight * operation.heightRatio).coerceAtLeast(40f)
                        val left = operation.xRatio * targetWidth
                        val top = operation.yRatio * targetHeight
                        RectF(
                            left - touchPadding,
                            top - touchPadding,
                            left + imageWidth + touchPadding,
                            top + imageHeight + touchPadding
                        )
                    }
                }

                is MarkupOperation.Cover -> {
                    val coverWidth = (targetWidth * operation.widthRatio).coerceAtLeast(40f)
                    val coverHeight = (targetHeight * operation.heightRatio).coerceAtLeast(40f)
                    val left = operation.xRatio * targetWidth
                    val top = operation.yRatio * targetHeight
                    RectF(
                        left - touchPadding,
                        top - touchPadding,
                        left + coverWidth + touchPadding,
                        top + coverHeight + touchPadding
                    )
                }
            }
        }

        private fun configureTextPaint(
            textPaint: Paint,
            color: Int,
            textSizeRatio: Float,
            isBold: Boolean,
            minDimension: Float,
            underline: Boolean
        ) {
            textPaint.color = color
            textPaint.textSize =
                (minDimension * textSizeRatio).coerceIn(18f, 84f)
            textPaint.typeface = if (isBold) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }
            textPaint.isFakeBoldText = isBold
            textPaint.isUnderlineText = underline
            val isLightText = isLightColor(color)
            val shadowRadius = if (isLightText) {
                (minDimension * 0.010f).coerceAtLeast(2.5f)
            } else {
                (minDimension * 0.0045f).coerceAtLeast(1.3f)
            }
            textPaint.setShadowLayer(
                shadowRadius,
                0f,
                shadowRadius * 0.34f,
                if (isLightText) 0xAA132236.toInt() else 0x33000000
            )
        }

        private fun isLightColor(color: Int): Boolean {
            val luminance =
                (0.299 * Color.red(color)) + (0.587 * Color.green(color)) + (0.114 * Color.blue(color))
            return luminance >= 185
        }
    }
}
