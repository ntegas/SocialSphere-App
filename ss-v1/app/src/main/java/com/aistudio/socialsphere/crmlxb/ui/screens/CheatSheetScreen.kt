@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.*

// Палитра тёмного листа-шпаргалки (макет Aurelia)
private val CsBg     = Color(0xFF13261D)
private val CsTx     = Color(0xFFF3EFE8)
private val CsMuted  = Color(0x8CF3EFE8) // .55
private val CsCard   = Color(0x0DF3EFE8) // .05
private val CsChip   = Color(0x14F3EFE8) // .08
private val CsBorder = Color(0x14F3EFE8)
private val CsGold   = Color(0xFFD7B468)
private val CsSage   = Color(0xFF9FCBA6)
private val CsTerra  = Color(0xFFE59A6B)
private val CsWarn   = Color(0xFFE0846E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatSheetScreen(
    contactId: String,
    onNavigateBack: () -> Unit
) {
    val contact = AppStateStore.getContact(contactId)
    val ctxLabel = LocalContext.current

    if (contact == null) {
        Box(Modifier.fillMaxSize().background(CsBg), Alignment.Center) {
            Text(stringResource(R.string.cs_not_found), color = CsTx)
        }
        return
    }

    val name       = "${contact.firstName} ${contact.lastName}".trim()
    val compRel    = contact.companyRelations.firstOrNull { it.isPrimary }
        ?: contact.companyRelations.firstOrNull()
    val company    = compRel?.companyId?.let { AppStateStore.getCompany(it)?.name } ?: ""
    val position   = compRel?.position ?: ""
    val city       = AppStateStore.addresses
        .find { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
        ?.city ?: ""

    val impNotes     = AppStateStore.notes.filter {
        it.contactId == contact.id && it.type == NoteType.IMPORTANT_TO_REMEMBER
    }
    val lastNote     = AppStateStore.notes
        .filter { it.contactId == contact.id }
        .maxByOrNull { it.createdAt }
    val upcomingEvents = AppStateStore.calendarItems.filter {
        it.links.any { l -> l.targetId == contact.id } &&
        it.status == CalendarItemStatus.ACTIVE
    }.sortedBy { it.startDate }.take(3)
    val familyRoles  = setOf("Муж","Жена","Партнёр","Отец","Мать","Сын","Дочь","Брат","Сестра")
    val familyRels   = AppStateStore.contactRelations.filter {
        (it.firstContactId == contact.id || it.secondContactId == contact.id) &&
        run {
            val role = if (it.firstContactId == contact.id) it.firstRole else it.secondRole
            role in familyRoles
        }
    }
    val interests    = contact.personalDetails.filter {
        it.category in setOf(
            PersonalDetailCategory.INTERESTS,
            PersonalDetailCategory.FOOD,
            PersonalDetailCategory.DRINKS,
            PersonalDetailCategory.HABITS
        )
    }
    val restrictions = contact.personalDetails.filter {
        it.category in setOf(PersonalDetailCategory.ALLERGIES, PersonalDetailCategory.RESTRICTIONS)
    }
    val dreamNotes   = AppStateStore.notes.filter {
        it.contactId == contact.id && it.type == NoteType.PERSONAL_DETAIL
    }
    val primaryPhone = contact.phones.find { it.isPrimary }?.number
        ?: contact.phones.firstOrNull()?.number ?: ""
    val primaryMessenger = contact.messengers.find { it.isPrimary }
        ?: contact.messengers.firstOrNull()

    Scaffold(
        containerColor = CsBg,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.cs_title).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, color = CsGold)
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(CsChip).clickable { onNavigateBack() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, stringResource(R.string.common_back), Modifier.size(17.dp), tint = CsTx) }
            }
        },
        bottomBar = {
            val ctx = LocalContext.current
            Surface(color = CsBg) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Button(
                        onClick = {
                            ExternalActionHandler.openDialer(
                                ctx, contact.phones.firstOrNull { it.isPrimary }?.number
                                    ?: contact.phones.firstOrNull()?.number
                            )
                        },
                        enabled  = contact.phones.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape    = RoundedCornerShape(15.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Call, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cs_call), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            val m = contact.messengers.firstOrNull()
                            if (m != null) ExternalActionHandler.openMessenger(ctx, m)
                            else ExternalActionHandler.openSms(ctx, contact.phones.firstOrNull()?.number)
                        },
                        enabled  = contact.messengers.isNotEmpty() || contact.phones.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape    = RoundedCornerShape(15.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = CsChip, contentColor = CsTx)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cs_write), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            // ── Hero ──
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(60.dp).clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFE59A6B), Color(0xFFC45D34))))
                            .border(2.dp, CsGold.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text((contact.firstName.take(1) + contact.lastName.take(1)).uppercase(),
                            color = Color.White, fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                            fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(name, fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CsTx)
                        val sub = listOf(position, company, city).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotEmpty())
                            Text(sub, fontSize = 12.sp, color = CsMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            // ── Важно помнить (золотая карта) ──
            if (impNotes.isNotEmpty()) item {
                CsBlock(stringResource(R.string.cs_remember), CsGold, Icons.Default.Star, gold = true) {
                    impNotes.forEach { note ->
                        Text(note.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CsTx, lineHeight = 21.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // ── Последняя запись ──
            if (lastNote != null) item {
                CsBlock(stringResource(R.string.cs_last_note), CsSage, Icons.Default.Schedule) {
                    Text(lastNote.createdAt.take(10), fontSize = 11.sp, color = CsMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(lastNote.text, fontSize = 14.sp, color = CsTx)
                }
            }

            // ── Ближайшее ──
            if (upcomingEvents.isNotEmpty()) item {
                CsBlock(stringResource(R.string.cs_upcoming), CsGold, Icons.Default.Event) {
                    upcomingEvents.forEach { event ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                            Text(event.title, fontSize = 14.sp, color = CsTx, modifier = Modifier.weight(1f))
                            Text(event.startDate, fontSize = 13.sp, color = CsGold, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Семья ──
            if (familyRels.isNotEmpty()) item {
                CsBlock(stringResource(R.string.cs_family), CsTerra, Icons.Default.Group) {
                    familyRels.forEach { rel ->
                        val isFirst   = rel.firstContactId == contact.id
                        val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                        val role      = if (isFirst) rel.secondRole else rel.firstRole
                        val otherName = AppStateStore.getContact(otherId)
                            ?.let { "${it.firstName} ${it.lastName}".trim() } ?: "—"
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                            Text(role ?: "", fontSize = 13.sp, color = CsMuted, modifier = Modifier.weight(0.4f))
                            Text(otherName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CsTx,
                                modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
                        }
                    }
                }
            }

            // ── Темы для разговора (чипы) ──
            if (!contact.talkingPoints.isNullOrBlank()) item {
                CsBlock(stringResource(R.string.cs_talking), CsSage, Icons.AutoMirrored.Filled.Chat) {
                    val points = contact.talkingPoints.split("\n", ";").map { it.trim() }.filter { it.isNotBlank() }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        points.forEach { CsTag(it) }
                    }
                }
            }

            // ── Интересы и вкусы (чипы) ──
            if (interests.isNotEmpty()) item {
                CsBlock(stringResource(R.string.cs_interests), CsTerra, Icons.Default.Favorite) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        interests.forEach { CsTag(it.value) }
                    }
                    if (restrictions.isNotEmpty()) {
                        Spacer(Modifier.height(11.dp))
                        HorizontalDivider(color = CsBorder, thickness = 1.dp)
                        Spacer(Modifier.height(11.dp))
                        restrictions.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = CsWarn)
                                Text("${r.category.label(ctxLabel)}: ${r.value}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CsWarn)
                            }
                        }
                    }
                }
            } else if (restrictions.isNotEmpty()) item {
                CsBlock(stringResource(R.string.cs_restrictions), CsWarn, Icons.Default.Warning) {
                    restrictions.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = CsWarn)
                            Text("${r.category.label(ctxLabel)}: ${r.value}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CsWarn)
                        }
                    }
                }
            }

            // ── Цели и мечты ──
            if (dreamNotes.isNotEmpty()) item {
                CsBlock(stringResource(R.string.cs_goals), CsGold, Icons.Default.Star) {
                    dreamNotes.forEach { note ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("→", color = CsGold, fontWeight = FontWeight.Bold)
                            Text(note.text, fontSize = 14.sp, color = CsTx)
                        }
                    }
                }
            }

            // ── Чем может помочь ──
            if (!contact.canHelpWith.isNullOrBlank()) item {
                CsBlock(stringResource(R.string.cs_can_help), CsSage, Icons.Default.Favorite) {
                    Text(contact.canHelpWith, fontSize = 14.sp, color = CsTx)
                }
            }

            // ── Чем я могу помочь ──
            if (!contact.iCanHelpWith.isNullOrBlank()) item {
                CsBlock(stringResource(R.string.cs_i_can_help), CsSage, Icons.Default.Favorite) {
                    Text(contact.iCanHelpWith, fontSize = 14.sp, color = CsTx)
                }
            }

            // ── Как связаться ──
            val hasContact = primaryPhone.isNotBlank() || primaryMessenger != null
            if (hasContact) item {
                CsBlock(stringResource(R.string.cs_how_contact), CsSage, Icons.Default.Phone) {
                    if (primaryPhone.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, null, Modifier.size(15.dp), tint = CsSage)
                            Text(primaryPhone, fontSize = 14.sp, color = CsTx)
                        }
                    }
                    if (primaryMessenger != null) {
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(15.dp), tint = CsSage)
                            Text("${primaryMessenger.type.label(ctxLabel)}: ${primaryMessenger.value}", fontSize = 14.sp, color = CsTx)
                        }
                    }
                }
            }

            // ── Пустое состояние ──
            if (!hasContact && impNotes.isEmpty() && lastNote == null &&
                familyRels.isEmpty() && interests.isEmpty() && dreamNotes.isEmpty() &&
                restrictions.isEmpty() &&
                contact.canHelpWith.isNullOrBlank() && contact.iCanHelpWith.isNullOrBlank() &&
                contact.talkingPoints.isNullOrBlank()
            ) item {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.EditNote, null, Modifier.size(48.dp), tint = CsMuted)
                        Text(stringResource(R.string.cs_fill_profile), color = CsTx)
                        Text(stringResource(R.string.cs_fill_hint), fontSize = 13.sp, color = CsMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun CsBlock(title: String, accent: Color, icon: ImageVector, gold: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (gold) CsGold.copy(alpha = 0.16f) else CsCard)
            .border(1.dp, if (gold) CsGold.copy(alpha = 0.28f) else CsBorder, RoundedCornerShape(18.dp))
            .padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(accent.copy(alpha = 0.22f)), Alignment.Center) {
                Icon(icon, null, Modifier.size(13.dp), tint = accent)
            }
            // Строки cs_* содержат ведущий эмодзи — иконка показана плиткой слева,
            // поэтому в подписи эмодзи убираем.
            Text(title.dropWhile { !it.isLetter() }.trim().uppercase(),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = accent)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun CsTag(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CsTx,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CsChip).padding(horizontal = 12.dp, vertical = 6.dp))
}
