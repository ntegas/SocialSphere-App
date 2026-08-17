package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import com.aistudio.socialsphere.crmlxb.model.CalendarViewMode
import com.aistudio.socialsphere.crmlxb.utils.ContactSortField
import com.aistudio.socialsphere.crmlxb.utils.ContactNameFormat

// Добавить язык = один новый пункт enum + новая папка values-xx/strings.xml —
// badge используется в LanguageSettingsScreen вместо отдельного when-блока.
enum class AppLanguage(val code: String, val displayName: String, val badge: String) {
    ENGLISH("en", "English", "En"),
    RUSSIAN("ru", "Русский", "Ru"),
    GREEK("el", "Ελληνικά", "Ελ")
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

    /** Язык системы на МОМЕНТ первого запуска (до заведения currentLanguage —
     *  см. ниже). Захватывается из applicationContext в init(). Английский —
     *  базовый/фолбэк язык приложения (values/ без квалификатора хранит
     *  английские строки, 2026-07-22); если системный язык не входит в число
     *  поддерживаемых — используется английский, а не русский. */
    private var systemLanguageAtInit: AppLanguage = AppLanguage.ENGLISH

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val sysCode = context.resources.configuration.locales.get(0).language
        systemLanguageAtInit = AppLanguage.values().find { it.code == sysCode } ?: AppLanguage.ENGLISH

