package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
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
    var aDistrict by remember(base?.id) { mutableStateOf(base?.district ?: "") }
    var aCity    by remember(base?.id) { mutableStateOf(base?.city ?: "") }
    var aCountry by remember(base?.id) { mutableStateOf(base?.country ?: "") }
    var aPostal  by remember(base?.id) { mutableStateOf(base?.postalCode ?: "") }
    var aType    by remember(base?.id) { mutableStateOf(base?.addressType ?: defaultType) }
    // ФИКС (2026-07-12, фидбэк владельца: «координаты/карта скрыта, должна быть
    // видна и легко добавляема»). Раньше геокодер искал координаты МОЛЧА в фоне
    // при сохранении, без полей и без индикации успеха/неудачи — владелец не мог
    // ни увидеть, ни исправить результат. Теперь показываем статус поиска и
    // даём необязательные редактируемые поля (предзаполняются геокодером).
    var aLat     by remember(base?.id) { mutableStateOf(base?.latitude?.toString() ?: "") }
    var aLng     by remember(base?.id) { mutableStateOf(base?.longitude?.toString() ?: "") }
    // ФИКС (2026-07-22): предзаполнение aLat/aLng из base?.latitude/longitude —
    // это НЕ решение пользователя ввести координаты вручную. Раньше manualLat
    // выводился из aLat.trim().toDoubleOrNull(), который был не-null для ЛЮБОГО
    // адреса с уже существующими координатами (не только для тех, что реально
    // отредактированы в этом сеансе) — из-за этого старые координаты молча
    // переживали правку улицы/города/страны, а авто-пере-геокодинг (условие
    // manualLat == null && addressChanged) никогда не срабатывал для таких
    // адресов. Теперь «ручной ввод» — отдельный dirty-флаг, взводится только
    // онValueChange полей и успешным нажатием «Найти» (тоже осознанное решение
    // пользователя в этом сеансе), а не любым непустым/предзаполненным текстом.
    var latLngManuallyEdited by remember(base?.id) { mutableStateOf(false) }
    var geocodeStatus by remember(base?.id) { mutableStateOf<String?>(null) }
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
        title = stringResource(if (base == null) R.string.ce_new_address else R.string.ce_edit_address),
        onDismiss = onDismiss,
        confirmText = stringResource(if (base == null) R.string.common_add else R.string.common_save),
        confirmEnabled = aLine.isNotBlank(),
        onConfirm = {
            val addressChanged = base == null ||
                base.addressLine != aLine.trim() || base.city != aCity.trim() ||
                base.country != aCountry.trim()
            // Ручной ввод координат — в приоритете над автогеокодингом. Важно:
            // считаем «ручным» только то, что реально отредактировано/найдено
            // в этом сеансе (latLngManuallyEdited), а не любое непустое значение
            // поля — иначе предзаполнение из base не даёт пере-геокодингу
            // сработать после правки адреса (см. фикс 2026-07-22 выше).
            val manualLat = if (latLngManuallyEdited) aLat.trim().toDoubleOrNull() else null
            val manualLng = if (latLngManuallyEdited) aLng.trim().toDoubleOrNull() else null
            val newAddr = Address(
                id          = base?.id ?: java.util.UUID.randomUUID().toString(),
                ownerType   = ownerType,
                ownerId     = ownerId,
                addressType = aType,
                addressLine = aLine.trim(),
                district    = aDistrict.trim().ifBlank { null },
                city        = aCity.trim(),
                country     = aCountry.trim(),
                postalCode  = aPostal.trim().ifBlank { null },
                latitude    = manualLat ?: (if (addressChanged) null else base?.latitude),
                longitude   = manualLng ?: (if (addressChanged) null else base?.longitude)
            )
            onCommit(newAddr)
            onDismiss()
            // Геокодим один раз при сохранении, только если координаты не введены
            // вручную — карта не зависит от повторного геокодинга при каждом открытии.
            if (manualLat == null && addressChanged && onGeocoded != null) scope.launch {
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
        OutlinedTextField(value = aLine, onValueChange = { aLine = it }, keyboardOptions = CapWordsNoCorrect,
            label = { Text(stringResource(R.string.ce_street_req)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
        OutlinedTextField(value = aDistrict, onValueChange = { aDistrict = it }, keyboardOptions = CapWordsNoCorrect,
            label = { Text(stringResource(R.string.ce_district)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = aCity, onValueChange = { aCity = it }, keyboardOptions = CapWordsNoCorrect,
                label = { Text(stringResource(R.string.ce_city)) },
                modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small)
            OutlinedTextField(value = aPostal, onValueChange = { aPostal = it },
                label = { Text(stringResource(R.string.ce_postal_code)) },
                modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small)
        }
        OutlinedTextField(value = aCountry, onValueChange = { aCountry = it }, keyboardOptions = CapWordsNoCorrect,
            label = { Text(stringResource(R.string.ce_country)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
        DropdownField(stringResource(R.string.ce_address_type), aType.label(ctxLabel),
            AddressType.values().map { it.label(ctxLabel) }) { picked ->
            aType = AddressType.values().firstOrNull { it.label(ctxLabel) == picked } ?: aType
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(value = aLat, onValueChange = { aLat = it; latLngManuallyEdited = true },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                label = { Text(stringResource(R.string.ce_latitude)) },
                modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small)
            OutlinedTextField(value = aLng, onValueChange = { aLng = it; latLngManuallyEdited = true },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                label = { Text(stringResource(R.string.ce_longitude)) },
                modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small)
            androidx.compose.material3.TextButton(onClick = {
                if (aLine.isBlank() && aCity.isBlank()) return@TextButton
                geocodeStatus = ctxLabel.getString(R.string.ce_geocode_searching)
                scope.launch {
                    val ll = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (!android.location.Geocoder.isPresent()) return@withContext null
                        try {
                            val q = listOf(aLine, aCity, aCountry).filter { it.isNotBlank() }.joinToString(", ")
                            if (q.isBlank()) return@withContext null
                            @Suppress("DEPRECATION")
                            android.location.Geocoder(ctxLabel, java.util.Locale.getDefault())
                                .getFromLocationName(q, 1)?.firstOrNull()
                                ?.let { it.latitude to it.longitude }
                        } catch (e: Exception) { null }
                    }
                    if (ll != null) {
                        aLat = ll.first.toString(); aLng = ll.second.toString()
                        // Найденные кнопкой координаты — тоже осознанное решение
                        // пользователя в этом сеансе, не просто предзаполнение из
                        // base: должны пережить onConfirm так же, как ручной ввод.
                        latLngManuallyEdited = true
                        geocodeStatus = ctxLabel.getString(R.string.ce_geocode_found)
                    } else {
                        geocodeStatus = ctxLabel.getString(R.string.ce_geocode_not_found)
                    }
                }
            }) { Text(stringResource(R.string.ce_geocode_find)) }
        }
        geocodeStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
        }
    }
}
