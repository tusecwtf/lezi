package com.lezi.babylog.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class HomeWifiSettingsTarget { AppPermission, LocationServices, Wifi }

/** Runtime permission gate required before Android exposes location-sensitive SSID fields. */
object HomeWifiPermission {
    /**
     * Android 12+ requires FINE and COARSE to be requested together. SSID access still needs the
     * precise (FINE) grant, so a user who selects approximate location is guided instead of being
     * treated as ready for home-Wi-Fi matching.
     */
    fun requiredPermissions(): List<String> =
        listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

    fun missingPermissions(context: Context): List<String> =
        if (hasRequiredPermissions(context)) emptyList() else requiredPermissions()

    fun hasRequiredPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** Both the precise grant and the system location switch are required for SSID access. */
    fun isSsidAccessReady(context: Context): Boolean = homeWifiAccessReady(
        hasFineLocation = hasRequiredPermissions(context),
        locationServicesEnabled = locationServicesEnabled(context),
    )

    fun settingsTarget(context: Context): HomeWifiSettingsTarget =
        homeWifiSettingsTarget(
            hasFineLocation = hasRequiredPermissions(context),
            locationServicesEnabled = locationServicesEnabled(context),
        )

    fun settingsIntent(context: Context): Intent = when (settingsTarget(context)) {
        HomeWifiSettingsTarget.AppPermission -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        HomeWifiSettingsTarget.LocationServices -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        HomeWifiSettingsTarget.Wifi -> Intent(Settings.ACTION_WIFI_SETTINGS)
    }

    private fun locationServicesEnabled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(false)
}

internal fun homeWifiSettingsTarget(
    hasFineLocation: Boolean,
    locationServicesEnabled: Boolean,
): HomeWifiSettingsTarget = when {
    !hasFineLocation -> HomeWifiSettingsTarget.AppPermission
    !locationServicesEnabled -> HomeWifiSettingsTarget.LocationServices
    else -> HomeWifiSettingsTarget.Wifi
}

internal fun homeWifiAccessReady(
    hasFineLocation: Boolean,
    locationServicesEnabled: Boolean,
): Boolean = hasFineLocation && locationServicesEnabled
