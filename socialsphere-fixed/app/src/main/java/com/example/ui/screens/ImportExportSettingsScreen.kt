package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToImportContacts: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Импорт и экспорт", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
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
            CardBlock("Импорт контактов") {
                ActionRow(icon = Icons.Default.Contacts, text = "Из телефонной книги", onClick = onNavigateToImportContacts)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ActionRow(icon = Icons.Default.CloudDownload, text = "Из файла vCard / CSV")
            }

            CardBlock("Управление данными") {
                ActionRow(icon = Icons.Default.Rule, text = "Проверка дубликатов")
            }

            CardBlock("Экспорт данных") {
                ActionRow(icon = Icons.Default.CloudUpload, text = "Экспорт контактов (CSV)")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ActionRow(icon = Icons.Default.CloudUpload, text = "Экспорт компаний (CSV)")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ActionRow(icon = Icons.Default.CloudUpload, text = "Экспорт календаря (ICS)")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ActionRow(icon = Icons.Default.CloudUpload, text = "Полный экспорт базы (ZIP)")
            }

            CardBlock("Резервные копии") {
                ActionRow(icon = Icons.Default.Storage, text = "Создать резервную копию")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ActionRow(icon = Icons.Default.Storage, text = "Восстановить из копии")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
    }
}
