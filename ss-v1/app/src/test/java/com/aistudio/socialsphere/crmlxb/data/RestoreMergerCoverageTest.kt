package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

/**
 * BACKUP INTEGRITY PACK #3 — защита от «забытого поля» в слиянии restore (тот же приём, что
 * StaleSnapshotLostUpdateTest.mergeContactEdit_hasARuleForEveryContactProperty).
 *
 * Для КАЖДОГО свойства каждой модели, участвующей в backup, объявлен режим слияния; тест падает, если
 * у модели появилось свойство без режима (или неизвестного типа), и проверяет поведение режима:
 *  • FILL_CONFLICT — текущее пусто -> заполняется из backup; оба заданы и различаются -> остаётся текущее + конфликт;
 *  • CONFLICT_ONLY — оба значения обязательны: различаются -> остаётся текущее + конфликт;
 *  • FLAG          — boolean-флаг: false в backup не сбрасывает true (без конфликта); true в backup заполняет false;
 *  • KEEP          — расстановка UI (isPrimary): всегда остаётся текущее, без конфликта;
 *  • LATER         — монотонная дата: берётся более поздняя, без конфликта;
 *  • SPECIAL       — покрыто отдельными тестами (пары/значения-списки/дети/идентичность).
 */
class RestoreMergerCoverageTest {

    private enum class Mode { FILL_CONFLICT, CONFLICT_ONLY, FLAG, KEEP, LATER, SPECIAL }
    private val FC = Mode.FILL_CONFLICT
    private val CO = Mode.CONFLICT_ONLY

    private val t = "2026-01-01T00:00:00"

    private fun <T : Any> withProp(instance: T, cls: KClass<T>, name: String, value: Any?): T {
        val copy = cls.memberFunctions.first { it.name == "copy" }
        val self = copy.parameters.first()
        val target = copy.parameters.first { it.name == name }
        @Suppress("UNCHECKED_CAST")
        return copy.callBy(mapOf(self to instance, target to value)) as T
    }

    /** («пусто», v1, v2) для свойства: пусто — то, что merger считает отсутствующим значением. */
    private fun triple(p: KProperty1<*, *>, cur: Any?): Triple<Any?, Any?, Any?> {
        val cls = p.returnType.classifier as KClass<*>
        val nullable = p.returnType.isMarkedNullable
        return when {
            cls == String::class -> Triple(if (nullable) null else "", "v1", "v2")
            cls == Int::class -> Triple(null, 11, 22)
            cls == Boolean::class -> Triple(false, true, false)
            cls.java.isEnum -> { val cs = cls.java.enumConstants.filter { it != cur }; Triple(null, cs[0], cs[1]) }
            cls == List::class -> {
                val arg = p.returnType.arguments[0].type!!.classifier as KClass<*>
                when {
                    arg == String::class -> Triple(emptyList<String>(), listOf("a"), listOf("b"))
                    arg.java.isEnum -> { val cs = arg.java.enumConstants.toList(); Triple(emptyList<Any>(), listOf(cs[0]), listOf(cs[1])) }
                    else -> fail("свойство ${p.name}: нет генератора значений для List<${arg.simpleName}>") as Nothing
                }
            }
            else -> fail("свойство ${p.name}: нет генератора значений для ${cls.simpleName} — расширьте тест и правило слияния") as Nothing
        }
    }

