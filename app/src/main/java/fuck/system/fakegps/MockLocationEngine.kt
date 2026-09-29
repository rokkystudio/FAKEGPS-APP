package fuck.system.fakegps

import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock

/**
 * Publishes a mock location through Android test providers for developer-mode Fake GPS.
 */
class MockLocationEngine(context: Context) {
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun start(): Boolean {
        return try {
            for (provider in providers()) {
                addProvider(provider)
                locationManager.setTestProviderEnabled(provider, true)
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun push(latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        for (provider in providers()) {
            val location = Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                altitude = 0.0
                accuracy = 3.0f
                time = now
                elapsedRealtimeNanos = elapsed
                bearing = 0f
                speed = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 0.1f
                    speedAccuracyMetersPerSecond = 0.01f
                    verticalAccuracyMeters = 1f
                }
            }
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun stop() {
        for (provider in providers()) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
            } catch (_: Exception) {
            }
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
            }
        }
    }

    private fun addProvider(name: String) {
        try {
            locationManager.addTestProvider(
                name,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        } catch (_: IllegalArgumentException) {
            // Provider already exists as a test provider.
        }
    }

    private fun providers(): List<String> {
        val names = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            names += LocationManager.FUSED_PROVIDER
        }
        return names
    }

    companion object {
        fun isSelectedAsMockApp(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
