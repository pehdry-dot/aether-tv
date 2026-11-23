package com.aether.tv.data.apps

import com.aether.tv.core.model.Favorite
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.db.FavoriteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(private val db: AetherDatabase) {
    private val dao get() = db.favorites()

    fun observe(profileId: String): Flow<List<Favorite>> = dao.observe(profileId).map { list -> list.map { it.toModel() } }

    suspend fun isFavorite(profileId: String, type: FavoriteType, key: String) = dao.find(profileId, type.name, key) != null

    suspend fun add(profileId: String, type: FavoriteType, key: String, title: String? = null, artwork: String? = null, group: String? = null): Long {
        dao.find(profileId, type.name, key)?.let { return it.id }
        return dao.insert(FavoriteEntity(profileId = profileId, type = type.name, targetKey = key, title = title, artworkUri = artwork, groupName = group, sortOrder = dao.maxOrder(profileId) + 1))
    }

    suspend fun remove(profileId: String, type: FavoriteType, key: String) = dao.delete(profileId, type.name, key)
    suspend fun remove(id: Long) = dao.delete(id)

    /** Returns the new favorite state. */
    suspend fun toggle(profileId: String, type: FavoriteType, key: String): Boolean =
        if (isFavorite(profileId, type, key)) { remove(profileId, type, key); false } else { add(profileId, type, key); true }

    suspend fun edit(id: Long, title: String?, artwork: String?, group: String?) = dao.edit(id, title, artwork, group)

    /** Moves [id] by [delta] positions within the subset [scope] (e.g. only App favorites on Home). */
    suspend fun move(profileId: String, id: Long, delta: Int, scope: (FavoriteEntity) -> Boolean = { true }) {
        val all = dao.all(profileId)
        val subset = all.filter(scope)
        val idx = subset.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, subset.lastIndex)
        if (target == idx) return
        val reordered = subset.toMutableList().apply { add(target, removeAt(idx)) }
        // Re-interleave: subset members take the subset's original slots in the global order.
        val slots = all.withIndex().filter { scope(it.value) }.map { it.index }
        val global = all.toMutableList()
        slots.forEachIndexed { i, slot -> global[slot] = reordered[i] }
        dao.reorder(global.map { it.id })
    }

    suspend fun seedIfEmpty(profileId: String, keys: List<Pair<FavoriteType, String>>) {
        if (dao.all(profileId).isNotEmpty()) return
        keys.forEach { (t, k) -> add(profileId, t, k) }
    }

    private fun FavoriteEntity.toModel() = Favorite(
        id = id, type = runCatching { FavoriteType.valueOf(type) }.getOrDefault(FavoriteType.App),
        targetKey = targetKey, title = title, artworkUri = artworkUri, group = groupName, order = sortOrder,
    )
}
