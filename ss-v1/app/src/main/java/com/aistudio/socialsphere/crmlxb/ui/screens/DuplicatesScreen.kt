package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.Contact
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

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
fun DuplicatesScreen(onNavigateBack: () -> Unit) {
    val pairs by remember { derivedStateOf { AppStateStore.findDuplicatePairs() } }
    // Слияние — деструктив (удаляет один контакт), поэтому через подтверждение.
    var pendingMerge by remember { mutableStateOf<Pair<Contact, Contact>?>(null) }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dup_title), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppleTheme.colors.groupedBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.dup_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            if (pairs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.dup_none), color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                pairs.forEach { (a, b) ->
                    val keep = if (contactScore(a) >= contactScore(b)) a else b
                    val drop = if (keep.id == a.id) b else a
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DuplicateContactLine(a)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                            DuplicateContactLine(b)
                            Text(
                                stringResource(R.string.dup_will_keep, "${keep.firstName} ${keep.lastName}".trim()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(
                                onClick = { pendingMerge = keep to drop },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Merge, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.dup_merge))
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
                    Button(onClick = {
                        AppStateStore.mergeContacts(keep.id, drop.id)
                        pendingMerge = null
                    }) { Text(stringResource(R.string.dup_merge)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingMerge = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }
    }
}

@Composable
private fun DuplicateContactLine(c: Contact) {
    Column {
        Text("${c.firstName} ${c.lastName}".trim(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        val sub = contactSubtitle(c)
        if (sub.isNotEmpty())
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
