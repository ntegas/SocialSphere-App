package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.Contact

/**
 * Проверка кандидатов импорта на «уже есть в приложении» (экран предпросмотра импорта).
 *
 * Инвариант: проверка выполняется ОДИН раз на кандидата ([ImportContactCandidate.duplicateChecked]) и больше НЕ трогает
 * его статус и галочку. Раньше она запускалась при каждом заходе на экран (LaunchedEffect(Unit)) и затирала решения
 * пользователя: после «Поиск дублей» (WILL_UPDATE/SKIPPED) и после снятых вручную галочек всё возвращалось к
 * «новый → отмечен / дубль → снят».
 */
internal object ImportDuplicateCheck {

    /** Существующий контакт, с которым кандидат совпадает (по связи с книгой телефона, номеру, email, имени) или null. */
    fun matchFor(c: ImportContactCandidate, existing: List<Contact>): Contact? {
        // Уже связан с этим самым контактом телефона — точное совпадение, надёжнее любого сравнения по полям.
        val deviceMatch = existing.find { !c.deviceLookupKey.isNullOrBlank() && it.deviceLookupKey == c.deviceLookupKey }
            ?: existing.find { it.deviceContactId == c.id }
        if (deviceMatch != null) return deviceMatch
        // Сравнение по НОРМАЛИЗОВАННЫМ 10 цифрам: «+7 900…» и «89001112233» — один и тот же номер.
        val phoneMatch = existing.find { e ->
            e.phones.any { ep ->
                c.phones.any { cp ->
                    val d = AppStateStore.phoneDigits(ep.number)
                    d.length >= 7 && d == AppStateStore.phoneDigits(cp.number)
                }
            }
        }
        if (phoneMatch != null) return phoneMatch
        val emailMatch = existing.find { e -> e.emails.any { ee -> c.emails.any { ce -> ee.email.equals(ce.email, ignoreCase = true) } } }
        if (emailMatch != null) return emailMatch
        return existing.find { it.firstName == c.firstName && it.lastName == c.lastName && c.firstName.isNotBlank() }
    }

    /** Новый список: непроверенные кандидаты получают статус и галочку, уже проверенные возвращаются КАК ЕСТЬ. */
    fun apply(candidates: List<ImportContactCandidate>, existing: List<Contact>): List<ImportContactCandidate> =
        candidates.map { c ->
            if (c.duplicateChecked) c
            else {
                val match = matchFor(c, existing)
                if (match != null) c.copy(
                    duplicateStatus = DuplicateStatus.POSSIBLE_DUPLICATE, selectedForImport = false,
                    matchedContactId = match.id, duplicateChecked = true,
                ) else c.copy(duplicateStatus = DuplicateStatus.NEW, selectedForImport = true, duplicateChecked = true)
            }
        }
}
