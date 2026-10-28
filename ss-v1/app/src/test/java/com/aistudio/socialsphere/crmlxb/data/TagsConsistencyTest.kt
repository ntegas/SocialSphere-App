package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.data.local.toDomain
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppLanguage
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.BackupData
import com.aistudio.socialsphere.crmlxb.utils.ContactNoteCodec
import com.aistudio.socialsphere.crmlxb.utils.ExportManager
import com.aistudio.socialsphere.crmlxb.utils.SearchEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BATCH 5 — TAGS CONSISTENCY.
 *
 * Каноническим состоянием тегов является управляемая система
 * (Tag + ContactTagMember). Легаси Contact.tags остаётся только как вход
 * совместимости (старые бэкапы, vCard-заметки) и приводится к канону
 * реконсиляцией. Эти тесты закрепляют оба инварианта плюс безопасную
 * семантику удаления.
 *
 * Все проверки идут по КАНОНИЧЕСКИМ таблицам/спискам, а не по Contact.tags.
 */
@RunWith(RobolectricTestRunner::class)
class TagsConsistencyTest {

    private lateinit var db: SocialsphereDatabase
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        // Настройки нужны реально проинициализированными: тест restore-сценария
        // читает и выставляет защёлку исторической миграции.
        AppSettings.init(ctx)
        db = freshDb()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(timeoutMs = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("фоновый loadInitialData() не завершился за таймаут в setup()")
        clearAll()
    }

    @After
    fun tearDown() {
        clearAll()
        db.close()
    }

    private fun freshDb(): SocialsphereDatabase =
        Room.inMemoryDatabaseBuilder(ctx, SocialsphereDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /**
     * BATCH 5B (5B-004): настоящая чистая установка. Прежний clearAll() чистил
     * только in-memory списки, а строки оставались в той же Room-БД — после
     * restoreBackup их возвращал reloadFromDb(), и round-trip-тест проходил бы,
     * даже если restore вообще перестал бы писать теги.
     */
    private fun restartOnEmptyDb() {
        clearAll()
        db.close()
        db = freshDb()
        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        val settled = pollUntilTrue(timeoutMs = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !AppStateStore.isLoading
        }
        if (!settled) fail("loadInitialData не завершился после пересоздания БД")
        clearAll()
    }

    /** Кандидат импорта, несущий теги ровно так, как их несёт реальный
     *  vCard/телефонный импорт — через NOTE, разбираемый ContactNoteCodec. */
    private fun candidateWithTags(id: String, first: String, tags: List<String>) =
        com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate(
            id = id, firstName = first, lastName = "Петров",
            notes = ContactNoteCodec.encode(contact("tmp", legacyTags = tags))
        )

    private fun clearAll() {
        AppStateStore.contacts.clear()
        AppStateStore.companies.clear()
        AppStateStore.notes.clear()
        AppStateStore.gifts.clear()
        AppStateStore.addresses.clear()
        AppStateStore.calendarItems.clear()
        AppStateStore.companyRelations.clear()
        AppStateStore.contactRelations.clear()
        AppStateStore.groups.clear()
        AppStateStore.groupMembers.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
        AppStateStore.personalDetails.clear()
        AppStateStore.sizeInfos.clear()
    }

    // ─── обвязка (см. §57 базы знаний: не блокировать главный поток) ───

    private fun pollUntilTrue(timeoutMs: Long = 10_000, check: suspend () -> Boolean): Boolean = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = check()
        while (!ok && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
            ok = check()
        }
        ok
    }

    /** Для suspend-вызовов, которые внутри переходят на Dispatchers.Main
     *  (restoreBackup -> reloadFromDb). Прямой runBlocking на них вешает класс. */
    private fun <T> awaitOffMainThread(timeoutMs: Long = 15_000, block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.IO).launch {
            try { done.complete(block()) } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        val settled = pollUntilTrue(timeoutMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            done.isCompleted
        }
        if (!settled) fail("suspend-операция не завершилась за $timeoutMs мс")
        return runBlocking { done.await() }
    }

    private fun contact(id: String, first: String = "Иван", legacyTags: List<String> = emptyList()) = Contact(
        id = id, firstName = first, lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        tags = legacyTags,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    /** Создаёт тег ПРОДАКШН-путём (addTag) и ждёт, пока он реально окажется в
     *  Room — иначе проверки «тег остался в БД» были бы пустыми: тега там
     *  никогда и не было. */
    private fun createTag(name: String): Tag {
        val t = AppStateStore.addTag(name) ?: fail("addTag вернул null для «$name»") as Nothing
        val landed = pollUntilTrue(timeoutMs = 5000) { tagsInDb().any { it.id == t.id } }
        if (!landed) fail("тег «$name» не долетел до Room за таймаут")
        return t
    }

    private fun managedNamesOf(contactId: String): Set<String> =
        AppStateStore.tagsOfContact(contactId).map { it.name }.toSet()

    private fun membersInDb(): List<ContactTagMemberEntityLike> = runBlocking {
        db.contactDao().getContactTagMembers().first().map {
            ContactTagMemberEntityLike(it.id, it.tagId, it.contactId)
        }
    }

    private data class ContactTagMemberEntityLike(val id: String, val tagId: String, val contactId: String)

    private fun tagsInDb(): List<Tag> = runBlocking {
        db.contactDao().getTags().first().map { it.toDomain() }
    }

    // ─────────────────────────────────────────────────────────────
    //  §42 / §13 — управляемый тег попадает в выгрузку
    // ─────────────────────────────────────────────────────────────

    /**
     * BATCH 5B (5B-004): идёт через РЕАЛЬНЫЙ ExportManager.exportVCard, а не
     * повторяет выражение из продакшна. Прежняя версия сама звала
     * ContactNoteCodec.encode(c, tagsOfContact(...)) — и осталась бы зелёной,
     * даже если бы ExportManager вернулся к encode(c) и потерял все теги,
     * созданные в UI.
     */
    @Test
    fun managedTagCreatedInUi_isPresentInExportedVCard() {
        val c = contact("t1")
        // EXPORT SNAPSHOT CONSISTENCY PACK (2026-09-23): экспорт теперь читает канонический Room-снимок,
        // не живую память — контакт ОБЯЗАН быть реально закоммичен (не только в AppStateStore.contacts).
        runBlocking { AppStateStore.addContact(c).await() }
        val vip = createTag("VIP")
        runBlocking { AppStateStore.setContactTags(c.id, setOf(vip.id)).await() }

        val vcf = runBlocking { ExportManager.exportVCard(ctx) }.readText()

        assertTrue("управляемый тег VIP обязан попасть в vCard-выгрузку, vcf=$vcf",
            vcf.contains("VIP"))
    }

    /** Легаси-строки, которые ещё не реконсилированы, из выгрузки не теряются (§14). */
    @Test
    fun unreconciledLegacyTag_isNotLostOnExport() {
        val c = contact("t2", legacyTags = listOf("Поставщик"))
        val encoded = ContactNoteCodec.encode(c, emptyList())
        assertTrue("нереконсилированный легаси-тег не должен потеряться, encoded=$encoded",
            encoded!!.contains("Поставщик"))
    }

    /** Управляемое и легаси-имя одного тега не должны задваиваться в выгрузке. */
    @Test
    fun managedAndLegacySameName_exportedOnce() {
        val c = contact("t3", legacyTags = listOf(" vip "))
        val encoded = ContactNoteCodec.encode(c, listOf("VIP"))!!
        val tagLine = encoded.lines().first { it.contains("VIP", ignoreCase = true) }
        assertEquals("имя не должно повторяться, строка=$tagLine",
            1, Regex("(?i)vip").findAll(tagLine).count())
    }

    // ─────────────────────────────────────────────────────────────
    //  §44 / §9 / §10 — импорт легаси-тегов даёт управляемые
    // ─────────────────────────────────────────────────────────────

    /**
     * BATCH 5B (5B-004): вызывает РЕАЛЬНЫЙ performImport, а не реконсиляцию
     * напрямую. Прежняя версия звала helper и осталась бы зелёной, даже если
     * убрать вызов реконсиляции из импорта целиком.
     */
    @Test
    fun legacyTagsFromImport_becomeManagedTagsAndMemberships() {
        val cand = candidateWithTags("t4cand", "Иван", listOf("VIP", "Supplier", "Tennis"))

        runBlocking {
            com.aistudio.socialsphere.crmlxb.ui.screens.performImport(listOf(cand), ctx)
        }

        val imported = AppStateStore.contacts.single { it.firstName == "Иван" && it.lastName == "Петров" }
        assertEquals(setOf("VIP", "Supplier", "Tennis"), managedNamesOf(imported.id))
        val namesInDb = tagsInDb().map { it.name }.toSet()
        assertTrue("теги обязаны быть в Room, есть=$namesInDb",
            namesInDb.containsAll(listOf("VIP", "Supplier", "Tennis")))
        assertEquals("членства обязаны быть в Room", 3, membersInDb().count { it.contactId == imported.id })
    }

    /** §53 — пустые/пробельные значения не создают фиктивных тегов. */
    @Test
    fun blankLegacyTagValues_doNotCreateTags() {
        AppStateStore.contacts.add(contact("t5", legacyTags = listOf("", "   ", "\t")))

        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }

        assertTrue("пустые строки не должны порождать теги", AppStateStore.tags.isEmpty())
        assertTrue(AppStateStore.tagMembers.isEmpty())
    }

