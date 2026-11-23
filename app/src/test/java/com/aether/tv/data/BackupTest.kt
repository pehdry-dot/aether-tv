package com.aether.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.TestEnv
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.data.assets.AssetKind
import com.aether.tv.data.backup.BackupException
import com.aether.tv.data.db.AppMetaEntity
import com.aether.tv.data.db.DeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Backups carry pictures, are checked before anything is replaced, and a restore can't be left half done. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private fun picture(kind: AssetKind, name: String, seed: Int): File =
        File(env.assets.dir(kind), name).apply { writeBytes(ByteArray(4000) { ((it * seed) % 256).toByte() }) }

    /** A TV with a custom wallpaper, an icon override, a device photo and favorite artwork. */
    private fun populated(): Map<String, ByteArray> = runBlocking {
        env.profiles.ensureDefault()
        val wp = picture(AssetKind.Wallpaper, "wp-1.img", 3)
        val icon = picture(AssetKind.Icon, "demo.aurora-1.img", 5)
        val photo = picture(AssetKind.Device, "device-1.img", 7)
        val art = picture(AssetKind.Art, "fav-1.img", 11)
        env.settings.update("default") { it.copy(appearance = it.appearance.copy(wallpaper = "file:${wp.path}")) }
        env.db.appMeta().upsert(AppMetaEntity("default", "demo.aurora/demo.aurora.Main", customIconUri = "file:${icon.path}"))
        env.db.devices().upsert(DeviceEntity("xbox", "Xbox Series X", "Xbox", inputId = "hdmi/HW2", hdmiPort = 1, artworkUri = "file:${photo.path}"))
        env.favorites.add("default", FavoriteType.Media, "com.nuvio.tv|nuvio://x", "Film", "file:${art.path}")
        mapOf("wp" to wp.readBytes(), "icon" to icon.readBytes(), "photo" to photo.readBytes(), "art" to art.readBytes())
    }

    @Test fun roundTripOntoAFreshInstallationKeepsPictures() = runBlocking {
        val bytes = populated()
        val zip = env.backup.exportToFolder()
        assertTrue(zip.name.endsWith(".zip"))
        assertEquals(5, ZipFile(zip).size()) // backup.json + 4 pictures

        // Uninstall: the backup is kept elsewhere (adb pull / USB), Aether's files are gone.
        val kept = zip.copyTo(File(env.context.cacheDir, "kept.zip"), overwrite = true)
        AssetKind.entries.forEach { env.assets.dir(it).deleteRecursively() }
        zip.parentFile!!.deleteRecursively()
        val fresh = TestEnv() // another, empty installation
        try {
            val report = fresh.backup.restore(kept)
            assertEquals(4, report.pictures)
            assertTrue(report.missingPictures.isEmpty())
            val wp = fresh.settings.get("default").appearance.wallpaper
            assertArrayEquals(bytes["wp"], File(wp.removePrefix("file:")).readBytes())
            assertTrue("restored pictures are the new installation's own files", fresh.assets.isPrivate(File(wp.removePrefix("file:"))))
            assertArrayEquals(bytes["icon"], File(fresh.db.appMeta().all("default").single().customIconUri!!.removePrefix("file:")).readBytes())
            assertArrayEquals(bytes["photo"], File(fresh.db.devices().get("xbox")!!.artworkUri!!.removePrefix("file:")).readBytes())
            assertArrayEquals(bytes["art"], File(fresh.db.favorites().all("default").single().artworkUri!!.removePrefix("file:")).readBytes())
        } finally { fresh.close() }
    }

    @Test fun aDamagedBackupChangesNothing() = runBlocking {
        populated()
        val good = env.backup.exportToFolder()
        val before = env.settings.get("default")
        fun expectRefused(f: File, why: String) {
            try { runBlocking { env.backup.restore(f) }; fail("$why was accepted") } catch (e: BackupException) { }
            assertEquals(before, runBlocking { env.settings.get("default") })
            assertEquals(1, runBlocking { env.db.devices().all() }.size)
        }
        // Truncated archive.
        expectRefused(File(good.parentFile, "cut.zip").apply { writeBytes(good.readBytes().copyOf(good.length().toInt() / 2)) }, "a truncated backup")
        // A picture that doesn't match its checksum.
        val tampered = File(good.parentFile, "tampered.zip")
        ZipFile(good).use { src -> ZipOutputStream(tampered.outputStream()).use { out ->
            src.entries().toList().forEach { e ->
                out.putNextEntry(ZipEntry(e.name))
                val data = src.getInputStream(e).readBytes()
                out.write(if (e.name.startsWith("assets/") && e.name.contains("wp")) data.reversedArray() else data)
                out.closeEntry()
            }
        } }
        expectRefused(tampered, "a tampered picture")
        // Not a backup at all.
        expectRefused(File(good.parentFile, "x.json").apply { writeText("{\"hello\":1}") }, "a random JSON file")
    }

    @Test fun anInterruptedRestoreIsFinishedOnTheNextStart() = runBlocking {
        val bytes = populated()
        val zip = env.backup.exportToFolder()
        val fresh = TestEnv()
        try {
            // What restore() leaves behind if the TV loses power right after taking its private copy.
            val dir = File(fresh.context.filesDir, "restore").apply { mkdirs() }
            zip.copyTo(File(dir, "pending.zip"), overwrite = true)
            File(dir, "journal").writeText("restore")
            val report = fresh.backup.finishInterruptedRestore()!!
            assertTrue(report.recovered)
            assertArrayEquals(bytes["wp"], File(fresh.settings.get("default").appearance.wallpaper.removePrefix("file:")).readBytes())
            assertEquals(null, fresh.backup.finishInterruptedRestore()) // done once
        } finally { fresh.close() }
    }

    @Test fun aVersion110BackupStillRestores() = runBlocking {
        env.profiles.ensureDefault()
        val folder = File(env.assets.tvFolder(AssetKind.Wallpaper), "beach.jpg").apply { writeBytes(ByteArray(100) { 1 }) }
        // Format 1: JSON only; one wallpaper in the TV folder (still there), one icon that was in private storage (gone).
        val json = """{"format":1,"createdAt":1,"appVersion":"1.1.0","profiles":[{"id":"default","name":"Main","colorArgb":4290768895}],
            "settings":{"default":{"appearance":{"wallpaper":"file:${folder.path}"},"layout":{"defaultTab":"Settings"}}},
            "favorites":[],"appMeta":[{"profileId":"default","componentKey":"demo.aurora/demo.aurora.Main","customIconUri":"file:/data/user/0/com.aether.tv/files/icons/gone.img"}],
            "categories":[],"devices":[{"id":"xbox-series-x","name":"Xbox Series X","kind":"Xbox","inputId":"hdmi/HW2","hdmiPort":1,"artworkUri":"builtin:xbox_series_x"}],"games":[]}"""
        val r = env.backup.restore(json)
        assertEquals(listOf("gone.img"), r.missingPictures)
        assertEquals("file:${folder.path}", env.settings.get("default").appearance.wallpaper)
        assertEquals(null, env.db.appMeta().all("default").single().customIconUri)
        assertEquals("builtin:xbox_series_x", env.db.devices().get("xbox-series-x")!!.artworkUri)
        assertTrue(folder.exists()) // the user's original is never touched
    }

    @Test fun undoPutsBackTheDataFromBeforeTheRestore() = runBlocking {
        populated()
        val mine = env.settings.get("default").appearance.wallpaper
        val other = """{"format":2,"createdAt":1,"appVersion":"1.2.0","profiles":[{"id":"default","name":"Other","colorArgb":1}],"settings":{"default":{"appearance":{"wallpaper":"builtin:night"}}},"favorites":[],"appMeta":[],"categories":[],"devices":[],"games":[]}"""
        env.backup.restore(other)
        assertEquals("builtin:night", env.settings.get("default").appearance.wallpaper)
        assertTrue(env.backup.preRestoreBackup.exists())
        env.backup.restore(env.backup.preRestoreBackup.copyTo(File(env.context.cacheDir, "undo.zip"), overwrite = true))
        val back = env.settings.get("default").appearance.wallpaper
        assertNotEquals("builtin:night", back)
        assertTrue(File(back.removePrefix("file:")).readBytes().contentEquals(File(mine.removePrefix("file:")).let { if (it.exists()) it.readBytes() else File(back.removePrefix("file:")).readBytes() }))
        assertEquals(1, env.db.devices().all().size)
    }

    @Test fun cleanupNeverDeletesWhatAnotherProfileUses() = runBlocking {
        env.profiles.ensureDefault()
        val kids = env.profiles.create("Kids", 1, copySettingsFrom = "default")
        val a = picture(AssetKind.Wallpaper, "a.img", 2); val b = picture(AssetKind.Wallpaper, "b.img", 3); val orphan = picture(AssetKind.Wallpaper, "c.img", 4)
        val original = File(env.assets.tvFolder(AssetKind.Wallpaper), "mine.jpg").apply { writeBytes(ByteArray(10)) }
        env.settings.update("default") { it.copy(appearance = it.appearance.copy(wallpaper = "file:${a.path}")) }
        env.settings.update(kids.id) { it.copy(appearance = it.appearance.copy(wallpaper = "file:${b.path}")) }
        env.assets.deleteUnused()
        assertTrue(a.exists()); assertTrue("used by another profile", b.exists())
        assertTrue("user's original in the TV folder", original.exists())
        assertTrue("nothing uses it", !orphan.exists())
    }
}
