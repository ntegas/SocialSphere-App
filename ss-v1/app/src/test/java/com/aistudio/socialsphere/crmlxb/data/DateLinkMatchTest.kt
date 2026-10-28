package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Чья дата, если связи с контактом нет: только однозначное совпадение, никакого гадания (§52, владелец 2026-10-10). */
class DateLinkMatchTest {

    private fun c(id: String, first: String, last: String = "") = Contact(
        id = id, firstName = first, lastName = last, photoUri = null,
        relationshipType = RelationshipType.FRIEND, connectionLevel = ConnectionLevel.NORMAL,
        importanceLevel = ImportanceLevel.NORMAL, socialRole = SocialRole.REGULAR,
        communicationRhythm = CommunicationRhythm.NOT_TRACKED,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00",
    )

    private val all = listOf(c("a", "Иван", "Петров"), c("b", "Мария", "Сидорова"), c("c", "Пётр"))

    @Test fun extractedNameEqualToOneContact_matches() {
        assertEquals("a", DateLinkMatch.uniqueContactFor("День рождения: Иван Петров", "Иван Петров", all)?.id)
    }

    @Test fun extractedNameEqualToTwoContacts_isAmbiguous_noFallbackGuess() {
        val dup = all + c("a2", "Иван", "Петров")
        assertNull(DateLinkMatch.uniqueContactFor("День рождения: Иван Петров", "Иван Петров", dup))
    }

    @Test fun fullNameInsideFreeTitle_matches_inEitherOrder_anyCase() {
        assertEquals("b", DateLinkMatch.uniqueContactFor("ДР мария сидорова!", null, all)?.id)
        assertEquals("b", DateLinkMatch.uniqueContactFor("Сидорова Мария — день рождения", null, all)?.id)
    }

    @Test fun onlyAFirstName_isNeverEnough() {
        assertNull(DateLinkMatch.uniqueContactFor("ДР Пётр", null, all))
        assertNull(DateLinkMatch.uniqueContactFor("День рождения", null, all))
    }

    @Test fun partialWordIsNotAMatch() {
        val short = listOf(c("x", "Ив", "Пет"))
        assertNull(DateLinkMatch.uniqueContactFor("Ив Петров", null, short))
        assertNull(DateLinkMatch.uniqueContactFor("Лёв Ив Пет", null, listOf(c("y", "Ив", "Пе"))))
    }

    @Test fun twoContactsNamedInOneTitle_isAmbiguous() {
        assertNull(DateLinkMatch.uniqueContactFor("Иван Петров и Мария Сидорова", null, all))
    }
}
