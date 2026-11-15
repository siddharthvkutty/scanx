package dev.scanx

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private val cvReady by lazy { OpenCVLoader.initLocal() }

/**
 * Finds the largest convex 4-sided contour (the sheet of paper) and returns its corners
 * as fractions of the image, order TL, TR, BR, BL. Null if nothing paper-like is found.
 */
fun detectCorners(src: Bitmap): List<Offset>? {
    if (!cvReady) return null
    val m = Mat()
    Utils.bitmapToMat(src, m)
    val k = 500.0 / maxOf(m.cols(), m.rows())
    Imgproc.resize(m, m, Size(), k, k, Imgproc.INTER_AREA)
    Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.GaussianBlur(m, m, Size(5.0, 5.0), 0.0)
    Imgproc.Canny(m, m, 75.0, 200.0)
    Imgproc.dilate(m, m, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
    val contours = ArrayList<MatOfPoint>()
    Imgproc.findContours(m, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
    val w = m.cols().toFloat()
    val h = m.rows().toFloat()
    m.release()
    for (c in contours.sortedByDescending { Imgproc.contourArea(it) }.take(5)) {
        if (Imgproc.contourArea(c) < .2 * w * h) break // too small to be the page
        val c2f = MatOfPoint2f(*c.toArray())
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(c2f, approx, .02 * Imgproc.arcLength(c2f, true), true)
        val p = approx.toArray()
        if (p.size != 4 || !Imgproc.isContourConvex(MatOfPoint(*p))) continue
        val tl = p.minBy { it.x + it.y }; val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }; val bl = p.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl).map {
            Offset((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f))
        }
    }
    return null
}

/** New page with auto-detected borders, falling back to the default inset. */
suspend fun newPage(f: File): Page = withContext(Dispatchers.IO) {
    val c = detectCorners(loadBitmap(f, 500))
    if (c != null) Page(f, c) else Page(f)
}
