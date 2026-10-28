package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.BusinessCardParser
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Сканер визитки. OCR-движка в стеке нет, поэтому экран принимает уже
 * распознанный/вставленный текст (с фото-OCR клавиатуры, надиктовки или вставки)
 * и раскладывает его через BusinessCardParser.
 *
 * V2.2 ИЗМЕНЕНИЕ: раньше этот экран сам показывал шаг проверки/правки полей и
 * сам создавал контакт (performImport) — второй, параллельный редактор рядом с
 * ContactEditScreen. Теперь шаг 1 (текст визитки) — единственный шаг ЭТОГО
 * экрана; как только что-то распознано, разобранный черновик уходит в
 * ContactDraftSession, и экран передаёт управление единой форме Create/Edit
 * (onDraftReady) — редактирование всех полей происходит там, в одном месте.
 * Камера/OCR/парсер (CardCameraScanner/BusinessCardOcr/BusinessCardParser) не
 * тронуты — меняется только маршрутизация результата.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanCardScreen(
    onNavigateBack: () -> Unit,
    onDraftReady: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawText  by remember { mutableStateOf("") }
    // Камера открывается сразу (как в макете SCANNER); вставка текста — запасной путь.
    var showCamera by remember { mutableStateOf(true) }
    var ocrRunning by remember { mutableStateOf(false) }
    // Предупреждение о размытом кадре (см. BusinessCardOcr.isBlurry) — снимок всё
    // равно распознаём (лучше нечёткий результат, чем ничего), но явно говорим
    // владельцу, почему могло получиться плохо, вместо тихой билиберды без причины.
    var blurryWarning by remember { mutableStateOf(false) }

    // Разбор текста → ImportContactCandidate → ContactDraftSession → дальше.
    // Ничего не создаёт и не пишет в БД — только готовит черновик для формы.
    // Проверка «есть хоть что-то полезное» — как раньше (иначе остаёмся на
    // шаге текста, ничего не теряя: rawText не очищается).
    fun tryGoToDraft(text: String): Boolean {
        val p = BusinessCardParser.parse(text)
        if (p.firstName.isBlank() && p.lastName.isBlank() && p.phones.isEmpty() && p.emails.isEmpty()) return false

        val phones = p.phones.mapIndexed { i, pp -> ContactPhone(UUID.randomUUID().toString(), "", pp.number, pp.type, i == 0) }
        val emails = p.emails.mapIndexed { i, pe -> ContactEmail(UUID.randomUUID().toString(), "", pe.email, pe.type, i == 0) }
        val addressCandidates = p.addresses.map { it.trim() }.filter { it.isNotBlank() }.map { line ->
            Address(
                id = UUID.randomUUID().toString(), ownerType = AddressOwnerType.CONTACT, ownerId = "",
                addressType = AddressType.OFFICE, addressLine = line, city = "", country = ""
            )
        }
        // КРИТИЧНО (фидбэк владельца «должен не терять ни фразы»): заметка на
        // контакте всегда включает весь исходный OCR-текст целиком — независимо
        // от того, насколько хорошо разбор разложил поля. Плюс нераспознанные
        // строки и сайт (у Contact нет отдельного поля «сайт»). Раньше эта
        // заметка собиралась на шаге 3 (создание), теперь — сразу здесь, и
        // уходит в ImportContactCandidate.notes → ContactDraftSession →
        // prefill черновика заметок в ContactEditScreen (тот же результат,
        // ничего не теряется, просто единая форма, а не вторая копия).
        val noteParts = mutableListOf<String>()
        p.website?.trim()?.takeIf { it.isNotBlank() }
            ?.let { noteParts += ctx.getString(R.string.scan_website_note, it) }
        if (p.unmatched.isNotEmpty())
            noteParts += ctx.getString(R.string.scan_unmatched_note, p.unmatched.joinToString("\n"))
        noteParts += ctx.getString(R.string.scan_raw_text_note, text)

        ContactDraftSession.pending = ImportContactCandidate(
            firstName  = p.firstName.trim(),
            middleName = p.middleName.trim(),
            lastName   = p.lastName.trim(),
            phones = phones,
            emails = emails,
            companyName = p.company?.trim()?.ifBlank { null },
            jobTitle = p.position?.trim()?.ifBlank { null },
            addresses = addressCandidates,
            notes = noteParts.joinToString("\n\n"),
            source = ctx.getString(R.string.scan_source)
        )
        onDraftReady()
        return true
    }

    // ── Камера → кадр → Tesseract-OCR (eng+rus+ell) → разбор в поля ──
    if (showCamera) {
        CardCameraScanner(
            onClose = { showCamera = false },
            onCaptured = { bmp ->
                showCamera = false
                ocrRunning = true
                blurryWarning = com.aistudio.socialsphere.crmlxb.utils.BusinessCardOcr.isBlurry(bmp)
                scope.launch {
                    val text = com.aistudio.socialsphere.crmlxb.utils.BusinessCardOcr.recognize(ctx, bmp)
                    if (text.isNotBlank()) { rawText = text; tryGoToDraft(text) }
                    ocrRunning = false
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { pv ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pv)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Шапка Aurelia (круглая кнопка назад + Playfair-заголовок) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.scan_title), fontSize = 28.sp)
            }

            // ── Ввод текста визитки ──
            Box(
                Modifier.fillMaxWidth().clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge)
                    .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(AppleTheme.colors.brand),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.DocumentScanner, null,
                            Modifier.size(28.dp), tint = Color.White)
                    }
                    Text(stringResource(R.string.scan_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            // Индикатор распознавания после снимка
            if (ocrRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.scan_recognize) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                }
            }
            // Кадр похож на размытый (см. BusinessCardOcr.isBlurry) — явно
            // предупреждаем, а не оставляем владельца гадать, почему билиберда.
            if (blurryWarning && !ocrRunning) {
                Text(
                    stringResource(R.string.scan_blurry_retake),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.red,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Снять визитку камерой (повторно)
            Button(
                onClick = { showCamera = true; blurryWarning = false },
                enabled = !ocrRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand)
            ) {
                Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_capture))
            }
            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                label = { Text(stringResource(R.string.scan_text_label)) },
                placeholder = { Text(stringResource(R.string.scan_text_placeholder)) }
            )
            var nothingRecognized by remember { mutableStateOf(false) }
            if (nothingRecognized) {
                Text(
                    stringResource(R.string.scan_nothing_recognized),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.red
                )
            }
            Button(
                onClick = { nothingRecognized = !tryGoToDraft(rawText) },
                enabled = rawText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand)
            ) {
                Icon(Icons.Outlined.AutoFixHigh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_recognize))
            }
        }
    }
}
