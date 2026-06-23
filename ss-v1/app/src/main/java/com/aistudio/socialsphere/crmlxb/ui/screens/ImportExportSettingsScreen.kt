package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import com.aistudio.socialsphere.crmlxb.utils.CalendarExporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.aistudio.socialsphere.crmlxb.R

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
    var loadingToPhone by remember { mutableStateOf(false) }
    var loadingJson    by remember { mutableStateOf(false) }
    var loadingZip     by remember { mutableStateOf(false) }
    var loadingRestore by remember { mutableStateOf(false) }
    var loadingSaveJson by remember { mutableStateOf(false) }
    var loadingCal     by remember { mutableStateOf(false) }

    // Восстановление из полного JSON-бэкапа: upsert по id, ничего не удаляет.
    // Прямое сохранение JSON-бэкапа в файл: открывается выбор папки и имени,
    // без share-листа.
    val saveJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadingSaveJson = true
        scope.launch {
            try {
                val json = ExportManager.backupJsonString()
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    }
                }
                snackbarHostState.showSnackbar(context.getString(R.string.ie_save_done))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.ie_error, e.localizedMessage ?: ""))
            } finally {
                loadingSaveJson = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadingRestore = true
        scope.launch {
            try {
                val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText() ?: ""
                }
                val n = ExportManager.importJsonBackup(json)
                if (n < 0) snackbarHostState.showSnackbar(context.getString(R.string.ie_restore_bad_file))
                else snackbarHostState.showSnackbar(context.getString(R.string.ie_restore_done, n))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.ie_error, e.localizedMessage ?: ""))
            } finally {
                loadingRestore = false
            }
        }
    }

    fun runExport(
        setLoading: (Boolean) -> Unit,
        block: suspend () -> Unit
    ) {
        setLoading(true)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.ie_error, e.localizedMessage ?: ""))
            } finally {
                setLoading(false)
            }
        }
    }

    // Экспорт событий в системный календарь (после выдачи разрешений)
    fun doCalendarExport() {
        runExport({ loadingCal = it }) {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) { CalendarExporter.exportAll(context) }
            if (result.noCalendar)
                snackbarHostState.showSnackbar(context.getString(R.string.ie_calendar_no_cal))
            else
                snackbarHostState.showSnackbar(context.getString(R.string.ie_calendar_done, result.inserted, result.skipped))
        }
    }

    val calendarPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.WRITE_CALENDAR] == true) doCalendarExport()
        else scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ie_calendar_no_perm)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ie_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
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
            ExportSectionCard(stringResource(R.string.ie_import_contacts)) {
                ActionRow(
                    icon    = Icons.Default.Contacts,
                    text    = stringResource(R.string.ie_from_phonebook),
                    onClick = onNavigateToImportContacts
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    icon    = Icons.Default.CloudDownload,
                    text    = stringResource(R.string.ie_from_file),
                    enabled = false,
                    subtitle = stringResource(R.string.ie_coming_next)
                )
            }

            // ── Экспорт ───────────────────────────────────────
            ExportSectionCard(stringResource(R.string.ie_export_data)) {
                ExportRow(
                    icon      = Icons.Default.TableChart,
                    text      = stringResource(R.string.ie_contacts_csv),
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
                    text      = stringResource(R.string.ie_companies_csv),
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
                    text      = stringResource(R.string.ie_contacts_vcard),
                    subtitle  = stringResource(R.string.ie_for_phone_gmail),
                    loading   = loadingVcf,
                    onClick   = {
                        runExport({ loadingVcf = it }) {
                            val file = ExportManager.exportVCard(context)
                            ExportManager.shareFile(context, file, "text/vcard")
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                // Прямой импорт всех контактов в системные «Контакты» телефона
                ExportRow(
                    icon      = Icons.Default.ContactPhone,
                    text      = stringResource(R.string.ie_to_phone_contacts),
                    subtitle  = stringResource(R.string.ie_to_phone_sub),
                    loading   = loadingToPhone,
                    onClick   = {
                        runExport({ loadingToPhone = it }) {
                            val file = ExportManager.exportVCard(context)
                            val ok = ExportManager.openVcfInContacts(context, file)
                            if (!ok) ExportManager.shareFile(context, file, "text/vcard")
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                // Экспорт событий (ДР/звонки/встречи) в системный календарь
                ExportRow(
                    icon      = Icons.Default.EventAvailable,
                    text      = stringResource(R.string.ie_to_calendar),
                    subtitle  = stringResource(R.string.ie_to_calendar_sub),
                    loading   = loadingCal,
                    onClick   = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_CALENDAR
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) doCalendarExport()
                        else calendarPermLauncher.launch(
                            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                        )
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ExportRow(
                    icon      = Icons.Default.Code,
                    text      = stringResource(R.string.ie_full_json),
                    subtitle  = stringResource(R.string.ie_for_restore),
                    loading   = loadingJson,
                    onClick   = {
                        runExport({ loadingJson = it }) {
                            val file = ExportManager.exportJsonBackup(context)
                            ExportManager.shareFile(context, file, "application/json")
                        }
                    }
                )
                ExportRow(
                    icon      = Icons.Default.SaveAlt,
                    text      = stringResource(R.string.ie_save_json),
                    subtitle  = stringResource(R.string.ie_save_sub),
                    loading   = loadingSaveJson,
                    onClick   = { saveJsonLauncher.launch("socialsphere_backup.json") }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ExportRow(
                    icon      = Icons.Default.Restore,
                    text      = stringResource(R.string.ie_restore_json),
                    subtitle  = stringResource(R.string.ie_restore_sub),
                    loading   = loadingRestore,
                    onClick   = { restoreLauncher.launch("*/*") }
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ExportRow(
                    icon      = Icons.Default.FolderOpen,
                    text      = stringResource(R.string.ie_full_zip),
                    subtitle  = stringResource(R.string.ie_csv_vcard_json),
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
            ExportSectionCard(stringResource(R.string.ie_current_db)) {
                val contacts  = com.aistudio.socialsphere.crmlxb.data.AppStateStore.contacts.size
                val companies = com.aistudio.socialsphere.crmlxb.data.AppStateStore.companies.size
                val events    = com.aistudio.socialsphere.crmlxb.data.AppStateStore.calendarItems.size
                val notes     = com.aistudio.socialsphere.crmlxb.data.AppStateStore.notes.size

                listOf(
                    stringResource(R.string.ie_contacts_n)  to contacts,
                    stringResource(R.string.ie_companies_n)   to companies,
                    stringResource(R.string.ie_events_n)    to events,
                    stringResource(R.string.ie_notes_n)    to notes
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
