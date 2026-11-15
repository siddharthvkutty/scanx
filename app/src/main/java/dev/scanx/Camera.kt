package dev.scanx

import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

/**
 * Live camera with an edge outline and batch capture: every shot becomes a page straight away,
 * so you can keep shooting and tap Done at the end.
 */
@Composable
fun CameraScreen(count: Int, onShot: (File) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val capture = remember { ImageCapture.Builder().build() }
    var live by remember { mutableStateOf<Pair<List<Offset>, IntSize>?>(null) } // outline + analysed frame size
    var shooting by remember { mutableStateOf(false) }
    BackHandler(onBack = onDone)

    DisposableEffect(Unit) {
        val exec = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(view.surfaceProvider)
            var miss = 0 // frames without a page; hold the last outline briefly so it doesn't flicker
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(exec) { img ->
                try {
                    val raw = img.toBitmap()
                    val deg = img.imageInfo.rotationDegrees
                    val b = if (deg == 0) raw
                    else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                    val c = detectCorners(b)
                    if (c != null) { miss = 0; live = c to IntSize(b.width, b.height) }
                    else if (++miss > 6) live = null
                } catch (e: Exception) {
                    live = null
                } finally {
                    img.close()
                }
            }
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { provider?.unbindAll(); exec.shutdown() }
    }

    fun shoot() {
        if (shooting) return
        shooting = true
        val f = newFile(ctx)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { shooting = false; onShot(f) }
                override fun onError(e: ImageCaptureException) {
                    shooting = false
                    Toast.makeText(ctx, "Couldn't take the photo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Pal.Ink).systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView({ view }, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                live?.let { (c, sz) ->
                    val (o, s) = fit(size, sz.width, sz.height)
                    cropMarks(c.map { it.at(o, s, sz.width, sz.height) }, dim = false)
                }
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(CircleShape).background(Pal.Ink.copy(alpha = .75f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                T(if (live != null) "Page found" else "Looking for page edges", 13, 600, Pal.Paper)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                T(if (count == 1) "1 page" else "$count pages", 15, 600, Pal.Paper)
            }
            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = if (shooting) .5f else 1f))
                    .clickable(enabled = !shooting, onClickLabel = "Take photo", role = Role.Button, onClick = ::shoot),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Pill("Done", onDone, bg = Pal.Blue, fg = Color.White)
            }
        }
    }
}
