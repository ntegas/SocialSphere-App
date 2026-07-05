package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import com.aistudio.socialsphere.crmlxb.model.CalendarViewMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class AppLanguage(val code: String, val displayName: String) {
    RUSSIAN("ru", "Русский"),
    ENGLISH("en", "English"),
    GREEK("el", "Ελληνικά")
}

/** Акцент-цвет приложения (бренд). Значения из макета Aurelia. */
enum class AccentColor(val key: String, val rgb: Long, val labelRes: Int) {
    MALACHITE ("malachite",  0xFF1C6B4C, com.aistudio.socialsphere.crmlxb.R.string.accent_malachite),
    SAPPHIRE  ("sapphire",   0xFF2A5DB0, com.aistudio.socialsphere.crmlxb.R.string.accent_sapphire),
    AMETHYST  ("amethyst",   0xFF7E5180, com.aistudio.socialsphere.crmlxb.R.string.accent_amethyst),
    TERRACOTTA("terracotta", 0xFFC45D34, com.aistudio.socialsphere.crmlxb.R.string.accent_terracotta)
}

// ── Persisted state helper — сохраняется при перезапуске ──────
class PersistedMutableState<T>(
    private val prefs: android.content.SharedPreferences,
    private val key: String,
    private val default: T,
    private val serialize: (T) -> String,
    private val deserialize: (String) -> T
) : MutableState<T> {
    private val state = mutableStateOf(
        prefs.getString(key, null)?.let {
            try { deserialize(it) } catch (e: Exception) { default }
        } ?: default
    )
    override var value: T
        get() = state.value
        set(v) {
            state.value = v
            prefs.edit().putString(key, serialize(v)).apply()
        }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

object AppSettings {
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    private fun getPrefs(): android.content.SharedPreferences =
        prefs ?: throw IllegalStateException("AppSettings.init() not called")

    val currentLanguage: MutableState<AppLanguage> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "language",
            default     = AppLanguage.RUSSIAN,
            serialize   = { it.code },
            deserialize = { code -> AppLanguage.values().find { it.code == code } ?: AppLanguage.RUSSIAN }
        )
    }

    val isDarkTheme: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "dark_theme",
            default     = false,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }

    /** Акцент-цвет (бренд). Персистентно. */
    val accentColor: MutableState<AccentColor> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "accent_color",
            default     = AccentColor.MALACHITE,
            serialize   = { it.key },
            deserialize = { k -> AccentColor.values().find { it.key == k } ?: AccentColor.MALACHITE }
        )
    }

    /** Безопасное чтение акцента для слоя темы (в @Preview prefs может быть не инициализирован). */
    fun accentColorSafe(): AccentColor =
        try { accentColor.value } catch (e: Exception) { AccentColor.MALACHITE }

    /** Пройден ли онбординг первого запуска. До этого показываем экран приветствия. */
    val onboardingCompleted: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "onboarding_completed",
            default     = false,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }

    val isNotificationsEnabled = mutableStateOf(true)
    val defaultReminderTime    = mutableStateOf(ReminderTime.DAY_1)
    val birthdayReminderTimes  = mutableStateOf(setOf(ReminderTime.ON_DAY, ReminderTime.DAY_1))
    val giftReminderTime       = mutableStateOf(ReminderTime.DAY_3)
    val meetingReminderTime    = mutableStateOf(ReminderTime.HOUR_1)
    val callReminderTime       = mutableStateOf(ReminderTime.MIN_10)
    val showOverdue            = mutableStateOf(true)
    val repeatOverdueVisually  = mutableStateOf(false)

    /** Биометрия/код устройства для показа защищённых записей («Защищено»).
     *  При включении карточка контакта стартует с включённым режимом приватности,
     *  а снятие замочка требует BiometricPrompt. Персистентно. */
    val biometricLock: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "biometric_lock",
            default     = false,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }

    /** Безопасное чтение (в @Preview prefs может не быть). */
    fun biometricLockSafe(): Boolean =
        try { biometricLock.value } catch (e: Exception) { false }

    /** Ежедневное напоминание «пора связаться» (по ритму общения). Персистентно. */
    val remindStaleContacts: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "remind_stale_contacts",
            default     = true,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }

    /** Независимый ежедневный пуш «сегодня день рождения» (не зависит от того,
     *  добавлено ли напоминание к событию). Персистентно. */
    val remindBirthdays: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "remind_birthdays", default = true,
            serialize = { it.toString() }, deserialize = { it == "true" }
        )
    }

    /** Ежедневная сводка «контакты без следующего шага». По умолчанию выкл. */
    val remindNoNextStep: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "remind_no_next_step", default = false,
            serialize = { it.toString() }, deserialize = { it == "true" }
        )
    }

    // ── Календарь (персистентно) ──
    val calendarDefaultMode: MutableState<CalendarViewMode> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "calendar_default_mode",
            default     = CalendarViewMode.LIST,
            serialize   = { it.name },
            // Миграция легаси-значений на русском (до перехода на enum) +
            // запасной вариант для будущих имён enum
            deserialize = { raw ->
                when (raw) {
                    // Легаси «Сегодня»/TODAY больше нет — сводим к «Ленте» (LIST).
                    "Сегодня", "TODAY" -> CalendarViewMode.LIST
                    "Список"  -> CalendarViewMode.LIST
                    "Неделя"  -> CalendarViewMode.WEEK
                    "Месяц"   -> CalendarViewMode.MONTH
                    else -> try { CalendarViewMode.valueOf(raw) } catch (e: Exception) { CalendarViewMode.LIST }
                }
            }
        )
    }

    /** Понедельник — первый день недели в сетке месяца. */
    val calendarFirstDayMonday: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "calendar_first_day_monday",
            default     = true,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }

    /** Имена CalendarItemType, скрытые в календаре и «Ближайшем» (CSV). */
    val calendarHiddenTypes: MutableState<Set<String>> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "calendar_hidden_types",
            default     = emptySet(),
            serialize   = { it.joinToString(",") },
            deserialize = { raw -> raw.split(",").filter { it.isNotBlank() }.toSet() }
        )
    }

    /** Свои цвета типов событий (фидбэк 2026-07-04: «хочу менять цвета годовщины,
     *  важных дат, встреч»). Ключ — CalendarItemType.name, значение — packed ARGB
     *  (см. toArgb() из androidx.compose.ui.graphics). Тип без записи здесь =
     *  встроенный цвет по умолчанию. */
    val calendarTypeColors: MutableState<Map<String, Int>> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "calendar_type_colors",
            default     = emptyMap(),
            serialize   = { map -> map.entries.joinToString(";") { "${it.key}:${it.value}" } },
            deserialize = { raw ->
                raw.split(";").filter { it.isNotBlank() }.mapNotNull { pair ->
                    val idx = pair.indexOf(':')
                    if (idx < 0) return@mapNotNull null
                    val v = pair.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
                    pair.substring(0, idx) to v
                }.toMap()
            }
        )
    }
}

