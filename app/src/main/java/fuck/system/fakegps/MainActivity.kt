package fuck.system.fakegps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.icu.util.ULocale
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import fuck.system.fakegps.databinding.ActivityMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Главный экран приложения Fake GPS, управляющий выбором фиктивного местоположения.
 *
 * Activity открывает экран карты с выбранной фиктивной точкой без запроса доступа к реальному
 * местоположению устройства.
 *
 * Выбор точки сохраняется в [MockLocationStore] и передаётся в [MockLocationService]. При
 * отключении фиктивного местоположения сервис останавливается, а маркер удаляется с карты.
 * Работа с системным геокодером выполняется в фоновом потоке, поэтому сетевой или системный
 * запрос адреса не блокирует интерфейс.
 *
 * Для публикации координат система также должна разрешить этому приложению режим mock location.
 * Если разрешение разработчика не выдано, Activity показывает инструкцию и игнорирует выбор точки.
 *
 * @see MockLocationStore
 * @see MockLocationService
 */
class MainActivity : ThemedActivity()
{
    /** Экраны, между которыми переключается Activity. */
    private enum class Screen {
        /** Экран с картой и выбранной фиктивной точкой. */
        Map
    }

    /** Доступные источники подложки карты. */
    private enum class MapProvider {
        /** Стандартная подробная карта OpenStreetMap. */
        OpenStreetMap,

        /** Тёмная подложка Esri World Dark Gray Canvas. */
        EsriDarkGray
    }

