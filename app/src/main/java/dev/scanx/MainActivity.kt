package dev.scanx

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
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

/** The in-progress scan lives on disk, so it survives the app being closed, not just rotated. */
private fun store(ctx: android.content.Context) = File(ctx.filesDir, "pages.txt")

private fun loadPages(ctx: android.content.Context): SnapshotStateList<Page> =
    runCatching { store(ctx).readLines() }.getOrDefault(emptyList())
        .mapNotNull { runCatching { Page.decode(it) }.getOrNull() }
        .filter { it.file.exists() }
        .toMutableStateList()

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
    var confirmClear by remember { mutableStateOf(false) }
    val lock = remember { Mutex() } // keeps batch camera shots in the order they were taken

    val i = editing?.takeIf { it in pages.indices }
    // Home is light; editor and camera are dark: flip the status/nav bar icons to match.
    SideEffect {
        val s = if (i != null || camera) SystemBarStyle.dark(Color.Transparent.toArgb())
        else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
        (ctx as ComponentActivity).enableEdgeToEdge(s, s)
    }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    fun add(p: Page) { saved = null; pages += p }

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
                    ctx.contentResolver.openOutputStream(uri)!!.use { writePdf(pages.toList(), it) }
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
            onDelete = { pages.removeAt(i).file.delete(); editing = null },
            onMove = { d ->
                val j = i + d
                if (j in pages.indices) { pages.add(j, pages.removeAt(i)); editing = j }
            },
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
                saved = null
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
            Pill("Camera", ::openCamera, Modifier.weight(1f), Color.Transparent, Pal.Paper)
            Pill(
                if (busy) "Saving…" else "Save PDF",
                { save.launch("scan-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.pdf") },
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
