
---

## 🆕 Раздел 7 — Правила из Google AI Studio (реальные баги при запуске)

### 7.1 Google Maps — обязательная проверка перед инициализацией

```kotlin
// ❌ НЕЛЬЗЯ — краш если нет Google Play Services
GoogleMap(...)

// ✅ ПРАВИЛЬНО — сначала проверить доступность
val isAvailable = GoogleApiAvailability.getInstance()
    .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

if (isAvailable) {
    GoogleMap(...)
} else {
    // Показать заглушку с сообщением
    Text("Карта недоступна — требуется обновление Google Play Services")
}
```

### 7.2 Анимация камеры — обязательный try-catch

```kotlin
// ❌ НЕЛЬЗЯ — краш если карта ещё не загружена
cameraState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15f))

// ✅ ПРАВИЛЬНО
try {
    cameraState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
} catch (e: Exception) {
    // карта ещё не готова — игнорируем
}
```

### 7.3 Запуск внешних приложений — Android 11+ политика

```kotlin
// ❌ НЕЛЬЗЯ — resolveActivity() возвращает null на Android 11+
if (intent.resolveActivity(context.packageManager) != null) {
    context.startActivity(intent)
}

// ✅ ПРАВИЛЬНО — startActivity напрямую в try-catch
try {
    context.startActivity(intent)
} catch (e: ActivityNotFoundException) {
    Toast.makeText(context, "Приложение не найдено", Toast.LENGTH_SHORT).show()
}
```

### 7.4 Локализация — запрет хардкода строк

```kotlin
// ❌ НЕЛЬЗЯ — хардкод, язык не меняется
Text("Актуальные контакты")
Text("Настройки")

// ✅ ПРАВИЛЬНО — через ресурсы
Text(stringResource(R.string.home_active_contacts))
Text(stringResource(R.string.settings_title))
```

### 7.5 CompositionLocalProvider — передавать ОБА провайдера

```kotlin
// ❌ НЕЛЬЗЯ — stringResource() не обновляется без LocalConfiguration
CompositionLocalProvider(LocalContext provides localizedContext) {
    content()
}

// ✅ ПРАВИЛЬНО — передавать оба
CompositionLocalProvider(
    LocalContext provides localizedContext,
    LocalConfiguration provides config
) {
    content()
}
```

### 7.6 Настройки — хранить на диске, не в памяти

```kotlin
// ❌ НЕЛЬЗЯ — сбрасывается при перезапуске
val currentLanguage = mutableStateOf(AppLanguage.RUSSIAN)

// ✅ ПРАВИЛЬНО — SharedPreferences через PersistedState
class PersistedState<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val default: T,
    private val serialize: (T) -> String,
    private val deserialize: (String) -> T
) : MutableState<T> {
    private val state = mutableStateOf(
        prefs.getString(key, null)?.let(deserialize) ?: default
    )
    override var value: T
        get() = state.value
        set(v) { state.value = v; prefs.edit().putString(key, serialize(v)).apply() }
    override fun component1() = value
    override fun component2(): (T) -> Unit = { value = it }
}
```

### 7.7 Чеклист при добавлении строк в UI

```
□ Строка добавлена в values/strings.xml (русский)?
□ Строка добавлена в values-en/strings.xml (английский)?
□ Строка добавлена в values-el/strings.xml (греческий)?
□ В коде используется stringResource(R.string.key)?
□ НЕТ хардкода текста на русском в .kt файлах?
```

---

## 🆕 Раздел 8 — Ошибки компиляции K2 (уроки 31–43, июнь 2026)

> Источник: первая реальная компиляция проекта в Android Studio.
> Код, который «выглядит правильно», не существует — существует код, который компилируется.
> Автопроверка: `python3 qa_check.py` из корня репо — обязательна перед каждым ZIP/коммитом.

