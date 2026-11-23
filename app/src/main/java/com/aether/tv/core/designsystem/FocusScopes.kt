package com.aether.tv.core.designsystem

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * A layer of the UI that can be shown or hidden while staying composed: a launcher page kept alive in the background,
 * the launcher underneath an open dialog, or a dialog underneath another dialog.
 *
 * Tiles inside an inactive layer can't take focus (see [GlassTile]): the remote can never end up on something that
 * isn't on screen, whatever asks for it (a delayed focus request, focus restoration, a D-pad search behind a dialog).
 * A focused tile whose layer becomes inactive loses focus at once.
 *
 * Provided through [LocalFocusLayer], a *static* local: tiles read the layer once and observe [isActive] only where
 * focus is decided, so hiding a page doesn't recompose it.
 */
@Stable
class FocusLayer(val name: String, private val parent: FocusLayer? = null) {
    private var self by mutableStateOf(true)

    /** This layer and every layer it sits in are shown. Reading it inside focusProperties is observed by Compose. */
    val isActive: Boolean get() = self && (parent?.isActive ?: true)

    fun setActive(active: Boolean) { if (self != active) self = active }

    override fun toString() = "FocusLayer($name${if (parent != null) " in ${parent.name}" else ""}, active=$isActive)"

    companion object { val Root = FocusLayer("root") }
}

val LocalFocusLayer = staticCompositionLocalOf { FocusLayer.Root }

/** Where focus was: a tile's requester and the layer it lives in. */
class FocusSpot(val requester: FocusRequester, val layer: FocusLayer)

/**
 * Remembers the most recently focused tile so focus can be put back exactly where it was after an overlay
 * (options menu, dialog) closes.
 */
object FocusMemory {
    @Volatile var lastSpot: FocusSpot? = null
    val last: FocusRequester? get() = lastSpot?.requester
}

/**
 * Moves focus to [this] only when its layer is on screen. Returns false (and does nothing) when the requester isn't
 * attached to a focusable tile, or that tile is in a hidden page or under a dialog.
 */
fun FocusSpot.tryFocus(): Boolean = layer.isActive && requester.tryFocus()

/** [FocusRequester.requestFocus] that reports failure instead of throwing when nothing is attached. */
fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

/**
 * D-pad entry into this group lands on [target] (when it returns one). Unlike focusRestorer, a *programmatic* request
 * for a particular child (a deep link, focus going back to what opened a dialog) is left alone: focusRestorer redirects
 * those too, which sent focus to the wrong control after dialogs and deep links.
 */
fun Modifier.dpadEntry(target: () -> FocusRequester?): Modifier = focusProperties {
    onEnter = {
        val d = requestedFocusDirection
        if (d == FocusDirection.Up || d == FocusDirection.Down || d == FocusDirection.Left || d == FocusDirection.Right) {
            target()?.let { t -> runCatching { t.requestFocus() } }
        }
    }
}
