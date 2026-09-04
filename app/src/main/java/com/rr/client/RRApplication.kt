package com.rr.client

import android.app.Application
import android.util.Log
import com.rr.client.lab.RRLogCapture
import com.rr.client.lab.StartupSelfCheck
import com.rr.client.storage.AppDatabase
import com.rr.client.storage.PreferencesManager
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class RRApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        initializeLibbox()

        database = AppDatabase.getDatabase(this)
        preferencesManager = PreferencesManager(this)

        // 0.10.0 observability layer only. These collectors never participate in packet forwarding.
        RRLogCapture.start()
        StartupSelfCheck.schedule(this)
    }

    private fun initializeLibbox() {
        val baseDir = filesDir.apply { mkdirs() }
        val workingDir = (getExternalFilesDir(null) ?: File(baseDir, "working")).apply { mkdirs() }
        val tempDir = cacheDir.apply { mkdirs() }

        try {
            Libbox.setup(
                SetupOptions().apply {
                    basePath = baseDir.absolutePath
                    workingPath = workingDir.absolutePath
                    tempPath = tempDir.absolutePath
                    fixAndroidStack = true
                    commandServerListenPort = 0
                    logMaxLines = 1_000L
                    debug = BuildConfig.DEBUG
                    crashReportSource = "RR Client"
                    appVersion = BuildConfig.VERSION_CODE.toString()
                    appMarketingVersion = BuildConfig.VERSION_NAME
                    oomKillerEnabled = false
                    oomKillerDisabled = true
                    oomMemoryLimit = 0L
                    powerReportEnabled = false
                }
            )
        } catch (e: Throwable) {
            Log.e("RRApplication", "Failed to initialize libbox", e)
            throw IllegalStateException("libbox initialization failed", e)
        }
    }

    companion object {
        lateinit var instance: RRApplication
            private set
    }
}
