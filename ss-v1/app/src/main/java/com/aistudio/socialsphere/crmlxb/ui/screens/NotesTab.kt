package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.R
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// ═══════════════════════════════════════════════════════════════
// TAB 4 — ЗАМЕТКИ (Timeline по месяцам)
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.notesTab(
    contact: Contact,
    onShowAdd: () -> Unit,
    onShowVoice: () -> Unit,
    onEditNote: (Note) -> Unit,
    onDeleteNote: (Note) -> Unit
, ctxLabel: android.content.Context,
    privacyMode: Boolean = false,
    onTogglePrivacy: () -> Unit = {}) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onShowAdd,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppleTheme.colors.brand.copy(alpha = 0.10f),
                    contentColor   = AppleTheme.colors.brand
                )
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_add))
            }
            Button(
                onClick = onShowVoice,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppleTheme.colors.fill,
                    contentColor   = AppleTheme.colors.label
                )
            ) {
                Icon(Icons.Default.PersonAdd, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cd_add_detail))
            }
        }
    }

    item {
        val allNotes = AppStateStore.notes
            .filter { it.contactId == contact.id }
            .sortedByDescending { it.createdAt }

        if (allNotes.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Notes, null, Modifier.size(48.dp),
                        tint = AppleTheme.colors.separator)
                    Text(stringResource(R.string.cd_no_notes_yet), color = AppleTheme.colors.secondaryLabel)
                    Text(stringResource(R.string.cd_tap_add_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.separator)
                }
            }
            return@item
        }

        // Статистика вверху
        val importantCnt = allNotes.count { it.isImportant }
        val workCnt      = allNotes.count { it.type == NoteType.WORK }
        val eventCnt     = allNotes.count { it.type == NoteType.DATE_EVENT }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (importantCnt > 0) StatChip(stringResource(R.string.cd_stat_important, importantCnt),
                AppleTheme.colors.red.copy(alpha = 0.12f), AppleTheme.colors.red)
            if (workCnt > 0) StatChip(stringResource(R.string.cd_stat_work, workCnt),
                AppleTheme.colors.orange.copy(alpha = 0.14f), AppleTheme.colors.orange)
            if (eventCnt > 0) StatChip(stringResource(R.string.cd_stat_events, eventCnt),
                AppleTheme.colors.brand.copy(alpha = 0.10f), AppleTheme.colors.brand)
        }

        // Группировка по месяцам
        val ruMonths = mapOf(
            1 to stringResource(R.string.month_1), 2 to stringResource(R.string.month_2),
            3 to stringResource(R.string.month_3), 4 to stringResource(R.string.month_4),
            5 to stringResource(R.string.month_5), 6 to stringResource(R.string.month_6),
            7 to stringResource(R.string.month_7), 8 to stringResource(R.string.month_8),
            9 to stringResource(R.string.month_9), 10 to stringResource(R.string.month_10),
            11 to stringResource(R.string.month_11), 12 to stringResource(R.string.month_12)
        )
        val notesByMonth = allNotes.groupBy { note ->
            try {
                val d = java.time.LocalDate.parse(note.createdAt.take(10))
                "${ruMonths[d.monthValue]} ${d.year}"
            } catch (e: Exception) { stringResource(R.string.common_other) }
        }

        Column {
            notesByMonth.forEach { (monthLabel, monthNotes) ->
                // Заголовок месяца
                Text(
                    monthLabel,
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = AppleTheme.colors.secondaryLabel,
                    modifier   = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                // Заметки месяца с Timeline
                monthNotes.forEachIndexed { idx, note ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Линия + точка
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (note.type) {
                                            NoteType.IMPORTANT_TO_REMEMBER ->
                                                AppleTheme.colors.red
                                            NoteType.WORK ->
                                                AppleTheme.colors.orange
                                            NoteType.DATE_EVENT ->
                                                AppleTheme.colors.brand
                                            else ->
                                                AppleTheme.colors.tertiaryLabel
                                        }
                                    )
                            )
                            if (idx < monthNotes.size - 1) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(if (note.text.length > 80) 80.dp else 56.dp)
                                        .background(
                                            AppleTheme.colors.separator.copy(alpha = 0.4f)
                                        )
                                )
                            }
                        }
                        // Карточка заметки
                        Card(
                            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                            shape    = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            colors   = CardDefaults.cardColors(
                                containerColor = when {
                                    note.isImportant ->
                                        AppleTheme.colors.red.copy(alpha = 0.12f).copy(alpha = 0.2f)
                                    note.type == NoteType.WORK ->
                                        AppleTheme.colors.orange.copy(alpha = 0.14f).copy(alpha = 0.2f)
                                    else ->
                                        AppleTheme.colors.card.copy(alpha = 0.4f)
                                }
                            ),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    Arrangement.SpaceBetween,
                                    Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment     = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                            color = AppleTheme.colors.brand.copy(alpha = 0.10f)
                                        ) {
                                            Text(
                                                note.type.label(ctxLabel),
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = AppleTheme.colors.brand,
                                                modifier = Modifier.padding(
                                                    horizontal = 6.dp, vertical = 2.dp
                                                )
                                            )
                                        }
                                        if (note.isImportant)
                                            Icon(Icons.Outlined.Star, null,
                                                Modifier.size(12.dp),
                                                tint = AppleTheme.colors.red)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            note.createdAt.take(10),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppleTheme.colors.secondaryLabel
                                        )
                                        Box {
                                            var menuOpen by remember { mutableStateOf(false) }
                                            IconButton(
                                                onClick = { menuOpen = true },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.MoreVert, stringResource(R.string.cd_note_actions),
                                                    Modifier.size(16.dp),
                                                    tint = AppleTheme.colors.secondaryLabel)
                                            }
                                            DropdownMenu(
                                                expanded = menuOpen,
                                                onDismissRequest = { menuOpen = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.cd_edit_short)) },
                                                    leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                                                    onClick = { menuOpen = false; onEditNote(note) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.common_delete), color = AppleTheme.colors.red) },
                                                    leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp),
                                                        tint = AppleTheme.colors.red) },
                                                    onClick = { menuOpen = false; onDeleteNote(note) }
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                // Приватность: важные («защищённые») заметки скрываются
                                // блюром при включённом режиме приватности (замок в шапке).
                                val protectedHidden = privacyMode && note.isImportant
                                Box {
                                    // На API<31 Modifier.blur не работает — маскируем текст,
                                    // чтобы приватность соблюдалась на всех устройствах.
                                    val masked = protectedHidden &&
                                        android.os.Build.VERSION.SDK_INT < 31
                                    Text(
                                        if (masked) "•".repeat(note.text.length.coerceIn(4, 60))
                                        else note.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = if (protectedHidden) Modifier.blur(7.dp) else Modifier
                                    )
                                    if (protectedHidden) {
                                        Column(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(AppleTheme.colors.card.copy(alpha = 0.4f))
                                                .clickable { onTogglePrivacy() },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(Icons.Outlined.Lock, null, Modifier.size(20.dp),
                                                tint = AppleTheme.colors.secondaryLabel)
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                stringResource(R.string.cd_note_tap_reveal),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = AppleTheme.colors.secondaryLabel
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Личные детали
    item {
        val pd = contact.personalDetails.filter {
            it.category in setOf(
                PersonalDetailCategory.INTERESTS, PersonalDetailCategory.HABITS,
                PersonalDetailCategory.BRANDS, PersonalDetailCategory.COMMUNICATION_STYLE,
                PersonalDetailCategory.OTHER
            )
        }
        if (pd.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            CardBlock(title = stringResource(R.string.cd_personal_details)) {
                pd.groupBy { it.category }.forEach { (cat, items) ->
                    Text(cat.label(ctxLabel), style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.brand, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    items.forEach { d ->
                        Text("• ${d.value}", style = MaterialTheme.typography.bodyMedium)
                        if (!d.note.isNullOrBlank())
                            Text("  ${d.note}", style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
