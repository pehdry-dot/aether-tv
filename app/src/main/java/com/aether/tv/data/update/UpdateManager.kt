package com.aether.tv.data.update

import android.content.Context
import android.os.StatFs
import com.aether.tv.BuildConfig
import com.aether.tv.data.settings.SettingsRepository
import com.aether.tv.platform.ApkInstaller
import com.aether.tv.platform.EventLog
import com.aether.tv.platform.InstallEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long, val newest: Release?, val note: String?) : UpdateState
    data class Available(val release: Release, val meta: UpdateMetadata, val apk: ReleaseAsset) : UpdateState
    data class Incompatible(val release: Release, val reason: String) : UpdateState
    data class Downloading(val release: Release, val meta: UpdateMetadata, val apk: ReleaseAsset, val done: Long, val total: Long) : UpdateState
    data class Verifying(val release: Release, val meta: UpdateMetadata) : UpdateState
    /** Downloaded and verified: ready for Android's installer. */
    data class Ready(val release: Release, val meta: UpdateMetadata, val file: File, val note: String? = null) : UpdateState
    /** Verified, but Aether may not install apps yet ("Install unknown apps"); continues when you come back. */
    data class NeedsPermission(val release: Release, val meta: UpdateMetadata, val file: File) : UpdateState
    /** Android's confirmation screen is up. */
    data class Installing(val release: Release, val meta: UpdateMetadata) : UpdateState
    data class Failed(val message: String, val retry: Retry, val release: Release? = null, val meta: UpdateMetadata? = null, val apk: ReleaseAsset? = null) : UpdateState
    enum class Retry { Check, Download, Install }
}

