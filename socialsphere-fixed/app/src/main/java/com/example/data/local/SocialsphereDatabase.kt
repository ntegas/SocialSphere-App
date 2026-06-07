package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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

        fun getDatabase(context: Context): SocialsphereDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SocialsphereDatabase::class.java,
                    "socialsphere_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
