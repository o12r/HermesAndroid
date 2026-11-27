package dev.oscar.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** Scans the pairing QR code (hermes://pair?...) with CameraX + zxing and returns it as the activity result. */
class ScanActivity : ComponentActivity() {
    private val exec = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    @Volatile private var done = false
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(PreviewView(this).apply { id = 1001 }, FrameLayout.LayoutParams(-1, -1))
        root.addView(TextView(this).apply { text = getString(R.string.scan_hint); setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(32, 96, 32, 32) },
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start() else perm.launch(Manifest.permission.CAMERA)
    }

    private fun start() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(findViewById<PreviewView>(1001).surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { img ->
                try {
                    if (done) return@setAnalyzer
                    val plane = img.planes[0]; val buf = plane.buffer
                    val data = ByteArray(buf.remaining()); buf.get(data)
                    val rowStride = plane.rowStride
                    val src = PlanarYUVLuminanceSource(data, rowStride, img.height, 0, 0, img.width, img.height, false)
                    val text = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text } catch (e: Exception) { null } finally { reader.reset() }
                    if (text != null && text.startsWith("hermes://pair")) {
                        done = true
                        runOnUiThread { setResult(RESULT_OK, Intent().putExtra("text", text)); finish() }
                    }
                } finally { img.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() { exec.shutdown(); super.onDestroy() }
}
