package com.aistudio.socialsphere.crmlxb.ui.theme

/*
 * ============================================================================
 *  SOCIALSPHERE · Общие компоненты прототипа «Socialsphere Прототип.dc.html»
 * ----------------------------------------------------------------------------
 *  ЕДИНСТВЕННОЕ место для примитивов макета. Экраны НЕ заводят своих копий:
 *    - AureliaAvatars       — палитра аватар-градиентов (5 пар) + brushFor(id)
 *    - AureliaCircleButton  — круглая кнопка 38dp (Filled/Tinted/Neutral)
 *    - AureliaBackButton    — круглая «назад» 36dp (нейтральная заливка)
 *    - AureliaStatCard      — стат-карта h96/r20: плитка-иконка + Playfair-число
 *    - AureliaSectionHeader — заголовок секции 19sp W800 + акцент-действие «Все»
 *    - AureliaCapsLabel     — UPPERCASE-подпись 11sp, трекинг .14em
 *    - AureliaCard          — карточка r22: фон card + inset-кольцо + мягкая тень
 *    - Modifier.aureliaPress— тап-анимация масштаба .96 (au-press из макета)
 *
 *  Значения 1:1 с инлайн-стилями прототипа (комментарии указывают CSS-источник).
 * ============================================================================
 */

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ── Палитра аватаров (5 градиентов из прототипа; порядок стабилен) ──────────
object AureliaAvatars {
    /** linear-gradient(145deg, …) из макета: терракот/сейдж/слива/тил/золото. */
    val gradients: List<List<Color>> = listOf(
        listOf(Color(0xFFE59A6B), Color(0xFFC45D34)), // терракот
        listOf(Color(0xFF9DBE92), Color(0xFF5E8C66)), // сейдж
        listOf(Color(0xFFB58CB6), Color(0xFF7E5180)), // слива
        listOf(Color(0xFF7FBDB2), Color(0xFF3E7E7A)), // тил
        listOf(Color(0xFFD8B26A), Color(0xFFB68A36)), // золото
    )

    /** Детерминированный градиент по id (одна и та же пара для контакта везде). */
    fun brushFor(id: String): Brush =
        Brush.linearGradient(gradients[kotlin.math.abs(id.hashCode()) % gradients.size])

    /** Инициалы «Имя Фамилия» → «ИФ» (макс. 2 буквы, верхний регистр). */
    fun initials(name: String): String =
        name.split(" ").mapNotNull { it.firstOrNull()?.toString() }
            .take(2).joinToString("").uppercase()

    /** Градиенты лого КОМПАНИЙ (из прототипа; отличаются от аватаров людей —
     *  есть тёмный малахит и синий). */
    val companyGradients: List<List<Color>> = listOf(
        listOf(Color(0xFF2E8B6B), Color(0xFF155539)), // малахит
        listOf(Color(0xFF5E78C4), Color(0xFF34488C)), // синий
        listOf(Color(0xFFC98A4A), Color(0xFF9A5E22)), // охра
        listOf(Color(0xFFB58CB6), Color(0xFF7E5180)), // слива
        listOf(Color(0xFF7FBDB2), Color(0xFF3E7E7A)), // тил
    )

    /** Детерминированный градиент лого компании по id. */
    fun companyBrushFor(id: String): Brush =
        Brush.linearGradient(companyGradients[kotlin.math.abs(id.hashCode()) % companyGradients.size])

    /** Градиент срочности «Нужно связаться»: >60 дн терракот, >14 золото, иначе сейдж. */
    fun urgencyBrush(daysSince: Long?): Brush = Brush.linearGradient(
        when {
            daysSince != null && daysSince > 60 -> gradients[0]
            daysSince != null && daysSince > 14 -> gradients[4]
            else                                -> gradients[1]
        }
    )
}

