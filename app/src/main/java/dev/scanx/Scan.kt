package dev.scanx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Offset
import java.io.File
import java.io.OutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private fun grey(contrast: Float, offset: Float) = FloatArray(20).also {
    for (r in 0..2) {
        it[r * 5] = .213f * contrast; it[r * 5 + 1] = .715f * contrast
        it[r * 5 + 2] = .072f * contrast; it[r * 5 + 4] = offset
    }
    it[18] = 1f
}

// ponytail: contrast-stretched greyscale, not adaptive threshold; uneven lighting/shadows won't be flattened.
enum class Filter(val label: String, val matrix: FloatArray?) {
    Original("Original", null),
    Grey("Greyscale", grey(1f, 0f)),
    Scan("B&W scan", grey(1.8f, -72f)),
}

/** Crop quad corners are fractions (0..1) of the image, order TL, TR, BR, BL. */
data class Page(
    val file: File,
    val corners: List<Offset> = listOf(Offset(.05f, .05f), Offset(.95f, .05f), Offset(.95f, .95f), Offset(.05f, .95f)),
    val filter: Filter = Filter.Grey,
) {
    fun encode() = "${file.path}|${filter.name}|" + corners.joinToString(",") { "${it.x},${it.y}" }

    /** Perspective-correct the quad into a rectangle and apply the filter. */
    fun render(maxSide: Int): Bitmap {
        val src = loadBitmap(file, maxSide)
        val p = corners.map { PointF(it.x * src.width, it.y * src.height) }
        fun d(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
        val w = max(d(p[0], p[1]), d(p[3], p[2])).roundToInt().coerceAtLeast(1)
        val h = max(d(p[0], p[3]), d(p[1], p[2])).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y), 0,
            floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4,
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = filter.matrix?.let { ColorMatrixColorFilter(it) }
        Canvas(out).drawBitmap(src, m, paint)
        src.recycle()
        return out
    }

    companion object {
        fun decode(s: String): Page {
            val (f, flt, c) = s.split("|")
            val n = c.split(",").map { it.toFloat() }
            return Page(File(f), List(4) { Offset(n[2 * it], n[2 * it + 1]) }, Filter.valueOf(flt))
        }
    }
}

/** Decodes with EXIF rotation applied, downsampled so the long side is roughly <= 2*maxSide. */
fun loadBitmap(f: File, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        dec.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / maxSide))
    }

fun writePdf(pages: List<Page>, out: OutputStream) {
    val doc = PdfDocument()
    pages.forEachIndexed { i, p ->
        val b = p.render(2000)
        val w = 595 // A4 width in pt; height follows the scan's aspect ratio
        val h = (w.toFloat() * b.height / b.width).roundToInt()
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
        pg.canvas.drawBitmap(b, null, Rect(0, 0, w, h), null)
        doc.finishPage(pg)
        b.recycle()
    }
    doc.writeTo(out)
    doc.close()
}
