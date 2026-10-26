package com.leylink.app.di

import com.leylink.core.port.Transport
import com.leylink.core.time.Clock
import com.leylink.transport.ble.BleTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Objects from library modules that have no Hilt annotations on purpose. Library modules stay
 * DI-agnostic; only :app (the composition root) knows how to build them.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {
    @Provides
    fun clock(): Clock = Clock.SYSTEM

    @Provides
    @Singleton
    fun transport(): Transport = BleTransport()
}
