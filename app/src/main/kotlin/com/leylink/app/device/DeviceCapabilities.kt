package com.leylink.app.device

/** What this phone can do right now. Abstracted so view models can be tested with a fake. */
interface DeviceCapabilities {
    fun hasBle(): Boolean

    fun isBluetoothEnabled(): Boolean
}
