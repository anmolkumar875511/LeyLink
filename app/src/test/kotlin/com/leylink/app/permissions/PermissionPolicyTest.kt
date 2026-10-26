package com.leylink.app.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {

    @Test
    fun `android 11 needs location only`() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), PermissionPolicy.required(30))
    }

    @Test
    fun `android 12 needs the three bluetooth permissions and no location`() {
        val required = PermissionPolicy.required(31)
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            required.toSet(),
        )
        assertFalse(required.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test
    fun `notifications are requested from android 13 but never required`() {
        assertFalse(PermissionPolicy.requestAll(32).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(PermissionPolicy.requestAll(33).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(PermissionPolicy.required(33).contains(Manifest.permission.POST_NOTIFICATIONS))
    }
}
