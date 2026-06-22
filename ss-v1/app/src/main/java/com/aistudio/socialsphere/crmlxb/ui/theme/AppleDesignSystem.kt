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

val AppleLightColors = AppleColors(
    groupedBackground = Color(0xFFF2F2F7),
    card              = Color(0xFFFFFFFF),
    cardElevated      = Color(0xFFFFFFFF),
    label             = Color(0xFF000000),
    secondaryLabel    = Color(0xFF86868B),
    tertiaryLabel     = Color(0xFFC7C7CC),
    separator         = Color(0x1F3C3C43), // rgba(60,60,67,0.12)
    fill              = Color(0x1F787880), // rgba(118,118,128,0.12)
    barBlur           = Color(0xD1F9F9F9), // rgba(249,249,249,0.82)
    brand = BrandIndigo, red = SystemRed, orange = SystemOrange,
    green = SystemGreen, blue = SystemBlue, pink = SystemPink,
    isDark = false,
)

val AppleDarkColors = AppleColors(
    groupedBackground = Color(0xFF000000),
    card              = Color(0xFF1C1C1E),
    cardElevated      = Color(0xFF2C2C2E),
    label             = Color(0xFFF5F5F7),
    secondaryLabel    = Color(0x99EBEBF5), // rgba(235,235,245,0.6)
    tertiaryLabel     = Color(0x4DEBEBF5), // rgba(235,235,245,0.3)
    separator         = Color(0x8C545458), // rgba(84,84,88,0.55)
    fill              = Color(0x47787880), // rgba(118,118,128,0.28)
    barBlur           = Color(0xD11C1C1E), // rgba(28,28,30,0.82)
    brand = BrandIndigo, red = SystemRed, orange = SystemOrange,
    green = SystemGreen, blue = SystemBlue, pink = SystemPink,
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
    val colors = if (darkTheme) AppleDarkColors else AppleLightColors
    CompositionLocalProvider(LocalAppleColors provides colors) {
        // используем существующую Typography из Type.kt
        MaterialTheme(typography = Typography, content = content)
    }
}

// ============================================================================
// 4. SHAPES  (радиусы из HTML)
// ============================================================================

object AppleShapes {
    val card    = RoundedCornerShape(18.dp) // сгруппированные inset-карточки
    val tile    = RoundedCornerShape(16.dp) // плитки quick-action
    val iconBox = RoundedCornerShape(8.dp)  // цветная иконка-плитка 30dp
    val chip    = RoundedCornerShape(17.dp) // фильтр-чипы
    val field   = RoundedCornerShape(11.dp) // поле поиска
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
