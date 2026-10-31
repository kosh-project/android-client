package project.kosh.feature.auth

import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded full-screen camera QR scanner dialog built with CameraX and ZXing.
 *
 * Designed to decode server pairing QR codes, especially those rendered in terminal TUIs
 * with inverted luminance (white Unicode half-blocks on black background) and zero quiet zone.
 */
@Composable
fun QrCameraScannerDialog(
    onDismiss: () -> Unit,
    onScanned: (rawBytes: ByteArray?, rawValue: String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val hasScanned = remember { AtomicBoolean(false) }

    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val imageAnalysisRef = remember { mutableStateOf<ImageAnalysis?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                imageAnalysisRef.value?.clearAnalyzer()
                cameraProviderRef.value?.unbindAll()
            } catch (_: Exception) {}
            cameraExecutor.shutdown()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // CameraX Live Preview & Analysis
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                            )
                        )
                        .build()

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProviderRef.value = cameraProvider

                            val preview = Preview.Builder()
                                .setResolutionSelector(resolutionSelector)
                                .build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setResolutionSelector(resolutionSelector)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        analyzeQrFrame(
                                            imageProxy = imageProxy,
                                            hasScanned = hasScanned
                                        ) { rawBytes, rawValue ->
                                            mainHandler.post {
                                                onScanned(rawBytes, rawValue)
                                            }
                                        }
                                    }
                                }
                            imageAnalysisRef.value = imageAnalysis

                            val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }

                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )

                            // Tap to focus and optimize exposure for terminal screens
                            previewView.setOnTouchListener { view, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    try {
                                        val factory = previewView.meteringPointFactory
                                        val point = factory.createPoint(event.x, event.y)
                                        val action = FocusMeteringAction.Builder(point).build()
                                        camera.cameraControl.startFocusAndMetering(action)
                                    } catch (_: Exception) {}
                                    view.performClick()
                                }
                                true
                            }

                            // Initial center metering once viewfinder is laid out
                            previewView.post {
                                try {
                                    if (previewView.width > 0 && previewView.height > 0) {
                                        val factory = previewView.meteringPointFactory
                                        val centerPoint = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
                                        val action = FocusMeteringAction.Builder(centerPoint).build()
                                        camera.cameraControl.startFocusAndMetering(action)
                                    }
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {
                            // Camera binding failure ignored gracefully
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Top Bar with Close button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.54f),
                    modifier = Modifier.size(44.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close scanner",
                            tint = Color.White
                        )
                    }
                }
            }

            // Viewfinder Center Overlay & Instructions
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(0.7f))

                // Viewfinder Target Frame
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(24.dp)
                        )
                )

                Spacer(Modifier.height(28.dp))

                // Title and instructions
                Text(
                    text = "Scan Server QR Code",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Point camera at the QR code displayed in your server terminal. Inverted terminal luminance is automatically supported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Extracts the luminance Y-plane from CameraX ImageProxy, constructs a ZXing
 * PlanarYUVLuminanceSource, and decodes with instant inverted luminance fallback.
 */
private fun analyzeQrFrame(
    imageProxy: ImageProxy,
    hasScanned: AtomicBoolean,
    onSuccess: (rawBytes: ByteArray?, rawValue: String?) -> Unit
) {
    try {
        if (hasScanned.get()) return

        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        buffer.rewind()
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val source = if (pixelStride == 1) {
            val yBytes = ByteArray(buffer.remaining())
            buffer.get(yBytes)
            PlanarYUVLuminanceSource(
                yBytes,
                rowStride,
                height,
                0,
                0,
                width,
                height,
                false
            )
        } else {
            val yBytes = ByteArray(width * height)
            val rowBuffer = ByteArray(rowStride)
            var outOffset = 0
            for (y in 0 until height) {
                val length = minOf(buffer.remaining(), rowStride)
                buffer.get(rowBuffer, 0, length)
                for (x in 0 until width) {
                    val srcIdx = x * pixelStride
                    if (srcIdx < length) {
                        yBytes[outOffset + x] = rowBuffer[srcIdx]
                    }
                }
                outOffset += width
            }
            PlanarYUVLuminanceSource(
                yBytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
        }

        val result = QrCodeDecoder.decode(source)
        if (result != null) {
            if (hasScanned.compareAndSet(false, true)) {
                val payloadBytes = QrCodeDecoder.extractPayloadBytes(result)
                onSuccess(payloadBytes, result.text)
            }
        }
    } catch (_: Exception) {
        // Suppress per-frame decode errors
    } finally {
        imageProxy.close()
    }
}
