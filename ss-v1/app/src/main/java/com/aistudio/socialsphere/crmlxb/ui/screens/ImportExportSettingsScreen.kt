package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToImportContacts: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Loading state per export type
    var loadingCsv     by remember { mutableStateOf(false) }
    var loadingCompCsv by remember { mutableStateOf(false) }
    var loadingVcf     by remember { mutableStateOf(false) }
    var loadingJson    by remember { mutableStateOf(false) }
    var loadingZip     by remember { mutableStateOf(false) }

    fun runExport(
        setLoading: (Boolean) -> Unit,
        block: suspend () -> Unit
    ) {
        setLoading(true)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Ошибка: ${e.localizedMessage ?: "неизвестная"}")
            } finally {
                setLoading(false)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Импорт и экспорт", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Импорт ────────────────────────────────────────
            ExportSectionCard("Импорт контактов") {
                ActionRow(
                    icon    = Icons.Default.Contacts,
                    text    = "Из телефонной книги Android",
                    onClick = onNavigateToImportContacts
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    icon    = Icons.Default.CloudDownload,
                    text    = "Из файла vCard / CSV",
                    enabled = false,
                    subtitle = "Будет добавлено в следующей версии"
                )
            }

            // ── Экспорт ───────────────────────────────────────
            ExportSectionCard("Экспорт данных") {
                ExportRow(
                    icon      = Icons.Default.TableChart,
                    text      = "Контакты → CSV",
                    subtitle  = "Excel / Google Sheets",
                    loading   = loadingCsv,
                    onClick   = {
                        runExport({ loadingCsv = it }) {
                            val file = ExportManager.exportContactsCsv(context)
                            ExportManager.shareFile(context, file, "text/csv")
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ExportRow(
                    icon      = Icons.Default.Business,
                    text      = "Компании → CSV",
                    subtitle  = "Excel / Google Sheets",
                    loading   = loadingCompCsv,
                    onClick   = {
                        runExport({ loadingCompCsv = it }) {
                            val file = ExportManager.exportCompaniesCsv(context)
                            ExportManager.shareFile(context, file, "text/csv")
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ExportRow(
                    icon      = Icons.Default.ContactPhone,
                    text      = "Контакты → vCard (.vcf)",
                    subtitle  = "Для импорта в телефон / Gmail",
                    loading   = loadingVcf,
                    onClick   = {
                        runExport({ loadingVcf = it }) {
                            val file = ExportManager.exportVCard(context)
                            ExportManager.shareFile(context, file, "text/vcard")
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ExportRow(
                    icon      = Icons.Default.Code,
                    text      = "Полный бэкап → JSON",
                    subtitle  = "Для восстановления в Socialsphere",
                    loading   = loadingJson,
                    onClick   = {
                        runExport({ loadingJson = it }) {
                            val file = ExportManager.exportJsonBackup(context)
                            ExportManager.shareFile(context, file, "application/json")
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ExportRow(
                    icon      = Icons.Default.FolderOpen,
                    text      = "Полный архив → ZIP",
                    subtitle  = "CSV + vCard + JSON в одном файле",
                    loading   = loadingZip,
                    onClick   = {
                        runExport({ loadingZip = it }) {
                            val file = ExportManager.exportFullZip(context)
                            ExportManager.shareFile(context, file, "application/zip")
                        }
                    }
                )
            }

            // ── Статистика ────────────────────────────────────
            ExportSectionCard("Текущая база") {
                val contacts  = com.aistudio.socialsphere.crmlxb.data.AppStateStore.contacts.size
                val companies = com.aistudio.socialsphere.crmlxb.data.AppStateStore.companies.size
                val events    = com.aistudio.socialsphere.crmlxb.data.AppStateStore.calendarItems.size
                val notes     = com.aistudio.socialsphere.crmlxb.data.AppStateStore.notes.size

                listOf(
                    "Контактов"  to contacts,
                    "Компаний"   to companies,
                    "Событий"    to events,
                    "Заметок"    to notes
                ).forEach { (label, count) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        Arrangement.SpaceBetween
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary)
                        Text(count.toString(), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ExportSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ExportRow(
    icon: ImageVector,
    text: String,
    subtitle: String = "",
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty())
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
fun ActionRow(
    icon: ImageVector,
    text: String,
    subtitle: String = "",
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant)
            if (subtitle.isNotEmpty())
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
        }
        Icon(Icons.Default.ChevronRight, null,
            tint = MaterialTheme.colorScheme.secondary)
    }
}
