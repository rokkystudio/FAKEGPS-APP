package fuck.system.fakegps

import android.app.AppOpsManager
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.os.SystemClock

/**
 * Управляет тестовыми провайдерами Android и публикует через них фиктивное местоположение.
 *
 * Движок добавляет и включает провайдеры GPS и сети, а на Android 12 и новее — также fused
 * provider. Для работы приложение должно быть выбрано пользователем в настройках разработчика
 * как приложение для фиктивных местоположений; проверить это можно через [isSelectedAsMockApp].
 *
 * Экземпляр не хранит координаты: их передаёт вызывающий код в [push]. Это позволяет сервису
 * получать актуальную точку из [MockLocationStore] без пересоздания движка.
 *
 * @param context контекст, используемый для получения системного [LocationManager].
 */
class MockLocationEngine(context: Context)
{
    /** Утилиты, не требующие создания экземпляра движка. */
    companion object {
        /**
         * Проверяет, разрешила ли система этому приложению публиковать mock location.
         *
         * На Android 16 и новее используется актуальный overload с attribution tag. На Android
         * 10–15 единственный совместимый вариант — `unsafeCheckOpNoThrow`, устаревший только в
         * API 36; его использование ограничено соответствующей версионной веткой.
         *
         * @param context контекст проверяемого приложения.
         * @return `true`, если режим mock location разрешён для текущего UID приложения.
         */
        fun isSelectedAsMockApp(context: Context): Boolean
        {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName,
                        null
                    )
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    @Suppress("DEPRECATION")
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName
                    )
                }

                else -> {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName
                    )
                }
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Системный менеджер, управляющий тестовыми провайдерами местоположения. */
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Добавляет и включает все поддерживаемые тестовые провайдеры.
     *
     * Ошибки доступа означают, что приложение не выбрано для mock location либо устройство не
     * позволяет изменить нужный провайдер. В этом случае вызывающий код должен остановить сервис.
     *
     * @return `true`, если все провайдеры успешно подготовлены; иначе `false`.
     */
    fun start(): Boolean
    {
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

    /**
     * Передаёт новую фиктивную точку во все доступные тестовые провайдеры.
     *
     * Для каждой отправки создаётся [Location] с текущим системным временем и временем с момента
     * загрузки устройства. Ошибка одного провайдера не прерывает публикацию в остальных.
     *
     * @param latitude географическая широта выбранной точки.
     * @param longitude географическая долгота выбранной точки.
     */
    fun push(latitude: Double, longitude: Double)
    {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()

        for (provider in providers())
        {
            val location = Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                altitude = 0.0
                accuracy = 3.0f
                time = now
                elapsedRealtimeNanos = elapsed
                bearing = 0f
                speed = 0f
                bearingAccuracyDegrees = 0.1f
                speedAccuracyMetersPerSecond = 0.01f
                verticalAccuracyMeters = 1f
            }

            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /**
     * Отключает и удаляет все ранее добавленные тестовые провайдеры.
     *
     * Метод безопасно обрабатывает уже удалённый провайдер и должен вызываться при остановке
     * [MockLocationService], чтобы система вернулась к реальным источникам местоположения.
     */
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

    /**
     * Добавляет тестовый провайдер с параметрами GPS-подобного источника.
     *
     * Если провайдер уже существует, Android выбрасывает [IllegalArgumentException]. Это штатная
     * ситуация после повторного запуска сервиса, поэтому она игнорируется. Константы
     * [ProviderProperties] встраиваются в байткод как целые числа, поэтому их можно безопасно
     * передать в совместимый overload на устройствах с API ниже 31.
     *
     * @param name системное имя провайдера, например [LocationManager.GPS_PROVIDER].
     */
    @SuppressLint("InlinedApi")
    private fun addProvider(name: String) {
        try {
            locationManager.addTestProvider(
                name, // provider
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true, // supportsAltitude
                true, // supportsSpeed
                true, // supportsBearing
                ProviderProperties.POWER_USAGE_LOW, // powerUsage
                ProviderProperties.ACCURACY_FINE // accuracy
            )
        } catch (_: IllegalArgumentException) {
            // Провайдер уже добавлен как тестовый при предыдущем запуске.
        }
    }

    /**
     * Формирует список провайдеров, для которых публикуется фиктивная точка.
     *
     * @return GPS и сетевой провайдеры, а также fused provider на Android 12 и новее.
     */
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

}
