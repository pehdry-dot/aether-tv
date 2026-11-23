package com.aether.tv.data.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import coil3.SingletonImageLoader
import com.aether.tv.core.designsystem.AppIconLoader
import com.aether.tv.data.assets.AssetKind
import com.aether.tv.data.assets.AssetStore
import com.aether.tv.data.backup.BackupManager
import com.aether.tv.data.media.SessionArtCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class FileInfo(val file: File, val size: Long, val modified: Long)
data class AssetUsage(val kind: AssetKind, val files: Int, val bytes: Long, val unusedFiles: Int, val unusedBytes: Long)

data class StorageReport(
    /** The TV's internal storage (the data partition apps install to). */
    val totalBytes: Long,
    val freeBytes: Long,
    /** Downloaded artwork (posters, logos) Aether can fetch again: safe to clear. */
    val artworkCacheBytes: Long,
    val backups: List<FileInfo>,
    val assets: List<AssetUsage>,
    /** Pictures you copied to the TV folder: yours, never deleted by Aether. */
    val tvFolderBytes: Long,
    val updateBytes: Long,
    /** Aether's own data: database and settings (profiles, favorites). Never cleared here. */
    val dataBytes: Long,
)

/**
 * Settings ▸ TV & System ▸ Storage, without Amazon's Settings: everything here is measured and cleaned by Aether itself,
 * so it works while Amazon's launcher (and with it the Fire TV storage screen) is switched off.
 *
 * Only Aether's own, regenerable or unused files can be removed: the artwork cache, pictures no profile uses any more,
 * backups you pick, and leftover update downloads. Wallpapers and pictures in use by any profile, the database
 * (profiles, favorites, devices), credentials and files in the TV folder are never touched.
 */
@Singleton
class StorageInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assets: AssetStore,
    private val backup: BackupManager,
) {
    private val artworkDir get() = File(context.cacheDir, "artwork")
    private val updatesDir get() = File(context.filesDir, "updates")

    suspend fun report(): StorageReport = withContext(Dispatchers.IO) {
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val unused = assets.unused().map { it.canonicalPath }.toSet()
        StorageReport(
            totalBytes = stat?.totalBytes ?: 0, freeBytes = stat?.availableBytes ?: 0,
            artworkCacheBytes = size(artworkDir),
            backups = backup.listBackups().map { FileInfo(it, it.length(), it.lastModified()) },
            assets = AssetKind.entries.map { k ->
                val files = assets.dir(k).listFiles()?.filter { it.isFile }.orEmpty()
                val un = files.filter { it.canonicalPath in unused }
                AssetUsage(k, files.size, files.sumOf { it.length() }, un.size, un.sumOf { it.length() })
            },
            tvFolderBytes = AssetKind.entries.sumOf { size(assets.tvFolder(it)) },
            updateBytes = size(updatesDir),
            dataBytes = size(context.getDatabasePath("aether.db").parentFile) + size(File(context.filesDir, "datastore")),
        )
    }

    /** Empties the artwork cache (downloaded posters and logos are fetched again when shown). Returns bytes freed. */
    suspend fun clearArtworkCache(): Long = withContext(Dispatchers.IO) {
        val before = size(artworkDir)
        runCatching { SingletonImageLoader.get(context).let { it.diskCache?.clear(); it.memoryCache?.clear() } }
        artworkDir.listFiles()?.forEach { it.deleteRecursively() }
        AppIconLoader.invalidate()
        SessionArtCache.clear()
        (before - size(artworkDir)).coerceAtLeast(0)
    }

    suspend fun deleteUnusedPictures(): Long = assets.deleteUnused()

    suspend fun deleteBackup(f: File): Boolean = withContext(Dispatchers.IO) { backup.deleteBackup(f) }

    suspend fun deleteUpdateDownloads(): Long = withContext(Dispatchers.IO) {
        val n = size(updatesDir); updatesDir.listFiles()?.forEach { it.delete() }; n
    }

    companion object {
        fun size(f: File?): Long = when {
            f == null || !f.exists() -> 0
            f.isFile -> f.length()
            else -> f.listFiles()?.sumOf { size(it) } ?: 0
        }

        fun format(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "${bytes shr 10} KB"
            else -> "$bytes B"
        }
    }
}
