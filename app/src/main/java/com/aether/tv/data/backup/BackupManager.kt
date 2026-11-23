package com.aether.tv.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.aether.tv.core.model.LauncherSettings
import com.aether.tv.data.assets.AssetKind
import com.aether.tv.data.assets.AssetStore
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.db.AppMetaEntity
import com.aether.tv.data.db.CategoryEntity
import com.aether.tv.data.db.DeviceEntity
import com.aether.tv.data.db.FavoriteEntity
import com.aether.tv.data.db.GameShortcutEntity
import com.aether.tv.data.db.ProfileEntity
import com.aether.tv.data.settings.AetherJson
import com.aether.tv.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A picture inside a backup: the uri it had on the TV that made the backup, and its file in the archive. */
@Serializable
data class BackupAsset(val uri: String, val path: String, val sha256: String, val size: Long)

/** Everything needed to rebuild the launcher. Provider credentials are deliberately excluded. */
@Serializable
data class BackupFile(
    val format: Int = BackupManager.FORMAT,
    val createdAt: Long,
    val appVersion: String,
    val profiles: List<ProfileEntity>,
    val settings: Map<String, LauncherSettings>,
    val favorites: List<FavoriteEntity>,
    val appMeta: List<AppMetaEntity>,
    val categories: List<CategoryEntity>,
    val devices: List<DeviceEntity>,
    val games: List<GameShortcutEntity>,
    /** Format 2: wallpapers, icon overrides, device photos and artwork, stored in the archive. */
    val assets: List<BackupAsset> = emptyList(),
)

/** What a restore did, for the message shown afterwards. */
data class RestoreReport(val profiles: Int, val favorites: Int, val devices: Int, val pictures: Int, val missingPictures: List<String>, val recovered: Boolean = false)

class BackupException(message: String) : Exception(message)

