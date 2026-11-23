package com.aether.tv.data.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `aether-update.json`, attached to every GitHub release by the release workflow next to the APK. It says, without
 * downloading the APK, which installs that APK can update.
 */
@Serializable
data class UpdateMetadata(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    /** Native-code ABIs inside the APK; empty means none (runs on every ABI). */
    val abis: List<String> = emptyList(),
    /** File name of the APK asset in the same release. */
    val apk: String,
    val size: Long,
    /** SHA-256 of the APK file, hex. */
    val sha256: String,
    /** SHA-256 of each signing certificate, hex. */
    val signerSha256: List<String>,
)

data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** A published GitHub release, as far as the updater cares. */
data class Release(
    val tag: String,
    val name: String,
    val publishedAt: String?,
    val notes: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
    val metadata: UpdateMetadata? = null,
) {
    val metadataAsset: ReleaseAsset? get() = assets.firstOrNull { it.name == METADATA_NAME }
    val apkAsset: ReleaseAsset? get() = metadata?.let { m -> assets.firstOrNull { it.name == m.apk } }

    companion object { const val METADATA_NAME = "aether-update.json" }
}

/** This install, as the updater compares it. */
data class Installed(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val sdk: Int,
    val supportedAbis: List<String>,
    val signerSha256: List<String>,
)

sealed interface Selection {
    /** [release] is newer and installable here. */
    data class Available(val release: Release, val metadata: UpdateMetadata, val apk: ReleaseAsset) : Selection
    /** Nothing newer, or nothing newer that fits; [newest] is the newest compatible release (maybe this one). */
    data class UpToDate(val newest: Release?, val note: String?) : Selection
    /** The newest release can't update this install, with the reason (signed with another key, needs newer Android…). */
    data class Incompatible(val release: Release, val reason: String) : Selection
}

object UpdateRules {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** GitHub's `GET /repos/{owner}/{repo}/releases` answer. */
    fun parseReleases(body: String): List<Release> = json.parseToJsonElement(body).let { it as? JsonArray ?: JsonArray(emptyList()) }.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        fun b(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
        Release(
            tag = s("tag_name") ?: return@mapNotNull null, name = s("name") ?: s("tag_name")!!, publishedAt = s("published_at"),
            notes = s("body").orEmpty(), draft = b("draft"), prerelease = b("prerelease"),
            assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                ReleaseAsset(
                    ao["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    ao["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    ao["size"]?.jsonPrimitive?.longOrNull ?: 0,
                )
            },
        )
    }

    fun parseMetadata(body: String): UpdateMetadata? = runCatching { json.decodeFromString(UpdateMetadata.serializer(), body) }.getOrNull()

    /** Stable, published releases that carry update metadata (older releases without it can't be compared safely). */
    fun candidates(releases: List<Release>): List<Release> = releases.filter { !it.draft && !it.prerelease && it.metadataAsset != null }

    /**
     * Picks the newest release that can update this install: same application id, a higher numeric versionCode, an
     * Android version it supports, native code for one of this TV's ABIs (the M450 is 32-bit only: armeabi-v7a), and
     * the same signing key. [releases] must have their metadata loaded.
     */
    fun select(releases: List<Release>, installed: Installed): Selection {
        val withMeta = releases.filter { !it.draft && !it.prerelease && it.metadata != null }
            .filter { it.metadata!!.applicationId == installed.applicationId }
            .sortedByDescending { it.metadata!!.versionCode }
        val newer = withMeta.filter { it.metadata!!.versionCode > installed.versionCode }
        if (newer.isEmpty()) {
            val note = if (releases.none { it.metadata?.applicationId == installed.applicationId } && releases.isNotEmpty())
                "No release is published for ${installed.applicationId}." else null
            return Selection.UpToDate(withMeta.firstOrNull(), note)
        }
        var firstProblem: Selection.Incompatible? = null
        for (r in newer) {
            val m = r.metadata!!
            val problem = problem(m, r, installed)
            if (problem == null) return Selection.Available(r, m, r.apkAsset!!)
            if (firstProblem == null) firstProblem = Selection.Incompatible(r, problem)
        }
        return firstProblem!!
    }

    /** Why [m] can't update [installed], or null when it can. */
    fun problem(m: UpdateMetadata, r: Release?, installed: Installed): String? = when {
        m.applicationId != installed.applicationId -> "It is a different app (${m.applicationId})."
        m.versionCode <= installed.versionCode -> "It isn't newer than the installed version."
        m.minSdk > installed.sdk -> "It needs Android API ${m.minSdk}; this TV has API ${installed.sdk}."
        m.abis.isNotEmpty() && m.abis.none { it in installed.supportedAbis } ->
            "It has no native code for this TV (${installed.supportedAbis.joinToString()}); it has ${m.abis.joinToString()}."
        r != null && r.apkAsset == null -> "The release lists ${m.apk}, but that file isn't attached."
        installed.signerSha256.isNotEmpty() && m.signerSha256.isNotEmpty() && !sameSigners(m.signerSha256, installed.signerSha256) ->
            "It is signed with a different key (${short(m.signerSha256.first())}) than the installed Aether (${short(installed.signerSha256.first())}). " +
                "Android refuses such an update; see Settings ▸ Updates ▸ About signing."
        else -> null
    }

    fun sameSigners(a: List<String>, b: List<String>) = a.map { it.lowercase() }.toSet() == b.map { it.lowercase() }.toSet()
    fun short(hex: String) = hex.lowercase().chunked(2).take(4).joinToString(":") + "…"

    /** What was read from the downloaded APK itself, before handing it to Android's installer. */
    data class Archive(val packageName: String?, val versionCode: Long, val signerSha256: List<String>, val sha256: String, val size: Long)

    /**
     * Checks the downloaded file is exactly the update that was offered: the same package, a newer version, the
     * same signers as the installed Aether, and the size and SHA-256 the release metadata promised. Returns the reason
     * it must not be installed, or null.
     */
    fun verify(a: Archive, expected: UpdateMetadata, installed: Installed): String? = when {
        a.packageName == null -> "The downloaded file isn't an Android app."
        a.packageName != installed.applicationId -> "The downloaded app is ${a.packageName}, not Aether (${installed.applicationId})."
        a.size != expected.size -> "The download is incomplete (${a.size} of ${expected.size} bytes)."
        !a.sha256.equals(expected.sha256, ignoreCase = true) -> "The download doesn't match the release (checksum differs)."
        a.versionCode != expected.versionCode -> "The downloaded version (${a.versionCode}) isn't the one offered (${expected.versionCode})."
        a.versionCode <= installed.versionCode -> "That would be a downgrade (${a.versionCode} ≤ ${installed.versionCode})."
        a.signerSha256.isEmpty() -> "The downloaded app isn't signed."
        installed.signerSha256.isNotEmpty() && !sameSigners(a.signerSha256, installed.signerSha256) ->
            "The downloaded app is signed with a different key (${short(a.signerSha256.first())}); Android would refuse it."
        else -> null
    }

    /** GitHub release notes are Markdown; the TV shows them as plain text. */
    fun plainNotes(md: String): String = md.lines().joinToString("\n") { l ->
        l.replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ").replace("**", "").replace("`", "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
    }.trim()
}
