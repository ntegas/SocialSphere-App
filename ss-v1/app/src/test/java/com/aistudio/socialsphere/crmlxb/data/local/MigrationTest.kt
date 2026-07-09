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
}
