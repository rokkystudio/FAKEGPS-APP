package fuck.system.fakegps

import android.content.Context

/**
 * Holds the currently selected mock coordinates for the UI and the mock GPS service.
 */
object MockLocationStore {
    const val PREFS_NAME = "fake_gps"
    const val KEY_LAT = "lat"
    const val KEY_LON = "lon"
    const val KEY_RUNNING = "running"
    const val DEFAULT_LATITUDE = 55.7558
    const val DEFAULT_LONGITUDE = 37.6173

    @Volatile
    var latitude: Double = DEFAULT_LATITUDE

    @Volatile
    var longitude: Double = DEFAULT_LONGITUDE

    @Volatile
    var running: Boolean = false

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        latitude = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: DEFAULT_LATITUDE
        longitude = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: DEFAULT_LONGITUDE
        running = prefs.getBoolean(KEY_RUNNING, false)
    }

    fun savePoint(context: Context, lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LON, lon.toString())
            .apply()
    }

    fun saveRunning(context: Context, isRunning: Boolean) {
        running = isRunning
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, isRunning)
            .apply()
    }
}
