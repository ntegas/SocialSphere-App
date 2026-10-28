package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import com.aistudio.socialsphere.crmlxb.model.Address
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.CommunicationRhythm
import com.aistudio.socialsphere.crmlxb.model.ConnectionLevel
import com.aistudio.socialsphere.crmlxb.model.ContactEmail
import com.aistudio.socialsphere.crmlxb.model.ContactPhone
import com.aistudio.socialsphere.crmlxb.model.ContactRelation
import com.aistudio.socialsphere.crmlxb.model.Messenger
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import com.aistudio.socialsphere.crmlxb.model.RelationshipType
import com.aistudio.socialsphere.crmlxb.model.SocialRole
import com.aistudio.socialsphere.crmlxb.model.Tag
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File

/** Новая важная дата в черновике формы (не сохранена как CalendarItem). */
@JsonClass(generateAdapter = true)
data class DraftDate(val title: String, val startDate: String, val type: CalendarItemType, val remind: ReminderTime = ReminderTime.ON_DAY)

/**
 * ЧЕРНОВИК формы контакта (жалоба владельца 2026-10-08: «не сохранив, я теряю данные»). Всё, что
 * пользователь успел ввести в ContactEditScreen, — одним значением: им же определяется «форма
 * изменена» (сравнение с исходным) и оно же пишется на диск ([ContactFormDraftStore]), чтобы ввод
 * пережил смерть процесса (система выгрузила приложение, пока телефон был заблокирован).
 *
 * Только поля, которые форма реально даёт править; всё остальное (заметки, подарки, размеры, дата
 * последнего контакта…) в черновик не входит и при сохранении берётся из оригинала.
 */
@JsonClass(generateAdapter = true)
data class ContactFormDraft(
    val firstName: String, val lastName: String, val middleName: String,
    val namePrefix: String, val nameSuffix: String,
    val phoneticFirstName: String, val phoneticMiddleName: String, val phoneticLastName: String,
    val phones: List<ContactPhone>, val emails: List<ContactEmail>, val messengers: List<Messenger>,
    val addresses: List<Address>,
    val selectedCompanyId: String, val companyPosition: String, val companyDept: String, val workNote: String,
    val linkedOfficeAddressId: String?,
    val relationshipType: RelationshipType?, val customRelationType: String,
    val secondaryRelationshipTypes: List<RelationshipType>,
    val connectionLevel: ConnectionLevel, val socialRole: SocialRole,
    val communicationRhythm: CommunicationRhythm, val customRhythmDaysText: String,
    val photoUri: String?,
    val nickname: String, val profession: String, val nextStep: String,
    val canHelpWith: String, val iCanHelpWith: String, val talkingPoints: String,
    val meetContext: String, val familyNote: String, val meetDate: String,
    val tags: List<Tag>, val newDates: List<DraftDate>, val newNotes: List<String>, val quickNoteText: String,
    val relations: List<ContactRelation>,
)

@JsonClass(generateAdapter = true)
data class StoredContactDraft(val savedAt: Long, val draft: ContactFormDraft)

/**
 * Черновики на диске (filesDir/form_drafts/contact_<ключ>.json; ключ — id контакта или "new").
 * Пишет/читает/чистит только форма контакта; wipeAllData чистит всё каталогом. Любая ошибка ввода-вывода
 * или разбора — «черновика нет» (никогда не роняет форму), повреждённый файл удаляется.
 */
object ContactFormDraftStore {
    /** Старше этого — считаем забытым и не предлагаем. */
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private val adapter by lazy { Moshi.Builder().build().adapter(StoredContactDraft::class.java) }
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "form_drafts")
    private fun file(ctx: Context, key: String) = File(dir(ctx), "contact_" + key.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")

    /** [stillWanted] проверяется ВНУТРИ блокировки: форма, закончившаяся (сохранение/отказ) между решением
     *  писать и самой записью, не должна воскресить уже удалённый черновик. */
    fun save(ctx: Context, key: String, draft: ContactFormDraft, now: Long = System.currentTimeMillis(), stillWanted: () -> Boolean = { true }) {
        synchronized(lock) {
            if (!stillWanted()) return
            try {
                val f = file(ctx, key)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(adapter.toJson(StoredContactDraft(now, draft)))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (t: Throwable) {
                android.util.Log.w("ContactFormDraftStore", "save failed", t)
            }
        }
    }

    fun load(ctx: Context, key: String, now: Long = System.currentTimeMillis()): StoredContactDraft? = synchronized(lock) {
        val f = file(ctx, key)
        if (!f.exists()) return@synchronized null
        try {
            val stored = adapter.fromJson(f.readText())
            when {
                stored == null -> { f.delete(); null }
                now - stored.savedAt > MAX_AGE_MS -> { f.delete(); null }
                else -> stored
            }
        } catch (t: Throwable) {
            android.util.Log.w("ContactFormDraftStore", "load failed, dropping draft", t)
            f.delete()
            null
        }
    }

    fun clear(ctx: Context, key: String) {
        synchronized(lock) { try { file(ctx, key).delete() } catch (_: Throwable) {} }
    }

    fun clearAll(ctx: Context) {
        synchronized(lock) { try { dir(ctx).deleteRecursively() } catch (_: Throwable) {} }
    }
}
