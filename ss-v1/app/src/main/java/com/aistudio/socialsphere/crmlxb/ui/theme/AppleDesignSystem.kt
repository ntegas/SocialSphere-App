package com.aistudio.socialsphere.crmlxb.ui.theme

/*
 * ============================================================================
 *  SOCIALSPHERE · Apple-grade Design System (Compose)
 * ----------------------------------------------------------------------------
 *  Перенос HTML-референса "SocialSphere - Apple Redesign" в Jetpack Compose.
 *
 *  Что внутри:
 *    1. AppleColors        — токены iOS (light + dark), индиго-бренд сохранён
 *    2. LocalAppleColors   — CompositionLocal для доступа из любого экрана
 *    3. AppleAppTheme      — обёртка темы (light/dark по системе)
 *    4. AppleShapes        — радиусы карточек/листов/чипов как в референсе
 *    5. Компоненты:        InsetGroup, InsetRow, SectionHeader,
 *                          QuickActionTile, AppleFilterChip, GroupedScreen
 *
 *  Как подключить:
 *    - положите файл в .../ui/theme/
 *    - оберните контент экрана в AppleAppTheme { ... }
 *    - фон экрана: Modifier.background(AppleTheme.colors.groupedBackground)
 *    - списки стройте через InsetGroup { InsetRow(...) ; Divider() ; ... }
 *
 *  Палитра соответствует HTML 1:1 (см. комментарии с hex).
 * ============================================================================
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme

// ============================================================================
// 1. COLOR TOKENS  (значения взяты напрямую из HTML-референса)
// ============================================================================

@Immutable
data class AppleColors(
    val groupedBackground: Color, // фон экрана  (прототип --bg)
    val card: Color,              // фон карточки (--card)
    val cardElevated: Color,      // вложенная плитка / сегмент
    val label: Color,             // основной текст (--tx)
    val secondaryLabel: Color,    // вторичный текст (--tx2)
    val tertiaryLabel: Color,     // подписи-приглушённые (--tx3)
    val quaternaryLabel: Color,   // самый тихий: chevron'ы, плейсхолдеры (--tx4)
    val separator: Color,         // волосяной разделитель (rgba(--line,.07))
    val fill: Color,              // тёплая заливка-подложка (rgba(--line,.06))
    val neutralFill: Color,       // нейтрально-серая заливка кнопок/полей (rgba(120,120,128,.10))
    val sheet: Color,             // фон bottom-sheet (--sheet)
    val barBlur: Color,           // матовый таб-бар / модальная шапка (--navbg)
    // акценты Aurelia
    val brand: Color,             // малахит / выбранный акцент (--ac)
    val red: Color,               // терракот-тревога
    val alarmRed: Color,          // тревожный красный (#C0492F): опасная зона, «никогда не общались»
    val orange: Color,            // золото (статус/просрочка)
    val goldLabel: Color,         // тёмное золото для подписей (#9A7223 на светлом)
    // Важность контакта — НАСЫЩЕННЕЕ базового золота/терракота (фидбэк владельца
    // 2026-07-03: «оранжевый не очень хорошо виднеется»). Единые токены для
    // ободка аватара, точек в списке, плиток сетки.
    val importanceKey: Color,     // «Ключевой» — яркое золото
    val importanceHigh: Color,    // «Важный» — яркий терракот
    val green: Color,             // = brand (легаси-поле)
    val blue: Color,              // приглушённый зелёный (легаси-поле)
    val pink: Color,              // = red (легаси-поле)
    val isDark: Boolean,
)

// --- общие акценты ---
private val BrandIndigo = Color(0xFF5B53D6)
private val SystemRed    = Color(0xFFFF3B30)
private val SystemOrange = Color(0xFFFF9500)
private val SystemGreen  = Color(0xFF34C759)
private val SystemBlue   = Color(0xFF007AFF)
private val SystemPink   = Color(0xFFFF2D55)

// Светлая палитра переотображена на дизайн-систему «Aurelia» (тёплая бумага/уголь,
// малахит/золото). Поля AppleColors сохранены — экраны, читающие AppleTheme.colors.*,
// автоматически получают новые цвета без переписывания каждого экрана.
// Значения 1:1 из ЖИВОГО прототипа («Socialsphere Прототип.dc.html», JS themeVars):
// светлая: --bg:#F1EDE6 --card:#FCFBF8 --sheet:#F4F1EA --tx:#1B1A16 --tx2:#807A6E
//          --tx3:#A79F90 --tx4:#C4BDB0 --line:35,32,24 --navbg:252,251,248
val AppleLightColors = AppleColors(
    groupedBackground = Color(0xFFF1EDE6), // бумага
    card              = Color(0xFFFCFBF8), // карточка
    cardElevated      = Color(0xFFFFFFFF),
    label             = Color(0xFF1B1A16), // уголь (--tx)
    secondaryLabel    = Color(0xFF807A6E), // --tx2
    tertiaryLabel     = Color(0xFFA79F90), // --tx3
    quaternaryLabel   = Color(0xFFC4BDB0), // --tx4 (chevron'ы)
    separator         = Color(0x14232018), // rgba(35,32,24,.08)
    fill              = Color(0x0F232018),
    neutralFill       = Color(0x1A787880), // rgba(120,120,128,.10)
    sheet             = Color(0xFFF4F1EA), // --sheet
    barBlur           = Color(0xD1F1EDE6), // матовая бумага
    brand  = Color(0xFF1C6B4C),            // малахит
    red    = Color(0xFFC45D34),            // терракот (тревога)
    alarmRed = Color(0xFFC0492F),          // тревожный красный (прототип)
    orange = Color(0xFFB68A36),            // золото (статус/просрочка)
    goldLabel = Color(0xFF9A7223),         // тёмное золото подписей на светлом
    importanceKey  = Color(0xFFD18A00),    // яркое золото (виднее #B68A36)
    importanceHigh = Color(0xFFD2521F),    // яркий терракот (виднее #C45D34)
    green  = Color(0xFF1C6B4C),            // малахит
    blue   = Color(0xFF2E6B57),            // приглушённый зелёный
    pink   = Color(0xFFC45D34),
    isDark = false,
)

// Тёмная палитра 1:1 из ЖИВОГО прототипа (JS themeVars, dark):
// --bg:#100E0A --card:#1B1813 --sheet:#221E18 --tx:#F3EFE8 --tx2:#A39B8C
// --tx3:#7A7264 --tx4:#544E44 --line:255,255,255
val AppleDarkColors = AppleColors(
    groupedBackground = Color(0xFF100E0A), // тёплый уголь (--bg)
    card              = Color(0xFF1B1813), // --card
    cardElevated      = Color(0xFF26221A),
    label             = Color(0xFFF3EFE8), // --tx
    secondaryLabel    = Color(0xFFA39B8C), // --tx2
    tertiaryLabel     = Color(0xFF7A7264), // --tx3
    quaternaryLabel   = Color(0xFF544E44), // --tx4
    separator         = Color(0x14FFFFFF), // rgba(255,255,255,.08)
    fill              = Color(0x0DFFFFFF), // rgba(255,255,255,.05)
    neutralFill       = Color(0x14FFFFFF), // нейтральная заливка на тёмном
    sheet             = Color(0xFF221E18), // --sheet
    barBlur           = Color(0xDB14120E), // rgba(20,18,14,.86)
    brand  = Color(0xFF5FB894),            // осветлённый малахит
    red    = Color(0xFFE0846E),            // терракот (тревога)
    alarmRed = Color(0xFFE0846E),          // на тёмном — осветлённый (читаемость)
    orange = Color(0xFFD7B468),            // золото
    goldLabel = Color(0xFFD7B468),         // на тёмном — светлое золото
    importanceKey  = Color(0xFFF2C14E),    // яркое золото на тёмном
    importanceHigh = Color(0xFFF08A5C),    // яркий терракот на тёмном
    green  = Color(0xFF5FB894),
    blue   = Color(0xFF7FBDB2),
    pink   = Color(0xFFE0846E),
    isDark = true,
)

// ============================================================================
// 2. COMPOSITION LOCAL + ACCESSOR
// ============================================================================

val LocalAppleColors = staticCompositionLocalOf { AppleLightColors }

object AppleTheme {
    val colors: AppleColors
        @Composable get() = LocalAppleColors.current
    val shapes get() = AppleShapes
}

// ============================================================================
// 3. THEME WRAPPER
// ============================================================================

@Composable
fun AppleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Акцент-цвет (бренд) выбирается в Настройках → Внешний вид. Один источник
    // правды: переопределяем brand здесь, и все экраны через AppleTheme.colors.brand
    // и AureliaTheme.colors.brand получают выбранный цвет.
    // Дефолтный малахит оставляем как в палитре (в тёмной он осветлён до #5FB894);
    // выбранный нестандартный акцент применяем как есть в обеих темах.
    val accentChoice = com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings.accentColorSafe()
    val base        = if (darkTheme) AppleDarkColors else AppleLightColors
    val aureliaBase = if (darkTheme) AureliaDarkColors else AureliaLightColors
    val isDefaultAccent = accentChoice == com.aistudio.socialsphere.crmlxb.ui.screens.AccentColor.MALACHITE
    val colors = if (isDefaultAccent) base else base.copy(brand = Color(accentChoice.rgb))
    CompositionLocalProvider(
        LocalAppleColors provides colors,
        LocalAureliaColors provides (if (isDefaultAccent) aureliaBase else aureliaBase.copy(brand = Color(accentChoice.rgb))),
    ) {
        // Типографика «Aurelia»: Playfair (serif) для заголовков/имён/чисел, Manrope для UI.
        // colorScheme ОБЯЗАТЕЛЕН: без него голые M3-компоненты (Button/FilterChip/
        // TabRow-индикатор/TextButton и т.д. без явных colors=...) берут дефолтную
        // Material You палитру (синий/фиолетовый), а не малахит/акцент из Настроек —
        // это системный баг, который проявлялся точечно на каждом экране редизайна
        // (Контакты, Дубликаты, Заметки — везде отдельно чинился один и тот же корень).
        MaterialTheme(
            colorScheme = aureliaColorScheme(colors),
            typography = AureliaTypography,
            // КОРНЕВЫЕ формы: extraLarge задаёт форму ВСЕХ AlertDialog/ModalBottomSheet
            // разом (r28 — радиус шторок макета), large/medium — карточкам/меню без
            // явного shape. Одна правка меняет вид всех диалогов приложения.
            shapes = androidx.compose.material3.Shapes(
                extraSmall = RoundedCornerShape(9.dp),
                small      = RoundedCornerShape(13.dp),
                medium     = RoundedCornerShape(16.dp),
                large      = RoundedCornerShape(22.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content
        )
    }
}

/**
 * Строит M3 ColorScheme из палитры Aurelia/Apple, чтобы ЛЮБОЙ M3-компонент без
 * явного `colors = ...` (Button, FilterChip, TextButton, TabRow-индикатор,
 * Switch, RadioButton, Checkbox, Slider…) по умолчанию рендерился в брендовых
 * тонах, а не в дефолтной Material You палитре.
 */