    private fun <T : Any> check(
        label: String, cls: KClass<T>, base: T, modes: Map<String, Mode>,
        merge: (RestoreMerger, T, T) -> T,
    ) {
        val props = cls.memberProperties
        assertEquals("$label: у КАЖДОГО свойства должен быть объявлен режим слияния (новое свойство -> добавьте правило в RestoreMerger и сюда)",
            props.map { it.name }.toSet(), modes.keys)
        for (p in props) {
            val mode = modes.getValue(p.name)
            if (mode == Mode.SPECIAL) continue
            @Suppress("UNCHECKED_CAST") val prop = p as KProperty1<T, *>
            val cur0 = prop.get(base)
            val (empty, v1, v2) = triple(p, cur0)
            fun run(cur: Any?, bak: Any?): Pair<Any?, List<String>> {
                val m = RestoreMerger({ null }, "now")
                val merged = merge(m, withProp(base, cls, p.name, cur), withProp(base, cls, p.name, bak))
                return prop.get(merged) to m.conflictFields
            }
            when (mode) {
                Mode.FILL_CONFLICT -> {
                    val (filled, c1) = run(empty, v1)
                    assertEquals("$label.${p.name}: пустое текущее заполняется из backup", v1, filled); assertTrue(c1.isEmpty())
                    val (kept, c2) = run(v1, v2)
                    assertEquals("$label.${p.name}: при расхождении остаётся ТЕКУЩЕЕ", v1, kept)
                    assertEquals("$label.${p.name}: расхождение сообщено", listOf(p.name), c2)
                    val (keptWhenBackupEmpty, c3) = run(v1, empty)
                    assertEquals("$label.${p.name}: пустое значение backup не затирает текущее", v1, keptWhenBackupEmpty); assertTrue(c3.isEmpty())
                }
                Mode.CONFLICT_ONLY -> {
                    val (kept, c) = run(v1, v2)
                    assertEquals("$label.${p.name}: остаётся текущее", v1, kept)
                    assertEquals("$label.${p.name}: расхождение сообщено", listOf(p.name), c)
                }
                Mode.FLAG -> {
                    val (keptTrue, c1) = run(true, false)
                    assertEquals("$label.${p.name}: false в backup не сбрасывает true", true, keptTrue); assertTrue(c1.isEmpty())
                    val (filled, c2) = run(false, true)
                    assertEquals("$label.${p.name}: true из backup заполняет false", true, filled); assertTrue(c2.isEmpty())
                }
                Mode.KEEP -> {
                    val (a, c1) = run(true, false); val (b, c2) = run(false, true)
                    assertEquals("$label.${p.name}: остаётся текущее (true)", true, a)
                    assertEquals("$label.${p.name}: остаётся текущее (false)", false, b)
                    assertTrue(c1.isEmpty() && c2.isEmpty())
                }
                Mode.LATER -> {
                    val (later1, c1) = run("2026-01-01", "2027-01-01"); val (later2, c2) = run("2027-01-01", "2026-01-01")
                    assertEquals("$label.${p.name}: берётся более поздняя (backup позже)", "2027-01-01", later1)
                    assertEquals("$label.${p.name}: берётся более поздняя (текущая позже)", "2027-01-01", later2)
                    assertTrue(c1.isEmpty() && c2.isEmpty())
                }
                Mode.SPECIAL -> {}
            }
        }
    }

    private val contact = Contact(id = "c", firstName = "Иван", lastName = "Петров", photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL, importanceLevel = ImportanceLevel.NORMAL,
        socialRole = SocialRole.REGULAR, communicationRhythm = CommunicationRhythm.NOT_TRACKED, createdAt = t, updatedAt = t)

    @Test
    fun contact_everyPropertyHasAMergeRule() = check("Contact", Contact::class, contact, mapOf(
        "id" to Mode.SPECIAL, "createdAt" to Mode.SPECIAL, "updatedAt" to Mode.SPECIAL,
        "notes" to Mode.SPECIAL, "gifts" to Mode.SPECIAL,                                  // независимые агрегаты — плоские списки
        "phones" to Mode.SPECIAL, "emails" to Mode.SPECIAL, "messengers" to Mode.SPECIAL, "addresses" to Mode.SPECIAL,
        "personalDetails" to Mode.SPECIAL, "companyRelations" to Mode.SPECIAL, "sizeInfo" to Mode.SPECIAL,
        "contactStatus" to Mode.SPECIAL,                                                    // enum со значением по умолчанию
        "lastContactDate" to Mode.LATER,
        "firstName" to FC, "lastName" to FC, "middleName" to FC, "nickname" to FC, "namePrefix" to FC, "nameSuffix" to FC,
        "phoneticFirstName" to FC, "phoneticMiddleName" to FC, "phoneticLastName" to FC, "photoUri" to FC,
        "customRelationshipType" to FC, "secondaryRelationshipTypes" to FC, "customRhythmDays" to FC,
        // ФИКС (Data Safety Residual Audit TAG-2 — Legacy Tags Compatibility & Phone Sync Safety Pack,
        // 2026-09-23): легаси Contact.tags БОЛЬШЕ НЕ заполняется из backup в mergeContact() (было FC) —
        // всегда остаётся cur.tags, без исключения. Не Mode.KEEP: тот вариант проверки написан только
        // под Boolean-свойства (run(true,false)); "tags" — List<String>, покрыт отдельно
        // TagsConsistencyTest.tagsPartB_T12_restoreMergeCannotSilentlyCreateUnselectedLegacyTag.
        "tags" to Mode.SPECIAL,
        "nextStep" to FC, "familyNote" to FC, "profession" to FC, "canHelpWith" to FC, "iCanHelpWith" to FC,
        "talkingPoints" to FC, "meetContext" to FC, "meetDate" to FC,
        "deviceContactId" to Mode.SPECIAL, "deviceLookupKey" to Mode.SPECIAL,         // связь с телефонной книгой — ПАРА (см. deviceLink_* ниже)
        "relationshipType" to CO, "connectionLevel" to CO, "importanceLevel" to CO, "socialRole" to CO, "communicationRhythm" to CO,
    )) { m, c, b -> m.mergeContact(c, b) }

