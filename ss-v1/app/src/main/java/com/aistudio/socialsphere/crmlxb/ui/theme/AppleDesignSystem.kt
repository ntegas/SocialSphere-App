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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme

// ============================================================================
// 1. COLOR TOKENS  (значения взяты напрямую из HTML-референса)
// ============================================================================

@Immutable
data class AppleColors(
    val groupedBackground: Color, // фон экрана  (light #F2F2F7 / dark #000000)
    val card: Color,              // фон карточки (light #FFFFFF / dark #1C1C1E)
    val cardElevated: Color,      // вложенная плитка / сегмент
    val label: Color,             // основной текст (light #000 / dark #F5F5F7)
    val secondaryLabel: Color,    // вторичный текст (#86868B / rgba(235,235,245,.6))
    val tertiaryLabel: Color,     // chevron / placeholder (#C7C7CC / rgba(235,235,245,.3))
    val separator: Color,         // волосяной разделитель
    val fill: Color,              // поиск / segmented track (rgba(118,118,128,.x))
    val barBlur: Color,           // матовый таб-бар / модальная шапка
    // системные акценты iOS (одинаковы в обеих темах)
    val brand: Color,             // индиго-бренд #5B53D6
    val red: Color,               // #FF3B30
    val orange: Color,            // #FF9500
    val green: Color,             // #34C759
    val blue: Color,              // #007AFF
    val pink: Color,              // #FF2D55
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
val AppleLightColors = AppleColors(
    groupedBackground = Color(0xFFF1EDE6), // бумага
    card              = Color(0xFFFCFBF8), // карточка
    cardElevated      = Color(0xFFFFFFFF),
    label             = Color(0xFF1B1A16), // уголь
    secondaryLabel    = Color(0xFF6F685B),
    tertiaryLabel     = Color(0xFF9A9284),
    separator         = Color(0x14232018), // rgba(35,32,24,.08)
    fill              = Color(0x0F232018),
    barBlur           = Color(0xD1F1EDE6), // матовая бумага
    brand  = Color(0xFF1C6B4C),            // малахит
    red    = Color(0xFFC45D34),            // терракот (тревога)
    orange = Color(0xFFB68A36),            // золото (статус/просрочка)
    green  = Color(0xFF1C6B4C),            // малахит
    blue   = Color(0xFF2E6B57),            // приглушённый зелёный
    pink   = Color(0xFFC45D34),
    isDark = false,
)

// Тёмная палитра переотображена на Aurelia-dark (тёплый уголь + осветлённый
// малахит/золото). Поля AppleColors сохранены — экраны меняются автоматически.
val AppleDarkColors = AppleColors(
    groupedBackground = Color(0xFF0E0D0A), // тёплый уголь
    card              = Color(0xFF1A1813),
    cardElevated      = Color(0xFF26221A),
    label             = Color(0xFFF3EFE8),
    secondaryLabel    = Color(0xFF8E877A),
    tertiaryLabel     = Color(0xFF6B655A),
    separator         = Color(0x14FFFFFF), // rgba(255,255,255,.08)
    fill              = Color(0x0DFFFFFF), // rgba(255,255,255,.05)
    barBlur           = Color(0xDB14120E), // rgba(20,18,14,.86)
    brand  = Color(0xFF5FB894),            // осветлённый малахит
    red    = Color(0xFFE0846E),            // терракот (тревога)
    orange = Color(0xFFD7B468),            // золото
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
        // Типографика «Aurelia»: Playfair (serif) для заголовков/имён/чисел, Manrope для UI
        MaterialTheme(typography = AureliaTypography, content = content)
    }
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
    Text(
        text = text.uppercase(),
        color = AppleTheme.colors.secondaryLabel,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
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
 * Цветная иконка-плитка 30dp (как в Настройках). Передавайте сюда Icon().
 *   IconTile(AppleTheme.colors.red) { ... }
 */
@Composable
fun IconTile(color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(AppleShapes.iconBox).background(color),
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