/**
 * Backups (format 2) are ZIP files: `backup.json` plus every picture a profile, favorite, device or game uses, so a
 * restore onto a fresh installation brings back custom wallpapers, device photos and icon overrides. Older JSON backups
 * (format 1, Aether 1.0–1.1) still restore; their pictures come back if they're still on the TV.
 *
 * A restore never half-happens:
 * 1. the whole backup is read and checked (structure, every picture present with the right checksum) before anything
 *    is replaced; a damaged backup changes nothing;
 * 2. the current data is saved first (`pre-restore` backup, so a restore can be undone);
 * 3. the backup is copied into private storage with a journal; if Aether stops midway (power cut, crash), the restore
 *    is finished from that copy on the next start.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AetherDatabase,
    private val settings: SettingsRepository,
    private val assets: AssetStore,
) {
    /** App-specific external folder, reachable with `adb pull` and most file managers without extra permissions. */
    val backupDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }
    private val journalDir: File get() = File(context.filesDir, "restore").apply { mkdirs() }
    private val journal: File get() = File(journalDir, "journal")
    private val pendingCopy: File get() = File(journalDir, "pending.zip")
    /** The data as it was just before the last restore. */
    val preRestoreBackup: File get() = File(context.filesDir, "pre-restore-backup.zip")

    suspend fun build(): BackupFile = withContext(Dispatchers.IO) {
        val profiles = db.profiles().all()
        BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersion = com.aether.tv.BuildConfig.VERSION_NAME,
            profiles = profiles,
            settings = profiles.associate { it.id to settings.get(it.id) },
            favorites = profiles.flatMap { db.favorites().all(it.id) },
            appMeta = profiles.flatMap { db.appMeta().all(it.id) },
            categories = profiles.flatMap { db.categories().all(it.id) },
            devices = db.devices().all(),
            games = profiles.flatMap { db.games().all(it.id) },
        )
    }

    fun encode(b: BackupFile): String = AetherJson.encodeToString(BackupFile.serializer(), b)
    fun decode(text: String): BackupFile = AetherJson.decodeFromString(BackupFile.serializer(), text)

    /** Every picture uri the backup refers to that is a file on this TV. */
    private fun fileUris(b: BackupFile): Set<String> = buildSet {
        b.settings.values.forEach { add(it.appearance.wallpaper) }
        b.appMeta.forEach { it.customIconUri?.let(::add) }
        b.favorites.forEach { it.artworkUri?.let(::add) }
        b.devices.forEach { it.artworkUri?.let(::add) }
        b.games.forEach { it.artworkUri?.let(::add) }
    }.filter { it.startsWith("file:") }.toSet()

    /** Writes a format 2 backup (data and pictures) to [out]. */
    suspend fun exportTo(out: OutputStream) = withContext(Dispatchers.IO) {
        val data = build()
        val files = fileUris(data).mapNotNull { uri -> File(uri.removePrefix("file:")).takeIf { it.isFile }?.let { uri to it } }
        val list = files.mapIndexed { i, (uri, f) -> BackupAsset(uri, "assets/$i-${f.name.take(40)}", sha256(f), f.length()) }
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME)); zip.write(encode(data.copy(assets = list)).toByteArray()); zip.closeEntry()
            list.zip(files).forEach { (a, pair) ->
                zip.putNextEntry(ZipEntry(a.path)); pair.second.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    suspend fun exportToFolder(): File = withContext(Dispatchers.IO) {
        val name = "aether-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val f = File(backupDir, name)
        val tmp = File(backupDir, "$name.part")
        tmp.outputStream().use { exportTo(it) }
        if (!tmp.renameTo(f)) throw BackupException("Couldn't save the backup.")
        f
    }

    /** Backups in the TV folder, newest first: this version's ZIPs and older JSON backups. */
    fun listBackups(): List<File> = backupDir.listFiles { f -> f.isFile && (f.extension == "zip" || f.extension == "json") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun deleteBackup(f: File): Boolean = f.canonicalPath.startsWith(backupDir.canonicalPath + File.separator) && f.delete()

    /** A checked backup, ready to apply: its data and its pictures unpacked to [dir]. */
    private class Checked(val data: BackupFile, val dir: File?, val pictures: Map<String, File>)

    /**
     * Reads and checks a backup completely. Throws [BackupException] (and changes nothing) when it's damaged, isn't an
     * Aether backup, is from a newer format, or a picture is missing or doesn't match its checksum.
     */
    private fun check(file: File, unpackTo: File): Checked {
        val head = file.inputStream().use { i -> ByteArray(2).also { i.read(it) } }
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (!isZip) {
            val b = runCatching { decode(file.readText()) }.getOrElse { throw BackupException("This file isn't an Aether backup.") }
            validate(b)
            return Checked(b, null, emptyMap())
        }
        unpackTo.deleteRecursively(); unpackTo.mkdirs()
        var json: String? = null
        val unpacked = HashMap<String, File>()
        runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name.contains("..")) throw BackupException("The backup contains an unsafe file name.")
                    if (e.name == JSON_NAME) json = zip.readBytes().decodeToString()
                    else if (e.name.startsWith("assets/")) {
                        val out = File(unpackTo, "a${unpacked.size}.img")
                        out.outputStream().use { zip.copyTo(it) }
                        unpacked[e.name] = out
                    }
                }
            }
        }.getOrElse { if (it is BackupException) throw it; throw BackupException("The backup file is damaged or incomplete (${it.message}).") }
        val b = runCatching { decode(json ?: throw BackupException("The backup has no backup.json.")) }
            .getOrElse { if (it is BackupException) throw it; throw BackupException("The backup's data can't be read.") }
        validate(b)
        val pictures = HashMap<String, File>()
        for (a in b.assets) {
            val f = unpacked[a.path] ?: throw BackupException("The backup is incomplete: a picture (${a.path}) is missing.")
            if (f.length() != a.size || !sha256(f).equals(a.sha256, true)) throw BackupException("The backup is damaged: a picture (${a.path}) doesn't match its checksum.")
            pictures[a.uri] = f
        }
        return Checked(b, unpackTo, pictures)
    }

    private fun validate(b: BackupFile) {
        if (b.format !in 1..FORMAT) throw BackupException("This backup is from a newer Aether (format ${b.format}). Update Aether first.")
        if (b.profiles.isEmpty()) throw BackupException("The backup has no profiles.")
        val ids = b.profiles.map { it.id }.toSet()
        if (b.favorites.any { it.profileId !in ids } || b.appMeta.any { it.profileId !in ids } || b.games.any { it.profileId !in ids } || b.categories.any { it.profileId !in ids }) {
            throw BackupException("The backup's data doesn't fit together (entries for a profile that isn't in it).")
        }
    }

    suspend fun restore(input: InputStream): RestoreReport = withContext(Dispatchers.IO) {
        val tmp = File(journalDir, "incoming.bin")
        input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        try { restore(tmp) } finally { tmp.delete() }
    }

    suspend fun restore(text: String): RestoreReport = withContext(Dispatchers.IO) {
        val tmp = File(journalDir, "incoming.json").apply { writeText(text) }
        try { restore(tmp) } finally { tmp.delete() }
    }

    /** Checks [file] completely, saves the current data, then replaces everything with the backup's contents. */
    suspend fun restore(file: File): RestoreReport = withContext(Dispatchers.IO) {
        check(file, File(journalDir, "check")) // throws before anything changes
        File(journalDir, "check").deleteRecursively()
        runCatching { preRestoreBackup.outputStream().use { exportTo(it) } }
        file.copyTo(pendingCopy, overwrite = true)
        journal.writeText("restore ${System.currentTimeMillis()}")
        apply(recovered = false)
    }

    /** Finishes a restore that was interrupted (called when Aether starts). Null when there was nothing to finish. */
    suspend fun finishInterruptedRestore(): RestoreReport? = withContext(Dispatchers.IO) {
        if (!journal.exists()) return@withContext null
        if (!pendingCopy.exists()) { journal.delete(); return@withContext null }
        runCatching { apply(recovered = true) }.getOrElse { journal.delete(); null }
    }

    private suspend fun apply(recovered: Boolean): RestoreReport {
        val staging = File(journalDir, "staging")
        val c = check(pendingCopy, staging)
        val b = c.data
        // Pictures: copies from the backup become Aether's own files; older backups keep pictures that are still here.
        val mapped = HashMap<String, String?>()
        val missing = ArrayList<String>()
        for (uri in fileUris(b)) {
            val packed = c.pictures[uri]
            mapped[uri] = when {
                packed != null -> "file:" + assets.adopt(kindOf(uri), packed, "restored").path
                File(uri.removePrefix("file:")).isFile -> uri
                else -> { missing += uri.substringAfterLast('/'); null }
            }
        }
        fun map(u: String?): String? = if (u != null && u.startsWith("file:")) mapped[u] else u
        val defaultWallpaper = LauncherSettings().appearance.wallpaper
        db.withTransaction {
            db.clearAllTables()
            b.profiles.forEach { db.profiles().upsert(it) }
            db.favorites().upsertAll(b.favorites.map { it.copy(artworkUri = map(it.artworkUri)) })
            db.appMeta().upsertAll(b.appMeta.map { it.copy(customIconUri = map(it.customIconUri)) })
            b.categories.forEach { db.categories().upsert(it) }
            b.devices.forEach { db.devices().upsert(it.copy(artworkUri = map(it.artworkUri))) }
            b.games.forEach { db.games().upsert(it.copy(artworkUri = map(it.artworkUri))) }
        }
        b.settings.forEach { (id, s) ->
            settings.replace(id, s.copy(appearance = s.appearance.copy(wallpaper = map(s.appearance.wallpaper) ?: defaultWallpaper)))
        }
        staging.deleteRecursively()
        journal.delete()
        pendingCopy.delete()
        assets.deleteUnused()
        return RestoreReport(b.profiles.size, b.favorites.size, b.devices.size, c.pictures.size, missing, recovered)
    }

    private fun kindOf(uri: String): AssetKind {
        val dir = File(uri.removePrefix("file:")).parentFile?.name
        return AssetKind.entries.firstOrNull { it.dir == dir } ?: AssetKind.Art
    }

    companion object {
        const val FORMAT = 2
        const val JSON_NAME = "backup.json"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
