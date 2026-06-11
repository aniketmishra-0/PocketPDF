package com.renameapk.pdfzip.reader.util

import com.renameapk.pdfzip.reader.domain.PdfPoint
import com.renameapk.pdfzip.reader.domain.PdfRect
import java.util.Locale

object JsonCodecs {
    fun encodeRects(rects: List<PdfRect>): String =
        rects.joinToString(separator = ";") { rect ->
            listOf(rect.left, rect.top, rect.right, rect.bottom).joinToString(",") { value ->
                String.format(Locale.US, "%.6f", value)
            }
        }

    fun decodeRects(raw: String?): List<PdfRect> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { encoded ->
            val parts = encoded.split(',').mapNotNull { it.toFloatOrNull() }
            if (parts.size == 4) PdfRect(parts[0], parts[1], parts[2], parts[3]) else null
        }
    }

    fun encodePoints(points: List<PdfPoint>): String =
        points.joinToString(separator = ";") { point ->
            String.format(Locale.US, "%.6f,%.6f", point.x, point.y)
        }

    fun decodePoints(raw: String?): List<PdfPoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { encoded ->
            val parts = encoded.split(',').mapNotNull { it.toFloatOrNull() }
            if (parts.size == 2) PdfPoint(parts[0], parts[1]) else null
        }
    }
}

