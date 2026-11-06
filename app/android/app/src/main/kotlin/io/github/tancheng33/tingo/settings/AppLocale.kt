package io.github.tancheng33.tingo.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言切换。
 *
 * 不用 AppCompatDelegate / LocaleManager：前者要求依赖 appcompat 并改用 AppCompatActivity，
 * 后者只在 API 33+ 可用，而本项目 minSdk 26。这里用最朴素也最可控的方式：
 * 把语言写进 Context 的配置，Activity 在 `attachBaseContext` 时套用。
 */
object AppLocale {
    /** 把语言注入 Context 的配置，返回套用后的 Context。 */
    fun wrap(base: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.tag)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(config)
    }
}
