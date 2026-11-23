package com.aether.tv.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.ui.test.hasContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.data.assets.AssetKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Settings ▸ TV & System ▸ Storage and Apps ▸ Store, with the remote. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class StorageAndStoreTest : LauncherUiTest() {
    private fun has(desc: String) = rule.onAllNodes(hasContentDescription(desc, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test fun storageMeasuresAndDeletesOnlyWhatYouPick() {
        val old = File(env.backup.backupDir, "aether-backup-20260101-000000.zip").apply { writeBytes(ByteArray(2048)) }
        val keep = File(env.backup.backupDir, "aether-backup-20260202-000000.zip").apply { writeBytes(ByteArray(1024)) }
        val unused = File(env.assets.dir(AssetKind.Wallpaper), "unused.img").apply { writeBytes(ByteArray(5000)) }
        val used = File(env.assets.dir(AssetKind.Wallpaper), "used.img").apply { writeBytes(ByteArray(5000)) }
        runBlocking { env.settings.update("default") { it.copy(appearance = it.appearance.copy(wallpaper = "file:${used.path}")) } }
        // TV & System ▸ Storage with the remote.
        vm.openSettings("system")
        settleUntil { focused().singleOrNull()?.toString()?.contains("Storage") == true }
        ok()
        settleUntil { has("Artwork cache") }
        down(1) // Wallpapers: 1 unused
        assertEquals("Wallpapers", focusedLabel())
        ok(); settleUntil { vm.overlay.value is Overlay.Confirm }
        right(); ok() // "Remove" (Cancel is focused first)
        settleUntil { !unused.exists() }
        assertTrue("in use by a profile", used.exists())
        settleUntil { has("Wallpapers") && vm.overlay.value is Overlay.Storage }
        // Delete one backup.
        repeat(12) { if (focused().singleOrNull()?.toString()?.contains("aether-backup") != true) down() }
        val target = focusedLabel()
        ok(); settleUntil { vm.overlay.value is Overlay.Confirm }
        right(); ok()
        settleUntil { !(if (target.contains("20260202")) keep else old).exists() }
        assertTrue("only the chosen backup", (if (target.contains("20260202")) old else keep).exists())
        settleUntil { !has(target) } // the numbers are measured again after each removal
    }

    @Test fun storeListsOnlyStoresThatOpenAndOffersAurora() {
        // An Amazon Appstore with a TV launch entry; no Google Play, no Aurora.
        val pm = shadowOf(env.context.packageManager)
        val pkg = "com.amazon.venezia"
        pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg; name = "Appstore" } })
        val cn = ComponentName(pkg, "$pkg.Main")
        pm.addActivityIfNotPresent(cn)
        pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) })
        val options = vm.stores.options()
        assertTrue(options.first { it.store.packageName == pkg }.launch != null)
        assertFalse(options.first { it.store.packageName == "com.android.vending" }.installed)
        // Apps ▸ Store with the remote.
        up(); right(); rest()
        down()
        repeat(4) { if (focused().singleOrNull()?.toString()?.contains("Store") != true) right() }
        assertEquals("Store", focusedLabel())
        ok()
        settleUntil { vm.overlay.value is Overlay.StorePicker }
        assertEquals("Amazon Appstore", focusedLabel())
        assertTrue(has("Download and install Aurora Store"))
        assertFalse(has("Google Play"))
    }
}
