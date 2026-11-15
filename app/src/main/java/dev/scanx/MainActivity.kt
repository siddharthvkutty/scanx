package dev.scanx

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
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
    var busy by remember { mutableStateOf(false) }

    fun newFile() = File(ctx.filesDir, "p${System.nanoTime()}.jpg")
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    val gallery = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
        scope.launch {
            for (u in uris) {
                val f = newFile()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                }
                pages += newPage(f)
            }
        }
    }
    val camera = rememberLauncherForActivityResult(TakePicture()) { ok ->
        val f = pending?.let { File(it) }
        pending = null
        if (f != null) scope.launch { if (ok) pages += newPage(f) else f.delete() }
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
            }.onFailure { toast("Save failed: ${it.message}") }
            busy = false
        }
    }

    val i = editing
    if (i != null && i in pages.indices) {
        Editor(
            page = pages[i],
            onDone = { pages[i] = it; editing = null },
            onDelete = { pages.removeAt(i).file.delete(); editing = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ gallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) { Text("Gallery") }
            Button({
                val f = newFile()
                pending = f.path
                camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", f))
            }) { Text("Camera") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            if (pages.isEmpty()) Text("Add pages from the gallery or camera")
            else LazyVerticalGrid(
                GridCells.Adaptive(140.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { itemsIndexed(pages) { idx, p -> Thumb(idx, p) { editing = idx } } }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            { save.launch("scan.pdf") },
            Modifier.fillMaxWidth(),
            enabled = pages.isNotEmpty() && !busy,
        ) { Text("Save PDF (${pages.size} pages)") }
    }
}

@Composable
fun Thumb(index: Int, page: Page, onClick: () -> Unit) {
    val img by produceState<Bitmap?>(null, page) {
        value = withContext(Dispatchers.IO) { page.render(400) }
    }
    Box(Modifier.aspectRatio(.75f).background(Color.LightGray).clickable(onClick = onClick)) {
        img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        Text("${index + 1}", Modifier.align(Alignment.TopStart).padding(6.dp))
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

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
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
                    val blue = Color(0xFF2196F3)
                    drawPath(
                        Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        },
                        blue, style = Stroke(3.dp.toPx()),
                    )
                    pts.forEach { drawCircle(blue, 14.dp.toPx(), it, alpha = .6f) }
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onDelete) { Text("Delete") }
            OutlinedButton({
                val b = bmp ?: return@OutlinedButton
                scope.launch {
                    val c = withContext(Dispatchers.Default) { detectCorners(b) }
                    if (c != null) corners = c
                    else Toast.makeText(ctx, "No page edges found, drag the corners", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Auto") }
            Button(done) { Text("Done") }
        }
    }
}
