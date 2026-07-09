package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.model.Address
import com.aistudio.socialsphere.crmlxb.model.AddressOwnerType
import com.aistudio.socialsphere.crmlxb.model.AddressType
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.utils.label
import kotlinx.coroutines.launch

/**
 * ЕДИНЫЙ диалог адреса (форма контакта, вкладка Связь, далее — везде).
 * Фидбэк владельца 2026-07-03: на Связи нельзя было сменить тип на «рабочий» —
 * там жила урезанная копия этого диалога. Все [AddressType] доступны всегда:
 * смена «домашний → рабочий» переносит адрес между вкладками/фильтрами карты.
 *
 * [onCommit] вызывается сразу при сохранении; [onGeocoded] — позже, когда
 * геокодер нашёл координаты (тем же id, с latitude/longitude). Если у [base]
 * координаты уже были, а адрес не менялся текстом, координаты сохраняются.
 */
@Composable
fun AddressEditDialog(
    base: Address?,
    ownerId: String,
    // Скоуп ЭКРАНА (не диалога!): onDismiss убирает диалог из композиции и его
    // собственный rememberCoroutineScope отменился бы до завершения геокода.
    scope: kotlinx.coroutines.CoroutineScope,
    ownerType: AddressOwnerType = AddressOwnerType.CONTACT,
    // Тип НОВОГО адреса по умолчанию (игнорируется, если base != null — тогда
    // берётся существующий тип). Вызывающая вкладка/фильтр может отличаться
    // (Работа показывает только рабочие типы) — если завести адрес с вкладки
    // Работа с дефолтом HOME, он молча пропадёт из её же списка сразу после
    // сохранения (фикс 2026-07-06, регрессия при переводе WorkTab на этот диалог).
    defaultType: AddressType = AddressType.HOME,
    onDismiss: () -> Unit,
    onCommit: (Address) -> Unit,
    onGeocoded: ((Address) -> Unit)? = null,
) {
    val ctxLabel = LocalContext.current
    var aLine    by remember(base?.id) { mutableStateOf(base?.addressLine ?: "") }
    var aCity    by remember(base?.id) { mutableStateOf(base?.city ?: "") }
    var aCountry by remember(base?.id) { mutableStateOf(base?.country ?: "") }
    var aPostal  by remember(base?.id) { mutableStateOf(base?.postalCode ?: "") }
    var aType    by remember(base?.id) { mutableStateOf(base?.addressType ?: defaultType) }
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
        title = stringResource(if (base == null) R.string.ce_new_address else R.string.ce_edit_address),
        onDismiss = onDismiss,
        confirmText = stringResource(if (base == null) R.string.common_add else R.string.common_save),
        confirmEnabled = aLine.isNotBlank(),
        onConfirm = {
            val addressChanged = base == null ||
                base.addressLine != aLine.trim() || base.city != aCity.trim() ||
                base.country != aCountry.trim()
            val newAddr = Address(
                id          = base?.id ?: java.util.UUID.randomUUID().toString(),
                ownerType   = ownerType,
                ownerId     = ownerId,
                addressType = aType,
                addressLine = aLine.trim(),
                city        = aCity.trim(),
                country     = aCountry.trim(),
                postalCode  = aPostal.trim().ifBlank { null },
                latitude    = if (addressChanged) null else base?.latitude,
                longitude   = if (addressChanged) null else base?.longitude
            )
            onCommit(newAddr)
            onDismiss()
            // Геокодим один раз при сохранении — карта не зависит от
            // повторного геокодинга при каждом открытии.
            if (addressChanged && onGeocoded != null) scope.launch {
                val ll = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (!android.location.Geocoder.isPresent()) return@withContext null
                    try {
                        val q = listOf(newAddr.addressLine, newAddr.city, newAddr.country)
                            .filter { it.isNotBlank() }.joinToString(", ")
                        if (q.isBlank()) return@withContext null
                        @Suppress("DEPRECATION")
                        android.location.Geocoder(ctxLabel, java.util.Locale.getDefault())
                            .getFromLocationName(q, 1)?.firstOrNull()
                            ?.let { it.latitude to it.longitude }
                    } catch (e: Exception) { null }
                }
                if (ll != null) onGeocoded(newAddr.copy(latitude = ll.first, longitude = ll.second))
            }
        },
        secondaryText = stringResource(R.string.common_cancel),
        onSecondary = onDismiss
    ) {
        OutlinedTextField(value = aLine, onValueChange = { aLine = it }, keyboardOptions = CapWords,
            label = { Text(stringResource(R.string.ce_street_req)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = aCity, onValueChange = { aCity = it }, keyboardOptions = CapWords,
                label = { Text(stringResource(R.string.ce_city)) },
                modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small)
            OutlinedTextField(value = aPostal, onValueChange = { aPostal = it },
                label = { Text(stringResource(R.string.ce_postal_code)) },
                modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small)
        }
        OutlinedTextField(value = aCountry, onValueChange = { aCountry = it }, keyboardOptions = CapWords,
            label = { Text(stringResource(R.string.ce_country)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
        DropdownField(stringResource(R.string.ce_address_type), aType.label(ctxLabel),
            AddressType.values().map { it.label(ctxLabel) }) { picked ->
            aType = AddressType.values().firstOrNull { it.label(ctxLabel) == picked } ?: aType
        }
    }
}
