package com.aether.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aether.tv.ui.home.rowFocus

/** How far rows extend past their slot so focus zoom and glow are never clipped by the scroll container. */
val RowBleed = 14.dp

/** Widens a scroll container by [bleed] on each side while keeping its visual start aligned to the grid. */
fun Modifier.bleedHorizontal(bleed: Dp = RowBleed): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2) else constraints
    val p = measurable.measure(wide)
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width - extra * 2
    layout(w, p.height) { p.place(-extra, 0) }
}

/** Horizontal TV row with focus restoration and edge bleed. [arrangement] overrides the plain [gap] spacing. */
@Composable
fun BleedRow(restore: Boolean, gap: Dp, modifier: Modifier = Modifier, arrangement: Arrangement.Horizontal? = null, content: LazyListScope.() -> Unit) {
    LazyRow(
        modifier.bleedHorizontal().rowFocus(restore),
        contentPadding = PaddingValues(horizontal = RowBleed),
        horizontalArrangement = arrangement ?: Arrangement.spacedBy(gap),
        content = content,
    )
}
