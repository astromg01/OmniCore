package com.omnicore.emulator

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.omnicore.emulator.core.n64.N64Diagnostics
import kotlin.system.exitProcess

/**
 * Application bootstrap shared by all OmniCore processes.
 *
 * The main process stays untouched. The isolated :n64 process installs a tiny
 * uncaught-exception recorder before any Activity is created so phone-only
 * testing can report failures that happen during Activity construction/onCreate.
 */
class OmniCoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!currentProcessName().endsWith(":n64")) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                N64Diagnostics.recordJavaCrash(this, thread.name, throwable)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        N64Diagnostics.mark(this, "process:application_ready", currentProcessName())
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName().orEmpty()
        }
        val pid = Process.myPid()
        val manager = getSystemService(ActivityManager::class.java)
        return manager?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            .orEmpty()
    }
}
