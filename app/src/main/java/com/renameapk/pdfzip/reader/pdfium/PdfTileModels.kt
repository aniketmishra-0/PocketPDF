package com.renameapk.pdfzip.reader.pdfium

import android.graphics.Rect

data class TileKey(
    val documentKey: String,
    val pageIndex: Int,
    val zoomBucket: Int,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val darkMode: Boolean = false,
)

data class TileRenderRequest(
    val key: TileKey,
    val pageIndex: Int,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val darkMode: Boolean,
)

object TilePlanner {
    private const val TILE_SIZE_PX = 768
    private const val PRELOAD_MARGIN_TILES = 1

    fun zoomBucket(zoom: Float): Int = (zoom.coerceIn(1f, 10f) * 100).toInt()

    fun requestsForVisibleRect(
        documentKey: String,
        pageIndex: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        visibleRect: Rect,
        zoom: Float,
        darkMode: Boolean,
    ): List<TileRenderRequest> {
        if (pageWidthPx <= 0 || pageHeightPx <= 0) return emptyList()
        val expanded = Rect(visibleRect).apply {
            inset(-TILE_SIZE_PX * PRELOAD_MARGIN_TILES, -TILE_SIZE_PX * PRELOAD_MARGIN_TILES)
            left = left.coerceIn(0, pageWidthPx)
            top = top.coerceIn(0, pageHeightPx)
            right = right.coerceIn(0, pageWidthPx)
            bottom = bottom.coerceIn(0, pageHeightPx)
        }
        if (expanded.width() <= 0 || expanded.height() <= 0) return emptyList()

        val firstColumn = expanded.left / TILE_SIZE_PX
        val lastColumn = (expanded.right - 1).coerceAtLeast(0) / TILE_SIZE_PX
        val firstRow = expanded.top / TILE_SIZE_PX
        val lastRow = (expanded.bottom - 1).coerceAtLeast(0) / TILE_SIZE_PX
        val bucket = zoomBucket(zoom)

        return buildList {
            for (row in firstRow..lastRow) {
                for (column in firstColumn..lastColumn) {
                    val left = column * TILE_SIZE_PX
                    val top = row * TILE_SIZE_PX
                    val width = minOf(TILE_SIZE_PX, pageWidthPx - left).coerceAtLeast(1)
                    val height = minOf(TILE_SIZE_PX, pageHeightPx - top).coerceAtLeast(1)
                    val key = TileKey(
                        documentKey = documentKey,
                        pageIndex = pageIndex,
                        zoomBucket = bucket,
                        pageWidthPx = pageWidthPx,
                        pageHeightPx = pageHeightPx,
                        left = left,
                        top = top,
                        width = width,
                        height = height,
                        darkMode = darkMode,
                    )
                    add(
                        TileRenderRequest(
                            key = key,
                            pageIndex = pageIndex,
                            pageWidthPx = pageWidthPx,
                            pageHeightPx = pageHeightPx,
                            left = left,
                            top = top,
                            width = width,
                            height = height,
                            darkMode = darkMode,
                        ),
                    )
                }
            }
        }
    }
}

