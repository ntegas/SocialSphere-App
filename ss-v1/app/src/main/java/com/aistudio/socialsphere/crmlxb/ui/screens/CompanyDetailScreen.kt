package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyDetailScreen(
    companyId: String,
    onNavigateBack: () -> Unit,
    onNavigateToContact: (String) -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToCreateCalendarItem: () -> Unit
) {
    val company = AppStateStore.getCompany(companyId)
    if (company == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.compd_not_found))
        }
        return
    }

    val ctxLabel = LocalContext.current
    // Вкладки по макету Aurelia: Люди · Контакты · Адреса
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.compd_tab_people),
        stringResource(R.string.common_contacts),
        stringResource(R.string.cd_addresses)
    )
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = AppleTheme.colors.red) },
            title = { Text(stringResource(R.string.compd_delete_q), fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.compd_delete_warning, company.name)) },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; AppStateStore.deleteCompany(companyId); onNavigateBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.red)
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // Статистика по сотрудникам (для трёх стат-боксов hero)
    val relations = AppStateStore.companyRelations.filter { it.companyId == company.id }
    val peopleCount    = relations.size
    val keyCount       = relations.count { AppStateStore.getContact(it.contactId)?.importanceLevel == ImportanceLevel.KEY }
    val importantCount = relations.count { AppStateStore.getContact(it.contactId)?.importanceLevel == ImportanceLevel.IMPORTANT }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Шапка: круглые кнопки назад / редактировать / удалить (по макету) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) { onNavigateBack() }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CircleIconButton(Icons.Default.Edit, stringResource(R.string.common_edit), tinted = true) { onNavigateToEdit() }
                    CircleIconButton(Icons.Default.Delete, stringResource(R.string.common_delete), danger = true) { showDeleteDialog = true }
                }
            }

            // ── Hero: центрированный лого + название + чипы ──
            CompanyHero(company, ctxLabel)

            // ── 3 стат-бокса: сотрудников · ключевых · важных ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompanyStat(peopleCount.toString(), stringResource(R.string.compd_stat_people), AppleTheme.colors.label, Modifier.weight(1f))
                CompanyStat(keyCount.toString(), stringResource(R.string.comp_stat_key), AppleTheme.colors.orange, Modifier.weight(1f))
                CompanyStat(importantCount.toString(), stringResource(R.string.comp_stat_important), AppleTheme.colors.red, Modifier.weight(1f))
            }

            // ── Вкладки: активная — бренд-подчёркивание 2.5dp + волосяная линия ──
            val dividerColor = AppleTheme.colors.separator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .drawBehind {
                        val y = size.height - 0.5.dp.toPx()
                        drawLine(dividerColor, androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    }
                    .padding(start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val sel = selectedTab == index
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { selectedTab = index }
                    ) {
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            maxLines = 1,
                            softWrap = false,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (sel) AppleTheme.colors.label else AppleTheme.colors.tertiaryLabel,
                            modifier = Modifier.padding(bottom = 11.dp)
                        )
                        Box(
                            Modifier.height(2.5.dp).width(28.dp)
                                .background(if (sel) AppleTheme.colors.brand else Color.Transparent,
                                    RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        )
                    }
                }
            }

            // weight(1f), НЕ fillMaxSize() — иначе список под шапкой+табами схлопывается
            // до «полэкрана» (повторяющийся баг скролла в Column).
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> companyPeopleTab(company, onNavigateToContact, ctxLabel = ctxLabel)
                    1 -> companyContactsTab(company, onNavigateToCreateCalendarItem, ctxLabel = ctxLabel)
                    2 -> companyAddressesTab(company, onNavigateToEdit, ctxLabel = ctxLabel)
                }
            }
        }
    }
}

// ─── Круглая кнопка-иконка шапки (по макету) ─────────────────────────────
@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tinted: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        danger -> AppleTheme.colors.red.copy(alpha = 0.12f)
        tinted -> AppleTheme.colors.brand.copy(alpha = 0.12f)
        else   -> AppleTheme.colors.fill
    }
    val tint = when {
        danger -> AppleTheme.colors.red
        tinted -> AppleTheme.colors.brand
        else   -> AppleTheme.colors.label
    }
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, Modifier.size(18.dp), tint = tint)
    }
}

