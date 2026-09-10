package dev.swart.inklab.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.swart.inklab.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(
    val version: String,
    val apkUrl: String,
    val checksumUrl: String,
    val pageUrl: String
)

object AppVersions {
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { index ->
            val difference = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference > 0
        }
        return false
    }
}

/** GitHub release updater with hash and signing-certificate verification before installation. */
class AppUpdater(private val context: Context) {
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val releases = JSONArray(request(RELEASES_URL))
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft")) continue
            val version = release.getString("tag_name").removePrefix("v")
            if (!AppVersions.isNewer(version, BuildConfig.VERSION_NAME)) continue
            val assets = release.getJSONArray("assets")
            var apk: String? = null
            var checksum: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val name = asset.getString("name")
                val url = asset.getString("browser_download_url")
                if (name.endsWith("-arm64.apk")) apk = url
                if (name.endsWith("-arm64.apk.sha256")) checksum = url
            }
            if (apk != null && checksum != null) return@withContext AppUpdate(
                version, apk, checksum, release.getString("html_url")
            )
        }
        null
    }

    suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        val expected = request(update.checksumUrl).trim().substringBefore(' ')
        require(expected.matches(Regex("[0-9a-fA-F]{64}"))) { "Некорректная контрольная сумма релиза" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "InkLab-${update.version}.apk.part")
        val target = File(directory, "InkLab-${update.version}.apk")
        try {
            downloadTo(update.apkUrl, partial)
            require(partial.length() in 1..MAX_APK_BYTES) { "Некорректный размер APK" }
            require(sha256(partial).equals(expected, ignoreCase = true)) { "SHA-256 обновления не совпадает" }
            require(hasSameSigner(partial)) { "Обновление подписано другим сертификатом" }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Не удалось подготовить обновление" }
            target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    fun installIntent(apk: File): Intent {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasSameSigner(apk: File): Boolean {
        val archiveSigners: Set<List<Byte>>
        val installedSigners: Set<List<Byte>>
        fun hashes(signatures: Array<android.content.pm.Signature>?) = signatures.orEmpty()
            .map { signer -> MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toList() }
            .toSet()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return false
            val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            archiveSigners = hashes(archive.signingInfo?.apkContentsSigners)
            installedSigners = hashes(installed.signingInfo?.apkContentsSigners)
        } else {
            val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?: return false
            val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            archiveSigners = hashes(archive.signatures)
            installedSigners = hashes(installed.signatures)
        }
        return archiveSigners.isNotEmpty() && archiveSigners == installedSigners
    }

    private fun request(url: String): String {
        val connection = open(url)
        val code = connection.responseCode
        require(code in 200..299) { "Сервер обновлений вернул HTTP $code" }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadTo(url: String, destination: File) {
        val connection = open(url)
        require(connection.responseCode in 200..299) { "Не удалось загрузить APK: HTTP ${connection.responseCode}" }
        connection.inputStream.use { input -> destination.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_APK_BYTES) { "APK превышает допустимый размер" }
                output.write(buffer, 0, count)
            }
        } }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "InkLab/${BuildConfig.VERSION_NAME}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/Swart-G/InkLab/releases?per_page=10"
        const val MAX_APK_BYTES = 300L * 1024L * 1024L
    }
}
