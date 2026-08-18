package com.omnicore.emulator.core.n64

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Phone-first N64 crash diagnostics and boot validation state.
 *
 * The N64 runtime lives in :n64, so the main OmniCore process can inspect the
 * Android process-exit history after a native crash. Tiny files shared between
 * both processes keep the last boot stage and whether a real first frame has
 * ever been produced successfully on this installation.
 */
object N64Diagnostics {
    private const val PREFS = "n64_diagnostics"
    private const val KEY_LAST_EXIT_TS = "last_exit_timestamp"
    private const val BREADCRUMB = "last_boot_stage.txt"
    private const val VERIFIED_BOOT = "boot_verified.flag"
    private const val EXIT_ASSOCIATION_WINDOW_MS = 2L * 60L * 60L * 1000L

    private fun root(context: Context) = File(context.filesDir, "n64")

    fun breadcrumbFile(context: Context): File = File(root(context), BREADCRUMB)

    fun verifiedBootFile(context: Context): File = File(root(context), VERIFIED_BOOT)

    fun hasVerifiedBoot(context: Context): Boolean = verifiedBootFile(context).isFile

    fun mark(context: Context, stage: String, detail: String = "") {
        runCatching {
            val file = breadcrumbFile(context)
            file.parentFile?.mkdirs()
            val payload = buildString {
                append("stage=").append(stage.trim())
                append('\n').append("timestamp=").append(System.currentTimeMillis())
                if (detail.isNotBlank()) append('\n').append("detail=").append(detail.take(240))
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

    fun readBreadcrumb(context: Context): String? = runCatching {
        breadcrumbFile(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Returns a one-shot human readable crash report for the most recent :n64
     * process death. Normal user exits/package updates and stale exits from an
     * older build/session are intentionally hidden.
     */
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

        // Associate Android's process exit with a launch breadcrumb created by
        // this build/session. This prevents an old Alpha crash from being shown
        // immediately after installing a newer diagnostic build.
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
        val description = exit.description?.trim()?.takeIf { it.isNotBlank() }

        return buildString {
            append("A sessão Nintendo 64 anterior terminou por ").append(reason).append('.')
            if (breadcrumb != null) {
                append("\n\nÚltimo rastro salvo:\n").append(breadcrumb)
            }
            if (description != null) {
                append("\n\nAndroid: ").append(description.take(300))
            }
        }
    }
}