    @Test
    fun company_everyPropertyHasAMergeRule() = check("Company", Company::class,
        Company("co", "Acme", null, Industry.IT, createdAt = t, updatedAt = t), mapOf(
        "id" to Mode.SPECIAL, "createdAt" to Mode.SPECIAL, "updatedAt" to Mode.SPECIAL,
        "phones" to Mode.SPECIAL, "emails" to Mode.SPECIAL, "addresses" to Mode.SPECIAL,
        "name" to FC, "logoUri" to FC, "description" to FC, "website" to FC, "industry" to CO,
    )) { m, c, b -> m.mergeCompany(c, b) }

    @Test
    fun calendarItem_everyPropertyHasAMergeRule() = check("CalendarItem", CalendarItem::class,
        CalendarItem("cal", "Событие", null, CalendarItemType.MEETING, "2027-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL, createdAt = t, updatedAt = t), mapOf(
        "id" to Mode.SPECIAL, "createdAt" to Mode.SPECIAL, "updatedAt" to Mode.SPECIAL,
        "links" to Mode.SPECIAL, "reminders" to Mode.SPECIAL,
        "postponedFromOccurrenceDate" to Mode.SPECIAL, "postponedToDate" to Mode.SPECIAL,      // пара — отдельный тест
        "lastCompletedOccurrenceDate" to Mode.LATER, "recurrenceExplicitlySet" to Mode.FLAG,
        "title" to FC, "description" to FC, "startDate" to FC, "startTime" to FC, "endDate" to FC, "endTime" to FC,
        "colorKey" to FC, "recurrenceRule" to FC,
        "type" to CO, "status" to CO, "importance" to CO, "isAllDay" to CO,
    )) { m, c, b -> m.mergeCalendarItem(c, b) }

    @Test
    fun note_everyPropertyHasAMergeRule() = check("Note", Note::class,
        Note("n", type = NoteType.GENERAL, text = "т", isImportant = false, createdAt = t, updatedAt = t), mapOf(
        "id" to Mode.SPECIAL, "createdAt" to Mode.SPECIAL, "updatedAt" to Mode.SPECIAL,
        "contactId" to FC, "companyId" to FC, "calendarItemId" to FC, "giftId" to FC, "text" to FC, "date" to FC,
        "type" to CO, "isImportant" to Mode.FLAG, "isLocked" to Mode.FLAG,
    )) { m, c, b -> m.mergeNote(c, b) }

    @Test
    fun gift_everyPropertyHasAMergeRule() = check("GiftIdea", GiftIdea::class,
        GiftIdea("g", "c", "Книга", status = GiftStatus.IDEA), mapOf(
        "id" to Mode.SPECIAL, "contactId" to FC, "title" to FC, "note" to FC, "link" to FC, "date" to FC, "status" to CO,
    )) { m, c, b -> m.mergeGift(c, b) }

    @Test
    fun sizeInfo_everyPropertyHasAMergeRule() = check("SizeInfo", SizeInfo::class, SizeInfo("s", "c"), mapOf(
        "id" to Mode.SPECIAL, "contactId" to Mode.SPECIAL,
        "clothingSize" to FC, "shoeSize" to FC, "ringSize" to FC, "other" to FC,
    )) { m, c, b -> m.mergeSizeInfo(c, b) }

