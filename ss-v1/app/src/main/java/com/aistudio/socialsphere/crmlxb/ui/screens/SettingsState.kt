package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class AppLanguage(val code: String, val displayName: String) {
    RUSSIAN("ru", "Русский"),
    ENGLISH("en", "English"),
    GREEK("el", "Ελληνικά")
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

    val isNotificationsEnabled = mutableStateOf(true)
    val defaultReminderTime    = mutableStateOf(ReminderTime.DAY_1)
    val birthdayReminderTimes  = mutableStateOf(setOf(ReminderTime.ON_DAY, ReminderTime.DAY_1))
    val giftReminderTime       = mutableStateOf(ReminderTime.DAY_3)
    val meetingReminderTime    = mutableStateOf(ReminderTime.HOUR_1)
    val callReminderTime       = mutableStateOf(ReminderTime.MIN_10)
    val showOverdue            = mutableStateOf(true)
    val repeatOverdueVisually  = mutableStateOf(false)

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
                    "Сегодня" -> CalendarViewMode.TODAY
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

    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)

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
