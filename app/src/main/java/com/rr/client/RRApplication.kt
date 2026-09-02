package com.rr.client

import android.app.Application
import com.rr.client.storage.AppDatabase
import com.rr.client.storage.PreferencesManager

class RRApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getDatabase(this)
        preferencesManager = PreferencesManager(this)
    }

    companion object {
        lateinit var instance: RRApplication
            private set
    }
}