    /** §54/§55 — существующее правило: сравнение имён без учёта регистра. */
    @Test
    fun duplicateAndCaseVariantLegacyTags_collapseToOneManagedTag() {
        AppStateStore.contacts.add(contact("t6", legacyTags = listOf("VIP", "VIP", "vip", " VIP ")))

        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }

        assertEquals("регистр и пробелы не должны плодить теги", 1, AppStateStore.tags.size)
        assertEquals(1, AppStateStore.tagMembers.count { it.contactId == "t6" })
        assertEquals("VIP", AppStateStore.tags.single().name)
    }

    // ─────────────────────────────────────────────────────────────
    //  §46 / §20 — идемпотентность
    // ─────────────────────────────────────────────────────────────

    @Test
    fun reconciliationRunTwice_doesNotDuplicateTagsOrMemberships() {
        AppStateStore.contacts.add(contact("t7", legacyTags = listOf("VIP")))

        runBlocking {
            AppStateStore.reconcileLegacyTagsIntoManaged()
            AppStateStore.reconcileLegacyTagsIntoManaged()
        }

        assertEquals("повторный вызов не должен создавать второй тег",
            1, tagsInDb().count { it.name.equals("VIP", ignoreCase = true) })
        assertEquals("повторный вызов не должен создавать второе членство",
            1, membersInDb().count { it.contactId == "t7" })
    }

    // ─────────────────────────────────────────────────────────────
    //  §45 / §16 / §21 — P1: restore после чистой переустановки
    // ─────────────────────────────────────────────────────────────

    @Test
    fun restoreOldBackup_reconcilesLegacyTags_evenWhenMigrationLatchAlreadyTrue() {
        // Моделируем ровно тот сценарий: свежая установка, база пуста,
        // историческая миграция уже «отработала» вхолостую и взвела защёлку.
        AppSettings.legacyContactTagsMigrated.value = true
        assertTrue("предусловие: управляемых тегов нет", AppStateStore.tags.isEmpty())

        // Старый бэкап: теги живут ТОЛЬКО в легаси-поле контакта.
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(contacts = listOf(contact("t8", legacyTags = listOf("VIP", "Supplier"))))
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        assertEquals("восстановленные легаси-теги обязаны стать управляемыми",
            setOf("VIP", "Supplier"), managedNamesOf("t8"))
        val restoredNames = tagsInDb().map { it.name }.toSet()
        assertTrue("и обязаны быть в Room к моменту возврата restore, есть=$restoredNames",
            restoredNames.containsAll(listOf("VIP", "Supplier")))
        assertEquals(2, membersInDb().count { it.contactId == "t8" })
        assertTrue("защёлка исторической миграции не должна сбрасываться",
            AppSettings.legacyContactTagsMigrated.value)
    }

    // ─────────────────────────────────────────────────────────────
    //  §43 / §15 — round trip экспорт -> импорт
    // ─────────────────────────────────────────────────────────────

    @Test
    fun exportImportRoundTrip_preservesManagedTagsInCanonicalTables() {
        // Контакт с УПРАВЛЯЕМЫМИ тегами (легаси-поле пустое) — как после
        // работы в современном UI.
        val c = contact("t9")
        AppStateStore.contacts.add(c)
        val vip = createTag("VIP")
        val sup = createTag("Supplier")
        AppStateStore.setContactTags(c.id, setOf(vip.id, sup.id))

        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(c),
                tags = AppStateStore.tags.toList(),
                tagMembers = AppStateStore.tagMembers.toList()
            )
        )

        // Честная чистая установка: пересоздаём саму Room-БД, а не только
        // in-memory списки (см. restartOnEmptyDb — это и был ложный green).
        restartOnEmptyDb()
        assertTrue(AppStateStore.tags.isEmpty())
        assertTrue("предусловие: БД действительно пуста", membersInDb().isEmpty())

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        assertEquals("после round-trip управляемые членства обязаны восстановиться",
            setOf("VIP", "Supplier"), managedNamesOf("t9"))
        assertEquals("без дублей членства", 2, membersInDb().count { it.contactId == "t9" })
    }

    // ─────────────────────────────────────────────────────────────
    //  §47 / §48 / §49 / §28 / §29 — семантика удаления
    // ─────────────────────────────────────────────────────────────

    /** §47 — снятие тега с контакта убирает членство, но НЕ сам тег. */
    @Test
    fun removingTagFromContact_removesMembershipButKeepsTag() {
        AppStateStore.contacts.add(contact("a1"))
        val vip = createTag("VIP")
        AppStateStore.setContactTags("a1", setOf(vip.id))

        // Ровно то, что делает тап по чипу на карточке контакта.
        AppStateStore.setContactTags("a1", emptySet())

        assertTrue("членство должно исчезнуть", managedNamesOf("a1").isEmpty())
        assertTrue("сам тег обязан остаться в каталоге",
            AppStateStore.tags.any { it.id == vip.id })
    }

    /** §48 — снятие у одного контакта не трогает другой. */
    @Test
    fun removingTagFromOneContact_doesNotAffectAnotherContact() {
        AppStateStore.contacts.add(contact("a2"))
        AppStateStore.contacts.add(contact("b2", first = "Пётр"))
        val vip = createTag("VIP")
        AppStateStore.setContactTags("a2", setOf(vip.id))
        AppStateStore.setContactTags("b2", setOf(vip.id))

        AppStateStore.setContactTags("a2", emptySet())

        assertTrue("у A тега больше нет", managedNamesOf("a2").isEmpty())
        assertEquals("у B тег остался", setOf("VIP"), managedNamesOf("b2"))
        assertTrue("сам тег существует", AppStateStore.tags.any { it.id == vip.id })
    }

    /**
     * §49 — прямая защита подтверждённого бага: снятие ПОСЛЕДНЕГО членства
     * не должно удалять тег. Раньше карточка контакта в этом случае молча
     * звала deleteTag() и уничтожала тег глобально.
     */
    @Test
    fun removingLastMembership_keepsTagInCatalog() {
        AppStateStore.contacts.add(contact("a3"))
        val vip = createTag("VIP")
        AppStateStore.setContactTags("a3", setOf(vip.id))
        assertEquals(1, AppStateStore.contactIdsWithTag(vip.id).size)

        AppStateStore.setContactTags("a3", emptySet())

        assertEquals("носителей не осталось", 0, AppStateStore.contactIdsWithTag(vip.id).size)
        assertTrue("но тег обязан остаться в каталоге",
            AppStateStore.tags.any { it.id == vip.id })
        assertTrue("и в Room тоже", tagsInDb().any { it.id == vip.id })
    }

    /** §50 — явное глобальное удаление убирает тег и все его членства. */
    @Test
    fun explicitGlobalDelete_removesTagAndAllMemberships_leavingOtherTagsIntact() {
        AppStateStore.contacts.add(contact("a4"))
        AppStateStore.contacts.add(contact("b4", first = "Пётр"))
        val vip = createTag("VIP")
        val other = createTag("Другой")
        AppStateStore.setContactTags("a4", setOf(vip.id, other.id))
        AppStateStore.setContactTags("b4", setOf(vip.id))

        AppStateStore.deleteTag(vip.id)
        val gone = pollUntilTrue(timeoutMs = 5000) { tagsInDb().none { it.id == vip.id } }
        if (!gone) fail("глобальное удаление не долетело до Room за таймаут")

        assertTrue("тег удалён", AppStateStore.tags.none { it.id == vip.id })
        assertTrue("все его членства удалены", AppStateStore.tagMembers.none { it.tagId == vip.id })
        assertTrue("в Room тоже", membersInDb().none { it.tagId == vip.id })
        assertTrue("другой тег не затронут", AppStateStore.tags.any { it.id == other.id })
        assertEquals("и его членство цело", setOf("Другой"), managedNamesOf("a4"))
    }

    // ─────────────────────────────────────────────────────────────
    //  §51 / §52 — поиск и фильтр видят реконсилированный тег
    // ─────────────────────────────────────────────────────────────

    /**
     * BATCH 5B (5B-004): легаси-поле контакта пустое, поэтому найти его можно
     * ТОЛЬКО через управляемый тег. Прежняя версия оставляла legacyTags=["VIP"],
     * и тест проходил на легаси-ветке SearchEngine, ничего не доказывая про
     * реконсиляцию. (После 5B-001 реконсиляция сама гасит поле — здесь это
     * закреплено проверкой.)
     */
    @Test
    fun searchFindsContactByReconciledManagedTag() {
        AppStateStore.contacts.add(contact("s1", legacyTags = listOf("VIP")))
        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }
        assertTrue("после реконсиляции легаси-поле обязано быть погашено",
            AppStateStore.contacts.single { it.id == "s1" }.tags.isEmpty())

        val found = SearchEngine.searchContacts("VIP")

        assertTrue("поиск обязан находить контакт по реконсилированному тегу",
            found.any { it.contact.id == "s1" })
    }

    @Test
    fun reconciledTagIsUsableInManagedTagFilter() {
        AppStateStore.contacts.add(contact("f1", legacyTags = listOf("VIP")))
        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }

        val vip = AppStateStore.tags.single { it.name == "VIP" }

        assertTrue("реконсилированный тег обязан быть доступен фильтру по id",
            AppStateStore.contactIdsWithTag(vip.id).contains("f1"))
    }

    // ─────────────────────────────────────────────────────────────
    //  BATCH 5B — регрессии подтверждённых аудитом дефектов
    // ─────────────────────────────────────────────────────────────

    /**
     * 5B-001 (CRITICAL). Аудит доказал воскрешение: реконсиляция обходит ВСЕ
     * контакты, а легаси-поле не гасилось — снятый пользователем тег
     * возвращался при следующем импорте или восстановлении.
     */
    @Test
    fun removedTag_doesNotResurrect_whenReconciliationRunsAgain() {
        // Контакт кладём в БД продакшн-путём: гашение легаси-поля — это UPDATE
        // реальной строки, на контакте, живущем только в памяти, оно бы
        // ничего не проверяло.
        runBlocking { AppStateStore.addContact(contact("r1", legacyTags = listOf("VIP"))).await() }
        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }
        val vip = AppStateStore.tags.single { it.name == "VIP" }
        assertTrue("предусловие: членство создано", managedNamesOf("r1").contains("VIP"))

        // Пользователь снимает тег с карточки (тап по чипу).
        AppStateStore.setContactTags("r1", emptySet())
        val removed = pollUntilTrue(timeoutMs = 5000) { membersInDb().none { it.contactId == "r1" } }
        if (!removed) fail("снятие членства не долетело до Room")

        // Реконсиляция повторяется — так бывает после любого импорта/restore.
        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }

        assertTrue("тег не должен вернуться в память",
            AppStateStore.tagMembers.none { it.tagId == vip.id && it.contactId == "r1" })
        assertTrue("и не должен вернуться в Room",
            membersInDb().none { it.tagId == vip.id && it.contactId == "r1" })
    }

    /** 5B-001. То же для глобального удаления: тег не пересоздаётся заново. */
    @Test
    fun globallyDeletedTag_doesNotResurrect_whenReconciliationRunsAgain() {
        runBlocking { AppStateStore.addContact(contact("r2", legacyTags = listOf("VIP"))).await() }
        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }
        val vip = AppStateStore.tags.single { it.name == "VIP" }

        AppStateStore.deleteTag(vip.id)
        val gone = pollUntilTrue(timeoutMs = 5000) { tagsInDb().none { it.id == vip.id } }
        if (!gone) fail("глобальное удаление не долетело до Room")

        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }

        assertTrue("удалённый тег не должен пересоздаться в памяти",
            AppStateStore.tags.none { it.name.equals("VIP", ignoreCase = true) })
        assertTrue("и в Room",
            tagsInDb().none { it.name.equals("VIP", ignoreCase = true) })
    }

    /** 5B-001. Легаси-поле потребляется даже когда членство уже существовало —
     *  именно этот случай раньше уходил на ранний выход и оставлял поле жить. */
    @Test
    fun reconciliation_consumesLegacyField_evenWhenMembershipAlreadyExists() {
        runBlocking { AppStateStore.addContact(contact("r3", legacyTags = listOf("VIP"))).await() }
        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }
        runBlocking { AppStateStore.reconcileLegacyTagsIntoManaged() }

        assertTrue("легаси-поле обязано быть погашено",
            AppStateStore.contacts.single { it.id == "r3" }.tags.isEmpty())
        val inDb = runBlocking { db.contactDao().getAllContacts() }.single { it.id == "r3" }
        assertTrue("и в Room тоже, tags=${inDb.tags}", inDb.tags.isNullOrBlank())
    }

    /**
     * 5B-002 (HIGH). Путь «Объединить» на экране дубликатов — второй путь
     * импорта. Он писал теги только в легаси-поле и не реконсилировал.
     * Тест зовёт РЕАЛЬНЫЙ production mergeCandidate.
     */
    @Test
    fun mergeCandidatePath_importedTagsBecomeManaged() {
        AppStateStore.contacts.add(contact("m1", first = "Пётр"))
        val cand = candidateWithTags("m1cand", "Пётр", listOf("VIP"))
            .copy(matchedContactId = "m1")

        runBlocking {
            com.aistudio.socialsphere.crmlxb.ui.screens.mergeCandidate(cand, ctx)
        }

        assertEquals("тег из merge-пути обязан стать управляемым",
            setOf("VIP"), managedNamesOf("m1"))
        assertTrue("и лежать в Room сразу после возврата",
            membersInDb().any { it.contactId == "m1" })
    }

    /**
     * 5B-005 (MEDIUM). Импорт БЕЗ тегов: реконсиляция уходит на ранний выход и
     * не даёт точки ожидания. До барьера performImport возвращал управление,
     * пока записи ещё стояли в очереди. Проверка идёт БЕЗ опроса — сразу после
     * возврата.
     */
    @Test
    fun performImport_withoutTags_isDurableOnReturn() {
        val cand = com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate(
            id = "p1cand", firstName = "Сергей", lastName = "Иванов"
        )

        runBlocking {
            com.aistudio.socialsphere.crmlxb.ui.screens.performImport(listOf(cand), ctx)
        }

        val inDb = runBlocking { db.contactDao().getAllContacts() }
        assertTrue("контакт обязан быть в Room сразу после возврата performImport, есть=${inDb.map { it.firstName }}",
            inDb.any { it.firstName == "Сергей" })
    }

    // ─────────────────────────────────────────────────────────────
    //  BATCH 5C (5C-003) — дедуп тегов при restore по логическому имени
    // ─────────────────────────────────────────────────────────────

    /**
     * Свежая установка уже содержит тег «Electrician» (создан ПРОДАКШН-путём
     * через createTag()/addTag() — реальный seeded-сценарий, а не голая
     * tags.isEmpty() precondition, подменяющая его), категория пуста.
     * Восстанавливаемый бэкап несёт ЛОГИЧЕСКИ тот же тег под ДРУГИМ id, с
     * заполненной категорией «Услуги». После restore обязан существовать
     * РОВНО один логический тег этого имени под КАНОНИЧЕСКИМ (уже
     * существовавшим) id, membership обязан указывать на этот же id, а
     * категория — быть заполнена значением из бэкапа (правило 2: у
     * существующего пусто, у бэкапа заполнено — берём из бэкапа; ИСПРАВЛЕНО —
     * раньше существующий тег не трогался вовсе и непустая категория бэкапа
     * терялась молча).
     */
    @Test
    fun restoreBackup_sameLogicalTagNameDifferentUuid_producesOneTagAndPreservesMembership() {
        val existing = createTag("Electrician")
        assertTrue("предусловие: категория существующего (seeded) тега не задана", existing.category == null)

        val backupTagId = "backup-tag-uuid-b"
        val backupContactId = "d1c"
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(contact(backupContactId)),
                tags = listOf(Tag(backupTagId, "Electrician", "Услуги", "2026-01-01T00:00", "2026-01-01T00:00")),
                tagMembers = listOf(ContactTagMember("backup-member-1", backupTagId, backupContactId))
            )
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        val logicalMatches = AppStateStore.tags.filter { it.name.equals("Electrician", ignoreCase = true) }
        assertEquals("ровно один логический тег в памяти, есть=$logicalMatches", 1, logicalMatches.size)
        val dbMatches = tagsInDb().filter { it.name.equals("Electrician", ignoreCase = true) }
        assertEquals("ровно один логический тег в Room, есть=$dbMatches", 1, dbMatches.size)

        assertEquals("выживает существовавший до restore canonical id",
            existing.id, logicalMatches.single().id)
        assertTrue("membership обязан указывать на canonical id (в памяти)",
            AppStateStore.tagMembers.any { it.tagId == existing.id && it.contactId == backupContactId })
        assertTrue("membership обязан указывать на canonical id (в Room)",
            membersInDb().any { it.tagId == existing.id && it.contactId == backupContactId })

        assertEquals("пустая категория существующего тега обязана заполниться из бэкапа",
            "Услуги", logicalMatches.single().category)
        assertEquals("и в Room тоже", "Услуги", dbMatches.single().category)
    }

    /**
     * Правило 4 той же семантики: у существующего тега категория УЖЕ
     * заполнена («Работа») — бэкап несёт другую категорию («Услуги») под
     * тем же логическим именем. Живое значение обязано победить, бэкап не
     * должен молча перезаписать существующую непустую категорию.
     */
    @Test
    fun restoreBackup_sameLogicalTagName_existingNonBlankCategory_isNotOverwrittenByBackup() {
        val existing = AppStateStore.addTag("Electrician", "Работа")
            ?: fail("addTag вернул null") as Nothing
        val landed = pollUntilTrue(timeoutMs = 5000) { tagsInDb().any { it.id == existing.id } }
        if (!landed) fail("тег «Electrician» не долетел до Room за таймаут")

        val backupTagId = "backup-tag-uuid-d"
        val backupContactId = "d3c"
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(contact(backupContactId)),
                tags = listOf(Tag(backupTagId, "Electrician", "Услуги", "2026-01-01T00:00", "2026-01-01T00:00")),
                tagMembers = listOf(ContactTagMember("backup-member-3", backupTagId, backupContactId))
            )
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        val logicalMatches = AppStateStore.tags.filter { it.name.equals("Electrician", ignoreCase = true) }
        assertEquals("ровно один логический тег", 1, logicalMatches.size)
        assertEquals("existing.category=\"Работа\" обязана победить, не быть заменена на \"Услуги\" из бэкапа",
            "Работа", logicalMatches.single().category)
        assertEquals("и в Room тоже",
            "Работа", tagsInDb().single { it.name.equals("Electrician", ignoreCase = true) }.category)
        assertTrue("membership всё равно обязан быть на canonical id",
            AppStateStore.tagMembers.any { it.tagId == existing.id && it.contactId == backupContactId })
    }

    /**
     * Идемпотентность того же сценария: повторное восстановление ТОГО ЖЕ
     * бэкапа не должно плодить второй логический тег или второе членство.
     */
    @Test
    fun restoreBackup_sameLogicalTagName_isIdempotentOnSecondRestore() {
        val existing = createTag("Electrician")
        val backupContactId = "d2c"
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(contact(backupContactId)),
                tags = listOf(Tag("backup-tag-uuid-c", "Electrician", null, "2026-01-01T00:00", "2026-01-01T00:00")),
                tagMembers = listOf(ContactTagMember("backup-member-2", "backup-tag-uuid-c", backupContactId))
            )
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }
        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        val logicalMatches = tagsInDb().filter { it.name.equals("Electrician", ignoreCase = true) }
        assertEquals("повторный restore не должен плодить второй логический тег", 1, logicalMatches.size)
        assertEquals("повторный restore не должен плодить второе членство",
            1, membersInDb().count { it.tagId == existing.id && it.contactId == backupContactId })
    }

    // ─────────────────────────────────────────────────────────────
    //  BATCH 5 (последний подтверждённый дефект) — cross-locale identity
    //  ВСТРОЕННЫХ seed-тегов при restore
    // ─────────────────────────────────────────────────────────────

    private fun namedTagMatches(vararg names: String): List<Tag> =
        AppStateStore.tags.filter { t -> names.any { t.name.equals(it, ignoreCase = true) } }

    private fun dbNamedTagMatches(vararg names: String): List<Tag> =
        tagsInDb().filter { t -> names.any { t.name.equals(it, ignoreCase = true) } }

    /**
     * Regression A: EN backup -> RU fresh install. Встроенный seed-тег
     * «Electrician»/«Электрик» имеет РАЗНЫЕ отображаемые имена в разных
     * локалях — простое trim+lowercase их не совпадёт. Restore обязан
     * распознать эту пару как ОДИН семантический тег (`seed_tag_electrician`),
     * не создавая второй.
     */
    @Test
    fun restoreBackup_crossLocaleSeedTag_enBackupIntoRuInstall_mergesToOneSemanticTag() {
        // "Свежая установка RU" — тег уже засеян под русским именем
        // (production-путь createTag(), не голая tags.isEmpty() precondition).
        val existing = createTag("Электрик")

        val backupTagId = "backup-tag-en-electrician"
        val backupContactId = "cl1c"
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(contact(backupContactId)),
                tags = listOf(Tag(backupTagId, "Electrician", null, "2026-01-01T00:00", "2026-01-01T00:00")),
                tagMembers = listOf(ContactTagMember("backup-member-cl1", backupTagId, backupContactId))
            )
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        val inMemory = namedTagMatches("Электрик", "Electrician")
        assertEquals("ровно один семантический тег в памяти, есть=$inMemory", 1, inMemory.size)
        assertEquals("канонический id — существующий (RU-install), не из бэкапа",
            existing.id, inMemory.single().id)
        assertEquals("display name НЕ должен смениться на язык бэкапа",
            "Электрик", inMemory.single().name)

        val inDb = dbNamedTagMatches("Электрик", "Electrician")
        assertEquals("ровно один и в Room, есть=$inDb", 1, inDb.size)
        assertEquals("Электрик", inDb.single().name)

        assertTrue("membership обязан указывать на существующий canonical id (в памяти)",
            AppStateStore.tagMembers.any { it.tagId == existing.id && it.contactId == backupContactId })
        assertTrue("membership обязан указывать на существующий canonical id (в Room)",
            membersInDb().any { it.tagId == existing.id && it.contactId == backupContactId })
    }

    /**
     * Regression B: RU backup -> EL fresh install. Та же логика для другой
     * пары локалей встроенного тега «Электрик»/«Ηλεκτρολόγος» — доказывает,
     * что распознавание работает для ЛЮБОЙ пары из трёх поддерживаемых
     * локалей, а не только для EN<->RU.
     */
    @Test
    fun restoreBackup_crossLocaleSeedTag_ruBackupIntoElInstall_mergesToOneSemanticTag() {
        val existing = createTag("Ηλεκτρολόγος")

        val backupTagId = "backup-tag-ru-electrician"
        val backupContactId = "cl2c"
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(contact(backupContactId)),
                tags = listOf(Tag(backupTagId, "Электрик", null, "2026-01-01T00:00", "2026-01-01T00:00")),
                tagMembers = listOf(ContactTagMember("backup-member-cl2", backupTagId, backupContactId))
            )
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        val inMemory = namedTagMatches("Ηλεκτρολόγος", "Электрик")
        assertEquals("ровно один семантический тег, есть=$inMemory", 1, inMemory.size)
        assertEquals(existing.id, inMemory.single().id)
        assertEquals("display name остаётся греческим (EL-install)", "Ηλεκτρολόγος", inMemory.single().name)

        assertTrue("membership на существующий canonical id",
            AppStateStore.tagMembers.any { it.tagId == existing.id && it.contactId == backupContactId })
        assertTrue("и в Room",
            membersInDb().any { it.tagId == existing.id && it.contactId == backupContactId })
    }

    /**
     * Regression C: пользовательские cross-language теги НЕ объединяются.
     * «Doctor»/«Врач» — реальные переводы друг друга, но НЕ входят в список
     * 15 встроенных seed-ресурсов (это RelationshipType.DOCTOR, не seed-тег).
     * Cross-locale identity применяется ТОЛЬКО к известным seed-ресурсам —
     * никакого общего словаря переводов для произвольных тегов быть не
     * должно. После restore обязаны остаться ДВА разных тега.
     */
    @Test
    fun restoreBackup_userCreatedCrossLanguageTags_doNotMerge() {
        val existingDoctor = createTag("Doctor")

        val backupTagId = "backup-tag-vrach"
        val backupContactId = "cl3c"
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(contact(backupContactId)),
                tags = listOf(Tag(backupTagId, "Врач", null, "2026-01-01T00:00", "2026-01-01T00:00")),
                tagMembers = listOf(ContactTagMember("backup-member-cl3", backupTagId, backupContactId))
            )
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        val inMemory = namedTagMatches("Doctor", "Врач")
        assertEquals("«Doctor» и «Врач» — пользовательские, обязаны остаться ДВУМЯ тегами, есть=$inMemory",
            2, inMemory.size)
        assertTrue(inMemory.any { it.id == existingDoctor.id && it.name == "Doctor" })
        assertTrue(inMemory.any { it.id == backupTagId && it.name == "Врач" })
        assertTrue("membership бэкапа обязан указывать на СВОЙ (не existing) id — тег генуинно новый",
            AppStateStore.tagMembers.any { it.tagId == backupTagId && it.contactId == backupContactId })
    }

    /**
     * Regression F: идемпотентность cross-locale сценария — повторный
     * restore того же EN-бэкапа в RU-установку не плодит второй тег/членство.
     */
    @Test
    fun restoreBackup_crossLocaleSeedTag_isIdempotentOnSecondRestore() {
        val existing = createTag("Электрик")
        val backupTagId = "backup-tag-en-electrician-f"
        val backupContactId = "cl4c"
        val backup = ExportManager.backupAdapter.toJson(
            BackupData(
                contacts = listOf(contact(backupContactId)),
                tags = listOf(Tag(backupTagId, "Electrician", null, "2026-01-01T00:00", "2026-01-01T00:00")),
                tagMembers = listOf(ContactTagMember("backup-member-cl4", backupTagId, backupContactId))
            )
        )

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }
        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        val inDb = dbNamedTagMatches("Электрик", "Electrician")
        assertEquals("повторный cross-locale restore не должен плодить второй тег", 1, inDb.size)
        assertEquals("Электрик", inDb.single().name)
        assertEquals("повторный cross-locale restore не должен плодить второе членство",
            1, membersInDb().count { it.tagId == existing.id && it.contactId == backupContactId })
    }

    // ─────────────────────────────────────────────────────────────
    //  BATCH 5 (последний подтверждённый дефект) — ЕДИНАЯ семантика
    //  идентичности встроенных тегов у addTag() и restoreBackup()
    // ─────────────────────────────────────────────────────────────

    /**
     * Regression A: addTag alias consistency. На RU-установке уже есть
     * встроенный тег «Электрик». Пользователь через продакшн-путь просит
     * addTag("Electrician") — это ТО ЖЕ встроенное семантическое понятие
     * (seed_tag_electrician), только другое локализованное имя. Раньше
     * addTag() сравнивал лишь текст имени и создавал ВТОРУЮ строку, а
     * restoreBackup() те же два имени считал одним тегом — прямое
     * противоречие между путём создания и путём восстановления.
     */
    @Test
    fun addTag_localizedAliasOfExistingSeedTag_returnsExistingTag() {
        val existing = createTag("Электрик")

        val returned = AppStateStore.addTag("Electrician")

        assertNotNull("addTag не должен вернуть null для известного seed-алиаса", returned)
        assertEquals("должен вернуться СУЩЕСТВУЮЩИЙ canonical id", existing.id, returned!!.id)
        assertEquals("display name существующего тега не меняется", "Электрик", returned.name)
        assertEquals("новый Tag не создан (в памяти)",
            1, namedTagMatches("Электрик", "Electrician").size)
        assertEquals("новый Tag не создан (в Room)",
            1, dbNamedTagMatches("Электрик", "Electrician").size)
    }

    /**
     * Regression B: то же для другой пары локалей — существующий тег на
     * греческом, запрос на русском. Доказывает, что правило не про
     * «английский против локального», а про семантический ключ ресурса.
     */
    @Test
    fun addTag_localizedAliasOfExistingSeedTag_otherLocalePair_returnsExistingTag() {
        val existing = createTag("Ηλεκτρολόγος")

        val returned = AppStateStore.addTag("Электрик")

        assertEquals(existing.id, returned?.id)
        assertEquals("display name остаётся греческим", "Ηλεκτρολόγος", returned?.name)
        assertEquals("второго тега быть не должно",
            1, namedTagMatches("Ηλεκτρολόγος", "Электрик", "Electrician").size)
    }

    /**
     * Regression C: обычные пользовательские теги, даже будучи переводами друг
     * друга, НЕ объединяются — cross-locale идентичность применяется только к
     * известным встроенным seed-ресурсам. Никакого общего словаря переводов
     * пользовательских тегов и никакого fuzzy-сопоставления.
     */
    @Test
    fun addTag_userCreatedTranslations_remainSeparateTags() {
        val doctor = createTag("Doctor")
        val vrach = createTag("Врач")
        val investor = createTag("Investor")
        val investorRu = createTag("Инвестор")

        assertTrue("«Doctor» и «Врач» обязаны быть разными тегами", doctor.id != vrach.id)
        assertTrue("«Investor» и «Инвестор» обязаны быть разными тегами", investor.id != investorRu.id)
        assertEquals("в памяти должны остаться все четыре пользовательских тега", 4,
            namedTagMatches("Doctor", "Врач", "Investor", "Инвестор").size)
        assertEquals("и в Room тоже", 4,
            dbNamedTagMatches("Doctor", "Врач", "Investor", "Инвестор").size)
        assertTrue("«Doctor» не должен считаться встроенным seed-тегом",
            AppStateStore.seedTagSemanticKey("Doctor") == null)
        assertTrue("«Врач» не должен считаться встроенным seed-тегом",
            AppStateStore.seedTagSemanticKey("Врач") == null)
    }

    /**
     * Regression D: create -> backup -> restore consistency. Тег, полученный
     * через alias-add (addTag("Electrician") на RU-установке), проходит
     * РЕАЛЬНЫЙ продакшн-бэкап (ExportManager.backupJsonString) и продакшн-
     * восстановление — и остаётся ОДНИМ семантическим тегом с тем же
     * canonical id и живым членством. Повторное восстановление идемпотентно.
     */
    @Test
    fun addTagAlias_thenBackupAndRestore_keepsOneSemanticTagAndMemberships() {
        val existing = createTag("Электрик")
        val aliasAdded = AppStateStore.addTag("Electrician")
        assertEquals("предусловие: alias-add вернул существующий тег", existing.id, aliasAdded?.id)

        val c = contact("d4c")
        runBlocking { AppStateStore.addContact(c).await() }
        AppStateStore.setContactTags(c.id, setOf(existing.id))
        val attached = pollUntilTrue(timeoutMs = 5000) {
            membersInDb().any { it.tagId == existing.id && it.contactId == c.id }
        }
        if (!attached) fail("членство не долетело до Room за таймаут")

        val backup = awaitOffMainThread { ExportManager.backupJsonString() }

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        assertEquals("после restore — один семантический тег",
            1, namedTagMatches("Электрик", "Electrician").size)
        assertEquals("canonical id сохранён", existing.id, namedTagMatches("Электрик").single().id)
        assertEquals("членство сохранено и не задвоено",
            1, membersInDb().count { it.tagId == existing.id && it.contactId == c.id })

        awaitOffMainThread { ExportManager.importJsonBackup(backup) }

        assertEquals("повторный restore идемпотентен по тегам",
            1, dbNamedTagMatches("Электрик", "Electrician").size)
        assertEquals("повторный restore идемпотентен по членству",
            1, membersInDb().count { it.tagId == existing.id && it.contactId == c.id })
    }

    /**
     * МАСШТАБИРУЕМОСТЬ ПО ЯЗЫКАМ и ПО ВСТРОЕННЫМ ТЕГАМ. Тест не содержит
     * ни своего списка языков, ни своего списка тегов: он перебирает ДВА
     * канонических продакшн-реестра — AppLanguage (реестр языков приложения, им же
     * управляется экран выбора языка) и AppStateStore.SEED_TAG_DEFINITIONS
     * (реестр встроенных тегов, из которого работает и сам сид).
     *
     * Отсюда доказательство требуемого критерия: новый язык = новый AppLanguage +
     * values-xx/strings.xml, новый встроенный тег = одна строка в
     * SEED_TAG_DEFINITIONS — и то и другое попадает под эту проверку автоматически,
     * без правок здесь, в addTag(), в restoreBackup(), в seedTagSemanticKey() или в
     * localizedVariants(). Если же кто-то заведёт для тегов/локализации ВТОРОЙ
     * список и забудет его синхронизировать, тест упадёт на первом же
     * расхождении.
     */
    @Test
    fun seedTagResolver_recognizesEverySeedTag_inEverySupportedLanguage() {
        val languages = AppLanguage.values()
        assertTrue("канонический реестр языков не должен быть пустым", languages.isNotEmpty())
        assertTrue("реестр встроенных тегов не должен быть пустым",
            AppStateStore.SEED_TAG_DEFINITIONS.isNotEmpty())

        languages.forEach { language ->
            val localeCtx = run {
                val conf = android.content.res.Configuration(ctx.resources.configuration)
                conf.setLocale(java.util.Locale(language.code))
                ctx.createConfigurationContext(conf)
            }
            AppStateStore.SEED_TAG_DEFINITIONS.forEach { def ->
                val displayName = localeCtx.getString(def.nameRes)
                assertEquals(
                    "язык «${language.code}»: отображаемое имя «$displayName» обязано разрешаться " +
                        "в семантический ключ «${def.semanticKey}»",
                    def.semanticKey,
                    AppStateStore.seedTagSemanticKey(displayName)
                )
                // Регистр и лишние пробелы не должны ломать распознавание —
                // нормализация та же, что у обычного совпадения по имени.
                assertEquals("язык «${language.code}»: нормализация (регистр/пробелы) для «$displayName»",
                    def.semanticKey,
                    AppStateStore.seedTagSemanticKey("  " + displayName.uppercase() + "  "))
            }
        }

        assertEquals("каждый встроенный тег обязан давать свой уникальный семантический ключ",
            AppStateStore.SEED_TAG_DEFINITIONS.size,
            AppStateStore.SEED_TAG_DEFINITIONS.map { it.semanticKey }.distinct().size)
        assertTrue("произвольное пользовательское имя не должно попадать в seed-идентичности",
            AppStateStore.seedTagSemanticKey("Совершенно произвольный тег") == null)
    }

    /**
     * А этот тест закрывает дыру, которую тест выше НЕ может заметить принципиально:
     * он берёт языки из того же AppLanguage, что и продакшн, и потому не может
     * обнаружить, что сам AppLanguage неполон относительно реально
     * переведённых ресурсов (та же ловушка, из-за которой прошлый тест не
     * поймал расхождение APP_LANGS и AppLanguage).
     *
     * Источник ожидаемого здесь НЕЗАВИСИМ от проверяемого кода — это
     * файлы переводов на диске (res/values-xx/strings.xml). Связь обязана быть
     * двусторонней:
     *  - есть values-fr/strings.xml, но нет AppLanguage.FRENCH — перевод есть, а
     *    приложение (и seed-resolver) этого языка не видит;
     *  - есть AppLanguage.FRENCH, но нет values-fr/ — язык предлагается в настройках,
     *    а строк для него нет.
     * Язык по умолчанию живёт в res/values/ без квалификатора — ровно один
     * такой язык допускается без своего values-xx/.
     */
    @Test
    fun appLanguageRegistry_matchesTranslationResourcesOnDisk() {
        val resDir = resDirOrFail()
        val qualifiedLocales = (resDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .filter { java.io.File(it, "strings.xml").isFile }
            .map { it.name.removePrefix("values-") }
            // Не-языковые квалификаторы (values-night, values-v26, values-sw600dp…)
            // к языкам отношения не имеют: код языка — две буквы (возможно
            // с регионом, например pt-rBR).
            .filter { Regex("^[a-z]{2}(-r[A-Z]{2})?$").matches(it) }
            .map { it.substringBefore("-r") }
            .distinct()

        assertTrue("не нашлось ни одного values-xx/strings.xml — тест был бы пустым; " +
            "проверь путь к res/ (найден: $resDir)", qualifiedLocales.isNotEmpty())

        val registryCodes = AppLanguage.values().map { it.code }.toSet()

        qualifiedLocales.forEach { code ->
            assertTrue(
                "есть перевод res/values-$code/strings.xml, но в AppLanguage нет записи с code=«$code» — " +
                    "приложение и распознавание встроенных тегов этот язык не увидят. " +
                    "Есть в реестре: $registryCodes",
                code in registryCodes
            )
        }

        val withoutOwnDir = registryCodes - qualifiedLocales.toSet()
        assertTrue(
            "языки из AppLanguage без своего res/values-xx/strings.xml: $withoutOwnDir — без " +
                "квалификатора в res/values/ может жить РОВНО ОДИН (язык по умолчанию)",
            withoutOwnDir.size <= 1
        )
    }

    /** Каталог res/ проекта — независимый от проверяемого кода источник
     *  истины о том, какие языки реально переведены. Ищется от рабочего
     *  каталога вверх по дереву, чтобы не зависеть от того, из какого каталога
     *  Gradle запустил тесты; признак найденного каталога — наличие
     *  values/strings.xml, иначе тест честно падает, а не проходит вхолостую. */
    private fun resDirOrFail(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 6) {
            for (rel in listOf("src/main/res", "app/src/main/res", "ss-v1/app/src/main/res")) {
                val candidate = java.io.File(dir, rel)
                if (java.io.File(candidate, "values/strings.xml").isFile) return candidate
            }
            dir = dir.parentFile
            hops++
        }
        fail("не нашёл каталог res/ с values/strings.xml, начиная от user.dir=" +
            System.getProperty("user.dir"))
        error("unreachable")
    }

    // ─────────────────────────────────────────────────────────────
    //  BATCH 5C (5C-004) — comma-safe vCard round-trip тегов
    // ─────────────────────────────────────────────────────────────

    /**
     * Тег с запятой внутри имени переживает ПОЛНЫЙ продакшн круг: управляемый
     * тег -> ExportManager.exportVCard() -> ContactImporter.parseVCard() ->
     * performImport(). Раньше «Sales, Europe» возвращался как ДВА тега
     * («Sales» и «Europe») — join(", ")/split(",") симметрично не различали
     * разделитель между тегами и запятую внутри одного имени.
     */
    @Test
    fun commaInTagName_survivesFullVCardExportImportRoundTrip() {
        // parseVCard() отбрасывает карточки без телефона/email (существующее,
        // не связанное с этим фиксом правило — «карточка без контактов»,
        // ContactImporterTest.kt) — контакту нужен хотя бы один способ связи,
        // иначе экспорт даст валидный .vcf, но импорт вернёт 0 кандидатов.
        val c = contact("e1").copy(phones = listOf(
            ContactPhone(id = "e1-phone", contactId = "e1", number = "+1 555 0100", type = PhoneType.MOBILE, isPrimary = true)
        ))
        // EXPORT SNAPSHOT CONSISTENCY PACK: экспорт читает Room, не живую память — реальный коммит.
        runBlocking { AppStateStore.addContact(c).await() }
        val tag = createTag("Sales, Europe")
        runBlocking { AppStateStore.setContactTags(c.id, setOf(tag.id)).await() }

        val vcf = runBlocking { ExportManager.exportVCard(ctx) }.readText()
        val candidates = com.aistudio.socialsphere.crmlxb.utils.parseVCard(vcf)
        assertTrue("экспорт обязан дать хотя бы одного кандидата", candidates.isNotEmpty())

        // Честная чистая установка — как импорт на другом устройстве.
        restartOnEmptyDb()

        runBlocking {
            com.aistudio.socialsphere.crmlxb.ui.screens.performImport(candidates, ctx)
        }

        val imported = AppStateStore.contacts.single()
        assertEquals("«Sales, Europe» обязан вернуться ОДНИМ тегом, а не двумя",
            setOf("Sales, Europe"), managedNamesOf(imported.id))
    }

    /**
     * Обратная совместимость: старый ", "-разделённый payload (снятый ДО
     * этого фикса, где имена тегов никогда не содержали запятую и потому
     * никогда не экранировались) обязан продолжать импортироваться как
     * НЕСКОЛЬКО отдельных тегов — сценарий F. Тест идёт через реальный
     * ContactNoteCodec.decode(), которым в проде пользуется performImport,
     * а не через сырое разбиение вручную.
     */
    @Test
    fun oldCommaSeparatedTagsFormat_stillImportsAsMultipleTags() {
        val legacyNote = "[Теги] VIP, Supplier, Tennis"

        val decoded = ContactNoteCodec.decode(legacyNote)

        assertEquals(listOf("VIP", "Supplier", "Tennis"), decoded.tags)
    }

    // ═════════════════════════════════════════════════════════════
    //  LEGACY TAGS COMPATIBILITY & PHONE SYNC SAFETY PACK (2026-09-23)
    //  PART A — повторный phone/VCF resync НЕ должен обходить managed membership
    //  (Data Safety Residual Audit, TAG-1: ImportScreens.kt:1116/1129 сравнивал/писал
    //  ЛЕГАСИ-поле, которое у уже сверенного контакта всегда пусто — сравнение всегда
    //  «изменилось», reconcile потом создавал membership заново).
    // ═════════════════════════════════════════════════════════════

    /** Кандидат, СВЯЗАННЫЙ с записью телефона (id с префиксом device_contact_) — именно так
     *  performImport() проставляет deviceContactId; без префикса «уже связан ли контакт с
     *  телефоном» неотличимо от первого импорта, и новый фикс (gate по deviceContactId) не сработал бы. */
    private fun phoneLinkedCandidateWithTags(deviceId: String, first: String, tags: List<String>) =
        com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate(
            id = "device_contact_$deviceId", firstName = first, lastName = "Петров",
            notes = ContactNoteCodec.encode(contact("tmp", legacyTags = tags))
        )

    /** T1: контакт импортирован из phone/VCF с тегом VIP, пользователь снял VIP управляемо,
     *  повторный resync ТОГО ЖЕ phone-контакта (источник всё ещё несёт VIP) — VIP не возвращается. */
    @Test
    fun tagsPartA_T1_removedManagedTag_doesNotReturnAfterPhoneResync() {
        val cand = phoneLinkedCandidateWithTags("a1", "Алиса", listOf("VIP"))
        runBlocking { com.aistudio.socialsphere.crmlxb.ui.screens.performImport(listOf(cand), ctx) }
        val contactA = AppStateStore.contacts.single { it.firstName == "Алиса" }
        assertEquals(setOf("VIP"), managedNamesOf(contactA.id))
        assertNotNull("предусловие: контакт реально связан с телефоном", contactA.deviceContactId)

        runBlocking { AppStateStore.setContactTags(contactA.id, emptySet()).await() }
        assertTrue("предусловие: VIP снят", managedNamesOf(contactA.id).isEmpty())

        val resync = phoneLinkedCandidateWithTags("a1", "Алиса", listOf("VIP")).copy(matchedContactId = contactA.id)
        runBlocking { com.aistudio.socialsphere.crmlxb.ui.screens.mergeCandidate(resync, ctx) }

        assertTrue("VIP не должен вернуться автоматически после повторного phone/VCF resync",
            managedNamesOf(contactA.id).isEmpty())
    }

    /** T3: у существующего (уже связанного с телефоном) контакта есть свой managed-тег Business —
     *  повторный resync с ДРУГИМ тегом в источнике (VIP) не трогает Business и не добавляет VIP. */
    @Test
    fun tagsPartA_T3_existingContactResync_preservesUntouchedManagedTags() {
        val cand = phoneLinkedCandidateWithTags("b1", "Борис", emptyList())
        runBlocking { com.aistudio.socialsphere.crmlxb.ui.screens.performImport(listOf(cand), ctx) }
        val contactB = AppStateStore.contacts.single { it.firstName == "Борис" }
        val biz = createTag("Business")
        runBlocking { AppStateStore.setContactTags(contactB.id, setOf(biz.id)).await() }
        assertEquals(setOf("Business"), managedNamesOf(contactB.id))

        val resync = phoneLinkedCandidateWithTags("b1", "Борис", listOf("VIP")).copy(matchedContactId = contactB.id)
        runBlocking { com.aistudio.socialsphere.crmlxb.ui.screens.mergeCandidate(resync, ctx) }

        assertEquals("Business остаётся нетронутым, VIP не появляется автоматически из resync",
            setOf("Business"), managedNamesOf(contactB.id))
    }

    // T2 (первый импорт всё ещё конвертирует легаси-теги в managed) уже покрыт существующим
    // legacyTagsFromImport_becomeManagedTagsAndMemberships (выше в этом файле) — не дублируем.

    // ═════════════════════════════════════════════════════════════
    //  PART B — RestoreMerge (generic-merge) не должен обходить Restore Preview для НЕПУСТОЙ БД
    //  (Data Safety Residual Audit, TAG-2: RestoreMerge.kt:382 трактовал Contact.tags как обычное
    //  скалярное поле generic-merge'а — пустое current заполнялось из backup, дальше reconcile
    //  создавал membership без единого выбора пользователя).
    // ═════════════════════════════════════════════════════════════

    /** T12: восстановление НЕПУСТОЙ БД (MERGE-режим) с backup, чьи теги есть ТОЛЬКО в легаси-поле —
     *  generic RestoreMerge не должен молча создать managed-тег без выбора пользователя (это работа
     *  Restore Preview, не автоматического merge). */
    @Test
    fun tagsPartB_T12_restoreMergeCannotSilentlyCreateUnselectedLegacyTag() {
        runBlocking { AppStateStore.addContact(contact("d1", first = "Дмитрий")).await() }
        // Посторонний контакт со своим тегом — должен остаться нетронутым (requirement T14).
        runBlocking { AppStateStore.addContact(contact("dOther", first = "Другой")).await() }
        val otherTag = createTag("Colleague")
        runBlocking { AppStateStore.setContactTags("dOther", setOf(otherTag.id)).await() }

        val bakContact = contact("d1", first = "Дмитрий", legacyTags = listOf("VIP"))
        awaitOffMainThread {
            AppStateStore.restoreBackup(RestorePlan(contacts = listOf(bakContact)), authorization = RestoreAuthorization.SafeMerge)
        }

        assertTrue("MERGE restore не должен молча создавать managed-тег из легаси-поля backup",
            managedNamesOf("d1").isEmpty())
        assertEquals("посторонний контакт и его тег не изменились",
            setOf("Colleague"), managedNamesOf("dOther"))
    }

    // ═════════════════════════════════════════════════════════════
    //  PART E — mergeContacts не должен воскрешать залежавшийся легаси-тег стороннего контакта
    //  (Data Safety Residual Audit, TAG-3: AppStateStore.kt:1496 объединял ЛЕГАСИ-поля union'ом
    //  всех сливаемых контактов — избыточный путь: managed-теги и так объединяются отдельно,
    //  корректно, через contactTagMembershipDiff чуть ниже).
    // ═════════════════════════════════════════════════════════════

    /** T13: keep-контакт имеет managed Business; сливаемый контакт несёт НИКОГДА не реконсилированный
     *  легаси VIP — после merge managed-теги survivor'а — только исходный Business, VIP не воскрес. */
    @Test
    fun tagsPartE_T13_mergeContactsDoesNotResurrectStaleLegacyTag() {
        AppStateStore.contacts.add(contact("e1", first = "Ева"))
        val biz = createTag("Business")
        runBlocking { AppStateStore.setContactTags("e1", setOf(biz.id)).await() }
        AppStateStore.contacts.add(contact("e2", first = "Ева2", legacyTags = listOf("VIP")))

        AppStateStore.mergeContacts(listOf("e1", "e2"))
        runBlocking { AppStateStore.awaitPendingWrites() }

        assertEquals("managed-теги — только исходный Business; VIP из легаси e2 не воскрес",
            setOf("Business"), managedNamesOf("e1"))
    }

    // ═════════════════════════════════════════════════════════════
    //  PART D — fresh restore ПО-ПРЕЖНЕМУ конвертирует legacy-only backup-теги в managed
    //  (уже работает через существующий безусловный AppStateStore.reconcileLegacyTagsIntoManaged()
    //  в restoreBackup() — тест ПОДТВЕРЖДАЮЩИЙ, не OLD FAIL: фиксирует контракт Part D явно).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun tagsPartD_T9_freshRestore_convertsLegacyOnlyTagsIntoManaged() {
        restartOnEmptyDb()
        val bakContact = contact("f1", first = "Фёдор", legacyTags = listOf("VIP", "Investor"))
        val json = ExportManager.backupAdapter.toJson(BackupData(contacts = listOf(bakContact)))

        val outcome = awaitOffMainThread { ExportManager.importJsonBackupDetailed(json) }
        val result = (outcome as? ExportManager.BackupImportOutcome.Restored)?.result
            ?: fail("ожидался Restored, получено $outcome") as Nothing
        assertEquals(RestoreMode.FRESH, result.mode)

        assertEquals(setOf("VIP", "Investor"), managedNamesOf("f1"))

        AppStateStore.resetForTests()
        AppStateStore.initialize(ctx, db)
        pollUntilTrue { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); !AppStateStore.isLoading }
        assertEquals("T11: переживает переинициализацию из той же Room-базы",
            setOf("VIP", "Investor"), managedNamesOf("f1"))
    }
}
