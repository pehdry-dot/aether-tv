package com.aether.tv.data.store

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.aether.tv.platform.ApkInstaller
import com.aether.tv.platform.EventLog
import com.aether.tv.platform.InstallEvent
import com.aether.tv.platform.SystemShortcuts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** An app store Aether knows, described as it is (who makes it, what it is). */
data class StoreApp(val id: String, val label: String, val description: String, val packageName: String, val alternative: Intent? = null)

/** A store as found on this TV. [launch] is null when it isn't installed or can't open. */
data class StoreOption(val store: StoreApp, val installed: Boolean, val launch: Intent?)

/**
 * Apps ▸ Store. Like tv-detox's Play Store tile, a store is offered only when it can actually open on this TV (a TV or
 * phone launch entry, not just an installed package). Aurora Store, an unofficial Google Play client, can be installed
 * from its official release on GitLab; the download is checked against Aurora's own signing key before Android's
 * installer (and you) see it.
 */
@Singleton
class AppStores @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shortcuts: SystemShortcuts,
    private val installer: ApkInstaller,
) {
    fun options(): List<StoreOption> = KNOWN.map { s ->
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(s.packageName, 0); true }.getOrDefault(false)
        val launch = if (!installed) null else
            (pm.getLeanbackLaunchIntentForPackage(s.packageName) ?: pm.getLaunchIntentForPackage(s.packageName)
                ?: s.alternative?.takeIf { shortcuts.isUsable(it) })?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        StoreOption(s, installed, launch)
    }

    /** A web browser that can open a page on this TV (many Fire TVs have one; some have none). */
    fun browser(url: String): Intent? = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .takeIf { shortcuts.isUsable(it) }

    sealed interface Setup {
        data object Idle : Setup
        data class Working(val step: String, val done: Long = 0, val total: Long = 0) : Setup
        /** Waiting for "Install unknown apps" for Aether; continues when you come back. */
        data class NeedsPermission(val file: File) : Setup
        data object WaitingForYou : Setup
        data object Installed : Setup
        data class Failed(val message: String) : Setup
    }

    private val _setup = MutableStateFlow<Setup>(Setup.Idle)
    val setup: StateFlow<Setup> = _setup.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    internal var client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    internal var gitlabApi = "https://gitlab.com/api/v4/projects/$AURORA_PROJECT"
    internal var gitlabBase = "https://gitlab.com/-/project/$AURORA_PROJECT"

    init {
        scope.launch {
            installer.events.collect { e ->
                if (e.pkg != AURORA_PACKAGE) return@collect
                _setup.value = when (e) {
                    is InstallEvent.WaitingForYou -> Setup.WaitingForYou
                    is InstallEvent.Installed -> Setup.Installed.also { EventLog.log("Aurora Store installed") }
                    is InstallEvent.Cancelled -> Setup.Failed("Installation was cancelled.")
                    is InstallEvent.Failed -> Setup.Failed(e.reason)
                }
            }
        }
    }

    /** Downloads the latest official Aurora Store release from GitLab, checks it, and hands it to Android's installer. */
    fun installAurora() {
        if (job?.isActive == true) return
        job = scope.launch {
            _setup.value = runCatching { doInstallAurora() }.getOrElse { Setup.Failed(it.message ?: "The download failed.") }
        }
    }

    /** Continues after "Install unknown apps" was allowed. */
    fun onResume() {
        val s = _setup.value
        if (s is Setup.NeedsPermission && installer.canInstall()) scope.launch { _setup.value = Setup.Working("Opening Android's installer…"); installer.install(s.file, AURORA_PACKAGE) }
    }

    fun reset() { if (_setup.value !is Setup.Working) _setup.value = Setup.Idle }

    private suspend fun doInstallAurora(): Setup {
        _setup.value = Setup.Working("Finding the latest Aurora Store release…")
        val release = get("$gitlabApi/releases/permalink/latest")
        val path = AuroraRelease.apkPath(release) ?: throw IOException("The latest Aurora Store release has no APK link.")
        val dir = File(context.cacheDir, "store").apply { mkdirs() }
        val apk = File(dir, "aurora.apk")
        download("$gitlabBase$path", apk)
        _setup.value = Setup.Working("Checking the download…")
        val a = installer.readArchive(apk)
        val problem = AuroraRelease.problem(a.packageName, a.signerSha256)
        if (problem != null) { apk.delete(); throw IOException(problem) }
        if (!installer.canInstall()) return Setup.NeedsPermission(apk)
        installer.install(apk, AURORA_PACKAGE)
        return Setup.Working("Opening Android's installer…")
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "AetherTV").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitLab answered HTTP ${r.code}.")
            r.body?.string().orEmpty()
        }
    }

    private suspend fun download(url: String, out: File) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "AetherTV").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The download failed (HTTP ${r.code}).")
            val total = r.body?.contentLength() ?: -1
            out.outputStream().use { o ->
                r.body!!.byteStream().use { i ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var shown = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (done > MAX_APK) throw IOException("The download is unexpectedly large.")
                        if (done - shown > 256 * 1024) { shown = done; _setup.value = Setup.Working("Downloading Aurora Store…", done, total) }
                    }
                }
            }
        }
    }

    companion object {
        const val AURORA_PACKAGE = "com.aurora.store"
        const val AURORA_PROJECT = 6922885
        const val AURORA_PAGE = "https://auroraoss.com"
        const val AURORA_FDROID_PAGE = "https://f-droid.org/packages/com.aurora.store/"
        private const val MAX_APK = 64L * 1024 * 1024

        val KNOWN = listOf(
            StoreApp("play", "Google Play Store", "Google's store. Usually not available on Fire TV.", "com.android.vending"),
            StoreApp("aurora", "Aurora Store", "An unofficial client for Google Play's catalog, by AuroraOSS — not by Google. Anonymous sign-in works.", AURORA_PACKAGE),
            StoreApp("amazon", "Amazon Appstore", "Fire TV's own store.", "com.amazon.venezia",
                Intent("com.amazon.venezia.ade.FEATURED_LAUNCH").setComponent(ComponentName("com.amazon.venezia", "com.amazon.venezia.ade.ADEHomeActivity"))),
            StoreApp("fdroid", "F-Droid", "Free and open-source apps; few are made for TVs.", "org.fdroid.fdroid"),
        )
    }
}

/** Reading Aurora's official GitLab release and checking its APK. Pure (unit-tested). */
object AuroraRelease {
    /** Aurora Store's release signing certificate (SHA-256), as published by AuroraOSS. */
    const val SIGNER_SHA256 = "4c626157ad02bda3401a7263555f68a79663fc3e13a4d4369a12570941aa280f"

    /**
     * The upload path of the regular APK in a release (GitLab release JSON or its description), skipping the
     * Huawei ("-hw") and preload builds.
     */
    fun apkPath(json: String): String? = Regex("""/uploads/[0-9a-f]{32}/AuroraStore-[0-9.]+\.apk""").findAll(json)
        .map { it.value }.firstOrNull { !it.contains("-hw") && !it.contains("preload") }

    fun problem(pkg: String?, signers: List<String>): String? = when {
        pkg == null -> "The download isn't an Android app."
        pkg != AppStores.AURORA_PACKAGE -> "The download is $pkg, not Aurora Store."
        signers.none { it.equals(SIGNER_SHA256, ignoreCase = true) } -> "The download isn't signed by Aurora's developers; it wasn't installed."
        else -> null
    }
}
