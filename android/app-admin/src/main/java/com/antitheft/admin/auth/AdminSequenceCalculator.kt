package com.antitheft.admin.auth

/**
 * Computes strictly monotonic command sequence numbers for the Admin companion app.
 *
 * Prevents device lockout upon app reinstall or device replacement:
 * - On fresh install or local wipe (saved = 0), initializes to current Unix epoch seconds.
 * - Under normal dispatch, increments saved + 1.
 * - If time has advanced, jumps forward to current epoch seconds, guaranteeing
 *   the Guardian will always see sequence > previous without needing a counter sync.
 */
object AdminSequenceCalculator {

    fun computeNextSequence(savedSequence: Long, nowEpochSec: Long): Long {
        return maxOf(savedSequence + 1, nowEpochSec)
    }
}
