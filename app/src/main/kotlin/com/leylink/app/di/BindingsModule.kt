package com.leylink.app.di

import com.leylink.app.device.AndroidDeviceCapabilities
import com.leylink.app.device.DeviceCapabilities
import com.leylink.app.mesh.MeshController
import com.leylink.app.mesh.ServiceMeshController
import com.leylink.app.permissions.AndroidPermissionChecker
import com.leylink.app.permissions.PermissionChecker
import com.leylink.core.concurrent.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Interface -> implementation bindings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun permissionChecker(impl: AndroidPermissionChecker): PermissionChecker

    @Binds
    abstract fun deviceCapabilities(impl: AndroidDeviceCapabilities): DeviceCapabilities

    @Binds
    abstract fun meshController(impl: ServiceMeshController): MeshController

    @Binds
    abstract fun dispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider
}
