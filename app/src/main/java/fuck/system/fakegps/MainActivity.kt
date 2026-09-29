package fuck.system.fakegps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fuck.system.fakegps.databinding.ActivityMainBinding
import fuck.system.fakegps.databinding.ActivityMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * Blocks the map until location permission is granted. A tap on the map immediately sets the mock
 * point (and starts the mock service if needed); the only button disables the mock and removes the
 * marker, so the real GPS is used again. If this app is not selected as the mock location app,
 * a dialog is shown over the map and taps do not change anything.
 */
class MainActivity : AppCompatActivity() {
    private var mockAppDialog: AlertDialog? = null
    private var gateBinding: ActivityMainBinding? = null
    private var mapBinding: ActivityMapBinding? = null
    private var pin: Marker? = null
    private var currentScreen: Screen? = null
    private var notificationPermissionAsked = false
    private var selectedPoint = GeoPoint(
        MockLocationStore.DEFAULT_LATITUDE,
        MockLocationStore.DEFAULT_LONGITUDE
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            routeAccess()
        } else {
            showPermissionScreen()
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startMockLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MockLocationStore.load(this)
        restorePoint(savedInstanceState)
        routeAccess(requestLocationIfNeeded = true)
    }

    override fun onResume() {
        super.onResume()
        routeAccess()
        if (currentScreen == Screen.Map) {
            mapBinding?.map?.onResume()
            syncMockState()
        }
    }

    override fun onPause() {
        mapBinding?.map?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mockAppDialog?.dismiss()
        mockAppDialog = null
        mapBinding?.map?.onDetach()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDouble(MockLocationStore.KEY_LAT, selectedPoint.latitude)
        outState.putDouble(MockLocationStore.KEY_LON, selectedPoint.longitude)
    }

    private fun routeAccess(requestLocationIfNeeded: Boolean = false) {
        val target = requiredScreen()
        if (target != Screen.Map || !isMockLocationApp()) {
            stopMockIfNeeded()
        }
        if (target == currentScreen) {
            if (requestLocationIfNeeded && target == Screen.Permission) {
                permissionLauncher.launch(LOCATION_PERMISSIONS)
            }
        } else {
            when (target) {
                Screen.Permission -> {
                    showPermissionScreen()
                    if (requestLocationIfNeeded) {
                        permissionLauncher.launch(LOCATION_PERMISSIONS)
                    }
                }
                Screen.Map -> showMapScreen()
            }
        }
        updateMockAppDialog()
    }

    private fun requiredScreen(): Screen {
        return if (hasLocationPermission()) Screen.Map else Screen.Permission
    }

    /** Shows the dialog over the map while this app is not selected as the mock location app. */
    private fun updateMockAppDialog() {
        val needDialog = currentScreen == Screen.Map && !isMockLocationApp()
        if (!needDialog) {
            mockAppDialog?.dismiss()
            mockAppDialog = null
            return
        }
        if (mockAppDialog?.isShowing == true || isFinishing || isDestroyed) {
            return
        }
        mockAppDialog = AlertDialog.Builder(this)
            .setTitle(R.string.mock_app_title)
            .setMessage(R.string.mock_app_message)
            .setPositiveButton(R.string.mock_app_open) { _, _ -> openDeveloperSettings() }
            .setNegativeButton(R.string.mock_app_close, null)
            .setOnDismissListener { mockAppDialog = null }
            .show()
    }

