package dev.scanx

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saved as strings so pages survive rotation AND the process being killed while the camera app is open.
    val pages = rememberSaveable(
        saver = listSaver<SnapshotStateList<Page>, String>(
            save = { list -> list.map { it.encode() } },
            restore = { list -> list.map { Page.decode(it) }.toMutableStateList() },
        ),
    ) { mutableStateListOf<Page>() }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var base by rememberSaveable { mutableStateOf(Filter.Grey.name) } // filter for newly added pages
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val i =editing?.takeIf { it in pages.indices }
    // Home is light, editor is dark: flip the status/nav bar icons to match.
    SideEffect {
        val s = if (i != null) SystemBarStyle.dark(Color.Transparent.toArgb())
        else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
        (ctx as ComponentActivity).enableEdgeToEdge(s, s)
    }

    fun newFile() = File(ctx.filesDir, "p${System.nanoTime()}.jpg")
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    val gallery = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
        scope.launch {
            for (u in uris) {
                val f = newFile()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(u)!!.use { src -> f.outputStream().use { src.copyTo(it) } }
                }
                pages += newPage(f, Filter.valueOf(base))
            }
        }
    }
    val camera = rememberLauncherForActivityResult(TakePicture()) { ok ->
        val f = pending?.let { File(it) }
        pending = null
        if (f != null) scope.launch { if (ok) pages += newPage(f, Filter.valueOf(base)) else f.delete() }
    }
    val save = rememberLauncherForActivityResult(CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { writePdf(pages.toList(), it) }
                }
            }.onSuccess {
                pages.forEach { it.file.delete() }
                pages.clear()
                toast("PDF saved")
            }.onFailure { toast("Couldn't save the PDF: ${it.message}") }
            busy = false
        }
    }

    if (i != null) {
        Editor(
            page = pages[i],
            onDone = { pages[i] = it; editing = null },
            onDelete = { pages.removeAt(i).file.delete(); editing = null },
        )
        return
    }

    val count = if (pages.size == 1) "1 page" else "${pages.size} pages"
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = Pal.Sheet,
        title = { T("Clear all pages?", 20, 800) },
        text = { T("This removes $count from this scan. PDFs you already saved are not affected.", 15, 400, Pal.Graphite) },
        dismissButton = { Pill("Keep", { confirmClear = false }, bg = Color.Transparent, fg = Pal.Ink) },
        confirmButton = {
            Pill("Clear", {
                pages.forEach { it.file.delete() }
                pages.clear()
                confirmClear = false
            }, bg = Pal.Red, fg = Color.White)
        },
    )

    Column(Modifier.fillMaxSize().background(Pal.Paper).systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), "ScanX", Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            T(if (pages.isEmpty()) "New scan" else count, 26, 800, modifier = Modifier.weight(1f), track = -.6f)
            if (pages.isNotEmpty() && !busy) Pill("Clear", { confirmClear = true }, bg = Pal.Mist, fg = Pal.Ink)
        }
        if (pages.isNotEmpty()) {
            T("Set every page to", 13, 500, Pal.Graphite, Modifier.padding(bottom = 6.dp))
            FilterSwitch(pages.map { it.filter }.distinct().singleOrNull(), { f ->
                base = f.name
                for (k in pages.indices) pages[k] = pages[k].copy(filter = f)
            }, dark = false)
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (pages.isEmpty()) Column(
                Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(180.dp))
                Spacer(Modifier.height(20.dp))
                T("Nothing scanned yet", 22, 800, track = -.4f)
                Spacer(Modifier.height(6.dp))
                T(
                    "Take a photo or pick images from your gallery. Page edges are found for you.",
                    15, 400, Pal.Graphite, align = TextAlign.Center,
                )
            } else LazyVerticalGrid(
                GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { itemsIndexed(pages) { idx, p -> Thumb(idx, p) { editing = idx } } }
        }
        if (busy) LinearProgressIndicator(
            Modifier.fillMaxWidth().padding(top = 8.dp), color = Pal.Blue, trackColor = Pal.Mist,
        )
        Row(
            Modifier.padding(top = 12.dp, bottom = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(Pal.Ink).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("Gallery", { gallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f), Color.Transparent, Pal.Paper)
            Pill("Camera", {
                val f = newFile()
                pending = f.path
                camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", f))
            }, Modifier.weight(1f), Color.Transparent, Pal.Paper)
            Pill(
                if (busy) "Saving…" else "Save PDF", { save.launch("scan.pdf") },
                Modifier.weight(1.3f), Pal.Blue, Color.White, enabled = pages.isNotEmpty() && !busy,
            )
        }
    }
}

