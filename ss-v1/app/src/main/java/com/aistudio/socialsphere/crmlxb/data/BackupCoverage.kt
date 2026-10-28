package com.aistudio.socialsphere.crmlxb.data

/**
 * BACKUP INTEGRITY PACK #3: карта покрытия «таблица Room -> представление в backup -> как восстанавливается».
 *
 * Это и документация, и предохранитель: `BackupCoverageTest` сверяет ключи этой карты с реальным
 * списком таблиц открытой Room-БД — новая user-data таблица без записи здесь роняет тест, то есть
 * не может тихо остаться вне backup.
 *
 * Все таблицы читаются одним снимком ([readRoomSnapshot]); восстановление — RestoreMerge.kt
 * (FRESH: 1:1; MERGE: data-safe по устойчивым id, см. шапку файла).
 */
object BackupCoverage {

    class Entry(
        /** Где лежит в BackupData (JSON). */
        val backupField: String,
        /** Как восстанавливается поверх существующих данных. */
        val restore: String,
    )

    val ROOM_TABLES: Map<String, Entry> = mapOf(
        "contacts" to Entry("contacts[] (все скаляры; legacy tags-строка как список)", "по id: add / заполнить пустое / конфликт=оставить текущее; lastContactDate=max"),
        "contact_phones" to Entry("contacts[].phones[] (companyId==null) и companies[].phones[] (companyId!=null)", "дочерние по id: add / поля / текущие сохраняются"),
        "contact_emails" to Entry("contacts[].emails[] и companies[].emails[] (по companyId)", "то же"),
        "messengers" to Entry("contacts[].messengers[]", "то же"),
        "addresses" to Entry("contacts[].addresses[] и companies[].addresses[] (ownerType) + координаты", "по id; координаты — парой и только при том же тексте адреса; null не затирает"),
        "personal_details" to Entry("contacts[].personalDetails[]", "по id"),
        "size_infos" to Entry("contacts[].sizeInfo (одна запись на контакт)", "по contactId: id ТЕКУЩЕЙ строки остаётся, поля — fill/конфликт (без дубля строки)"),
        "notes" to Entry("notes[] (плоский, все заметки) + contacts[].notes[] из того же снимка", "по id; flat побеждает nested; текст-конфликт=оставить текущий"),
        "gift_ideas" to Entry("gifts[] (плоский) + contacts[].gifts[] из того же снимка", "по id; reminderId — мёртвая колонка (всегда null), не экспортируется"),
        "companies" to Entry("companies[] (+ вложенные phones/emails/addresses)", "по id; даты backup сохраняются (не перештамповываются)"),
        "contact_company_relations" to Entry("contacts[].companyRelations[]", "по id"),
        "contact_relations" to Entry("contactRelations[]", "по id (текущее сохраняется)"),
        "tags" to Entry("tags[]", "по логическому имени (Batch 5C): тот же тег под другим id не дублируется"),
        "contact_tag_members" to Entry("tagMembers[]", "по id и по паре (тег, контакт)"),
        "contact_groups" to Entry("groups[]", "по id"),
        "contact_group_members" to Entry("groupMembers[]", "по id и по паре (группа, контакт)"),
        "calendar_items" to Entry("calendarItems[] (recurrence/postpone-поля включены)", "по id; recurrence/postpone — fill/конфликт; lastCompletedOccurrenceDate=max; пара postpone целиком"),
        "calendar_item_links" to Entry("calendarItems[].links[]", "как значение-список (id перегенерируются при каждом сохранении события): fill пустого / конфликт"),
        "reminder_rules" to Entry("calendarItems[].reminders[]", "то же"),
        "tombstones" to Entry("tombstones[] (EXPLICIT RECOVERY PACK)", "SAFE MERGE: локальный tombstone блокирует добавление backup-only записи; объединяется с tombstones бэкапа (union). SELECTIVE/RESTORE ALL: выбранные явно очищаются и запись восстанавливается — см. RestoreMerge.kt"),
    )

    /**
     * Вне Room-таблиц и вне JSON-backup — ЯВНО, не по забывчивости:
     *  • app_settings (SharedPreferences): язык, тема, флаги уведомлений/напоминаний, отображение — предпочтения
     *    установки, а не данные пользователя; PIN-хэш и состояние блокировки НЕЛЬЗЯ выносить в файл (безопасность);
     *  • файлы фото (Contact.photoUri / Company.logoUri — пути в filesDir): в backup только строка пути, сами
     *    файлы не включены — после переустановки путь может указывать в никуда (известное ограничение);
     *  • CSV/Excel-экспорты — не Full Backup (см. отчёт пакета).
     */
    val NOT_PART_OF_BACKUP: List<String> = listOf("app_settings (SharedPreferences)", "photo/logo files", "CSV/vCard exports")
}
