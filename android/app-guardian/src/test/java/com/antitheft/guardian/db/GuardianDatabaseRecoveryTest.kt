package com.antitheft.guardian.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit test verifying database corruption recovery and crash-loop mitigation.
 * Proves that:
 * 1. Corrupted database files (.db, -wal, -shm, -journal) are safely quarantined with a timestamp.
 * 2. Original file paths are freed so the app can recreate a clean SQLite database and continue operation.
 */
class GuardianDatabaseRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testQuarantineCorruptedFilesMovesAllSuffixes() {
        val dbDir = tempFolder.newFolder("databases")
        val mainDb = File(dbDir, GuardianDatabase.DB_NAME).apply {
            writeBytes("GARBAGE_ENCRYPTED_HEADER_DATA".toByteArray())
        }
        val walFile = File(dbDir, "${GuardianDatabase.DB_NAME}-wal").apply {
            writeBytes("CORRUPTED_WAL_LOG".toByteArray())
        }
        val shmFile = File(dbDir, "${GuardianDatabase.DB_NAME}-shm").apply {
            writeBytes("CORRUPTED_SHM_INDEX".toByteArray())
        }
        val journalFile = File(dbDir, "${GuardianDatabase.DB_NAME}-journal").apply {
            writeBytes("CORRUPTED_JOURNAL_ROLLBACK".toByteArray())
        }

        assertTrue(mainDb.exists())
        assertTrue(walFile.exists())
        assertTrue(shmFile.exists())
        assertTrue(journalFile.exists())

        val testTimestamp = 1729000000000L
        val quarantined = GuardianDatabase.quarantineCorruptedFiles(mainDb, testTimestamp)

        // Verify all 4 files were quarantined
        assertEquals(4, quarantined.size)

        // Verify original files are gone
        assertFalse(mainDb.exists())
        assertFalse(walFile.exists())
        assertFalse(shmFile.exists())
        assertFalse(journalFile.exists())

        // Verify quarantined files exist with timestamp suffix
        val expectedMain = File(dbDir, "${GuardianDatabase.DB_NAME}.corrupted_${testTimestamp}")
        val expectedWal = File(dbDir, "${GuardianDatabase.DB_NAME}.corrupted_${testTimestamp}-wal")
        val expectedShm = File(dbDir, "${GuardianDatabase.DB_NAME}.corrupted_${testTimestamp}-shm")
        val expectedJournal = File(dbDir, "${GuardianDatabase.DB_NAME}.corrupted_${testTimestamp}-journal")

        assertTrue(expectedMain.exists())
        assertTrue(expectedWal.exists())
        assertTrue(expectedShm.exists())
        assertTrue(expectedJournal.exists())

        assertEquals("GARBAGE_ENCRYPTED_HEADER_DATA", expectedMain.readText())
        assertEquals("CORRUPTED_WAL_LOG", expectedWal.readText())
        assertEquals("CORRUPTED_SHM_INDEX", expectedShm.readText())
        assertEquals("CORRUPTED_JOURNAL_ROLLBACK", expectedJournal.readText())
    }

    @Test
    fun testQuarantineWhenOnlyMainDbExists() {
        val dbDir = tempFolder.newFolder("databases_single")
        val mainDb = File(dbDir, GuardianDatabase.DB_NAME).apply {
            writeBytes("SINGLE_DB_FILE".toByteArray())
        }

        val testTimestamp = 1729000000555L
        val quarantined = GuardianDatabase.quarantineCorruptedFiles(mainDb, testTimestamp)

        assertEquals(1, quarantined.size)
        assertFalse(mainDb.exists())

        val expectedMain = File(dbDir, "${GuardianDatabase.DB_NAME}.corrupted_${testTimestamp}")
        assertTrue(expectedMain.exists())
        assertEquals("SINGLE_DB_FILE", expectedMain.readText())
    }

    @Test
    fun testIsCorruptionOrKeyErrorIdentifiesCorruptionAndKeyFailures() {
        // SQLCipher / Room key invalidation error
        val keyErr = RuntimeException("net.zetetic.database.sqlcipher.SQLiteException: file is not a database")
        assertTrue(GuardianDatabase.isCorruptionOrKeyError(keyErr))

        val corruptErr = RuntimeException("net.zetetic.database.sqlcipher.SQLiteDatabaseCorruptException: database disk image is malformed")
        assertTrue(GuardianDatabase.isCorruptionOrKeyError(corruptErr))

        val passphraseErr = RuntimeException("Invalid passphrase for encrypted database")
        assertTrue(GuardianDatabase.isCorruptionOrKeyError(passphraseErr))

        // Transient disk / lock errors must NOT be marked as corruption
        val diskIoErr = RuntimeException("net.zetetic.database.sqlcipher.SQLiteDiskIOException: disk error")
        assertFalse(GuardianDatabase.isCorruptionOrKeyError(diskIoErr))

        val diskFullErr = RuntimeException("net.zetetic.database.sqlcipher.SQLiteFullException: disk full")
        assertFalse(GuardianDatabase.isCorruptionOrKeyError(diskFullErr))

        val lockErr = RuntimeException("net.zetetic.database.sqlcipher.SQLiteDatabaseLockedException: database is locked")
        assertFalse(GuardianDatabase.isCorruptionOrKeyError(lockErr))
    }

    @Test
    fun testQuarantinePruningCapsMaxSets() {
        val dbDir = tempFolder.newFolder("databases_cap")
        val baseName = GuardianDatabase.DB_NAME

        // Create 7 distinct quarantine sets (timestamps 1000..7000)
        for (i in 1..7) {
            val ts = 1000L * i
            File(dbDir, "${baseName}.corrupted_${ts}").writeText("DB_$i")
            File(dbDir, "${baseName}.corrupted_${ts}-wal").writeText("WAL_$i")
            File(dbDir, "${baseName}.corrupted_${ts}-shm").writeText("SHM_$i")
        }

        // 7 sets * 3 files = 21 files initially
        assertEquals(21, dbDir.listFiles()?.size)

        // Prune keeping at most 5 sets
        GuardianDatabase.pruneQuarantinedFiles(dbDir, baseName, maxSets = 5)

        val remainingFiles = dbDir.listFiles() ?: emptyArray()
        // 5 sets * 3 files = 15 files remaining
        assertEquals(15, remainingFiles.size)

        // Oldest sets (1000 and 2000) should be gone
        assertFalse(File(dbDir, "${baseName}.corrupted_1000").exists())
        assertFalse(File(dbDir, "${baseName}.corrupted_1000-wal").exists())
        assertFalse(File(dbDir, "${baseName}.corrupted_2000").exists())

        // Newer sets (3000..7000) must still exist
        for (i in 3..7) {
            val ts = 1000L * i
            assertTrue(File(dbDir, "${baseName}.corrupted_${ts}").exists())
            assertTrue(File(dbDir, "${baseName}.corrupted_${ts}-wal").exists())
            assertTrue(File(dbDir, "${baseName}.corrupted_${ts}-shm").exists())
        }
    }
}
