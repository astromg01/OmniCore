package com.omnicore.emulator.core.n64

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Phone-first crash diagnostics and boot validation for the isolated N64 process. */
object N64Diagnostics {
    private const val PREFS = "n64_diagnostics"
    private const val KEY_LAST_EXIT_TS = "last_exit_timestamp"
    private const val BREADCRUMB = "last_boot_stage.txt"
    private const val JAVA_CRASH = "last_java_crash.txt"
    private const val VERIFIED_BOOT = "boot_verified.flag"
    private const val EXIT_ASSOCIATION_WINDOW_MS = 2L * 60L * 60L * 1000L

    private fun root(context: Context) = File(context.filesDir, "n64")
    fun breadcrumbFile(context: Context): File = File(root(context), BREADCRUMB)
    fun verifiedBootFile(context: Context): File = File(root(context), VERIFIED_BOOT)
    private fun javaCrashFile(context: Context): File = File(root(context), JAVA_CRASH)
    fun hasVerifiedBoot(context: Context): Boolean = verifiedBootFile(context).isFile

    fun beginLaunch(context: Context, detail: String) {
        runCatching { javaCrashFile(context).delete() }
        mark(context, "main:launch_requested", detail)
    }

    fun mark(context: Context, stage: String, detail: String = "") {
        runCatching {
            val file = breadcrumbFile(context)
            file.parentFile?.mkdirs()
            val payload = buildString {
                append("stage=").append(stage.trim())
                append('\n').append("timestamp=").append(System.currentTimeMillis())
                if (detail.isNotBlank()) append('\n').append("detail=").append(detail.take(500))
                append('\n')
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(payload)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    fun recordJavaCrash(context: Context, threadName: String, throwable: Throwable) {
        runCatching {
            val file = javaCrashFile(context)
            file.parentFile?.mkdirs()
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            file.writeText(buildString {
                append("thread=").append(threadName).append('\n')
                append("type=").append(throwable.javaClass.name).append('\n')
                append("message=").append(throwable.message.orEmpty()).append('\n')
                append("stack=\n").append(writer.toString().take(12_000))
            })
            mark(
                context,
                "process:uncaught_exception",
                "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
            )
        }
    }

    fun readBreadcrumb(context: Context): String? = runCatching {
        breadcrumbFile(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readJavaCrash(context: Context): String? = runCatching {
        javaCrashFile(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun consumeRecentProcessExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val processName = "${context.packageName}:n64"
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .firstOrNull { it.processName == processName }
        }.getOrNull() ?: return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        if (exit.timestamp <= lastSeen) return null
        prefs.edit().putLong(KEY_LAST_EXIT_TS, exit.timestamp).apply()

        val breadcrumbFile = breadcrumbFile(context)
        val breadcrumbModified = breadcrumbFile.takeIf { it.isFile }?.lastModified() ?: return null
        val delta = exit.timestamp - breadcrumbModified
        if (delta < -5_000L || delta > EXIT_ASSOCIATION_WINDOW_MS) return null

        val crashLike = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            exit.reason == ApplicationExitInfo.REASON_CRASH ||
            exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            exit.reason == ApplicationExitInfo.REASON_ANR ||
            exit.reason == ApplicationExitInfo.REASON_SIGNALED ||
            exit.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
            exit.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
        if (!crashLike) return null

        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo"
            ApplicationExitInfo.REASON_CRASH -> "exceção Android"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "encerrado por memória"
            ApplicationExitInfo.REASON_ANR -> "ANR/travamento"
            ApplicationExitInfo.REASON_SIGNALED -> "sinal nativo ${exit.status}"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falha de inicialização"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excessivo de recursos"
            else -> "motivo ${exit.reason}"
        }
        val breadcrumb = readBreadcrumb(context)
        val javaCrash = readJavaCrash(context)
        val description = exit.description?.trim()?.takeIf { it.isNotBlank() }

        return buildString {
            append("A sessão Nintendo 64 anterior terminou por ").append(reason).append('.')
            if (breadcrumb != null) append("\n\nÚltimo rastro salvo:\n").append(breadcrumb)
            if (javaCrash != null) append("\n\nExceção capturada:\n").append(javaCrash.take(4_500))
            if (description != null) append("\n\nAndroid: ").append(description.take(500))
        }
    }
}
