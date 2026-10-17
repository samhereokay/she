package com.antitheft.guardian.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(entities = [LocationEntity::class], version = 1, exportSchema = false)
abstract class GuardianDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    companion object {
        const val DB_NAME = "guardian_encrypted.db"
        private const val TAG = "GuardianDatabase"

        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        fun getInstance(context: Context, dbPassphrase: ByteArray): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                val existing = INSTANCE
                if (existing != null) return@synchronized existing

                // Ensure Direct Boot Device Protected Storage context is used
                val deContext = if (context.isDeviceProtectedStorage) {
                    context
                } else {
                    context.createDeviceProtectedStorageContext()
                }

                fun createBuilder(): RoomDatabase.Builder<GuardianDatabase> {
                    val factory = SupportOpenHelperFactory(dbPassphrase)
                    return Room.databaseBuilder(
                        deContext,
                        GuardianDatabase::class.java,
                        DB_NAME
                    )
                        .openHelperFactory(factory)
                        .fallbackToDestructiveMigration()
                }

                var db = createBuilder().build()
                try {
                    // Force database open to test passphrase validity immediately
                    db.openHelper.writableDatabase
                } catch (e: Exception) {
                    Log.e(TAG, "Guardian database corrupted or passphrase invalid. Quarantining corrupted database files.", e)
                    try { db.close() } catch (_: Exception) {}

                    val dbFile = deContext.getDatabasePath(DB_NAME)
                    quarantineCorruptedFiles(dbFile)

                    db = createBuilder().build()
                    try {
                        db.openHelper.writableDatabase
                        Log.i(TAG, "Fresh encrypted database successfully recreated after corruption recovery.")
                    } catch (fatal: Exception) {
                        Log.e(TAG, "Fatal failure initializing database after quarantine", fatal)
                    }
                }

                INSTANCE = db
                db
            }
        }

        /**
         * Quarantines corrupted database files (.db, -wal, -shm, -journal) by moving them
         * to DB_NAME.corrupted_<timestamp>[suffix] to break startup crash loops and preserve forensic evidence.
         */
        private fun logSafe(tag: String, msg: String, err: Throwable? = null) {
            try {
                if (err != null) Log.e(tag, msg, err) else Log.i(tag, msg)
            } catch (_: Throwable) {
                if (err != null) {
                    System.err.println("[$tag] $msg: ${err.message}")
                } else {
                    println("[$tag] $msg")
                }
            }
        }

        fun quarantineCorruptedFiles(
            dbFile: File,
            timestamp: Long = System.currentTimeMillis()
        ): List<File> {
            val parentDir = dbFile.parentFile ?: return emptyList()
            val baseName = dbFile.name
            val quarantined = mutableListOf<File>()

            val suffixes = listOf("", "-wal", "-shm", "-journal")
            for (suffix in suffixes) {
                val file = File(parentDir, baseName + suffix)
                if (file.exists()) {
                    val dest = File(parentDir, "${baseName}.corrupted_${timestamp}${suffix}")
                    if (file.renameTo(dest)) {
                        quarantined.add(dest)
                        logSafe(TAG, "Quarantined corrupted file: ${file.name} -> ${dest.name}")
                    }
                }
            }
            return quarantined
        }

        /**
         * For unit testing: reset singleton instance.
         */
        fun resetInstanceForTesting() {
            synchronized(this) {
                try { INSTANCE?.close() } catch (_: Exception) {}
                INSTANCE = null
            }
        }
    }
}
