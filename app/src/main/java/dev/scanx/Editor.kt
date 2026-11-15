package dev.scanx

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Where an image of w x h sits inside a box of this size: (top-left, scale). */
fun fit(box: Size, w: Int, h: Int): Pair<Offset, Float> {
    val s = minOf(box.width / w, box.height / h)
    return Offset((box.width - w * s) / 2, (box.height - h * s) / 2) to s
}

/** Fractional image coordinate -> screen position, for an image of w x h drawn at [o] with scale [s]. */
fun Offset.at(o: Offset, s: Float, w: Int, h: Int) = o + Offset(x * w * s, y * h * s)

/** Crop outline with the logo's rounded L-shaped corner brackets. [dim] darkens everything outside. */
fun DrawScope.cropMarks(pts: List<Offset>, dim: Boolean, brackets: Boolean = true) {
    val quad = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    if (dim) clipPath(quad, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = .5f)) }
    drawPath(quad, Color.White.copy(alpha = .7f), style = Stroke(1.5.dp.toPx()))
    if (!brackets) return
    for (n in 0..3) {
        val p = pts[n]
        val da = pts[(n + 1) % 4] - p
        val db = pts[(n + 3) % 4] - p
        val len = minOf(28.dp.toPx(), da.getDistance() / 2, db.getDistance() / 2)
        val bracket = Path().apply {
            val a = p + da / da.getDistance().coerceAtLeast(1f) * len
            val c = p + db / db.getDistance().coerceAtLeast(1f) * len
            moveTo(a.x, a.y); lineTo(p.x, p.y); lineTo(c.x, c.y)
        }
        drawPath(bracket, Color.Black.copy(alpha = .4f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(bracket, Pal.Cyan, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun Editor(
    page: Page, index: Int, count: Int,
    onDone: (Page) -> Unit, onDelete: () -> Unit,
) {
    val raw by produceState<Bitmap?>(null) { value = withContext(Dispatchers.IO) { loadBitmap(page.file, 1600) } }
    var corners by remember { mutableStateOf(page.corners) }
    var filter by remember { mutableStateOf(page.filter) }
    var turns by remember { mutableStateOf(page.turns) }
    var active by remember { mutableStateOf(-1) } // corner being dragged, -1 when none (drives the magnifier)
    val bmp = remember(raw, turns) { raw?.turn(turns) }
    // What is drawn: B&W scan is previewed with the same threshold the PDF uses.
    val shown by produceState<Bitmap?>(bmp, bmp, filter) {
        value = if (filter == Filter.Scan && bmp != null) withContext(Dispatchers.Default) { bmp.binarize() } else bmp
    }
    val done = { onDone(page.copy(corners = corners, filter = filter, turns = turns)) }
    BackHandler(onBack = done)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Pal.Ink).systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Adjust edges", 22, 800, Pal.Paper, track = -.4f)
                T("Page ${index + 1} of $count. Drag a corner to move it.", 13, 500, Color(0xFFA8A8A5))
            }
            Pill("Detect edges", {
                val b = bmp ?: return@Pill
                scope.launch {
                    val c = withContext(Dispatchers.Default) { detectCorners(b) }
                    if (c != null) corners = c
                    else Toast.makeText(ctx, "No page edges found. Drag the corners instead.", Toast.LENGTH_SHORT).show()
                }
            }, bg = Pal.InkRaised, fg = Pal.Paper)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val b = shown
            if (b != null) {
                val img = remember(b) { b.asImageBitmap() }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(b) {
                        var k = 0 // corner being dragged = nearest to the touch-down point
                        detectDragGestures(
                            onDragStart = { p ->
                                val (o, s) = fit(size.toSize(), b.width, b.height)
                                k = corners.indices.minBy { (corners[it].at(o, s, b.width, b.height) - p).getDistance() }
                                active = k
                            },
                            onDragEnd = { active = -1 },
                            onDragCancel = { active = -1 },
                        ) { change, d ->
                            change.consume()
                            val (_, s) = fit(size.toSize(), b.width, b.height)
                            val c = corners[k]
                            corners = corners.toMutableList().also {
                                it[k] = Offset(
                                    (c.x + d.x / (b.width * s)).coerceIn(0f, 1f),
                                    (c.y + d.y / (b.height * s)).coerceIn(0f, 1f),
                                )
                            }
                        }
                    },
                ) {
                    val (o, s) = fit(size, b.width, b.height)
                    drawImage(
                        img,
                        dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                        dstSize = IntSize((b.width * s).roundToInt(), (b.height * s).roundToInt()),
                        colorFilter = filter.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                    )
                    val pts = corners.map { it.at(o, s, b.width, b.height) }
                    cropMarks(pts, dim = true)
                    if (active >= 0) {
                        // Magnifier: 3x zoom around the dragged corner, floated above the finger.
                        val c = pts[active]
                        val r = 60.dp.toPx()
                        var l = Offset(c.x.coerceIn(r, size.width - r), c.y - 1.7f * r)
                        if (l.y < r) l = Offset(l.x, c.y + 1.7f * r)
                        val z = 3f
                        val org = l - (c - o) * z
                        clipPath(Path().apply { addOval(Rect(l - Offset(r, r), Size(2 * r, 2 * r))) }) {
                            drawRect(Pal.Ink)
                            drawImage(
                                img,
                                dstOffset = IntOffset(org.x.roundToInt(), org.y.roundToInt()),
                                dstSize = IntSize((b.width * s * z).roundToInt(), (b.height * s * z).roundToInt()),
                                colorFilter = filter.matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                            )
                            cropMarks(pts.map { l + (it - c) * z }, dim = false, brackets = false)
                        }
                        drawCircle(Color.White, r, l, style = Stroke(3.dp.toPx()))
                        val tick = 10.dp.toPx()
                        drawLine(Pal.Cyan, l - Offset(tick, 0f), l + Offset(tick, 0f), 2.dp.toPx())
                        drawLine(Pal.Cyan, l - Offset(0f, tick), l + Offset(0f, tick), 2.dp.toPx())
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FilterSwitch(filter, { filter = it }, dark = true)
        Row(Modifier.padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Delete", onDelete, Modifier.weight(1f), Color.Transparent, Pal.RedOnDark)
            Pill("Rotate", {
                turns = (turns + 1) % 4
                corners = corners.rotateCw()
            }, Modifier.weight(1f), Pal.InkRaised, Pal.Paper)
            Pill("Done", done, Modifier.weight(1.4f), Pal.Blue, Color.White)
        }
    }
}