private fun aureliaColorScheme(c: AppleColors): ColorScheme {
    val onBrand = if (c.isDark) Color(0xFF12100C) else Color.White
    // Ограниченный набор параметров (как в уже существующем Theme.kt этого
    // проекта) — вызов lightColorScheme/darkColorScheme СО ВСЕМИ ~33 полями
    // (surfaceContainer*, scrim и т.д.) ломает компиляцию Kotlin: "Named
    // arguments are prohibited for function types" — похоже на предел
    // компилятора по числу именованных аргументов у функции с дефолтами.
    return if (c.isDark) darkColorScheme(
        primary = c.brand,
        onPrimary = onBrand,
        primaryContainer = c.brand.copy(alpha = 0.14f),
        onPrimaryContainer = c.brand,
        inversePrimary = c.brand,
        secondary = c.brand,
        onSecondary = onBrand,
        secondaryContainer = c.brand.copy(alpha = 0.14f),
        onSecondaryContainer = c.brand,
        tertiary = c.orange,
        onTertiary = onBrand,
        tertiaryContainer = c.orange.copy(alpha = 0.16f),
        onTertiaryContainer = c.orange,
        error = c.red,
        onError = Color.White,
        errorContainer = c.red.copy(alpha = 0.14f),
        onErrorContainer = c.red,
        background = c.groupedBackground,
        onBackground = c.label,
        surface = c.card,
        onSurface = c.label,
        surfaceVariant = c.fill,
        onSurfaceVariant = c.secondaryLabel,
        outline = c.secondaryLabel.copy(alpha = 0.4f),
        outlineVariant = c.separator,
        inverseSurface = c.label,
        inverseOnSurface = c.card,
    )
    else lightColorScheme(
        primary = c.brand,
        onPrimary = onBrand,
        primaryContainer = c.brand.copy(alpha = 0.14f),
        onPrimaryContainer = c.brand,
        inversePrimary = c.brand,
        secondary = c.brand,
        onSecondary = onBrand,
        secondaryContainer = c.brand.copy(alpha = 0.14f),
        onSecondaryContainer = c.brand,
        tertiary = c.orange,
        onTertiary = onBrand,
        tertiaryContainer = c.orange.copy(alpha = 0.16f),
        onTertiaryContainer = c.orange,
        error = c.red,
        onError = Color.White,
        errorContainer = c.red.copy(alpha = 0.14f),
        onErrorContainer = c.red,
        background = c.groupedBackground,
        onBackground = c.label,
        surface = c.card,
        onSurface = c.label,
        surfaceVariant = c.fill,
        onSurfaceVariant = c.secondaryLabel,
        outline = c.secondaryLabel.copy(alpha = 0.4f),
        outlineVariant = c.separator,
        inverseSurface = c.label,
        inverseOnSurface = c.card,
    )
}

