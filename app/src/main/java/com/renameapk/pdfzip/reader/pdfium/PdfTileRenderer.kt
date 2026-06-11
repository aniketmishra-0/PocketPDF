package com.renameapk.pdfzip.reader.pdfium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import io.legere.pdfiumandroid.suspend.PdfPageKt

object PdfTileRenderer {
    private val darkModePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    suspend fun renderPageTile(page: PdfPageKt, request: TileRenderRequest): Bitmap? =
        try {
            val bitmap = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.renderPageBitmap(
                bitmap = bitmap,
                startX = -request.left,
                startY = -request.top,
                drawSizeX = request.pageWidthPx,
                drawSizeY = request.pageHeightPx,
                renderAnnot = true,
                textMask = false,
                canvasColor = Color.TRANSPARENT,
                pageBackgroundColor = Color.WHITE,
            )
            if (request.darkMode) bitmap.invertedCopy() else bitmap
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun Bitmap.invertedCopy(): Bitmap {
        val output = Bitmap.createBitmap(width, height, config ?: Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(this, 0f, 0f, darkModePaint)
        recycle()
        return output
    }
}

