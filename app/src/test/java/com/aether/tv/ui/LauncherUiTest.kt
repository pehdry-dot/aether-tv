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
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/** Shared harness: real LauncherApp, seeded data, D-pad helpers that pump the main looper. */
@OptIn(ExperimentalTestApi::class)
abstract class LauncherUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var env: TestEnv
    protected lateinit var vm: LauncherViewModel

    @Before fun setUpLauncher() {
        PagePrewarm.enabled = false // tests decide when pages are built
        PagePrewarm.builds.clear()
        env = TestEnv()
        UiHarness.seed(env)
        vm = env.viewModel()
        vm.testStateTransform.value = UiHarness::transform
        rule.setContent { LauncherApp(vm) }
        settleUntil { vm.state.value.apps != null && vm.state.value.favoriteApps.isNotEmpty() }
        settleUntil { rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDownLauncher() = env.close()

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
}
