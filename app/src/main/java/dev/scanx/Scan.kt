package dev.scanx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale
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

/** Scan has no colour matrix: it is an adaptive threshold done by OpenCV, see [binarize]. */
enum class Filter(val label: String, val matrix: FloatArray?) {
    Original("Original", null),
    Grey("Greyscale", grey(1f, 0f)),
    Scan("B&W scan", null),
}

/** PDF size/sharpness trade-off: longest photo side kept, and JPEG quality. */
enum class Quality(val label: String, val maxSide: Int, val jpeg: Int, val hint: String) {
    Small("Small", 1400, 60, "Smallest file. Fine print can look soft."),
    Standard("Standard", 2000, 80, "Good for most documents."),
    High("High", 2600, 90, "Sharpest text, biggest file."),
}

/** Page size in points; [Fit] makes each page match its scan. */
enum class PageSize(val label: String, val w: Int, val h: Int) {
    A4("A4", 595, 842),
    Letter("Letter", 612, 792),
    Fit("Fit to scan", 0, 0),
}

/**
 * One scanned page. The photo itself is never modified: [corners] are fractions (0..1) of the
 * rotated image, order TL, TR, BR, BL; [turns] is the number of clockwise quarter turns.
 */
data class Page(
    val file: File,
    val corners: List<Offset> = listOf(Offset(.05f, .05f), Offset(.95f, .05f), Offset(.95f, .95f), Offset(.05f, .95f)),
    val filter: Filter = Filter.Grey,
    val turns: Int = 0,
) {
    fun encode() = listOf(file.path, filter.name, corners.joinToString(",") { "${it.x},${it.y}" }, turns).joinToString("|")

    /** Rotate, perspective-correct the quad into a rectangle, then apply the filter. */
    fun render(maxSide: Int): Bitmap {
        val src = loadBitmap(file, maxSide).turn(turns)
        val p = corners.map { PointF(it.x * src.width, it.y * src.height) }
        fun d(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
        val w = max(d(p[0], p[1]), d(p[3], p[2])).roundToInt().coerceAtLeast(1)
        val h = max(d(p[0], p[3]), d(p[1], p[2])).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE) // JPEG has no alpha; keep warped edges white, not black
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y), 0,
            floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4,
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = filter.matrix?.let { ColorMatrixColorFilter(it) }
        Canvas(out).drawBitmap(src, m, paint)
        src.recycle()
        if (filter != Filter.Scan) return out
        val bw = out.binarize()
        if (bw !== out) out.recycle()
        return bw
    }

    companion object {
        fun decode(s: String): Page {
            val parts = s.split("|")
            val n = parts[2].split(",").map { it.toFloat() }
            return Page(
                File(parts[0]),
                List(4) { Offset(n[2 * it], n[2 * it + 1]) },
                Filter.valueOf(parts[1]),
                parts.getOrNull(3)?.toInt() ?: 0,
            )
        }
    }
}

/** Corners after turning the image a quarter turn clockwise: (x, y) -> (1 - y, x), and the old BL becomes TL. */
fun List<Offset>.rotateCw(): List<Offset> =
    listOf(this[3], this[0], this[1], this[2]).map { Offset(1 - it.y, it.x) }

fun Bitmap.turn(n: Int): Bitmap =
    if (n % 4 == 0) this
    else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(90f * (n % 4)) }, true)

fun newFile(ctx: Context) = File(ctx.filesDir, "p${System.nanoTime()}.jpg")

/** Decodes with EXIF rotation applied, downsampled so the long side is roughly <= 2*maxSide. */
fun loadBitmap(f: File, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        dec.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / maxSide))
    }

private class Counter(private val o: OutputStream) : OutputStream() {
    var n = 0L
    override fun write(b: Int) { o.write(b); n++ }
    override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); n += len }
}

/**
 * Hand-written PDF, one JPEG per page. Android's PdfDocument stores pixels losslessly, which made
 * multi-MB pages; JPEG is roughly 10x smaller.
 * Objects: 1 catalog, 2 page tree, then per page k: 3+3k page, 4+3k content stream, 5+3k image.
 */
fun writePdf(pages: List<Page>, out: OutputStream, quality: Quality, size: PageSize) {
    val c = Counter(out)
    val xref = ArrayList<Long>() // xref[id - 1] = byte offset of object id
    fun s(t: String) = c.write(t.toByteArray(Charsets.ISO_8859_1))
    fun begin(id: Int) { xref.add(c.n); s("$id 0 obj\n") }

    s("%PDF-1.4\n")
    begin(1); s("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    begin(2)
    s("<< /Type /Pages /Count ${pages.size} /Kids [${pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }}] >>\nendobj\n")
    pages.forEachIndexed { k, p ->
        val b = p.render(quality.maxSide)
        val iw = b.width
        val ih = b.height
        val jpg = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, it) }.toByteArray()
        b.recycle()
        val (w, h) = if (size == PageSize.Fit) 595 to (595f * ih / iw).roundToInt() else size.w to size.h
        val scale = minOf(w.toFloat() / iw, h.toFloat() / ih) // image is centred and fitted inside the page
        val dw = iw * scale
        val dh = ih * scale
        val id = 3 + 3 * k
        begin(id)
        s("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources << /XObject << /Im0 ${id + 2} 0 R >> >> /Contents ${id + 1} 0 R >>\nendobj\n")
        val content = String.format(Locale.ROOT, "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q", dw, dh, (w - dw) / 2, (h - dh) / 2)
        begin(id + 1)
        s("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        begin(id + 2)
        s("<< /Type /XObject /Subtype /Image /Width $iw /Height $ih /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>\nstream\n")
        c.write(jpg)
        s("\nendstream\nendobj\n")
    }
    val start = c.n
    s("xref\n0 ${xref.size + 1}\n0000000000 65535 f \n")
    xref.forEach { s(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
    s("trailer\n<< /Size ${xref.size + 1} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
    c.flush()
}
