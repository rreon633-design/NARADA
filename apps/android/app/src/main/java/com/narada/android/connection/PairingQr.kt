package com.narada.android.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.narada.android.ui.NaradaTheme
import com.narada.connection.*
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result Intent carries no secret; activity-result saved state must never serialize the code. */
@Singleton
class PairingCapture @Inject constructor() {
    private var code: String? = null
    private var deadline = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expiry = Runnable { clear() }
    @Synchronized fun put(value: String) {
        val parsed = PairCode.parse(value)
        handler.removeCallbacks(expiry)
        val remaining = (parsed.expiryMillis() - System.currentTimeMillis()).coerceIn(1, 150_000)
        deadline = SystemClock.elapsedRealtime() + remaining; code = value
        handler.postDelayed(expiry, remaining)
    }
    @Synchronized fun take(): String? {
        val value = code?.takeIf { SystemClock.elapsedRealtime() < deadline }
        clear(); return value
    }
    @Synchronized fun clear() { code = null; deadline = 0; handler.removeCallbacks(expiry) }
}
object QrDecoder {
    fun decode(bitmap: Bitmap): String {
        demand(bitmap.width in 1..2048 && bitmap.height in 1..2048 && bitmap.width.toLong() * bitmap.height <= 2_500_000, "QR_UNAVAILABLE")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val reader = MultiFormatReader()
        val result = try { reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
        catch (_: Exception) { fail("QR_UNAVAILABLE") } finally { reader.reset(); pixels.fill(0) }
        demand(result.text.toByteArray(Charsets.UTF_8).size <= Wire.MAX_CODE, "BODY_TOO_LARGE")
        PairCode.parse(result.text)
        return result.text
    }
}
@Singleton
class PairingImageReader @Inject constructor(@param:ApplicationContext private val context: Context) {
    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        demand(uri.scheme == "content", "QR_UNAVAILABLE")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; demand(n > 0 && out.size() + n <= 8 * 1024 * 1024, "QR_UNAVAILABLE"); out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: fail("QR_UNAVAILABLE")
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            demand(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000 && bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp"), "QR_UNAVAILABLE")
            var sample = 1
            while (bounds.outWidth / sample > 1536 || bounds.outHeight / sample > 1536) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: fail("QR_UNAVAILABLE")
            try { QrDecoder.decode(bitmap) } finally { bitmap.recycle() }
        } finally { bytes.fill(0) }
    }
}

@AndroidEntryPoint
class ScanPairingActivity : ComponentActivity() {
    @Inject lateinit var capture: PairingCapture
    private var permitted by mutableStateOf(false)
    private var unavailable by mutableStateOf(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val completed = AtomicBoolean(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> permitted = allowed; unavailable = !allowed }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        capture.clear()
        permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent { NaradaTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Scan your computer’s code", style = MaterialTheme.typography.headlineMedium)
                Text("Frames are decoded on this phone. Nothing is uploaded or saved. The invitation expires after two minutes.")
                if (permitted && !unavailable) CameraPreview(Modifier.weight(1f))
                else {
                    Text(if (unavailable) "Camera access is unavailable. Go back to import a QR image or paste a code." else "Camera access is optional and used only while this scanner is visible.")
                    if (!unavailable) Button({ permission.launch(Manifest.permission.CAMERA) }, Modifier.heightIn(min = 48.dp)) { Text("Allow camera for scanning") }
                }
                OutlinedButton({ capture.clear(); finish() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel scanner") }
            }
        } }
    }
    @Composable private fun CameraPreview(modifier: Modifier = Modifier) {
        val view = remember { PreviewView(this@ScanPairingActivity).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
        AndroidView({ view }, modifier)
        DisposableEffect(view) {
            val future = ProcessCameraProvider.getInstance(this@ScanPairingActivity)
            var provider: ProcessCameraProvider? = null
            var disposed = false
            val started = SystemClock.elapsedRealtime(); var lastFrame = 0L
            future.addListener({
                if (!disposed && !isFinishing) try {
                    if (ContextCompat.checkSelfPermission(this@ScanPairingActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { unavailable = true; return@addListener }
                    provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER)).build()).build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            val now = SystemClock.elapsedRealtime()
                            if (completed.get() || now - lastFrame < 400 || now - started > 120_000 || image.width.toLong() * image.height > 2_000_000) return@setAnalyzer
                            lastFrame = now
                            val bitmap = image.toBitmap()
                            val code = try { QrDecoder.decode(bitmap) } finally { bitmap.recycle() }
                            if (completed.compareAndSet(false, true)) runOnUiThread {
                                if (!isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                                    if (runCatching { capture.put(code) }.isSuccess) { setResult(RESULT_OK); finish() } else { completed.set(false); unavailable = true }
                                } else { completed.set(false) }
                            }
                        } catch (_: Exception) { /* A frame without a valid code is not an error or a log event. */ }
                        finally { image.close() }
                    }
                    provider?.bindToLifecycle(this@ScanPairingActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { unavailable = true }
            }, ContextCompat.getMainExecutor(this@ScanPairingActivity))
            val timeout = Runnable { if (!disposed) { unavailable = true; provider?.unbindAll() } }
            view.postDelayed(timeout, 120_000)
            onDispose { disposed = true; view.removeCallbacks(timeout); provider?.unbindAll() }
        }
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
