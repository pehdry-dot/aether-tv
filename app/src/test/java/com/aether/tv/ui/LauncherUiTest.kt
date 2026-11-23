package com.aether.tv.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.aether.tv.TestEnv
import org.junit.After
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/** Shared harness: real LauncherApp, seeded data, D-pad helpers that pump the main looper. */
@OptIn(ExperimentalTestApi::class)
abstract class LauncherUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var env: TestEnv
    protected lateinit var vm: LauncherViewModel
    /** More installed apps for a test (the demo apps are always there). */
    protected open val extraApps: List<com.aether.tv.data.apps.InstalledApp> = emptyList()

    @Before fun setUpLauncher() {
        PagePrewarm.enabled = false // tests decide when pages are built
        PagePrewarm.builds.clear()
        env = TestEnv(com.aether.tv.DemoApps.installed() + extraApps)
        UiHarness.seed(env)
        vm = env.viewModel()
        vm.testStateTransform.value = UiHarness::transform
        rule.setContent { LauncherApp(vm) }
        settleUntil { vm.state.value.apps != null && vm.state.value.favoriteApps.isNotEmpty() }
        // First-run favorites are added one by one in the background; a short test could end (and close the
        // database) before the last one is written.
        settleUntil { kotlinx.coroutines.runBlocking { env.settings.flag("seeded_default") } }
        settleUntil { rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDownLauncher() {
        // Stop the launcher's collectors first: a settings change at the end of a test makes Room re-run its observed
        // queries, and those must not reach a database that is already closed.
        vm.viewModelScope.cancel()
        repeat(3) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        env.close()
    }

    /** Pumps the main looper and Compose clock until [cond] holds (background IO posts results to main). */
    protected fun settleUntil(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("Condition not met in ${timeoutMs}ms. Focused: ${focused()}")
    }

    /** Back goes through the Activity's dispatcher exactly like the remote's Back key; others go to Compose. */
    protected fun key(k: Key, times: Int = 1) = repeat(times) {
        if (k == Key.Back) rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        else rule.onRoot().performKeyInput { pressKey(k) }
        settleUntil { true }
    }

    /** Pause on the focused item like a person would (tabs switch pages only after focus rests, see TAB_SETTLE_MS). */
    protected fun rest() {
        rule.mainClock.advanceTimeBy(TAB_SETTLE_MS + 100)
        settleUntil { true }
    }

    protected fun focused() = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        .map { it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null } }

    /** Content description of the single focused node: where the next OK lands. Fails if focus is nowhere or split. */
    protected fun focusedLabel(): String {
        val f = focused()
        if (f.size != 1) throw AssertionError("Expected exactly one focused node, got $f")
        return f.single()?.joinToString().orEmpty()
    }

    /** Number of Back presses sent with [remote] in this test (the acceptance route must need none). */
    protected var backPresses = 0

    /**
     * A real remote key: KeyDown then KeyUp through Activity.dispatchKeyEvent, the path a key from the TV's input
     * system takes (Compose key handlers first; Back the Activity doesn't consume becomes onBackPressed).
     */
    protected fun remote(code: Int, times: Int = 1) = repeat(times) {
        if (code == android.view.KeyEvent.KEYCODE_BACK) backPresses++
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + 40, android.view.KeyEvent.ACTION_UP, code, 0))
        }
        settleUntil { true }
    }

    protected fun up(n: Int = 1) = remote(android.view.KeyEvent.KEYCODE_DPAD_UP, n)
    protected fun down(n: Int = 1) = remote(android.view.KeyEvent.KEYCODE_DPAD_DOWN, n)
    protected fun left(n: Int = 1) = remote(android.view.KeyEvent.KEYCODE_DPAD_LEFT, n)
    protected fun right(n: Int = 1) = remote(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, n)
    protected fun ok() = remote(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
    protected fun back() = remote(android.view.KeyEvent.KEYCODE_BACK)

    /** Package of the last app Aether asked Android to start, or null. */
    protected fun lastStartedPackage(): String? =
        org.robolectric.Shadows.shadowOf(env.context).nextStartedActivity?.let { it.component?.packageName ?: it.`package` }
}
