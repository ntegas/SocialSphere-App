package com.aistudio.socialsphere.crmlxb.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner

/**
 * Проверяем, что миграции 1→2, 2→3, 3→4 НЕ теряют данные.
 *
 * Схемы старых версий (1–5) не экспортированы, а room-testing/MigrationTestHelper
 * в проект не подключён, поэтому вызываем migrate() напрямую на реальной SQLite
 * (Robolectric, in-memory): создаём таблицу в «старой» форме → вставляем строку →
 * прогоняем миграцию → проверяем сохранность данных и появление новых колонок/таблиц.
 *
 * Миграции должны быть видимы тесту (сделаны internal в SocialsphereDatabase).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    /** Открывает in-memory SQLite на заданной версии; onCreate строит «старую» схему. */
    private fun openDb(version: Int, onCreate: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null) // null = in-memory
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun migration1to2_preservesContacts_andAddsColumns() {
        val db = openDb(1) {
            it.execSQL(
                "CREATE TABLE contacts (" +
                "id TEXT NOT NULL PRIMARY KEY, firstName TEXT NOT NULL, lastName TEXT NOT NULL)"
            )
        }
        db.execSQL("INSERT INTO contacts (id, firstName, lastName) VALUES ('c1', 'Иван', 'Петров')")

        SocialsphereDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT firstName, lastName, contactStatus, nickname FROM contacts WHERE id='c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Иван", c.getString(0))   // данные целы
            assertEquals("Петров", c.getString(1))
            assertEquals("ACTIVE", c.getString(2)) // NOT NULL DEFAULT применился
            assertTrue(c.isNull(3))                 // новая колонка nickname добавлена, пустая
        }
        db.close()
    }

    @Test
    fun migration2to3_preservesContacts_andCreatesTables() {
        val db = openDb(2) {
            it.execSQL(
                "CREATE TABLE contacts (id TEXT NOT NULL PRIMARY KEY, firstName TEXT NOT NULL)"
            )
        }
        db.execSQL("INSERT INTO contacts (id, firstName) VALUES ('c1', 'Иван')")

        SocialsphereDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT firstName, meetContext FROM contacts WHERE id='c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Иван", c.getString(0))
            assertTrue(c.isNull(1)) // колонка meetContext добавлена
        }
        // Таблицы созданы (запрос не бросает) и пусты
        db.query("SELECT count(*) FROM contact_relations").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT count(*) FROM size_infos").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT count(*) FROM personal_details").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migration3to4_isNoOp_keepsData() {
        val db = openDb(3) {
            it.execSQL(
                "CREATE TABLE contacts (id TEXT NOT NULL PRIMARY KEY, firstName TEXT NOT NULL, " +
                "contactStatus TEXT NOT NULL DEFAULT 'ACTIVE')"
            )
        }
        db.execSQL("INSERT INTO contacts (id, firstName) VALUES ('c1', 'Иван')")

        SocialsphereDatabase.MIGRATION_3_4.migrate(db)

        db.query("SELECT firstName, contactStatus FROM contacts WHERE id='c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Иван", c.getString(0))
            assertEquals("ACTIVE", c.getString(1))
        }
        db.close()
    }

    /**
     * ФИКС КРИТИЧНОГО БАГА (2026-07-07, владелец: «теряю данные при каждом
     * обновлении»): причина была не в самих миграциях (они верны — тесты выше
     * это уже проверяют), а в `fallbackToDestructiveMigration()` для DEBUG-сборок
     * в `SocialsphereDatabase.getDatabase()`, который молча стирал всю БД при
     * ЛЮБОМ несовпадении схемы вместо того чтобы прогнать миграции. Он убран.
     *
     * Этот тест — страховка на будущее: прогоняет ВСЮ цепочку миграций 6→13
     * подряд на одном реальном SQLite (начиная с ПОДЛИННОЙ схемы v6 — взята
     * дословно из экспортированного `app/schemas/.../6.json`, не придумана),
     * и проверяет, что (а) ни одна миграция не бросает исключение, и
     * (б) исходная строка контакта доживает до v13 со всеми полями нетронутыми.
     * Если когда-нибудь добавят миграцию с пропуском/опечаткой — этот тест
     * упадёт раньше, чем баг доберётся до боевых данных владельца.
     */
    @Test
    fun fullMigrationChain_6to13_preservesContactData_noExceptions() {
        val db = openDb(6) {
            // Дословно из app/schemas/.../6.json — подлинная схема v6, не догадка.
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `contacts` (`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, " +
                "`lastName` TEXT NOT NULL, `nickname` TEXT, `photoUri` TEXT, `relationshipType` TEXT NOT NULL, " +
                "`connectionLevel` TEXT NOT NULL, `importanceLevel` TEXT NOT NULL, `socialRole` TEXT NOT NULL, " +
                "`communicationRhythm` TEXT NOT NULL, `contactStatus` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                "`lastContactDate` TEXT, `nextStep` TEXT, `tags` TEXT, `canHelpWith` TEXT, `iCanHelpWith` TEXT, " +
                "`talkingPoints` TEXT, `meetContext` TEXT, `meetDate` TEXT, `createdAt` TEXT NOT NULL, " +
                "`updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
        }
        db.execSQL(
            "INSERT INTO contacts (id, firstName, lastName, photoUri, relationshipType, " +
            "connectionLevel, importanceLevel, socialRole, communicationRhythm, createdAt, updatedAt) " +
            "VALUES ('c1', 'Иван', 'Петров', 'file:///photo.jpg', 'FRIEND', 'CLOSE', 'HIGH', " +
            "'FRIEND', 'WEEKLY', '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )

        // Вся цепочка подряд, без destructive fallback — именно то, что должно
        // происходить при апдейте приложения на реальном устройстве владельца.
        SocialsphereDatabase.MIGRATION_6_7.migrate(db)
        SocialsphereDatabase.MIGRATION_7_8.migrate(db)
        SocialsphereDatabase.MIGRATION_8_9.migrate(db)
        SocialsphereDatabase.MIGRATION_9_10.migrate(db)
        SocialsphereDatabase.MIGRATION_10_11.migrate(db)
        SocialsphereDatabase.MIGRATION_11_12.migrate(db)
        SocialsphereDatabase.MIGRATION_12_13.migrate(db)

        db.query(
            "SELECT firstName, lastName, photoUri, deviceContactId, customRelationshipType, " +
            "middleName, familyNote, profession, namePrefix, nameSuffix, phoneticFirstName, phoneticLastName " +
            "FROM contacts WHERE id='c1'"
        ).use { c ->
            assertTrue("строка контакта должна дожить до v13", c.moveToFirst())
            assertEquals("Иван", c.getString(0))          // исходные данные целы
            assertEquals("Петров", c.getString(1))
            assertEquals("file:///photo.jpg", c.getString(2))
            assertTrue(c.isNull(3))   // deviceContactId — новая колонка v8, пустая
            assertTrue(c.isNull(4))   // customRelationshipType — v9
            assertTrue(c.isNull(5))   // middleName — v10
            assertTrue(c.isNull(6))   // familyNote — v10
            assertTrue(c.isNull(7))   // profession — v12
            assertTrue(c.isNull(8))   // namePrefix — v13
            assertTrue(c.isNull(9))   // nameSuffix — v13
            assertTrue(c.isNull(10))  // phoneticFirstName — v13
            assertTrue(c.isNull(11))  // phoneticLastName — v13
        }
        // Таблицы, добавленные по пути (v11), должны существовать и быть пустыми.
        db.query("SELECT count(*) FROM contact_groups").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    /**
     * ФИКС (2026-07-08, база знаний §29): приватность заметки — новое поле
     * `isLocked`, отдельное от `isImportant`. Прогоняем MIGRATION_13_14 на
     * подлинной схеме `notes` из v13 (дословно из `app/schemas/.../13.json`),
     * проверяем: старые заметки сохраняются, новая колонка добавляется со
     * значением по умолчанию false (не ломает уже существующие «важные»
     * заметки, не помечает их внезапно как приватные).
     */
    @Test
    fun migration13to14_addsIsLocked_defaultsFalse_keepsExistingNotes() {
        val db = openDb(13) {
            // Дословно из app/schemas/.../13.json — подлинная схема v13, не догадка.
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `contactId` TEXT, `companyId` TEXT, " +
                "`calendarItemId` TEXT, `giftId` TEXT, `type` TEXT NOT NULL, `text` TEXT NOT NULL, `date` TEXT, " +
                "`isImportant` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
        }
        db.execSQL(
            "INSERT INTO notes (id, contactId, type, text, isImportant, createdAt, updatedAt) " +
            "VALUES ('n1', 'c1', 'GENERAL', 'Любит кофе', 1, '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_13_14.migrate(db)

        db.query("SELECT text, isImportant, isLocked FROM notes WHERE id='n1'").use { c ->
            assertTrue("заметка должна дожить до v14", c.moveToFirst())
            assertEquals("Любит кофе", c.getString(0))   // текст цел
            assertEquals(1, c.getInt(1))                  // isImportant не тронут
            assertEquals(0, c.getInt(2))                  // isLocked = false по умолчанию
        }
        db.close()
    }

    /**
     * phoneticMiddleName (v15) — паритет с phoneticFirstName/phoneticLastName (v13).
     * Прогоняем MIGRATION_14_15 на подлинной схеме `contacts` из v14 (дословно из
     * `app/schemas/.../14.json`), проверяем: существующая строка с уже заполненными
     * phoneticFirstName/phoneticLastName доживает нетронутой, новая колонка
     * добавляется и равна NULL (не выдумывает значение).
     */
    @Test
    fun migration14to15_addsPhoneticMiddleName_defaultsNull_keepsExistingContact() {
        val db = openDb(14) {
            // Дословно из app/schemas/.../14.json — подлинная схема v14, не догадка.
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `contacts` (`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, " +
                "`lastName` TEXT NOT NULL, `middleName` TEXT, `nickname` TEXT, `namePrefix` TEXT, " +
                "`nameSuffix` TEXT, `phoneticFirstName` TEXT, `phoneticLastName` TEXT, `photoUri` TEXT, " +
                "`relationshipType` TEXT NOT NULL, `customRelationshipType` TEXT, `connectionLevel` TEXT NOT NULL, " +
                "`importanceLevel` TEXT NOT NULL, `socialRole` TEXT NOT NULL, `communicationRhythm` TEXT NOT NULL, " +
                "`contactStatus` TEXT NOT NULL DEFAULT 'ACTIVE', `lastContactDate` TEXT, `nextStep` TEXT, " +
                "`familyNote` TEXT, `profession` TEXT, `tags` TEXT, `canHelpWith` TEXT, `iCanHelpWith` TEXT, " +
                "`talkingPoints` TEXT, `meetContext` TEXT, `meetDate` TEXT, `deviceContactId` TEXT, " +
                "`createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
        }
        db.execSQL(
            "INSERT INTO contacts (id, firstName, lastName, phoneticFirstName, phoneticLastName, " +
            "relationshipType, connectionLevel, importanceLevel, socialRole, communicationRhythm, " +
            "createdAt, updatedAt) VALUES ('c1', 'Юки', 'Танака', 'Yuki', 'Tanaka', 'FRIEND', 'NORMAL', " +
            "'NORMAL', 'REGULAR', 'NOT_TRACKED', '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_14_15.migrate(db)

        db.query(
            "SELECT firstName, lastName, phoneticFirstName, phoneticLastName, phoneticMiddleName " +
            "FROM contacts WHERE id='c1'"
        ).use { c ->
            assertTrue("контакт должен дожить до v15", c.moveToFirst())
            assertEquals("Юки", c.getString(0))
            assertEquals("Танака", c.getString(1))
            assertEquals("Yuki", c.getString(2))     // старая фонетика не тронута
            assertEquals("Tanaka", c.getString(3))
            assertTrue("phoneticMiddleName должно быть NULL по умолчанию", c.isNull(4))
        }
        db.close()
    }

    /**
     * district (v16, 2026-07-13, фидбэк владельца: «при вводе адреса нужен район,
     * помимо город/страну») — тот же безопасный паттерн, что postalCode в
     * MIGRATION_4_5. Прогоняем MIGRATION_15_16 на подлинной схеме `addresses` из
     * v15 (дословно из `app/schemas/.../15.json`), проверяем: существующий адрес
     * со всеми уже заполненными полями доживает нетронутым, новая колонка district
     * добавляется и равна NULL (не выдумывает значение).
     */
    @Test
    fun migration15to16_addsDistrict_defaultsNull_keepsExistingAddress() {
        val db = openDb(15) {
            // Дословно из app/schemas/.../15.json — подлинная схема v15, не догадка.
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `addresses` (`id` TEXT NOT NULL, `ownerType` TEXT NOT NULL, " +
                "`ownerId` TEXT NOT NULL, `addressType` TEXT NOT NULL, `addressLine` TEXT NOT NULL, " +
                "`city` TEXT NOT NULL, `country` TEXT NOT NULL, `comment` TEXT, `latitude` REAL, " +
                "`longitude` REAL, `postalCode` TEXT, PRIMARY KEY(`id`))"
            )
        }
        db.execSQL(
            "INSERT INTO addresses (id, ownerType, ownerId, addressType, addressLine, city, country, " +
            "postalCode) VALUES ('a1', 'CONTACT', 'c1', 'HOME', 'Тверская 1', 'Москва', 'Россия', '125009')"
        )

        SocialsphereDatabase.MIGRATION_15_16.migrate(db)

        db.query(
            "SELECT addressLine, city, country, postalCode, district FROM addresses WHERE id='a1'"
        ).use { c ->
            assertTrue("адрес должен дожить до v16", c.moveToFirst())
            assertEquals("Тверская 1", c.getString(0))   // старые данные целы
            assertEquals("Москва", c.getString(1))
            assertEquals("Россия", c.getString(2))
            assertEquals("125009", c.getString(3))        // postalCode не тронут
            assertTrue("district должно быть NULL по умолчанию", c.isNull(4))
        }
        db.close()
    }

    /**
     * secondaryRelationshipTypes + customRhythmDays (v17, 2026-07-23, решение
     * владельца): второстепенные типы отношений (comma-joined, паттерн как tags)
     * и «раз в N дней» для CommunicationRhythm.CUSTOM. Прогоняем MIGRATION_16_17
     * на подлинной схеме `contacts` из v16 (дословно из `app/schemas/.../16.json`),
     * проверяем: существующий контакт со всеми уже заполненными полями доживает
     * нетронутым, новые колонки добавляются с ожидаемыми дефолтами —
     * secondaryRelationshipTypes = '' (не NULL), customRhythmDays = NULL.
     */
    @Test
    fun migration16to17_addsSecondaryRelationshipTypesAndCustomRhythmDays_keepsExistingContact() {
        val db = openDb(16) {
            // Дословно из app/schemas/.../16.json — подлинная схема v16, не догадка.
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `contacts` (`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, " +
                "`lastName` TEXT NOT NULL, `middleName` TEXT, `nickname` TEXT, `namePrefix` TEXT, " +
                "`nameSuffix` TEXT, `phoneticFirstName` TEXT, `phoneticMiddleName` TEXT, `phoneticLastName` TEXT, " +
                "`photoUri` TEXT, `relationshipType` TEXT NOT NULL, `customRelationshipType` TEXT, " +
                "`connectionLevel` TEXT NOT NULL, `importanceLevel` TEXT NOT NULL, `socialRole` TEXT NOT NULL, " +
                "`communicationRhythm` TEXT NOT NULL, `contactStatus` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                "`lastContactDate` TEXT, `nextStep` TEXT, `familyNote` TEXT, `profession` TEXT, `tags` TEXT, " +
                "`canHelpWith` TEXT, `iCanHelpWith` TEXT, `talkingPoints` TEXT, `meetContext` TEXT, " +
                "`meetDate` TEXT, `deviceContactId` TEXT, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
            )
        }
        db.execSQL(
            "INSERT INTO contacts (id, firstName, lastName, relationshipType, connectionLevel, " +
            "importanceLevel, socialRole, communicationRhythm, tags, createdAt, updatedAt) " +
            "VALUES ('c1', 'Иван', 'Петров', 'FRIEND', 'NORMAL', 'NORMAL', 'REGULAR', 'WEEKLY', " +
            "'семья,работа', '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_16_17.migrate(db)

        db.query(
            "SELECT firstName, lastName, tags, secondaryRelationshipTypes, customRhythmDays " +
            "FROM contacts WHERE id='c1'"
        ).use { c ->
            assertTrue("контакт должен дожить до v17", c.moveToFirst())
            assertEquals("Иван", c.getString(0))          // старые данные целы
            assertEquals("Петров", c.getString(1))
            assertEquals("семья,работа", c.getString(2))  // tags не тронут
            assertEquals("", c.getString(3))               // secondaryRelationshipTypes = '' по умолчанию
            assertTrue("customRhythmDays должно быть NULL по умолчанию", c.isNull(4))
        }
        db.close()
    }

    /**
     * Теги контактов (v18, 2026-07-11, прямой запрос владельца — «кто чем
     * занимается»: электрик, продаёт масло, сдаёт квартиру...). MIGRATION_17_18
     * только создаёт две новые таблицы (tags, contact_tag_members) — ALTER на
     * существующих таблицах нет. Проверяем: существующий контакт из v17
     * доживает нетронутым, новые таблицы создаются и в них можно писать/читать.
     */
    @Test
    fun migration17to18_addsTagTables_keepsExistingContact() {
        val db = openDb(17) {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `contacts` (`id` TEXT NOT NULL, `firstName` TEXT NOT NULL, " +
                "`lastName` TEXT NOT NULL, `middleName` TEXT, `nickname` TEXT, `namePrefix` TEXT, " +
                "`nameSuffix` TEXT, `phoneticFirstName` TEXT, `phoneticMiddleName` TEXT, `phoneticLastName` TEXT, " +
                "`photoUri` TEXT, `relationshipType` TEXT NOT NULL, `customRelationshipType` TEXT, " +
                "`secondaryRelationshipTypes` TEXT NOT NULL DEFAULT '', " +
                "`connectionLevel` TEXT NOT NULL, `importanceLevel` TEXT NOT NULL, `socialRole` TEXT NOT NULL, " +
                "`communicationRhythm` TEXT NOT NULL, `customRhythmDays` INTEGER, " +
                "`contactStatus` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                "`lastContactDate` TEXT, `nextStep` TEXT, `familyNote` TEXT, `profession` TEXT, `tags` TEXT, " +
                "`canHelpWith` TEXT, `iCanHelpWith` TEXT, `talkingPoints` TEXT, `meetContext` TEXT, " +
                "`meetDate` TEXT, `deviceContactId` TEXT, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
            )
        }
        db.execSQL(
            "INSERT INTO contacts (id, firstName, lastName, relationshipType, connectionLevel, " +
            "importanceLevel, socialRole, communicationRhythm, tags, createdAt, updatedAt) " +
            "VALUES ('c1', 'Иван', 'Петров', 'FRIEND', 'NORMAL', 'NORMAL', 'REGULAR', 'WEEKLY', " +
            "'семья,работа', '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_17_18.migrate(db)

        db.query("SELECT firstName, lastName, tags FROM contacts WHERE id='c1'").use { c ->
            assertTrue("контакт должен дожить до v18", c.moveToFirst())
            assertEquals("Иван", c.getString(0))
            assertEquals("Петров", c.getString(1))
            assertEquals("семья,работа", c.getString(2)) // легаси-теги не тронуты
        }

        // Новые таблицы существуют и рабочие (пишем/читаем тег + привязку).
        db.execSQL(
            "INSERT INTO tags (id, name, category, createdAt, updatedAt) " +
            "VALUES ('t1', 'Электрик', 'Услуги на дому', '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )
        db.execSQL("INSERT INTO contact_tag_members (id, tagId, contactId) VALUES ('m1', 't1', 'c1')")
        db.query("SELECT name, category FROM tags WHERE id='t1'").use { c ->
            assertTrue("тег должен быть читаем", c.moveToFirst())
            assertEquals("Электрик", c.getString(0))
            assertEquals("Услуги на дому", c.getString(1))
        }
        db.query("SELECT tagId, contactId FROM contact_tag_members WHERE id='m1'").use { c ->
            assertTrue("привязка тега к контакту должна быть читаема", c.moveToFirst())
            assertEquals("t1", c.getString(0))
            assertEquals("c1", c.getString(1))
        }
        db.close()
    }

    // ═════════════════════════════════════════════════════════════
    //  v19: schema-foundation для recurring-completion/explicit-NONE.
    //  MIGRATION_18_19 добавляет к calendar_items lastCompletedOccurrenceDate
    //  (nullable TEXT, дефолт NULL) и recurrenceExplicitlySet (NOT NULL
    //  INTEGER DEFAULT 0) — ALTER на существующей таблице, без DROP/пересоздания.
    //  Подлинная схема v18 (дословно из app/schemas/.../18.json).
    // ═════════════════════════════════════════════════════════════

    private fun createV18CalendarTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_items` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`description` TEXT, `type` TEXT NOT NULL, `startDate` TEXT NOT NULL, `startTime` TEXT, " +
            "`endDate` TEXT, `endTime` TEXT, `isAllDay` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
            "`importance` TEXT NOT NULL, `colorKey` TEXT, `recurrenceRule` TEXT, `createdAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_item_links` (`id` TEXT NOT NULL, " +
            "`calendarItemId` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminder_rules` (`id` TEXT NOT NULL, `calendarItemId` TEXT NOT NULL, " +
            "`reminderType` TEXT NOT NULL, `offsetValue` INTEGER, `offsetUnit` TEXT, `exactDateTime` TEXT, " +
            "PRIMARY KEY(`id`))"
        )
    }

    // ─── A. legacy BIRTHDAY (rule=null) → explicit=0, lastCompleted=NULL,
    // effective recurrence (вычисленная отдельно, не миграцией) остаётся YEARYLY ───

    @Test
    fun migration18to19_legacyBirthdayWithoutRule_addsColumnsWithLegacyDefaults() {
        val db = openDb(18) { createV18CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, type, startDate, isAllDay, status, importance, " +
            "createdAt, updatedAt) VALUES ('bday1', 'ДР Ивана', 'BIRTHDAY', '1990-06-15', 1, 'ACTIVE', " +
            "'NORMAL', '2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_18_19.migrate(db)

        db.query(
            "SELECT title, recurrenceRule, recurrenceExplicitlySet, lastCompletedOccurrenceDate " +
            "FROM calendar_items WHERE id='bday1'"
        ).use { c ->
            assertTrue("legacy ДР должна дожить до v19", c.moveToFirst())
            assertEquals("ДР Ивана", c.getString(0))       // старые данные целы
            assertTrue("recurrenceRule остаётся NULL — миграция его не трогает", c.isNull(1))
            assertEquals("recurrenceExplicitlySet = 0 по умолчанию (legacy)", 0, c.getInt(2))
            assertTrue("lastCompletedOccurrenceDate = NULL по умолчанию", c.isNull(3))
        }
        // Эффективная семантика (не часть миграции — canonical policy) обязана
        // остаться YEARLY для этой legacy-записи, как и до v19.
        assertEquals(com.aistudio.socialsphere.crmlxb.model.RecurrenceMode.YEARLY,
            com.aistudio.socialsphere.crmlxb.utils.effectiveRecurrenceMode(
                com.aistudio.socialsphere.crmlxb.model.CalendarItemType.BIRTHDAY, null, recurrenceExplicitlySet = false))
        db.close()
    }

    // ─── B. обычный CALL (rule=null) → explicit=0, но effective recurrence NONE ───

    @Test
    fun migration18to19_normalCallWithoutRule_explicitFalse_effectiveRecurrenceStaysNone() {
        val db = openDb(18) { createV18CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, type, startDate, isAllDay, status, importance, " +
            "createdAt, updatedAt) VALUES ('call1', 'Звонок клиенту', 'CALL', '2026-01-01', 0, 'ACTIVE', " +
            "'NORMAL', '2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_18_19.migrate(db)

        db.query("SELECT recurrenceExplicitlySet FROM calendar_items WHERE id='call1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        assertEquals(com.aistudio.socialsphere.crmlxb.model.RecurrenceMode.NONE,
            com.aistudio.socialsphere.crmlxb.utils.effectiveRecurrenceMode(
                com.aistudio.socialsphere.crmlxb.model.CalendarItemType.CALL, null, recurrenceExplicitlySet = false))
        db.close()
    }

    // ─── C. известный FREQ=WEEKLY → raw rule неизменён после миграции ───

    @Test
    fun migration18to19_knownWeeklyRule_rawRuleUnchanged() {
        val db = openDb(18) { createV18CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, type, startDate, isAllDay, status, importance, " +
            "recurrenceRule, createdAt, updatedAt) VALUES ('w1', 'Еженедельная встреча', 'MEETING', " +
            "'2026-01-07', 0, 'ACTIVE', 'NORMAL', 'FREQ=WEEKLY', '2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_18_19.migrate(db)

        db.query("SELECT recurrenceRule, recurrenceExplicitlySet FROM calendar_items WHERE id='w1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("FREQ=WEEKLY", c.getString(0)) // raw rule не тронут
            assertEquals(0, c.getInt(1))                  // explicit — легаси-дефолт, миграция его не решает
        }
        db.close()
    }

    // ─── MIGRATION SAFETY: links и reminder_rules остаются привязаны к тем же
    // item ID после миграции (MIGRATION_18_19 их не трогает — только ALTER на
    // calendar_items, но проверяем явно, а не только «база открылась») ───

    @Test
    fun migration18to19_preservesCalendarItemLinksAndReminderRules_sameItemIds() {
        val db = openDb(18) { createV18CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, type, startDate, isAllDay, status, importance, " +
            "createdAt, updatedAt) VALUES ('ev1', 'Событие', 'MEETING', '2026-01-01', 0, 'ACTIVE', " +
            "'NORMAL', '2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )
        db.execSQL(
            "INSERT INTO calendar_item_links (id, calendarItemId, targetType, targetId) " +
            "VALUES ('l1', 'ev1', 'CONTACT', 'c1')"
        )
        db.execSQL(
            "INSERT INTO reminder_rules (id, calendarItemId, reminderType) VALUES ('r1', 'ev1', 'AT_TIME')"
        )

        SocialsphereDatabase.MIGRATION_18_19.migrate(db)

        db.query("SELECT calendarItemId, targetId FROM calendar_item_links WHERE id='l1'").use { c ->
            assertTrue("связь события с контактом должна дожить до v19", c.moveToFirst())
            assertEquals("ev1", c.getString(0))
            assertEquals("c1", c.getString(1))
        }
        db.query("SELECT calendarItemId, reminderType FROM reminder_rules WHERE id='r1'").use { c ->
            assertTrue("напоминание должно дожить до v19", c.moveToFirst())
            assertEquals("ev1", c.getString(0))
            assertEquals("AT_TIME", c.getString(1))
        }
        db.close()
    }

    // ─── NEGATIVE PROOF: если бы миграция массово выставляла explicit=1 (или
    // NOT NULL без DEFAULT), эта вставка провалилась бы / тест выше упал.
    // Явно проверяем нижнюю границу — DEFAULT именно 0, не 1. ───

    @Test
    fun migration18to19_defaultIsExactlyZero_notOne() {
        val db = openDb(18) { createV18CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, type, startDate, isAllDay, status, importance, " +
            "createdAt, updatedAt) VALUES ('x1', 'x', 'TASK', '2026-01-01', 0, 'ACTIVE', 'NORMAL', " +
            "'2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )
        SocialsphereDatabase.MIGRATION_18_19.migrate(db)
        db.query("SELECT recurrenceExplicitlySet FROM calendar_items WHERE id='x1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DEFAULT обязан быть 0 — массовое true сломало бы legacy YEARLY-fallback", 0, c.getInt(0))
        }
        db.close()
    }

    // ═════════════════════════════════════════════════════════════
    //  v20: persistence-foundation для postpone ОДНОГО occurrence recurring
    //  CalendarItem (2026-09-17). MIGRATION_19_20 добавляет
    //  postponedFromOccurrenceDate/postponedToDate (оба nullable TEXT, дефолт
    //  NULL) — ALTER на существующей таблице, без DROP/пересоздания. Никакой
    //  runtime-логики (pendingOccurrenceDate/CalendarCompletion/
    //  NotificationScheduler/CalendarScreen) эта миграция не подключает —
    //  только schema. Подлинная схема v19 (дословно из
    //  app/schemas/.../19.json), не догадка.
    // ═════════════════════════════════════════════════════════════

    private fun createV19CalendarTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_items` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`description` TEXT, `type` TEXT NOT NULL, `startDate` TEXT NOT NULL, `startTime` TEXT, " +
            "`endDate` TEXT, `endTime` TEXT, `isAllDay` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
            "`importance` TEXT NOT NULL, `colorKey` TEXT, `recurrenceRule` TEXT, " +
            "`lastCompletedOccurrenceDate` TEXT, `recurrenceExplicitlySet` INTEGER NOT NULL DEFAULT 0, " +
            "`createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_item_links` (`id` TEXT NOT NULL, " +
            "`calendarItemId` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminder_rules` (`id` TEXT NOT NULL, `calendarItemId` TEXT NOT NULL, " +
            "`reminderType` TEXT NOT NULL, `offsetValue` INTEGER, `offsetUnit` TEXT, `exactDateTime` TEXT, " +
            "PRIMARY KEY(`id`))"
        )
    }

    // ─── A. Migration 19→20 basic: existing recurring CalendarItem -> после
    // migration postponedFromOccurrenceDate=null, postponedToDate=null, все
    // старые поля идентичны ───

    @Test
    fun migration19to20_basic_addsPostponeColumnsAsNull_keepsAllOldFieldsIdentical() {
        val db = openDb(19) { createV19CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, description, type, startDate, startTime, endDate, endTime, " +
            "isAllDay, status, importance, colorKey, recurrenceRule, lastCompletedOccurrenceDate, " +
            "recurrenceExplicitlySet, createdAt, updatedAt) VALUES ('ev1', 'Еженедельная встреча', " +
            "'Обсудить проект', 'MEETING', '2026-09-16', '10:00', NULL, NULL, 0, 'ACTIVE', 'NORMAL', " +
            "'blue', 'FREQ=WEEKLY', NULL, 1, '2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_19_20.migrate(db)

        db.query(
            "SELECT title, description, type, startDate, startTime, isAllDay, status, importance, colorKey, " +
            "recurrenceRule, lastCompletedOccurrenceDate, recurrenceExplicitlySet, " +
            "postponedFromOccurrenceDate, postponedToDate FROM calendar_items WHERE id='ev1'"
        ).use { c ->
            assertTrue("строка должна дожить до v20", c.moveToFirst())
            assertEquals("Еженедельная встреча", c.getString(0))    // старые поля идентичны
            assertEquals("Обсудить проект", c.getString(1))
            assertEquals("MEETING", c.getString(2))
            assertEquals("2026-09-16", c.getString(3))
            assertEquals("10:00", c.getString(4))
            assertEquals(0, c.getInt(5))
            assertEquals("ACTIVE", c.getString(6))
            assertEquals("NORMAL", c.getString(7))
            assertEquals("blue", c.getString(8))
            assertEquals("FREQ=WEEKLY", c.getString(9))
            assertTrue(c.isNull(10))
            assertEquals(1, c.getInt(11))
            assertTrue("postponedFromOccurrenceDate = NULL по умолчанию", c.isNull(12))
            assertTrue("postponedToDate = NULL по умолчанию", c.isNull(13))
        }
        db.close()
    }

    // ─── B. Migration preserves v19 recurrence state: recurrenceExplicitlySet,
    // lastCompletedOccurrenceDate, recurrenceRule ───

    @Test
    fun migration19to20_preservesV19RecurrenceState() {
        val db = openDb(19) { createV19CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, type, startDate, isAllDay, status, importance, " +
            "recurrenceRule, lastCompletedOccurrenceDate, recurrenceExplicitlySet, createdAt, updatedAt) " +
            "VALUES ('ev2', 'Weekly call', 'CALL', '2026-08-05', 0, 'ACTIVE', 'NORMAL', 'FREQ=WEEKLY', " +
            "'2026-09-09', 1, '2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_19_20.migrate(db)

        db.query(
            "SELECT recurrenceRule, lastCompletedOccurrenceDate, recurrenceExplicitlySet " +
            "FROM calendar_items WHERE id='ev2'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("recurrenceRule не тронут", "FREQ=WEEKLY", c.getString(0))
            assertEquals("lastCompletedOccurrenceDate не тронут", "2026-09-09", c.getString(1))
            assertEquals("recurrenceExplicitlySet не тронут", 1, c.getInt(2))
        }
        db.close()
    }

    // ─── C. Links/reminders preserved (та же проверка, что migration18to19_
    // preservesCalendarItemLinksAndReminderRules_sameItemIds, для 19→20) ───

    @Test
    fun migration19to20_preservesCalendarItemLinksAndReminderRules_sameItemIds() {
        val db = openDb(19) { createV19CalendarTables(it) }
        db.execSQL(
            "INSERT INTO calendar_items (id, title, type, startDate, isAllDay, status, importance, " +
            "createdAt, updatedAt) VALUES ('ev3', 'Событие', 'MEETING', '2026-01-01', 0, 'ACTIVE', " +
            "'NORMAL', '2025-01-01T00:00:00', '2025-01-01T00:00:00')"
        )
        db.execSQL(
            "INSERT INTO calendar_item_links (id, calendarItemId, targetType, targetId) " +
            "VALUES ('l1', 'ev3', 'CONTACT', 'c1')"
        )
        db.execSQL(
            "INSERT INTO reminder_rules (id, calendarItemId, reminderType) VALUES ('r1', 'ev3', 'AT_TIME')"
        )

        SocialsphereDatabase.MIGRATION_19_20.migrate(db)

        db.query("SELECT calendarItemId, targetId FROM calendar_item_links WHERE id='l1'").use { c ->
            assertTrue("связь события с контактом должна дожить до v20", c.moveToFirst())
            assertEquals("ev3", c.getString(0))
            assertEquals("c1", c.getString(1))
        }
        db.query("SELECT calendarItemId, reminderType FROM reminder_rules WHERE id='r1'").use { c ->
            assertTrue("напоминание должно дожить до v20", c.moveToFirst())
            assertEquals("ev3", c.getString(0))
            assertEquals("AT_TIME", c.getString(1))
        }
        db.close()
    }

    // ═════════════════════════════════════════════════════════════
    //  v21 (PHONE / VCF PACK #4): contacts.deviceLookupKey — устойчивая связь с контактом телефонной книги.
    //  Только аддитивно: одна NULLABLE-колонка; старая связь (deviceContactId) обязана дожить.
    // ═════════════════════════════════════════════════════════════

    private fun createV20Contacts(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE contacts (id TEXT NOT NULL PRIMARY KEY, firstName TEXT NOT NULL, lastName TEXT NOT NULL, " +
            "photoUri TEXT, relationshipType TEXT NOT NULL, connectionLevel TEXT NOT NULL, importanceLevel TEXT NOT NULL, " +
            "socialRole TEXT NOT NULL, communicationRhythm TEXT NOT NULL, deviceContactId TEXT, " +
            "createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)"
        )
    }

    @Test
    fun migration20to21_addsDeviceLookupKeyAsNull_keepsLegacyDeviceContactId_andAllFields() {
        val db = openDb(20) { createV20Contacts(it) }
        db.execSQL(
            "INSERT INTO contacts (id, firstName, lastName, photoUri, relationshipType, connectionLevel, importanceLevel, " +
            "socialRole, communicationRhythm, deviceContactId, createdAt, updatedAt) VALUES " +
            "('c1', 'Анна', 'Иванова', '/p.jpg', 'FRIEND', 'NORMAL', 'NORMAL', 'REGULAR', 'NOT_TRACKED', 'device_contact_10', " +
            "'2026-01-01T00:00:00', '2026-01-02T00:00:00')"
        )
        db.execSQL(
            "INSERT INTO contacts (id, firstName, lastName, relationshipType, connectionLevel, importanceLevel, socialRole, " +
            "communicationRhythm, createdAt, updatedAt) VALUES ('c2', 'Без', 'Связи', 'FRIEND', 'NORMAL', 'NORMAL', 'REGULAR', " +
            "'NOT_TRACKED', '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_20_21.migrate(db)

        db.query("SELECT firstName, lastName, photoUri, deviceContactId, deviceLookupKey, createdAt, updatedAt FROM contacts WHERE id='c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(listOf("Анна", "Иванова", "/p.jpg", "device_contact_10"), (0..3).map { c.getString(it) })
            assertTrue("новая колонка пуста: связь работает через фолбэк по id, ключ заполнится при первом resolve", c.isNull(4))
            assertEquals("2026-01-01T00:00:00" to "2026-01-02T00:00:00", c.getString(5) to c.getString(6))
        }
        db.query("SELECT deviceContactId, deviceLookupKey FROM contacts WHERE id='c2'").use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1))
        }
        // после миграции ключ можно записать и прочитать
        db.execSQL("UPDATE contacts SET deviceLookupKey = '0r1-ABC' WHERE id = 'c1'")
        db.query("SELECT deviceLookupKey FROM contacts WHERE id='c1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("0r1-ABC", c.getString(0)) }
        db.close()
    }

    // ═════════════════════════════════════════════════════════════
    //  INTEGRATION DATA SAFETY GATE #1 (2026-09-21) — РЕАЛЬНЫЙ путь обновления Room, а не прямой вызов migrate().
    //  Файловая БД строится из ПОДЛИННОЙ схемы app/schemas/.../<N>.json (createSql + setupQueries), заполняется
    //  данными «до обновления» и открывается ПРОДАКШЕН-билдером SocialsphereDatabase.getDatabase() — тем самым, что
    //  запускается на устройстве: тот же список addMigrations, без destructive-fallback. Room сам прогоняет цепочку
    //  и сверяет итоговую схему с 21.json: пропущенная регистрация миграции или расхождение схемы = исключение,
    //  а не тихая потеря данных. Ожидаемые значения — data-class-равенство с явно заданными константами.
    // ═════════════════════════════════════════════════════════════

    private val schemaDir = "schemas/com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase"
    private val productionDbName = "socialsphere_database"      // то же имя, что в SocialsphereDatabase.getDatabase()
    private val gT0 = "2026-01-01T00:00:00"

    private fun schemaOf(version: Int): org.json.JSONObject {
        val file = listOf(java.io.File("$schemaDir/$version.json"), java.io.File("app/$schemaDir/$version.json")).firstOrNull { it.exists() }
            ?: throw AssertionError("нет файла схемы v$version (cwd=${java.io.File(".").absolutePath})")
        return org.json.JSONObject(file.readText()).getJSONObject("database")
    }

    /** Строит на диске БД ровно такой формы, какой её оставила бы версия [version] приложения. */
    private fun createRealDb(version: Int): java.io.File {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath(productionDbName)
        file.parentFile?.mkdirs()
        listOf("", "-journal", "-wal", "-shm").forEach { java.io.File(file.path + it).delete() }
        val schema = schemaOf(version)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.getJSONArray("indices")
                for (j in 0 until indices.length()) raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.version = version
        }
        return file
    }

    /**
     * INSERT по именам колонок. Значения для колонок, которых в этой версии схемы ещё нет, пропускаются
     * (одна и та же фикстура годится для v18 и v20); прочие NOT NULL-колонки без DEFAULT получают нейтральное значение.
     */
    private fun insertRow(db: android.database.sqlite.SQLiteDatabase, table: String, values: Map<String, Any?>) {
        val cv = android.content.ContentValues()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            while (c.moveToNext()) {
                val col = c.getString(1)
                val isText = c.getString(2).uppercase() == "TEXT"
                val requiredWithoutDefault = c.getInt(3) == 1 && c.isNull(4)
                val v: Any? = if (values.containsKey(col)) values[col] else if (requiredWithoutDefault) (if (isText) "" else 0) else continue
                when (v) {
                    null -> cv.putNull(col)
                    is Boolean -> cv.put(col, if (v) 1 else 0)
                    is Int -> cv.put(col, v)
                    is Double -> cv.put(col, v)
                    else -> cv.put(col, v.toString())
                }
            }
        }
        db.insertOrThrow(table, null, cv)
    }

    /** Максимально заполненный набор: Contact, телефоны/почты/мессенджер, адреса+координаты, SizeInfo, Gift, Notes, Company, связь, календарь. */
    private fun populateOldCanary(raw: android.database.sqlite.SQLiteDatabase) {
        val t0 = gT0
        fun row(table: String, vararg kv: Pair<String, Any?>) = insertRow(raw, table, mapOf(*kv))
        row("companies", "id" to "co-m1", "name" to "Acme Migration", "logoUri" to "/logo.png", "industry" to "LOGISTICS",
            "description" to "Описание компании", "website" to "https://acme.example", "createdAt" to t0, "updatedAt" to t0)
        row("contacts", "id" to "c-m1", "firstName" to "Анна", "lastName" to "Миграционная", "middleName" to "Сергеевна", "nickname" to "Аня",
            "photoUri" to "/anna.jpg", "relationshipType" to "CLIENT", "customRelationshipType" to "Кума",
            "secondaryRelationshipTypes" to "FRIEND,COLLEAGUE", "connectionLevel" to "CLOSE", "importanceLevel" to "KEY",
            "socialRole" to "EXPERT", "communicationRhythm" to "CUSTOM", "customRhythmDays" to 45, "contactStatus" to "MAINTAIN",
            "lastContactDate" to "2026-09-01", "nextStep" to "Позвонить в пятницу", "familyNote" to "Муж и двое детей",
            "profession" to "Юрист", "tags" to "семья,работа", "canHelpWith" to "Договоры", "iCanHelpWith" to "Контакты",
            "talkingPoints" to "Футбол", "meetContext" to "Конференция", "meetDate" to "2024-05-05",
            "deviceContactId" to "device_contact_77", "createdAt" to t0, "updatedAt" to "2026-02-02T00:00:00")
        row("contacts", "id" to "c-m2", "firstName" to "Борис", "lastName" to "Без-связи", "relationshipType" to "FRIEND",
            "connectionLevel" to "NORMAL", "importanceLevel" to "NORMAL", "socialRole" to "REGULAR", "communicationRhythm" to "NOT_TRACKED",
            "createdAt" to t0, "updatedAt" to t0)
        row("contact_phones", "id" to "p-m1", "contactId" to "c-m1", "companyId" to null, "number" to "+79000000001", "type" to "MOBILE", "isPrimary" to true, "comment" to null)
        row("contact_phones", "id" to "p-m2", "contactId" to "c-m1", "companyId" to null, "number" to "+74950000003", "type" to "WORK", "isPrimary" to false, "comment" to "рабочий")
        row("contact_phones", "id" to "p-m3", "contactId" to null, "companyId" to "co-m1", "number" to "+74950000001", "type" to "WORK", "isPrimary" to true, "comment" to "офис")
        row("contact_emails", "id" to "e-m1", "contactId" to "c-m1", "companyId" to null, "email" to "anna@example.com", "type" to "PERSONAL", "isPrimary" to true, "comment" to null)
        row("contact_emails", "id" to "e-m2", "contactId" to "c-m1", "companyId" to null, "email" to "anna@work.example", "type" to "WORK", "isPrimary" to false, "comment" to "работа")
        row("messengers", "id" to "m-m1", "contactId" to "c-m1", "type" to "TELEGRAM", "value" to "@anna", "link" to "https://t.me/anna", "isPrimary" to true, "comment" to "основной")
        row("addresses", "id" to "a-m1", "ownerType" to "CONTACT", "ownerId" to "c-m1", "addressType" to "HOME", "addressLine" to "Ленина 5", "city" to "Москва",
            "country" to "РФ", "comment" to "домофон 12", "latitude" to 55.75, "longitude" to 37.61, "postalCode" to "119000", "district" to "Арбат")
        row("addresses", "id" to "a-m2", "ownerType" to "COMPANY", "ownerId" to "co-m1", "addressType" to "OFFICE", "addressLine" to "Тверская 1", "city" to "Москва",
            "country" to "РФ", "comment" to null, "latitude" to 55.76, "longitude" to 37.62, "postalCode" to "101000", "district" to null)
        row("size_infos", "id" to "s-m1", "contactId" to "c-m1", "clothingSize" to "M", "shoeSize" to "39", "ringSize" to "16", "other" to "перчатки S")
        row("gift_ideas", "id" to "g-m1", "contactId" to "c-m1", "title" to "Книга", "note" to "автор Х", "link" to "https://shop.example/1", "date" to "2026-12-01", "reminderId" to null, "status" to "IDEA")
        row("gift_ideas", "id" to "g-m2", "contactId" to "c-m1", "title" to "Шарф", "note" to null, "link" to null, "date" to null, "reminderId" to null, "status" to "BOUGHT")
        row("notes", "id" to "n-m1", "contactId" to "c-m1", "companyId" to null, "calendarItemId" to null, "giftId" to null, "type" to "GENERAL", "text" to "Заметка контакта",
            "date" to "2026-08-01", "isImportant" to true, "isLocked" to true, "createdAt" to t0, "updatedAt" to t0)
        row("notes", "id" to "n-m2", "contactId" to null, "companyId" to "co-m1", "calendarItemId" to null, "giftId" to null, "type" to "WORK", "text" to "Заметка компании",
            "date" to null, "isImportant" to false, "isLocked" to false, "createdAt" to t0, "updatedAt" to t0)
        row("contact_company_relations", "id" to "rel-m1", "contactId" to "c-m1", "companyId" to "co-m1", "position" to "Директор", "department" to "Отдел продаж",
            "role" to "Руководитель", "employmentStatus" to "CURRENT", "startDate" to "2020-01-01", "endDate" to null, "responsibilities" to "Управление",
            "managedAccounts" to "Ключевые клиенты", "workNote" to "заметка", "officeAddressId" to "a-m2", "isPrimary" to true)
        row("contact_relations", "id" to "cr-m1", "firstContactId" to "c-m1", "secondContactId" to "c-m2", "firstRole" to "Друг", "secondRole" to "Друг", "note" to "школа")
        row("personal_details", "id" to "d-m1", "contactId" to "c-m1", "category" to "LIKES", "value" to "чай", "note" to "зелёный")
        row("contact_groups", "id" to "grp-m1", "name" to "Клиенты", "createdAt" to t0, "updatedAt" to t0)
        row("contact_group_members", "id" to "gm-m1", "groupId" to "grp-m1", "contactId" to "c-m1")
        row("tags", "id" to "tag-m1", "name" to "КаноТег", "category" to "Категория А", "createdAt" to t0, "updatedAt" to t0)
        row("contact_tag_members", "id" to "tm-m1", "tagId" to "tag-m1", "contactId" to "c-m1")
        // Поля recurrence (v19) и postpone (v20) в БД v18 отсутствуют — insertRow их пропустит; в v20 они заполнены и обязаны дожить.
        row("calendar_items", "id" to "cal-m1", "title" to "Встреча с Анной", "description" to "обсудить договор", "type" to "MEETING", "startDate" to "2027-03-10",
            "startTime" to "10:30", "endDate" to "2027-03-10", "endTime" to "11:30", "isAllDay" to false, "status" to "ACTIVE", "importance" to "IMPORTANT",
            "colorKey" to "blue", "recurrenceRule" to "FREQ=WEEKLY", "lastCompletedOccurrenceDate" to "2027-03-03", "recurrenceExplicitlySet" to true,
            "postponedFromOccurrenceDate" to "2027-03-17", "postponedToDate" to "2027-03-19", "createdAt" to t0, "updatedAt" to t0)
        row("calendar_item_links", "id" to "l-m1", "calendarItemId" to "cal-m1", "targetType" to "CONTACT", "targetId" to "c-m1")
        row("calendar_item_links", "id" to "l-m2", "calendarItemId" to "cal-m1", "targetType" to "COMPANY", "targetId" to "co-m1")
        row("reminder_rules", "id" to "r-m1", "calendarItemId" to "cal-m1", "reminderType" to "BEFORE", "offsetValue" to 30, "offsetUnit" to "MINUTES", "exactDateTime" to null)
        row("reminder_rules", "id" to "r-m2", "calendarItemId" to "cal-m1", "reminderType" to "CUSTOM_DATE_TIME", "offsetValue" to null, "offsetUnit" to null, "exactDateTime" to "2027-03-09T09:00")
    }

    private fun resetProductionSingleton() {
        val field = SocialsphereDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (field.get(null) as? SocialsphereDatabase)?.close()
        field.set(null, null)
    }

    /** Готовит БД версии [fromVersion] с канарейкой и открывает её продакшен-билдером (Room мигрирует + валидирует схему). */
    private fun upgradeThroughProductionBuilder(fromVersion: Int): SocialsphereDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        resetProductionSingleton()
        val file = createRealDb(fromVersion)
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { populateOldCanary(it) }
        return SocialsphereDatabase.getDatabase(ctx)
    }

    private fun assertOldCanarySurvived(db: SocialsphereDatabase, fromVersion: Int) = runBlocking {
        val t0 = gT0
        assertEquals("схема после обновления — v22", 22, db.openHelper.readableDatabase.version)

        assertEquals(
            ContactEntity(id = "c-m1", firstName = "Анна", lastName = "Миграционная", middleName = "Сергеевна", nickname = "Аня", photoUri = "/anna.jpg",
                relationshipType = "CLIENT", customRelationshipType = "Кума", secondaryRelationshipTypes = "FRIEND,COLLEAGUE", connectionLevel = "CLOSE",
                importanceLevel = "KEY", socialRole = "EXPERT", communicationRhythm = "CUSTOM", customRhythmDays = 45, contactStatus = "MAINTAIN",
                lastContactDate = "2026-09-01", nextStep = "Позвонить в пятницу", familyNote = "Муж и двое детей", profession = "Юрист",
                tags = "семья,работа", canHelpWith = "Договоры", iCanHelpWith = "Контакты", talkingPoints = "Футбол", meetContext = "Конференция",
                meetDate = "2024-05-05", deviceContactId = "device_contact_77", deviceLookupKey = null, createdAt = t0, updatedAt = "2026-02-02T00:00:00"),
            db.contactDao().getAllContacts().first { it.id == "c-m1" })
        assertEquals("контакт без связи остался без связи", listOf<String?>(null, null),
            db.contactDao().getAllContacts().first { it.id == "c-m2" }.let { listOf(it.deviceContactId, it.deviceLookupKey) })
        assertEquals(setOf("c-m1", "c-m2"), db.contactDao().getAllContacts().map { it.id }.toSet())

        assertEquals(listOf(
            ContactPhoneEntity("p-m1", "c-m1", null, "+79000000001", "MOBILE", true, null),
            ContactPhoneEntity("p-m2", "c-m1", null, "+74950000003", "WORK", false, "рабочий"),
            ContactPhoneEntity("p-m3", null, "co-m1", "+74950000001", "WORK", true, "офис")), db.contactDao().getContactPhones().sortedBy { it.id })
        assertEquals(listOf(
            ContactEmailEntity("e-m1", "c-m1", null, "anna@example.com", "PERSONAL", true, null),
            ContactEmailEntity("e-m2", "c-m1", null, "anna@work.example", "WORK", false, "работа")), db.contactDao().getContactEmails().sortedBy { it.id })
        assertEquals(listOf(MessengerEntity("m-m1", "c-m1", "TELEGRAM", "@anna", "https://t.me/anna", true, "основной")), db.contactDao().getMessengers())
        assertEquals("адреса + координаты", listOf(
            AddressEntity("a-m1", "CONTACT", "c-m1", "HOME", "Ленина 5", "Москва", "РФ", "домофон 12", 55.75, 37.61, "119000", "Арбат"),
            AddressEntity("a-m2", "COMPANY", "co-m1", "OFFICE", "Тверская 1", "Москва", "РФ", null, 55.76, 37.62, "101000", null)),
            db.addressDao().getAllAddresses().sortedBy { it.id })
        assertEquals(listOf(SizeInfoEntity("s-m1", "c-m1", "M", "39", "16", "перчатки S")), db.contactDao().getSizeInfos())
        assertEquals("Gift Ideas", listOf(
            GiftIdeaEntity("g-m1", "c-m1", "Книга", "автор Х", "https://shop.example/1", "2026-12-01", null, "IDEA"),
            GiftIdeaEntity("g-m2", "c-m1", "Шарф", null, null, null, null, "BOUGHT")), db.giftDao().getAllGifts().sortedBy { it.id })
        assertEquals("Notes", listOf(
            NoteEntity("n-m1", "c-m1", null, null, null, "GENERAL", "Заметка контакта", "2026-08-01", true, true, t0, t0),
            NoteEntity("n-m2", null, "co-m1", null, null, "WORK", "Заметка компании", null, false, false, t0, t0)), db.noteDao().getAllNotes().sortedBy { it.id })
        assertEquals(listOf(CompanyEntity("co-m1", "Acme Migration", "/logo.png", "LOGISTICS", "Описание компании", "https://acme.example", t0, t0)),
            db.companyDao().getAllCompanies())
        assertEquals("связь Contact ↔ Company", listOf(ContactCompanyRelationEntity("rel-m1", "c-m1", "co-m1", "Директор", "Отдел продаж", "Руководитель",
            "CURRENT", "2020-01-01", null, "Управление", "Ключевые клиенты", "заметка", "a-m2", true)), db.contactDao().getContactCompanyRelations())
        assertEquals(listOf(ContactRelationEntity("cr-m1", "c-m1", "c-m2", "Друг", "Друг", "школа")), db.contactDao().getContactRelations())
        assertEquals(listOf(PersonalDetailEntity("d-m1", "c-m1", "LIKES", "чай", "зелёный")), db.contactDao().getPersonalDetails())
        assertEquals(listOf(ContactGroupEntity("grp-m1", "Клиенты", t0, t0)), db.contactDao().getContactGroups())
        assertEquals(listOf(ContactGroupMemberEntity("gm-m1", "grp-m1", "c-m1")), db.contactDao().getContactGroupMembers())
        assertEquals(listOf(TagEntity("tag-m1", "КаноТег", "Категория А", t0, t0)), db.contactDao().getTags().first())
        assertEquals(listOf(ContactTagMemberEntity("tm-m1", "tag-m1", "c-m1")), db.contactDao().getContactTagMembersOnce())

        // Calendar: v19-поля recurrence и v20-поля postpone. Из v20 — значения доживают; из v18 колонок не было — легаси-дефолты.
        val v20 = fromVersion >= 20
        assertEquals("Calendar recurrence + postpone", CalendarItemEntity("cal-m1", "Встреча с Анной", "обсудить договор", "MEETING", "2027-03-10", "10:30",
            "2027-03-10", "11:30", false, "ACTIVE", "IMPORTANT", "blue", "FREQ=WEEKLY",
            lastCompletedOccurrenceDate = if (v20) "2027-03-03" else null, recurrenceExplicitlySet = v20,
            postponedFromOccurrenceDate = if (v20) "2027-03-17" else null, postponedToDate = if (v20) "2027-03-19" else null,
            createdAt = t0, updatedAt = t0), db.calendarDao().getAllCalendarItems().single())
        assertEquals(listOf(CalendarItemLinkEntity("l-m1", "cal-m1", "CONTACT", "c-m1"), CalendarItemLinkEntity("l-m2", "cal-m1", "COMPANY", "co-m1")),
            db.calendarDao().getCalendarItemLinks().sortedBy { it.id })
        assertEquals(listOf(ReminderRuleEntity("r-m1", "cal-m1", "BEFORE", 30, "MINUTES", null),
            ReminderRuleEntity("r-m2", "cal-m1", "CUSTOM_DATE_TIME", null, null, "2027-03-09T09:00")), db.calendarDao().getReminderRules().sortedBy { it.id })
    }

    /** Цепочка 18→19→20→21 целиком, как её проходит пользователь v1.5.1 (versionCode 15), обновляясь до текущей версии. */
    @Test
    fun gate_realUpgradePath_18to19to20to21_throughProductionBuilder_keepsEveryOldValue() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        try {
            assertOldCanarySurvived(upgradeThroughProductionBuilder(18), fromVersion = 18)
            // Повторное открытие: миграции не гоняются заново, БД не пересоздаётся, идентичность схемы обновлена.
            resetProductionSingleton()
            assertOldCanarySurvived(SocialsphereDatabase.getDatabase(ctx), fromVersion = 18)
        } finally { resetProductionSingleton() }
    }

    /** Отдельный шаг 20→21: канарейка v20 включает recurrence + postpone; deviceLookupKey = null допустим, deviceContactId жив. */
    @Test
    fun gate_realUpgradePath_20to21_throughProductionBuilder_keepsRecurrencePostponeAndDeviceLink() {
        try { assertOldCanarySurvived(upgradeThroughProductionBuilder(20), fromVersion = 20) }
        finally { resetProductionSingleton() }
    }

    // ═════════════════════════════════════════════════════════════
    //  v22 (EXPLICIT RECOVERY PACK, 2026-09-22): новая таблица tombstones. Аддитивно — старые таблицы не трогаются,
    //  новая таблица создаётся пустой (записей об удалениях ДО этой версии нет и быть не может — сами удалённые
    //  строки уже стёрты раньше, восстанавливать историю задним числом неоткуда).
    // ═════════════════════════════════════════════════════════════

    @Test
    fun migration21to22_addsEmptyTombstonesTable_keepsExistingContact() {
        val db = openDb(21) {
            it.execSQL(
                "CREATE TABLE contacts (id TEXT NOT NULL PRIMARY KEY, firstName TEXT NOT NULL, lastName TEXT NOT NULL, " +
                "photoUri TEXT, relationshipType TEXT NOT NULL, connectionLevel TEXT NOT NULL, importanceLevel TEXT NOT NULL, " +
                "socialRole TEXT NOT NULL, communicationRhythm TEXT NOT NULL, deviceContactId TEXT, deviceLookupKey TEXT, " +
                "createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)"
            )
        }
        db.execSQL(
            "INSERT INTO contacts (id, firstName, lastName, relationshipType, connectionLevel, importanceLevel, socialRole, " +
            "communicationRhythm, deviceLookupKey, createdAt, updatedAt) VALUES ('c1', 'Анна', 'Иванова', 'FRIEND', 'NORMAL', " +
            "'NORMAL', 'REGULAR', 'NOT_TRACKED', 'lookup-1', '2026-01-01T00:00:00', '2026-01-01T00:00:00')"
        )

        SocialsphereDatabase.MIGRATION_21_22.migrate(db)

        db.query("SELECT firstName, deviceLookupKey FROM contacts WHERE id='c1'").use { c ->
            assertTrue("контакт должен дожить до v22", c.moveToFirst())
            assertEquals("Анна", c.getString(0)); assertEquals("lookup-1", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM tombstones").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        // новая таблица реально рабочая: пишем и читаем
        db.execSQL("INSERT INTO tombstones (entityType, entityId, deletedAt) VALUES ('NOTE', 'n1', '2026-09-22T00:00:00')")
        db.query("SELECT entityType, entityId FROM tombstones WHERE entityId='n1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("NOTE", c.getString(0)); assertEquals("n1", c.getString(1))
        }
        db.close()
    }
}
