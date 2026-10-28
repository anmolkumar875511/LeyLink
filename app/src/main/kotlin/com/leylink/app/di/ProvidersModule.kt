package com.leylink.app.di

import com.leylink.core.time.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Objects from library modules that have no Hilt annotations on purpose. Library modules stay
 * DI-agnostic; only :app (the composition root) knows how to build them. The mesh stack itself
 * (Room stores, BLE transport, router) is assembled in MeshEngine.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {
    @Provides
    fun clock(): Clock = Clock.SYSTEM
}
