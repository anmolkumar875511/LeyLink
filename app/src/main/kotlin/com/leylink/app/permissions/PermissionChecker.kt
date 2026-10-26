package com.leylink.app.permissions

/** Abstraction over runtime permission state so view models can be tested with a fake. */
interface PermissionChecker {
    fun hasRequiredPermissions(): Boolean
}
