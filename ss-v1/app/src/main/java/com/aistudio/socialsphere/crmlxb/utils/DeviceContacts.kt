package com.aistudio.socialsphere.crmlxb.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * PHONE / VCF PACK #4 — единая точка доступа к КОНКРЕТНОМУ (связанному) контакту телефонной книги.
 *
 * Поток: SocialSphere Contact → (deviceLookupKey | deviceContactId) → ContactsContract → ОДИН контакт
 * (не вся книга) → [ImportContactCandidate] → патч в PhoneContactSync.
 *
 * Связь:
 *  • устойчивая идентичность — LOOKUP_KEY (переживает переименование, смену номера/email, переагрегацию,
 *    синхронизацию); Contacts._ID при этом МЕНЯЕТСЯ, поэтому «device_contact_<_ID>» — лишь кэш/фолбэк;
 *  • старая связь (только id, без ключа) резолвится по _ID и при первом успехе получает LOOKUP_KEY;
 *  • имя/номер/email как идентичность НЕ используются никогда.
 *
 * Ничего не бросает наружу: любое восстановимое состояние — явный [DeviceContactRead].
 */
data class DeviceContactRef(val contactId: Long?, val lookupKey: String?) {
    /** deviceContactId в формате, который хранит Contact («device_contact_<_ID>»). */
    val legacyId: String? get() = contactId?.let { "${DeviceContacts.LEGACY_ID_PREFIX}$it" }
}

enum class ResolvedBy { LOOKUP_KEY, LEGACY_ID }

sealed class DeviceContactRead {
    /** Связанный контакт найден и прочитан. */
    data class Found(val candidate: ImportContactCandidate, val ref: DeviceContactRef, val resolvedBy: ResolvedBy) : DeviceContactRead()
    /** Связь есть, но такого контакта в книге больше нет (удалён / связь устарела). */
    object Gone : DeviceContactRead()
    /** READ_CONTACTS не выдано или отозвано (в т.ч. автосбросом неиспользуемого приложения). */
    object PermissionDenied : DeviceContactRead()
    /** Провайдер недоступен / вернул мусор — не «удалён» и не «нет разрешения». */
    data class Unavailable(val reason: String) : DeviceContactRead()
}

object DeviceContacts {
    const val LEGACY_ID_PREFIX = "device_contact_"
    private const val TAG = "DeviceContacts"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** «device_contact_123» → 123; всё остальное (null, пусто, чужой формат) → null. */
    fun parseLegacyId(deviceContactId: String?): Long? =
        deviceContactId?.takeIf { it.startsWith(LEGACY_ID_PREFIX) }?.removePrefix(LEGACY_ID_PREFIX)?.toLongOrNull()

    private fun Cursor.str(name: String): String? {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.long(name: String): Long? = str(name)?.toLongOrNull()

    private class Resolved(val contactId: Long, val lookupKey: String?, val by: ResolvedBy)

    /** Провайдер вернул null-курсор — он недоступен (это НЕ «контакт удалён»). */
    private class ProviderUnavailable(message: String) : RuntimeException(message)

    /**
     * Читает связанный контакт. Порядок: разрешение → resolve (LOOKUP_KEY, иначе кэш-_ID) → чтение
     * строк Data ЭТОГО контакта. Любое исключение провайдера превращается в состояние результата.
     */
    fun readLinked(context: Context, deviceContactId: String?, lookupKey: String?): DeviceContactRead {
        if (deviceContactId.isNullOrBlank() && lookupKey.isNullOrBlank()) return DeviceContactRead.Gone
        if (!hasPermission(context)) return DeviceContactRead.PermissionDenied
        return try {
            val resolver = context.contentResolver
            val resolved = resolve(resolver, deviceContactId, lookupKey) ?: return DeviceContactRead.Gone
            val cursor = resolver.query(
                ContactsContract.Data.CONTENT_URI, ContactImporter.DATA_PROJECTION,
                "${ContactsContract.Data.CONTACT_ID}=?", arrayOf(resolved.contactId.toString()), null
            ) ?: return DeviceContactRead.Unavailable("Data query returned null")
            val rows = cursor.use {
                if (it.getColumnIndex(ContactsContract.Data.CONTACT_ID) < 0 || it.getColumnIndex(ContactsContract.Data.MIMETYPE) < 0)
                    return DeviceContactRead.Unavailable("Data cursor without required columns")
                ContactImporter.readDataRows(it, emptyMap())
            }
            val key = resolved.contactId.toString()
            val candidate = (rows[key] ?: ImportContactCandidate(id = "$LEGACY_ID_PREFIX$key"))
                .let { c -> if (c.deviceLookupKey == null && resolved.lookupKey != null) c.copy(deviceLookupKey = resolved.lookupKey) else c }
            DeviceContactRead.Found(candidate, DeviceContactRef(resolved.contactId, resolved.lookupKey ?: candidate.deviceLookupKey), resolved.by)
        } catch (e: SecurityException) {
            DeviceContactRead.PermissionDenied
        } catch (e: RuntimeException) {
            android.util.Log.e(TAG, "linked contact read failed", e)
            DeviceContactRead.Unavailable(e.javaClass.simpleName)
        }
    }

    private fun resolve(resolver: android.content.ContentResolver, deviceContactId: String?, lookupKey: String?): Resolved? {
        if (!lookupKey.isNullOrBlank()) {
            // Провайдер сам сопоставляет LOOKUP_KEY с ТЕКУЩИМ контактом (в т.ч. после переагрегации).
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey))
            (resolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY), null, null, null)
                ?: throw ProviderUnavailable("lookup query returned null")).use { c ->
                if (c.moveToFirst()) {
                    val id = c.long(ContactsContract.Contacts._ID) ?: return null
                    return Resolved(id, c.str(ContactsContract.Contacts.LOOKUP_KEY) ?: lookupKey, ResolvedBy.LOOKUP_KEY)
                }
            }
            return null    // ключ есть, но не разрешился — контакта нет (кэш-_ID при живом ключе не используем)
        }
        val id = parseLegacyId(deviceContactId) ?: return null
        (resolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts._ID}=?", arrayOf(id.toString()), null
        ) ?: throw ProviderUnavailable("contacts query returned null")).use { c ->
            if (c.moveToFirst()) return Resolved(id, c.str(ContactsContract.Contacts.LOOKUP_KEY), ResolvedBy.LEGACY_ID)
        }
        return null
    }
}
