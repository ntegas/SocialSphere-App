package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22) — чистая логика движка сравнения
 * ([RestoreDiffEngine.analyze]/[RestoreDiffEngine.buildApplyPlan]/[RestoreDiffEngine.summarize]/[filtered]).
 *
 * Намеренно БЕЗ Robolectric/Room: движок не зависит от БД и Compose (требование пакета), поэтому его
 * инвариант — «CURRENT + BACKUP -> DIFFERENCE ANALYSIS -> USER SELECTION -> ATOMIC APPLY» — проверяется
 * напрямую на [PersistedState]/[RestorePlan], без обвязки Room/AppStateStore. DB-round-trip часть той же
 * матрицы (T10 полностью, T12 полностью, T16-T19) — в [RestorePreviewIntegrationTest] (Robolectric).
 *
 * Нумерация тестов — литеральный список требований владельца (T1..T20).
 */
class RestoreDiffEngineTest {

    private val t0 = "2026-01-01T00:00:00"

    // ─── фабрики минимальных доменных объектов ────────────────────────────────

    private fun contact(id: String, first: String = "Alex", last: String = "Johnson") = Contact(
        id = id, firstName = first, lastName = last, photoUri = null, relationshipType = RelationshipType.FRIEND,
        connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t0, updatedAt = t0,
    )

    private fun company(id: String, name: String = "Acme") =
        Company(id = id, name = name, industry = Industry.IT, createdAt = t0, updatedAt = t0)

    private fun note(id: String, contactId: String, text: String) =
        Note(id = id, contactId = contactId, type = NoteType.GENERAL, text = text, isImportant = false, createdAt = t0, updatedAt = t0)

    private fun gift(id: String, contactId: String, title: String) =
        GiftIdea(id = id, contactId = contactId, title = title, status = GiftStatus.IDEA)

    private fun phone(id: String, contactId: String, number: String) =
        ContactPhone(id = id, contactId = contactId, number = number, type = PhoneType.MOBILE, isPrimary = true)

    private fun tag(id: String, name: String) = Tag(id = id, name = name, createdAt = t0, updatedAt = t0)
    private fun tagMember(id: String, tagId: String, contactId: String) = ContactTagMember(id = id, tagId = tagId, contactId = contactId)

    private fun persisted(
        contacts: List<Contact> = emptyList(), companies: List<Company> = emptyList(),
        calendarItems: List<CalendarItem> = emptyList(), notes: List<Note> = emptyList(),
        gifts: List<GiftIdea> = emptyList(), tombstones: List<Tombstone> = emptyList(),
        tags: List<Tag> = emptyList(), tagMembers: List<ContactTagMember> = emptyList(),
    ) = PersistedState(
        contacts = contacts, companies = companies, calendarItems = calendarItems, notes = notes, gifts = gifts,
        companyRelations = emptyList(), contactRelations = emptyList(), addresses = emptyList(), sizeInfos = emptyList(),
        personalDetails = emptyList(), groups = emptyList(), groupMembers = emptyList(), tags = tags,
        tagMembers = tagMembers, tombstones = tombstones, schemaVersion = 22,
    )

