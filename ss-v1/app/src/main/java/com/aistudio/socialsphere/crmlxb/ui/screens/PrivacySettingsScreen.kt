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
                title = { Text("Приватность", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            CardBlock("Разрешения Android") {
                ActionRow(
                    icon = Icons.Default.Security,
                    text = "Управление разрешениями",
                    subtitle = "Контакты, уведомления — в системных настройках",
                    onClick = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        )
                        ExternalActionHandler.startIntentSafely(context, intent)
                    }
                )
            }

            CardBlock("Локальное хранение") {
                Text(
                    "Все данные хранятся только на этом устройстве и никуда не " +
                        "передаются. Файлы экспорта не шифруются — храни их в " +
                        "надёжном месте.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            CardBlock("Опасная зона") {
                ActionRow(
                    icon = Icons.Default.DeleteForever,
                    text = "Удалить все данные",
                    subtitle = "Контакты, компании, события, заметки — безвозвратно",
                    onClick = { showWipeConfirm = true }
                )
                if (wipeDone) {
                    Text(
                        "Данные удалены. Перезапусти приложение.",
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
            title = { Text("Удалить все данные?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Будут безвозвратно удалены все контакты, компании, " +
                        "события, заметки и подарки. Перед удалением можно " +
                        "сделать экспорт в Настройках → Импорт и экспорт."
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
                ) { Text("Удалить всё") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text("Отмена") }
            }
        )
    }
}