/**
 * Settings ▸ Updates: Aether updates itself from the GitHub Releases of [BuildConfig.UPDATE_REPO].
 *
 * Only stable, published releases that carry `aether-update.json` are considered, and only one that can really update
 * this install (application id, numeric versionCode, Android version, ABI, signing key; see [UpdateRules.select]).
 * The APK goes to app-private storage, is checked against the release metadata and the installed app's signers, and is
 * handed to Android's own installer, whose confirmation the remote approves. Checks happen when you ask, and at most once
 * a day when Aether comes to the front (switchable); never in the background.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val installer: ApkInstaller,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _autoCheck = MutableStateFlow(true)
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()
    private val dismissedCode = MutableStateFlow(0L)
    private val _lastCheckAt = MutableStateFlow(0L)
    val lastCheckAt: StateFlow<Long> = _lastCheckAt.asStateFlow()

    /** The Home notice ("Aether 1.3.0 is available"), unless dismissed for that version. */
    val notice: StateFlow<String?> = combine(_state, dismissedCode) { s, d ->
        val meta = when (s) { is UpdateState.Available -> s.meta; is UpdateState.Ready -> s.meta; is UpdateState.NeedsPermission -> s.meta; else -> null }
        meta?.takeIf { it.versionCode != d }?.let { "Aether ${it.versionName} is available" }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    internal var apiBase = "https://api.github.com"
    internal var client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    internal var now: () -> Long = System::currentTimeMillis
    internal var installedInfo: () -> Installed = { installer.installed() }
    internal var readArchive: suspend (File) -> UpdateRules.Archive = { installer.readArchive(it) }
    internal var canInstall: () -> Boolean = { installer.canInstall() }
    internal var startInstall: suspend (File) -> Unit = { installer.install(it) }
    private var job: Job? = null

    val updatesDir: File get() = File(context.filesDir, "updates").apply { mkdirs() }

    init {
        scope.launch {
            _autoCheck.value = !settings.flag(FLAG_AUTO_OFF)
            dismissedCode.value = settings.number(KEY_DISMISSED)
            _lastCheckAt.value = settings.number(KEY_LAST_CHECK)
            cleanUp()
        }
        scope.launch {
            installer.events.collect { e ->
                val s = _state.value
                val (release, meta) = when (s) {
                    is UpdateState.Installing -> s.release to s.meta
                    is UpdateState.Ready -> s.release to s.meta
                    else -> return@collect
                }
                when (e) {
                    InstallEvent.WaitingForYou -> _state.value = UpdateState.Installing(release, meta)
                    InstallEvent.Installed -> EventLog.log("Update ${meta.versionName} installed")
                    InstallEvent.Cancelled -> _state.value = UpdateState.Ready(release, meta, apkFile(meta), "Installation was cancelled. Nothing changed.")
                    is InstallEvent.Failed -> {
                        EventLog.log("Update ${meta.versionName} failed: ${e.reason}")
                        _state.value = UpdateState.Failed(e.reason, UpdateState.Retry.Install, release, meta)
                    }
                }
            }
        }
    }

    fun setAutoCheck(on: Boolean) { _autoCheck.value = on; scope.launch { settings.setFlag(FLAG_AUTO_OFF, !on) } }

    /** Hides the Home notice for the version on offer (a newer one shows it again). */
    fun dismissNotice() {
        val code = when (val s = _state.value) { is UpdateState.Available -> s.meta.versionCode; is UpdateState.Ready -> s.meta.versionCode; is UpdateState.NeedsPermission -> s.meta.versionCode; else -> return }
        dismissedCode.value = code
        scope.launch { settings.setNumber(KEY_DISMISSED, code) }
    }

    /** Aether came to the front: continue an install that waited for permission; check if the daily check is due. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && canInstall()) install()
        if (s is UpdateState.Installing) _state.value = UpdateState.Ready(s.release, s.meta, apkFile(s.meta))
        scope.launch {
            if (_autoCheck.value && now() - settings.number(KEY_LAST_CHECK) >= DAY_MS && _state.value.isQuiet()) check()
        }
    }

    private fun UpdateState.isQuiet() = this is UpdateState.Idle || this is UpdateState.UpToDate || this is UpdateState.Failed || this is UpdateState.Incompatible

    /** Asks GitHub for the releases and picks the update for this install, if any. */
    fun check() {
        if (job?.isActive == true) return
        job = scope.launch { _state.value = runCatching { doCheck() }.getOrElse { if (it is CancellationException) throw it; UpdateState.Failed(reason(it), UpdateState.Retry.Check) } }
    }

    private suspend fun doCheck(): UpdateState {
        _state.value = UpdateState.Checking
        val installed = installedInfo()
        val releases = UpdateRules.candidates(UpdateRules.parseReleases(get("$apiBase/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=20")))
        // Metadata of the newest few releases only: each is one small download.
        val withMeta = releases.take(5).map { r -> r.copy(metadata = runCatching { UpdateRules.parseMetadata(get(r.metadataAsset!!.url)) }.getOrNull()) }
        val t = now()
        _lastCheckAt.value = t
        settings.setNumber(KEY_LAST_CHECK, t)
        return when (val sel = UpdateRules.select(withMeta, installed)) {
            is Selection.Available -> {
                val done = apkFile(sel.metadata)
                if (done.exists() && done.length() == sel.metadata.size) verify(sel.release, sel.metadata, done) else UpdateState.Available(sel.release, sel.metadata, sel.apk)
            }
            is Selection.UpToDate -> UpdateState.UpToDate(t, sel.newest, sel.note)
            is Selection.Incompatible -> UpdateState.Incompatible(sel.release, sel.reason)
        }
    }

    /** Downloads the offered APK into app-private storage (resuming a partial download), then verifies it. */
    fun download() {
        val (release, meta, apk) = when (val s = _state.value) {
            is UpdateState.Available -> Triple(s.release, s.meta, s.apk)
            is UpdateState.Failed -> if (s.retry == UpdateState.Retry.Download && s.release != null && s.meta != null && s.apk != null) Triple(s.release, s.meta, s.apk) else return
            else -> return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = try {
                val file = fetch(release, meta, apk)
                verify(release, meta, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(reason(e) + " You can try again; the part already downloaded is kept.", UpdateState.Retry.Download, release, meta, apk)
            }
        }
    }

    /** Stops a download and removes what was downloaded. */
    fun cancel() {
        val s = _state.value
        job?.cancel()
        if (s is UpdateState.Downloading) {
            partFile(s.meta).delete()
            _state.value = UpdateState.Available(s.release, s.meta, s.apk)
        }
    }

    private suspend fun fetch(release: Release, meta: UpdateMetadata, apk: ReleaseAsset): File = withContext(Dispatchers.IO) {
        val part = partFile(meta)
        val have = if (part.exists()) part.length() else 0L
        val free = runCatching { StatFs(updatesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < meta.size - have + SPACE_MARGIN) throw IOException("Not enough free space: ${mb(meta.size + SPACE_MARGIN)} needed, ${mb(free)} free. Free some space in Settings ▸ TV & System ▸ Storage.")
        val req = Request.Builder().url(apk.url).header("User-Agent", USER_AGENT).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(req).execute().use { r ->
            if (r.code == 416) { part.delete(); throw IOException("The download had to start over.") }
            if (!r.isSuccessful) throw httpError(r.code, r.header("X-RateLimit-Remaining"), r.header("X-RateLimit-Reset"))
            val append = r.code == 206 && have > 0
            var done = if (append) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                if (!append) out.setLength(0) else out.seek(have)
                val body = r.body ?: throw IOException("Empty download")
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastShown = 0L
                    while (isActive) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done > meta.size) throw IOException("The download is larger than the release says.")
                        if (done - lastShown > 128 * 1024 || done == meta.size) { lastShown = done; _state.value = UpdateState.Downloading(release, meta, apk, done, meta.size) }
                    }
                }
            }
            if (!isActive) throw CancellationException()
        }
        if (part.length() != meta.size) throw IOException("The download stopped early (${mb(part.length())} of ${mb(meta.size)}).")
        val done = apkFile(meta)
        done.delete()
        if (!part.renameTo(done)) throw IOException("Couldn't store the download.")
        done
    }

    private suspend fun verify(release: Release, meta: UpdateMetadata, file: File): UpdateState {
        _state.value = UpdateState.Verifying(release, meta)
        val archive = readArchive(file)
        val problem = UpdateRules.verify(archive, meta, installedInfo())
        if (problem != null) {
            file.delete()
            EventLog.log("Update ${meta.versionName} rejected: $problem")
            return UpdateState.Failed(problem, UpdateState.Retry.Check, release, meta)
        }
        return UpdateState.Ready(release, meta, file)
    }

    /** Hands the verified APK to Android's installer, or first asks for "Install unknown apps". */
    fun install() {
        val (release, meta, file) = when (val s = _state.value) {
            is UpdateState.Ready -> Triple(s.release, s.meta, s.file)
            is UpdateState.NeedsPermission -> Triple(s.release, s.meta, s.file)
            is UpdateState.Failed -> if (s.retry == UpdateState.Retry.Install && s.release != null && s.meta != null) Triple(s.release, s.meta, apkFile(s.meta)) else return
            else -> return
        }
        if (!file.exists()) { _state.value = UpdateState.Failed("The downloaded file is gone. Download it again.", UpdateState.Retry.Check, release, meta); return }
        if (!canInstall()) { _state.value = UpdateState.NeedsPermission(release, meta, file); return }
        _state.value = UpdateState.Installing(release, meta)
        scope.launch {
            // The file is checked once more right before installing: it sits in app-private storage, but it costs little.
            val problem = UpdateRules.verify(readArchive(file), meta, installedInfo())
            if (problem != null) { file.delete(); _state.value = UpdateState.Failed(problem, UpdateState.Retry.Check, release, meta); return@launch }
            startInstall(file)
        }
    }

    /** Removes downloads that are installed already or abandoned. */
    private fun cleanUp() {
        val code = runCatching { installedInfo().versionCode }.getOrDefault(Long.MAX_VALUE)
        updatesDir.listFiles()?.forEach { f ->
            val vc = Regex("aether-(\\d+)\\.apk").find(f.name)?.groupValues?.get(1)?.toLongOrNull()
            if (vc == null || vc <= code) f.delete()
        }
    }

    private fun apkFile(m: UpdateMetadata) = File(updatesDir, "aether-${m.versionCode}.apk")
    private fun partFile(m: UpdateMetadata) = File(updatesDir, "aether-${m.versionCode}.apk.part")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.header("X-RateLimit-Remaining"), r.header("X-RateLimit-Reset"))
            r.body?.string().orEmpty()
        }
    }

    private fun httpError(code: Int, remaining: String?, reset: String?): IOException = when {
        (code == 403 || code == 429) && (remaining == "0" || code == 429) -> {
            val at = reset?.toLongOrNull()?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000)) }
            IOException("GitHub's limit for checks from this network is reached." + (at?.let { " Try again after $it." } ?: " Try again later."))
        }
        code == 404 -> IOException("The release list wasn't found on GitHub (${BuildConfig.UPDATE_REPO}).")
        code >= 500 -> IOException("GitHub isn't answering properly right now (HTTP $code).")
        else -> IOException("GitHub answered HTTP $code.")
    }

    private fun reason(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "No connection to GitHub. Check the TV's network."
        is java.net.SocketTimeoutException -> "GitHub didn't answer in time."
        is javax.net.ssl.SSLException -> "A secure connection to GitHub couldn't be made."
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun mb(b: Long) = "%.1f MB".format(Locale.US, b / 1_048_576.0)

    companion object {
        const val FLAG_AUTO_OFF = "updates_auto_off"
        const val KEY_DISMISSED = "updates_dismissed_code"
        const val KEY_LAST_CHECK = "updates_last_check"
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val SPACE_MARGIN = 30L * 1024 * 1024
        /** No personal data: just the app and version (GitHub asks API clients for a User-Agent). */
        val USER_AGENT = "AetherTV-updater/${BuildConfig.VERSION_NAME}"
    }
}
