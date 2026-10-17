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
}
