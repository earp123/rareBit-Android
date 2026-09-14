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

enum class RelayChannel { STABLE, DEVELOPMENT }

// Relay firmware (legacy Nordic DFU). Resolved from the release's manifest.json.
data class RelayReleaseInfo(
    val version: String,      // "M.N" from fw_version_byte — same format as the FW char
    val versionByte: String,  // e.g. "0x20"
    val build: Int?,          // development builds only
    val channel: RelayChannel,
    val tag: String,
    val zipUrl: String,       // stable: browser_download_url; dev: asset API url
    val sha256: String        // manifest dfu_package_sha256, lowercase
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

    fun clearCache() { cache.clear(); relayCache = null; relayStableCache = null }

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

    // ── Relay (legacy Nordic DFU) ─────────────────────────────────────────────
    // The Relay never fetches from rareBit-Flags-Receivers. Stable builds come from
    // the public releases repo (no auth); development builds from private
    // rareBit-Relay (PAT). Both carry manifest.json.

    private const val RELAY_STABLE_RELEASES_URL =
        "https://api.github.com/repos/earp123/rareBit-firmware-releases/releases"
    private const val RELAY_DEV_RELEASES_URL =
        "https://api.github.com/repos/earp123/rareBit-Relay/releases"
    private const val RELAY_STABLE_TAG_PREFIX = "relay-v"
    private const val RELAY_DEV_TAG_PREFIX = "RELAY_"
    const val RELAY_DEV_BRANCH = "main"

    // Pin a stable Relay tag (e.g. "relay-v1.10") for testing; null = highest version.
    private val RELAY_STABLE_EXACT_TAG: String? = null

    private val DEV_BUILD_REGEX = Regex("""-dev\.(\d+)$""")

    private var relayStableCache: RelayReleaseInfo? = null

    suspend fun fetchRelayStable(): RelayReleaseInfo? = withContext(Dispatchers.IO) {
        relayStableCache?.let { return@withContext it }
        val releases = JSONArray(httpGet(RELAY_STABLE_RELEASES_URL, pat = null))
        var best: JSONObject? = null
        var bestVersion = ""
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("prerelease") || r.optBoolean("draft")) continue
            val tag = r.getString("tag_name")
            if (!tag.startsWith(RELAY_STABLE_TAG_PREFIX)) continue
            val pinned = RELAY_STABLE_EXACT_TAG
            if (pinned != null) {
                if (tag == pinned) { best = r; break }
                continue
            }
            val v = parseVersion(tag) ?: continue
            // GitHub lists by date, not version — relay-v1.10 must beat relay-v1.9
            if (best == null || isNewerVersion(v, bestVersion)) { best = r; bestVersion = v }
        }
        val release = best ?: return@withContext null
        resolveRelayRelease(release, RelayChannel.STABLE, pat = null)?.also { relayStableCache = it }
    }

    // Never cached: dev builds churn, and the developer picked this on purpose.
    suspend fun fetchRelayDev(pat: String): RelayReleaseInfo? = withContext(Dispatchers.IO) {
        val releases = JSONArray(httpGet(RELAY_DEV_RELEASES_URL, pat))
        var best: JSONObject? = null
        var bestVersion = ""
        var bestBuild = -1
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (!r.optBoolean("prerelease")) continue
            if (r.optString("target_commitish") != RELAY_DEV_BRANCH) continue
            val tag = r.getString("tag_name")
            if (!tag.startsWith(RELAY_DEV_TAG_PREFIX)) continue
            val v = parseVersion(tag) ?: continue
            val build = DEV_BUILD_REGEX.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            // Newest version stream first, then highest -dev.<n> within it
            val sameVersion = !isNewerVersion(v, bestVersion) && !isNewerVersion(bestVersion, v)
            if (best == null || isNewerVersion(v, bestVersion) || (sameVersion && build > bestBuild)) {
                best = r; bestVersion = v; bestBuild = build
            }
        }
        val release = best ?: return@withContext null
        resolveRelayRelease(release, RelayChannel.DEVELOPMENT, pat)
    }

    suspend fun downloadRelayZip(info: RelayReleaseInfo, pat: String): ByteArray =
        withContext(Dispatchers.IO) { downloadRelayAsset(info.zipUrl, info.channel, pat) }

    private fun resolveRelayRelease(release: JSONObject, channel: RelayChannel, pat: String?): RelayReleaseInfo? {
        val tag = release.getString("tag_name")
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject? =
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name") == name }
        fun urlOf(a: JSONObject): String =
            if (channel == RelayChannel.STABLE) a.getString("browser_download_url") else a.getString("url")

        val manifestAsset = asset("manifest.json")
        if (manifestAsset == null) {
            Log.w("FirmwareRepo", "relay $tag has no manifest.json")
            return null
        }
        val manifest = JSONObject(String(downloadRelayAsset(urlOf(manifestAsset), channel, pat), Charsets.UTF_8))
        if (manifest.optString("product") != "relay") {
            Log.w("FirmwareRepo", "relay $tag manifest product=${manifest.optString("product")}")
            return null
        }
        val byteStr = manifest.getString("fw_version_byte")
        val b = byteStr.removePrefix("0x").removePrefix("0X").toInt(16)
        val version = "${b shr 4}.${b and 0x0F}"
        val packageName = manifest.getString("dfu_package")
        val zip = asset(packageName)
        if (zip == null) {
            Log.w("FirmwareRepo", "relay $tag missing $packageName")
            return null
        }
        val build = if (manifest.has("build")) manifest.getInt("build") else null
        Log.d("FirmwareRepo", "relay $tag -> $byteStr build ${build ?: "-"}")
        return RelayReleaseInfo(
            version = version,
            versionByte = byteStr,
            build = build,
            channel = channel,
            tag = tag,
            zipUrl = urlOf(zip),
            sha256 = manifest.getString("dfu_package_sha256").lowercase()
        )
    }

    private fun downloadRelayAsset(url: String, channel: RelayChannel, pat: String?): ByteArray =
        if (channel == RelayChannel.DEVELOPMENT) {
            downloadAsset(url, pat ?: "")  // asset API url + octet-stream + PAT; 302 → storage
        } else {
            httpGetBytes(url, pat = null, accept = "application/octet-stream")
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

    // Relay requests surface the HTTP status ("HTTP 404") rather than a bare
    // FileNotFoundException, so a PAT without rareBit-Relay access says so.
    private fun httpGetBytes(url: String, pat: String?, accept: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        pat?.let { conn.setRequestProperty("Authorization", "token $it") }
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "rareBit-Android")
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return conn.inputStream.use { it.readBytes() }
    }

    private fun httpGet(url: String, pat: String?): String =
        String(httpGetBytes(url, pat, "application/vnd.github+json"), Charsets.UTF_8)

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
