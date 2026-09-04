package com.example.rarebit.ble

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
    private const val DEV_BRANCH = "development"

    // Exact tags mirror iOS RareBitDeviceType.releaseTag; the prefix is the
    // fallback when tags are misconfigured (same as iOS fetchReleaseWithFallback).
    // Update the exact tags when releasing new firmware.
    private data class TagSpec(val exact: String, val prefix: String)

    private val TAG_SPECS = mapOf(
        DeviceType.FLAG     to TagSpec("PRO_FLAG_v1.9.0", "PRO_FLAG"),
        DeviceType.RECEIVER to TagSpec("PRO_RX_v1.8.0", "PRO_RX")
    )
    // Receiver ⇄ Relay firmware swap (SMP flow). The Relay device itself uses
    // legacy Nordic DFU and never fetches from this repo.
    private val RELAY_SWAP_SPEC = TagSpec("RXRLY_v10.0", "RXRLY")

    private val cache = mutableMapOf<DeviceType, ReleaseInfo>()
    private var relayCache: ReleaseInfo? = null

    fun clearCache() { cache.clear(); relayCache = null }

    suspend fun fetchReleaseInfo(deviceType: DeviceType, pat: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            cache[deviceType]?.let { return@withContext it }
            val spec = TAG_SPECS[deviceType] ?: return@withContext null
            findRelease(spec, pat)?.also { cache[deviceType] = it }
        }

    suspend fun fetchRelayReleaseInfo(pat: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            relayCache?.let { return@withContext it }
            findRelease(RELAY_SWAP_SPEC, pat)?.also { relayCache = it }
        }

    // Developer plumbing: first release cut against the development branch
    // (target_commitish), matching this device type's tag prefix. Uncached —
    // dev builds churn. Returns null until such a release exists.
    suspend fun fetchDevReleaseInfo(deviceType: DeviceType, pat: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            val spec = TAG_SPECS[deviceType] ?: return@withContext null
            val json = githubGet(RELEASES_URL, pat, "application/vnd.github+json")
            val releases = JSONArray(json)
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optString("target_commitish") != DEV_BRANCH) continue
                val tagName = release.getString("tag_name")
                if (!tagName.startsWith(spec.prefix, ignoreCase = true)) continue
                val version = parseVersion(tagName) ?: continue
                val assets = release.getJSONArray("assets")
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.getString("name").endsWith(".bin")) {
                        Log.d("FirmwareRepo", "dev release: $tagName -> $version")
                        return@withContext ReleaseInfo(version, asset.getString("url"))
                    }
                }
            }
            null
        }

    private fun findRelease(spec: TagSpec, pat: String): ReleaseInfo? {
        val json = githubGet(RELEASES_URL, pat, "application/vnd.github+json")
        val releases = JSONArray(json)
        Log.d("FirmwareRepo", "Total releases: ${releases.length()}")

        var exact: JSONObject? = null
        var prefixed: JSONObject? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            // Stable channel only: dev builds are prereleases sharing the same
            // tag prefixes (e.g. PRO_FLAG_v2.0.0-dev.1) and are newest-first —
            // without this guard a stale exact tag would hand customers a dev
            // build via the prefix fallback. Dev fetch is fetchDevReleaseInfo.
            if (release.optBoolean("prerelease")) continue
            val tagName = release.getString("tag_name")
            if (tagName == spec.exact) { exact = release; break }
            if (prefixed == null && tagName.startsWith(spec.prefix, ignoreCase = true)) {
                prefixed = release
            }
        }
        val release = exact ?: prefixed ?: return null
        val tagName = release.getString("tag_name")
        if (exact == null) Log.w("FirmwareRepo", "Exact tag ${spec.exact} not found; using $tagName")

        val version = parseVersion(tagName) ?: return null
        val assets = release.getJSONArray("assets")
        for (j in 0 until assets.length()) {
            val asset = assets.getJSONObject(j)
            if (asset.getString("name").endsWith(".bin")) {
                Log.d("FirmwareRepo", "matched: $tagName -> $version (${asset.getString("name")})")
                return ReleaseInfo(version, asset.getString("url"))
            }
        }
        return null
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