// ─── Центрированный hero компании (по макету Aurelia) ────────────────────
@Composable
fun CompanyHero(company: Company, ctxLabel: android.content.Context) {
    val addresses = AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
    val mainCity = addresses.firstOrNull { it.addressType == AddressType.OFFICE || it.addressType == AddressType.LEGAL }?.city
        ?: addresses.firstOrNull()?.city ?: ""
    val initial = company.name.take(1).uppercase()

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(22.dp))
                // Малахитовый градиент Aurelia (точно по макету)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                    Color(0xFF2E8B6B), Color(0xFF155539)))),
            contentAlignment = Alignment.Center
        ) {
            Text(initial, color = Color.White,
                fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            company.name,
            fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
            fontSize = 26.sp, fontWeight = FontWeight.Bold,
            color = AppleTheme.colors.label,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp)
        )
        Row(
            modifier = Modifier.padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(13.dp)).background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(company.industry.label(ctxLabel), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.brand)
            }
            if (mainCity.isNotEmpty()) {
                Box(
                    Modifier.clip(RoundedCornerShape(13.dp)).background(AppleTheme.colors.fill)
                        .padding(horizontal = 11.dp, vertical = 5.dp)
                ) {
                    Text(mainCity, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.secondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun CompanyStat(value: String, label: String, valueColor: Color, modifier: Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(AppleTheme.colors.card).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = valueColor)
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = AppleTheme.colors.secondaryLabel, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

// ─── Заголовок секции внутри вкладки ─────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = AppleTheme.colors.tertiaryLabel,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

// ════════════════════════ ВКЛАДКА «ЛЮДИ» ════════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.companyPeopleTab(
    company: Company, onNavigateToContact: (String) -> Unit, ctxLabel: android.content.Context
) {
    val relations = AppStateStore.companyRelations.filter { it.companyId == company.id }

    // Добавление сотрудника (функция сохранена из прежней реализации)
    item {
        var showAdd  by remember { mutableStateOf(false) }
        var search   by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<Contact?>(null) }
        var position by remember { mutableStateOf("") }

        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.compd_add_employee))
        }
        if (showAdd) {
            val candidates = AppStateStore.contacts.filter { c ->
                relations.none { it.contactId == c.id } &&
                "${c.firstName} ${c.lastName}".contains(search, ignoreCase = true)
            }
            AlertDialog(
                onDismissRequest = { showAdd = false; selected = null; search = ""; position = "" },
                title = { Text(stringResource(R.string.compd_add_employee), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val sel = selected
                        if (sel == null) {
                            OutlinedTextField(
                                value = search, onValueChange = { search = it },
                                label = { Text(stringResource(R.string.ce_search_contact)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            candidates.take(8).forEach { c ->
                                Text(
                                    "${c.firstName} ${c.lastName}".trim(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable { selected = c }.padding(vertical = 8.dp)
                                )
                            }
                            if (candidates.isEmpty()) {
                                Text(
                                    stringResource(R.string.compd_no_candidates),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppleTheme.colors.secondaryLabel
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${sel.firstName} ${sel.lastName}".trim(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.ce_change)) }
                            }
                            OutlinedTextField(
                                value = position, onValueChange = { position = it }, keyboardOptions = CapSentences,
                                label = { Text(stringResource(R.string.cd_position)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(enabled = selected != null, onClick = {
                        selected?.let { c ->
                            AppStateStore.updateContact(c.copy(
                                companyRelations = c.companyRelations + ContactCompanyRelation(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contactId = c.id,
                                    companyId = company.id,
                                    position = position.ifBlank { null },
                                    employmentStatus = EmploymentStatus.CURRENT,
                                    isPrimary = c.companyRelations.isEmpty()
                                )
                            ))
                        }
                        selected = null; search = ""; position = ""; showAdd = false
                    }) { Text(stringResource(R.string.common_add)) }
                },
                dismissButton = { TextButton(onClick = { showAdd = false; selected = null; search = ""; position = "" }) { Text(stringResource(R.string.common_cancel)) } }
            )
        }
    }

    if (relations.isEmpty()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.compd_no_people), color = AppleTheme.colors.secondaryLabel)
            }
        }
    } else {
        // Сгруппированная inset-карточка со строками сотрудников (по макету):
        // аватар-градиент + имя + должность/отдел/роль + шеврон → карточка контакта.
        item {
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column {
                    relations.forEachIndexed { idx, rel ->
                        val contact = AppStateStore.getContact(rel.contactId)
                        if (contact != null) {
                            CompanyPersonRow(contact, rel) { onNavigateToContact(contact.id) }
                            if (idx < relations.lastIndex)
                                HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = AppleTheme.colors.separator, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

private val companyPersonGrads = listOf(
    listOf(Color(0xFFE59A6B), Color(0xFFC45D34)),
    listOf(Color(0xFF9DBE92), Color(0xFF5E8C66)),
    listOf(Color(0xFFB58CB6), Color(0xFF7E5180)),
    listOf(Color(0xFF7FBDB2), Color(0xFF3E7E7A)),
    listOf(Color(0xFFD8B26A), Color(0xFFB68A36))
)

@Composable
private fun CompanyPersonRow(contact: Contact, rel: ContactCompanyRelation, onClick: () -> Unit) {
    val g = companyPersonGrads[kotlin.math.abs(contact.id.hashCode()) % companyPersonGrads.size]
    val sub = listOfNotNull(rel.position, rel.department, rel.role).filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(g)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (contact.firstName.firstOrNull()?.toString() ?: "") + (contact.lastName.firstOrNull()?.toString() ?: ""),
                fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("${contact.firstName} ${contact.lastName}".trim(), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = AppleTheme.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty())
                Text(sub, fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
    }
}

// ════════════════════════ ВКЛАДКА «КОНТАКТЫ» ════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.companyContactsTab(
    company: Company, onNavigateToCreateCalendarItem: () -> Unit, ctxLabel: android.content.Context
) {
    val hasChannels = company.phones.isNotEmpty() || company.emails.isNotEmpty() || company.website != null

    // Описание
    company.description?.takeIf { it.isNotBlank() }?.let { desc ->
        item {
            CardBlock(title = stringResource(R.string.compd_description)) {
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (hasChannels) {
        // Телефоны
        if (company.phones.isNotEmpty()) {
            item {
                Column {
                    SectionLabel(stringResource(R.string.cd_phones))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        val context = LocalContext.current
                        Column {
                            company.phones.forEachIndexed { idx, ph ->
                                ChannelRow(Icons.Default.Phone, ph.number, ph.type.label(ctxLabel), AppleTheme.colors.brand) {
                                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(context, ph.number)
                                }
                                if (idx < company.phones.lastIndex)
                                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = AppleTheme.colors.separator, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
        // Почта и сайт
        if (company.emails.isNotEmpty() || company.website != null) {
            item {
                Column {
                    SectionLabel(stringResource(R.string.compd_mail_site))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        val context = LocalContext.current
                        Column {
                            val items = buildList {
                                company.emails.forEach { add(it) }
                                company.website?.let { add(it) }
                            }
                            company.emails.forEachIndexed { idx, em ->
                                ChannelRow(Icons.Default.Email, em.email, em.type.label(ctxLabel), Color(0xFF5E78C4)) {
                                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(context, em.email)
                                }
                                val last = idx == company.emails.lastIndex && company.website == null
                                if (!last) HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = AppleTheme.colors.separator, thickness = 0.5.dp)
                            }
                            company.website?.let { ws ->
                                ChannelRow(Icons.Default.Language, ws, stringResource(R.string.comp_website), AppleTheme.colors.brand) {
                                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openWebsite(context, ws)
                                }
                            }
                            if (items.isEmpty()) Spacer(Modifier.height(0.dp))
                        }
                    }
                }
            }
        }
    } else if (company.description.isNullOrBlank()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.compd_no_contacts), color = AppleTheme.colors.secondaryLabel)
            }
        }
    }

    // Ближайшие события + кнопка добавления (функция сохранена)
    item {
        CardBlock(title = stringResource(R.string.home_upcoming)) {
            val events = AppStateStore.calendarItems.filter { it.links.any { link -> link.targetId == company.id } && it.status == CalendarItemStatus.ACTIVE }
            if (events.isNotEmpty()) {
                events.forEach { InfoRow(it.startDate, it.title) }
            } else {
                Text(stringResource(R.string.cd_no_events), color = AppleTheme.colors.secondaryLabel, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNavigateToCreateCalendarItem,
                colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.card, contentColor = AppleTheme.colors.secondaryLabel),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cal_add_event))
            }
        }
    }

    // Заметки
    item {
        CardBlock(title = stringResource(R.string.cd_tab_notes)) {
            val notes = AppStateStore.notes.filter { it.companyId == company.id }
            if (notes.isNotEmpty()) {
                notes.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium) }
            } else {
                Text(stringResource(R.string.cd_no_notes_yet), color = AppleTheme.colors.secondaryLabel, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChannelRow(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, sub: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(16.dp), tint = tint) }
        Column(modifier = Modifier.weight(1f)) {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty())
                Text(sub, fontSize = 11.sp, color = AppleTheme.colors.secondaryLabel)
        }
    }
}

// ════════════════════════ ВКЛАДКА «АДРЕСА» ══════════════════════════════
fun androidx.compose.foundation.lazy.LazyListScope.companyAddressesTab(
    company: Company, onNavigateToEdit: () -> Unit, ctxLabel: android.content.Context
) {
    val addresses = AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }

    // Декоративная мини-карта (по макету; реальная карта — на вкладке «Карта»)
    item {
        Box(
            modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFE6E0D4)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(AppleTheme.colors.brand))
        }
    }

    // Заголовок «Офисы» + добавить
    item {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.compd_offices))
            Text("+ " + stringResource(R.string.common_add), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = AppleTheme.colors.brand, modifier = Modifier.clickable { onNavigateToEdit() }.padding(bottom = 8.dp))
        }
    }

    if (addresses.isEmpty()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.cd_addresses_none), color = AppleTheme.colors.secondaryLabel)
            }
        }
    } else {
        items(addresses, key = { it.id }) { address ->
            val context = LocalContext.current
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AppleTheme.colors.brand.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp), tint = AppleTheme.colors.brand) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(address.addressType.label(ctxLabel), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label)
                        Text(
                            "${address.addressLine}, ${address.city}${address.postalCode?.let { " $it" } ?: ""}",
                            fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        if (address.latitude != null && address.longitude != null) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                        } else {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
                        }
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Directions, contentDescription = stringResource(R.string.map_route), modifier = Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                    }
                }
            }
        }
    }
}