    private fun showPermissionScreen() {
        detachMap()
        val binding = bindGate()
        binding.title.setText(R.string.permission_title)
        binding.message.setText(R.string.permission_message)
        binding.grant.setText(R.string.permission_grant)
        binding.settings.setText(R.string.permission_settings)
        binding.grant.setOnClickListener {
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
        binding.settings.setOnClickListener { openAppSettings() }
        currentScreen = Screen.Permission
    }

    private fun bindGate(): ActivityMainBinding {
        val binding = ActivityMainBinding.inflate(layoutInflater)
        gateBinding = binding
        setContentView(binding.root)
        return binding
    }

    private fun showMapScreen() {
        if (requiredScreen() != Screen.Map) {
            routeAccess()
            return
        }
        if (mapBinding != null) {
            currentScreen = Screen.Map
            return
        }

        gateBinding = null
        configureOsmDroid()
        val binding = ActivityMapBinding.inflate(layoutInflater)
        mapBinding = binding
        setContentView(binding.root)
        setupMap(binding)
        binding.map.onResume()
        currentScreen = Screen.Map
    }

    private fun setupMap(binding: ActivityMapBinding) {
        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(DEFAULT_ZOOM)
        map.controller.setCenter(selectedPoint)

        val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onPointChosen(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        map.overlays.add(tapOverlay)

        binding.disable.setOnClickListener { disableMockLocation() }

        // The app may have been killed while the mock was active: make sure the service is alive
        // again and the marker is restored at the last saved point.
        if (MockLocationStore.running && isMockLocationApp()) {
            MockLocationService.start(this)
        }
        syncMockState()
    }

    /** A tap on the map (or the end of a marker drag) sets the new fake point right away. */
    private fun onPointChosen(point: GeoPoint) {
        if (requiredScreen() != Screen.Map) {
            routeAccess()
            return
        }
        if (!isMockLocationApp()) {
            updateMockAppDialog()
            return
        }
        selectedPoint = point
        // Persist first, so the point survives even if the process dies right after the tap.
        MockLocationStore.savePoint(this, point.latitude, point.longitude)
        showPin(point)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startMockLocation()
    }

    private fun startMockLocation() {
        if (requiredScreen() != Screen.Map || !isMockLocationApp()) {
            routeAccess()
            syncMockState()
            return
        }
        MockLocationStore.savePoint(this, selectedPoint.latitude, selectedPoint.longitude)
        MockLocationStore.saveRunning(this, true)
        // Also refreshes the notification text when the service is already running; the service
        // reads the new point from MockLocationStore on its next tick.
        MockLocationService.start(this)
        syncMockState()
    }

    /** Removes the marker and stops the mock, so real GPS coordinates are used again. */
    private fun disableMockLocation() {
        if (MockLocationStore.running) {
            MockLocationService.stop(this)
        }
        MockLocationStore.saveRunning(this, false)
        syncMockState()
    }

    private fun stopMockIfNeeded() {
        if (!MockLocationStore.running) {
            return
        }
        MockLocationService.stop(this)
        MockLocationStore.saveRunning(this, false)
    }

    /** Brings the marker, coordinates, status and button in line with the stored mock state. */
    private fun syncMockState() {
        val binding = mapBinding ?: return
        val running = MockLocationStore.running
        if (running) {
            val point = GeoPoint(MockLocationStore.latitude, MockLocationStore.longitude)
            selectedPoint = point
            showPin(point)
        } else {
            removePin()
            binding.coordinates.setText(R.string.coordinates_none)
        }
        binding.status.setText(if (running) R.string.status_running else R.string.status_stopped)
        binding.disable.isEnabled = running
    }

    private fun showPin(point: GeoPoint) {
        val map = mapBinding?.map ?: return
        val marker = pin ?: createPin(map).also {
            pin = it
            map.overlays.add(it)
        }
        marker.position = point
        map.invalidate()
        updateCoordinates(point)
    }

    private fun removePin() {
        val marker = pin ?: return
        mapBinding?.map?.let {
            it.overlays.remove(marker)
            it.invalidate()
        }
        pin = null
    }

    private fun createPin(map: MapView): Marker {
        return Marker(map).apply {
            title = getString(R.string.selected_point)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = pinIcon()
            isDraggable = true
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) {
                    updateCoordinates(marker.position)
                }

                override fun onMarkerDragEnd(marker: Marker) {
                    onPointChosen(GeoPoint(marker.position.latitude, marker.position.longitude))
                }

                override fun onMarkerDragStart(marker: Marker) = Unit
            })
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    private fun updateCoordinates(point: GeoPoint) {
        mapBinding?.coordinates?.text = getString(
            R.string.coordinates_format,
            point.latitude,
            point.longitude
        )
    }

    private fun configureOsmDroid() {
        val config = Configuration.getInstance()
        config.userAgentValue = packageName
        config.osmdroidBasePath = File(cacheDir, "osmdroid")
        config.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
    }

    private fun detachMap() {
        mapBinding?.map?.onPause()
        mapBinding?.map?.onDetach()
        mapBinding = null
        pin = null
    }

    private fun hasLocationPermission(): Boolean {
        return LOCATION_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isMockLocationApp(): Boolean {
        return MockLocationEngine.isSelectedAsMockApp(this)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.mock_app_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun restorePoint(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            selectedPoint = GeoPoint(
                savedInstanceState.getDouble(
                    MockLocationStore.KEY_LAT,
                    MockLocationStore.DEFAULT_LATITUDE
                ),
                savedInstanceState.getDouble(
                    MockLocationStore.KEY_LON,
                    MockLocationStore.DEFAULT_LONGITUDE
                )
            )
            return
        }
        selectedPoint = GeoPoint(MockLocationStore.latitude, MockLocationStore.longitude)
    }

    private fun pinIcon(): BitmapDrawable {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_pin)!!
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

    private enum class Screen {
        Permission,
        Map
    }

    private companion object {
        const val DEFAULT_ZOOM = 14.0
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
