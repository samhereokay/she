package com.antitheft.deviceowner

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class DeviceOwnerTest {

    private val deviceOwnerManager = DeviceOwnerManager()
    private val guidanceManager = SystemGuidanceManager()
    private val intruderCapture = ExperimentalIntruderCapture()

    @Test
    fun testAdbProvisioningCommandFormat() {
        val fakePackage = "com.antitheft.guardian"
        val cmd = "adb shell dpm set-device-owner $fakePackage/${AntiTheftDeviceAdminReceiver::class.java.name}"
        assertTrue(cmd.startsWith("adb shell dpm set-device-owner"))
        assertTrue(cmd.contains("com.antitheft.deviceowner.AntiTheftDeviceAdminReceiver"))
    }

    @Test
    fun testOemPowerMenuGuidanceGeneric() {
        val guidance = guidanceManager.getOemPowerMenuGuidance()
        assertNotNull(guidance.manufacturer)
        assertNotNull(guidance.settingName)
        assertTrue(guidance.instructions.isNotBlank())
    }

    @Test
    fun testOemPowerMenuGuidanceSamsung() {
        val guidance = guidanceManager.getOemPowerMenuGuidance("Samsung")
        assertEquals("Samsung", guidance.manufacturer)
        assertEquals("Lock Network and Security", guidance.settingName)
        assertTrue(guidance.instructions.contains("Lock network and security"))
    }

    @Test
    fun testOemPowerMenuGuidancePixel() {
        val guidance = guidanceManager.getOemPowerMenuGuidance("Google")
        assertEquals("Google Pixel", guidance.manufacturer)
        assertEquals("Lockdown Mode", guidance.settingName)
        assertTrue(guidance.instructions.contains("Lockdown"))
    }

    @Test
    fun testOemPowerMenuGuidanceXiaomi() {
        val guidance = guidanceManager.getOemPowerMenuGuidance("Xiaomi")
        assertEquals("Xiaomi / HyperOS", guidance.manufacturer)
        assertEquals("Secure Power-Off", guidance.settingName)
    }

    @Test
    fun testOemPowerMenuGuidanceOnePlus() {
        val guidance = guidanceManager.getOemPowerMenuGuidance("OnePlus")
        assertEquals("OnePlus / Oppo", guidance.manufacturer)
        assertEquals("Require Password to Power Off", guidance.settingName)
    }

    @Test
    fun testAdminEventListenerCallbacks() {
        var statusActive: Boolean? = null
        var failedAttemptsReceived: Int? = null
        var unlockSuccess = false

        val testListener = object : AntiTheftDeviceAdminReceiver.AdminEventListener {
            override fun onAdminStatusChanged(isActive: Boolean) {
                statusActive = isActive
            }

            override fun onFailedUnlockAttempt(failedAttempts: Int) {
                failedAttemptsReceived = failedAttempts
            }

            override fun onSuccessfulUnlock() {
                unlockSuccess = true
            }
        }

        AntiTheftDeviceAdminReceiver.listener = testListener

        testListener.onAdminStatusChanged(true)
        assertEquals(true, statusActive)

        testListener.onFailedUnlockAttempt(3)
        assertEquals(3, failedAttemptsReceived)

        testListener.onSuccessfulUnlock()
        assertTrue(unlockSuccess)

        AntiTheftDeviceAdminReceiver.listener = null
    }

    @Test
    fun testExperimentalIntruderCapturePolicyConstants() {
        assertNotNull(AntiTheftDeviceAdminReceiver.ACTION_SECURITY_PASSWORD_FAILED)
        assertEquals("failed_attempts", AntiTheftDeviceAdminReceiver.EXTRA_FAILED_ATTEMPTS)
    }
}
