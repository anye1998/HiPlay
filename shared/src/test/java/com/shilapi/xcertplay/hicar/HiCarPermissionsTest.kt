package com.shilapi.xcertplay.hicar

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android changed this contract in API 31, and the bug it caused was not a wrong message but a dead
 * receiver: on Android 10 the API-31 permissions answer DENIED because the platform does not define
 * them at all, so the advertiser refused before it ever reached the stack.
 */
class HiCarPermissionsTest {
    @Test
    fun belowApi31TheOnlyRuntimeGrantIsLocation() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), HiCarPermissions.runtimeRequests(29))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), HiCarPermissions.runtimeRequests(30))
    }

    @Test
    fun fromApi31TheNearbyDevicePermissionsAreRequested() {
        val requests = HiCarPermissions.runtimeRequests(31)
        assertTrue(requests.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
        assertTrue(requests.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(requests.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertFalse(requests.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(requests, HiCarPermissions.runtimeRequests(34))
    }

    @Test
    fun theExplanationNamesWhyAnOlderBuildCannotHaveThosePermissions() {
        assertTrue(HiCarPermissions.explain(29).contains("do not exist below API 31"))
        assertTrue(HiCarPermissions.explain(29).contains("ACCESS_FINE_LOCATION"))
        assertTrue(HiCarPermissions.explain(31).contains("runtime grants"))
    }
}