    @Test
    fun personalDetail_everyPropertyHasAMergeRule() = check("PersonalDetail", PersonalDetail::class,
        PersonalDetail("d", "c", PersonalDetailCategory.LIKES, "чай"), mapOf(
        "id" to Mode.SPECIAL, "contactId" to Mode.SPECIAL, "value" to FC, "note" to FC, "category" to CO,
    )) { m, c, b -> m.mergePersonalDetail(c, b) }

    @Test
    fun phone_email_messenger_everyPropertyHasAMergeRule() {
        check("ContactPhone", ContactPhone::class, ContactPhone("p", "c", "+7", PhoneType.MOBILE, false), mapOf(
            "id" to Mode.SPECIAL, "contactId" to Mode.SPECIAL, "number" to FC, "comment" to FC, "type" to CO, "isPrimary" to Mode.KEEP,
        )) { m, c, b -> m.mergePhone(c, b) }
        check("ContactEmail", ContactEmail::class, ContactEmail("e", "c", "a@b", EmailType.PERSONAL, false), mapOf(
            "id" to Mode.SPECIAL, "contactId" to Mode.SPECIAL, "email" to FC, "comment" to FC, "type" to CO, "isPrimary" to Mode.KEEP,
        )) { m, c, b -> m.mergeEmail(c, b) }
        check("Messenger", Messenger::class, Messenger("m", "c", MessengerType.TELEGRAM, "@x", null, false), mapOf(
            "id" to Mode.SPECIAL, "contactId" to Mode.SPECIAL, "value" to FC, "link" to FC, "comment" to FC, "type" to CO, "isPrimary" to Mode.KEEP,
        )) { m, c, b -> m.mergeMessenger(c, b) }
    }

    @Test
    fun address_companyRelation_contactRelation_group_everyPropertyHasAMergeRule() {
        check("Address", Address::class, Address("a", AddressOwnerType.CONTACT, "c", AddressType.HOME, "Ленина 5", "Москва", "РФ"), mapOf(
            "id" to Mode.SPECIAL, "ownerType" to Mode.SPECIAL, "ownerId" to Mode.SPECIAL,
            "latitude" to Mode.SPECIAL, "longitude" to Mode.SPECIAL,                          // пара — отдельный тест
            "addressLine" to FC, "city" to FC, "country" to FC, "comment" to FC, "postalCode" to FC, "district" to FC,
            "addressType" to CO,
        )) { m, c, b -> m.mergeAddress(c, b) }
        check("ContactCompanyRelation", ContactCompanyRelation::class,
            ContactCompanyRelation("r", "c", "co", employmentStatus = EmploymentStatus.CURRENT, isPrimary = false), mapOf(
            "id" to Mode.SPECIAL, "contactId" to Mode.SPECIAL, "companyId" to FC, "position" to FC, "department" to FC, "role" to FC,
            "startDate" to FC, "endDate" to FC, "responsibilities" to FC, "managedAccounts" to FC, "workNote" to FC,
            "officeAddressId" to FC, "employmentStatus" to CO, "isPrimary" to Mode.KEEP,
        )) { m, c, b -> m.mergeCompanyRelation(c, b) }
        check("ContactRelation", ContactRelation::class, ContactRelation("cr", "a", "b", "Друг", "Друг"), mapOf(
            "id" to Mode.SPECIAL, "firstContactId" to FC, "secondContactId" to FC, "firstRole" to FC, "secondRole" to FC, "note" to FC,
        )) { m, c, b -> m.mergeContactRelation(c, b) }
        check("ContactGroup", ContactGroup::class, ContactGroup("g", "Клиенты", t, t), mapOf(
            "id" to Mode.SPECIAL, "createdAt" to Mode.SPECIAL, "updatedAt" to Mode.SPECIAL, "name" to FC,
        )) { m, c, b -> m.mergeGroup(c, b) }
    }

    // ── SPECIAL-режимы ────────────────────────────────────────

