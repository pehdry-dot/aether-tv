package com.aether.tv.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.AppIcon
import com.aether.tv.core.designsystem.Chip
import com.aether.tv.core.designsystem.EmptyState
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.model.AppInfo
import com.aether.tv.core.model.AppSort
import com.aether.tv.core.model.LibraryView
import com.aether.tv.data.apps.AppRepository
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.AppTile
import com.aether.tv.ui.common.TvTextField
import com.aether.tv.ui.common.bleedHorizontal
import com.aether.tv.ui.home.rowFocus

/** Library filter. Categories are dynamic, so filters are identified by label. */
sealed interface AppFilter {
    val label: String
    data object All : AppFilter { override val label = "All apps" }
    data object Favorites : AppFilter { override val label = "Favorites" }
    data object Games : AppFilter { override val label = "Games" }
    data object TvApps : AppFilter { override val label = "TV apps" }
    data object Hidden : AppFilter { override val label = "Hidden" }
    data class Category(val name: String) : AppFilter { override val label = name }
}

fun filterApps(apps: List<AppInfo>, filter: AppFilter, query: String, showHidden: Boolean, showNonTv: Boolean, sort: AppSort): List<AppInfo> {
    val base = when (filter) {
        AppFilter.Hidden -> apps.filter { it.hidden }
        else -> AppRepository.visible(apps, showHidden, showNonTv)
    }
    val filtered = when (filter) {
        AppFilter.All, AppFilter.Hidden -> base
        AppFilter.Favorites -> base.filter { it.favorite }
        AppFilter.Games -> base.filter { it.isGame }
        AppFilter.TvApps -> base.filter { it.isTvApp }
        is AppFilter.Category -> base.filter { it.category == filter.name }
    }
    return if (query.isBlank()) AppRepository.sort(filtered, sort)
    else filtered.map { it to AppRepository.matchScore(it.label, query) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<AppInfo, Int>> { it.second }.thenBy { it.first.label.lowercase() }).map { it.first }
}

@Composable
fun AppsPage(state: LauncherUiState, vm: LauncherViewModel) {
    val lib = state.settings.library
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf<AppFilter>(AppFilter.All) }
    val all = state.apps
    val apps = remember(all, filter, query, lib) { filterApps(all.orEmpty(), filter, query, lib.showHidden, lib.showNonTvApps, lib.sort) }
    val installedCount = all?.count { !it.hidden } ?: 0
    val restore = state.settings.behavior.restoreFocus

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AText(filter.label, Aether.type.panelTitle)
            Spacer(Modifier.width(14.dp))
            AText(if (all == null) "Loading…" else "$installedCount installed", Aether.type.caption, color = Aether.colors.textSecondary)
            Spacer(Modifier.weight(1f))
            TvTextField(query, { query = it }, "Search apps…", Modifier.width(210.dp), icon = AetherIcons.Search)
            Spacer(Modifier.width(10.dp))
            PillButton(filter.label, AetherIcons.SlidersHorizontal) {
                val filters = buildList {
                    add(AppFilter.All); add(AppFilter.Favorites); add(AppFilter.Games); add(AppFilter.TvApps)
                    state.categories.forEach { add(AppFilter.Category(it)) }
                    add(AppFilter.Hidden)
                }
                vm.show(Overlay.Choice("Show", filters.map { it.label }, filters.indexOf(filter)) { filter = filters[it] })
            }
            Spacer(Modifier.width(10.dp))
            PillButton("Store", AetherIcons.Download, chevron = false) { vm.show(Overlay.StorePicker) }
            Spacer(Modifier.width(10.dp))
            PillButton(lib.sort.label, AetherIcons.List) {
                vm.show(Overlay.Choice("Sort apps", AppSort.entries.map { it.label }, lib.sort.ordinal) { i ->
                    vm.updateSettings { it.copy(library = it.library.copy(sort = AppSort.entries[i])) }
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            all == null -> Unit
            apps.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 30.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (query.isNotBlank()) "No apps match “$query”" else "Nothing here",
                    if (filter == AppFilter.Hidden) "Hidden apps appear here. Long-press an app ▸ Hide to add one." else "Try a different filter.",
                    AetherIcons.AppsGrid,
                )
            }
            lib.view == LibraryView.Grid -> LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp, start = com.aether.tv.ui.common.RowBleed, end = com.aether.tv.ui.common.RowBleed),
                modifier = Modifier.fillMaxSize().bleedHorizontal().rowFocus(restore),
            ) {
                items(apps, key = { it.key }) { app ->
                    AppTile(app, { vm.launchApp(app) }, { vm.onAppLongPress(app, null) }, height = 66.dp, banner = lib.useBanners)
                }
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp, top = 6.dp, start = com.aether.tv.ui.common.RowBleed, end = com.aether.tv.ui.common.RowBleed),
                modifier = Modifier.fillMaxSize().bleedHorizontal().rowFocus(restore),
            ) {
                items(apps, key = { it.key }) { app -> AppListRow(app, { vm.launchApp(app) }, { vm.onAppLongPress(app, null) }) }
            }
        }
    }
}

@Composable
fun PillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, chevron: Boolean = true, onClick: () -> Unit) {
    GlassTile(onClick = onClick, shape = Aether.shapes.pill, scaleOnFocus = false, modifier = Modifier.semantics { contentDescription = label }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            AIcon(icon, size = 16.dp, tint = Aether.colors.textSecondary)
            Spacer(Modifier.width(9.dp))
            AText(label, Aether.type.body)
            if (chevron) { Spacer(Modifier.width(8.dp)); AIcon(AetherIcons.ChevronDown, size = 14.dp, tint = Aether.colors.textSecondary) }
        }
    }
}

@Composable
private fun AppListRow(app: AppInfo, onClick: () -> Unit, onLong: () -> Unit) {
    GlassTile(onClick = onClick, onLongClick = onLong, scaleOnFocus = false, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, 32.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AText(app.label, Aether.type.bodyStrong)
                AText(app.packageName, Aether.type.caption, color = Aether.colors.textTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (app.favorite) Chip("Favorite")
                app.gameKind?.let { Chip(it.label) }
                app.category?.let { Chip(it) }
                if (!app.isTvApp) Chip("Phone app")
                if (app.hidden) Chip("Hidden")
            }
        }
    }
}
