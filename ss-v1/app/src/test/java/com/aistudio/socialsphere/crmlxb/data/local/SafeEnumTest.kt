package com.aistudio.socialsphere.crmlxb.data.local

import com.aistudio.socialsphere.crmlxb.model.RelationshipType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * safeEnum должен возвращать default при любой невалидной/отсутствующей строке —
 * иначе кривой импорт (CSV/vCard/ручная правка БД) ронял бы reloadFromDb().
 * Чистый JVM-тест, Room не нужен.
 */
class SafeEnumTest {

    @Test
    fun validName_parsesToEnum() {
        assertEquals(
            RelationshipType.OTHER,
            safeEnum("OTHER", RelationshipType.ACQUAINTANCE)
        )
    }

    @Test
    fun invalidName_fallsBackToDefault() {
        assertEquals(
            RelationshipType.ACQUAINTANCE,
            safeEnum("NOT_A_REAL_VALUE", RelationshipType.ACQUAINTANCE)
        )
    }

    @Test
    fun nullValue_fallsBackToDefault() {
        assertEquals(
            RelationshipType.OTHER,
            safeEnum(null, RelationshipType.OTHER)
        )
    }

    @Test
    fun wrongCase_fallsBackToDefault() {
        // enumValueOf регистрозависим — "other" не равно "OTHER"
        assertEquals(
            RelationshipType.ACQUAINTANCE,
            safeEnum("other", RelationshipType.ACQUAINTANCE)
        )
    }

    @Test
    fun blankValue_fallsBackToDefault() {
        assertEquals(
            RelationshipType.OTHER,
            safeEnum("", RelationshipType.OTHER)
        )
    }
}
