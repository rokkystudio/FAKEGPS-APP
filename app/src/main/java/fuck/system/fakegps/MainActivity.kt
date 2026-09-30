package fuck.system.fakegps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import fuck.system.fakegps.MainActivity.Companion.LOCATION_PERMISSIONS
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
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Главный экран приложения Fake GPS, управляющий выбором фиктивного местоположения.
 *
 * Activity переключается между двумя состояниями:
 *
 * 1. экран запроса разрешения на геолокацию;
 * 2. экран карты с выбранной точкой.
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
class MainActivity : AppCompatActivity()
{
    /** Экраны, между которыми переключается Activity. */
    private enum class Screen {
        /** Экран запроса разрешения на геолокацию. */
        Permission,

        /** Экран с картой и выбранной фиктивной точкой. */
        Map
    }

    /** Константы, общие для логики главного экрана. */
    private companion object {
        /** Начальный масштаб карты. */
        const val DEFAULT_ZOOM = 14.0

        /** Разрешения точного и приблизительного доступа к геолокации. */
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /** Диалог с инструкцией по выбору приложения для фиктивных местоположений в настройках. */
    private var mockAppDialog: AlertDialog? = null

    /** Привязка экрана запроса разрешения или `null`, когда открыт экран карты. */
    private var gateBinding: ActivityMainBinding? = null

    /** Привязка экрана карты или `null`, пока карта не создана. */
    private var mapBinding: ActivityMapBinding? = null

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

    /** Последняя выбранная точка, сохраняемая в `Bundle` при пересоздании Activity. */
    private var selectedPoint = GeoPoint(
        MockLocationStore.DEFAULT_LATITUDE,
        MockLocationStore.DEFAULT_LONGITUDE
    )

    /**
     * Обработчик результата запроса разрешений на точное и приблизительное местоположение.
     * При успешном результате открывает карту, иначе оставляет пользователя на экране разрешений.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            routeAccess()
        } else {
            showPermissionScreen()
            Toast.makeText(
                this, R.string.permission_denied,
                Toast.LENGTH_SHORT
            ).show()
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
     * открывает экран разрешений либо карту в зависимости от текущих разрешений.
     *
     * @param savedInstanceState состояние Activity, сохранённое перед пересозданием, или `null`.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MockLocationStore.load(this)
        restorePoint(savedInstanceState)
        routeAccess(requestLocationIfNeeded = true)
    }

    /**
     * Возобновляет отображение карты и повторно проверяет доступ приложения.
     *
     * Метод вызывается также после возврата из системных настроек, поэтому здесь повторно
     * синхронизируется состояние mock-сервиса и диалога выбора приложения.
     */
    override fun onResume() {
        super.onResume()
        routeAccess()
        if (currentScreen == Screen.Map) {
            mapBinding?.map?.onResume()
            syncMockState()
        }
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
     * Определяет доступный экран и переводит Activity в соответствующее состояние.
     *
     * @param requestLocationIfNeeded нужно ли сразу запустить системный запрос разрешений.
     */
    private fun routeAccess(requestLocationIfNeeded: Boolean = false)
    {
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

    /**
     * Возвращает экран, который должен быть открыт при текущем состоянии разрешений.
     *
     * @return экран карты при наличии разрешения или экран запроса разрешения.
     */
    private fun requiredScreen(): Screen {
        return if (hasLocationPermission()) Screen.Map else Screen.Permission
    }

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

        mockAppDialog = AlertDialog.Builder(this)
            .setTitle(R.string.mock_app_title)
            .setMessage(R.string.mock_app_message)
            .setPositiveButton(R.string.mock_app_open) { _, _ -> openDeveloperSettings() }
            .setNegativeButton(R.string.mock_app_close, null)
            .setOnDismissListener { mockAppDialog = null }
            .show()
    }

    /**
     * Настраивает и показывает экран запроса разрешения на геолокацию.
     *
     * Перед установкой экрана разрешений карта останавливается и отсоединяется, чтобы у Activity
     * оставался только один активный набор ViewBinding.
     */
    private fun showPermissionScreen()
    {
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

    /**
     * Создаёт привязку экрана разрешений и устанавливает его корневой View.
     *
     * @return созданная привязка экрана разрешений.
     */
    private fun bindGate(): ActivityMainBinding {
        val binding = ActivityMainBinding.inflate(layoutInflater)
        gateBinding = binding
        setContentView(binding.root)
        return binding
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

        gateBinding = null
        configureOsmDroid()
        val binding = ActivityMapBinding.inflate(layoutInflater)
        mapBinding = binding
        setContentView(binding.root)
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
        map.setTileSource(TileSourceFactory.MAPNIK)
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
        mapBinding?.coordinates?.setText(R.string.address_loading)
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
        mapBinding?.coordinates?.text = getString(
            R.string.coordinates_format,
            point.latitude,
            point.longitude
        )
    }

    /**
     * Асинхронно получает полный адрес по координатам через системный геокодер.
     *
     * Сначала извлекаются полные строки адреса из [android.location.Address]. Если провайдер не
     * вернул строк адреса, используются доступные компоненты адреса: объект, улица, населённый
     * пункт, регион и страна. Результат применяется только для последнего выбранного запроса.
     * Обновление TextView возвращается в главный поток через [mainHandler].
     *
     * @param point координаты, для которых нужно определить адрес.
     */
    private fun requestPlaceName(point: GeoPoint)
    {
        val requestId = ++geocodeRequestId
        if (!Geocoder.isPresent()) {
            updateCoordinates(point)
            return
        }

        geocoderExecutor.execute {
            val fullAddress = try {
                @Suppress("DEPRECATION")
                Geocoder(this, Locale.getDefault())
                    .getFromLocation(point.latitude, point.longitude, 1)
                    ?.firstOrNull()
                    ?.let { address ->
                        val lines = if (address.maxAddressLineIndex >= 0) {
                            (0..address.maxAddressLineIndex)
                                .mapNotNull { index -> address.getAddressLine(index)?.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        } else {
                            emptyList()
                        }
                        val fallback = listOfNotNull(
                            address.featureName,
                            address.thoroughfare,
                            address.subThoroughfare,
                            address.locality,
                            address.subAdminArea,
                            address.adminArea,
                            address.countryName
                        ).distinct()
                        val addressParts = lines.ifEmpty { fallback }
                        addressParts.joinToString(", ").takeIf { it.isNotEmpty() }
                    }
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                if (requestId != geocodeRequestId || mapBinding == null) {
                    return@post
                }

                val coordinates = getString(
                    R.string.coordinates_format,
                    point.latitude,
                    point.longitude
                )
                mapBinding?.coordinates?.text = fullAddress?.let {
                    getString(R.string.coordinates_with_place, it, coordinates)
                } ?: coordinates
            }
        }
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
     * Приостанавливает карту и освобождает её привязку перед сменой экрана.
     *
     * После вызова ссылки на карту и маркер обнуляются, поэтому экран карты должен быть создан
     * заново при следующем переходе к нему.
     */
    private fun detachMap() {
        mapBinding?.map?.onPause()
        mapBinding?.map?.onDetach()
        mapBinding = null
        pin = null
    }

    /**
     * Проверяет наличие хотя бы одного разрешения на доступ к местоположению.
     *
     * Для открытия карты достаточно точного или приблизительного разрешения, поэтому проверяются
     * оба разрешения из [LOCATION_PERMISSIONS].
     *
     * @return `true`, если выдано точное или приблизительное разрешение.
     */
    private fun hasLocationPermission(): Boolean {
        return LOCATION_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
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

    /** Открывает страницу настроек приложения, где пользователь может управлять разрешениями. */
    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    /**
     * Открывает настройки разработчика для выбора приложения фиктивных местоположений.
     *
     * Если устройство не поддерживает соответствующий Intent или режим разработчика отключён,
     * пользователю показывается сообщение об ошибке.
     */
    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.mock_app_open_failed, Toast.LENGTH_LONG).show()
        }
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
     * Преобразует drawable значка точки в BitmapDrawable, совместимый с osmdroid Marker.
     *
     * Размер Bitmap берётся из исходного drawable и принудительно ограничивается минимумом `1x1`,
     * чтобы избежать ошибки при отсутствии у drawable заданных габаритов.
     *
     * @return растровый значок маркера.
     */
    private fun pinIcon(): BitmapDrawable {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_pin)!!
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap.toDrawable(resources)
    }

}
