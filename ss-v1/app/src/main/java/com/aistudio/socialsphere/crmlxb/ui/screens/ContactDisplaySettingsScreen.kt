package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.ContactNameFormat
import com.aistudio.socialsphere.crmlxb.utils.ContactSortField

/**
 * ContactDisplayPreferences (2026-07-11) — как в Android-контактах: «сортировать
 * по» и «формат отображения» это ДВЕ независимые настройки (ContactsContract.
 * Preferences.SORT_ORDER vs DISPLAY_ORDER), не один флаг «зеркалить оба сразу».
 * Можно сортировать список по фамилии, но по-прежнему показывать «Имя Фамилия».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDisplaySettingsScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(
                    text = stringResource(R.string.settings_contact_display),
                    fontSize = 28.sp
                )
            }

            CardBlock(stringResource(R.string.cds_sort_by)) {
                val sortField = AppSettings.contactSortField.value
                val sortFirstLabel = stringResource(R.string.cds_sort_first_name)
                val sortLastLabel  = stringResource(R.string.cds_sort_last_name)
                FilterChipsRow(
                    options  = listOf(sortFirstLabel, sortLastLabel),
                    selected = when (sortField) {
                        ContactSortField.FIRST_NAME -> sortFirstLabel
                        ContactSortField.LAST_NAME  -> sortLastLabel
                    },
                    onSelect = { label ->
                        AppSettings.contactSortField.value =
                            if (label == sortLastLabel) ContactSortField.LAST_NAME else ContactSortField.FIRST_NAME
                    }
                )
            }

            CardBlock(stringResource(R.string.cds_name_format)) {
                val nameFormat = AppSettings.contactNameFormat.value
                val formatFirstLabel = stringResource(R.string.cds_name_format_first)
                val formatLastLabel  = stringResource(R.string.cds_name_format_last)
                FilterChipsRow(
                    options  = listOf(formatFirstLabel, formatLastLabel),
                    selected = when (nameFormat) {
                        ContactNameFormat.FIRST_NAME_FIRST -> formatFirstLabel
                        ContactNameFormat.LAST_NAME_FIRST  -> formatLastLabel
                    },
                    onSelect = { label ->
                        AppSettings.contactNameFormat.value =
                            if (label == formatLastLabel) ContactNameFormat.LAST_NAME_FIRST else ContactNameFormat.FIRST_NAME_FIRST
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.cds_name_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.secondaryLabel
                )
            }
        }
    }
}
