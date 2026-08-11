package com.aistudio.socialsphere.crmlxb.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aistudio.socialsphere.crmlxb.R
import java.util.concurrent.TimeUnit

// Тёмная палитра камеры-сканера (как в макете Aurelia)
private val CamBrand = Color(0xFF5FB894)
private val CamGold  = Color(0xFFD7B468)

// Геометрия окна-рамки визитки — ЕДИНЫЙ источник и для отрисовки (ScanFrameOverlay),
// и для кропа захваченного bitmap (cropToCardFrame). 85.6×54мм — стандарт ISO 7810 ID-1.
private const val FRAME_WIDTH_FRACTION = 0.86f
private const val CARD_ASPECT = 1.586f // 85.6 / 54

/** Прямоугольник окна визитки в пикселях для канваса/bitmap заданного размера. */
private fun cardFrameRect(width: Float, height: Float): androidx.compose.ui.geometry.Rect {
    val frameW = width * FRAME_WIDTH_FRACTION
    val frameH = frameW / CARD_ASPECT
    val left = (width - frameW) / 2f
    val top = (height - frameH) / 2f
    return androidx.compose.ui.geometry.Rect(left, top, left + frameW, top + frameH)
}

/**
 * Полноэкранный камера-сканер визитки: CameraX-превью + рамка с уголками и
 * линией сканирования (по макету), кнопка съёмки. По кадру возвращает Bitmap
 * в [onCaptured], дальше OCR (Tesseract) → BusinessCardParser.
 */
@Composable
fun CardCameraScanner(
    onClose: () -> Unit,
    onCaptured: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Разрешение камеры — штатный Activity Result API (как в остальном приложении).
    // Accompanist-permissions убран: 0.37.x собран под Compose 1.8, на BOM 2024.09
    // (Compose 1.7) падал NoSuchMethodError при первой композиции — крэш сканера.
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!cameraGranted) {
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
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
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
                // MAXIMIZE_QUALITY (не MINIMIZE_LATENCY) — карточка снимается один раз и не
                // торопясь, приоритет точности мелкого печатного текста для OCR важнее задержки.
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
            }
            // Сохраняются для фокус-лока перед съёмкой (см. кнопку ниже) — Camera даёт
            // cameraControl.startFocusAndMetering, PreviewView даёт meteringPointFactory.
            var boundCamera by remember { mutableStateOf<Camera?>(null) }
            var boundPreviewView by remember { mutableStateOf<PreviewView?>(null) }

            // ── Превью камеры ──
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    boundPreviewView = previewView
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            provider.unbindAll()
                            // ФИКС (2026-07-11, критика воркфлоу «билиберда»): раньше Preview
                            // и ImageCapture биндились без общего ViewPort — то, что видно в
                            // превью (координаты Compose-канваса рамки-гида), НЕ соответствовало
                            // пикселям снятого bitmap (разрешение/aspect сенсора камеры). Общий
                            // ViewPort от previewView заставляет CameraX кропать ImageCapture по
                            // тому же кадру, что показан в превью (WYSIWYG) — тогда дробная
                            // геометрия рамки (cardFrameRect) применима напрямую к bitmap.
                            // viewPort доступен только после layout — previewView.post{} гарантирует.
                            previewView.post {
                                val viewPort = previewView.viewPort
                                try {
                                    provider.unbindAll()
                                    boundCamera = if (viewPort != null) {
                                        val group = UseCaseGroup.Builder()
                                            .setViewPort(viewPort)
                                            .addUseCase(preview)
                                            .addUseCase(imageCapture)
                                            .build()
                                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                                    } else {
                                        provider.bindToLifecycle(
                                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                                        )
                                    }
                                } catch (_: Exception) { /* камера занята/недоступна */ }
                            }
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
                        focusThenCapture(boundCamera, boundPreviewView, imageCapture, context, onCaptured)
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
        val frame = cardFrameRect(w, h)
        val frameW = frame.width
        val frameH = frame.height
        val left = frame.left
        val top = frame.top
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

/**
 * Фокус-лок перед съёмкой: наводит автофокус на центр окна визитки (там, где
 * реально печатный текст) и ждёт его завершения, ТОЛЬКО ПОТОМ вызывает [capturePhoto].
 * Мелкий печатный текст на визитке легко смазать, если снять на нестабилизированном
 * фокусе превью — explicit AF перед кадром напрямую повышает точность OCR.
 * Если камера/превью ещё не готовы (boundCamera/boundPreviewView == null) или AF
 * не поддерживается — снимаем сразу текущим фокусом, чтобы не блокировать пользователя.
 */
private fun focusThenCapture(
    camera: Camera?,
    previewView: PreviewView?,
    imageCapture: ImageCapture,
    context: android.content.Context,
    onCaptured: (Bitmap) -> Unit
) {
    val cameraControl = camera?.cameraControl
    if (cameraControl == null || previewView == null || previewView.width <= 0 || previewView.height <= 0) {
        capturePhoto(imageCapture, context, onCaptured)
        return
    }
    val point = previewView.meteringPointFactory.createPoint(
        previewView.width / 2f, previewView.height / 2f
    )
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()
    val future = cameraControl.startFocusAndMetering(action)
    future.addListener(
        { capturePhoto(imageCapture, context, onCaptured) },
        ContextCompat.getMainExecutor(context)
    )
}

private fun capturePhoto(
    imageCapture: ImageCapture,
    context: android.content.Context,
    onCaptured: (Bitmap) -> Unit
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = image.toBitmap()
                image.close()
                val rotated = if (rotation != 0) rotateBitmap(bmp, rotation) else bmp
                // Кроп по той же дробной геометрии, что рисует ScanFrameOverlay —
                // валиден благодаря общему ViewPort (см. bindToLifecycle выше).
                onCaptured(cropToCardFrame(rotated))
            }
            override fun onError(exc: ImageCaptureException) { /* игнор — пользователь снимет ещё раз */ }
        }
    )
}

private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

/** Обрезает bitmap по той же дробной геометрии окна, что рисует ScanFrameOverlay. */
private fun cropToCardFrame(src: Bitmap): Bitmap {
    val frame = cardFrameRect(src.width.toFloat(), src.height.toFloat())
    val left = frame.left.toInt().coerceIn(0, src.width - 1)
    val top = frame.top.toInt().coerceIn(0, src.height - 1)
    val w = frame.width.toInt().coerceIn(1, src.width - left)
    val h = frame.height.toInt().coerceIn(1, src.height - top)
    return Bitmap.createBitmap(src, left, top, w, h)
}
