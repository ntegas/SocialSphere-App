package com.aistudio.socialsphere.crmlxb.data.local

import android.content.Context
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
        PersonalDetailEntity::class,
        ContactGroupEntity::class,
        ContactGroupMemberEntity::class,
        TagEntity::class,
        ContactTagMemberEntity::class
    ],
    version = 18,
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
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
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
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
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
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // no-op
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE addresses ADD COLUMN postalCode TEXT")
            }
        }

        // Migration v5 → v6: version был поднят без изменения @Entity-схемы.
        // SQLite-схема идентична v5 (identity-hash совпадает) — поэтому no-op.
        // Регистрация перехода нужна, чтобы апдейт с v5 НЕ срабатывал на
        // destructive fallback (потеря боевых данных). Аналог MIGRATION_3_4.
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // no-op
            }
        }

        // Migration v6 → v7: схема Entity не изменилась, но identity-hash в БД
        // на устройстве (e9f08...) не совпадал с хэшем, который Room ожидал от
        // текущего кода (ac5b2...) — результат двух изменений Entity при version=6.
        // Поднимаем версию, миграция пустая — SQLite-таблицы остаются те же.
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // no-op: таблицы не изменились, правится только version tracking
            }
        }

        // v8: связь контакта с телефонной книгой для синхронизации «обновить из телефона»
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN deviceContactId TEXT")
            }
        }

        // v9 (решение владельца 2026-07-02):
        // 1) свой тип отношений (customRelationshipType) — «Кум», «Тренер» и т.п.;
        // 2) слияние «Уровень связи» в «Статус»: CLOSE/WEAK переносятся в
        //    contactStatus (кроме архивных — Архив важнее уровня связи).
        //    Колонка connectionLevel остаётся в БД нетронутой (сохранность данных).
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN customRelationshipType TEXT")
                database.execSQL(
                    "UPDATE contacts SET contactStatus = 'CLOSE' " +
                    "WHERE connectionLevel = 'CLOSE' AND contactStatus != 'ARCHIVED'"
                )
                database.execSQL(
                    "UPDATE contacts SET contactStatus = 'WEAK' " +
                    "WHERE connectionLevel = 'WEAK' AND contactStatus != 'ARCHIVED'"
                )
            }
        }

        // v10 (2026-07-02): отчество (middleName) — телефонная книга/vCard несут
        // 3-4-словные имена, отчество раньше терялось при импорте; familyNote —
        // свободный текст о семье без карточек контактов (блок F в Обзоре).
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN middleName TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN familyNote TEXT")
            }
        }

        // v11 (2026-07-03): группы контактов (как в телефонной книге) —
        // таблица групп + членство (многие-ко-многим). SQL 1:1 с Entity.
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_groups` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_group_members` (" +
                    "`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                    "`contactId` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        // v12 (2026-07-03): профессия без привязки к компании («не могу добавить
        // профессию, не указав компанию») — свободное поле контакта.
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN profession TEXT")
            }
        }

        // v13 (2026-07-04): полная структура имени как в Android-контактах —
        // приставка/суффикс/фонетические имя-фамилия. Раньше при импорте
        // prefix/suffix из StructuredName молча склеивались в middleName
        // (фидбэк владельца: «хочу как в андроид, идентично»).
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN namePrefix TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN nameSuffix TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN phoneticFirstName TEXT")
                database.execSQL("ALTER TABLE contacts ADD COLUMN phoneticLastName TEXT")
            }
        }

        // v14 (2026-07-08): приватность заметки — отдельно от isImportant (см. базу
        // знаний §29). isImportant остаётся «попадает в Обзор/Шпаргалку + красная
        // рамка», isLocked — «скрывается блюром под Защитой записей», владелец
        // выбирает по каждой записи вручную (как iOS Notes/WhatsApp Chat Lock).
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v15 (2026-07-11): phoneticMiddleName — паритет с phoneticFirstName/
        // phoneticLastName (v13). Android StructuredName хранит фонетическую
        // фамилию/имя/отчество тремя отдельными полями (DATA7/DATA8/DATA9) —
        // раньше отчество было единственным без фонетической пары.
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN phoneticMiddleName TEXT")
            }
        }

        // v16 (2026-07-13, фидбэк владельца: «при вводе адреса нужен район,
        // помимо город/страну») — тот же безопасный паттерн, что postalCode
        // в MIGRATION_4_5: одна необязательная TEXT-колонка, без пересоздания
        // таблицы, существующие адреса не затрагиваются (NULL по умолчанию).
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE addresses ADD COLUMN district TEXT")
            }
        }

        // v17 (2026-07-23, решение владельца): 1) secondaryRelationshipTypes —
        // произвольное число второстепенных типов отношений (comma-joined,
        // тот же паттерн, что tags), relationshipType остаётся единственным
        // главным типом; 2) customRhythmDays — «раз в N дней» для
        // CommunicationRhythm.CUSTOM (раньше значение было в UI недоступно).
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN secondaryRelationshipTypes TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE contacts ADD COLUMN customRhythmDays INTEGER")
            }
        }

        // v18 (2026-07-28): теги контакта — плоский управляемый список (id, name,
        // category?), связь с контактом многие-ко-многим. Тот же паттерн, что
        // contact_groups/contact_group_members (MIGRATION_10_11) — только CREATE
        // TABLE, без ALTER существующих таблиц.
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT, " +
                    "`createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_tag_members` (" +
                    "`id` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
                    "`contactId` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        fun getDatabase(context: Context): SocialsphereDatabase {
            return INSTANCE ?: synchronized(this) {
                // ФИКС КРИТИЧНОГО БАГА (2026-07-07, владелец: «теряю данные при
                // каждом обновлении»): здесь стоял `fallbackToDestructiveMigration()`
                // для DEBUG-сборок — а владелец пользуется именно debug-сборкой
                // (отдельного release-процесса нет), т.е. КАЖДЫЙ раз, когда Room
                // не находил идеального совпадения схемы (после почти любой правки
                // Entity), он МОЛЧА стирал всю БД и создавал её заново — ни падения,
                // ни предупреждения, просто пустые контакты/заметки/даты при
                // следующем запуске. Все переходы 1→13 покрыты миграциями без
                // пропусков (см. ниже) — fallback не нужен, он был ложной защитой
                // «на всякий случай», которая на практике стирала боевые данные
                // владельца. Теперь при отсутствии валидного пути миграции Room
                // упадёт с явным исключением (описывающим несовпадение схемы) —
                // это заметно и чинится, а не тихая потеря данных.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SocialsphereDatabase::class.java,
                    "socialsphere_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
