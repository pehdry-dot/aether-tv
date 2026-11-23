package com.aether.tv.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import com.aether.tv.core.designsystem.AppIconLoader
import com.aether.tv.core.model.AppInfo
import com.aether.tv.core.model.AppSort
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.GameKind
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.db.AppMetaEntity
import com.aether.tv.platform.AppLauncher
import com.aether.tv.platform.LaunchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A launchable activity as reported by PackageManager, before user metadata is applied. */
data class InstalledApp(
    val key: String,
    val packageName: String,
    val activityName: String,
    val label: String,
    val isTvApp: Boolean,
    val isSystem: Boolean,
    val gameKind: GameKind?,
    val installedAt: Long,
)

/** Source of installed apps; abstracted so tests can supply a fixed list. */
interface InstalledAppSource {
    fun query(): List<InstalledApp>
}

class PackageManagerAppSource @Inject constructor(@ApplicationContext private val context: Context) : InstalledAppSource {
    override fun query(): List<InstalledApp> {
        val pm = context.packageManager
        val own = context.packageName.removeSuffix(".debug")
        fun q(category: String) = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        }.getOrDefault(emptyList())
        val tv = q(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tvPackages = tv.map { it.activityInfo.packageName }.toSet()
        // Phone launcher entries are kept only for packages without a TV entry (avoids duplicates).
        val phone = q(Intent.CATEGORY_LAUNCHER).filter { it.activityInfo.packageName !in tvPackages }
        return (tv.map { it to true } + phone.map { it to false })
            .filter { (ri, _) -> !ri.activityInfo.packageName.startsWith(own) }
            .map { (ri, isTv) ->
                val ai = ri.activityInfo
                val appInfo = ai.applicationInfo
                val installed = runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L)
                InstalledApp(
                    key = "${ai.packageName}/${ai.name}",
                    packageName = ai.packageName,
                    activityName = ai.name,
                    label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName },
                    isTvApp = isTv,
                    isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 && appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                    gameKind = GameClassifier.classify(ai.packageName, appInfo),
                    installedAt = installed,
                )
            }
            .distinctBy { it.key }
    }
}

@Singleton
class AppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: InstalledAppSource,
    private val db: AetherDatabase,
    private val launcher: AppLauncher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _installed = MutableStateFlow<List<InstalledApp>?>(null)
    /** null until the first query completes. */
    val installed: StateFlow<List<InstalledApp>?> = _installed.asStateFlow()
    private val refreshLock = Mutex()
    private var callbackRegistered = false

    fun start() {
        refresh()
        if (callbackRegistered) return
        callbackRegistered = true
        runCatching {
            val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            la.registerCallback(object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String?, user: UserHandle?) = changed()
                override fun onPackageAdded(packageName: String?, user: UserHandle?) = changed()
                override fun onPackageChanged(packageName: String?, user: UserHandle?) = changed()
                override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed()
                override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed()
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun changed() { AppIconLoader.invalidate(); refresh() }

    fun refresh() {
        scope.launch { refreshLock.withLock { _installed.value = runCatching { source.query() }.getOrDefault(_installed.value ?: emptyList()) } }
    }

    suspend fun refreshNow() = refreshLock.withLock { _installed.value = source.query() }

    /** Installed apps merged with this profile's metadata and favorites. */
    fun apps(profileId: String): Flow<List<AppInfo>?> = combine(
        installed, db.appMeta().observe(profileId), db.favorites().observe(profileId),
    ) { installed, meta, favs ->
        installed ?: return@combine null
        val metaByKey = meta.associateBy { it.componentKey }
        val favKeys = favs.filter { it.type == FavoriteType.App.name }.map { it.targetKey }.toSet()
        installed.map { a -> merge(a, metaByKey[a.key], a.key in favKeys) }
    }

    fun merge(a: InstalledApp, m: AppMetaEntity?, favorite: Boolean): AppInfo {
        val gameKind = when (m?.gameOverride) { true -> a.gameKind ?: GameKind.Local; false -> null; null -> a.gameKind }
        return AppInfo(
            key = a.key, packageName = a.packageName, activityName = a.activityName, systemLabel = a.label,
            customLabel = m?.customLabel, isTvApp = a.isTvApp, isSystemApp = a.isSystem,
            isGame = gameKind != null, gameKind = gameKind, installedAt = a.installedAt,
            hidden = m?.hidden ?: false, favorite = favorite, category = m?.category, customIconUri = m?.customIconUri,
            launchCount = m?.launchCount ?: 0, lastLaunchedAt = m?.lastLaunchedAt ?: 0,
        )
    }

    suspend fun launch(profileId: String, app: AppInfo): LaunchResult {
        val r = launcher.launch(app.component, app.isTvApp)
        if (r is LaunchResult.Opened) recordLaunch(profileId, app.key)
        return r
    }

    suspend fun recordLaunch(profileId: String, key: String) {
        db.appMeta().update(profileId, key) { it.copy(launchCount = it.launchCount + 1, lastLaunchedAt = System.currentTimeMillis()) }
    }

    suspend fun setHidden(profileId: String, key: String, hidden: Boolean) = db.appMeta().update(profileId, key) { it.copy(hidden = hidden) }
    suspend fun rename(profileId: String, key: String, label: String?) = db.appMeta().update(profileId, key) { it.copy(customLabel = label?.trim()?.ifEmpty { null }) }
    suspend fun setIcon(profileId: String, key: String, uri: String?) { db.appMeta().update(profileId, key) { it.copy(customIconUri = uri) }; AppIconLoader.invalidate() }
    suspend fun setCategory(profileId: String, key: String, category: String?) = db.appMeta().update(profileId, key) { it.copy(category = category) }
    suspend fun setGameOverride(profileId: String, key: String, isGame: Boolean?) = db.appMeta().update(profileId, key) { it.copy(gameOverride = isGame) }

    fun isInstalled(pkg: String) = AppIconLoader.packageInstalled(context.packageManager, pkg)

    companion object {
        fun sort(apps: List<AppInfo>, sort: AppSort): List<AppInfo> = when (sort) {
            AppSort.NameAsc -> apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            AppSort.NameDesc -> apps.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.label })
            AppSort.Recent -> apps.sortedWith(compareByDescending<AppInfo> { it.lastLaunchedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            AppSort.MostUsed -> apps.sortedWith(compareByDescending<AppInfo> { it.launchCount }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            AppSort.Installed -> apps.sortedWith(compareByDescending<AppInfo> { it.installedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }

        /** Library filter honoring hidden apps and the non-TV-app toggle. */
        fun visible(apps: List<AppInfo>, showHidden: Boolean, showNonTv: Boolean) =
            apps.filter { (showHidden || !it.hidden) && (showNonTv || it.isTvApp) }

        /** Fuzzy-ish match used by search: prefix of any word ranks above substring. */
        fun matchScore(label: String, query: String): Int {
            val l = label.lowercase(); val q = query.trim().lowercase()
            if (q.isEmpty()) return 0
            return when {
                l == q -> 100
                l.startsWith(q) -> 80
                l.split(' ', '-', '.', '_').any { it.startsWith(q) } -> 60
                l.contains(q) -> 40
                q.length >= 3 && isSubsequence(q, l) -> 15
                else -> 0
            }
        }

        private fun isSubsequence(q: String, s: String): Boolean {
            var i = 0
            for (c in s) if (i < q.length && q[i] == c) i++
            return i == q.length
        }

        fun hasPermission(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }
}
