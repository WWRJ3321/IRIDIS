package com.iris.localai

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/**
 * 2.3.1 多语言界面. App UI strings live in res/values-xx/strings.xml; code reads them through [s] so
 * view models, notifications and object constants (which have no Composable scope) work too.
 *
 * Language choice: Android 13+ uses the system per-app language (LocaleManager, also shown in
 * system Settings › Apps › Language). Android 12 keeps the tag in prefs and wraps the app /
 * activity contexts; the activity is recreated on change either way, so every screen re-reads.
 *
 * Adding a language = one values-xx/strings.xml + one [LANGS] entry + one line in
 * res/xml/locales_config.xml (I18nResourcesTest checks the three agree).
 */
object I18n {
    /** tag → native name; "" = 跟随系统. Order = picker order. */
    val LANGS: List<Pair<String, String>> = listOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
        "de" to "Deutsch",
        "es" to "Español",
        "hi" to "हिन्दी",
    )

    private const val PREFS = "iris_i18n"
    private const val KEY = "app_locale"
    private lateinit var app: Context
    @Volatile private var override: Resources? = null

    fun init(context: Context) {
        app = context.applicationContext
        if (Build.VERSION.SDK_INT < 33) override = stored(app).takeIf { it.isNotEmpty() }?.let { localized(app, it).resources }
    }

    private val res: Resources get() = override ?: app.resources

    fun s(id: Int): String = if (::app.isInitialized) res.getString(id) else jvm(id)
    fun s(id: Int, vararg args: Any?): String =
        if (::app.isInitialized) res.getString(id, *args) else String.format(jvm(id), *args)

    /** Plain-JVM unit tests only (no Application): 简中 text straight from res/values/strings.xml. */
    private val jvmTable: Map<Int, String> by lazy {
        val f = listOf("app/src/main/res/values/strings.xml", "src/main/res/values/strings.xml").map { java.io.File(it) }.firstOrNull { it.isFile }
            ?: return@lazy emptyMap()
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        val byName = (0 until nodes.length).map { nodes.item(it) }.associate {
            it.attributes.getNamedItem("name").nodeValue to it.textContent.replace("\\n", "\n").replace("\\u0020", " ")
                .replace("\\'", "'").replace("\\\"", "\"")
        }
        R.string::class.java.fields.mapNotNull { fld -> byName[fld.name]?.let { fld.getInt(null) to it } }.toMap()
    }
    private fun jvm(id: Int) = jvmTable[id] ?: ""

    /** The chosen tag, "" when following the system. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
                ?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()?.let(::normalize) ?: ""
        } else stored(context)

    /** Apply [tag] ("" = system). Android 13+: the system recreates activities itself. */
    fun set(activity: android.app.Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            init(activity)
            activity.recreate()
        }
    }

    /** For Activity.attachBaseContext on Android 12 (13+ handles it in the framework). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base)
        return if (tag.isEmpty()) base else localized(base, tag)
    }

    /** Effective UI language as the resources resolved it: zh-CN, zh-TW, en, ja, de, es, hi. */
    fun uiLang(): String = if (::app.isInitialized) s(R.string.lang_code) else "zh-CN"

    private fun stored(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    private fun localized(c: Context, tag: String): Context {
        val conf = Configuration(c.resources.configuration)
        conf.setLocales(LocaleList.forLanguageTags(tag))
        return c.createConfigurationContext(conf)
    }

    /** zh-Hant-*, zh-HK, zh-MO → zh-TW; zh-Hans-* → zh-CN (picker check marks). */
    fun normalize(tag: String): String = I18nPure.normalize(tag)
}

/** Pure parts (unit-tested). */
object I18nPure {
    fun normalize(tag: String): String {
        val t = tag.replace('_', '-')
        val l = t.lowercase()
        return when {
            l.startsWith("zh") && (l.contains("hant") || l.endsWith("-tw") || l.endsWith("-hk") || l.endsWith("-mo")) -> "zh-TW"
            l.startsWith("zh") -> "zh-CN"
            else -> t.substringBefore('-')
        }
    }

    private val NAMES = mapOf(
        "en" to "English", "ja" to "Japanese", "de" to "German", "es" to "Spanish", "hi" to "Hindi",
    )

    /** One system-prompt line asking for replies in the UI language; null for a Chinese UI (简/繁). */
    fun replyLine(uiLang: String): String? = NAMES[uiLang]?.let {
        "By default, reply in $it (the user's app language), unless the user writes in another language — then reply in the user's language."
    }

    fun withReplyLine(system: String, uiLang: String): String {
        val line = replyLine(uiLang) ?: return system
        return if (system.isBlank()) line else system.trimEnd() + "\n\n" + line
    }
}
