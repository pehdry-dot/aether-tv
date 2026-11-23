package com.aether.tv.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt") fun observeAll(): Flow<List<ProfileEntity>>
    @Query("SELECT * FROM profiles ORDER BY createdAt") suspend fun all(): List<ProfileEntity>
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun get(id: String): ProfileEntity?
    @Upsert suspend fun upsert(p: ProfileEntity)
    @Query("DELETE FROM profiles WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface AppMetaDao {
    @Query("SELECT * FROM app_meta WHERE profileId = :profileId") fun observe(profileId: String): Flow<List<AppMetaEntity>>
    @Query("SELECT * FROM app_meta WHERE profileId = :profileId") suspend fun all(profileId: String): List<AppMetaEntity>
    @Query("SELECT * FROM app_meta") suspend fun allRows(): List<AppMetaEntity>
    @Query("SELECT * FROM app_meta WHERE profileId = :profileId AND componentKey = :key") suspend fun get(profileId: String, key: String): AppMetaEntity?
    @Upsert suspend fun upsert(m: AppMetaEntity)
    @Upsert suspend fun upsertAll(m: List<AppMetaEntity>)
    @Query("DELETE FROM app_meta WHERE profileId = :profileId") suspend fun clear(profileId: String)

    @Transaction
    suspend fun update(profileId: String, key: String, block: (AppMetaEntity) -> AppMetaEntity) {
        val current = get(profileId, key) ?: AppMetaEntity(profileId, key)
        upsert(block(current))
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY sortOrder, id") fun observe(profileId: String): Flow<List<FavoriteEntity>>
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY sortOrder, id") suspend fun all(profileId: String): List<FavoriteEntity>
    @Query("SELECT * FROM favorites") suspend fun allRows(): List<FavoriteEntity>
    @Query("SELECT * FROM favorites WHERE profileId = :profileId AND type = :type AND targetKey = :key") suspend fun find(profileId: String, type: String, key: String): FavoriteEntity?
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM favorites WHERE profileId = :profileId") suspend fun maxOrder(profileId: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(f: FavoriteEntity): Long
    @Upsert suspend fun upsertAll(f: List<FavoriteEntity>)
    @Query("UPDATE favorites SET sortOrder = :order WHERE id = :id") suspend fun setOrder(id: Long, order: Int)
    @Query("DELETE FROM favorites WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM favorites WHERE profileId = :profileId AND type = :type AND targetKey = :key") suspend fun delete(profileId: String, type: String, key: String)
    @Query("DELETE FROM favorites WHERE profileId = :profileId") suspend fun clear(profileId: String)
    @Query("UPDATE favorites SET title = :title, artworkUri = :art, groupName = :group WHERE id = :id") suspend fun edit(id: Long, title: String?, art: String?, group: String?)

    @Transaction
    suspend fun reorder(ids: List<Long>) { ids.forEachIndexed { i, id -> setOrder(id, i) } }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE profileId = :profileId ORDER BY sortOrder, name") fun observe(profileId: String): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE profileId = :profileId ORDER BY sortOrder, name") suspend fun all(profileId: String): List<CategoryEntity>
    @Upsert suspend fun upsert(c: CategoryEntity)
    @Delete suspend fun delete(c: CategoryEntity)
    @Query("UPDATE app_meta SET category = NULL WHERE profileId = :profileId AND category = :name") suspend fun unassign(profileId: String, name: String)
    @Query("DELETE FROM categories WHERE profileId = :profileId") suspend fun clear(profileId: String)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY sortOrder, name") fun observe(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM devices ORDER BY sortOrder, name") suspend fun all(): List<DeviceEntity>
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun get(id: String): DeviceEntity?
    @Upsert suspend fun upsert(d: DeviceEntity)
    @Query("DELETE FROM devices WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM devices") suspend fun clear()
}

@Dao
interface GameShortcutDao {
    @Query("SELECT * FROM game_shortcuts WHERE profileId = :profileId ORDER BY sortOrder, title") fun observe(profileId: String): Flow<List<GameShortcutEntity>>
    @Query("SELECT * FROM game_shortcuts WHERE profileId = :profileId") suspend fun all(profileId: String): List<GameShortcutEntity>
    @Query("SELECT * FROM game_shortcuts") suspend fun allRows(): List<GameShortcutEntity>
    @Upsert suspend fun upsert(g: GameShortcutEntity)
    @Query("DELETE FROM game_shortcuts WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE game_shortcuts SET lastPlayedAt = :t WHERE id = :id") suspend fun touch(id: String, t: Long)
    @Query("DELETE FROM game_shortcuts WHERE profileId = :profileId") suspend fun clear(profileId: String)
}
