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
        PersonalDetailEntity::class
    ],
    version = 3,
    exportSchema = false
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

        fun getDatabase(context: Context): SocialsphereDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SocialsphereDatabase::class.java,
                    "socialsphere_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
