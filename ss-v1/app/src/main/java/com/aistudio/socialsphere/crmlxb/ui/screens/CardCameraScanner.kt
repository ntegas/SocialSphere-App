package com.aistudio.socialsphere.crmlxb.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aistudio.socialsphere.crmlxb.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

// Тёмная палитра камеры-сканера (как в макете Aurelia)
private val CamBrand = Color(0xFF5FB894)
private val CamGold  = Color(0xFFD7B468)

/**
 * Полноэкранный камера-сканер визитки: CameraX-превью + рамка с уголками и
 * линией сканирования (по макету), кнопка съёмки. По кадру возвращает Bitmap
 * в [onCaptured], дальше OCR (Tesseract) → BusinessCardParser.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CardCameraScanner(
    onClose: () -> Unit,
    onCaptured: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!cameraPermission.status.isGranted) {
            // ── Нет разрешения — объяснение + запрос ──
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(56.dp), tint = CamGold)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.scan_camera_permission),
                    color = Color.White, textAlign = TextAlign.Center, fontSize = 15.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { cameraPermission.launchPermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = CamBrand)
                ) { Text(stringResource(R.string.imp_allow)) }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.common_back), color = Color.White,
                    modifier = Modifier.clickable { onClose() }.padding(12.dp)
                )
            }
        } else {
            val imageCapture = remember {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
            }

            // ── Превью камеры ──
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                            )
                        } catch (_: Exception) { /* камера занята/недоступна */ }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )

            // ── Рамка сканирования: затемнение + прозрачное окно + уголки + линия ──
            ScanFrameOverlay()

            // ── Подсказка сверху ──
            Text(
                stringResource(R.string.scan_frame_hint),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 24.dp, end = 24.dp)
            )

            // ── Кнопка «Закрыть» ──
            Box(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)
                    .size(40.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = Color.White) }

            // ── Кнопка съёмки ──
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp)
                    .size(72.dp).clip(CircleShape).background(Color.White)
                    .clickable {
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val rotation = image.imageInfo.rotationDegrees
                                    val bmp = image.toBitmap()
                                    image.close()
                                    onCaptured(if (rotation != 0) rotateBitmap(bmp, rotation) else bmp)
                                }
                                override fun onError(exc: ImageCaptureException) { /* игнор — пользователь снимет ещё раз */ }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(CamBrand), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PhotoCamera, stringResource(R.string.scan_capture), tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/** Затемнение с прозрачным «окном» визитки, уголками и линией сканирования. */
@Composable
private fun ScanFrameOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val frameW = w * 0.86f
        val frameH = frameW / 1.586f            // пропорция визитки 85.6×54 мм
        val left = (w - frameW) / 2f
        val top = (h - frameH) / 2f
        val radius = 18.dp.toPx()

        // Затемнение вокруг окна (рисуем 4 прямоугольника, окно остаётся прозрачным)
        val scrim = Color(0x99000000)
        drawRect(scrim, size = Size(w, top))                                   // сверху
        drawRect(scrim, topLeft = Offset(0f, top + frameH), size = Size(w, h - top - frameH)) // снизу
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, frameH))  // слева
        drawRect(scrim, topLeft = Offset(left + frameW, top), size = Size(w - left - frameW, frameH)) // справа

        // Уголки рамки
        val corner = 26.dp.toPx()
        val stroke = 3.dp.toPx()
        val gold = CamGold
        // левый верх
        drawLine(gold, Offset(left, top + radius), Offset(left, top + corner + radius), stroke)
        drawLine(gold, Offset(left + radius, top), Offset(left + corner + radius, top), stroke)
        // правый верх
        drawLine(gold, Offset(left + frameW, top + radius), Offset(left + frameW, top + corner + radius), stroke)
        drawLine(gold, Offset(left + frameW - radius, top), Offset(left + frameW - corner - radius, top), stroke)
        // левый низ
        drawLine(gold, Offset(left, top + frameH - radius), Offset(left, top + frameH - corner - radius), stroke)
        drawLine(gold, Offset(left + radius, top + frameH), Offset(left + corner + radius, top + frameH), stroke)
        // правый низ
        drawLine(gold, Offset(left + frameW, top + frameH - radius), Offset(left + frameW, top + frameH - corner - radius), stroke)
        drawLine(gold, Offset(left + frameW - radius, top + frameH), Offset(left + frameW - corner - radius, top + frameH), stroke)

        // Линия сканирования (по центру окна)
        drawLine(
            CamBrand.copy(alpha = 0.9f),
            Offset(left + 8.dp.toPx(), top + frameH / 2f),
            Offset(left + frameW - 8.dp.toPx(), top + frameH / 2f),
            2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        )
    }
}

private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}
