package com.aether.tv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, AppMetaEntity::class, FavoriteEntity::class, CategoryEntity::class, DeviceEntity::class, GameShortcutEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun appMeta(): AppMetaDao
    abstract fun favorites(): FavoriteDao
    abstract fun categories(): CategoryDao
    abstract fun devices(): DeviceDao
    abstract fun games(): GameShortcutDao
}
