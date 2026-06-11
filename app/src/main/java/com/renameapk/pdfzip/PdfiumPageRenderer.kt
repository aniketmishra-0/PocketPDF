package com.renameapk.pdfzip

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Page renderer backed by Pdfium instead of [android.graphics.pdf.PdfRenderer].
 *
 * Why this exists:
 *  - The platform `PdfRenderer` can hard-crash the process (native SIGSEGV /
 *    SIGABRT) on large or complex PDFs. Those crashes are uncatchable from
 *    Kotlin `try/catch`, so a single 100 MB+ file can kill the whole app.
 *  - Pdfium opens straight from a [ParcelFileDescriptor] (memory-mapped, never
 *    loads the whole file into RAM) so 800 MB - 1 GB documents open instantly,
 *    and rendering failures surface as catchable exceptions / OOM errors.
 *
 * Every page bitmap is bounded by both a maximum dimension and a hard pixel
 * budget, so no single page can allocate enough memory to OOM-kill the process.
 */
class PdfiumPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val document: PdfDocumentKt,
    val pageCount: Int,
) {

    /** Width/height of a page in PDF points (1/72 inch). */
    suspend fun pageSizePoints(pageIndex: Int): Pair<Int, Int> {
        val page = document.openPage(pageIndex)
        return try {
            page.getPageWidthPoint().coerceAtLeast(1) to page.getPageHeightPoint().coerceAtLeast(1)
        } finally {
            page.safeClose()
        }
    }

    /**
     * Renders [pageIndex] to a bitmap scaled to roughly [targetWidth] px wide,
     * clamped so the longest edge never exceeds [maxDimension] and the total
     * pixel count never exceeds [maxPixels].
     */
    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Float,
        maxDimension: Float,
        maxPixels: Long,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap {
        val page = document.openPage(pageIndex)
        try {
            val pageWidth = page.getPageWidthPoint().toFloat().coerceAtLeast(1f)
            val pageHeight = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)

            val widthScale = targetWidth.coerceAtLeast(1f) / pageWidth
            val longestEdge = max(pageWidth, pageHeight).coerceAtLeast(1f)
            val maxDimensionScale = maxDimension.coerceAtLeast(1f) / longestEdge
            val budgetScale = sqrt(
                maxPixels.coerceAtLeast(1L).toDouble() / (pageWidth.toDouble() * pageHeight.toDouble())
            ).toFloat()

            val renderScale = min(min(widthScale, maxDimensionScale), budgetScale)
                .coerceAtLeast(MIN_RENDER_SCALE)
            val width = (pageWidth * renderScale).roundToInt().coerceAtLeast(1)
            val height = (pageHeight * renderScale).roundToInt().coerceAtLeast(1)

            var bitmap: Bitmap? = null
            try {
                bitmap = Bitmap.createBitmap(width, height, config)
                bitmap.eraseColor(Color.WHITE)
                page.renderPageBitmap(
                    bitmap = bitmap,
                    startX = 0,
                    startY = 0,
                    drawSizeX = width,
                    drawSizeY = height,
                    renderAnnot = true,
                    textMask = false,
                    canvasColor = Color.TRANSPARENT,
                    pageBackgroundColor = Color.WHITE,
                )
                return bitmap
            } catch (error: Throwable) {
                bitmap?.recycle()
                throw error
            }
        } finally {
            page.safeClose()
        }
    }

    /** Releases the native document and the underlying descriptor. */
    fun close() {
        runCatching { document.safeClose() }
        runCatching { descriptor.close() }
    }

    companion object {
        private const val MIN_RENDER_SCALE = 0.2f

        // Pdfium's native layer is NOT thread-safe. The in-app reader and
        // editor can both hold a document open at the same time, so every
        // Pdfium call (open, render, close) is funnelled onto a single
        // dedicated thread to avoid concurrent native access crashing the app.
        private val core = PdfiumCoreKt(
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "pdfium-render").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        )

        /**
         * Opens [descriptor] with Pdfium. The renderer takes ownership of the
         * descriptor and closes it in [close]. On failure the caller's
         * descriptor is left untouched (close it yourself).
         */
        suspend fun open(
            descriptor: ParcelFileDescriptor,
            password: String? = null,
        ): PdfiumPageRenderer {
            val document = core.newDocument(descriptor, password)
            val pageCount = document.getPageCount()
            return PdfiumPageRenderer(descriptor, document, pageCount)
        }
    }
}
