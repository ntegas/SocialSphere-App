package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.BorderStroke
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
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

// Солидный тон градиента avatarSage (0xFF9DBE92→0xFF5E8C66) — для бейджа
// «Рабочая» заметки нужен ровный цвет, не Brush.
private val NoteWorkGreen = Color(0xFF5E8C66)

// ═══════════════════════════════════════════════════════════════
// TAB 4 — ЗАМЕТКИ (по макету Aurelia)
// ═══════════════════════════════════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.notesTab(
    contact: Contact,
    onShowAdd: () -> Unit,
    onShowVoice: () -> Unit,
    onEditNote: (Note) -> Unit,
    onDeleteNote: (Note) -> Unit,
    // Правка существующей личной детали (раньше записанное было не изменить)
    onEditDetail: (PersonalDetail) -> Unit = {}
, ctxLabel: android.content.Context,
    privacyMode: Boolean = false,
    onTogglePrivacy: () -> Unit = {}) {
    // Одна большая кнопка по макету (было две тесных в ряд — из-за этого
    // текст второй кнопки визуально обрезался). Добавление личной детали
    // (бывшая вторая кнопка, onShowVoice/showVoiceDialog) перенесено к
    // разделу «Личные детали» ниже — там, где эти данные и показываются.
    item {
        Button(
            onClick = onShowAdd,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppleTheme.colors.brand,
                contentColor   = Color.White
            )
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.cd_add_note), fontWeight = FontWeight.Bold)
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

        // Группировка по месяцам (без «Цели и мечты» — те в своём разделе ниже,
        // как в макете: «Мечты и цели» вынесены из общей ленты).
        val ruMonths = mapOf(
            1 to stringResource(R.string.month_1), 2 to stringResource(R.string.month_2),
            3 to stringResource(R.string.month_3), 4 to stringResource(R.string.month_4),
            5 to stringResource(R.string.month_5), 6 to stringResource(R.string.month_6),
            7 to stringResource(R.string.month_7), 8 to stringResource(R.string.month_8),
            9 to stringResource(R.string.month_9), 10 to stringResource(R.string.month_10),
            11 to stringResource(R.string.month_11), 12 to stringResource(R.string.month_12)
        )
        val timelineNotes = allNotes.filterNot { it.type == NoteType.PERSONAL_DETAIL }
        val notesByMonth = timelineNotes.groupBy { note ->
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
                // Заметки месяца (по макету: сплошная карточка, без timeline-
                // точек/линий — важные отмечены цветной левой полосой, а не
                // тонировкой всей карточки).
                monthNotes.forEach { note ->
                    val badgeColor = when {
                        note.isImportant                 -> AppleTheme.colors.red
                        note.type == NoteType.WORK        -> NoteWorkGreen
                        note.type == NoteType.DATE_EVENT  -> AppleTheme.colors.brand
                        note.type == NoteType.GIFT        -> AureliaTheme.colors.gold
                        else                               -> AppleTheme.colors.secondaryLabel
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape    = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        border   = if (note.isImportant) BorderStroke(2.4.dp, AppleTheme.colors.red) else null,
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
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                            color = badgeColor.copy(alpha = if (note.isImportant) 0.14f else 0.18f)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                if (note.isImportant)
                                                    Icon(Icons.Outlined.Lock, null, Modifier.size(10.dp), tint = badgeColor)
                                                Text(
                                                    if (note.isImportant) stringResource(R.string.cd_note_protected)
                                                    else note.type.label(ctxLabel),
                                                    style    = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color    = badgeColor
                                                )
                                            }
                                        }
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
                                    // Маскируем ВСЕГДА при скрытии: blur — только визуальный
                                    // эффект, под ним в семантике лежал реальный текст —
                                    // Accessibility/скринридер мог его прочитать (аудит 2026-07-02).
                                    val masked = protectedHidden
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

    // Цели, мечты и важное — NoteType.PERSONAL_DETAIL вынесены из общей ленты
    // в свою секцию с иконкой (по макету «Мечты и цели»).
    item {
        val dreamNotes = AppStateStore.notes.filter {
            it.contactId == contact.id && it.type == NoteType.PERSONAL_DETAIL
        }
        if (dreamNotes.isNotEmpty()) {
            Text(
                stringResource(R.string.cd_goals_dreams).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = AppleTheme.colors.tertiaryLabel,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dreamNotes.forEach { note ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AppleTheme.colors.card)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(AureliaTheme.colors.gold.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Star, null, Modifier.size(18.dp), tint = AureliaTheme.colors.gold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(note.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (!note.date.isNullOrBlank())
                                Text(note.date, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        // Правка/удаление — как у заметок ленты (раньше «мечты»
                        // после создания было не изменить)
                        Box {
                            var dreamMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { dreamMenu = true }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.cd_note_actions),
                                    Modifier.size(16.dp), tint = AppleTheme.colors.secondaryLabel)
                            }
                            DropdownMenu(expanded = dreamMenu, onDismissRequest = { dreamMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cd_edit_short)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                                    onClick = { dreamMenu = false; onEditNote(note) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_delete), color = AppleTheme.colors.red) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp),
                                        tint = AppleTheme.colors.red) },
                                    onClick = { dreamMenu = false; onDeleteNote(note) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Личные детали — кнопка добавления (бывшая вторая кнопка вверху вкладки,
    // onShowVoice/showVoiceDialog — на деле открывает форму «личная деталь» с
    // выбором категории, а не голосовой ввод) видна всегда, список — только
    // если есть данные (как в GiftsTab: «+ Добавить» отдельно над CardBlock).
    item {
        val pd = contact.personalDetails.filter {
            it.category in setOf(
                PersonalDetailCategory.INTERESTS, PersonalDetailCategory.HABITS,
                PersonalDetailCategory.BRANDS, PersonalDetailCategory.COMMUNICATION_STYLE,
                PersonalDetailCategory.OTHER
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onShowVoice, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.cd_add_detail))
        }
        if (pd.isNotEmpty()) {
            CardBlock(title = stringResource(R.string.cd_personal_details)) {
                pd.groupBy { it.category }.forEach { (cat, items) ->
                    Text(cat.label(ctxLabel), style = MaterialTheme.typography.labelSmall,
                        color = AppleTheme.colors.brand, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    items.forEach { d ->
                        // Тап по строке (или карандаш) — правка/удаление детали
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onEditDetail(d) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("• ${d.value}", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Edit, stringResource(R.string.cd_edit_short),
                                Modifier.size(13.dp), tint = AppleTheme.colors.tertiaryLabel)
                        }
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
