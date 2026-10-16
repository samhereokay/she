package com.antitheft.guardian.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [LocationEntity::class], version = 1, exportSchema = false)
abstract class GuardianDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    companion object {
        private const val DB_NAME = "guardian_encrypted.db"

        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        fun getInstance(context: Context, dbPassphrase: ByteArray): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                // Ensure Direct Boot Device Protected Storage context is used
                val deContext = if (context.isDeviceProtectedStorage) {
                    context
                } else {
                    context.createDeviceProtectedStorageContext()
                }

                val factory = SupportOpenHelperFactory(dbPassphrase)
                val instance = Room.databaseBuilder(
                    deContext,
                    GuardianDatabase::class.java,
                    DB_NAME
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
