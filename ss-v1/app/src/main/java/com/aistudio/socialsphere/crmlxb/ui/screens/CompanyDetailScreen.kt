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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.cd_tab_overview), stringResource(R.string.compd_tab_people))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.common_edit))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color(0xFF2E8B6B), navigationIconContentColor = androidx.compose.ui.graphics.Color.White, actionIconContentColor = androidx.compose.ui.graphics.Color.White, titleContentColor = androidx.compose.ui.graphics.Color.White)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            CompanyHeader(company)

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                companyPeopleTab(company, onNavigateToContact, ctxLabel = ctxLabel)
                companyOverviewTab(company, onNavigateToCreateCalendarItem, ctxLabel = ctxLabel)
            }
        }
    }
}

@Composable
fun CompanyHeader(company: Company, onShowPeople: () -> Unit = {}) {
    val ctxLabel = LocalContext.current
    val addresses = AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
    val mainCity = addresses.firstOrNull { it.addressType == AddressType.OFFICE || it.addressType == AddressType.LEGAL }?.city
        ?: addresses.firstOrNull()?.city ?: stringResource(R.string.common_unknown)

    val initial = company.name.take(1).uppercase()
    val cW = androidx.compose.ui.graphics.Color.White

    Box(
        modifier = Modifier.fillMaxWidth()
            // Малахитовый градиент Aurelia (был старый индиго #5B53D6 — не из палитры).
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                androidx.compose.ui.graphics.Color(0xFF2E8B6B),
                androidx.compose.ui.graphics.Color(0xFF1C6B4C),
                androidx.compose.ui.graphics.Color(0xFF155539))))
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)).background(cW.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initial, color = cW, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(company.name, color = cW, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(company.industry.label(ctxLabel) + " · " + mainCity, color = cW.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val hasContact = company.phones.isNotEmpty() || company.emails.isNotEmpty() || company.website != null
            if (hasContact) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cW.copy(alpha = 0.12f))) {
                    company.phones.forEach { ph ->
                        CompanyInfoRow(Icons.Default.Phone, ph.number, ph.type.label(ctxLabel)) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(ctxLabel, ph.number)
                        }
                    }
                    company.emails.forEach { em ->
                        CompanyInfoRow(Icons.Default.Email, em.email, em.type.label(ctxLabel)) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(ctxLabel, em.email)
                        }
                    }
                    company.website?.let { ws ->
                        CompanyInfoRow(Icons.Default.Language, ws, stringResource(R.string.comp_website)) {
                            com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openWebsite(ctxLabel, ws)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanyInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, sub: String, onClick: () -> Unit) {
    val w = androidx.compose.ui.graphics.Color.White
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Icon(icon, contentDescription = null, tint = w.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(value, color = w, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = w.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = w.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.companyOverviewTab(company: Company, onNavigateToCreateCalendarItem: () -> Unit, ctxLabel: android.content.Context) {
    
    val addresses = AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
    if (addresses.isNotEmpty()) {
        item {
            CardBlock(title = stringResource(R.string.cd_addresses)) {
                addresses.forEachIndexed { index, address ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Column(modifier = Modifier.weight(1f)) {
                            Text(address.addressType.label(ctxLabel), color = AppleTheme.colors.brand, style = MaterialTheme.typography.bodySmall)
                            Text("${address.addressLine}, ${address.city}${address.postalCode?.let { " $it" } ?: ""}, ${address.country}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { 
                            if (address.latitude != null && address.longitude != null) {
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                            } else {
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
                            }
                        }, modifier = Modifier.size(36.dp).background(AppleTheme.colors.card, CircleShape)) {
                            Icon(Icons.Default.Directions, contentDescription = stringResource(R.string.map_route), modifier = Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                        }
                    }
                    if (index < addresses.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = AppleTheme.colors.card)
                    }
                }
            }
        }
    }
    
    company.description?.let { desc ->
        item {
            CardBlock(title = stringResource(R.string.compd_description)) {
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    
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

fun androidx.compose.foundation.lazy.LazyListScope.companyPeopleTab(company: Company, onNavigateToContact: (String) -> Unit, ctxLabel: android.content.Context) {
    val relations = AppStateStore.companyRelations.filter { it.companyId == company.id }

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
        items(relations, key = { it.id }) { rel ->
            val contact = AppStateStore.getContact(rel.contactId)
            if (contact != null) {
                Card(
                    onClick = { onNavigateToContact(contact.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(AppleTheme.colors.brand),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = contact.firstName.take(1) + contact.lastName.take(1),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "${contact.firstName} ${contact.lastName}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = rel.employmentStatus.label(ctxLabel), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
                                }
                                
                                val posRoles = listOfNotNull(rel.position, rel.department, rel.role).filter { it.isNotBlank() }
                                if (posRoles.isNotEmpty()) {
                                    Text(text = posRoles.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        
                        val workContext = listOfNotNull(
                            if (!rel.responsibilities.isNullOrBlank()) stringResource(R.string.compd_zone, rel.responsibilities) else null,
                            if (!rel.managedAccounts.isNullOrBlank()) stringResource(R.string.compd_accounts, rel.managedAccounts) else null,
                            if (!rel.workNote.isNullOrBlank()) stringResource(R.string.compd_worknote, rel.workNote) else null
                        )
                        
                        if (workContext.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            workContext.forEach { ctx ->
                                Text("• $ctx", style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text(contact.relationshipType.label(ctxLabel), fontSize = 10.sp) })
                            AssistChip(onClick = {}, label = { Text(contact.importanceLevel.label(ctxLabel), fontSize = 10.sp) })
                            if (contact.socialRole != SocialRole.REGULAR) {
                                AssistChip(onClick = {}, label = { Text(contact.socialRole.label(ctxLabel), fontSize = 10.sp) })
                            }
                        }
                    }
                }
            }
        }
    }
}
