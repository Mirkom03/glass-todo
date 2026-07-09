package com.mirko.glasstodo.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mirko.glasstodo.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class GhAsset(val name: String, val browser_download_url: String)

@Serializable
data class GhRelease(val tag_name: String, val assets: List<GhAsset> = emptyList())

/**
 * In-app updater: checks the public GitHub repo's latest release and, if newer than the
 * installed versionName, downloads the APK and fires the system installer (1-tap update).
 */
object Updater {
    private const val API = "https://api.github.com/repos/Mirkom03/glass-todo/releases/latest"
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    data class Update(val version: String, val apkUrl: String)

    /** Returns an Update if a newer release exists, else null. Runs blocking — call from IO. */
    fun check(): Update? {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val rel = json.decodeFromString<GhRelease>(resp.body!!.string())
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            val remote = rel.tag_name.removePrefix("v")
            return if (isNewer(remote, BuildConfig.VERSION_NAME)) Update(remote, apk.browser_download_url) else null
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Downloads the APK and launches the system installer. Runs blocking — call from IO. */
    fun downloadAndInstall(context: Context, url: String) {
        val req = Request.Builder().url(url).build()
        val file = File(context.cacheDir, "update.apk")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            file.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
