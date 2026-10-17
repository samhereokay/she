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
        const val MAX_QUARANTINE_SETS = 5
        private const val TAG = "GuardianDatabase"

        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        fun getInstance(context: Context, storageHelper: DirectBootStorageHelper): GuardianDatabase {
            return getInstance(context, storageHelper.getDbPassphrase()) {
                storageHelper.rotateDbPassphrase()
            }
        }

        fun getInstance(
            context: Context,
            dbPassphrase: ByteArray,
            onKeyRotation: (() -> ByteArray)? = null
        ): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                val existing = INSTANCE
                if (existing != null) return@synchronized existing

                // Ensure Direct Boot Device Protected Storage context is used
                val deContext = if (context.isDeviceProtectedStorage) {
                    context
                } else {
                    context.createDeviceProtectedStorageContext()
                }

                fun createBuilder(passphrase: ByteArray): RoomDatabase.Builder<GuardianDatabase> {
                    val factory = SupportOpenHelperFactory(passphrase)
                    return Room.databaseBuilder(
                        deContext,
                        GuardianDatabase::class.java,
                        DB_NAME
                    )
                        .openHelperFactory(factory)
                        .fallbackToDestructiveMigration()
                }

                var currentPassphrase = dbPassphrase
                var db = createBuilder(currentPassphrase).build()
                try {
                    // Force database open to test passphrase validity immediately
                    db.openHelper.writableDatabase
                } catch (e: Exception) {
                    if (!isCorruptionOrKeyError(e)) {
                        logSafe(TAG, "Transient disk or I/O error opening database. Preserving database files without quarantine.", e)
                        throw e
                    }

                    logSafe(TAG, "Guardian database corrupted or passphrase invalid. Quarantining corrupted database files.", e)
                    try { db.close() } catch (_: Exception) {}

                    val dbFile = deContext.getDatabasePath(DB_NAME)
                    val quarantined = quarantineCorruptedFiles(dbFile)
                    logSafe(TAG, "Quarantine completed. Moved ${quarantined.size} files for forensic audit: ${quarantined.map { it.name }}")

                    // Rotate database encryption key if rotation handler provided
                    if (onKeyRotation != null) {
                        currentPassphrase = onKeyRotation()
                        logSafe(TAG, "Rotated database encryption passphrase post-quarantine.")
                    }

                    db = createBuilder(currentPassphrase).build()
                    try {
                        db.openHelper.writableDatabase
                        logSafe(TAG, "Fresh encrypted database successfully recreated after corruption recovery.")
                    } catch (fatal: Exception) {
                        logSafe(TAG, "Fatal failure initializing database after quarantine", fatal)
                    }
                }

                INSTANCE = db
                db
            }
        }

        /**
         * Inspects exception chain to distinguish corruption / key mismatch from transient disk I/O errors.
         * Only returns true for corruption, header invalidation, passphrase mismatch, or malformed DB.
         * Returns false for SQLiteDiskIOException, SQLiteFullException, lock timeouts, etc.
         */
        fun isCorruptionOrKeyError(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                val className = cause.javaClass.name
                val msg = cause.message?.lowercase() ?: ""

                // Transient disk/lock errors that should NOT trigger database quarantine
                if (className.contains("SQLiteDiskIOException") ||
                    className.contains("SQLiteFullException") ||
                    className.contains("SQLiteTableLockedException") ||
                    className.contains("SQLiteDatabaseLockedException") ||
                    cause is java.io.InterruptedIOException) {
                    return false
                }

                // Explicit corruption or key invalidation errors
                if (className.contains("SQLiteDatabaseCorruptException") ||
                    msg.contains("file is not a database") ||
                    msg.contains("file is encrypted or is not a database") ||
                    msg.contains("passphrase") ||
                    msg.contains("corrupt") ||
                    msg.contains("malformed") ||
                    msg.contains("unsupported file format")
                ) {
                    return true
                }
                cause = cause.cause
            }
            return false
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

            // Cap quarantine sets to prevent unbounded disk usage
            pruneQuarantinedFiles(parentDir, baseName, MAX_QUARANTINE_SETS)

            return quarantined
        }

        fun pruneQuarantinedFiles(parentDir: File, baseName: String, maxSets: Int = MAX_QUARANTINE_SETS) {
            val prefix = "${baseName}.corrupted_"
            val matchingFiles = parentDir.listFiles { file ->
                file.name.startsWith(prefix)
            } ?: return

            val setsByTimestamp = matchingFiles.groupBy { file ->
                val afterPrefix = file.name.substring(prefix.length)
                afterPrefix.split("-", "_", ".").firstOrNull() ?: afterPrefix
            }

            if (setsByTimestamp.size > maxSets) {
                val sortedTimestamps = setsByTimestamp.keys.sorted()
                val excessCount = sortedTimestamps.size - maxSets
                for (i in 0 until excessCount) {
                    val oldTimestamp = sortedTimestamps[i]
                    setsByTimestamp[oldTimestamp]?.forEach { oldFile ->
                        if (oldFile.delete()) {
                            logSafe(TAG, "Pruned old quarantine file to stay under cap ($maxSets): ${oldFile.name}")
                        }
                    }
                }
            }
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
