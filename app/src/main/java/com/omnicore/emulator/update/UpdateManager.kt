package com.omnicore.emulator.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.omnicore.emulator.BuildConfig
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UpdateManager {
    private const val RELEASES_API = "https://api.github.com/repos/mauricio-gamedev/OmniCore/releases?per_page=10"
    const val ACTION_INSTALL_STATUS = "com.omnicore.emulator.UPDATE_INSTALL_STATUS"

    data class ReleaseInfo(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val sha256: String?,
        val name: String
    )

    sealed interface CheckResult {
        data class Available(val release: ReleaseInfo) : CheckResult
        data class Current(val version: String) : CheckResult
        data class Error(val message: String) : CheckResult
    }

    sealed interface InstallResult {
        data class Progress(val message: String) : InstallResult
        data object NeedsUnknownSourcesPermission : InstallResult
        data class Error(val message: String) : InstallResult
        data object InstallerStarted : InstallResult
    }

    fun checkForUpdate(context: Context, callback: (CheckResult) -> Unit) {
        val app = context.applicationContext
        Thread({
            val result = runCatching {
                val connection = openConnection(RELEASES_API)
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                require(connection.responseCode in 200..299) { "GitHub respondeu HTTP ${connection.responseCode}" }
                val releases = JSONArray(body)
                var best: ReleaseInfo? = null
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    if (release.optBoolean("draft", false)) continue
                    val tag = release.optString("tag_name")
                    val version = parseDevTag(tag) ?: continue
                    val assets = release.optJSONArray("assets") ?: continue
                    var apkUrl: String? = null
                    var digest: String? = null
                    val expectedName = "OmniCore-v$version-debug.apk"
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        if (asset.optString("name") == expectedName) {
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            digest = asset.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                            break
                        }
                    }
                    if (apkUrl == null) continue
                    val candidate = ReleaseInfo(
                        version = version,
                        tag = tag,
                        apkUrl = apkUrl,
                        sha256 = digest,
                        name = release.optString("name", tag)
                    )
                    if (best == null || compareVersions(candidate.version, best!!.version) > 0) best = candidate
                }
                val latest = best ?: return@runCatching CheckResult.Error("Nenhuma build DEV válida foi encontrada.")
                if (compareVersions(latest.version, BuildConfig.VERSION_NAME) > 0) {
                    CheckResult.Available(latest)
                } else {
                    CheckResult.Current(BuildConfig.VERSION_NAME)
                }
            }.getOrElse { CheckResult.Error(it.message ?: "Falha ao verificar atualizações.") }
            post { callback(result) }
        }, "OmniCore-UpdateCheck").start()
    }

    fun install(context: Context, release: ReleaseInfo, callback: (InstallResult) -> Unit) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            callback(InstallResult.NeedsUnknownSourcesPermission)
            return
        }

        Thread({
            try {
                post { callback(InstallResult.Progress("Baixando OmniCore ${release.version}…")) }
                val updateDir = File(app.cacheDir, "updates").apply { mkdirs() }
                val target = File(updateDir, "OmniCore-${release.version}.apk")
                val part = File(updateDir, "OmniCore-${release.version}.apk.part")
                part.delete()

                val digest = MessageDigest.getInstance("SHA-256")
                val connection = openConnection(release.apkUrl)
                require(connection.responseCode in 200..299) { "Download respondeu HTTP ${connection.responseCode}" }
                connection.inputStream.buffered(256 * 1024).use { input ->
                    part.outputStream().buffered(256 * 1024).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                require(part.length() > 1_000_000L) { "APK baixado está incompleto." }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                release.sha256?.let { expected ->
                    require(actualSha.equals(expected, ignoreCase = true)) {
                        "SHA-256 do APK não confere; atualização cancelada."
                    }
                }
                if (target.exists()) target.delete()
                require(part.renameTo(target)) { "Não consegui finalizar o APK baixado." }

                post { callback(InstallResult.Progress("APK verificado. Abrindo atualização do Android…")) }
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                    }
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    target.inputStream().buffered(256 * 1024).use { input ->
                        session.openWrite("base.apk", 0, target.length()).use { output ->
                            input.copyTo(output, 256 * 1024)
                            session.fsync(output)
                        }
                    }
                    val statusIntent = Intent(app, UpdateInstallReceiver::class.java).apply {
                        action = ACTION_INSTALL_STATUS
                    }
                    val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pending = PendingIntent.getBroadcast(
                        app,
                        sessionId,
                        statusIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
                    )
                    session.commit(pending.intentSender)
                }
                post { callback(InstallResult.InstallerStarted) }
            } catch (t: Throwable) {
                post { callback(InstallResult.Error(t.message ?: "Falha ao instalar atualização.")) }
            }
        }, "OmniCore-Updater").start()
    }

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OmniCore/${BuildConfig.VERSION_NAME} Android")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connect()
        }

    private fun parseDevTag(tag: String): String? {
        val match = Regex("^v(\\d+\\.\\d+\\.\\d+)-dev$").matchEntire(tag) ?: return null
        return match.groupValues[1]
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val bb = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val av = aa.getOrElse(i) { 0 }
            val bv = bb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun post(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
