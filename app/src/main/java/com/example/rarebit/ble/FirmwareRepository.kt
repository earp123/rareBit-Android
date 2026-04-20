package com.example.rarebit.ble

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val version: String,     // e.g. "1.9.1"
    val assetApiUrl: String  // GitHub API URL for the .bin asset
)

object FirmwareRepository {

    private const val RELEASES_URL =
        "https://api.github.com/repos/earp123/rareBit-Flags-Receivers/releases"

    private val FLAG_TAG_PREFIXES     = listOf("FLAG")
    private val RECEIVER_TAG_PREFIXES = listOf("RX", "RECV")

    private val cache = mutableMapOf<DeviceType, ReleaseInfo>()

    fun clearCache() = cache.clear()

    suspend fun fetchReleaseInfo(deviceType: DeviceType, pat: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            cache[deviceType]?.let { return@withContext it }

            val tagPrefixes = when (deviceType) {
                DeviceType.FLAG     -> FLAG_TAG_PREFIXES
                DeviceType.RECEIVER -> RECEIVER_TAG_PREFIXES
                DeviceType.UNKNOWN  -> return@withContext null
            }

            val json = githubGet(RELEASES_URL, pat, "application/vnd.github+json")
            val releases = JSONArray(json)
            Log.d("FirmwareRepo", "Total releases: ${releases.length()}")

            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val tagName = release.getString("tag_name")
                Log.d("FirmwareRepo", "release[$i]: $tagName")

                val matches = tagPrefixes.any { tagName.contains(it, ignoreCase = true) }
                if (!matches) continue

                val version = parseVersion(tagName) ?: continue
                Log.d("FirmwareRepo", "matched: $tagName -> $version")

                val assets = release.getJSONArray("assets")
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.getString("name")
                    if (name.endsWith(".bin")) {
                        val assetApiUrl = asset.getString("url")
                        Log.d("FirmwareRepo", "asset: $name -> $assetApiUrl")
                        return@withContext ReleaseInfo(version, assetApiUrl)
                            .also { cache[deviceType] = it }
                    }
                }
            }
            null
        }

    suspend fun downloadFirmware(assetApiUrl: String, pat: String): ByteArray =
        withContext(Dispatchers.IO) { downloadAsset(assetApiUrl, pat) }

    // Compares "major.minor.patch" strings; returns true if remote > device
    fun isNewerVersion(remoteVersion: String, deviceVersion: String): Boolean {
        fun parts(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(remoteVersion)
        val d = parts(deviceVersion)
        for (i in 0 until maxOf(r.size, d.size)) {
            val rv = r.getOrElse(i) { 0 }
            val dv = d.getOrElse(i) { 0 }
            if (rv != dv) return rv > dv
        }
        return false
    }

    fun getInstalledVersion(context: Context, deviceType: DeviceType): String? =
        context.getSharedPreferences("firmware_prefs", Context.MODE_PRIVATE)
            .getString("installed_${deviceType.name}", null)

    fun saveInstalledVersion(context: Context, deviceType: DeviceType, version: String) {
        context.getSharedPreferences("firmware_prefs", Context.MODE_PRIVATE)
            .edit().putString("installed_${deviceType.name}", version).apply()
    }

    private fun parseVersion(tagName: String): String? =
        Regex("""\d+\.\d+(?:\.\d+)?""").find(tagName)?.value

    private fun githubGet(url: String, pat: String, accept: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "token $pat")
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "rareBit-Android")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun downloadAsset(url: String, pat: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "token $pat")
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("User-Agent", "rareBit-Android")
        conn.instanceFollowRedirects = false
        conn.connect()
        return if (conn.responseCode in 301..302) {
            URL(conn.getHeaderField("Location")).openStream().readBytes()
        } else {
            conn.inputStream.readBytes()
        }
    }
}
