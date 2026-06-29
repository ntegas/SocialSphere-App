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
}
