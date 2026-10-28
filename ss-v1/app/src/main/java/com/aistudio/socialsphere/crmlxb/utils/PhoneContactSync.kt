package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.applyImportedCompany
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * PHONE / VCF PACK #4 — «Обновить из телефона» как бизнес-операция с ЯВНЫМ результатом.
 *
 *   SocialSphere Contact → сохранённая связь (LOOKUP_KEY | id) → [DeviceContacts.readLinked] (один контакт)
 *   → [computePatch] (что телефон реально добавляет) → `updateContact(base, edited)` (Pack #2, разница
 *   поверх свежего состояния) → durable commit в Room → [PhoneSyncResult].
 *
 * Раньше это была лямбда внутри composable без проверки разрешения и без перехвата исключений: SecurityException
 * из ContentResolver (разрешение отозвано / автосброс) вылетал в scope композиции — приложение закрывалось.
 * Теперь ни одно восстановимое состояние не бросает: оно возвращается результатом, а UI выбирает реакцию
 * ([uiActionFor]); picker при валидной связи не открывается никогда.
 *
 * Поля, принадлежащие телефону (и только они меняются): структура имени (пустые поля), телефоны, email,
 * адреса, заметка телефона, место работы/должность. Аддитивно: ничего не удаляется и не перезаписывается.
 * Всё внутреннее (подарки, заметки, размеры, теги, группы, связи, familyNote, nextStep, lastContactDate,
 * личные детали, календарь) в патч не входит вовсе.
 */
sealed class PhoneSyncResult {
    data class Applied(val phones: Int, val emails: Int, val addresses: Int, val workplace: Boolean) : PhoneSyncResult()
    object NothingNew : PhoneSyncResult()
    object NotLinked : PhoneSyncResult()
    object PermissionDenied : PhoneSyncResult()
    /** Связанного контакта в телефонной книге больше нет — предложить «Связать заново». */
    object LinkGone : PhoneSyncResult()
    /** Запись не закоммичена в Room (сообщение уже показал корневой экран, память пересобрана). */
    object CommitFailed : PhoneSyncResult()
    data class Failed(val reason: String) : PhoneSyncResult()
}

/** Реакция экрана на результат. OPEN_PICKER — только для НЕсвязанного контакта. */
enum class SyncUiAction { SHOW_APPLIED, SHOW_NOTHING_NEW, REQUEST_PERMISSION, OFFER_RELINK, SHOW_FAILED, OPEN_PICKER, NONE }

fun uiActionFor(result: PhoneSyncResult): SyncUiAction = when (result) {
    is PhoneSyncResult.Applied -> SyncUiAction.SHOW_APPLIED
    PhoneSyncResult.NothingNew -> SyncUiAction.SHOW_NOTHING_NEW
    PhoneSyncResult.NotLinked -> SyncUiAction.OPEN_PICKER
    PhoneSyncResult.PermissionDenied -> SyncUiAction.REQUEST_PERMISSION
    PhoneSyncResult.LinkGone -> SyncUiAction.OFFER_RELINK
    PhoneSyncResult.CommitFailed -> SyncUiAction.NONE
    is PhoneSyncResult.Failed -> SyncUiAction.SHOW_FAILED
}

/** Что телефон реально добавляет к свежему состоянию контакта. */
internal class PhonePatch(
    val firstName: String, val lastName: String, val middleName: String?,
    val namePrefix: String?, val nameSuffix: String?,
    val phoneticFirstName: String?, val phoneticMiddleName: String?, val phoneticLastName: String?,
    val newPhones: List<ContactPhone>, val newEmails: List<ContactEmail>, val newAddresses: List<Address>,
    val newNoteText: String?,
) {
    fun hasFieldUpdates(fresh: Contact): Boolean =
        newPhones.isNotEmpty() || newEmails.isNotEmpty() || newAddresses.isNotEmpty() || newNoteText != null ||
            firstName != fresh.firstName || lastName != fresh.lastName || middleName != fresh.middleName ||
            namePrefix != fresh.namePrefix || nameSuffix != fresh.nameSuffix ||
            phoneticFirstName != fresh.phoneticFirstName || phoneticMiddleName != fresh.phoneticMiddleName ||
            phoneticLastName != fresh.phoneticLastName
}

