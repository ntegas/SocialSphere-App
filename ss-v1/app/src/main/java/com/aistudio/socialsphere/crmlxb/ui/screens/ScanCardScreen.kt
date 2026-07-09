package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import com.aistudio.socialsphere.crmlxb.utils.BusinessCardParser
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Сканер визитки. OCR-движка в стеке нет, поэтому экран принимает уже
 * распознанный/вставленный текст (с фото-OCR клавиатуры, надиктовки или вставки)
 * и раскладывает его через BusinessCardParser. Шаги по макету:
 *   1) ввод текста визитки  →  2) проверка/правка полей  →  3) создание контакта.
 * Создание переиспользует протестированный performImport (компания/телефоны/почты).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanCardScreen(
    onNavigateBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawText  by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    // Камера открывается сразу (как в макете SCANNER); вставка текста — запасной путь.
    var showCamera by remember { mutableStateOf(true) }
    var ocrRunning by remember { mutableStateOf(false) }

    var firstName by remember { mutableStateOf("") }
    var lastName  by remember { mutableStateOf("") }
    var phone     by remember { mutableStateOf("") }
    var email     by remember { mutableStateOf("") }
    var company   by remember { mutableStateOf("") }
    var position  by remember { mutableStateOf("") }

    // Разбор текста в поля; на шаг проверки — только если парсер что-то нашёл
    fun applyParsed(text: String) {
        val p = BusinessCardParser.parse(text)
        firstName = p.firstName
        lastName  = p.lastName
        phone     = p.phones.firstOrNull() ?: ""
        email     = p.emails.firstOrNull() ?: ""
        company   = p.company ?: ""
        position  = p.position ?: ""
        if (firstName.isNotBlank() || lastName.isNotBlank() ||
            phone.isNotBlank() || email.isNotBlank()) reviewed = true
    }

    // ── Камера → кадр → Tesseract-OCR (eng+rus+ell) → разбор в поля ──
    if (showCamera) {
        CardCameraScanner(
            onClose = { showCamera = false },
            onCaptured = { bmp ->
                showCamera = false
                ocrRunning = true
                scope.launch {
                    val text = com.aistudio.socialsphere.crmlxb.utils.BusinessCardOcr.recognize(ctx, bmp)
                    if (text.isNotBlank()) { rawText = text; applyParsed(text) }
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

            if (!reviewed) {
                // ── Шаг 1: ввод текста визитки ──
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
                // Снять визитку камерой (повторно)
                Button(
                    onClick = { showCamera = true },
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
                Button(
                    onClick = {
                        val p = BusinessCardParser.parse(rawText)
                        firstName = p.firstName
                        lastName  = p.lastName
                        phone     = p.phones.firstOrNull() ?: ""
                        email     = p.emails.firstOrNull() ?: ""
                        company   = p.company ?: ""
                        position  = p.position ?: ""
                        reviewed  = true
                    },
                    enabled = rawText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand)
                ) {
                    Icon(Icons.Outlined.AutoFixHigh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_recognize))
                }
            } else {
                // ── Шаг 2: проверка/правка полей ──
                val initials = (firstName.firstOrNull()?.toString().orEmpty() +
                    lastName.firstOrNull()?.toString().orEmpty()).ifBlank { "?" }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(AureliaTheme.colors.avatarTerracotta),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Text(stringResource(R.string.scan_review_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(firstName, { firstName = it }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.scan_first_name)) }, singleLine = true)
                    OutlinedTextField(lastName, { lastName = it }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.scan_last_name)) }, singleLine = true)
                }
                OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scan_phone)) }, singleLine = true,
                    keyboardOptions = PhoneKeyboard)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scan_email)) }, singleLine = true,
                    keyboardOptions = EmailKeyboard)
                OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scan_company)) }, singleLine = true)
                OutlinedTextField(position, { position = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scan_position)) }, singleLine = true)

                val canSave = firstName.isNotBlank() || lastName.isNotBlank() ||
                    phone.isNotBlank() || email.isNotBlank()
                // stringResource нельзя вызывать в onClick (не @Composable) — берём заранее
                val sourceLabel = stringResource(R.string.scan_source)
                Button(
                    onClick = {
                        val cid = ""
                        val candidate = ImportContactCandidate(
                            firstName = firstName.trim(),
                            lastName  = lastName.trim(),
                            phones = if (phone.isNotBlank())
                                listOf(ContactPhone(UUID.randomUUID().toString(), cid, phone.trim(), PhoneType.MOBILE, true))
                            else emptyList(),
                            emails = if (email.isNotBlank())
                                listOf(ContactEmail(UUID.randomUUID().toString(), cid, email.trim(), EmailType.WORK, true))
                            else emptyList(),
                            companyName = company.trim().ifBlank { null },
                            jobTitle = position.trim().ifBlank { null },
                            source = sourceLabel
                        )
                        val before = AppStateStore.contacts.map { it.id }.toSet()
                        performImport(listOf(candidate), ctx)
                        val newId = AppStateStore.contacts.map { it.id }.firstOrNull { it !in before }
                        if (newId != null) onCreated(newId) else onNavigateBack()
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand)
                ) {
                    Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_create))
                }
                TextButton(
                    onClick = { reviewed = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.scan_back_to_text)) }
            }
        }
    }
}