    @Test
    fun coordinates_arePairedAndBoundToAddressText() {
        val cur = Address("a", AddressOwnerType.CONTACT, "c", AddressType.HOME, "Ленина 5", "Москва", "РФ", latitude = 55.75, longitude = 37.61)
        val noCoords = cur.copy(latitude = null, longitude = null)
        val m1 = RestoreMerger({ null }, "n")
        assertEquals("null из backup не затирает координаты", 55.75 to 37.61, m1.mergeAddress(cur, noCoords).let { it.latitude to it.longitude })
        assertTrue(m1.conflictFields.isEmpty())
        val m2 = RestoreMerger({ null }, "n")
        assertEquals("пустое текущее заполняется парой", 10.0 to 20.0, m2.mergeAddress(noCoords, cur.copy(latitude = 10.0, longitude = 20.0)).let { it.latitude to it.longitude })
        val m3 = RestoreMerger({ null }, "n")
        assertEquals("разные координаты — остаётся текущая пара + конфликт", 55.75 to 37.61,
            m3.mergeAddress(cur, cur.copy(latitude = 1.0, longitude = 2.0)).let { it.latitude to it.longitude })
        assertEquals(listOf("coordinates"), m3.conflictFields)
        val m4 = RestoreMerger({ null }, "n")
        val other = cur.copy(addressLine = "Другая 9", latitude = 1.0, longitude = 2.0)
        val merged = m4.mergeAddress(noCoords, other)
        assertEquals("координаты чужого адреса (другой текст) не переносятся", null to null, merged.latitude to merged.longitude)
    }

    @Test
    fun contactStatus_backupDefaultDoesNotOverrideMeaningfulCurrent() {
        val m = RestoreMerger({ null }, "n")
        assertEquals(ContactStatus.ARCHIVED, m.mergeContact(contact.copy(contactStatus = ContactStatus.ARCHIVED), contact).contactStatus)
        assertTrue(m.conflictFields.isEmpty())
        val m2 = RestoreMerger({ null }, "n")
        assertEquals("осмысленные разные статусы — конфликт, остаётся текущий", ContactStatus.CLOSE,
            m2.mergeContact(contact.copy(contactStatus = ContactStatus.CLOSE), contact.copy(contactStatus = ContactStatus.WEAK)).contactStatus)
        assertEquals(listOf("contactStatus"), m2.conflictFields)
    }

