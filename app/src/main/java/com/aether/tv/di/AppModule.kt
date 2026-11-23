package com.aether.tv.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.aether.tv.data.apps.InstalledAppSource
import com.aether.tv.data.apps.PackageManagerAppSource
import com.aether.tv.data.db.AetherDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.aetherDataStore: DataStore<Preferences> by preferencesDataStore(name = "aether_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AetherDatabase =
        Room.databaseBuilder(context, AetherDatabase::class.java, "aether.db").build()

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.aetherDataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun appSource(impl: PackageManagerAppSource): InstalledAppSource
    @Binds abstract fun secrets(impl: com.aether.tv.data.security.SecureStore): com.aether.tv.data.security.SecretStorage
}