    // ═════════════════════════════════════════════════════════════
    //  T1 — одинаковое значение не показывается в preview
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t1_sameValue_absentFromPreview() {
        val cur = contact("c1").copy(profession = "Engineer")
        val bak = contact("c1").copy(profession = "Engineer")
        val preview = RestoreDiffEngine.analyze(persisted(contacts = listOf(cur)), RestorePlan(contacts = listOf(bak)))
        assertTrue("одинаковые данные не порождают ни одной группы/разницы", preview.groups.isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T2 — телефон, известный только backup, показан как Missing
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t2_backupOnlyPhone_shownAsMissing() {
        val cur = contact("c1")
        val bak = contact("c1").copy(phones = listOf(phone("p1", "c1", "+30123456")))
        val preview = RestoreDiffEngine.analyze(persisted(contacts = listOf(cur)), RestorePlan(contacts = listOf(bak)))
        val group = preview.groups.single()
        assertFalse("контакт существует — это НЕ whole-missing", group.wholeEntityMissing)
        val diff = group.differences.single()
        assertEquals(RestoreFieldKind.PHONE, diff.fieldKind)
        assertEquals(RestoreDifferenceType.MISSING, diff.differenceType)
        assertNull(diff.currentValue)
        assertEquals("+30123456", diff.backupValue)
    }

    // ═════════════════════════════════════════════════════════════
    //  T3 — заметка, известная только backup, показана как Missing
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t3_backupOnlyNote_shownAsMissing() {
        val cur = contact("c1")
        val bak = contact("c1").copy(notes = listOf(note("n1", "c1", "Met at Cisco")))
        val preview = RestoreDiffEngine.analyze(persisted(contacts = listOf(cur)), RestorePlan(contacts = listOf(bak)))
        val diff = preview.groups.single().differences.single()
        assertEquals(RestoreFieldKind.WHOLE_NOTE, diff.fieldKind)
        assertEquals(RestoreDifferenceType.MISSING, diff.differenceType)
        assertEquals("Met at Cisco", diff.backupValue)
    }

    // ═════════════════════════════════════════════════════════════
    //  T4 — различающееся скалярное поле показано как Conflict
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t4_differingScalar_shownAsConflict() {
        val cur = contact("c1").copy(profession = "Sales Manager")
        val bak = contact("c1").copy(profession = "Account Executive")
        val preview = RestoreDiffEngine.analyze(persisted(contacts = listOf(cur)), RestorePlan(contacts = listOf(bak)))
        val diff = preview.groups.single().differences.single()
        assertEquals(RestoreDifferenceType.CONFLICT, diff.differenceType)
        assertEquals("Sales Manager", diff.currentValue)
        assertEquals("Account Executive", diff.backupValue)
    }

    // ═════════════════════════════════════════════════════════════
    //  T5 — конфликт по умолчанию оставляет текущее (ничего не выбрано -> ничего не применяется)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t5_conflict_defaultsToKeepCurrent() {
        val cur = contact("c1").copy(profession = "Sales Manager")
        val bak = contact("c1").copy(profession = "Account Executive")
        val current = persisted(contacts = listOf(cur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys = emptySet())
        assertTrue("ни один conflict не выбран — scalar-обновлений нет", applyPlan.scalarContactUpdates.isEmpty())
        assertEquals(0, applyPlan.totalSelected)
    }

    // ═════════════════════════════════════════════════════════════
    //  T6 — пользователь выбирает backup-значение конфликта -> применяется backup-значение
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t6_selectedConflict_backupValueApplied() {
        val cur = contact("c1").copy(profession = "Sales Manager")
        val bak = contact("c1").copy(profession = "Account Executive")
        val current = persisted(contacts = listOf(cur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val diff = preview.groups.single().differences.single()
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys = setOf(diff.key))
        assertEquals("Account Executive", applyPlan.scalarContactUpdates.single().profession)
        assertEquals("copy() исходного контакта, не голая пересборка — id/имя целы",
            "c1" to "Alex", applyPlan.scalarContactUpdates.single().id to applyPlan.scalarContactUpdates.single().firstName)
    }

    // ═════════════════════════════════════════════════════════════
    //  T7 — невыбранная Missing остаётся отсутствующей
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t7_unselectedMissing_remainsAbsent() {
        val cur = contact("c1")
        val bak = contact("c1").copy(phones = listOf(phone("p1", "c1", "+30123456")))
        val current = persisted(contacts = listOf(cur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys = emptySet())
        assertTrue(applyPlan.childPhones.isEmpty())
        assertEquals(0, applyPlan.totalSelected)
    }

    // ═════════════════════════════════════════════════════════════
    //  T8 — выбранная Missing добавляется
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t8_selectedMissing_added() {
        val cur = contact("c1")
        val bak = contact("c1").copy(phones = listOf(phone("p1", "c1", "+30123456")))
        val current = persisted(contacts = listOf(cur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val diff = preview.groups.single().differences.single()
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys = setOf(diff.key))
        assertEquals(listOf("+30123456"), applyPlan.childPhones.map { it.number })
    }

    // ═════════════════════════════════════════════════════════════
    //  T9 — tombstoned-заметка показана как Previously Deleted, а не просто Missing
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t9_tombstonedNote_shownAsPreviouslyDeleted() {
        val cur = contact("c1")
        val bak = contact("c1").copy(notes = listOf(note("n1", "c1", "старая заметка")))
        val current = persisted(contacts = listOf(cur), tombstones = listOf(Tombstone(TombstoneEntityType.NOTE, "n1", t0)))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val diff = preview.groups.single().differences.single()
        assertEquals(RestoreDifferenceType.MISSING, diff.differenceType)
        assertTrue("tombstone — контекст, не блок: разница всё равно MISSING", diff.previouslyDeleted)
    }

    // ═════════════════════════════════════════════════════════════
    //  T10 (план) — выбор tombstoned-заметки восстанавливает её И планирует снятие tombstone
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t10_selectedDeletedNote_planRestoresAndClearsTombstone() {
        val cur = contact("c1")
        val bak = contact("c1").copy(notes = listOf(note("n1", "c1", "старая заметка")))
        val current = persisted(contacts = listOf(cur), tombstones = listOf(Tombstone(TombstoneEntityType.NOTE, "n1", t0)))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val diff = preview.groups.single().differences.single()
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys = setOf(diff.key))
        assertEquals(listOf("старая заметка"), applyPlan.standaloneNotes.map { it.text })
        assertEquals(listOf(TombstoneKey(TombstoneEntityType.NOTE, "n1")), applyPlan.tombstonesToClear)
    }

    // ═════════════════════════════════════════════════════════════
    //  T11 (план) — невыбранная удалённая заметка остаётся удалённой, tombstone не трогается
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t11_unselectedDeletedNote_planLeavesItDeleted() {
        val cur = contact("c1")
        val bak = contact("c1").copy(notes = listOf(note("n1", "c1", "старая заметка")))
        val current = persisted(contacts = listOf(cur), tombstones = listOf(Tombstone(TombstoneEntityType.NOTE, "n1", t0)))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys = emptySet())
        assertTrue(applyPlan.standaloneNotes.isEmpty())
        assertTrue(applyPlan.tombstonesToClear.isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T12 (план) — полностью отсутствующий Contact восстанавливается СО своими зависимостями,
    //  атомарно (requirement 11/18): выбор один — «Restore contact» — тянет все дочерние ряды.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t12_missingContact_restoredWithDependencies_noOrphan() {
        val bakPhone = phone("p1", "c1", "+30123456")
        val bakNote = note("n1", "c1", "заметка")
        val bakGift = gift("g1", "c1", "Whisky")
        val bak = contact("c1").copy(phones = listOf(bakPhone), notes = listOf(bakNote), gifts = listOf(bakGift))
        val current = persisted()
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val group = preview.groups.single()
        assertTrue(group.wholeEntityMissing)
        assertEquals("3 информационные строки: телефон/заметка/подарок", 3, group.childSummaryLines.size)
        assertTrue(group.differences.isEmpty()) // для missing-владельца нет отдельных чекбоксов у детей

        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selectedKeys = setOf(group.wholeKey!!))
        val restored = applyPlan.wholeContacts.single()
        assertEquals("телефон приехал ВМЕСТЕ с контактом, не отдельно", listOf(bakPhone), restored.phones)
        assertEquals(listOf(bakNote), restored.notes)
        assertEquals(listOf(bakGift), restored.gifts)
        // не сироты и не дубли: standalone-списки для этих детей пусты, они только внутри restored-контакта
        assertTrue(applyPlan.standaloneNotes.isEmpty())
        assertTrue(applyPlan.standaloneGifts.isEmpty())
        assertTrue(applyPlan.childPhones.isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T13 — поиск фильтрует корректные группы (имя, значение дочернего поля)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t13_search_filtersCorrectGroups() {
        val alexCur = contact("c1", first = "Alex", last = "Johnson")
        val ninaCur = contact("c2", first = "Nina", last = "Petrova")
        val alexBak = alexCur.copy(phones = listOf(phone("p1", "c1", "+301111111")))
        val ninaBak = ninaCur.copy(phones = listOf(phone("p2", "c2", "+302222222")))
        val current = persisted(contacts = listOf(alexCur, ninaCur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(alexBak, ninaBak)))
        assertEquals(2, preview.groups.size)

        assertEquals("поиск по имени владельца", listOf("c1"), preview.filtered(RestorePreviewFilter.ALL, "Alex").map { it.ownerId })
        assertEquals("поиск по значению дочернего поля (телефон)",
            listOf("c2"), preview.filtered(RestorePreviewFilter.ALL, "302222222").map { it.ownerId })
        assertTrue("нерелевантный запрос не находит ничего", preview.filtered(RestorePreviewFilter.ALL, "нет такого").isEmpty())
    }

    // ═════════════════════════════════════════════════════════════
    //  T14 — фильтр по типу сущности работает
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t14_typeFilter_works() {
        val contactCur = contact("c1")
        val contactBak = contactCur.copy(phones = listOf(phone("p1", "c1", "+301111111")))
        val companyCur = company("co1").copy(website = "https://old.example")
        val companyBak = company("co1").copy(website = "https://new.example")
        val current = persisted(contacts = listOf(contactCur), companies = listOf(companyCur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(contactBak), companies = listOf(companyBak)))
        assertEquals(2, preview.groups.size)

        assertEquals(listOf(TombstoneEntityType.CONTACT), preview.filtered(RestorePreviewFilter.CONTACTS, "").map { it.ownerType })
        assertEquals(listOf(TombstoneEntityType.COMPANY), preview.filtered(RestorePreviewFilter.COMPANIES, "").map { it.ownerType })
        assertEquals("CONFLICTS видит только компанию (у контакта — MISSING)",
            listOf(TombstoneEntityType.COMPANY), preview.filtered(RestorePreviewFilter.CONFLICTS, "").map { it.ownerType })
        assertEquals("MISSING видит только контакт", listOf(TombstoneEntityType.CONTACT), preview.filtered(RestorePreviewFilter.MISSING, "").map { it.ownerType })
    }

    // ═════════════════════════════════════════════════════════════
    //  T15 — смена фильтра не меняет ключи разниц -> выбор, сделанный отдельно от UI-фильтра, остаётся валиден
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t15_filterChange_doesNotAlterKeys_selectionStaysValid() {
        val cur = contact("c1")
        val bak = contact("c1").copy(phones = listOf(phone("p1", "c1", "+301111111")))
        val current = persisted(contacts = listOf(cur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val diff = preview.groups.single().differences.single()
        val selection = setOf(diff.key)

        val allView = preview.filtered(RestorePreviewFilter.ALL, "")
        val missingView = preview.filtered(RestorePreviewFilter.MISSING, "")
        assertEquals("тот же ключ виден под ALL", diff.key, allView.single().differences.single().key)
        assertEquals("тот же ключ виден под MISSING", diff.key, missingView.single().differences.single().key)

        // выбор, сделанный ДО смены фильтра, применяется корректно ПОСЛЕ неё — фильтрация не трогала данные
        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, selection)
        assertEquals(1, applyPlan.childPhones.size)
    }

    // ═════════════════════════════════════════════════════════════
    //  T20 (согласованность) — итоговая сводка соответствует фактически выбранным операциям
    // ═════════════════════════════════════════════════════════════

    @Test
    fun t20_summary_matchesSelectedOperations() {
        val cur = contact("c1").copy(profession = "Sales Manager")
        val bak = cur.copy(profession = "Account Executive", phones = listOf(phone("p1", "c1", "+301111111")))
        val current = persisted(contacts = listOf(cur))
        val preview = RestoreDiffEngine.analyze(current, RestorePlan(contacts = listOf(bak)))
        val allKeys = preview.groups.single().differences.map { it.key }.toSet()
        assertEquals(2, allKeys.size) // phone MISSING + profession CONFLICT

        val summary = RestoreDiffEngine.summarize(preview, allKeys)
        assertEquals("1 добавление (телефон)", 1, summary.additions)
        assertEquals("1 замена (профессия)", 1, summary.replacements)

        val applyPlan = RestoreDiffEngine.buildApplyPlan(preview, current, allKeys)
        assertEquals(summary.additions, applyPlan.childPhones.size)
        assertEquals(summary.replacements, applyPlan.scalarContactUpdates.size)
    }

    // ═════════════════════════════════════════════════════════════
    //  TAGS BACKUP RECOVERY PACK (2026-09-23) — T1: существующий Contact + существующий (managed) Tag,
    //  membership отсутствует в current, но есть в backup -> preview показывает "+ Tag: VIP" по ИМЕНИ,
    //  не по internal id.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun tagsT1_existingContactExistingTag_missingMembership_shownInPreview() {
        val vipTag = tag("t-vip", "VIP")
        val cur = contact("c1")
        val bak = contact("c1")
        val current = persisted(contacts = listOf(cur), tags = listOf(vipTag)) // тег существует, membership — нет
        val plan = RestorePlan(contacts = listOf(bak), tags = listOf(vipTag), tagMembers = listOf(tagMember("m1", "t-vip", "c1")))
        val preview = RestoreDiffEngine.analyze(current, plan)

        val group = preview.groups.singleOrNull { it.ownerId == "c1" } ?: fail("должна быть группа с разницей по тегу для c1") as Nothing
        val diff = group.differences.singleOrNull { it.backupValue == "VIP" } ?: fail("preview должен показать «+ Tag: VIP» по имени тега") as Nothing
        assertEquals(RestoreDifferenceType.MISSING, diff.differenceType)
        assertNull("тега сейчас у контакта нет — currentValue пуст", diff.currentValue)
    }

    // ═════════════════════════════════════════════════════════════
    //  LEGACY TAGS COMPATIBILITY & PHONE SYNC SAFETY PACK (2026-09-23) — PART C, T4: старый backup,
    //  чьи теги существуют ТОЛЬКО в легаси Contact.tags (plan.tags/plan.tagMembers пусты) — Restore
    //  Preview всё равно должен предложить их как recoverable-кандидаты, а не молча терять.
    // ═════════════════════════════════════════════════════════════

    @Test
    fun tagsPartC_T4_legacyOnlyBackupTag_shownInPreview() {
        val cur = contact("c1")
        val bak = contact("c1").copy(tags = listOf("VIP", "Investor"))
        val current = persisted(contacts = listOf(cur))
        // plan.tags/plan.tagMembers намеренно ПУСТЫ — тег существует только в легаси-поле backup Contact.
        val plan = RestorePlan(contacts = listOf(bak))
        val preview = RestoreDiffEngine.analyze(current, plan)

        val group = preview.groups.singleOrNull { it.ownerId == "c1" } ?: fail("должна быть группа с разницами для c1") as Nothing
        val tagValues = group.differences.filter { it.fieldKind == RestoreFieldKind.TAG }.map { it.backupValue }.toSet()
        assertEquals(setOf("VIP", "Investor"), tagValues)
    }
}
