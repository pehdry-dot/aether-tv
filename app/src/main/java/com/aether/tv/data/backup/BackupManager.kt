package com.aether.tv.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.aether.tv.core.model.LauncherSettings
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Everything needed to rebuild the launcher. Provider credentials are deliberately excluded. */
@Serializable
data class BackupFile(
    val format: Int = 1,
    val createdAt: Long,
    val appVersion: String,
    val profiles: List<ProfileEntity>,
    val settings: Map<String, LauncherSettings>,
    val favorites: List<FavoriteEntity>,
    val appMeta: List<AppMetaEntity>,
    val categories: List<CategoryEntity>,
    val devices: List<DeviceEntity>,
    val games: List<GameShortcutEntity>,
)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AetherDatabase,
    private val settings: SettingsRepository,
) {
    /** App-specific external folder, reachable with `adb pull` and most file managers without extra permissions. */
    val backupDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }

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

    suspend fun exportToFolder(): File = withContext(Dispatchers.IO) {
        val name = "aether-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
        File(backupDir, name).apply { writeText(encode(build())) }
    }

    suspend fun exportTo(out: OutputStream) = withContext(Dispatchers.IO) { out.use { it.write(encode(build()).toByteArray()) } }

    fun listBackups(): List<File> = backupDir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()

    suspend fun restore(input: InputStream) = restore(withContext(Dispatchers.IO) { input.use { it.readBytes().decodeToString() } })

    /** Replaces all launcher data with the backup's contents, atomically for the database. */
    suspend fun restore(text: String) {
        val b = decode(text)
        require(b.format == 1) { "Unsupported backup format ${b.format}" }
        db.withTransaction {
            db.clearAllTables()
            b.profiles.forEach { db.profiles().upsert(it) }
            db.favorites().upsertAll(b.favorites)
            db.appMeta().upsertAll(b.appMeta)
            b.categories.forEach { db.categories().upsert(it) }
            b.devices.forEach { db.devices().upsert(it) }
            b.games.forEach { db.games().upsert(it) }
        }
        b.settings.forEach { (id, s) -> settings.replace(id, s) }
    }
}
