package com.antitheft.guardian

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

class GuardianApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize SQLCipher native OpenSSL libraries
        SQLiteDatabase.loadLibs(this)
    }
}
