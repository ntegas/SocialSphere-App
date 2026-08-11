package com.aistudio.socialsphere.crmlxb.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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
}
