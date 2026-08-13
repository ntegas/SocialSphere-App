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
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import com.aistudio.socialsphere.crmlxb.utils.BusinessCardParser
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate
import com.aistudio.socialsphere.crmlxb.utils.label
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
    // Предупреждение о размытом кадре (см. BusinessCardOcr.isBlurry) — снимок всё
    // равно распознаём (лучше нечёткий результат, чем ничего), но явно говорим
    // владельцу, почему могло получиться плохо, вместо тихой билиберды без причины.
    var blurryWarning by remember { mutableStateOf(false) }

    var firstName by remember { mutableStateOf("") }
    var lastName  by remember { mutableStateOf("") }
    var phones    by remember { mutableStateOf<List<ContactPhone>>(emptyList()) }
    var emails    by remember { mutableStateOf<List<ContactEmail>>(emptyList()) }
    var website   by remember { mutableStateOf("") }
    var company   by remember { mutableStateOf("") }
    var position  by remember { mutableStateOf("") }
    // Адреса — как строки без структуры (улицу/город из OCR-текста надёжно не
    // разложить), редактируются как есть и уходят в ImportContactCandidate.addresses.
    var addressLines by remember { mutableStateOf<List<String>>(emptyList()) }
    // Строки, которые парсер не смог уверенно отнести ни к одному полю — сеть
    // безопасности: попадают отдельной заметкой при создании, не теряются.
    var unmatched by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAddPhone by remember { mutableStateOf(false) }
    var newPhone     by remember { mutableStateOf("") }
    var newPhoneType by remember { mutableStateOf(PhoneType.MOBILE) }
    var showAddEmail by remember { mutableStateOf(false) }
    var newEmail      by remember { mutableStateOf("") }
    var newEmailType  by remember { mutableStateOf(EmailType.WORK) }

    // Разбор текста в поля; на шаг проверки — только если парсер что-то нашёл.
    // ФИКС (данные-потеря, фидбэк владельца «должен не терять ни фразы»): раньше
    // брали только firstOrNull() по телефонам/почтам — второй и далее контакт
    // молча пропадал, если на визитке было 2+. Теперь весь список идёт в
    // редактируемые поля шага 2 (см. reviewed-ветку ниже).
    fun applyParsed(text: String) {
        val p = BusinessCardParser.parse(text)
        firstName = p.firstName
        lastName  = p.lastName
        phones = p.phones.mapIndexed { i, pp ->
            ContactPhone(UUID.randomUUID().toString(), "", pp.number, pp.type, i == 0)
        }
        emails = p.emails.mapIndexed { i, pe ->
            ContactEmail(UUID.randomUUID().toString(), "", pe.email, pe.type, i == 0)
        }
        // ФИКС (2026-07-11, реальный тест владельца — «сайт не распознал»):
        // BusinessCardParser.website реально находил сайт, но экран его нигде
        // не читал — распознанный сайт молча выбрасывался на этом шаге.
        website   = p.website ?: ""
        company   = p.company ?: ""
        position  = p.position ?: ""
        addressLines = p.addresses
        unmatched    = p.unmatched
        if (firstName.isNotBlank() || lastName.isNotBlank() ||
            phones.isNotEmpty() || emails.isNotEmpty()) reviewed = true
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
                Button(
                    onClick = {
                        applyParsed(rawText)
                        reviewed = true
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
                    // Контакт ещё не создан (нет id) — превью красим по имени;
                    // после сохранения экраны красят по id, цвет может отличаться.
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.brushFor("$firstName $lastName")),
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
                // ── Телефоны — все найденные, не только первый ──────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        AureliaCaption(stringResource(R.string.ce_phones))
                        Text(
                            "+ " + stringResource(R.string.common_add), color = AppleTheme.colors.brand,
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.clickable { showAddPhone = true }
                        )
                    }
                    if (phones.isEmpty()) {
                        Text(stringResource(R.string.ce_no_phones),
                            style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                    } else {
                        phones.forEachIndexed { idx, ph ->
                            ContactItemRow(
                                icon = Icons.Default.Call, iconTint = AppleTheme.colors.brand,
                                iconBg = AppleTheme.colors.brand.copy(alpha = 0.10f),
                                value = ph.number,
                                onValueChange = { num -> phones = phones.toMutableList().also { it[idx] = ph.copy(number = num) } },
                                keyboardOptions = PhoneKeyboard, label = ph.type.label(ctx),
                                isPrimary = ph.isPrimary,
                                onTogglePrimary = { phones = phones.mapIndexed { i, p -> p.copy(isPrimary = i == idx) } },
                                onDelete = { phones = phones.toMutableList().also { it.removeAt(idx) } }
                            )
                        }
                    }
                }

                // ── Email — все найденные, не только первый ─────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        AureliaCaption(stringResource(R.string.ce_email))
                        Text(
                            "+ " + stringResource(R.string.common_add), color = AppleTheme.colors.brand,
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.clickable { showAddEmail = true }
                        )
                    }
                    if (emails.isEmpty()) {
                        Text(stringResource(R.string.ce_no_email),
                            style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                    } else {
                        emails.forEachIndexed { idx, em ->
                            ContactItemRow(
                                icon = Icons.Default.Email, iconTint = AureliaTheme.colors.gold,
                                iconBg = AureliaTheme.colors.gold.copy(alpha = 0.14f),
                                value = em.email,
                                onValueChange = { v -> emails = emails.toMutableList().also { it[idx] = em.copy(email = v) } },
                                keyboardOptions = EmailKeyboard, label = em.type.label(ctx),
                                isPrimary = em.isPrimary,
                                onTogglePrimary = { emails = emails.mapIndexed { i, e -> e.copy(isPrimary = i == idx) } },
                                onDelete = { emails = emails.toMutableList().also { it.removeAt(idx) } }
                            )
                        }
                    }
                }

                OutlinedTextField(website, { website = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scan_website)) }, singleLine = true,
                    keyboardOptions = UrlKeyboard)
                OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scan_company)) }, singleLine = true)
                OutlinedTextField(position, { position = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scan_position)) }, singleLine = true)

                // ── Адрес(а) — если распознаны; парсер отдаёт их строками без
                // структуры (улицу/город из OCR-текста надёжно не разложить) ──
                if (addressLines.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            AureliaCaption(stringResource(R.string.ce_addresses))
                            Text(
                                "+ " + stringResource(R.string.common_add), color = AppleTheme.colors.brand,
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                modifier = Modifier.clickable { addressLines = addressLines + "" }
                            )
                        }
                        addressLines.forEachIndexed { idx, line ->
                            ContactItemRow(
                                icon = Icons.Default.LocationOn, iconTint = AppleTheme.colors.secondaryLabel,
                                iconBg = AppleTheme.colors.secondaryLabel.copy(alpha = 0.10f),
                                value = line,
                                onValueChange = { v -> addressLines = addressLines.toMutableList().also { it[idx] = v } },
                                label = stringResource(R.string.ce_address),
                                onDelete = { addressLines = addressLines.toMutableList().also { it.removeAt(idx) } }
                            )
                        }
                    }
                }

                val canSave = firstName.isNotBlank() || lastName.isNotBlank() ||
                    phones.isNotEmpty() || emails.isNotEmpty()
                // stringResource нельзя вызывать в onClick (не @Composable) — берём заранее
                val sourceLabel = stringResource(R.string.scan_source)
                Button(
                    onClick = {
                        val addressCandidates = addressLines.map { it.trim() }.filter { it.isNotBlank() }
                            .map { line ->
                                Address(
                                    id = UUID.randomUUID().toString(),
                                    ownerType = AddressOwnerType.CONTACT,
                                    ownerId = "",
                                    addressType = AddressType.OFFICE,
                                    addressLine = line,
                                    city = "",
                                    country = ""
                                )
                            }
                        // КРИТИЧНО (фидбэк владельца «должен не терять ни фразы»): заметка
                        // на контакте всегда включает весь исходный OCR-текст целиком —
                        // независимо от того, насколько хорошо разбор разложил поля, и
                        // независимо от того, заглядывал ли владелец в поле текста на шаге 1.
                        // Плюс нераспознанные строки и сайт (у Contact нет отдельного поля
                        // «сайт»). ctx.getString — как в ImportScreens.kt/CommunicationTab.kt
                        // (stringResource с аргументом нельзя вызывать внутри onClick).
                        val noteParts = mutableListOf<String>()
                        website.trim().takeIf { it.isNotBlank() }
                            ?.let { noteParts += ctx.getString(R.string.scan_website_note, it) }
                        if (unmatched.isNotEmpty())
                            noteParts += ctx.getString(R.string.scan_unmatched_note, unmatched.joinToString("\n"))
                        noteParts += ctx.getString(R.string.scan_raw_text_note, rawText)

                        val candidate = ImportContactCandidate(
                            firstName = firstName.trim(),
                            lastName  = lastName.trim(),
                            phones = phones,
                            emails = emails,
                            companyName = company.trim().ifBlank { null },
                            jobTitle = position.trim().ifBlank { null },
                            addresses = addressCandidates,
                            notes = noteParts.joinToString("\n\n"),
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

                if (showAddPhone) {
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.ce_add_phone),
                        onDismiss = { showAddPhone = false; newPhone = "" },
                        confirmText = stringResource(R.string.common_add),
                        onConfirm = {
                            if (newPhone.isNotBlank()) {
                                val added = ContactPhone(UUID.randomUUID().toString(), "", newPhone.trim(), newPhoneType, phones.isEmpty())
                                phones = phones + added
                                newPhone = ""; showAddPhone = false
                            }
                        },
                        secondaryText = stringResource(R.string.common_cancel),
                        onSecondary = { showAddPhone = false; newPhone = "" }
                    ) {
                        OutlinedTextField(value = newPhone, onValueChange = { newPhone = it }, keyboardOptions = PhoneKeyboard,
                            label = { Text(stringResource(R.string.ce_number)) }, modifier = Modifier.fillMaxWidth())
                        DropdownField(stringResource(R.string.ce_type), newPhoneType.label(ctx), PhoneType.values().map { it.label(ctx) }) { v ->
                            newPhoneType = PhoneType.values().firstOrNull { it.label(ctx) == v } ?: PhoneType.MOBILE
                        }
                    }
                }
                if (showAddEmail) {
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.ce_add_email),
                        onDismiss = { showAddEmail = false; newEmail = "" },
                        confirmText = stringResource(R.string.common_add),
                        onConfirm = {
                            if (newEmail.isNotBlank()) {
                                val added = ContactEmail(UUID.randomUUID().toString(), "", newEmail.trim(), newEmailType, emails.isEmpty())
                                emails = emails + added
                                newEmail = ""; showAddEmail = false
                            }
                        },
                        secondaryText = stringResource(R.string.common_cancel),
                        onSecondary = { showAddEmail = false; newEmail = "" }
                    ) {
                        OutlinedTextField(value = newEmail, onValueChange = { newEmail = it }, keyboardOptions = EmailKeyboard,
                            label = { Text(stringResource(R.string.ce_email)) }, modifier = Modifier.fillMaxWidth())
                        DropdownField(stringResource(R.string.ce_type), newEmailType.label(ctx), EmailType.values().map { it.label(ctx) }) { v ->
                            newEmailType = EmailType.values().firstOrNull { it.label(ctx) == v } ?: EmailType.WORK
                        }
                    }
                }
            }
        }
    }
}
