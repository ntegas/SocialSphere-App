package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.Contact
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme

/** «Полнота» контакта — чтобы при слиянии оставлять более заполненного. */
private fun contactScore(c: Contact): Int =
    c.phones.size + c.emails.size + c.messengers.size + c.addresses.size +
    c.notes.size + c.gifts.size + c.companyRelations.size +
    (if (c.nickname.isNullOrBlank()) 0 else 1)

private fun contactSubtitle(c: Contact): String =
    listOfNotNull(
        c.phones.firstOrNull()?.number,
        c.emails.firstOrNull()?.email
    ).joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onNavigateBack: () -> Unit,
    // «Объединить и править»: после слияния открываем форму итогового контакта
    onNavigateToEditContact: (String) -> Unit = {}
) {
    val pairs by remember { derivedStateOf { AppStateStore.findDuplicatePairs() } }
    // Слияние — деструктив (удаляет один контакт), поэтому через подтверждение.
    var pendingMerge by remember { mutableStateOf<Pair<Contact, Contact>?>(null) }
    // Ручное объединение (фидбэк владельца): два слота + пикер-шторка
    var manualA by remember { mutableStateOf<Contact?>(null) }
    var manualB by remember { mutableStateOf<Contact?>(null) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) } // 1 или 2

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Шапка Aurelia (круглая кнопка назад + Playfair-заголовок) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.dup_title), fontSize = 28.sp)
            }

            Text(
                stringResource(R.string.dup_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = AppleTheme.colors.secondaryLabel
            )

            // ── Ручное объединение: выбрать два любых контакта ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18,
                colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.dup_manual_title),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    ManualPickRow(stringResource(R.string.dup_pick_a), manualA) { pickingSlot = 1 }
                    ManualPickRow(stringResource(R.string.dup_pick_b), manualB) { pickingSlot = 2 }
                    val a = manualA; val b = manualB
                    Button(
                        enabled = a != null && b != null && a.id != b.id,
                        onClick = {
                            if (a != null && b != null) {
                                val keep = if (contactScore(a) >= contactScore(b)) a else b
                                val drop = if (keep.id == a.id) b else a
                                pendingMerge = keep to drop
                            }
                        },
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppleTheme.colors.brand, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) { Text(stringResource(R.string.dup_merge), fontWeight = FontWeight.Bold) }
                }
            }

            if (pairs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.dup_none), color = AppleTheme.colors.secondaryLabel)
                }
            } else {
                pairs.forEach { match ->
                    val a = match.a; val b = match.b
                    val keep = if (contactScore(a) >= contactScore(b)) a else b
                    val drop = if (keep.id == a.id) b else a
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R18,
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // ── Шапка пары: два аватара + разделитель + имя/кол-во записей (по макету) ──
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DuplicateAvatar(a)
                                Icon(Icons.Default.Sync, null, Modifier.size(16.dp), tint = AureliaTheme.colors.gold)
                                DuplicateAvatar(b)
                                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                    Text(
                                        "${keep.firstName} ${keep.lastName}".trim(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        stringResource(R.string.dup_two_records),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppleTheme.colors.secondaryLabel
                                    )
                                }
                            }
                            // ПРИЧИНА совпадения (фидбэк: «непонятно, чем связаны»)
                            val reason = when {
                                match.byPhone != null -> stringResource(R.string.dup_match_phone, "…" + match.byPhone.takeLast(4))
                                match.byEmail != null -> stringResource(R.string.dup_match_email, match.byEmail)
                                else -> null
                            }
                            if (reason != null) Text(
                                reason,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = AppleTheme.colors.goldLabel
                            )
                            DuplicateContactLine(a)
                            HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                            DuplicateContactLine(b)
                            Text(
                                stringResource(R.string.dup_will_keep, "${keep.firstName} ${keep.lastName}".trim()),
                                style = MaterialTheme.typography.labelMedium,
                                color = AppleTheme.colors.brand
                            )
                            Button(
                                onClick = { pendingMerge = keep to drop },
                                shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppleTheme.colors.brand,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                            ) {
                                Text(stringResource(R.string.dup_merge), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        pendingMerge?.let { (keep, drop) ->
            AlertDialog(
                onDismissRequest = { pendingMerge = null },
                title = { Text(stringResource(R.string.dup_merge_confirm_title), fontWeight = FontWeight.Bold) },
                text = {
                    Text(stringResource(
                        R.string.dup_merge_confirm_text,
                        "${keep.firstName} ${keep.lastName}".trim(),
                        "${drop.firstName} ${drop.lastName}".trim()
                    ))
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                AppStateStore.mergeContacts(keep.id, drop.id)
                                pendingMerge = null
                                manualA = null; manualB = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White)
                        ) { Text(stringResource(R.string.dup_merge)) }
                        // «Объединить и править» — сразу открывает форму итога (фидбэк владельца)
                        TextButton(onClick = {
                            AppStateStore.mergeContacts(keep.id, drop.id)
                            pendingMerge = null
                            manualA = null; manualB = null
                            onNavigateToEditContact(keep.id)
                        }) { Text(stringResource(R.string.dup_merge_edit)) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingMerge = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        // ── Пикер контакта для ручного объединения (шторка с поиском) ──
        if (pickingSlot != null) {
            var pickQuery by remember { mutableStateOf("") }
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(onDismiss = { pickingSlot = null }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pickQuery, onValueChange = { pickQuery = it },
                        placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    val q = pickQuery.trim().lowercase()
                    val matches = AppStateStore.contacts
                        .filter { q.isBlank() || "${it.firstName} ${it.lastName} ${it.nickname.orEmpty()}".lowercase().contains(q) }
                        .sortedBy { "${it.firstName} ${it.lastName}".lowercase() }
                        .take(30)
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
                    ) {
                        items(matches.size) { i ->
                            val c = matches[i]
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        if (pickingSlot == 1) manualA = c else manualB = c
                                        pickingSlot = null
                                    }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatar(
                                    c.id, "${c.firstName} ${c.lastName}".trim(), size = 36.dp, fontSize = 13.sp)
                                Text("${c.firstName} ${c.lastName}".trim(),
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Слот выбора контакта для ручного объединения
@Composable
private fun ManualPickRow(label: String, picked: Contact?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Medium)
            .background(AppleTheme.colors.neutralFill)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (picked != null) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatar(
                picked.id, "${picked.firstName} ${picked.lastName}".trim(), size = 30.dp, fontSize = 11.sp)
            Text("${picked.firstName} ${picked.lastName}".trim(),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        } else {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = AppleTheme.colors.secondaryLabel, modifier = Modifier.weight(1f))
        }
        Icon(Icons.Default.Sync, null, Modifier.size(16.dp), tint = AppleTheme.colors.brand)
    }
}

@Composable
private fun DuplicateContactLine(c: Contact) {
    Column {
        Text("${c.firstName} ${c.lastName}".trim(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        val sub = contactSubtitle(c)
        if (sub.isNotEmpty())
            Text(sub, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
    }
}

@Composable
private fun DuplicateAvatar(c: Contact) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatars.brushFor(c.id)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (c.firstName.firstOrNull()?.toString() ?: "") + (c.lastName.firstOrNull()?.toString() ?: ""),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}