    /** Константы, общие для логики главного экрана. */
    private companion object {
        /** Тег Logcat для диагностики кнопки текущего местоположения. */
        const val LOCATION_LOG_TAG = "FakeGpsLocation"

        /** Начальный масштаб карты. */
        const val DEFAULT_ZOOM = 14.0

        /** Масштаб карты при переходе к району реального местоположения устройства. */
        const val DEVICE_LOCATION_ZOOM = 15.0

        /** Максимальное время ожидания одноразового запроса реального местоположения. */
        const val DEVICE_LOCATION_TIMEOUT_MS = 12_000L

        /**
         * Тёмный источник растровых тайлов Esri World Dark Gray Canvas.
         *
         * Сервис не требует ключа API. В REST-адресе Esri порядок координат —
         * «масштаб/строка/столбец», поэтому [getTileURLString] меняет местами координаты XYZ.
         */
        val ESRI_DARK_TILE_SOURCE = object : XYTileSource(
            "Esri World Dark Gray Canvas",
            0,
            23,
            256,
            ".png",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                "${getBaseUrl()}${MapTileIndex.getZoom(pMapTileIndex)}/" +
                    "${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
        }

        /** Имя настроек выбора источника карты. */
        const val PREFS_MAP = "fake_gps_map"

        /** Ключ выбранного источника карты. */
        const val KEY_MAP_PROVIDER = "map_provider"

        /** Разрешения точного и приблизительного доступа к реальному местоположению устройства. */
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /** Диалог с инструкцией по выбору приложения для фиктивных местоположений в настройках. */
    private var mockAppDialog: AlertDialog? = null

    /** Привязка экрана карты или `null`, пока карта не создана. */
    private var mapBinding: ActivityMapBinding? = null

    /** Выбранный пользователем источник подложки карты. */
    private var mapProvider = MapProvider.OpenStreetMap

    /** Маркер выбранной точки; создаётся лениво при первом выборе координат. */
    private var pin: Marker? = null

    /** Однопоточный исполнитель для последовательного обратного геокодирования координат. */
    private val geocoderExecutor = Executors.newSingleThreadExecutor()

    /** Обработчик главного потока, на котором разрешено изменять Views. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Идентификатор последнего запроса геокодирования.
     *
     * Увеличивается при выборе новой точки или удалении маркера. Благодаря этому результат
     * старого фонового запроса не перезаписывает адрес новой точки.
     */
    private var geocodeRequestId = 0L

    /** Текущее состояние навигации Activity, используемое для предотвращения лишней переинициализации. */
    private var currentScreen: Screen? = null

    /** Показывался ли запрос разрешения на уведомления в текущем экземпляре Activity. */
    private var notificationPermissionAsked = false

    /** Нужно ли повторить поиск текущей точки после возврата из системных настроек геолокации. */
    private var findDeviceLocationAfterSettings = false

    /** Идентификатор последнего пользовательского запроса перехода к текущей точке. */
    private var deviceLocationRequestId = 0L

    /** Активный системный запрос текущего местоположения, если он ожидает результата. */
    private var activeDeviceLocationCancellation: CancellationSignal? = null

    /** Менеджер, в котором зарегистрирован legacy listener текущего местоположения. */
    private var activeDeviceLocationManager: LocationManager? = null

    /** Legacy listener текущего местоположения, если запрос выполняется через requestSingleUpdate. */
    private var activeDeviceLocationListener: LocationListener? = null

    /** Диалог, показываемый во время ожидания координат от LocationManager. */
    private var deviceLocationProgressDialog: AlertDialog? = null

    /** Последняя выбранная точка, сохраняемая в `Bundle` при пересоздании Activity. */
    private var selectedPoint = GeoPoint(
        MockLocationStore.DEFAULT_LATITUDE,
        MockLocationStore.DEFAULT_LONGITUDE
    )

    /**
     * Обработчик результата запроса доступа к реальному местоположению устройства.
     * При успешном результате центрирует карту на текущем местоположении, иначе оставляет карту
     * в текущей позиции и показывает короткое сообщение.
     */
    private val deviceLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.d(LOCATION_LOG_TAG, "Permission result: $result")
        if (result.values.any { granted -> granted }) {
            Log.d(LOCATION_LOG_TAG, "At least one location permission granted; requesting device location")
            showDeviceLocation()
        } else {
            Log.d(LOCATION_LOG_TAG, "Location permission denied")
            Toast.makeText(this, R.string.current_location_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Обработчик возврата из системных настроек геолокации.
     *
     * Если пользователь включил геолокацию устройства, приложение сразу повторяет поиск текущей
     * точки. Если геолокация осталась выключенной, будет снова открыт системный экран включения.
     */
    private val deviceLocationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(
            LOCATION_LOG_TAG,
            "Returned from location settings; retry=$findDeviceLocationAfterSettings"
        )
        if (findDeviceLocationAfterSettings) {
            findDeviceLocationAfterSettings = false
            showDeviceLocationOrRequestPermission()
        }
    }

    /**
     * Обработчик разрешения на уведомления.
     * Сервис запускается независимо от результата, так как разрешение влияет только на видимость
     * уведомления, а не на сам выбор фиктивной точки.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startMockLocation()
    }

    /**
     * Инициализирует Activity.
     *
     * Загружает сохранённые координаты, восстанавливает точку после пересоздания Activity и
     * открывает карту для выбора фиктивной точки.
     *
     * @param savedInstanceState состояние Activity, сохранённое перед пересозданием, или `null`.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MockLocationStore.load(this)
        restorePoint(savedInstanceState)
        routeAccess()
    }

    /**
     * Возобновляет отображение карты и повторно проверяет доступ приложения.
     *
     * Метод вызывается после возврата Activity на экран, поэтому синхронизирует карту, состояние
     * mock-сервиса и диалог выбора приложения для фиктивных местоположений без пересоздания UI.
     */
    override fun onResume() {
        super.onResume()
        mapBinding?.map?.onResume()
        syncMockState()
        updateMockAppDialog()
    }

    /** Приостанавливает карту, когда Activity уходит с переднего плана. */
    override fun onPause() {
        mapBinding?.map?.onPause()
        super.onPause()
    }

    /**
     * Освобождает ресурсы Activity.
     *
     * Увеличение [geocodeRequestId] делает результаты уже запущенных запросов недействительными,
     * а остановка [geocoderExecutor] предотвращает выполнение новых фоновых задач после удаления
     * Activity.
     */
    override fun onDestroy() {
        mockAppDialog?.dismiss()
        mockAppDialog = null
        cancelDeviceLocationLookup()
        geocodeRequestId++
        geocoderExecutor.shutdownNow()
        mapBinding?.map?.onDetach()
        super.onDestroy()
    }

    /**
     * Сохраняет выбранные координаты перед пересозданием Activity.
     *
     * @param outState Bundle, в который Android записывает состояние экрана.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDouble(MockLocationStore.KEY_LAT, selectedPoint.latitude)
        outState.putDouble(MockLocationStore.KEY_LON, selectedPoint.longitude)
    }

    /**
     * Открывает карту и синхронизирует доступ приложения к публикации фиктивных координат.
     *
     * При отсутствии системного выбора приложения для фиктивных местоположений работающий сервис
     * останавливается, а поверх карты показывается инструкция по настройке.
     */
    private fun routeAccess()
    {
        if (!isMockLocationApp()) {
            stopMockIfNeeded()
        }

        if (currentScreen != Screen.Map) {
            showMapScreen()
        }
        updateMockAppDialog()
    }

    /**
     * Возвращает основной экран приложения.
     *
     * @return экран карты, доступный без разрешения на реальное местоположение устройства.
     */
    private fun requiredScreen(): Screen = Screen.Map

    /**
     * Синхронизирует диалог выбора приложения для фиктивных местоположений.
     *
     * Диалог показывается только поверх карты и только до тех пор, пока системный AppOps не
     * разрешит этому приложению публиковать тестовые координаты. Повторный диалог не создаётся,
     * если предыдущий ещё отображается или Activity уже завершается.
     */
    private fun updateMockAppDialog()
    {
        val needDialog = currentScreen == Screen.Map && !isMockLocationApp()
        if (!needDialog) {
            mockAppDialog?.dismiss()
            mockAppDialog = null
            return
        }

        if (mockAppDialog?.isShowing == true || isFinishing || isDestroyed) {
            return
        }

        val contentView = layoutInflater.inflate(R.layout.dialog_mock_app, null)
        val developerSettingsEnabled = isDeveloperSettingsEnabled()
        contentView.findViewById<TextView>(R.id.mockAppTitle).setText(
            if (developerSettingsEnabled) {
                R.string.mock_app_select_title
            } else {
                R.string.mock_app_enable_developer_title
            }
        )
        contentView.findViewById<TextView>(R.id.mockAppMessage).setText(
            if (developerSettingsEnabled) {
                R.string.mock_app_select_message
            } else {
                R.string.mock_app_enable_developer_message
            }
        )
        mockAppDialog = AlertDialog.Builder(this)
            .setView(contentView)
            .create()
            .apply {
                setOnDismissListener { mockAppDialog = null }
                show()
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                contentView.findViewById<View>(R.id.mockAppClose).setOnClickListener { dismiss() }
                contentView.findViewById<View>(R.id.mockAppOpen).setOnClickListener {
                    dismiss()
                    openDeveloperSettings()
                }
            }
    }

    /**
     * Создаёт экран карты, настраивает osmdroid и восстанавливает состояние фиктивной точки.
     *
     * Если карта уже создана, повторная инфляция разметки не выполняется. Это важно при вызовах
     * из `onResume`, когда Activity возвращается из настроек разработчика.
     */
    private fun showMapScreen()
    {
        if (requiredScreen() != Screen.Map) {
            routeAccess()
            return
        }

        if (mapBinding != null) {
            currentScreen = Screen.Map
            return
        }

        configureOsmDroid()
        val binding = ActivityMapBinding.inflate(layoutInflater)
        mapBinding = binding
        setContentView(binding.root)
        setupTopBar(binding.topBar, getString(R.string.app_name))
        binding.mapLayersButton.setOnClickListener { showMapLayersMenu(binding) }
        binding.currentLocation.setOnClickListener { showDeviceLocationOrRequestPermission() }
        setupMap(binding)
        binding.map.onResume()
        currentScreen = Screen.Map
    }

    /**
     * Настраивает карту, обработчики нажатий, маркер и кнопку отключения фиктивного GPS.
     *
     * @param binding привязка экрана карты, которую нужно настроить.
     */
    private fun setupMap(binding: ActivityMapBinding)
    {
        val map = binding.map
        mapProvider = savedMapProvider()
        applyMapProvider(binding)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(DEFAULT_ZOOM)
        map.controller.setCenter(selectedPoint)

        val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
            /**
             * Обрабатывает одиночное подтверждённое нажатие на карту.
             *
             * @param p координаты места нажатия.
             * @return `true`, чтобы событие считалось обработанным этим overlay.
             */
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onPointChosen(p)
                return true
            }

            /**
             * Обрабатывает длительное нажатие на карту без выбора точки.
             *
             * @param p координаты места длительного нажатия.
             * @return `false`, поскольку длительное нажатие не обрабатывается.
             */
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        map.overlays.add(tapOverlay)

        binding.disable.setOnClickListener { disableMockLocation() }

        // Приложение могло быть завершено во время работы mock location: снова запускаем сервис
        // и восстанавливаем маркер в последней сохранённой точке.
        if (MockLocationStore.running && isMockLocationApp()) {
            MockLocationService.start(this)
        }
        syncMockState()
    }

    /**
     * Центрирует карту на реальном местоположении устройства или запускает системный запрос
     * разрешения на геолокацию.
     */
    private fun showDeviceLocationOrRequestPermission()
    {
        Log.d(LOCATION_LOG_TAG, "Current location button clicked")
        if (hasDeviceLocationPermission()) {
            Log.d(LOCATION_LOG_TAG, "Location permission already granted")
            showDeviceLocation()
            return
        }

        Log.d(LOCATION_LOG_TAG, "Location permission missing; launching permission request")
        deviceLocationPermissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    /**
     * Проверяет наличие доступа к реальному местоположению устройства.
     *
     * @return `true`, если выдано точное или приблизительное разрешение.
     */
    private fun hasDeviceLocationPermission(): Boolean {
        val result = LOCATION_PERMISSIONS.associateWith { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(LOCATION_LOG_TAG, "Permission state: $result")
        return result.values.any { granted -> granted }
    }

    /**
     * Получает реальное местоположение устройства и переносит карту к найденной точке.
     *
     * Каждый новый вызов отменяет предыдущий незавершённый запрос. Если активна фиктивная точка,
     * LocationManager возвращает координаты, которые видят другие приложения, и карта переходит к ним.
     */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun showDeviceLocation()
    {
        val requestId = ++deviceLocationRequestId
        activeDeviceLocationCancellation?.cancel()
        activeDeviceLocationCancellation = null
        Log.d(LOCATION_LOG_TAG, "Starting device location lookup requestId=$requestId")

        if (!hasDeviceLocationPermission()) {
            Log.d(LOCATION_LOG_TAG, "Permission disappeared before lookup; launching permission request")
            deviceLocationPermissionLauncher.launch(LOCATION_PERMISSIONS)
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = enabledLocationProviders(locationManager)
        Log.d(LOCATION_LOG_TAG, "Enabled providers for lookup requestId=$requestId: $providers")
        if (providers.isEmpty()) {
            Log.d(LOCATION_LOG_TAG, "No enabled providers; opening location settings")
            openDeviceLocationSettings()
            return
        }

        showDeviceLocationProgress()
        val cachedLocation = latestKnownLocation(locationManager)
        Log.d(LOCATION_LOG_TAG, "Latest usable cached location requestId=$requestId: ${cachedLocation.logString()}")
        if (cachedLocation != null) {
            showDeviceLocation(cachedLocation)
        }

        requestDeviceLocation(locationManager, providers, cachedLocation != null, requestId)
    }

    /**
     * Открывает системный экран включения геолокации устройства.
     *
     * Android не разрешает приложению включать геолокацию напрямую. После возврата из системного
     * экрана приложение повторяет запрос текущей точки без дополнительного нажатия на кнопку.
     */
    private fun openDeviceLocationSettings() {
        findDeviceLocationAfterSettings = true
        try {
            Log.d(LOCATION_LOG_TAG, "Launching system location settings")
            deviceLocationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (error: Exception) {
            Log.w(LOCATION_LOG_TAG, "Unable to open system location settings", error)
            findDeviceLocationAfterSettings = false
            Toast.makeText(
                this,
                R.string.current_location_settings_unavailable,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Запрашивает текущую точку по списку включённых provider'ов.
     *
     * @param locationManager системный менеджер местоположения.
     * @param providers включённые provider'ы, разрешённые текущим runtime-доступом.
     * @param hasShownCachedLocation `true`, если на карте уже показана последняя известная точка.
     * @param requestId идентификатор актуального пользовательского запроса.
     */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun requestDeviceLocation(
        locationManager: LocationManager,
        providers: List<String>,
        hasShownCachedLocation: Boolean,
        requestId: Long
    ) {
        if (requestId != deviceLocationRequestId) {
            Log.d(LOCATION_LOG_TAG, "Ignoring stale requestId=$requestId; active=$deviceLocationRequestId")
            return
        }

        val provider = providers.firstOrNull()
        if (provider == null) {
            activeDeviceLocationCancellation = null
            activeDeviceLocationListener = null
            activeDeviceLocationManager = null
            Log.d(
                LOCATION_LOG_TAG,
                "No providers left requestId=$requestId; hasShownCachedLocation=$hasShownCachedLocation"
            )
            hideDeviceLocationProgress()
            if (!hasShownCachedLocation) {
                Toast.makeText(this, R.string.current_location_unavailable, Toast.LENGTH_SHORT).show()
            }
            return
        }

        Log.d(LOCATION_LOG_TAG, "Requesting current location requestId=$requestId provider=$provider")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellationSignal = CancellationSignal()
            var finished = false
            activeDeviceLocationCancellation = cancellationSignal
            mainHandler.postDelayed({
                if (!finished && requestId == deviceLocationRequestId && activeDeviceLocationCancellation == cancellationSignal) {
                    finished = true
                    Log.d(LOCATION_LOG_TAG, "Current location timeout requestId=$requestId provider=$provider")
                    activeDeviceLocationCancellation = null
                    cancellationSignal.cancel()
                    requestDeviceLocation(locationManager, providers.drop(1), hasShownCachedLocation, requestId)
                }
            }, DEVICE_LOCATION_TIMEOUT_MS)
            locationManager.getCurrentLocation(provider, cancellationSignal, mainExecutor) { location ->
                if (finished) {
                    Log.d(LOCATION_LOG_TAG, "Ignoring completed current location result requestId=$requestId provider=$provider")
                    return@getCurrentLocation
                }
                finished = true
                if (requestId != deviceLocationRequestId) {
                    Log.d(LOCATION_LOG_TAG, "Ignoring stale current location result requestId=$requestId provider=$provider")
                    return@getCurrentLocation
                }
                if (activeDeviceLocationCancellation == cancellationSignal) {
                    activeDeviceLocationCancellation = null
                }
                activeDeviceLocationListener = null
                activeDeviceLocationManager = null
                Log.d(LOCATION_LOG_TAG, "Current location result requestId=$requestId provider=$provider: ${location.logString()}")
                if (location != null && isUsableDeviceLocation(location)) {
                    showDeviceLocation(location)
                } else {
                    Log.d(LOCATION_LOG_TAG, "Provider=$provider result rejected requestId=$requestId; trying next provider")
                    requestDeviceLocation(locationManager, providers.drop(1), hasShownCachedLocation, requestId)
                }
            }
            return
        }

        var delivered = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (requestId != deviceLocationRequestId) {
                    Log.d(LOCATION_LOG_TAG, "Ignoring stale single update result requestId=$requestId provider=$provider")
                    locationManager.removeUpdates(this)
                    if (activeDeviceLocationListener == this) {
                        activeDeviceLocationListener = null
                        activeDeviceLocationManager = null
                    }
                    return
                }
                delivered = true
                locationManager.removeUpdates(this)
                if (activeDeviceLocationListener == this) {
                    activeDeviceLocationListener = null
                    activeDeviceLocationManager = null
                }
                Log.d(LOCATION_LOG_TAG, "Single update result requestId=$requestId provider=$provider: ${location.logString()}")
                if (isUsableDeviceLocation(location)) {
                    showDeviceLocation(location)
                } else {
                    Log.d(LOCATION_LOG_TAG, "Provider=$provider result rejected requestId=$requestId; trying next provider")
                    requestDeviceLocation(locationManager, providers.drop(1), hasShownCachedLocation, requestId)
                }
            }
        }
        activeDeviceLocationManager = locationManager
        activeDeviceLocationListener = listener
        mainHandler.postDelayed({
            if (!delivered && requestId == deviceLocationRequestId && activeDeviceLocationListener == listener) {
                Log.d(LOCATION_LOG_TAG, "Single update timeout requestId=$requestId provider=$provider")
                locationManager.removeUpdates(listener)
                activeDeviceLocationListener = null
                activeDeviceLocationManager = null
                requestDeviceLocation(locationManager, providers.drop(1), hasShownCachedLocation, requestId)
            }
        }, DEVICE_LOCATION_TIMEOUT_MS)
        locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    }

    /**
     * Возвращает включённые provider'ы, подходящие для одноразового запроса текущей точки.
     *
     * Fused и network provider проверяются перед GPS, чтобы кнопка быстрее реагировала в помещении
     * и после остановки фиктивных координат. GPS используется только при точном доступе.
     *
     * @param locationManager системный менеджер местоположения.
     * @return список provider'ов, доступных для запроса координат.
     */
    private fun enabledLocationProviders(locationManager: LocationManager): List<String> {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fusedEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val providers = mutableListOf<String>()

        Log.d(
            LOCATION_LOG_TAG,
            "Provider state: fused=$fusedEnabled gps=$gpsEnabled network=$networkEnabled fine=$fineLocationGranted"
        )

        if (fusedEnabled) {
            providers += LocationManager.FUSED_PROVIDER
        }
        if (networkEnabled) {
            providers += LocationManager.NETWORK_PROVIDER
        }
        if (fineLocationGranted && gpsEnabled) {
            providers += LocationManager.GPS_PROVIDER
        }

        return providers
    }

    /**
     * Возвращает самое свежее сохранённое системой местоположение, пригодное для перехода камеры.
     *
     * Последняя выбранная fake-точка не используется для кнопки перехода к текущей точке, потому
     * что после остановки фиктивного GPS система может ещё хранить её как последнюю координату.
     *
     * @param locationManager системный менеджер местоположения.
     * @return последняя пригодная точка или `null`, если система не хранит координаты.
     */
    @Suppress("MissingPermission")
    private fun latestKnownLocation(locationManager: LocationManager): Location? {
        return deviceLocationProvidersForCache()
            .mapNotNull { provider ->
                val location = locationManager.getLastKnownLocation(provider)
                Log.d(LOCATION_LOG_TAG, "Cached location provider=$provider: ${location.logString()}")
                location
            }
            .filter(::isUsableDeviceLocation)
            .maxByOrNull { location -> location.time }
    }

    /**
     * Возвращает provider'ы, из которых можно читать сохранённую системную координату.
     *
     * @return список provider'ов с fused provider на Android 12 и новее.
     */
    private fun deviceLocationProvidersForCache(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * Проверяет, можно ли использовать координату для перехода к реальному местоположению.
     *
     * При активном Fake GPS используется координата, которую LocationManager отдаёт другим
     * приложениям. Без активного Fake GPS отбрасывается только последняя опубликованная fake-точка,
     * если система ещё хранит её в кэше после остановки сервиса.
     *
     * @param location координата, которую вернул LocationManager.
     * @return `true`, если координату можно использовать для перехода камеры.
     */
    private fun isUsableDeviceLocation(location: Location): Boolean {
        val fakePoint = Location("fake_point").apply {
            latitude = MockLocationStore.latitude
            longitude = MockLocationStore.longitude
        }
        val distanceToFake = location.distanceTo(fakePoint)
        val usable = MockLocationStore.running || distanceToFake > 25f
        Log.d(
            LOCATION_LOG_TAG,
            "Usability check: usable=$usable mockRunning=${MockLocationStore.running} " +
                "distanceToFake=${"%.1f".format(distanceToFake)}m location=${location.logString()}"
        )
        return usable
    }

    /**
     * Показывает отменяемый диалог ожидания координат от системного LocationManager.
     *
     * Диалог можно закрыть кнопкой отмены или нажатием вне окна. В обоих случаях активный запрос
     * координат отменяется, а поздний результат LocationManager игнорируется.
     */
    private fun showDeviceLocationProgress() {
        if (deviceLocationProgressDialog?.isShowing == true || isFinishing || isDestroyed) {
            return
        }
        val contentView = layoutInflater.inflate(R.layout.dialog_device_location_progress, null)
        deviceLocationProgressDialog = AlertDialog.Builder(this)
            .setView(contentView)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelDeviceLocationLookup() }
            .create()
            .apply {
                setCanceledOnTouchOutside(true)
                setOnCancelListener { cancelDeviceLocationLookup() }
                setOnDismissListener { deviceLocationProgressDialog = null }
                show()
            }
    }

    /** Отменяет активное ожидание координат и запрещает позднему результату менять карту. */
    private fun cancelDeviceLocationLookup() {
        val cancelledRequestId = ++deviceLocationRequestId
        activeDeviceLocationCancellation?.cancel()
        activeDeviceLocationCancellation = null
        activeDeviceLocationListener?.let { listener ->
            activeDeviceLocationManager?.removeUpdates(listener)
        }
        activeDeviceLocationListener = null
        activeDeviceLocationManager = null
        Log.d(LOCATION_LOG_TAG, "Device location lookup cancelled requestId=$cancelledRequestId")
        hideDeviceLocationProgress()
    }

    /** Закрывает диалог ожидания координат, если он отображается. */
    private fun hideDeviceLocationProgress() {
        deviceLocationProgressDialog?.dismiss()
        deviceLocationProgressDialog = null
    }

    /**
     * Переносит карту к району реального местоположения устройства без добавления маркера.
     *
     * Кнопка навигации используется только для перемещения камеры. Выбранная mock-точка и её
     * маркер остаются единственными объектами, которыми пользователь управляет на карте.
     *
     * @param location реальное местоположение устройства.
     */
    private fun showDeviceLocation(location: Location)
    {
        val map = mapBinding?.map
        if (map == null) {
            Log.d(LOCATION_LOG_TAG, "Map is not ready; cannot move camera to ${location.logString()}")
            hideDeviceLocationProgress()
            return
        }
        hideDeviceLocationProgress()
        val point = GeoPoint(location.latitude, location.longitude)
        Log.d(LOCATION_LOG_TAG, "Moving map camera to ${location.logString()} zoom=$DEVICE_LOCATION_ZOOM")
        map.controller.setZoom(DEVICE_LOCATION_ZOOM)
        map.controller.animateTo(point)
        map.invalidate()
    }

    /**
     * Форматирует координату для компактной диагностики в Logcat.
     *
     * @return строка с provider, координатами, точностью, временем и mock-флагом или `null`.
     */
    @Suppress("DEPRECATION")
    private fun Location?.logString(): String {
        if (this == null) return "null"
        val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            isFromMockProvider
        }
        return "provider=$provider lat=${"%.6f".format(latitude)} lon=${"%.6f".format(longitude)} " +
            "accuracy=${"%.1f".format(accuracy)}m time=$time elapsed=$elapsedRealtimeNanos mock=$mock"
    }

    /**
     * Показывает меню выбора источника карты возле кнопки слоёв.
     *
     * Активный источник отмечается стандартной радиокнопкой. Выбор сохраняется сразу и
     * применяется без пересоздания Activity, поэтому выбранная точка и масштаб карты не теряются.
     *
     * @param binding привязка активного экрана карты.
     */
    private fun showMapLayersMenu(binding: ActivityMapBinding) {
        PopupMenu(this, binding.mapLayersButton).apply {
            menuInflater.inflate(R.menu.map_layers, menu)
            menu.findItem(mapProvider.menuItemId()).isChecked = true
            setOnMenuItemClickListener { item ->
                val provider = mapProviderFromMenuItemId(item.itemId)
                    ?: return@setOnMenuItemClickListener false
                selectMapProvider(binding, provider)
                true
            }
            show()
        }
    }

    /**
     * Сохраняет выбранный источник и перерисовывает карту с его тайлами.
     *
     * @param binding привязка активного экрана карты.
     * @param provider источник, выбранный в меню слоёв.
     */
    private fun selectMapProvider(binding: ActivityMapBinding, provider: MapProvider) {
        if (mapProvider == provider) {
            return
        }

        mapProvider = provider
        getSharedPreferences(PREFS_MAP, MODE_PRIVATE)
            .edit()
            .putString(KEY_MAP_PROVIDER, provider.name)
            .apply()
        applyMapProvider(binding)
    }

    /**
     * Устанавливает сохранённый источник тайлов.
     *
     * Цветовая тема приложения намеренно не участвует в выборе: карту меняет только меню слоёв.
     *
     * @param binding привязка активного экрана карты.
     */
    private fun applyMapProvider(binding: ActivityMapBinding) {
        val map = binding.map
        val tileSource = when (mapProvider) {
            MapProvider.OpenStreetMap -> TileSourceFactory.MAPNIK
            MapProvider.EsriDarkGray -> ESRI_DARK_TILE_SOURCE
        }

        map.setTileSource(tileSource)
        map.overlayManager.tilesOverlay.setColorFilter(null)
        map.overlayManager.tilesOverlay.setLoadingBackgroundColor(Color.rgb(216, 208, 208))
        map.overlayManager.tilesOverlay.setLoadingLineColor(Color.rgb(200, 192, 192))
        map.invalidate()
    }

    /** @return источник карты, сохранённый между запусками приложения. */
    private fun savedMapProvider(): MapProvider {
        val name = getSharedPreferences(PREFS_MAP, MODE_PRIVATE)
            .getString(KEY_MAP_PROVIDER, null)
        return MapProvider.entries.firstOrNull { it.name == name } ?: MapProvider.OpenStreetMap
    }

    /** @return идентификатор пункта меню для текущего источника. */
    private fun MapProvider.menuItemId(): Int = when (this) {
        MapProvider.OpenStreetMap -> R.id.map_provider_open_street_map
        MapProvider.EsriDarkGray -> R.id.map_provider_esri_dark_gray
    }

    /** Преобразует идентификатор пункта меню в источник карты. */
    private fun mapProviderFromMenuItemId(itemId: Int): MapProvider? = when (itemId) {
        R.id.map_provider_open_street_map -> MapProvider.OpenStreetMap
        R.id.map_provider_esri_dark_gray -> MapProvider.EsriDarkGray
        else -> null
    }

    /**
     * Обрабатывает выбор новой фиктивной точки нажатием на карту или завершением перетаскивания
     * маркера.
     *
     * Метод сохраняет координаты до запуска сервиса, обновляет маркер и запрашивает адрес точки.
     * На Android 13 и новее перед первым запуском сервиса дополнительно запрашивается разрешение
     * на уведомления.
     *
     * @param point выбранные географические координаты.
     */
    private fun onPointChosen(point: GeoPoint)
    {
        if (requiredScreen() != Screen.Map) {
            routeAccess()
            return
        }

        if (!isMockLocationApp()) {
            updateMockAppDialog()
            return
        }

        selectedPoint = point
        // Сначала сохраняем точку, чтобы она не потерялась при немедленном завершении процесса.
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

    /**
     * Сохраняет текущую точку и запускает или обновляет сервис фиктивного местоположения.
     *
     * Если разрешение на геолокацию или системное разрешение mock location отозвано, запуск
     * отменяется, а Activity возвращается к актуальному экрану.
     */
    private fun startMockLocation()
    {
        if (requiredScreen() != Screen.Map || !isMockLocationApp()) {
            routeAccess()
            syncMockState()
            return
        }

        MockLocationStore.savePoint(this, selectedPoint.latitude, selectedPoint.longitude)
        MockLocationStore.saveRunning(this, true)

        // При уже работающем сервисе это также обновляет текст уведомления; сам сервис прочитает
        // новые координаты из MockLocationStore на следующем цикле.
        MockLocationService.start(this)
        syncMockState()
    }

    /**
     * Отключает фиктивное местоположение по нажатию пользовательской кнопки.
     *
     * После выполнения реальное местоположение снова становится доступным для системы, а кнопка
     * отключения скрывается синхронизацией состояния интерфейса.
     */
    private fun disableMockLocation()
    {
        if (MockLocationStore.running) {
            MockLocationService.stop(this)
        }

        MockLocationStore.saveRunning(this, false)
        syncMockState()
    }

    /**
     * Принудительно останавливает фиктивное местоположение при потере необходимого доступа.
     *
     * Метод используется перед переходом на экран разрешений или при потере выбора приложения
     * в настройках разработчика.
     */
    private fun stopMockIfNeeded() {
        if (!MockLocationStore.running) return
        MockLocationService.stop(this)
        MockLocationStore.saveRunning(this, false)
    }

    /**
     * Синхронизирует интерфейс с сохранённым состоянием сервиса.
     *
     * В активном состоянии отображает маркер, запускает определение адреса и показывает кнопку
     * отключения. В неактивном состоянии удаляет маркер, очищает координаты и скрывает кнопку.
     */
    private fun syncMockState()
    {
        val binding = mapBinding ?: return
        val running = MockLocationStore.running
        if (running) {
            val point = GeoPoint(MockLocationStore.latitude, MockLocationStore.longitude)
            selectedPoint = point
            showPin(point)
        } else {
            removePin()
            binding.address.visibility = View.GONE
            binding.coordinates.visibility = View.VISIBLE
            binding.coordinates.setText(R.string.coordinates_none)
            binding.mapHint.setText(R.string.map_hint)
        }
        binding.disable.isEnabled = running
        binding.disable.visibility = if (running) View.VISIBLE else View.GONE
    }

    /**
     * Показывает маркер выбранной точки и запускает получение её полного адреса.
     *
     * Пока адрес определяется, в поле координат отображается промежуточное состояние. Сам запрос
     * выполняется в фоне, поэтому этот метод можно безопасно вызывать из обработчика карты.
     *
     * @param point координаты точки, которую нужно отобразить.
     */
    private fun showPin(point: GeoPoint)
    {
        val map = mapBinding?.map ?: return
        val marker = pin ?: createPin(map).also {
            pin = it
            map.overlays.add(it)
        }

        marker.position = point
        map.invalidate()
        mapBinding?.address?.apply {
            visibility = View.VISIBLE
            setText(R.string.address_loading)
        }
        mapBinding?.coordinates?.visibility = View.INVISIBLE
        requestPlaceName(point)
        mapBinding?.mapHint?.setText(R.string.map_hint_selected)
    }

    /**
     * Удаляет маркер с карты и отменяет актуальность незавершённого запроса адреса.
     *
     * Результат уже выполняющегося геокодирования будет проигнорирован по изменённому
     * [geocodeRequestId].
     */
    private fun removePin()
    {
        geocodeRequestId++
        val marker = pin ?: return
        mapBinding?.map?.let {
            it.overlays.remove(marker)
            it.invalidate()
        }
        pin = null
    }

    /**
     * Создаёт настроенный перетаскиваемый маркер.
     *
     * Маркер использует собственный значок, закреплён нижней частью в выбранной координате и
     * передаёт новую точку в [onPointChosen] после завершения перетаскивания.
     *
     * @param map карта, на которой будет размещён маркер.
     * @return новый маркер выбранной точки.
     */
    private fun createPin(map: MapView): Marker
    {
        return Marker(map).apply {
            title = getString(R.string.selected_point)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = pinIcon()
            isDraggable = true
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                /**
                 * Показывает текущие координаты во время перетаскивания маркера.
                 *
                 * @param marker маркер, перемещаемый пользователем.
                 */
                override fun onMarkerDrag(marker: Marker) {
                    updateCoordinates(marker.position)
                }

                /**
                 * Сохраняет новую точку после завершения перетаскивания маркера.
                 *
                 * @param marker маркер с финальной позицией.
                 */
                override fun onMarkerDragEnd(marker: Marker) {
                    onPointChosen(GeoPoint(marker.position.latitude, marker.position.longitude))
                }

                /**
                 * Обрабатывает начало перетаскивания без дополнительных действий.
                 *
                 * @param marker маркер, который начал перемещение.
                 */
                override fun onMarkerDragStart(marker: Marker) = Unit
            })
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    /**
     * Немедленно обновляет строку координат во время перетаскивания маркера.
     *
     * Полный адрес здесь не запрашивается: новый адрес определяется один раз после завершения
     * перетаскивания в [onPointChosen].
     *
     * @param point текущая позиция маркера.
     */
    private fun updateCoordinates(point: GeoPoint) {
        mapBinding?.address?.apply {
            visibility = View.VISIBLE
            setText(R.string.address_loading)
        }
        mapBinding?.coordinates?.apply {
            visibility = View.VISIBLE
            text = getString(
                R.string.coordinates_format,
                point.latitude,
                point.longitude
            )
        }
    }

    /**
     * Асинхронно получает полный адрес по координатам через системный геокодер.
     *
     * Адрес приводится к порядку от общего к частному: страна, регион, населённый пункт, улица и
     * дом. Результат применяется только для последнего выбранного запроса. Обновление TextView
     * возвращается в главный поток через [mainHandler].
     *
     * @param point координаты, для которых нужно определить адрес.
     */
    private fun requestPlaceName(point: GeoPoint)
    {
        val requestId = ++geocodeRequestId
        if (!Geocoder.isPresent()) {
            showLocationDetails(point, null)
            return
        }

        geocoderExecutor.execute {
            val fullAddress = try {
                @Suppress("DEPRECATION")
                Geocoder(this, Locale.getDefault())
                    .getFromLocation(point.latitude, point.longitude, 1)
                    ?.firstOrNull()
                    ?.let(::formatAddress)
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                if (requestId != geocodeRequestId || mapBinding == null) {
                    return@post
                }

                showLocationDetails(point, fullAddress)
            }
        }
    }

    /**
     * Раздельно отображает полный адрес и координаты выбранной точки.
     *
     * @param point точка, координаты которой нужно показать.
     * @param fullAddress адрес, определённый геокодером, или `null` при отсутствии результата.
     */
    private fun showLocationDetails(point: GeoPoint, fullAddress: String?) {
        val binding = mapBinding ?: return
        binding.address.apply {
            visibility = View.VISIBLE
            text = fullAddress ?: getString(R.string.address_not_found)
        }
        binding.coordinates.apply {
            visibility = View.VISIBLE
            text = getString(
                R.string.coordinates_format,
                point.latitude,
                point.longitude
            )
        }
    }

    /**
     * Собирает адрес в порядке от общего к частному.
     *
     * @param address ответ системного геокодера.
     * @return форматированный адрес или `null`, если геокодер не вернул ни одного компонента.
     */
    private fun formatAddress(address: Address): String? {
        val parts = mutableListOf<String>()

        fun addPart(value: String?) {
            val part = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (parts.none { it.equals(part, ignoreCase = true) }) {
                parts += part
            }
        }

        addPart(address.postalCode)
        addPart(nativeCountryName(address))
        addPart(address.adminArea)
        addPart(address.subAdminArea)
        addPart(address.locality)
        addPart(address.subLocality)
        val street = listOfNotNull(address.thoroughfare, address.subThoroughfare)
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
        addPart(street)

        if (parts.isEmpty() && address.maxAddressLineIndex >= 0) {
            for (index in 0..address.maxAddressLineIndex) {
                addPart(address.getAddressLine(index))
            }
        }
        return parts.joinToString(", ").takeIf { it.isNotEmpty() }
    }

    /**
     * Возвращает самоназвание страны на её основном локальном языке.
     *
     * @param address ответ геокодера с двухбуквенным кодом страны.
     * @return локальное название страны или название, которое вернул геокодер, если код недоступен.
     */
    private fun nativeCountryName(address: Address): String? {
        val countryCode = address.countryCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: return address.countryName
        return runCatching {
            val countryLocale = ULocale.addLikelySubtags(
                ULocale.forLanguageTag("und-$countryCode")
            )
            countryLocale.getDisplayCountry(countryLocale)
                .takeIf { it.isNotBlank() }
        }.getOrNull() ?: address.countryName
    }

    /**
     * Настраивает каталоги кэша и идентификатор пользователя для библиотеки osmdroid.
     *
     * Кэш размещается в каталоге приложения, чтобы библиотека не требовала доступа к общему
     * хранилищу устройства.
     */
    private fun configureOsmDroid() {
        val config = Configuration.getInstance()
        config.userAgentValue = packageName
        config.osmdroidBasePath = File(cacheDir, "osmdroid")
        config.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
    }

    /**
     * Проверяет, выбрано ли это приложение системным приложением для фиктивных местоположений.
     *
     * Проверка выполняется через AppOpsManager и учитывает различия API до и после Android 10.
     *
     * @return `true`, если системный AppOps разрешает mock location для приложения.
     */
    private fun isMockLocationApp(): Boolean {
        return MockLocationEngine.isSelectedAsMockApp(this)
    }

    /**
     * Проверяет, включён ли системный раздел параметров разработчика.
     *
     * Результат используется только для текста инструкции. Если прошивка запрещает чтение
     * глобальной настройки, диалог показывает сценарий выбора mock-приложения.
     *
     * @return `true`, если Android сообщает, что параметры разработчика включены.
     */
    private fun isDeveloperSettingsEnabled(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (error: Exception) {
            Log.w(LOCATION_LOG_TAG, "Unable to read developer settings state", error)
            true
        }
    }

    /**
     * Открывает системные настройки, где выбирается приложение фиктивных местоположений.
     *
     * На Nubia/RedMagic прямой Intent параметров разработчика ведёт в системный экран-заглушку,
     * поэтому для этих устройств сразу открывается общий экран настроек.
     */
    private fun openDeveloperSettings() {
        val intents = if (isNubiaDevice()) {
            listOf(Intent(Settings.ACTION_SETTINGS))
        } else {
            listOf(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
                Intent(Settings.ACTION_SETTINGS)
            )
        }

        for (intent in intents) {
            try {
                Log.d(LOCATION_LOG_TAG, "Opening settings action=${intent.action}")
                startActivity(intent)
                return
            } catch (error: Exception) {
                Log.w(LOCATION_LOG_TAG, "Unable to open settings action=${intent.action}", error)
            }
        }

        Toast.makeText(this, R.string.mock_app_open_failed, Toast.LENGTH_LONG).show()
    }

    /** Возвращает `true`, если устройство использует прошивку Nubia или RedMagic. */
    private fun isNubiaDevice(): Boolean {
        return Build.MANUFACTURER.equals("nubia", ignoreCase = true) ||
            Build.BRAND.equals("nubia", ignoreCase = true)
    }

    /**
     * Восстанавливает точку из состояния Activity или из постоянного хранилища.
     *
     * Состояние Activity имеет приоритет, поскольку содержит последние координаты до пересоздания.
     * При обычном запуске используются координаты, загруженные из [MockLocationStore].
     *
     * @param savedInstanceState состояние, сохранённое перед пересозданием Activity, или `null`.
     */
    private fun restorePoint(savedInstanceState: Bundle?)
    {
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

    /**
     * Возвращает растровый значок выбранной mock-точки для osmdroid Marker.
     *
     * Значок подготовлен из исходного изображения приложения и хранится как PNG-ресурс
     * подходящего размера, чтобы маркер не перекрывал карту.
     *
     * @return растровый значок маркера.
     */
    private fun pinIcon(): BitmapDrawable {
        return drawableIcon(R.drawable.ic_mock_location_pin)
    }

    /**
     * Преобразует drawable-ресурс в BitmapDrawable с исходными размерами.
     *
     * @param resId идентификатор drawable-ресурса.
     * @return растровая версия drawable-ресурса.
     */
    private fun drawableIcon(resId: Int): BitmapDrawable {
        val drawable = ContextCompat.getDrawable(this, resId)!!
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap.toDrawable(resources)
    }

}
