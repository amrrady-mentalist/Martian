package com.scalpian.marty.offline.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Camera screen: take a photo of the card, or pick one from the gallery. */
@Composable
fun CaptureScreen(
    leftHanded: Boolean,
    onCaptured: (Bitmap) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var busy by remember { mutableStateOf(false) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    DisposableEffect(hasPermission) {
        var provider: ProcessCameraProvider? = null
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }, mainExecutor)
        }
        onDispose {
            provider?.unbindAll()
        }
    }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            cameraExecutor.execute {
                val bmp = runCatching { decodeUri(context, uri) }.getOrNull()
                mainExecutor.execute {
                    if (bmp != null) {
                        onCaptured(bmp)
                    } else {
                        busy = false
                    }
                }
            }
        }
    }

    fun takePicture() {
        if (busy || !hasPermission) return
        busy = true
        imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching {
                    val rotation = image.imageInfo.rotationDegrees
                    image.toBitmap().rotated(rotation).downscaled(1600)
                }.getOrNull()
                image.close()
                mainExecutor.execute {
                    if (bmp != null) {
                        onCaptured(bmp)
                    } else {
                        busy = false
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute { busy = false }
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                "Camera permission is needed to take the photo.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }

        // Soft framing guide: one card-shaped outline.
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height * 0.5f
            val w = h * 0.714f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(18f, 18f),
                style = Stroke(width = 3f)
            )
        }

        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier
                .systemBarsPadding()
                .padding(16.dp)
                .size(28.dp)
                .clickable(onClick = onBack)
        )

        // shutter always in the middle; the gallery shortcut goes on the side away from your thumb
        val shutterAlign = Alignment.BottomCenter
        val galleryAlign = if (leftHanded) Alignment.BottomEnd else Alignment.BottomStart

        Box(
            modifier = Modifier
                .align(shutterAlign)
                .systemBarsPadding()
                .padding(horizontal = 32.dp, vertical = 28.dp)
                .size(76.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
                .border(4.dp, Color.White, CircleShape)
                .clickable { takePicture() },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White))
        }

        Icon(
            Icons.Filled.PhotoLibrary,
            contentDescription = "Pick from gallery",
            tint = Color.White,
            modifier = Modifier
                .align(galleryAlign)
                .systemBarsPadding()
                .padding(horizontal = 40.dp, vertical = 44.dp)
                .size(34.dp)
                .clickable {
                    if (!busy) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
        )

        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

private fun decodeUri(context: android.content.Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > 1600) decoder.setTargetSampleSize(ceil(longest / 1600.0).toInt())
    }.let { b -> if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false) }
}
