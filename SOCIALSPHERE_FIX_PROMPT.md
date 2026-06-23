# SocialSphere — промт для точечных исправлений

> Вставляй этот файл в начало любого чата по SocialSphere и в «Fix with AI».
> **Правило №0: не ломать то, что уже работает.** Каждая правка — минимальная,
> под конкретную ошибку из лога. Никаких рефакторингов «заодно».

---

## 0. Роль и режим работы

Ты правишь приватное Android-приложение SocialSphere (Kotlin + Jetpack Compose,
пакет `com.aistudio.socialsphere.crmlxb`). Сейчас проект **собирается и
запускается** — задача только добивать конкретные ошибки, не регрессируя.

**Один источник правды.** Правки вносит кто-то один. Если код правит внешний
зип — «Fix with AI» в студии не трогает те же файлы, и наоборот. Параллельные
правки двух источников = дрифт репозитория = главный источник багов.

**Цикл на каждую правку:**
1. Сначала прочитать **реальный** код того, что правишь (не предполагать).
2. Правка минимальная, под текст ошибки. Ничего не переименовывать.
3. После правки — проверка баланса `{}` и `()`.
4. Перед сборкой — `python3 qa_check.py`, все проверки зелёные.
5. Собирать `gradlew clean assembleDebug` в **чистую** папку. Перед установкой
   APK — удалить старое приложение и очистить данные.

---

## 1. Зафиксированный стек — НЕ менять

AGP **8.5.2** · Kotlin **2.0.21** · KSP **2.0.21-1.0.28** (точно = Kotlin) ·
Gradle **8.9** · Compose BOM **2024.09.03** · Room **2.6.1** · Java **17** ·
compileSdk/targetSdk **35** · minSdk **24**.

- Android Studio будет предлагать «Start AGP Upgrade Assistant» и «Migrate to
  Gradle Daemon toolchain» — **всегда Ignore**. Обновление AGP сломает
  совместимость с Gradle 8.x. Это предложения IDE, а не ошибки.
- Никаких preview/alpha. AGP 8.x несовместим с Gradle 9.x.

---

## 2. Проверенные шаблоны правок (применять дословно)

Эти пять паттернов закрыли весь реальный каскад ошибок. При новой ошибке —
сначала сопоставь её с одним из них.

### 2.1 `@Composable invocations can only happen from a @Composable function`
Причина: `@Composable`-функция (`stringResource`, `label()`, `title()`) вызвана
**не** из composable-контекста — внутри `.map{}`, `.find{}`, `.joinToString{}`,
`onSelect`/`onToggle`-колбэка, `LaunchedEffect{}`, `launch{}`, `withContext{}`,
`remember{}`, или в обычной (не-`@Composable`) функции.

**Фикс — перевести `label()/title()` на приём `Context`:**
```kotlin
// было:
@Composable fun ReminderTime.label(): String = when (this) {
    ReminderTime.NONE -> stringResource(R.string.rt_none) … }
// стало:
fun ReminderTime.label(context: android.content.Context): String = when (this) {
    ReminderTime.NONE -> context.getString(R.string.rt_none) … }
```
На вызовах передавать захваченный контекст: `it.label(ctxLabel)`.
Все `label()/title()` в проекте должны принимать `Context` — так они работают и
в data-слое, и в любых лямбдах.

**Если это `stringResource` в `LaunchedEffect`/корутине** — захватить строку
в `val` в composable-скоупе ДО эффекта, внутри эффекта только использовать:
```kotlin
val errText = stringResource(R.string.x)        // composable scope
LaunchedEffect(k) { state = errText }           // тут только присваивание
```

### 2.2 `Unresolved reference 'ctxLabel'`
Контекст должен быть в своей области видимости.
- В **`@Composable`-функции** (экран, хедер, карточка, диалог): в начало тела
  `val ctxLabel = LocalContext.current`.
- В **`LazyListScope`-расширении** (`fun LazyListScope.xxxTab(...)`,
  не `@Composable`!): добавить параметр `ctxLabel: android.content.Context`
  и передавать его из вызывающего composable.
Функции Kotlin **не** видят локальные переменные других функций — у каждой
функции-хелпера должен быть свой `ctxLabel` (через `val` или параметр).
Импорт: `androidx.compose.ui.platform.LocalContext`.

