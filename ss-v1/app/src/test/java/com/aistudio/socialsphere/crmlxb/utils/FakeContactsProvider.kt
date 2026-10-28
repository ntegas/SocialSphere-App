package com.aistudio.socialsphere.crmlxb.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract

/**
 * PHONE / VCF PACK #4 — фейковый провайдер Android Contacts для Robolectric-тестов.
 *
 * Моделирует ровно то, что реально делает системный ContactsProvider2 для приложения:
 *  • Data-запросы (`content://com.android.contacts/data`) с `contact_id=?` или без фильтра;
 *  • разрешение LOOKUP_KEY (`.../contacts/lookup/<key>`): ключ стабилен, _ID при переагрегации меняется;
 *  • запрос `contacts` по `_id`;
 *  • сбой доступа — SecurityException (так провайдер отвечает без READ_CONTACTS, в том числе после
 *    автосброса разрешений неиспользуемого приложения);
 *  • «сломанные» ответы: пустой курсор, null-курсор, курсор без колонки, строки с null-полями.
 */
class FakeContactsProvider : ContentProvider() {

    class Row(val mime: String, val data: Map<String, Any?>)

    class FakeContact(var id: Long, val lookupKey: String, val rows: MutableList<Row> = mutableListOf())

    companion object {
        val contacts = mutableListOf<FakeContact>()
        @Volatile var securityFailure = false
        @Volatile var emptyCursors = false
        @Volatile var nullCursors = false
        @Volatile var dropColumns: Set<String> = emptySet()
        @Volatile var throwOnQuery: RuntimeException? = null
        @Volatile var extraMalformedRows = false
        /** Все сделанные провайдеру запросы (uri + selection) — для проверки «без picker/без полного скана». */
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun reset() {
            contacts.clear(); securityFailure = false; emptyCursors = false; nullCursors = false
            dropColumns = emptySet(); throwOnQuery = null; extraMalformedRows = false; queries.clear()
        }

        private val DATA_COLUMNS = listOf(
            "contact_id", "mimetype", "lookup",
            "data1", "data2", "data3", "data4", "data5", "data6", "data7", "data8", "data9", "data10",
            "data11", "data12", "data13", "data14", "data15",
        )

        // ── построители строк ──
        fun nameRow(given: String?, family: String?, display: String? = listOfNotNull(given, family).joinToString(" ").ifBlank { null },
                    middle: String? = null, prefix: String? = null, suffix: String? = null) = Row(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            mapOf("data1" to display, "data2" to given, "data3" to family, "data4" to prefix, "data5" to middle, "data6" to suffix))

        fun phoneRow(number: String, type: Int = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) = Row(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, mapOf("data1" to number, "data2" to type))

        fun emailRow(email: String, type: Int = ContactsContract.CommonDataKinds.Email.TYPE_HOME) = Row(
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, mapOf("data1" to email, "data2" to type))

        fun addressRow(street: String, city: String, country: String = "", neighborhood: String? = null,
                       type: Int = ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME) = Row(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            mapOf("data1" to "$street, $city", "data2" to type, "data4" to street, "data6" to neighborhood, "data7" to city, "data10" to country))

        fun orgRow(company: String, title: String? = null) = Row(
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, mapOf("data1" to company, "data4" to title))

        fun noteRow(text: String) = Row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf("data1" to text))

        fun contact(id: Long, lookup: String, vararg rows: Row) = FakeContact(id, lookup, rows.toMutableList()).also { contacts += it }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        queries += "$uri | ${selection ?: ""} | ${selectionArgs?.joinToString(",") ?: ""}"
        throwOnQuery?.let { throw it }
        if (securityFailure) {
            throw SecurityException("Permission Denial: opening provider com.android.providers.contacts.ContactsProvider2 " +
                "requires android.permission.READ_CONTACTS or grantUriPermission()")
        }
        if (nullCursors) return null
        val segs = uri.pathSegments
        return when {
            segs.firstOrNull() == "data" -> dataCursor(projection, selection, selectionArgs)
            segs.size >= 3 && segs[0] == "contacts" && segs[1] == "lookup" -> lookupCursor(projection, segs[2])
            segs.firstOrNull() == "contacts" -> contactsCursor(projection, selection, selectionArgs)
            else -> MatrixCursor(projection?.map { it }?.toTypedArray() ?: arrayOf("_id"))   // groups и прочее — пусто
        }
    }

    private fun cols(projection: Array<out String>?, all: List<String>): List<String> =
        (projection?.toList() ?: all).filter { it !in dropColumns }

    private fun dataCursor(projection: Array<out String>?, selection: String?, args: Array<out String>?): Cursor {
        val columns = cols(projection, DATA_COLUMNS)
        val cursor = MatrixCursor(columns.toTypedArray())
        if (emptyCursors) return cursor
        val wantId = if (selection != null && selection.contains("contact_id") && args?.isNotEmpty() == true) args[0].toLongOrNull() else null
        for (c in contacts) {
            if (wantId != null && c.id != wantId) continue
            for (r in c.rows) cursor.addRow(columns.map { col ->
                when (col) { "contact_id" -> c.id; "mimetype" -> r.mime; "lookup" -> c.lookupKey; else -> r.data[col] }
            })
            if (extraMalformedRows) {
                cursor.addRow(columns.map { col -> when (col) { "contact_id" -> c.id; "mimetype" -> null; else -> null } })
                cursor.addRow(columns.map { col -> when (col) { "contact_id" -> null; "mimetype" -> ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE; else -> null } })
            }
        }
        return cursor
    }

    private fun lookupCursor(projection: Array<out String>?, key: String): Cursor {
        val columns = cols(projection, listOf("_id", "lookup"))
        val cursor = MatrixCursor(columns.toTypedArray())
        if (emptyCursors) return cursor
        contacts.firstOrNull { it.lookupKey == Uri.decode(key) }?.let { c ->
            cursor.addRow(columns.map { col -> when (col) { "_id" -> c.id; "lookup" -> c.lookupKey; else -> null } })
        }
        return cursor
    }

    private fun contactsCursor(projection: Array<out String>?, selection: String?, args: Array<out String>?): Cursor {
        val columns = cols(projection, listOf("_id", "lookup"))
        val cursor = MatrixCursor(columns.toTypedArray())
        if (emptyCursors) return cursor
        val wantId = if (selection != null && selection.contains("_id") && args?.isNotEmpty() == true) args[0].toLongOrNull() else null
        contacts.filter { wantId == null || it.id == wantId }.forEach { c ->
            cursor.addRow(columns.map { col -> when (col) { "_id" -> c.id; "lookup" -> c.lookupKey; else -> null } })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
