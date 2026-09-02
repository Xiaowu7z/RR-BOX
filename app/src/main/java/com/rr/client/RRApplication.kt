package com.rr.client

import android.app.Application
import android.util.Log
import com.rr.client.storage.AppDatabase
import com.rr.client.storage.PreferencesManager
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

class RRApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var preferencesManager: PreferencesManager
        private set

    @Volatile
    var libboxReady: Boolean = false
        private set

    @Volatile
    var libboxInitializationError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()
        initializeLibboxSafely()
        database = AppDatabase.getDatabase(this)
        preferencesManager = PreferencesManager(this)
    }

    private fun initializeLibboxSafely() {
        val baseDir = filesDir.apply { mkdirs() }
        val workingDir = (getExternalFilesDir(null) ?: filesDir).apply { mkdirs() }
        val tempDir = cacheDir.apply { mkdirs() }

        runCatching {
            runCatching { Libbox.setLocale(Locale.getDefault().toLanguageTag()) }
            Libbox.setup(SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                fixAndroidStack = true
                logMaxLines = 3000
                debug = BuildConfig.DEBUG
                crashReportSource = "RR Client"
                appVersion = BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = BuildConfig.VERSION_NAME
            })
        }.onSuccess {
            libboxReady = true
            libboxInitializationError = null
        }.onFailure { error ->
            libboxReady = false
            libboxInitializationError = error.message ?: error.javaClass.simpleName
            Log.e("RRApplication", "libbox setup failed", error)
            writeDiagnostic("last-libbox-init-error.txt", error.stackTraceToString())
        }
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val writer = StringWriter()
                PrintWriter(writer).use { printWriter ->
                    printWriter.println("Thread: ${thread.name}")
                    printWriter.println("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    throwable.printStackTrace(printWriter)
                }
                writeDiagnostic("last-crash.txt", writer.toString())
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun writeDiagnostic(fileName: String, content: String) {
        runCatching {
            val directory = File(filesDir, "diagnostics").apply { mkdirs() }
            File(directory, fileName).writeText(content)
        }.onFailure {
            Log.w("RRApplication", "Unable to write diagnostic $fileName", it)
        }
    }

    companion object {
        lateinit var instance: RRApplication
            private set
    }
}
