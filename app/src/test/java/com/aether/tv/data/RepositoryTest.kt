package com.aether.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.TestEnv
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.data.devices.DeviceRepository
import com.aether.tv.data.profiles.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoryTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    @Test fun favoritesAddToggleAndScopedMove() = runBlocking {
        val p = "default"
        val f = env.favorites
        f.add(p, FavoriteType.App, "a"); f.add(p, FavoriteType.Device, "dev"); f.add(p, FavoriteType.App, "b"); f.add(p, FavoriteType.App, "c")
        f.add(p, FavoriteType.App, "a") // duplicate ignored
        assertEquals(listOf("a", "dev", "b", "c"), f.observe(p).first().map { it.targetKey })
        val c = f.observe(p).first().first { it.targetKey == "c" }
        // Moving "c" left among apps only skips over the device favorite's slot.
        f.move(p, c.id, -2) { it.type == FavoriteType.App.name }
        assertEquals(listOf("c", "dev", "a", "b"), f.observe(p).first().map { it.targetKey })
        assertFalse(f.toggle(p, FavoriteType.App, "a"))
        assertTrue(f.toggle(p, FavoriteType.App, "a"))
        assertEquals(4, f.observe(p).first().size)
    }

    @Test fun appMetadataMergesIntoInstalledApps() = runBlocking {
        env.appRepo.refreshNow()
        val key = env.apps.first().key
        env.appRepo.rename("default", key, "Renamed")
        env.appRepo.setHidden("default", key, true)
        env.appRepo.recordLaunch("default", key)
        env.favorites.add("default", FavoriteType.App, key)
        val app = env.appRepo.apps("default").first { it != null }!!.first { it.key == key }
        assertEquals("Renamed", app.label); assertTrue(app.hidden); assertTrue(app.favorite); assertEquals(1, app.launchCount)
        // Metadata is per profile.
        val other = env.appRepo.apps("other").first { it != null }!!.first { it.key == key }
        assertFalse(other.hidden); assertFalse(other.favorite)
    }

    @Test fun gameOverrideWins() = runBlocking {
        env.appRepo.refreshNow()
        val notGame = env.apps.first { it.gameKind == null }.key
        env.appRepo.setGameOverride("default", notGame, true)
        assertTrue(env.appRepo.apps("default").first { it != null }!!.first { it.key == notGame }.isGame)
        env.appRepo.setGameOverride("default", notGame, false)
        assertFalse(env.appRepo.apps("default").first { it != null }!!.first { it.key == notGame }.isGame)
    }

    @Test fun profilesPinAndSettingsIsolation() = runBlocking {
        env.profiles.ensureDefault()
        val kid = env.profiles.create("Kids", 0xFF00FF00, copySettingsFrom = "default")
        env.profiles.setPin(kid.id, "1234")
        assertFalse(env.profiles.switchTo(kid.id, "0000"))
        assertTrue(env.profiles.switchTo(kid.id, "1234"))
        assertEquals(kid.id, env.profiles.activeId())
        env.settings.update(kid.id) { it.copy(appearance = it.appearance.copy(cornerRadiusDp = 4)) }
        assertEquals(4, env.settings.get(kid.id).appearance.cornerRadiusDp)
        assertEquals(14, env.settings.get("default").appearance.cornerRadiusDp)
        env.profiles.delete(kid.id)
        assertEquals(ProfileRepository.DEFAULT_ID, env.profiles.activeId())
    }

    @Test fun pinHashIsSaltedAndVerifies() {
        val a = ProfileRepository.hashPin("2468"); val b = ProfileRepository.hashPin("2468")
        assertTrue(a != b)
        assertTrue(ProfileRepository.verifyPin("2468", a)); assertFalse(ProfileRepository.verifyPin("2469", a))
    }

    @Test fun backupRoundTripRestoresEverything() = runBlocking {
        env.profiles.ensureDefault()
        env.devices.seedDefaults()
        env.favorites.add("default", FavoriteType.App, "x/y")
        env.settings.update("default") { it.copy(appearance = it.appearance.copy(glassOpacity = 0.3f)) }
        val json = env.backup.encode(env.backup.build())
        env.db.clearAllTables()
        env.settings.replace("default", com.aether.tv.core.model.LauncherSettings())
        env.backup.restore(json)
        assertEquals(listOf("x/y"), env.favorites.observe("default").first().map { it.targetKey })
        assertNotNull(env.devices.get(DeviceRepository.XBOX_ID))
        assertEquals(0.3f, env.settings.get("default").appearance.glassOpacity, 0.001f)
        assertFalse(json.contains("token", ignoreCase = true))
    }

    @Test fun xboxSeedIsAnUnconfiguredShortcut() = runBlocking {
        env.devices.seedDefaults()
        val x = env.devices.get(DeviceRepository.XBOX_ID)!!
        assertEquals(null, x.inputId)
        val resolved = DeviceRepository.resolve(listOf(x), emptyList(), com.aether.tv.platform.PeripheralSnapshot(false, false, false, emptyList(), emptyList()), false)
        assertEquals(com.aether.tv.core.model.DeviceStatus.Shortcut, resolved.single().status)
    }
}
