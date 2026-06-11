
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