### 2.3 Ресурсы: `Invalid unicode escape sequence` / `not a valid string resource`
Это `mergeDebugResources`, не Kotlin. Причина — неэкранированный символ в
`strings.xml`:
- апостроф `'` → `\'` (или строку в `"..."`);
- амперсанд `&` → `&amp;`.
Чинить во всех трёх локалях (`values/`, `values-en/`, `values-el/`).
Номера строк в логе указывают на слитый файл `merged.dir/...`, а не на исходник —
искать по **имени ресурса**.

### 2.4 Чтение enum из БД — только безопасно
Никогда не парсить enum из строки БД через `Enum.valueOf(x)` напрямую — кривое
значение (импорт CSV/vCard, переименование) роняет весь `reloadFromDb()`.
Использовать хелпер из `Mappers.kt`:
```kotlin
inline fun <reified T : Enum<T>> safeEnum(value: String?, default: T): T =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
```

### 2.5 Изменил схему БД → подними версию
Любое изменение `@Entity`/`@ColumnInfo`/набора таблиц меняет identity-hash Room.
`Room cannot verify the data integrity` лечится так:
- поднять `version = N` в `@Database` (то, что Room прямо просит в тексте);
- держать `.fallbackToDestructiveMigration()` в билдере (для pre-1.0 ок —
  данные сидятся из `DemoDataProvider`);
- `@Database` менял схему → добавить и `Migration`, если данные надо сохранить.
`allowBackup="false"` в манифесте, иначе старая `.db` возвращается из облака
при переустановке и хэш снова не совпадает.

### 2.6 `This material API is experimental`
Над composable добавить `@OptIn(ExperimentalMaterial3Api::class)`
(+ `ExperimentalLayoutApi::class`, если есть `FlowRow`).

---

## 3. Запрещено

- **Переименовывать** переменные/функции «для чистоты» (`ctxLabel`→`ctx` и т.п.).
  Переименование создаёт новые `Unresolved reference` в других файлах. Никогда.
- Менять зафиксированный стек версий и принимать апгрейды AGP/Gradle от IDE.
- `Icons.AutoMirrored.Filled.X` для зеркальных иконок (ArrowBack/Send/List…) —
  это **правильно** на BOM 2024.09.03, НЕ «исправлять» на `Icons.Default`.
- `.name` у enum в UI → только `.label(context)`. В data-слое `.name` для
  сериализации — допустимо и нужно.
- `!!`, `database!!`, `resolveActivity()`, `LocalDate.now().toString()`,
  хардкод строк в UI, бизнес-логика в `@Composable`.
- Трогать ресурсы/иконки/файлы, не относящиеся к текущей ошибке.

---

## 4. Как давать мне ошибку

Присылай первые строки с `e:` из вкладки Build (компиляция) или блок
`FATAL EXCEPTION` из Logcat (рантайл). **Проверяй время лога** — он должен быть
свежее последней правки, иначе это ошибка старой сборки.
Перед выводом — сверь, что собран нужный код: в `SocialsphereDatabase.kt`
актуальная `version`, в манифесте `allowBackup="false"`.

---

## 5. Стражи qa_check.py (зелёные перед каждым ZIP)

Помимо существующих — следить, чтобы держались:
- неэкранированные `'`/`&` в strings.xml = 0 (страж У54);
- `AutoMirrored` без импорта = 0; `com/example` = 0; `database!!` = 0;
  `resolveActivity` = 0; дубликаты `<string name>` = 0; целостность `.webp`.

Новый страж добавлять только с **негативным тестом** (краснеет на сломанном
входе, зеленеет на исправленном).

---

## 6. Бэклог (НЕ трогать, пока не попросят отдельно — это не баги сборки)

- Каскадное удаление в Room: у `@Entity` нет `foreignKeys`/`onDelete=CASCADE` —
  при удалении контакта телефоны/заметки/подарки/связи остаются сиротами.
- Архитектура: глобальный `object AppStateStore` вместо ViewModel (работает,
  но это техдолг — не переписывать без явной задачи).
- Maps API-ключ: отозвать засвеченный, новый — через GitHub Secrets.
- `lastContactDate`: сейчас обновляется на любую заметку; по ТЗ — только на
  CALL/MEETING/MESSAGE и на событие COMPLETED.

---

*SocialSphere · промт для точечных правок · держать в корне рядом с qa_check.py*