// ── Круглый аватар с инициалами (градиент по id, белые инициалы) ─────────────
// Списки — Manrope 700 (serif=false); hero/шпаргалка — Playfair (serif=true).
@Composable
fun AureliaAvatar(
    id: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    serif: Boolean = false,
    brush: Brush = AureliaAvatars.brushFor(id),
) {
    Box(
        modifier.size(size).clip(CircleShape).background(brush),
        contentAlignment = Alignment.Center
    ) {
        Text(
            AureliaAvatars.initials(name),
            color = Color.White,
            fontSize = fontSize,
            fontWeight = if (serif) FontWeight.W600 else FontWeight.W700,
            fontFamily = if (serif) AureliaSerif else AureliaSans,
        )
    }
}

// ── Шторка по спеке макета: фон --sheet, r28 сверху, граббер 40×5 ────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AureliaSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppleTheme.colors.sheet,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 14.dp, bottom = 11.dp)
                    .size(width = 40.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AppleTheme.colors.label.copy(alpha = 0.16f))
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 30.dp),
            content = content
        )
    }
}

// ── Тап-анимация масштаба (au-press: transform .12s ease; :active scale .96) ─
fun Modifier.aureliaPress(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "auPress")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
}

// ── Круглая кнопка 38dp ──────────────────────────────────────────────────────
enum class AureliaCircleStyle {
    /** Заливка акцентом, белая иконка (сканер, «+»). */
    Filled,
    /** rgba(ac,.10) + акцент-иконка (поиск, настройки). */
    Tinted,
    /** rgba(120,120,128,.10) + иконка цвета текста (назад, ⋯). */
    Neutral,
}

@Composable
fun AureliaCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    style: AureliaCircleStyle = AureliaCircleStyle.Tinted,
    size: Dp = 38.dp,
    iconSize: Dp = 19.dp,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val bg = when (style) {
        AureliaCircleStyle.Filled  -> AppleTheme.colors.brand
        AureliaCircleStyle.Tinted  -> AppleTheme.colors.brand.copy(alpha = 0.10f)
        AureliaCircleStyle.Neutral -> AppleTheme.colors.neutralFill
    }
    val tint = when (style) {
        AureliaCircleStyle.Filled  -> Color.White
        AureliaCircleStyle.Tinted  -> AppleTheme.colors.brand
        AureliaCircleStyle.Neutral -> AppleTheme.colors.label
    }
    val base = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Box(
        modifier = base.size(size).clip(CircleShape).background(bg).aureliaPress(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

/** Круглая «назад» 36dp — нейтральная заливка + шеврон (шапки push-экранов). */
@Composable
fun AureliaBackButton(
    contentDescription: String?,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    AureliaCircleButton(
        icon = Icons.Default.ChevronLeft,
        contentDescription = contentDescription,
        style = AureliaCircleStyle.Neutral,
        size = 36.dp,
        iconSize = 22.dp,
        testTag = testTag,
        onClick = onClick,
    )
}

// ── Карточка макета: фон card + inset-кольцо rgba(line,.04-.06) + мягкая тень ─
@Composable
fun AureliaCard(
    modifier: Modifier = Modifier,
    radius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val ringAlpha = if (AppleTheme.colors.isDark) 0.10f else 0.05f
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.aureliaPress(onClick = onClick) else Modifier)
            .clip(shape)
            .background(AppleTheme.colors.card)
            .border(1.dp, AppleTheme.colors.label.copy(alpha = ringAlpha), shape),
        content = content,
    )
}

// ── Стат-карта Главной: h96, r20, плитка 30/r9 @14%, Playfair-число 26 ───────
@Composable
fun AureliaStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    tile: Color,
    value: String,
    label: String,
    valueColor: Color = AppleTheme.colors.label,
    onClick: () -> Unit = {},
) {
    AureliaCard(modifier = modifier.height(96.dp), radius = 20.dp, onClick = onClick) {
        Column(
            Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tile.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = tile)
            }
            Column {
                // font:700 26px 'Playfair Display'
                Text(value, fontFamily = AureliaSerif, fontWeight = FontWeight.W700,
                    fontSize = 26.sp, lineHeight = 26.sp, color = valueColor)
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = AppleTheme.colors.secondaryLabel, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ── Заголовок секции: font:800 19px Manrope + акцент-действие 14px 600 ───────
@Composable
fun AureliaSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.W800, color = AppleTheme.colors.label)
        if (actionText != null && onAction != null) {
            Text(actionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = AppleTheme.colors.brand,
                modifier = Modifier.aureliaPress(onClick = onAction))
        }
    }
}

