package com.aistudio.socialsphere.crmlxb.ui.screens
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var showWipeConfirm by remember { mutableStateOf(false) }
    var wipeDone        by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.priv_title), fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            CardBlock(stringResource(R.string.priv_android_perms)) {
                ActionRow(
                    icon = Icons.Default.Security,
                    text = stringResource(R.string.priv_manage_perms),
                    subtitle = stringResource(R.string.priv_perms_sub),
                    onClick = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        )
                        ExternalActionHandler.startIntentSafely(context, intent)
                    }
                )
            }

            CardBlock(stringResource(R.string.priv_local_storage)) {
                Text(
                    stringResource(R.string.priv_local_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTheme.colors.secondaryLabel
                )
            }

            CardBlock(stringResource(R.string.priv_danger_zone)) {
                ActionRow(
                    icon = Icons.Default.DeleteForever,
                    text = stringResource(R.string.priv_delete_all),
                    subtitle = stringResource(R.string.priv_delete_all_sub),
                    onClick = { showWipeConfirm = true }
                )
                if (wipeDone) {
                    Text(
                        stringResource(R.string.priv_deleted),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.brand,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text(stringResource(R.string.priv_delete_q), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.priv_delete_warning)
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppleTheme.colors.red
                    ),
                    onClick = {
                        showWipeConfirm = false
                        // wipeDone = ok: при ошибке БД сообщение «удалено» НЕ
                        // показываем, чтобы не давать ложного подтверждения.
                        AppStateStore.wipeAllData { ok -> wipeDone = ok }
                    }
                ) { Text(stringResource(R.string.priv_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
