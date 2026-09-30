package fuck.system.fakegps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground-сервис, который непрерывно публикует фиктивные координаты устройства.
 *
 * При создании сервис загружает сохранённую точку, инициализирует [MockLocationEngine] и создаёт
 * канал уведомлений. Каждый запуск сервиса переводит его в foreground-режим и запускает периодическую
 * отправку координат с интервалом [UPDATE_INTERVAL_MS]. Координаты читаются из [MockLocationStore]
 * на каждой итерации, поэтому смена точки применяется без перезапуска сервиса.
 *
 * Сервис не поддерживает binding: управление выполняется через [start] и [stop], а пользователь
 * может остановить фиктивное местоположение действием в foreground-уведомлении.
 *
 * @see MockLocationEngine
 * @see MockLocationStore
 */
class MockLocationService : Service()
{
    /** Точка входа для управления экземплярами [MockLocationService]. */
    companion object {
        /** Действие Intent, которым запрашивается остановка фиктивного местоположения. */
        const val ACTION_STOP = "fuck.system.fakegps.STOP_MOCK"

        /** Идентификатор канала foreground-уведомления. */
        private const val CHANNEL_ID = "fake_gps_mock"

        /** Идентификатор уведомления работающего сервиса. */
        private const val NOTIFICATION_ID = 42

        /** Интервал между публикациями координат в миллисекундах. */
        private const val UPDATE_INTERVAL_MS = 500L

        /**
         * Запускает сервис в foreground-режиме.
         *
         * @param context контекст, из которого выполняется запуск.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MockLocationService::class.java)
            )
        }

        /**
         * Отправляет сервису команду [ACTION_STOP].
         *
         * @param context контекст, из которого отправляется команда остановки.
         */
        fun stop(context: Context)
        {
            val intent = Intent(
                context, MockLocationService::class.java
            ).setAction(ACTION_STOP)

            context.startForegroundService(intent)
        }
    }

    /** Движок, добавляющий и обновляющий тестовые провайдеры LocationManager. */
    private lateinit var engine: MockLocationEngine

    /** Поток, на котором выполняется периодическая отправка координат. */
    private var workerThread: HandlerThread? = null

    /** Обработчик рабочего потока; `null`, если периодическая отправка остановлена. */
    private var worker: Handler? = null

    /**
     * Задача одного цикла публикации координат.
     *
     * После отправки планирует следующий цикл, только если рабочий обработчик ещё существует.
     */
    private val tick = object : Runnable {
        override fun run() {
            engine.push(MockLocationStore.latitude, MockLocationStore.longitude)
            worker?.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    /**
     * Инициализирует сервис перед первым вызовом [onStartCommand].
     *
     * Загружает сохранённые координаты, создаёт движок тестовых провайдеров и регистрирует канал
     * foreground-уведомления.
     */
    override fun onCreate() {
        super.onCreate()
        MockLocationStore.load(this)
        engine = MockLocationEngine(this)
        createChannel()
    }

    /**
     * Запускает публикацию координат или обрабатывает команду остановки.
     *
     * Обычный запуск переводит сервис в foreground, включает тестовые провайдеры, отправляет
     * первую координату немедленно и затем запускает периодический таймер. Если движок не удалось
     * запустить, состояние сбрасывается, foreground-режим удаляется, а сервис останавливается.
     *
     * @param intent Intent с командой запуска; [ACTION_STOP] означает остановку сервиса.
     * @param flags дополнительные флаги повторного запуска от Android.
     * @param startId идентификатор конкретного запроса запуска.
     * @return [START_STICKY] для обычного запуска или [START_NOT_STICKY] после остановки/ошибки.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        if (intent?.action == ACTION_STOP) {
            stopMock()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        if (!engine.start()) {
            MockLocationStore.saveRunning(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        MockLocationStore.saveRunning(this, true)
        engine.push(MockLocationStore.latitude, MockLocationStore.longitude)
        startTicker()
        return START_STICKY
    }

    /**
     * Сервис не предоставляет связанный интерфейс управления.
     *
     * @param intent Intent запроса binding.
     * @return всегда `null`, поскольку сервис управляется командами запуска.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Останавливает таймер и движок, а затем сбрасывает признак работающего mock location. */
    override fun onDestroy() {
        stopTicker()
        engine.stop()
        MockLocationStore.saveRunning(this, false)
        super.onDestroy()
    }

    /**
     * Запускает рабочий поток и первый цикл публикации координат.
     *
     * Метод идемпотентен: повторный вызов не создаёт второй поток, если таймер уже работает.
     */
    private fun startTicker() {
        if (worker != null) return
        val thread = HandlerThread("fake-gps", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        workerThread = thread
        worker = Handler(thread.looper).also { it.post(tick) }
    }

    /** Отменяет отложенные циклы и безопасно завершает рабочий поток. */
    private fun stopTicker() {
        worker?.removeCallbacks(tick)
        worker = null
        workerThread?.quitSafely()
        workerThread = null
    }

    /**
     * Полностью останавливает фиктивное местоположение по команде [ACTION_STOP].
     *
     * Останавливает публикацию, удаляет тестовые провайдеры, обновляет хранилище и завершает
     * foreground-режим вместе с самим сервисом.
     */
    private fun stopMock() {
        stopTicker()
        engine.stop()
        MockLocationStore.saveRunning(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Переводит сервис в foreground-режим с типом location.
     *
     * Для Android 14 и новее явно передаёт тип foreground-сервиса, а на более старых версиях
     * использует совместимый вариант API.
     */
    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Создаёт уведомление с текущими координатами и действиями открытия приложения и остановки.
     *
     * @return уведомление, которое будет показано во время работы foreground-сервиса.
     */
    private fun buildNotification(): Notification
    {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getForegroundService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = getString(
            R.string.coordinates_format,
            MockLocationStore.latitude,
            MockLocationStore.longitude
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .addAction(0, getString(R.string.stop_mock_location), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Создаёт канал уведомлений на Android 8.0 и новее, если он ещё не зарегистрирован. */
    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

}
