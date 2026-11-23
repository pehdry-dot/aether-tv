package com.aether.tv.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.aether.tv.data.update.Installed
import com.aether.tv.data.update.UpdateRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** What Android's installer said about a session. */
sealed interface InstallEvent {
    /** Package the session installs (Aether itself, or a store the user asked for). */
    val pkg: String?
    /** The confirmation screen is up (approve it with the remote). */
    data class WaitingForYou(override val pkg: String?) : InstallEvent
    data class Installed(override val pkg: String?) : InstallEvent
    /** Back or Cancel on the confirmation screen. */
    data class Cancelled(override val pkg: String?) : InstallEvent
    data class Failed(override val pkg: String?, val reason: String) : InstallEvent
}

/**
 * Hands a verified APK to Android's own package installer (a PackageInstaller session; the system shows its usual
 * confirmation, which the remote can approve). Also reads what's installed and what a downloaded APK contains.
 * Never uninstalls anything and never installs a package other than Aether itself.
 */
@Singleton
class ApkInstaller @Inject constructor(@ApplicationContext private val context: Context) {
    private val pm get() = context.packageManager
    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> = _events

    fun installed(): Installed {
        val info = packageInfo(context.packageName)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0 else info?.versionCode?.toLong() ?: 0
        return Installed(
            applicationId = context.packageName, versionCode = code, versionName = info?.versionName.orEmpty(),
            sdk = Build.VERSION.SDK_INT, supportedAbis = Build.SUPPORTED_ABIS.toList(), signerSha256 = signers(info),
        )
    }

    private fun packageInfo(pkg: String): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
    }.getOrNull()

    /** SHA-256 of the current signing certificate(s); with key rotation only the newest certificate counts. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.let { si ->
            if (si.hasMultipleSigners()) si.apkContentsSigners?.toList() else listOfNotNull(si.signingCertificateHistory?.lastOrNull())
        } else info?.signatures?.toList()
        return sigs.orEmpty().map { sha256(it.toByteArray()) }
    }

    /** Reads a downloaded APK without installing it. */
    suspend fun readArchive(file: File): UpdateRules.Archive = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            else pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        }.getOrNull()
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0 else info?.versionCode?.toLong() ?: 0
        UpdateRules.Archive(info?.packageName, code, signers(info), fileSha256(file), file.length())
    }

    /** Android 8+ asks the user to allow each app that installs others ("Install unknown apps"). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || runCatching { pm.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * The screen where "Install unknown apps" is allowed for Aether, if this TV has one that opens. On Fire OS it may
     * open Developer options or the device page instead of Aether's own switch; the UI explains the path either way.
     */
    fun unknownSourcesIntent(shortcuts: SystemShortcuts): Intent? = listOfNotNull(
        if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")) else null,
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
    ).firstOrNull { shortcuts.isUsable(it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Installs [apk] as an update of Aether through a PackageInstaller session. Progress and the outcome arrive on
     * [events]. The confirmation screen is started for the user (Fire OS shows it ~1–6 s later).
     */
    suspend fun install(apk: File, packageName: String = context.packageName): Unit = withContext(Dispatchers.IO) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("aether.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                registerReceiver()
                val intent = Intent(ACTION_RESULT).setPackage(context.packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra(EXTRA_TARGET, packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            _events.tryEmit(InstallEvent.Failed(packageName, e.message ?: "Android's installer refused the file."))
        }
    }

    @Volatile private var receiver: BroadcastReceiver? = null

    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val pkg = i.getStringExtra(EXTRA_TARGET) ?: i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)
                        if (confirm != null) runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { _events.tryEmit(InstallEvent.Failed(pkg, "The installer screen couldn't be opened.")) }
                        _events.tryEmit(InstallEvent.WaitingForYou(pkg))
                    }
                    PackageInstaller.STATUS_SUCCESS -> _events.tryEmit(InstallEvent.Installed(pkg))
                    PackageInstaller.STATUS_FAILURE_ABORTED -> _events.tryEmit(InstallEvent.Cancelled(pkg))
                    else -> _events.tryEmit(InstallEvent.Failed(pkg, explain(status, msg)))
                }
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun explain(status: Int, msg: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android refused it: it conflicts with the installed Aether (usually a different signing key)."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Android refused it as incompatible with this TV."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough free space to install it."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Android says the file is invalid."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation is blocked on this TV."
        else -> msg ?: "Installation failed (status $status)."
    }

    companion object {
        const val ACTION_RESULT = "com.aether.tv.action.INSTALL_RESULT"
        private const val EXTRA_TARGET = "com.aether.tv.extra.TARGET_PACKAGE"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun fileSha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
