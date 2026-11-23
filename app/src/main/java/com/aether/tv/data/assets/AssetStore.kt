package com.aether.tv.data.assets

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Kinds of picture Aether keeps its own copy of, each in its own folder of app-private storage. */
enum class AssetKind(val dir: String, val label: String) {
    Wallpaper("wallpapers", "Wallpapers"),
    Icon("icons", "App icon overrides"),
    Device("devices", "Device photos"),
    Art("art", "Favorite and game artwork"),
}

sealed interface ImportResult {
    data class Ok(val file: File) : ImportResult
    data class Failed(val reason: String) : ImportResult
}

/**
 * Pictures the user gave Aether: wallpapers, icon overrides, device photos and artwork.
 *
 * - **Aether's copies** live in app-private storage (`files/<kind>`). Only these are ever deleted, and only when no
 *   profile, favorite, device or game refers to them any more.
 * - **User-owned originals** (the TV folder `Android/data/<pkg>/files/<kind>`, filled with adb or a file manager) are
 *   never deleted or modified; using one copies it.
 * - **Previews** (a wallpaper downloaded or picked but not applied yet) live in the cache and are removed when the
 *   preview closes or Aether next starts.
 */
@Singleton
class AssetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AetherDatabase,
    private val settings: SettingsRepository,
) {
    fun dir(kind: AssetKind): File = File(context.filesDir, kind.dir).apply { mkdirs() }

    /** The folder you can copy pictures into without any permission (adb push, file managers). */
    fun tvFolder(kind: AssetKind): File = File(context.getExternalFilesDir(null) ?: context.filesDir, kind.dir).apply { mkdirs() }

    val previewDir: File get() = File(context.cacheDir, "wallpaper-preview").apply { mkdirs() }

    fun isPrivate(f: File): Boolean = AssetKind.entries.any { f.canonicalPath.startsWith(dir(it).canonicalPath + File.separator) }
    fun isPreview(f: File): Boolean = f.canonicalPath.startsWith(previewDir.canonicalPath + File.separator)

    /** Pictures in the TV folder for [kind], newest first. */
    fun tvFolderImages(kind: AssetKind): List<File> = tvFolder(kind)
        .listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Absolute paths of every picture something in Aether still uses, across all profiles. */
    suspend fun referencedPaths(): Set<String> = withContext(Dispatchers.IO) {
        val uris = buildList {
            db.profiles().all().forEach { p -> add(settings.get(p.id).appearance.wallpaper) }
            db.appMeta().allRows().forEach { add(it.customIconUri) }
            db.favorites().allRows().forEach { add(it.artworkUri) }
            db.devices().all().forEach { add(it.artworkUri) }
            db.games().allRows().forEach { add(it.artworkUri) }
        }
        uris.mapNotNull { u -> u?.takeIf { it.startsWith("file:") }?.removePrefix("file:")?.let { runCatching { File(it).canonicalPath }.getOrNull() } }.toSet()
    }

    /** Aether's own copies that nothing uses any more (safe to delete). */
    suspend fun unused(kinds: Collection<AssetKind> = AssetKind.entries): List<File> = withContext(Dispatchers.IO) {
        val used = referencedPaths()
        kinds.flatMap { k -> dir(k).listFiles()?.filter { it.isFile && it.canonicalPath !in used }.orEmpty() }
    }

    /** Deletes [unused] copies; returns the bytes freed. Never touches the TV folder or anything still referenced. */
    suspend fun deleteUnused(kinds: Collection<AssetKind> = AssetKind.entries): Long = withContext(Dispatchers.IO) {
        unused(kinds).sumOf { f -> val n = f.length(); if (f.delete()) n else 0L }
    }

    /** Removes wallpaper previews left over (a preview closed by a crash, or Aether stopped mid-download). */
    fun clearPreviews(): Long = previewDir.listFiles()?.sumOf { f -> val n = f.length(); if (f.delete()) n else 0L } ?: 0L

    fun newPreviewFile(): File = File(previewDir, "preview-${System.currentTimeMillis()}.img")

    /**
     * Makes [source] one of Aether's own copies of [kind]: a preview is moved (it's ours), anything else is copied (the
     * original stays where it is). An existing private copy is returned as it is.
     */
    suspend fun adopt(kind: AssetKind, source: File, name: String): File = withContext(Dispatchers.IO) {
        if (isPrivate(source)) return@withContext source
        val out = uniqueFile(kind, name)
        if (isPreview(source) && source.renameTo(out)) return@withContext out
        source.inputStream().use { i -> out.outputStream().use { i.copyTo(it) } }
        if (isPreview(source)) source.delete()
        out
    }

    private fun uniqueFile(kind: AssetKind, name: String) = File(dir(kind), "$name-${System.currentTimeMillis()}.img")

    /** Copies a picture the user chose with the system picker, checking that it is one. */
    suspend fun importUri(uri: Uri, target: File): ImportResult = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext ImportResult.Failed("The file couldn't be opened.")
            input.use { copyLimited(it, target) }
            checkImage(target)
        }.getOrElse { target.delete(); ImportResult.Failed(it.message ?: "The file couldn't be read.") }
    }

    /** Downloads a picture from [url] into [target], with a clear reason when it isn't one. */
    suspend fun download(url: String, target: File, client: OkHttpClient = com.aether.tv.data.media.JsonHttp.defaultClient): ImportResult = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return@withContext ImportResult.Failed("Enter an address starting with https://")
        runCatching {
            val req = Request.Builder().url(clean).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext ImportResult.Failed("The server answered HTTP ${r.code}.")
                val type = r.header("Content-Type").orEmpty()
                if (type.isNotEmpty() && !type.startsWith("image/") && !type.startsWith("application/octet-stream")) {
                    return@withContext ImportResult.Failed("That address is a web page ($type), not a picture. Use a direct link to a JPG, PNG or WebP.")
                }
                val len = r.body?.contentLength() ?: -1
                if (len > MAX_BYTES) return@withContext ImportResult.Failed("The picture is larger than ${MAX_BYTES / 1024 / 1024} MB.")
                r.body!!.byteStream().use { copyLimited(it, target) }
            }
            checkImage(target)
        }.getOrElse { target.delete(); ImportResult.Failed(networkReason(it)) }
    }

    private fun copyLimited(input: InputStream, target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("The picture is larger than ${MAX_BYTES / 1024 / 1024} MB.")
                out.write(buf, 0, n)
            }
        }
    }

    private fun checkImage(f: File): ImportResult {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.path, o) }
        return if (o.outWidth > 0 && o.outHeight > 0) ImportResult.Ok(f)
        else { f.delete(); ImportResult.Failed("That isn't a picture Android can open (JPEG, PNG or WebP).") }
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        const val MAX_BYTES = 15L * 1024 * 1024

        fun networkReason(t: Throwable): String = when (t) {
            is java.net.UnknownHostException -> "No connection, or that address doesn't exist."
            is java.net.SocketTimeoutException -> "The server didn't answer in time."
            is javax.net.ssl.SSLException -> "A secure connection to that server couldn't be made."
            is IllegalArgumentException -> "That isn't a valid web address."
            else -> t.message ?: "The download failed."
        }
    }
}