object PhoneContactSync {

    /** Тестовый шов: вызывается ПОСЛЕ чтения контакта телефона и ДО применения патча — модель
     *  независимого изменения SocialSphere-контакта во время чтения Android-контакта. В проде null. */
    @Volatile internal var afterReadHookForTests: (suspend () -> Unit)? = null

    /**
     * Чистое вычисление патча от Android-контакта [dev] к СВЕЖЕМУ состоянию [fresh].
     * Дедуп: телефоны — по нормализованным 10 цифрам (AppStateStore.phoneDigits), email — без регистра,
     * адреса — строка+город. Структура имени только заполняет пустое (как и раньше).
     */
    internal fun computePatch(fresh: Contact, dev: ImportContactCandidate, notePrefix: String): PhonePatch {
        val newPhones = dev.phones
            .filter { d -> fresh.phones.none { AppStateStore.phoneDigits(it.number) == AppStateStore.phoneDigits(d.number) } }
            .distinctBy { AppStateStore.phoneDigits(it.number) }
            .mapIndexed { i, p -> p.copy(id = UUID.randomUUID().toString(), contactId = fresh.id, isPrimary = fresh.phones.isEmpty() && i == 0) }
        val newEmails = dev.emails
            .filter { d -> fresh.emails.none { it.email.equals(d.email, ignoreCase = true) } }
            .distinctBy { it.email.lowercase() }
            .mapIndexed { i, e -> e.copy(id = UUID.randomUUID().toString(), contactId = fresh.id, isPrimary = fresh.emails.isEmpty() && i == 0) }
        val newAddresses = dev.addresses
            .filter { d -> fresh.addresses.none { it.addressLine == d.addressLine && it.city == d.city } }
            .map { it.copy(id = UUID.randomUUID().toString(), ownerId = fresh.id, ownerType = AddressOwnerType.CONTACT) }
        val newNote = dev.notes?.takeIf { it.isNotBlank() }
            ?.takeIf { text -> fresh.notes.none { it.text == notePrefix + text } }
        return PhonePatch(
            firstName = fresh.firstName.ifBlank { dev.firstName },
            lastName = fresh.lastName.ifBlank { dev.lastName },
            middleName = fresh.middleName ?: dev.middleName.ifBlank { null },
            namePrefix = fresh.namePrefix ?: dev.namePrefix.ifBlank { null },
            nameSuffix = fresh.nameSuffix ?: dev.nameSuffix.ifBlank { null },
            phoneticFirstName = fresh.phoneticFirstName ?: dev.phoneticFirstName.ifBlank { null },
            phoneticMiddleName = fresh.phoneticMiddleName ?: dev.phoneticMiddleName.ifBlank { null },
            phoneticLastName = fresh.phoneticLastName ?: dev.phoneticLastName.ifBlank { null },
            newPhones = newPhones, newEmails = newEmails, newAddresses = newAddresses, newNoteText = newNote,
        )
    }

    /** Связать SocialSphere-контакт с выбранным контактом телефона (LOOKUP_KEY + кэш-id — парой). */
    fun link(contact: Contact, candidate: ImportContactCandidate): Deferred<Unit> =
        AppStateStore.updateContact(contact, contact.copy(deviceContactId = candidate.id, deviceLookupKey = candidate.deviceLookupKey))

