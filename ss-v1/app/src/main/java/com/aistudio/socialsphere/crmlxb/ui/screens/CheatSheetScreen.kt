package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatSheetScreen(
    contactId: String,
    onNavigateBack: () -> Unit
) {
    val contact = AppStateStore.getContact(contactId)
    val ctxLabel = LocalContext.current

    if (contact == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stringResource(R.string.cs_not_found))
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

    // Data sources
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
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.cs_title), fontWeight = FontWeight.Bold)
                        Text(name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppleTheme.colors.groupedBackground
                )
            )
        },
        bottomBar = {
            // ТЗ (Экран 4): кнопки внизу — 📞 Позвонить + 💬 Написать
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Surface(color = AppleTheme.colors.card, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            ExternalActionHandler.openDialer(
                                ctx, contact.phones.firstOrNull { it.isPrimary }?.number
                                    ?: contact.phones.firstOrNull()?.number
                            )
                        },
                        enabled  = contact.phones.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Call, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cs_call))
                    }
                    Button(
                        onClick = {
                            val m = contact.messengers.firstOrNull()
                            if (m != null) ExternalActionHandler.openMessenger(ctx, m)
                            else ExternalActionHandler.openSms(
                                ctx, contact.phones.firstOrNull()?.number
                            )
                        },
                        enabled  = contact.messengers.isNotEmpty() || contact.phones.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor   = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cs_write))
                    }
                }
            }
        }
    ) { paddingValues ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            // ── Hero card ──────────────────────────────────────
            item {
                Card(
                    shape  = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(56.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            Alignment.Center
                        ) {
                            Text(
                                contact.firstName.take(1) + contact.lastName.take(1),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                        Column {
                            Text(name, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (company.isNotEmpty())
                                Text(
                                    listOf(position, company).filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                )
                            if (city.isNotEmpty())
                                Text(city, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            // ── Важно помнить ──────────────────────────────────
            if (impNotes.isNotEmpty()) {
                item {
                    SheetBlock(stringResource(R.string.cs_remember)) {
                        impNotes.forEach { note ->
                            Text("• ${note.text}",
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            // ── Последний контакт ──────────────────────────────
            if (lastNote != null) {
                item {
                    SheetBlock(stringResource(R.string.cs_last_note)) {
                        Text(lastNote.createdAt.take(10),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(4.dp))
                        Text(lastNote.text,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ── Ближайшие события ──────────────────────────────
            if (upcomingEvents.isNotEmpty()) {
                item {
                    SheetBlock(stringResource(R.string.cs_upcoming)) {
                        upcomingEvents.forEach { event ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                Arrangement.SpaceBetween
                            ) {
                                Text(event.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                Text(event.startDate,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            // ── Семья ──────────────────────────────────────────
            if (familyRels.isNotEmpty()) {
                item {
                    SheetBlock(stringResource(R.string.cs_family)) {
                        familyRels.forEach { rel ->
                            val isFirst   = rel.firstContactId == contact.id
                            val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                            // Роль ДРУГОГО человека
                            val role      = if (isFirst) rel.secondRole else rel.firstRole
                            val otherName = AppStateStore.getContact(otherId)
                                ?.let { "${it.firstName} ${it.lastName}".trim() }
                                ?: "—"
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                Arrangement.SpaceBetween
                            ) {
                                Text(role, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.weight(0.4f))
                                Text(otherName, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(0.6f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                        }
                    }
                }
            }

            // ── Интересы ───────────────────────────────────────
            if (interests.isNotEmpty()) {
                item {
                    SheetBlock(stringResource(R.string.cs_interests)) {
                        interests.forEach { detail ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("•", color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold)
                                Text("${detail.category.label(ctxLabel)}: ${detail.value}",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ── Цели и мечты ───────────────────────────────────
            if (dreamNotes.isNotEmpty()) {
                item {
                    SheetBlock(stringResource(R.string.cs_goals)) {
                        dreamNotes.forEach { note ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("→", color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold)
                                Text(note.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ── Чем может помочь ───────────────────────────────
            if (!contact.canHelpWith.isNullOrBlank()) {
                item {
                    SheetBlock(stringResource(R.string.cs_can_help)) {
                        Text(
                            contact.canHelpWith,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // ── Чем я могу помочь ──────────────────────────────
            if (!contact.iCanHelpWith.isNullOrBlank()) {
                item {
                    SheetBlock(stringResource(R.string.cs_i_can_help)) {
                        Text(
                            contact.iCanHelpWith,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // ── Темы для разговора ─────────────────────────────
            if (!contact.talkingPoints.isNullOrBlank()) {
                item {
                    SheetBlock(stringResource(R.string.cs_talking)) {
                        // Split by newline or semicolon for bullet list
                        val points = contact.talkingPoints
                            .split("\n", ";")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        if (points.size > 1) {
                            points.forEach { point ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Text("→", color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold)
                                    Text(point, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            Text(contact.talkingPoints,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // ── Ограничения / аллергии ─────────────────────────
            if (restrictions.isNotEmpty()) {
                item {
                    SheetBlock(stringResource(R.string.cs_restrictions)) {
                        restrictions.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("!", color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold)
                                Text("${r.category.label(ctxLabel)}: ${r.value}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // ── Как связаться ──────────────────────────────────
            val hasContact = primaryPhone.isNotBlank() || primaryMessenger != null
            if (hasContact) {
                item {
                    SheetBlock(stringResource(R.string.cs_how_contact)) {
                        if (primaryPhone.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Phone, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(primaryPhone, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (primaryMessenger != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text("${primaryMessenger.type.label(ctxLabel)}: ${primaryMessenger.value}",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ── Пустое состояние ───────────────────────────────
            if (!hasContact && impNotes.isEmpty() && lastNote == null &&
                familyRels.isEmpty() && interests.isEmpty() && dreamNotes.isEmpty() &&
                contact.canHelpWith.isNullOrBlank() && contact.iCanHelpWith.isNullOrBlank() &&
                contact.talkingPoints.isNullOrBlank()
            ) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.EditNote, null, Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant)
                            Text(stringResource(R.string.cs_fill_profile),
                                color = MaterialTheme.colorScheme.secondary)
                            Text(stringResource(R.string.cs_fill_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetBlock(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = AppleTheme.colors.card
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