// ── Пульсирующая точка (au-pulse 2.6s: scale 1→.78, opacity 1→.4) ────────────
@Composable
fun AureliaPulseDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
    pulse: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "auPulse")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "auPulsePhase"
    )
    val scale = if (pulse) 1f - 0.22f * phase else 1f
    val alpha = if (pulse) 1f - 0.6f * phase else 1f
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
    )
}

// ── UPPERCASE-подпись: font:700 11px, letter-spacing .14em ───────────────────
@Composable
fun AureliaCapsLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppleTheme.colors.tertiaryLabel,
) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 0.14.em,
        color = color,
        modifier = modifier,
    )
}

// ── Канонический пикер: шторка + поиск + список строк (аватар/имя/подпись) ──
// ЕДИНЫЙ компонент для всех «добавить человека/компанию» (семья, связанные,
// сотрудник компании, место работы) — вместо разрозненных AlertDialog без
// поиска и в старом стиле. Опциональная строка «создать нового».
data class AureliaPickItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** true — градиент лого компании, false — аватар человека. */
    val isCompany: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AureliaPickerSheet(
    title: String,
    items: List<AureliaPickItem>,
    onPick: (AureliaPickItem) -> Unit,
    onDismiss: () -> Unit,
    searchPlaceholder: String = "",
    emptyText: String = "",
    createNewText: String? = null,
    onCreateNew: (() -> Unit)? = null,
) {
    val search = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val filtered = if (search.value.isBlank()) items
        else items.filter {
            it.title.contains(search.value, ignoreCase = true) ||
            it.subtitle?.contains(search.value, ignoreCase = true) == true
        }
    AureliaSheet(onDismiss = onDismiss) {
        Text(
            title,
            fontFamily = AureliaSerif, fontSize = 22.sp, fontWeight = FontWeight.W800,
            color = AppleTheme.colors.label,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        // Поиск — капсула как на списках (r13, нейтральная заливка)
        androidx.compose.material3.OutlinedTextField(
            value = search.value,
            onValueChange = { search.value = it },
            placeholder = { Text(searchPlaceholder, color = AppleTheme.colors.tertiaryLabel) },
            leadingIcon = {
                Icon(androidx.compose.material.icons.Icons.Default.Search, null,
                    Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
            },
            singleLine = true,
            shape = RoundedCornerShape(13.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
        ) {
            if (createNewText != null && onCreateNew != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .aureliaPress { onCreateNew() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Default.Add, null,
                                Modifier.size(20.dp), tint = AppleTheme.colors.brand)
                        }
                        Text(createNewText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = AppleTheme.colors.brand)
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        emptyText, fontSize = 14.sp, color = AppleTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(vertical = 18.dp, horizontal = 4.dp)
                    )
                }
            }
            items(filtered.size, key = { filtered[it].id }) { i ->
                val item = filtered[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .aureliaPress { onPick(item) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (item.isCompany) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                                .background(AureliaAvatars.companyBrushFor(item.id)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item.title.take(1).uppercase(), color = Color.White,
                                fontFamily = AureliaSerif, fontSize = 17.sp, fontWeight = FontWeight.W700)
                        }
                    } else {
                        AureliaAvatar(id = item.id, name = item.title, size = 40.dp, fontSize = 14.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = AppleTheme.colors.label,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!item.subtitle.isNullOrBlank())
                            Text(item.subtitle, fontSize = 12.sp, color = AppleTheme.colors.secondaryLabel,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