@Composable
fun Thumb(index: Int, page: Page, onClick: () -> Unit) {
    val img by produceState<Bitmap?>(null, page) {
        value = withContext(Dispatchers.IO) { page.render(400) }
    }
    Box(
        Modifier.aspectRatio(.74f)
            .shadow(8.dp, SheetShape, ambientColor = Pal.Ink.copy(.3f), spotColor = Pal.Ink.copy(.3f))
            .clip(SheetShape).background(Pal.Sheet)
            .clickable(onClickLabel = "Edit page ${index + 1}", role = Role.Button, onClick = onClick)
            .drawWithContent {
                drawContent()
                val c = size.width * .22f // the folded-over corner
                drawPath(
                    Path().apply { moveTo(size.width - c, 0f); lineTo(size.width, c); lineTo(size.width - c, c); close() },
                    Pal.Mist,
                )
            },
    ) {
        img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit) }
        Box(
            Modifier.align(Alignment.BottomStart).padding(10.dp).size(26.dp).clip(CircleShape).background(Pal.Ink),
            contentAlignment = Alignment.Center,
        ) { T("${index + 1}", 12, 600, Pal.Paper) }
    }
}

/** Where the image sits inside a box of this size: (top-left, scale). */
private fun fit(box: Size, w: Int, h: Int): Pair<Offset, Float> {
    val s = minOf(box.width / w, box.height / h)
    return Offset((box.width - w * s) / 2, (box.height - h * s) / 2) to s
}

private fun Offset.at(o: Offset, s: Float, b: Bitmap) = o + Offset(x * b.width * s, y * b.height * s)

@Composable
fun Editor(page: Page, onDone: (Page) -> Unit, onDelete: () -> Unit) {
    val bmp by produceState<Bitmap?>(null) { value = withContext(Dispatchers.IO) { loadBitmap(page.file, 1600) } }
    var corners by remember { mutableStateOf(page.corners) }
    var filter by remember { mutableStateOf(page.filter) }
    val done = { onDone(page.copy(corners = corners, filter = filter)) }
    BackHandler(onBack = done)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Pal.Ink).systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Adjust edges", 22, 800, Pal.Paper, track = -.4f)
                T("Drag a corner to move it", 13, 500, Color(0xFFA8A8A5))
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
            val b = bmp
            if (b != null) {
                val img = remember(b) { b.asImageBitmap() }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(b) {
                        var k = 0 // corner being dragged = nearest to the touch-down point
                        detectDragGestures(onDragStart = { p ->
                            val (o, s) = fit(size.toSize(), b.width, b.height)
                            k = corners.indices.minBy { (corners[it].at(o, s, b) - p).getDistance() }
                        }) { change, d ->
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
                    val pts = corners.map { it.at(o, s, b) }
                    val quad = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    // Dim everything outside the crop, like the paper on a desk.
                    clipPath(quad, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = .5f)) }
                    drawPath(quad, Color.White.copy(alpha = .7f), style = Stroke(1.5.dp.toPx()))
                    // Corner brackets, same rounded L as the logo's viewfinder.
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
            }
        }
        Spacer(Modifier.height(12.dp))
        FilterSwitch(filter, { filter = it }, dark = true)
        Row(
            Modifier.padding(top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("Delete", onDelete, Modifier.weight(1f), Color.Transparent, Pal.RedOnDark)
            Pill("Done", done, Modifier.weight(2f), Pal.Blue, Color.White)
        }
    }
}