// ============================================================================
// 4. SHAPES  (радиусы из HTML)
// ============================================================================

// Радиусы из макета Aurelia: inset-группы r20, иконка-плитка r9, поиск r13.
object AppleShapes {
    val card    = RoundedCornerShape(20.dp) // сгруппированные inset-карточки
    val tile    = RoundedCornerShape(16.dp) // плитки quick-action
    val iconBox = RoundedCornerShape(9.dp)  // цветная иконка-плитка 30dp
    val chip    = RoundedCornerShape(16.dp) // фильтр-чипы
    val field   = RoundedCornerShape(13.dp) // поле поиска
    val segment = RoundedCornerShape(9.dp)  // segmented control
    val sheet   = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

// ============================================================================
// 5. REUSABLE COMPONENTS
// ============================================================================

/** Заголовок секции (UPPERCASE, вторичный цвет) — как над каждым списком. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    // Капсовый заголовок секции: Manrope 600, трекинг .16em как в макете.
    Text(
        text = text.uppercase(),
        color = AppleTheme.colors.secondaryLabel,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.16.em,
        modifier = modifier.padding(start = 22.dp, end = 22.dp, bottom = 7.dp),
    )
}

/**
 * Сгруппированная inset-карточка iOS. Внутрь кладите InsetRow'ы и AppleDivider'ы:
 *
 *   InsetGroup {
 *       InsetRow(title = "Язык", value = "Русский")
 *       AppleDivider()
 *       InsetRow(title = "Уведомления")
 *   }
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(AppleShapes.card)
            .background(AppleTheme.colors.card),
        content = content,
    )
}

/** Волосяной разделитель с отступом слева (как leadingInset в iOS). */
@Composable
fun AppleDivider(startInset: Dp = 57.dp) {
    Box(
        Modifier
            .padding(start = startInset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(AppleTheme.colors.separator),
    )
}

/**
 * Строка списка: [цветная иконка-плитка] · заголовок · [значение] · [chevron].
 * leadingColor задаёт цвет иконки-плитки (напр. AppleTheme.colors.red).
 */
@Composable
fun InsetRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        leading?.invoke()
        Text(
            text = title,
            color = AppleTheme.colors.label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(text = value, color = AppleTheme.colors.secondaryLabel, fontSize = 16.sp)
        }
        trailing?.invoke()
    }
}

/**
 * Цветная иконка-плитка 32dp/r9 (прототип, строки Настроек). Передавайте Icon().
 *   IconTile(AppleTheme.colors.red) { ... }
 */
@Composable
fun IconTile(color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(AppleShapes.iconBox).background(color),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** Фон-обёртка экрана: сгруппированный фон iOS. */
@Composable
fun GroupedScreen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(AppleTheme.colors.groupedBackground),
        content = { content() },
    )
}
