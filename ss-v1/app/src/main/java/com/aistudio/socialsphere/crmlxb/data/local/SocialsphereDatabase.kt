package com.aistudio.socialsphere.crmlxb.data.local

import android.content.Context
import com.aistudio.socialsphere.crmlxb.BuildConfig
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ContactEntity::class,
        CompanyEntity::class,
        ContactCompanyRelationEntity::class,
        ContactRelationEntity::class,
        ContactPhoneEntity::class,
        ContactEmailEntity::class,
        MessengerEntity::class,
        AddressEntity::class,
        CalendarItemEntity::class,
        CalendarItemLinkEntity::class,
        ReminderRuleEntity::class,
        NoteEntity::class,
        GiftIdeaEntity::class,
        SizeInfoEntity::class,
        PersonalDetailEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class SocialsphereDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun companyDao(): CompanyDao
    abstract fun calendarDao(): CalendarDao
    abstract fun addressDao(): AddressDao
    abstract fun noteDao(): NoteDao
    abstract fun giftDao(): GiftDao

    companion object {
        @Volatile
        private var INSTANCE: SocialsphereDatabase? = null

        // Migration v1 → v2: add new Contact fields
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN nickname TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN contactStatus TEXT NOT NULL DEFAULT 'ACTIVE'")
                database.execSQL("ALTER TABLE contacts ADD COLUMN lastContactDate TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN nextStep TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN tags TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN canHelpWith TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN iCanHelpWith TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN talkingPoints TEXT")
            }
        }

        // Migration v2 → v3: add meetContext and meetDate
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN meetContext TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN meetDate TEXT")
                // Защита: таблицы, отсутствовавшие в ранних версиях схемы.
                // Для свежих установок не выполняется (Room создаёт всё по текущей схеме).
                // SQL точно повторяет Entity — иначе Room уронит валидацию.
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_relations` (" +
                    "`id` TEXT NOT NULL, `firstContactId` TEXT NOT NULL, " +
                    "`secondContactId` TEXT NOT NULL, `firstRole` TEXT NOT NULL, " +
                    "`secondRole` TEXT NOT NULL, `note` TEXT, PRIMARY KEY(`id`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `size_infos` (" +
                    "`id` TEXT NOT NULL, `contactId` TEXT NOT NULL, " +
                    "`clothingSize` TEXT, `shoeSize` TEXT, `ringSize` TEXT, " +
                    "`other` TEXT, PRIMARY KEY(`id`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personal_details` (" +
                    "`id` TEXT NOT NULL, `contactId` TEXT NOT NULL, " +
                    "`category` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                    "`note` TEXT, PRIMARY KEY(`id`))"
                )
            }
        }

        // Migration v3 → v4: только аннотация @ColumnInfo(defaultValue="ACTIVE")
        // на contactStatus. Реальная схема SQLite не меняется (колонка уже была
        // TEXT NOT NULL DEFAULT 'ACTIVE' из MIGRATION_1_2) — поэтому миграция
        // пустая. Её наличие позволяет обновиться с v3 на v4 БЕЗ потери данных,
        // вместо destructive fallback.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // no-op
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE addresses ADD COLUMN postalCode TEXT")
            }
        }

        // Migration v5 → v6: version был поднят без изменения @Entity-схемы.
        // SQLite-схема идентична v5 (identity-hash совпадает) — поэтому no-op.
        // Регистрация перехода нужна, чтобы апдейт с v5 НЕ срабатывал на
        // destructive fallback (потеря боевых данных). Аналог MIGRATION_3_4.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // no-op
            }
        }

        // Migration v6 → v7: схема Entity не изменилась, но identity-hash в БД
        // на устройстве (e9f08...) не совпадал с хэшем, который Room ожидал от
        // текущего кода (ac5b2...) — результат двух изменений Entity при version=6.
        // Поднимаем версию, миграция пустая — SQLite-таблицы остаются те же.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // no-op: таблицы не изменились, правится только version tracking
            }
        }

        // v8: связь контакта с телефонной книгой для синхронизации «обновить из телефона»
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN deviceContactId TEXT")
            }
        }

        fun getDatabase(context: Context): SocialsphereDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    SocialsphereDatabase::class.java,
                    "socialsphere_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

                // Destructive fallback — ТОЛЬКО в debug. В release недостающая
                // миграция/несовпадение схемы должны падать с явной ошибкой Room,
                // а НЕ тихо стирать боевую БД владельца (правило: потеря данных
                // недопустима). Пропуск миграции теперь ловится на сборке гардом
                // У55 и exportSchema; этот fallback — лишь удобство для debug-данных.
                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }
                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }
    }
}
