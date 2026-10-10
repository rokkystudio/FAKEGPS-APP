package fuck.system.fakegps

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rokkystudio.sharedapp.neoui.NeoThemeMode
import com.rokkystudio.sharedapp.neoui.NeoUiSettings
import fuck.system.fakegps.databinding.TopBarBinding

/**
 * Базовая Activity приложения с общей настройкой темы и верхней панели Neo UI.
 *
 * Activity применяет выбранную пользователем тему до создания интерфейса и предоставляет общий
 * метод настройки верхней панели с названием приложения, второй строкой и текущей версией сборки.
 */
abstract class ThemedActivity : AppCompatActivity() {
    /**
     * Подготавливает конфигурацию контекста с локалью и темой из настроек Neo UI.
     *
     * @param newBase исходный контекст Activity.
     */
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration)
        neoUiSettings(newBase).applyToConfiguration(configuration)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    /**
     * Применяет AppCompat-тему и продолжает создание Activity.
     *
     * @param savedInstanceState состояние Activity, сохранённое перед пересозданием, или `null`.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = neoUiSettings(this).appCompatNightMode()
        setTheme(R.style.Theme_GPS)
        super.onCreate(savedInstanceState)
    }

    /**
     * Настраивает верхнюю панель Neo UI для экрана приложения.
     *
     * Панель получает основное название, вторую строку с назначением приложения, метку версии и
     * обработчики переключения темы. Данные передаются в штатные поля [NeoTopBarView].
     *
     * @param binding привязка верхней панели.
     * @param title заголовок текущего экрана.
     */
    protected fun setupTopBar(binding: TopBarBinding, title: String) {
        val settings = neoUiSettings(this)
        binding.neoTopBar.setAppInfo(
            title = title,
            subtitle = getString(R.string.app_subtitle),
            version = appVersionName(),
            iconResId = R.mipmap.ic_launcher,
        )
        settings.bindTopBar(binding.neoTopBar) {
            delegate.localNightMode = settings.appCompatNightMode()
            recreate()
        }
    }

    /** @return `true`, если активна тёмная тема Neo UI. */
    protected fun isDarkTheme(): Boolean = neoUiSettings(this).getTheme() == NeoThemeMode.DARK

    /**
     * Возвращает имя версии текущего APK.
     *
     * Значение берётся из package info, которое Android формирует из Gradle-полей `versionName` и
     * `versionCode`.
     *
     * @return строка версии приложения.
     */
    private fun appVersionName(): String
    {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return packageInfo.versionName ?: ""
    }

    /**
     * Создаёт объект настроек Neo UI и выполняет перенос старого ключа темы.
     *
     * @param context контекст, из которого читаются SharedPreferences.
     * @return настройки Neo UI для текущего приложения.
     */
    private fun neoUiSettings(context: Context): NeoUiSettings {
        migrateLegacyUiSettings(context)
        return NeoUiSettings(
            context = context,
            preferencesName = PREFS_UI,
            defaultTheme = NeoThemeMode.LIGHT,
            defaultLanguageTag = null,
        )
    }

    /**
     * Переносит старый булевый ключ тёмной темы в строковый ключ Neo UI.
     *
     * @param context контекст, из которого читаются SharedPreferences.
     */
    private fun migrateLegacyUiSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        if (!prefs.contains(KEY_THEME) && prefs.contains(KEY_DARK_THEME)) {
            val theme = if (prefs.getBoolean(KEY_DARK_THEME, false)) {
                NeoThemeMode.DARK.preferenceValue
            } else {
                NeoThemeMode.LIGHT.preferenceValue
            }
            prefs.edit().putString(KEY_THEME, theme).apply()
        }
    }

    /** Константы SharedPreferences для настроек интерфейса. */
    private companion object {
        /** Имя файла настроек интерфейса. */
        const val PREFS_UI = "fake_gps_ui"

        /** Старый ключ тёмной темы. */
        const val KEY_DARK_THEME = "dark_theme"

        /** Ключ темы Neo UI. */
        const val KEY_THEME = "theme"
    }
}