@Composable
fun LocalizedApp(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val locale  = Locale(language.code)

    Locale.setDefault(locale)

    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    val localizedContext = context.createConfigurationContext(config)

    // FIX: раньше эта устаревшая глобальная мутация выполнялась В ТЕЛЕ
    // композабла — то есть при КАЖДОЙ рекомпозиции (не только при смене языка).
    // Мутация глобального Resources() как побочный эффект вне SideEffect —
    // источник как раз того типа гонки, что мог давать «где-то греческий текст,
    // хотя язык — русский»: сложно гарантировать порядок относительно чтения
    // ресурсов другими композаблами. Теперь выполняется одноразово на вход
    // в композицию (родитель уже оборачивает в key(language), так что это и
    // так происходит ровно при смене языка, не чаще).
    androidx.compose.runtime.SideEffect {
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    // Сохраняем ActivityResultRegistryOwner чтобы не терялся при смене языка
    val activityResultRegistry = androidx.activity.compose.LocalActivityResultRegistryOwner.current

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides config,
        // Явно передаём ActivityResultRegistryOwner — иначе краш в MapScreen и ImportScreens
        *if (activityResultRegistry != null)
            arrayOf(androidx.activity.compose.LocalActivityResultRegistryOwner provides activityResultRegistry)
        else
            emptyArray()
    ) {
        content()
    }
}
