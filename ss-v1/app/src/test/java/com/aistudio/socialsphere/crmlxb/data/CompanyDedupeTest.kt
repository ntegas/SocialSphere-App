package com.aistudio.socialsphere.crmlxb.data

import com.aistudio.socialsphere.crmlxb.model.Company
import com.aistudio.socialsphere.crmlxb.model.Industry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Регрессия (жалоба владельца: «даже в одних и тех же профессиях делает
 * разделение, не комбинирует группу»). Корень — компания создавалась ЧЕТЫРЬМЯ
 * независимыми путями (CompanyEditScreen/ContactEditScreen/ImportScreens/
 * WorkplaceComponents), и только ТРИ из них сверялись с уже существующими
 * компаниями через trim+equalsIgnoreCase; CompanyEditScreen (экран «Добавить
 * компанию») создавал дубль без какой-либо проверки и даже без trim.
 *
 * Теперь все четыре места вызывают единственную общую функцию
 * AppStateStore.findCompanyByName() (см. комментарий над ней в AppStateStore.kt).
 * Этот тест закрепляет контракт САМОЙ этой функции: "Marketing", " Marketing ",
 * "marketing" должны резолвиться в ОДНУ и ту же запись. Если общая функция
 * когда-нибудь перестанет это делать (или конкретный экран снова обойдёт её
 * прямым созданием Company), тест обязан упасть.
 *
 * Чистый JVM-тест: AppStateStore.database остаётся null, работаем только со
 * SnapshotStateList AppStateStore.companies напрямую (без addCompany(), чтобы
 * не задевать фоновую корутину записи в Room) — тот же паттерн, что и
 * PhoneDedupeTest для AppStateStore.contacts.
 */
class CompanyDedupeTest {

    @Before
    fun clearCompanies() = AppStateStore.companies.clear()

    @After
    fun cleanup() = AppStateStore.companies.clear()

    private fun company(id: String, name: String) = Company(
        id = id, name = name, logoUri = null, industry = Industry.MARKETING,
        description = null, website = null,
        createdAt = "2026-01-01T00:00", updatedAt = "2026-01-01T00:00"
    )

    @Test
    fun findCompanyByName_matchesRegardlessOfCaseOrWhitespace() {
        AppStateStore.companies.add(company("co1", "Marketing"))

        assertEquals("co1", AppStateStore.findCompanyByName("Marketing")?.id)
        assertEquals("co1", AppStateStore.findCompanyByName(" Marketing ")?.id)
        assertEquals("co1", AppStateStore.findCompanyByName("marketing")?.id)
        assertEquals("co1", AppStateStore.findCompanyByName("MARKETING")?.id)
    }

    @Test
    fun findCompanyByName_noMatch_returnsNull() {
        AppStateStore.companies.add(company("co1", "Marketing"))
        assertNull(AppStateStore.findCompanyByName("Sales"))
    }

    @Test
    fun findCompanyByName_emptyStore_returnsNull() {
        assertNull(AppStateStore.findCompanyByName("Marketing"))
    }
}
