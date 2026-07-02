package com.aistudio.socialsphere.crmlxb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Дизайн-система «Aurelia» (новый макет): тёплая бумага + уголь, малахит + золото,
 * редакционная типографика (Playfair Display для имён/чисел/заголовков, Manrope для UI).
 *
 * Структура повторяет AppleDesignSystem: токены-цвета → CompositionLocal → формы →
 * типографика → обёртка AureliaTheme. Подключается на экраны постепенно.
 *
 * ШРИФТЫ: пока системные (Serif ≈ роль Playfair, SansSerif ≈ Manrope) — чтобы
 * собиралось без бинарных ассетов. Когда положишь TTF в res/font, заменить
 * AureliaSerif/AureliaSans на FontFamily(Font(R.font.playfair_display)) и
 * FontFamily(Font(R.font.manrope)) — это единственное место правки.
 */

// ── Палитра 1:1 из живого прототипа («Socialsphere Прототип.dc.html», themeVars) ──
private val Malachite   = Color(0xFF1C6B4C) // бренд / действия (--ac)
private val Gold        = Color(0xFFB68A36) // редкий акцент: статус, ключевые
private val Paper       = Color(0xFFF1EDE6) // фон экрана (--bg)
private val Card        = Color(0xFFFCFBF8) // карточки (--card)
private val Ink         = Color(0xFF1B1A16) // основной текст (--tx)
private val Charcoal    = Color(0xFF100E0A) // тёмные плашки (= dark --bg)
private val InkSoft     = Color(0xFF807A6E) // вторичный текст (--tx2)
private val InkFaint    = Color(0xFFA79F90) // совсем тихий (--tx3)
private val Divider     = Color(0x14232018) // rgba(35,32,24,.08)
private val Fill        = Color(0x0F232018) // лёгкая заливка-подложка

data class AureliaColors(
    val brand: Color,        // малахит
    val gold: Color,
    val background: Color,    // бумага
    val card: Color,
    val label: Color,         // основной текст (уголь)
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val charcoal: Color,
    val divider: Color,
    val fill: Color,
    val onBrand: Color,       // текст на малахите
    // Градиенты аватаров
    val avatarTerracotta: Brush,
    val avatarSage: Brush,
    val isDark: Boolean,
)

val AureliaLightColors = AureliaColors(
    brand = Malachite,
    gold = Gold,
    background = Paper,
    card = Card,
    label = Ink,
    secondaryLabel = InkSoft,
    tertiaryLabel = InkFaint,
    charcoal = Charcoal,
    divider = Divider,
    fill = Fill,
    onBrand = Color(0xFFF7F4ED),
    avatarTerracotta = Brush.linearGradient(listOf(Color(0xFFE59A6B), Color(0xFFC45D34))),
    avatarSage = Brush.linearGradient(listOf(Color(0xFF9DBE92), Color(0xFF5E8C66))),
    isDark = false,
)

// Тёмная палитра Aurelia 1:1 из живого прототипа (themeVars, dark):
// --bg:#100E0A --card:#1B1813 --tx2:#A39B8C --tx3:#7A7264
val AureliaDarkColors = AureliaColors(
    brand = Color(0xFF5FB894),            // осветлённый малахит
    gold = Color(0xFFD7B468),
    background = Color(0xFF100E0A),
    card = Color(0xFF1B1813),
    label = Color(0xFFF3EFE8),
    secondaryLabel = Color(0xFFA39B8C),
    tertiaryLabel = Color(0xFF7A7264),
    charcoal = Color(0xFF26221A),
    divider = Color(0x14FFFFFF),          // rgba(255,255,255,.08)
    fill = Color(0x0DFFFFFF),             // rgba(255,255,255,.05)
    onBrand = Color(0xFF12100C),          // тёмный текст на светлом малахите
    avatarTerracotta = Brush.linearGradient(listOf(Color(0xFFE59A6B), Color(0xFFC45D34))),
    avatarSage = Brush.linearGradient(listOf(Color(0xFF7FBDB2), Color(0xFF3E7E7A))),
    isDark = true,
)

val LocalAureliaColors = staticCompositionLocalOf { AureliaLightColors }

// ── Формы (радиусы из макета) ────────────────────────────────────────────
object AureliaShapes {
    val cardLarge = RoundedCornerShape(28.dp)
    val card = RoundedCornerShape(20.dp)
    val stat = RoundedCornerShape(20.dp)
    val chip = RoundedCornerShape(14.dp)
    val iconBox = RoundedCornerShape(9.dp)
}

// ── Типографика: настоящие Playfair Display (serif) и Manrope (sans).
// ВАЖНО: это ВАРИАТИВНЫЕ шрифты — вес нужно задавать через ось wght
// (FontVariation.Settings), иначе все начертания рисуются дефолтным весом
// и заголовки выходят тоньше макета. Ось применяется на API 26+; на 24/25 —
// дефолтное начертание (без краша). ──
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variableFont(resId: Int, w: Int) = androidx.compose.ui.text.font.   Font(
    resId,
    weight = FontWeight(w),
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
        androidx.compose.ui.text.font.FontVariation.weight(w)
    ),
)

private val PF = com.aistudio.socialsphere.crmlxb.R.font.playfair_display
private val MR = com.aistudio.socialsphere.crmlxb.R.font.manrope

val AureliaSerif: FontFamily = FontFamily(
    variableFont(PF, 500), variableFont(PF, 700), variableFont(PF, 800), variableFont(PF, 900),
)
val AureliaSans: FontFamily = FontFamily(
    variableFont(MR, 400), variableFont(MR, 500), variableFont(MR, 600),
    variableFont(MR, 700), variableFont(MR, 800),
)

// Шкала выверена по CSS макета: Playfair-заголовки letter-spacing -.01em/-.02em
// (в em, как в CSS), вес 800; Manrope-интерфейс 500/600/700. Размеры и line-height 1:1.
val AureliaTypography = Typography(
    // Заголовки/имена/числа — Playfair (serif)
    displayLarge   = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em),
    displayMedium  = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.02).em),
    displaySmall   = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    headlineLarge  = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
    headlineSmall  = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    titleLarge     = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W700, fontSize = 21.sp, lineHeight = 27.sp),
    // Интерфейс/текст — Manrope (sans)
    titleMedium    = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W700, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W700, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge      = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall      = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W500, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W700, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium    = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.1.em),
)

// ── Доступ из любого экрана ──────────────────────────────────────────────
object AureliaTheme {
    val colors: AureliaColors
        @Composable get() = LocalAureliaColors.current
    val shapes get() = AureliaShapes
}

/**
 * Крупный заголовок экрана как в макете: Playfair (serif), уголь.
 * Корневые вкладки (Контакты, Компании, Календарь) — 34sp (по умолчанию);
 * push-экраны (Настройки и вложенные) — 28sp (прототип: font:800 28px).
 */
@Composable
fun AureliaScreenTitle(
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    color: Color = AureliaTheme.colors.label,
    fontSize: androidx.compose.ui.unit.TextUnit = 34.sp,
) {
    androidx.compose.material3.Text(
        text = text,
        fontFamily = AureliaSerif,
        fontWeight = FontWeight.W800,
        fontSize = fontSize,
        lineHeight = fontSize * 1.18f,
        letterSpacing = (-0.01).em,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun AureliaAppTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Тёмная палитра добавится позже; пока только светлое ядро.
    val colors = AureliaLightColors
    androidx.compose.runtime.CompositionLocalProvider(LocalAureliaColors provides colors) {
        MaterialTheme(typography = AureliaTypography, content = content)
    }
}
