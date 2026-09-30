package fuck.system.fakegps

import android.content.Context
import androidx.core.content.edit

/**
 * Единое хранилище состояния фиктивного местоположения.
 *
 * Объект хранит последние выбранные широту и долготу, а также признак активного mock location.
 * Значения доступны одновременно из [MainActivity] и [MockLocationService], поэтому изменяемые
 * поля помечены [Volatile]. Постоянное хранение выполняется через SharedPreferences с именем
 * [PREFS_NAME]; в памяти всегда остаётся актуальная копия данных.
 *
 * Перед чтением состояния нужно вызвать [load]. Изменять координаты и состояние следует только
 * методами [savePoint] и [saveRunning], чтобы память и SharedPreferences оставались согласованными.
 */
object MockLocationStore
{
    /** Имя SharedPreferences, в котором хранится состояние фиктивного местоположения. */
    const val PREFS_NAME = "fake_gps"

    /** Ключ сохранённой широты. Значение хранится строкой для безопасного разбора `Double`. */
    const val KEY_LAT = "lat"

    /** Ключ сохранённой долготы. Значение хранится строкой для безопасного разбора `Double`. */
    const val KEY_LON = "lon"

    /** Ключ признака работающего сервиса фиктивного местоположения. */
    const val KEY_RUNNING = "running"

    /** Широта Москвы, используемая до выбора пользователем первой точки. */
    const val DEFAULT_LATITUDE = 55.7558

    /** Долгота Москвы, используемая до выбора пользователем первой точки. */
    const val DEFAULT_LONGITUDE = 37.6173

    /**
     * Текущая широта фиктивного местоположения.
     *
     * [Volatile] гарантирует, что сервис в рабочем потоке увидит значение, сохранённое из UI.
     */
    @Volatile
    var latitude: Double = DEFAULT_LATITUDE

    /**
     * Текущая долгота фиктивного местоположения.
     *
     * [Volatile] гарантирует видимость изменений между UI-потоком и сервисом.
     */
    @Volatile
    var longitude: Double = DEFAULT_LONGITUDE

    /**
     * Признак активного фиктивного местоположения.
     *
     * Значение обновляется сервисом и читается Activity, поэтому требуется [Volatile].
     */
    @Volatile
    var running: Boolean = false

    /**
     * Загружает координаты и состояние сервиса из SharedPreferences.
     *
     * Некорректные или отсутствующие координаты заменяются значениями по умолчанию. Этот метод
     * следует вызвать до первого обращения к [latitude], [longitude] и [running].
     *
     * @param context контекст, используемый для доступа к SharedPreferences.
     */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        latitude = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: DEFAULT_LATITUDE
        longitude = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: DEFAULT_LONGITUDE
        running = prefs.getBoolean(KEY_RUNNING, false)
    }

    /**
     * Сохраняет выбранные координаты в памяти и в SharedPreferences.
     *
     * Запись выполняется через `apply()`, поэтому её сохранение на диск происходит асинхронно,
     * но новые значения сразу доступны через [latitude] и [longitude].
     *
     * @param context контекст, используемый для доступа к SharedPreferences.
     * @param lat новая географическая широта.
     * @param lon новая географическая долгота.
     */
    fun savePoint(context: Context, lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_LAT, lat.toString())
                .putString(KEY_LON, lon.toString())
            }
    }

    /**
     * Сохраняет признак активности фиктивного местоположения.
     *
     * @param context контекст, используемый для доступа к SharedPreferences.
     * @param isRunning `true`, если сервис публикует фиктивные координаты; иначе `false`.
     */
    fun saveRunning(context: Context, isRunning: Boolean) {
        running = isRunning
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_RUNNING, isRunning)
            }
    }
}
