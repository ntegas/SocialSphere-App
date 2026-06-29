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

// ── Палитра (из макета Aurelia) ──────────────────────────────────────────
private val Malachite   = Color(0xFF1C6B4C) // бренд / действия
private val Gold        = Color(0xFFB68A36) // редкий акцент: статус, ключевые
private val Paper       = Color(0xFFF1EDE6) // фон экрана
private val Card        = Color(0xFFFCFBF8) // карточки
private val Ink         = Color(0xFF1B1A16) // основной текст
private val Charcoal    = Color(0xFF0E0D0A) // тёмные плашки
private val InkSoft     = Color(0xFF6F685B) // вторичный текст
private val InkMuted    = Color(0xFF807A6E) // подписи
private val InkFaint    = Color(0xFF9A9284) // совсем тихий
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

// Тёмная палитра Aurelia (из тёмных экранов макета): тёплый уголь-фон,
// осветлённые малахит/золото для контраста на тёмном.
val AureliaDarkColors = AureliaColors(
    brand = Color(0xFF5FB894),            // осветлённый малахит
    gold = Color(0xFFD7B468),
    background = Color(0xFF0E0D0A),
    card = Color(0xFF1A1813),
    label = Color(0xFFF3EFE8),
    secondaryLabel = Color(0xFF8E877A),
    tertiaryLabel = Color(0xFF6B655A),
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
// Вариативные TTF в res/font; вес применяется через ось wght (API 26+,
// на 24/25 — дефолтное начертание, без краша). ──
val AureliaSerif: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.playfair_display, weight = FontWeight.W500),
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.playfair_display, weight = FontWeight.W700),
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.playfair_display, weight = FontWeight.W800),
)
val AureliaSans: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.manrope, weight = FontWeight.W400),
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.manrope, weight = FontWeight.W500),
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.manrope, weight = FontWeight.W600),
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.manrope, weight = FontWeight.W700),
    androidx.compose.ui.text.font.Font(com.aistudio.socialsphere.crmlxb.R.font.manrope, weight = FontWeight.W800),
)

val AureliaTypography = Typography(
    // Заголовки/имена/числа — Playfair (serif)
    displayLarge   = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-1.2).sp),
    displayMedium  = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W800, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp),
    displaySmall   = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W700, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
    headlineLarge  = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W700, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W700, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall  = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W700, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge     = TextStyle(fontFamily = AureliaSerif, fontWeight = FontWeight.W700, fontSize = 22.sp, lineHeight = 28.sp),
    // Интерфейс/текст — Manrope (sans)
    titleMedium    = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W700, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W700, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge      = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall      = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W500, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W700, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelMedium    = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall     = TextStyle(fontFamily = AureliaSans, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

// ── Доступ из любого экрана ──────────────────────────────────────────────
object AureliaTheme {
    val colors: AureliaColors
        @Composable get() = LocalAureliaColors.current
    val shapes get() = AureliaShapes
}

/**
 * Крупный заголовок экрана как в макете: Playfair (serif) 34sp, уголь.
 * Используется в шапках списков (Контакты, Компании, Календарь, Настройки…).
 */
@Composable
fun AureliaScreenTitle(
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    color: Color = AureliaTheme.colors.label,
) {
    androidx.compose.material3.Text(
        text = text,
        fontFamily = AureliaSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        letterSpacing = (-0.3).sp,
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
