package com.aistudio.socialsphere.crmlxb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Дизайн-система «Aurelia» (новый макет): тёплая бумага + уголь, малахит + золото,
 * редакционная типографика (Playfair Display для имён/чисел/заголовков, Manrope для UI).
 *
 * Структура повторяет AppleDesignSystem: токены-цвета → CompositionLocal → формы →
 * типографика → обёртка AureliaTheme. Подключается на экраны постепенно.
 *
 * ШРИФТЫ: настоящие Playfair Display/Manrope, статические TTF на каждый вес
 * (res/font/playfair_display_*.ttf, manrope_*.ttf) — см. комментарий над
 * AureliaSerif/AureliaSans ниже про то, почему НЕ один вариативный файл.
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
    // Градиенты аватаров НЕ здесь: цвет аватара — только AureliaAvatars.brushFor(id),
    // чтобы контакт был одного цвета на всех экранах (фикс §28; токены-«одиночки»
    // avatarTerracotta/avatarSage провоцировали красить всех одним цветом).
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
    isDark = true,
)

val LocalAureliaColors = staticCompositionLocalOf { AureliaLightColors }

// ── Формы (радиусы из макета) ────────────────────────────────────────────
object AureliaShapes {
    val cardLarge = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Search
    val card = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge
    val stat = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.XLarge
    val chip = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14
    val iconBox = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R9
}

// ── Типографика: настоящие Playfair Display (serif) и Manrope (sans).
//
// ФИКС (2026-09-11, подтверждённый краш на реальном Android 7.0/API 24):
// исходные res/font/manrope.ttf и playfair_display.ttf — ВАРИАТИВНЫЕ шрифты
// (один файл, ось wght). Прежний комментарий здесь утверждал «ось применяется
// на API 26+, на 24/25 — дефолтное начертание, без краша» — это оказалось
// неверно. На живом эмуляторе Android 7.0 (google_apis/x86_64) приложение
// падало на ПЕРВОМ же кадре: `ResourcesCompat.getFont` не мог разобрать сам
// ФАЙЛ вариативного шрифта вообще (Resources$NotFoundException), а не только
// ось — краш воспроизводился даже при попытке загрузить тот же ресурс без
// FontVariation.Settings. Значит проблема не в применении оси, а в том, что
// шрифтовый стек Android 7 в принципе не умеет парсить формат variable font.
//
// Фикс — не API-развилка (это оставило бы на 24/25 либо тот же краш, либо
// молчаливое падение до одного начертания), а замена на СТАТИЧЕСКИЕ файлы —
// стандартный способ объявления multi-weight FontFamily, работающий на всех
// версиях Android без единой оси вариации. Файлы сгенерированы из тех же
// исходных вариативных шрифтов через `fontTools.varLib.instancer` (те же
// именованные инстансы, что видит FontVariation.weight — Bold/ExtraBold/…),
// то есть визуально идентичны прежнему поведению на современных устройствах,
// но теперь являются настоящими статическими TTF, а не одним файлом с осью.
// Сами вариативные manrope.ttf/playfair_display.ttf удалены — на них ничего
// больше не ссылается.
val AureliaSerif: FontFamily = FontFamily(
    Font(com.aistudio.socialsphere.crmlxb.R.font.playfair_display_medium, FontWeight.W500),
    Font(com.aistudio.socialsphere.crmlxb.R.font.playfair_display_bold, FontWeight.W700),
    Font(com.aistudio.socialsphere.crmlxb.R.font.playfair_display_extrabold, FontWeight.W800),
    Font(com.aistudio.socialsphere.crmlxb.R.font.playfair_display_black, FontWeight.W900),
)
val AureliaSans: FontFamily = FontFamily(
    Font(com.aistudio.socialsphere.crmlxb.R.font.manrope_regular, FontWeight.W400),
    Font(com.aistudio.socialsphere.crmlxb.R.font.manrope_medium, FontWeight.W500),
    Font(com.aistudio.socialsphere.crmlxb.R.font.manrope_semibold, FontWeight.W600),
    Font(com.aistudio.socialsphere.crmlxb.R.font.manrope_bold, FontWeight.W700),
    Font(com.aistudio.socialsphere.crmlxb.R.font.manrope_extrabold, FontWeight.W800),
)

// Playfair Display не существует в греческой раскладке (у Google Fonts для него нет
// такого начертания вообще) — ни одного греческого глифа в файле. Без этой проверки
// Android молча подменяет отсутствующие буквы системным шрифтом ПОСИМВОЛЬНО (Minikin
// fallback): греческое слово целиком становится «тонким», а греческая буква рядом с
// цифрой/латиницей — кривой на фоне соседних Playfair-символов. Manrope греческий
// покрывает полностью (включая тонос и конечную сигму ς), поэтому для греческого
// текста используем его вместо Playfair — жирный и цельный, а не разнобой начертаний.
private fun containsGreek(text: String): Boolean =
    text.any { it.code in 0x0370..0x03FF || it.code in 0x1F00..0x1FFF }

fun aureliaSerifFor(text: String): FontFamily = if (containsGreek(text)) AureliaSans else AureliaSerif

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
        fontFamily = aureliaSerifFor(text),
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