    /** «Обновить из телефона». Никогда не бросает (кроме отмены корутины) и никогда не открывает picker. */
    suspend fun pullFromPhone(context: Context, contactId: String): PhoneSyncResult {
        return try {
            val start = AppStateStore.getContact(contactId) ?: return PhoneSyncResult.Failed("contact not found")
            if (start.deviceContactId.isNullOrBlank() && start.deviceLookupKey.isNullOrBlank()) return PhoneSyncResult.NotLinked
            val read = withContext(Dispatchers.IO) {
                DeviceContacts.readLinked(context, start.deviceContactId, start.deviceLookupKey)
            }
            when (read) {
                DeviceContactRead.PermissionDenied -> PhoneSyncResult.PermissionDenied
                DeviceContactRead.Gone -> PhoneSyncResult.LinkGone
                is DeviceContactRead.Unavailable -> PhoneSyncResult.Failed(read.reason)
                is DeviceContactRead.Found -> {
                    afterReadHookForTests?.invoke()
                    apply(context, start, read)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            android.util.Log.e("PhoneContactSync", "pullFromPhone failed", e)
            PhoneSyncResult.Failed(e.javaClass.simpleName)
        }
    }

    private suspend fun apply(context: Context, start: Contact, read: DeviceContactRead.Found): PhoneSyncResult {
        val dev = read.candidate
        // СВЕЖЕЕ каноническое состояние на момент применения (а не снимок до чтения): дедуп и base для
        // updateContact считаются от него — независимые правки во время чтения Android-контакта не откатываются.
        val fresh = AppStateStore.getContact(start.id) ?: return PhoneSyncResult.Failed("contact deleted")
        val notePrefix = context.getString(R.string.imp_note_from_import, "")
        val patch = computePatch(fresh, dev, notePrefix)

        var edited = fresh.copy(
            firstName = patch.firstName, lastName = patch.lastName, middleName = patch.middleName,
            namePrefix = patch.namePrefix, nameSuffix = patch.nameSuffix,
            phoneticFirstName = patch.phoneticFirstName, phoneticMiddleName = patch.phoneticMiddleName,
            phoneticLastName = patch.phoneticLastName,
            phones = fresh.phones + patch.newPhones,
            emails = fresh.emails + patch.newEmails,
            addresses = fresh.addresses + patch.newAddresses,
        )
        // Апгрейд связи: LOOKUP_KEY и актуальный кэш-_ID. Только если пользователь не менял связь за время чтения.
        val linkUntouched = fresh.deviceContactId == start.deviceContactId && fresh.deviceLookupKey == start.deviceLookupKey
        val newKey = read.ref.lookupKey
        val newLegacyId = read.ref.legacyId
        if (linkUntouched && ((newKey != null && newKey != fresh.deviceLookupKey) || (newLegacyId != null && newLegacyId != fresh.deviceContactId))) {
            edited = edited.copy(deviceContactId = newLegacyId ?: fresh.deviceContactId, deviceLookupKey = newKey ?: fresh.deviceLookupKey)
        }

        val writes = mutableListOf<Deferred<*>>()
        if (edited != fresh) writes += AppStateStore.updateContact(fresh, edited)
        if (patch.newNoteText != null) {
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            writes += AppStateStore.addNote(Note(
                id = UUID.randomUUID().toString(), contactId = fresh.id, type = NoteType.GENERAL,
                text = notePrefix + patch.newNoteText, isImportant = false, createdAt = now, updatedAt = now,
            ))
        }
        // Работа/должность — та же функция, что у массового импорта (find-or-create компания + связь, без дублей).
        val companyAdded = applyImportedCompany(dev.companyName, dev.jobTitle, fresh.id, context)

        val visible = patch.hasFieldUpdates(fresh) || companyAdded
        if (writes.isEmpty() && !companyAdded) return PhoneSyncResult.NothingNew

        // «Успех» — только после подтверждённого коммита (Pack #2); барьер подтверждает и запись компании/связи.
        val committed = AppStateStore.awaitDurableCommit(*writes.toTypedArray(), AppStateStore.writeBarrier())
        if (!committed) return PhoneSyncResult.CommitFailed
        return if (visible) PhoneSyncResult.Applied(patch.newPhones.size, patch.newEmails.size, patch.newAddresses.size, companyAdded)
        else PhoneSyncResult.NothingNew
    }
}
