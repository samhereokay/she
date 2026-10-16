package com.antitheft.guardian

import android.app.Application

class GuardianApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            // Handled automatically by SupportOpenHelperFactory if bundled
        }
    }
}