        // Тур первого запуска (2026-07-27) добавлен ПОСЛЕ того, как у владельца уже
        // были установки приложения. Ключ "onboarding_completed" отсутствует и у
        // настоящих новых установок, И у тех, кто просто ОБНОВИЛСЯ со старой версии
        // (Android обновление не создаёт новый app_settings) — по умолчанию оба
        // случая читались бы как false и тур показался бы существующим
        // пользователям заново. Различаем через firstInstallTime/lastUpdateTime
        // (у настоящей чистой установки они совпадают, апдейт всегда увеличивает
        // lastUpdateTime) — если это апдейт, отмечаем тур как уже пройденный ДО
        // того как onboardingCompleted первый раз прочитает свой дефолт.
        val p = getPrefs()
        if (!p.contains("onboarding_completed")) {
            val isUpdate = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.lastUpdateTime != info.firstInstallTime
            } catch (e: Exception) { false }
            if (isUpdate) p.edit().putString("onboarding_completed", "true").apply()
        }
    }

    private fun getPrefs(): android.content.SharedPreferences =
        prefs ?: throw IllegalStateException("AppSettings.init() not called")

    /** Язык приложения. При первом запуске (нет сохранённого выбора) подставляется
     *  язык устройства из поддерживаемых, иначе — английский (базовый). Дальше
     *  пользователь может сменить в Настройках — выбор персистентный и не
     *  переопределяется системным языком повторно. */
    val currentLanguage: MutableState<AppLanguage> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "language",
            default     = systemLanguageAtInit,
            serialize   = { it.code },
            deserialize = { code -> AppLanguage.values().find { it.code == code } ?: AppLanguage.ENGLISH }
        )
    }

    /** Единая точка смены языка — обновляет персистентный выбор И реальную
     *  локаль приложения через AppCompatDelegate (сама пересоздаёт все Activity
     *  с новой Configuration; ручное createConfigurationContext-оборачивание
     *  Compose-дерева, из-за которого раньше «путался» язык при переключении,
     *  больше не нужно — 2026-07-22). */
    fun setLanguage(lang: AppLanguage) {
        currentLanguage.value = lang
        applyLocale(lang)
    }

    /** Применить текущую персистентную локаль к AppCompatDelegate — вызывается
     *  из MainActivity.onCreate ДО super.onCreate(), чтобы холодный старт сразу
     *  шёл на сохранённом языке (AppCompat также восстанавливает это сам через
     *  AppLocalesMetadataHolderService, вызов здесь — идемпотентная страховка). */
    fun applyLocale(lang: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang.code))
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

    /** Дефолтный набор тегов (v18, 2026-07-28) уже засеян — ровно один раз за
     *  всю жизнь установки (см. AppStateStore.seedDefaultTagsIfNeeded). Не
     *  «теги есть» — именно «сидирование уже пробовали», иначе разово удалённые
     *  дефолтные теги владельцем появлялись бы обратно при следующем холодном старте. */
    val defaultTagsSeeded: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "default_tags_seeded",
            default     = false,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }

    // Раньше это были простые mutableStateOf — не персистились, и выключенное
    // «Включить уведомления» молча возвращалось в true после перезапуска
    // процесса/перезагрузки телефона (BootReceiver.rescheduleAll() ре-армил
    // все напоминания, хотя владелец их явно отключил). Баг §35, найден
    // повторным аудитом — приведено к тому же паттерну PersistedMutableState,
    // что и остальные настройки в этом файле.
    val isNotificationsEnabled: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "notifications_enabled",
            default     = true,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }
    val defaultReminderTime: MutableState<ReminderTime> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "default_reminder_time",
            default     = ReminderTime.DAY_1,
            serialize   = { it.name },
            deserialize = { n -> ReminderTime.values().find { it.name == n } ?: ReminderTime.DAY_1 }
        )
    }
    val birthdayReminderTimes: MutableState<Set<ReminderTime>> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "birthday_reminder_times",
            default     = setOf(ReminderTime.ON_DAY, ReminderTime.DAY_1),
            serialize   = { it.joinToString(",") { r -> r.name } },
            deserialize = { s -> s.split(",").filter { it.isNotBlank() }
                .mapNotNull { n -> ReminderTime.values().find { it.name == n } }.toSet() }
        )
    }
    val giftReminderTime: MutableState<ReminderTime> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "gift_reminder_time",
            default     = ReminderTime.DAY_3,
            serialize   = { it.name },
            deserialize = { n -> ReminderTime.values().find { it.name == n } ?: ReminderTime.DAY_3 }
        )
    }
    val meetingReminderTime: MutableState<ReminderTime> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "meeting_reminder_time",
            default     = ReminderTime.HOUR_1,
            serialize   = { it.name },
            deserialize = { n -> ReminderTime.values().find { it.name == n } ?: ReminderTime.HOUR_1 }
        )
    }
    val callReminderTime: MutableState<ReminderTime> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "call_reminder_time",
            default     = ReminderTime.MIN_10,
            serialize   = { it.name },
            deserialize = { n -> ReminderTime.values().find { it.name == n } ?: ReminderTime.MIN_10 }
        )
    }
    val showOverdue: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "show_overdue",
            default     = true,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }
    val repeatOverdueVisually: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "repeat_overdue_visually",
            default     = false,
            serialize   = { it.toString() },
            deserialize = { it == "true" }
        )
    }

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

    // ── Свой PIN-код + блокировка всего приложения (2026-07-05) ──────────────
    // Раньше единственной опцией была биометрия/код УСТРОЙСТВА — если на
    // телефоне не настроен отпечаток/лицо/код экрана, защиты не было вообще,
    // и никакого экрана блокировки на запуске приложения не существовало.
    // Свой PIN не зависит от системных настроек устройства. Храним НЕ пароль,
    // а соль+хеш (PBKDF2WithHmacSHA256, 120k итераций) — исходный PIN нигде
    // не сохраняется, чистый javax.crypto без новой зависимости (среда иногда
    // офлайн для новых библиотек, см. §9 базы знаний).
    private const val PIN_ITERATIONS = 120_000
    private const val PIN_KEY_LENGTH = 256

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_KEY_LENGTH)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** "saltHex:hashHex", пусто = PIN не задан. Персистентно. */
    private val pinHashRaw: MutableState<String> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "app_pin_hash", default = "",
            serialize = { it }, deserialize = { it }
        )
    }

    /** ФИКС (2026-07-08, владелец: «показываешь 6 точек, хотя ввожу 4»): раньше
     *  экраны ввода PIN всегда рисовали максимум (6) точек-плейсхолдеров,
     *  потому что нигде не хранилась РЕАЛЬНАЯ длина заданного PIN — только
     *  соль+хеш, из которых длину не восстановить. Теперь сохраняем длину
     *  отдельно при setPin(), и экраны разблокировки/раскрытия рисуют ровно
     *  столько точек, сколько владелец реально ввёл при настройке. */
    private val pinLength: MutableState<Int> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "app_pin_length", default = 0,
            serialize = { it.toString() }, deserialize = { it.toIntOrNull() ?: 0 }
        )
    }

    /** Реальная длина текущего PIN (0, если не задан) — для рендера точек. */
    fun currentPinLength(): Int = try { pinLength.value } catch (e: Exception) { 0 }

    fun hasPinSet(): Boolean = try { pinHashRaw.value.isNotBlank() } catch (e: Exception) { false }

    /** Задать/сменить PIN (4-6 цифр — длину проверяет UI). Сбрасывает счётчик неудачных попыток. */
    fun setPin(pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        pinHashRaw.value = "${bytesToHex(salt)}:${bytesToHex(hash)}"
        pinLength.value = pin.length
        pinFailCount.value = 0
        pinLockedUntil.value = 0L
    }

    fun clearPin() {
        pinHashRaw.value = ""
        pinLength.value = 0
        pinFailCount.value = 0
        pinLockedUntil.value = 0L
    }

    // ── Защита от подбора PIN тапами по экрану (2026-07-06) ───────────────
    // Раньше verifyPin() не ограничивал число попыток вообще — 4-значный PIN
    // подбирается за конечное число тапов без всякого троттлинга. Персистентно
    // (не в памяти), чтобы простой перезапуск процесса не сбрасывал блокировку.
    private val pinFailCount: MutableState<Int> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "pin_fail_count", default = 0,
            serialize = { it.toString() }, deserialize = { it.toIntOrNull() ?: 0 }
        )
    }
    private val pinLockedUntil: MutableState<Long> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "pin_locked_until", default = 0L,
            serialize = { it.toString() }, deserialize = { it.toLongOrNull() ?: 0L }
        )
    }

    /** Эскалация: первые 4 попытки бесплатны, дальше — растущая блокировка. */
    private fun pinLockoutDurationMs(failCount: Int): Long = when {
        failCount < 5 -> 0L
        failCount < 7 -> 30_000L
        failCount < 9 -> 60_000L
        else -> 300_000L
    }

    /** Сколько ещё мс осталось до конца блокировки (0 = можно пробовать).
     *  ФИКС (глубокий аудит 2026-07-06): раньше `pinLockedUntil` считался от
     *  `System.currentTimeMillis()` (настенные часы) — владелец мог в системных
     *  Настройках перевести дату/время вперёд и снять блокировку от подбора PIN
     *  мгновенно, без ожидания. `SystemClock.elapsedRealtime()` — монотонные
     *  часы с момента загрузки устройства, не подвержены смене даты/времени
     *  пользователем (сбрасываются только при перезагрузке — что само по себе
     *  куда менее тривиальное действие, и просто снимает блокировку раньше
     *  срока, а не открывает дыру). */
    fun pinLockRemainingMs(): Long {
        val until = try { pinLockedUntil.value } catch (e: Exception) { 0L }
        val remaining = until - android.os.SystemClock.elapsedRealtime()
        return if (remaining > 0) remaining else 0L
    }

    /** Сверка введённого PIN с сохранённым хешем. false и при отсутствии PIN
     *  или активной блокировке — при блокировке попытка НЕ считается (иначе
     *  блокировка продлевалась бы бесконечно от одних лишь попыток набора). */
    fun verifyPin(pin: String): Boolean {
        val raw = try { pinHashRaw.value } catch (e: Exception) { "" }
        if (raw.isBlank()) return false
        if (pinLockRemainingMs() > 0) return false
        val parts = raw.split(":")
        if (parts.size != 2) return false
        val ok = try {
            val salt = hexToBytes(parts[0])
            val expected = hexToBytes(parts[1])
            pbkdf2(pin, salt).contentEquals(expected)
        } catch (e: Exception) { false }
        if (ok) {
            pinFailCount.value = 0
            pinLockedUntil.value = 0L
        } else {
            val fails = (try { pinFailCount.value } catch (e: Exception) { 0 }) + 1
            pinFailCount.value = fails
            val lockMs = pinLockoutDurationMs(fails)
            if (lockMs > 0) pinLockedUntil.value = android.os.SystemClock.elapsedRealtime() + lockMs
        }
        return ok
    }

    /** Блокировка ВСЕГО приложения на запуске/возврате из фона — отдельно от
     *  «биометрия для защищённых заметок» (biometricLock выше). Требует PIN
     *  ИЛИ биометрию — включать можно только если задан хотя бы один способ
     *  разблокировки (проверяется в UI перед включением). */
    val appLockEnabled: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "app_lock_enabled", default = false,
            serialize = { it.toString() }, deserialize = { it == "true" }
        )
    }

    fun appLockEnabledSafe(): Boolean =
        try { appLockEnabled.value } catch (e: Exception) { false }

    // ── Freemium / подписка Socialsphere Pro (2026-08) ────────
    // Модель по прямому решению владельца (2026-08-16): ТОЛЬКО подписка, без
    // разовой покупки (более ранний вариант с раздельными Pro/Pro+ отменён).
    // 9€/мес или 7€/мес при годовой оплате. Статус читает BillingManager
    // (utils/BillingManager.kt) после покупки или queryPurchasesAsync() при
    // холодном старте — сам AppSettings ничего не знает о BillingClient,
    // только хранит итоговый флаг (тот же принцип, что и остальные настройки
    // в этом файле).

    /** Активная подписка Socialsphere Pro — снимает все 3 лимита ниже. */
    val subscriptionActive: MutableState<Boolean> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "subscription_active", default = false,
            serialize = { it.toString() }, deserialize = { it == "true" }
        )
    }

    /** Epoch millis окончания текущего периода подписки — для отображения даты
     *  продления/грейс-периода. 0 = нет активной подписки. */
    val subscriptionExpiryEpoch: MutableState<Long> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "subscription_expiry", default = 0L,
            serialize = { it.toString() }, deserialize = { it.toLongOrNull() ?: 0L }
        )
    }

    /** Пока BillingManager.restorePurchases() ещё не ответил (например, только
     *  что холодный старт после переустановки) — НЕ считаем пользователя
     *  бесплатным по умолчанию: легитимный подписчик иначе на долю секунды
     *  увидел бы paywall и мог бы зря потратить единицу из бесплатной квоты,
     *  пока статус ещё не восстановлен. Экраны-гейты должны ждать true здесь
     *  перед тем, как решать, показывать ли PaywallSheet. НЕ персистентно —
     *  сбрасывается в false при каждом старте процесса намеренно.
     *  ФИКС (2026-08-16, риск найден при планировании freemium): гонка
     *  восстановления покупок при холодном старте. */
    var entitlementResolved: MutableState<Boolean> = mutableStateOf(false)

    fun isPremium(): Boolean = subscriptionActive.value

    // Общий приём для обоих месячных лимитов ниже: лениво сбрасываемый по
    // календарному месяцу счётчик (не AlarmManager/WorkManager — в проекте
    // нет готовой инфраструктуры периодических задач, изобретать её ради
    // счётчиков избыточно). В отличие от pinLockedUntil
    // (SystemClock.elapsedRealtime(), не подвержено переводу даты) — здесь
    // обычные календарные месяцы; перевод даты назад технически даёт лишние
    // бесплатные единицы. Осознанный компромисс: цена ошибки низкая,
    // сложность защиты (серверное время) не оправдана.
    // ВАЖНО: used/monthKey передаются как УЖЕ СУЩЕСТВУЮЩИЕ lazy-инстансы
    // PersistedMutableState (а не создаются заново из ключей внутри) — иначе
    // Compose не подхватит recomposition: новый PersistedMutableState на
    // каждый вызов означает новый State-инстанс, на который никто не
    // подписан, и экран не перерисуется, когда квота изменится.
    // internal (не private) — виден AppSettingsSubscriptionTest напрямую, с
    // локально сконструированными mutableStateOf вместо кэшированных lazy-полей
    // AppSettings: PersistedMutableState читает SharedPreferences ОДИН раз при
    // конструировании (см. класс выше) и никогда не перечитывает на value-гет —
    // подменить сохранённый месяц «под капотом» у уже сконструированного
    // AppSettings.scanQuotaMonthKey из теста невозможно (это не баг прод-кода,
    // это следствие того, что реальный rollover происходит между ЗАПУСКАМИ
    // процесса, а не в рамках одного). Тестируем эту функцию саму по себе.
    internal fun monthlyRemaining(used: MutableState<Int>, monthKey: MutableState<String>, quota: Int): Int {
        if (isPremium()) return Int.MAX_VALUE
        val nowKey = java.time.YearMonth.now().toString()
        if (monthKey.value != nowKey) { monthKey.value = nowKey; used.value = 0 }
        return (quota - used.value).coerceAtLeast(0)
    }

    internal fun monthlyRecordUsed(used: MutableState<Int>, monthKey: MutableState<String>, quota: Int) {
        if (isPremium()) return
        monthlyRemaining(used, monthKey, quota) // форсирует rollover, если новый месяц
        used.value += 1
    }

    private const val FREE_SCAN_QUOTA = 4
    private val scansUsedThisMonth: MutableState<Int> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "scans_used_month", default = 0,
            serialize = { it.toString() }, deserialize = { it.toIntOrNull() ?: 0 }
        )
    }
    private val scanQuotaMonthKey: MutableState<String> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "scan_quota_month_key", default = "",
            serialize = { it }, deserialize = { it }
        )
    }

    /** Сколько сканов визитки осталось в текущем календарном месяце у
     *  бесплатного пользователя. */
    fun scansRemainingThisMonth(): Int =
        monthlyRemaining(scansUsedThisMonth, scanQuotaMonthKey, FREE_SCAN_QUOTA)

    /** Списывает один скан из бесплатной квоты. Звать ДО запуска OCR
     *  (оптимистично), не после — распознавание занимает несколько секунд,
     *  и списание по завершении позволило бы получить бесплатный скан,
     *  убив процесс приложения посреди распознавания. Для подписчиков — no-op. */
    fun recordScanUsed() = monthlyRecordUsed(scansUsedThisMonth, scanQuotaMonthKey, FREE_SCAN_QUOTA)

    private const val FREE_CONTACT_WITH_PHONE_QUOTA = 3
    private val contactsWithPhoneUsedThisMonth: MutableState<Int> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "contacts_with_phone_used_month", default = 0,
            serialize = { it.toString() }, deserialize = { it.toIntOrNull() ?: 0 }
        )
    }
    private val contactsWithPhoneMonthKey: MutableState<String> by lazy {
        PersistedMutableState(
            prefs = getPrefs(), key = "contacts_with_phone_month_key", default = "",
            serialize = { it }, deserialize = { it }
        )
    }

    /** Сколько новых контактов С ТЕЛЕФОНОМ ещё можно сохранить в этом месяце
     *  бесплатно (владелец, 2026-08-16: «сохранение контакта с телефоном —
     *  3 бесплатных в месяц»). Контакты без единого номера телефона в квоту
     *  не входят — гейт только на «контакт с телефоном» буквально. */
    fun contactsWithPhoneRemainingThisMonth(): Int =
        monthlyRemaining(contactsWithPhoneUsedThisMonth, contactsWithPhoneMonthKey, FREE_CONTACT_WITH_PHONE_QUOTA)

    /** Списывает один контакт из бесплатной квоты. Звать при КАЖДОМ успешном
     *  создании контакта с непустым списком телефонов — вручную (ContactEditScreen)
     *  или через любой из путей импорта (ImportScreens.performImport/mergeCandidate),
     *  все они делят одну и ту же квоту. */
    fun recordContactWithPhoneUsed() =
        monthlyRecordUsed(contactsWithPhoneUsedThisMonth, contactsWithPhoneMonthKey, FREE_CONTACT_WITH_PHONE_QUOTA)

    /** Карта (владелец, 2026-08-16: «карта 5 точек бесплатно») — НЕ месячная
     *  квота, а постоянный потолок числа меток для бесплатного тарифа: видно
     *  первые 5 контактов на карте, остальное — за подпиской. Простой предел,
     *  без rollover-логики. */
    const val FREE_MAP_MARKER_LIMIT = 5

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

    // ── ContactDisplayPreferences (2026-07-11, как в Android-контактах) ──
    // Sort by и Name format — раздельные персистентные настройки (см. ContactSortField/
    // ContactNameFormat в SearchEngine.kt): сортировать можно по фамилии, при этом
    // по-прежнему показывать «Имя Фамилия» — это ДВЕ независимые оси, не одна.
    val contactSortField: MutableState<ContactSortField> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "contact_sort_field",
            default     = ContactSortField.FIRST_NAME,
            serialize   = { it.name },
            deserialize = { raw -> try { ContactSortField.valueOf(raw) } catch (e: Exception) { ContactSortField.FIRST_NAME } }
        )
    }

    val contactNameFormat: MutableState<ContactNameFormat> by lazy {
        PersistedMutableState(
            prefs       = getPrefs(),
            key         = "contact_name_format",
            default     = ContactNameFormat.FIRST_NAME_FIRST,
            serialize   = { it.name },
            deserialize = { raw -> try { ContactNameFormat.valueOf(raw) } catch (e: Exception) { ContactNameFormat.FIRST_NAME_FIRST } }
        )
    }

    /** Безопасное чтение (в @Preview prefs может не быть). */
    fun contactSortFieldSafe(): ContactSortField =
        try { contactSortField.value } catch (e: Exception) { ContactSortField.FIRST_NAME }

    fun contactNameFormatSafe(): ContactNameFormat =
        try { contactNameFormat.value } catch (e: Exception) { ContactNameFormat.FIRST_NAME_FIRST }

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
