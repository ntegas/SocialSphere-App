package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
<<<<<<< HEAD
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
=======
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
<<<<<<< HEAD
            CardBlock(stringResource(R.string.priv_android_perms)) {
                ActionRow(
                    icon = Icons.Default.Security,
                    text = stringResource(R.string.priv_manage_perms),
                    subtitle = stringResource(R.string.priv_perms_sub),
=======
            CardBlock("Разрешения Android") {
                ActionRow(
                    icon = Icons.Default.Security,
                    text = "Управление разрешениями",
                    subtitle = "Контакты, уведомления — в системных настройках",
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                    onClick = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        )
                        ExternalActionHandler.startIntentSafely(context, intent)
                    }
                )
            }

<<<<<<< HEAD
            CardBlock(stringResource(R.string.priv_local_storage)) {
                Text(
                    stringResource(R.string.priv_local_desc),
=======
            CardBlock("Локальное хранение") {
                Text(
                    "Все данные хранятся только на этом устройстве и никуда не " +
                        "передаются. Файлы экспорта не шифруются — храни их в " +
                        "надёжном месте.",
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

<<<<<<< HEAD
            CardBlock(stringResource(R.string.priv_danger_zone)) {
                ActionRow(
                    icon = Icons.Default.DeleteForever,
                    text = stringResource(R.string.priv_delete_all),
                    subtitle = stringResource(R.string.priv_delete_all_sub),
=======
            CardBlock("Опасная зона") {
                ActionRow(
                    icon = Icons.Default.DeleteForever,
                    text = "Удалить все данные",
                    subtitle = "Контакты, компании, события, заметки — безвозвратно",
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                    onClick = { showWipeConfirm = true }
                )
                if (wipeDone) {
                    Text(
<<<<<<< HEAD
                        stringResource(R.string.priv_deleted),
=======
                        "Данные удалены. Перезапусти приложение.",
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
<<<<<<< HEAD
            title = { Text(stringResource(R.string.priv_delete_q), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.priv_delete_warning)
=======
            title = { Text("Удалить все данные?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Будут безвозвратно удалены все контакты, компании, " +
                        "события, заметки и подарки. Перед удалением можно " +
                        "сделать экспорт в Настройках → Импорт и экспорт."
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        showWipeConfirm = false
                        AppStateStore.wipeAllData { wipeDone = true }
                    }
<<<<<<< HEAD
                ) { Text(stringResource(R.string.priv_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
=======
                ) { Text("Удалить всё") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text("Отмена") }
>>>>>>> d252f445053b776536aff0d571b80c4608c8a4ee
            }
        )
    }
}