    @Test
    fun postponePair_isMergedAsAWhole() {
        val base = CalendarItem("cal", "Событие", null, CalendarItemType.MEETING, "2027-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL, createdAt = t, updatedAt = t)
        val withPair = base.copy(postponedFromOccurrenceDate = "2027-02-08", postponedToDate = "2027-02-10")
        assertEquals("пара из backup заполняет пустую целиком", "2027-02-08" to "2027-02-10",
            RestoreMerger({ null }, "n").mergeCalendarItem(base, withPair).let { it.postponedFromOccurrenceDate to it.postponedToDate })
        assertEquals("отсутствующая пара в backup не сбрасывает текущую", "2027-02-08" to "2027-02-10",
            RestoreMerger({ null }, "n").mergeCalendarItem(withPair, base).let { it.postponedFromOccurrenceDate to it.postponedToDate })
        val m = RestoreMerger({ null }, "n")
        val other = base.copy(postponedFromOccurrenceDate = "2027-03-01", postponedToDate = "2027-03-03")
        assertEquals("разные пары — остаётся текущая (половинки не смешиваются)", "2027-02-08" to "2027-02-10",
            m.mergeCalendarItem(withPair, other).let { it.postponedFromOccurrenceDate to it.postponedToDate })
        assertEquals(listOf("postponed"), m.conflictFields)
    }

    @Test
    fun valueLists_linksAndReminders_compareBySemanticsNotIds() {
        val base = CalendarItem("cal", "Событие", null, CalendarItemType.MEETING, "2027-01-01", isAllDay = true, status = CalendarItemStatus.ACTIVE,
            importance = ImportanceLevel.NORMAL, createdAt = t, updatedAt = t)
        val a = base.copy(links = listOf(CalendarItemLink("l1", "cal", CalendarTargetType.CONTACT, "c1")))
        val sameMeaningNewId = base.copy(links = listOf(CalendarItemLink("l9", "cal", CalendarTargetType.CONTACT, "c1")))
        val different = base.copy(links = listOf(CalendarItemLink("l2", "cal", CalendarTargetType.CONTACT, "c2")))
        val m1 = RestoreMerger({ null }, "n")
        assertEquals(a.links, m1.mergeCalendarItem(a, sameMeaningNewId).links); assertTrue(m1.conflictFields.isEmpty())
        assertEquals("пустые текущие ссылки заполняются (id backup сохраняются)", sameMeaningNewId.links,
            RestoreMerger({ null }, "n").mergeCalendarItem(base, sameMeaningNewId).links)
        assertEquals("пустой backup не стирает", a.links, RestoreMerger({ null }, "n").mergeCalendarItem(a, base).links)
        val m2 = RestoreMerger({ null }, "n")
        assertEquals(a.links, m2.mergeCalendarItem(a, different).links); assertEquals(listOf("links"), m2.conflictFields)
    }

    @Test
    fun children_currentOnlyKept_backupOnlyAdded_sameIdMergedByField_currentOrderKept() {
        val p1 = ContactPhone("p1", "c", "+71", PhoneType.MOBILE, true); val p2 = ContactPhone("p2", "c", "+72", PhoneType.MOBILE, false)
        val p3 = ContactPhone("p3", "c", "+73", PhoneType.MOBILE, false)
        val m = RestoreMerger({ null }, "n")
        val merged = m.mergeContact(contact.copy(phones = listOf(p1, p2)), contact.copy(phones = listOf(p1.copy(comment = "из backup"), p3)))
        assertEquals("порядок текущих сохранён, backup-only добавлен в конец", listOf("p1", "p2", "p3"), merged.phones.map { it.id })
        assertEquals("пустой комментарий заполнен у общего id", "из backup", merged.phones.first { it.id == "p1" }.comment)
        assertTrue(m.conflictFields.isEmpty())
    }

    @Test
    fun identicalEntities_produceNoWritesAndNoReport() {
        val current = PersistedState(
            contacts = listOf(contact.copy(phones = listOf(ContactPhone("p1", "c", "+71", PhoneType.MOBILE, true)))),
            companies = emptyList(), calendarItems = emptyList(), notes = emptyList(), gifts = emptyList(), companyRelations = emptyList(),
            contactRelations = emptyList(), addresses = emptyList(), sizeInfos = emptyList(), personalDetails = emptyList(),
            groups = emptyList(), groupMembers = emptyList(), tags = emptyList(), tagMembers = emptyList(),
            tombstones = emptyList(), schemaVersion = 20)
        val plan = RestorePlan(contacts = current.contacts)

        val (writes, result) = RestoreMerger({ null }, "now").merge(current, plan)

        assertTrue(writes.contacts.isEmpty() && writes.notes.isEmpty() && writes.gifts.isEmpty())
        assertEquals(RestoreMode.MERGE, result.mode)
        assertEquals(0, result.conflictCount + result.filledFields + result.addedChildren + result.addedContacts)
        assertFalse(result.conflicts.isNotEmpty())
    }

    /** PHONE PACK #4: связь с телефонной книгой (id-кэш + LOOKUP_KEY) сливается ПАРОЙ — половинки разных связей не смешиваются. */
    @Test
    fun deviceLink_isMergedAsPair_neverMixesHalvesOfDifferentLinks() {
        val cur = contact.copy(deviceContactId = "device_contact_1", deviceLookupKey = "LA")
        val other = contact.copy(deviceContactId = "device_contact_2", deviceLookupKey = "LB")
        fun link(c: Contact) = c.deviceContactId to c.deviceLookupKey

        val m1 = RestoreMerger({ null }, "n")
        assertEquals("две разные связи — остаётся текущая пара + конфликт", "device_contact_1" to "LA", link(m1.mergeContact(cur, other)))
        assertEquals(listOf("deviceLink"), m1.conflictFields)

        val curNoKey = contact.copy(deviceContactId = "device_contact_1", deviceLookupKey = null)
        assertEquals("чужой LOOKUP_KEY не подставляется к своему id", "device_contact_1" to null,
            link(RestoreMerger({ null }, "n").mergeContact(curNoKey, other)))

        val m3 = RestoreMerger({ null }, "n")
        assertEquals("та же связь: недостающий LOOKUP_KEY добавляется", "device_contact_1" to "LA", link(m3.mergeContact(curNoKey, cur)))
        assertTrue(m3.conflictFields.isEmpty())

        assertEquals("пустая текущая связь заполняется парой backup", "device_contact_2" to "LB",
            link(RestoreMerger({ null }, "n").mergeContact(contact, other)))
        val m5 = RestoreMerger({ null }, "n")
        assertEquals("backup без связи не стирает текущую", "device_contact_1" to "LA", link(m5.mergeContact(cur, contact)))
        assertTrue(m5.conflictFields.isEmpty())
    }
}
