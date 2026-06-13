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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.compd_delete_q), fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.compd_delete_warning, company.name)) },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; AppStateStore.deleteCompany(companyId); onNavigateBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(company.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
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
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            CompanyHeader(company, onShowPeople = { selectedTab = 1 })

            Spacer(modifier = Modifier.height(16.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 14.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTab) {
                    0 -> companyOverviewTab(company, onNavigateToCreateCalendarItem)
                    1 -> companyPeopleTab(company, onNavigateToContact)
                }
            }
        }
    }
}

@Composable
fun CompanyHeader(company: Company, onShowPeople: () -> Unit = {}) {
    val addresses = AppStateStore.addresses.filter { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
    val mainCity = addresses.firstOrNull { it.addressType == AddressType.OFFICE || it.addressType == AddressType.LEGAL }?.city
        ?: addresses.firstOrNull()?.city ?: stringResource(R.string.common_unknown)

    val peopleCount = AppStateStore.companyRelations.count { it.companyId == company.id }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Business, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = company.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // FIX: use .label(ctxLabel) instead of .name
            Text(text = company.industry.label(ctxLabel), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text("•", color = MaterialTheme.colorScheme.secondary)
            Text(text = mainCity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        // FIX: chip now navigates to People tab
        AssistChip(
            onClick = onShowPeople,
            label = { Text(stringResource(R.string.compd_people_count, peopleCount), fontSize = 12.sp) },
            leadingIcon = {
                Icon(Icons.Default.People, null, Modifier.size(16.dp))
            }
        )
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.companyOverviewTab(company: Company, onNavigateToCreateCalendarItem: () -> Unit) {
    item {
        val context = androidx.compose.ui.platform.LocalContext.current
        CardBlock(title = stringResource(R.string.compd_company_contacts)) {
            var hasContacts = false
            company.phones.forEach { ph ->
                hasContacts = true
                InfoRow(ph.type.label(ctxLabel), ph.number, onClick = {
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openDialer(context, ph.number)
                })
            }
            company.emails.forEach { em ->
                hasContacts = true
                InfoRow(em.type.label(ctxLabel), em.email, onClick = {
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openEmail(context, em.email)
                })
            }
            company.website?.let { ws ->
                hasContacts = true
                InfoRow(stringResource(R.string.comp_website), ws, onClick = {
                    com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openWebsite(context, ws)
                })
            }
            if (!hasContacts) {
                Text(stringResource(R.string.compd_no_contacts), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    
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
                            Text(address.addressType.label(ctxLabel), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            Text("${address.addressLine}, ${address.city}, ${address.country}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { 
                            if (address.latitude != null && address.longitude != null) {
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRouteByCoordinates(context, address.latitude, address.longitude)
                            } else {
                                com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler.openRoute(context, "${address.addressLine}, ${address.city}, ${address.country}")
                            }
                        }, modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                            Icon(Icons.Default.Directions, contentDescription = stringResource(R.string.map_route), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (index < addresses.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
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
                Text(stringResource(R.string.cd_no_events), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNavigateToCreateCalendarItem,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
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
                Text(stringResource(R.string.cd_no_notes_yet), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.companyPeopleTab(company: Company, onNavigateToContact: (String) -> Unit) {
    val relations = AppStateStore.companyRelations.filter { it.companyId == company.id }
    
    if (relations.isEmpty()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.compd_no_people), color = MaterialTheme.colorScheme.secondary)
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = contact.firstName.take(1) + contact.lastName.take(1),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "${contact.firstName} ${contact.lastName}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = rel.employmentStatus.label(ctxLabel), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                                
                                val posRoles = listOfNotNull(rel.position, rel.department, rel.role).filter { it.isNotBlank() }
                                if (posRoles.isNotEmpty()) {
                                    Text(text = posRoles.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                                Text("• $ctx", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
