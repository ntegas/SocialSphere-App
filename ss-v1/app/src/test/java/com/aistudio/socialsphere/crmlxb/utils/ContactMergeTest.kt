package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * BATCH 5C (5C-002). computeMergePreview() считал "tags" по легаси
 * `Contact.tags.size` — контакт, чьи теги существуют только как управляемые
 * Tag/ContactTagMember (единственный способ добавить тег через актуальный UI
 * «+ Тег»), показывал 0 в предпросмотре слияния, даже владея двумя тегами.
 *
 * Чистый JVM-тест: то же семейство, что PhoneDedupeTest/SearchEngineQueryTest —
 * AppStateStore.tags/tagMembers это обычные in-memory списки, Room не нужен.
 */
class ContactMergeTest {

    @Before
    fun setup() {
        AppStateStore.contacts.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.contacts.clear()
        AppStateStore.tags.clear()
        AppStateStore.tagMembers.clear()
    }

    private fun contact(id: String, first: String) = Contact(
        id = id, firstName = first, lastName = "Тестов", photoUri = null,
        relationshipType = RelationshipType.OTHER, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    /**
     * BATCH 5C (5C-002, regression C). Контакт с двумя управляемыми тегами и
     * ПУСТЫМ легаси Contact.tags должен показать count = 2 в предпросмотре
     * слияния, а не 0.
     */
    @Test
    fun computeMergePreview_countsManagedTagMemberships_notLegacyContactTags() {
        val a = contact("a", "Иван")   // 2 managed tags, легаси-поле пусто
        val b = contact("b", "Пётр")   // 0 тегов вообще
        AppStateStore.contacts.add(a)
        AppStateStore.contacts.add(b)
        AppStateStore.tags.add(Tag("t1", "VIP", null, "2026-01-01T00:00", "2026-01-01T00:00"))
        AppStateStore.tags.add(Tag("t2", "Клиент", null, "2026-01-01T00:00", "2026-01-01T00:00"))
        AppStateStore.tagMembers.add(ContactTagMember("m1", "t1", "a"))
        AppStateStore.tagMembers.add(ContactTagMember("m2", "t2", "a"))

        val preview = computeMergePreview(listOf(a, b))

        assertEquals("предусловие: легаси-поле контакта A пусто", 0, a.tags.size)
        assertEquals(listOf("a", "b"), preview.contactIds)
        assertEquals(listOf(2, 0), preview.listCounts["tags"])
    }
}
