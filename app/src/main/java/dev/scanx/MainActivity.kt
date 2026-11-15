package dev.scanx

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

/** Pages taken out of the scan, kept (files included) until the undo bar times out. [items] = (old index, page). */
private class Undo(val msg: String, val items: List<Pair<Int, Page>>)

private fun discard(u: Undo?) = u?.items?.forEach { it.second.file.delete() }

/** The in-progress scan lives on disk, so it survives the app being closed, not just rotated. */
private fun store(ctx: Context) = File(ctx.filesDir, "pages.txt")

private fun loadPages(ctx: Context): SnapshotStateList<Page> {
    val pages = runCatching { store(ctx).readLines() }.getOrDefault(emptyList())
        .mapNotNull { runCatching { Page.decode(it) }.getOrNull() }
        .filter { it.file.exists() }
    // Photos no page points at (e.g. an undo window that never finished) are dead weight.
    ctx.filesDir.listFiles { f -> f.name.startsWith("p") && f.name.endsWith(".jpg") }
        ?.filter { f -> pages.none { it.file == f } }?.forEach { it.delete() }
    return pages.toMutableStateList()
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { loadPages(ctx) }
    LaunchedEffect(Unit) {
        snapshotFlow { pages.map { it.encode() } }.collect { lines ->
            withContext(Dispatchers.IO) { store(ctx).writeText(lines.joinToString("\n")) }
        }
    }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var camera by rememberSaveable { mutableStateOf(false) }
    var base by rememberSaveable { mutableStateOf(Filter.Grey.name) } // filter for newly added pages
    var saved by rememberSaveable { mutableStateOf<Uri?>(null) } // last PDF written, for Share / Open
    var busy by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<Undo?>(null) }
    val grid = rememberLazyGridState() // hoisted so scroll position survives a trip to the editor
    val lock = remember { Mutex() } // keeps batch camera shots in the order they were taken

    val prefs = remember { ctx.getSharedPreferences("options", Context.MODE_PRIVATE) }
    var quality by remember {
        mutableStateOf(runCatching { Quality.valueOf(prefs.getString("quality", "")!!) }.getOrDefault(Quality.Standard))
    }
    var pageSize by remember {
        mutableStateOf(runCatching { PageSize.valueOf(prefs.getString("size", "")!!) }.getOrDefault(PageSize.A4))
    }

    LaunchedEffect(undo) {
        val u = undo ?: return@LaunchedEffect
        delay(6000)
        if (undo === u) { discard(u); undo = null }
    }

    val i = editing?.takeIf { it in pages.indices }
    // Home is light; editor and camera are dark: flip the status/nav bar icons to match.
    SideEffect {
        val s = if (i != null || camera) SystemBarStyle.dark(Color.Transparent.toArgb())
        else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
        (ctx as ComponentActivity).enableEdgeToEdge(s, s)
    }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    fun add(p: Page) { saved = null; pages += p }
    /** Call after taking pages out of [pages]; shows the undo bar and drops any earlier undo for good. */
    fun removed(msg: String, items: List<Pair<Int, Page>>) { discard(undo); undo = Undo(msg, items) }
    fun restore(u: Undo) {
        saved = null
        u.items.sortedBy { it.first }.forEach { (idx, p) -> pages.add(idx.coerceAtMost(pages.size), p) }
        undo = null
    }

    val gallery = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
        scope.launch {
            for (u in uris) {
                val f = newFile(ctx)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(u)!!.use { src -> f.outputStream().use { src.copyTo(it) } }
                }
                add(newPage(f, Filter.valueOf(base)))
            }
        }
    }
    val askCamera = rememberLauncherForActivityResult(RequestPermission()) { ok ->
        if (ok) camera = true
        else toast("Camera access is off. Allow it in Settings, or add images from the gallery.")
    }
    fun openCamera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera = true
        else askCamera.launch(Manifest.permission.CAMERA)
    }
    val save = rememberLauncherForActivityResult(CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { writePdf(pages.toList(), it, quality, pageSize) }
                }
            }.onSuccess {
                pages.forEach { it.file.delete() }
                pages.clear()
                saved = uri
            }.onFailure { toast("Couldn't save the PDF: ${it.message}") }
            busy = false
        }
    }
    fun share(uri: Uri) = ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share PDF",
        ),
    )
    fun open(uri: Uri) = try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (e: ActivityNotFoundException) {
        toast("No app on this phone can open PDFs.")
    }

    if (camera) {
        CameraScreen(
            count = pages.size,
            onShot = { f -> scope.launch { lock.withLock { add(newPage(f, Filter.valueOf(base))) } } },
            onDone = { camera = false },
        )
        return
    }
    if (i != null) {
        Editor(
            page = pages[i], index = i, count = pages.size,
            onDone = { pages[i] = it; editing = null },
            onDelete = { removed("Page deleted", listOf(i to pages.removeAt(i))); editing = null },
        )
        return
    }

    if (options) AlertDialog(
        onDismissRequest = { options = false },
        containerColor = Pal.Sheet,
        title = { T("PDF options", 20, 800) },
        text = {
            Column {
                T("Quality", 13, 500, Pal.Graphite, Modifier.padding(bottom = 6.dp))
                Segmented(Quality.entries, quality, { it.label }, {
                    quality = it
                    prefs.edit().putString("quality", it.name).apply()
                }, dark = false)
                T(quality.hint, 13, 400, Pal.Graphite, Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                T("Page size", 13, 500, Pal.Graphite, Modifier.padding(bottom = 6.dp))
                Segmented(PageSize.entries, pageSize, { it.label }, {
                    pageSize = it
                    prefs.edit().putString("size", it.name).apply()
                }, dark = false)
            }
        },
        dismissButton = { Pill("Cancel", { options = false }, bg = Color.Transparent, fg = Pal.Ink) },
        confirmButton = {
            Pill("Save", {
                options = false
                save.launch("scan-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.pdf")
            }, bg = Pal.Blue, fg = Color.White)
        },
    )

    val count = if (pages.size == 1) "1 page" else "${pages.size} pages"
    val haptic = LocalHapticFeedback.current
    var dragKey by remember { mutableStateOf<String?>(null) } // file path of the page being dragged
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var lastDrag by remember { mutableStateOf(0L) } // a long-press-and-release must not also open the editor

    Column(Modifier.fillMaxSize().background(Pal.Paper).systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), "ScanX", Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            T(if (pages.isEmpty()) "New scan" else count, 26, 800, modifier = Modifier.weight(1f), track = -.6f)
            if (pages.isNotEmpty() && !busy) Pill("Clear", {
                removed(if (pages.size == 1) "Cleared 1 page" else "Cleared ${pages.size} pages", pages.mapIndexed { n, p -> n to p })
                pages.clear()
            }, bg = Pal.Mist, fg = Pal.Ink)
        }
        if (pages.isNotEmpty()) {
            T("Set every page to", 13, 500, Pal.Graphite, Modifier.padding(bottom = 6.dp))
            FilterSwitch(pages.map { it.filter }.distinct().singleOrNull(), { f ->
                base = f.name
                for (k in pages.indices) pages[k] = pages[k].copy(filter = f)
            }, dark = false)
            if (pages.size > 1) T("Touch and hold a page to move it", 13, 500, Pal.Graphite, Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(12.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val done = saved
            if (pages.isEmpty()) Column(
                Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(180.dp))
                Spacer(Modifier.height(20.dp))
                if (done != null) {
                    T("PDF saved", 22, 800, track = -.4f)
                    Spacer(Modifier.height(6.dp))
                    T("Share it, open it, or start another scan below.", 15, 400, Pal.Graphite, align = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Share", { share(done) }, bg = Pal.Blue, fg = Color.White)
                        Pill("Open", { open(done) }, bg = Pal.Mist, fg = Pal.Ink)
                    }
                } else {
                    T("Nothing scanned yet", 22, 800, track = -.4f)
                    Spacer(Modifier.height(6.dp))
                    T(
                        "Take a photo or pick images from your gallery. Page edges are found for you.",
                        15, 400, Pal.Graphite, align = TextAlign.Center,
                    )
                }
            } else LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { n ->
                                p.x >= n.offset.x && p.x < n.offset.x + n.size.width &&
                                    p.y >= n.offset.y && p.y < n.offset.y + n.size.height
                            }
                            dragKey = hit?.key as? String
                            dragOffset = Offset.Zero
                            if (hit != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = { dragKey = null; dragOffset = Offset.Zero; lastDrag = SystemClock.uptimeMillis() },
                        onDragCancel = { dragKey = null; dragOffset = Offset.Zero; lastDrag = SystemClock.uptimeMillis() },
                        onDrag = { change, delta ->
                            change.consume()
                            val key = dragKey ?: return@detectDragGesturesAfterLongPress
                            dragOffset += delta
                            // Near the top/bottom edge: scroll, and keep the page under the finger.
                            val edge = 72.dp.toPx()
                            val y = change.position.y
                            val h = grid.layoutInfo.viewportSize.height
                            val dy = if (y < edge) -24f else if (y > h - edge) 24f else 0f
                            if (dy != 0f) dragOffset += Offset(0f, grid.dispatchRawDelta(dy))
                            val items = grid.layoutInfo.visibleItemsInfo
                            val cur = items.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                            val cx = cur.offset.x + cur.size.width / 2f + dragOffset.x
                            val cy = cur.offset.y + cur.size.height / 2f + dragOffset.y
                            val target = items.firstOrNull { n ->
                                n.key != key && cx >= n.offset.x && cx < n.offset.x + n.size.width &&
                                    cy >= n.offset.y && cy < n.offset.y + n.size.height
                            }
                            val from = pages.indexOfFirst { it.file.path == key }
                            if (target != null && from >= 0) {
                                pages.add(target.index, pages.removeAt(from))
                                // The page now sits in the target's slot; shift the offset so it stays under the finger.
                                dragOffset += Offset((cur.offset.x - target.offset.x).toFloat(), (cur.offset.y - target.offset.y).toFloat())
                            }
                        },
                    )
                },
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(pages, key = { _, p -> p.file.path }) { idx, p ->
                    val dragged = p.file.path == dragKey
                    Thumb(idx, p, dragged, dragOffset, if (dragged) Modifier else Modifier.animateItem()) {
                        if (dragKey == null && SystemClock.uptimeMillis() - lastDrag > 400) editing = idx
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(
            Modifier.fillMaxWidth().padding(top = 8.dp), color = Pal.Blue, trackColor = Pal.Mist,
        )
        undo?.let { u ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(Pal.Mist).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(u.msg, 15, 500, modifier = Modifier.weight(1f))
                Pill("Undo", { restore(u) }, bg = Color.Transparent, fg = Pal.Blue)
            }
        }
        Row(
            Modifier.padding(top = 12.dp, bottom = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(Pal.Ink).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("Gallery", { gallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f), Color.Transparent, Pal.Paper)
            Pill("Camera", ::openCamera, Modifier.weight(1f), Color.Transparent, Pal.Paper)
            Pill(
                if (busy) "Saving…" else "Save PDF", { options = true },
                Modifier.weight(1.3f), Pal.Blue, Color.White, enabled = pages.isNotEmpty() && !busy,
            )
        }
    }
}

@Composable
fun Thumb(index: Int, page: Page, dragged: Boolean, dragOffset: Offset, modifier: Modifier, onClick: () -> Unit) {
    val img by produceState<Bitmap?>(null, page) {
        value = withContext(Dispatchers.IO) { page.render(400) }
    }
    Box(
        modifier.aspectRatio(.74f)
            .zIndex(if (dragged) 1f else 0f)
            .graphicsLayer {
                if (dragged) {
                    translationX = dragOffset.x; translationY = dragOffset.y
                    scaleX = 1.05f; scaleY = 1.05f
                }
            }
            .shadow(if (dragged) 18.dp else 8.dp, SheetShape, ambientColor = Pal.Ink.copy(.3f), spotColor = Pal.Ink.copy(.3f))
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
