package com.antitheft.admin.auth

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies that Admin sequence number strategy avoids device lockout across
 * application uninstalls, phone replacements, and local storage wipes.
 */
class AdminSequenceStrategyTest {

    @Test
    fun testInitialSequenceInitializesToCurrentEpochSeconds() {
        val nowEpoch = 1770000000L
        val savedSeq = 0L // fresh install / uninitialized

        val nextSeq = AdminSequenceCalculator.computeNextSequence(savedSeq, nowEpoch)
        assertEquals(nowEpoch, nextSeq)
    }

    @Test
    fun testRapidSuccessiveDispatchesIncrementMonotonicallyWithinSameSecond() {
        val nowEpoch = 1770000000L
        var currentSeq = nowEpoch

        // Dispatch 5 commands rapidly within the exact same second
        val generatedSequences = mutableListOf<Long>()
        repeat(5) {
            currentSeq = AdminSequenceCalculator.computeNextSequence(currentSeq, nowEpoch)
            generatedSequences.add(currentSeq)
        }

        assertEquals(
            listOf(1770000001L, 1770000002L, 1770000003L, 1770000004L, 1770000005L),
            generatedSequences
        )
    }

    @Test
    fun testSequenceSurvivesAppReinstallOrWipeWithoutLockout() {
        // Device previously dispatched commands up to seq 1770000005
        val oldDispatchedSeq = 1770000005L

        // App is uninstalled, wiped, or replaced 10 seconds later
        val newInstallEpoch = 1770000015L
        val wipedStorageSeq = 0L // Local state was cleared on wipe

        // When user dispatches command from reinstalled app:
        val reinstalledNextSeq = AdminSequenceCalculator.computeNextSequence(wipedStorageSeq, newInstallEpoch)

        // Must be strictly greater than oldDispatchedSeq so Guardian accepts it without replay rejection
        assertTrue(
            "New sequence ($reinstalledNextSeq) must be strictly greater than previously seen sequence ($oldDispatchedSeq)",
            reinstalledNextSeq > oldDispatchedSeq
        )
        assertEquals(1770000015L, reinstalledNextSeq)
    }

    @Test
    fun testClockAdvancesNormallyAdvancesSequence() {
        var currentSeq = 1770000000L
        val nextDispatchTime = 1770000060L // 1 minute later

        val nextSeq = AdminSequenceCalculator.computeNextSequence(currentSeq, nextDispatchTime)
        assertEquals(1770000060L, nextSeq)
    }
}