| # | Ошибка | Урок |
|---|---|---|
| 31 | `java.time.ChronoUnit` — Unresolved | Класс живёт в `java.time.temporal.ChronoUnit`. Полные пути НЕ писать по памяти — только проверенные |
| 32 | `Icons.Default.ArrowBack` — Unresolved ×4 | Каждая иконка требует импорта. Файлы с точечными импортами иконок — мина. Стандарт проекта: `import ...icons.filled.*` |
| 33 | `FlowRow` — «API is experimental» как ОШИБКА | K2 считает experimental без opt-in ошибкой, и она каскадирует на вызовы. Каждый файл с FlowRow: `@file:OptIn(...ExperimentalLayoutApi::class)` ПЕРЕД `package` |
| 34 | `PaddingValues(vertical=…, bottom=…)` | Оси (vertical/horizontal) и стороны (top/bottom/start/end) — разные перегрузки, смешивать нельзя |
| 35 | `LocalDate < LocalDate` — operator required | K2 спотыкается. Только `isBefore()/isAfter()` для дат |
| 36 | `-daysUntil` — ambiguity unaryMinus | K2 не делает smart-cast nullable в составных when. Перед использованием: `val du: Long = daysUntil` |
| 37 | `ContactImporter.parseVCard` — Unresolved | parseVCard/parseCsv — top-level функции, НЕ члены object. Перед вызовом `Объект.функция` — проверить, что функция внутри object |
| 38 | `positionInParent` — Unresolved | Это extension-функция: полная квалификация не работает, нужен `import androidx.compose.ui.layout.positionInParent` |
| 39 | `return@X: return@X` | Мусор от автоправок. Скриптовые правки — только с assert и проверкой баланса скобок |
| 40 | `CalendarItemType.REMINDER` не существует | Значения enum НЕ угадывать — читать `Enums.kt` перед использованием |
| 41 | Хелпер вшит внутрь чужой функции | `rfind('}')` находит НЕ ту скобку. Вставка top-level кода — только по уникальному текстовому якорю |
| 42 | ClassNotFoundException BootReceiver на устройстве | Старый APK + drift: манифест объявляет класс, которого нет в dex. Перед установкой новой сборки — УДАЛИТЬ старое приложение |
| 43 | Studio собирал папку socialsphere-X при готовом Y | Собирать всегда ПОСЛЕДНИЙ ZIP в чистую папку, не патчить старую |

| 44 | Профессии стали компаниями при импорте | CSV: contains-матчинг заголовков цеплял «Organization 1 - Type/Title» под «org». Матчинг колонок: сначала точное совпадение, потом подстрока С исключениями (title/type/должн не могут быть компанией). Юнит-тест на реальных заголовках Google CSV обязателен |
| 45 | vCard ORG = «Компания;Отдел;…» | Поля vCard разделены `;`, у строк бывают параметры (`ORG;CHARSET=…:`). Брать `substringAfter(":").substringBefore(";")`. Страж при создании сущностей: companyName ≠ jobTitle |
| 46 | Счётчик скобок врёт на `1)` в комментариях | В комментариях и строках кода не писать голые скобки — «1.», «2.» вместо «1)», «2)» |

| 47 | `Icons.Default.ArrowBack` deprecated на BOM 2024.09.03 | Старый запрет «AutoMirrored нельзя» УСТАРЕЛ с обновлением BOM. Зеркальные иконки (ArrowBack/Forward, Send, List, ExitToApp, Reply...) ДОЛЖНЫ быть `Icons.AutoMirrored.Filled.X` + импорт `...icons.automirrored.filled.X`. Прочие иконки AutoMirrored по-прежнему нельзя. qa_check обновлён под это |
| 48 | Статус лога: `finished` ≠ `failed` | `finished` + deprecated = ПРЕДУПРЕЖДЕНИЯ, сборка прошла, APK собран. `failed` + `e:` = ошибки. Не путать warning с error — паниковать только на `failed` |
| 49 | ClassNotFoundException BootReceiver ПОВТОРИЛСЯ после свежего APK | Урок 42 был неполным: дело не только в старом APK. `isMinifyEnabled=true` (release) → R8/ProGuard вырезает классы, которые инстанцирует система рефлексией по имени из манифеста (BroadcastReceiver/Service/Activity/Application/ContentProvider), т.к. не видит обращений из кода. Лекарство: `-keep public class * extends android.content.BroadcastReceiver { *; }` (и аналоги) в `proguard-rules.pro`. Стражи **У51** (каждый компонент манифеста существует как класс по физическому пути) и **У52** (при minify есть keep для ресиверов) — оба с негативными тестами |
| 50 | Локализация enum-лейблов: `label()` нужен в UI (перевод) И в data-слое (SearchEngine/ExternalActionHandler, где @Composable невозможен) | Два метода: `label(context: Context)` через `getString` для UI (Context из `LocalContext.current`, захватывается в `val ctxLabel` в начале composable — работает и во вложенных lambda/map/derived), и `labelKey()` со стабильным русским для поиска/тостов (перевод не нужен — поиск по данным). Не делать `label()` @Composable: сломает data-слой. Обратный матчинг дропдаунов `firstOrNull { it.label(ctx) == v }` безопасен ТОЛЬКО если лейблы уникальны внутри enum во всех языках — проверять программно перед тем как полагаться на него |

### Главный мета-урок
Среда без Android SDK ловит не всё. Поэтому: **(1)** `qa_check.py` перед каждой выдачей, **(2)** первая ошибка из лога компилятора важнее десяти статических догадок, **(3)** цикл «сборка → первая `e:`-строка → фикс» до зелёного.
