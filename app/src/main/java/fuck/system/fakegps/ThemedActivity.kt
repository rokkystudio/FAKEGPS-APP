package fuck.system.fakegps

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import fuck.system.fakegps.databinding.TopBarBinding
import java.util.Locale

/**
 * Базовая Activity с сохраняемыми настройками языка и цветовой темы.
 *
 * Выбранный язык применяется к конфигурации до создания View. Ночной режим задаётся через
 * [AppCompatDelegate], поэтому Android выбирает нужные ресурсы из `values-night` без ручного
 * обновления цветов и без конфликтов с AppCompat.
 */
abstract class ThemedActivity : AppCompatActivity() {
    /** Ключи настроек интерфейса. */
    private companion object {
        /** Имя SharedPreferences с настройками интерфейса. */
        const val PREFS_UI = "fake_gps_ui"

        /** Ключ выбранного языка интерфейса. */
        const val KEY_LANGUAGE = "language"

        /** Ключ выбранной ночной темы. */
        const val KEY_DARK_THEME = "dark_theme"
    }

    /**
     * Создаёт контекст с сохранённым языком интерфейса.
     *
     * @param newBase исходный контекст, предоставленный Android.
     */
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
        val language = prefs.getString(KEY_LANGUAGE, null)
            ?: if (Locale.getDefault().language == "ru") "ru" else "en"
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    /** Устанавливает общую тему и выбранный ночной режим до создания интерфейса Activity. */
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (isDarkTheme()) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        setTheme(R.style.Theme_GPS)
        super.onCreate(savedInstanceState)
    }

    /**
     * Настраивает общую верхнюю панель и обработчики смены языка и темы.
     *
     * @param binding привязка включённой разметки `top_bar`.
     * @param title заголовок, отображаемый на панели.
     */
    protected fun setupTopBar(binding: TopBarBinding, title: String) {
        binding.titleTextView.text = title
        binding.backButton.visibility = View.GONE
        renderTopBarButtons(binding)
        binding.themeToggleButton.setOnClickListener {
            val darkTheme = !isDarkTheme()
            preferences().edit()
                .putBoolean(KEY_DARK_THEME, darkTheme)
                .apply()
            delegate.localNightMode = if (darkTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        }
        binding.languageFlag.setOnClickListener {
            preferences().edit()
                .putString(KEY_LANGUAGE, if (currentLanguage() == "ru") "en" else "ru")
                .apply()
            recreate()
        }
    }

    /** Обновляет иконки в соответствии с активными настройками интерфейса. */
    private fun renderTopBarButtons(binding: TopBarBinding) {
        binding.themeToggleButton.setImageResource(
            if (isDarkTheme()) R.drawable.theme_moon else R.drawable.theme_sun
        )
        binding.languageFlag.setImageResource(
            if (currentLanguage() == "ru") R.drawable.flag_ru else R.drawable.flag_us
        )
    }

    /** @return SharedPreferences с настройками языка и темы. */
    private fun preferences() = getSharedPreferences(PREFS_UI, MODE_PRIVATE)

    /** @return `true`, если пользователь выбрал ночную тему. */
    protected fun isDarkTheme(): Boolean = preferences().getBoolean(KEY_DARK_THEME, false)

    /** @return текущий код языка интерфейса: `ru` или `en`. */
    private fun currentLanguage(): String = preferences().getString(KEY_LANGUAGE, null)
        ?: if (Locale.getDefault().language == "ru") "ru" else "en"
}
