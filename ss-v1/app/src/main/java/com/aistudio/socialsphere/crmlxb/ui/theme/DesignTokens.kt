package com.aistudio.socialsphere.crmlxb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ФИКС (аудит 2026-07-06, по прямому запросу владельца — «не точечные решения,
// а общий код, который распространяется на всё, корневой»): раньше
// MaterialTheme.shapes нигде не использовался, а вместо единого источника форм
// по кодовой базе было рассеяно ~173 хардкод-вызова RoundedCornerShape(N.dp) с
// 18 РАЗНЫМИ значениями N (3–28) — три параллельных подхода без консистентности.
// Ниже — токены под КАЖДОЕ реально использовавшееся значение (без снэппинга
// близких величин друг к другу, т.е. БЕЗ изменения фактического визуала нигде) —
// это чистая консолидация источника истины, а не редизайн скруглений.
object SocialShape {
    val R3     = RoundedCornerShape(3.dp)
    val XSmall = RoundedCornerShape(4.dp)
    val R5     = RoundedCornerShape(5.dp)
    val R6     = RoundedCornerShape(6.dp)
    val R7     = RoundedCornerShape(7.dp)
    val Small  = RoundedCornerShape(8.dp)
    val R9     = RoundedCornerShape(9.dp)
    val R10    = RoundedCornerShape(10.dp)
    val R11    = RoundedCornerShape(11.dp)
    val Medium = RoundedCornerShape(12.dp)
    val R13    = RoundedCornerShape(13.dp)
    val R14    = RoundedCornerShape(14.dp)
    val R15    = RoundedCornerShape(15.dp)
    val Large  = RoundedCornerShape(16.dp)
    val R18    = RoundedCornerShape(18.dp)
    val XLarge = RoundedCornerShape(20.dp)
    val R22    = RoundedCornerShape(22.dp)
    val Full   = RoundedCornerShape(50)
    val Card   = RoundedCornerShape(16.dp)
    // ВНИМАНИЕ: «радиус шторки» задан в ТРЁХ разных местах с ТРЕМЯ разными
    // значениями (Sheet здесь — 24dp; AppleDesignSystem.sheet — 20dp;
    // фактический угол в AureliaComponents.AureliaSheet — 28dp, см. её же
    // RoundedCornerShape(topStart=28.dp,...)). Не унифицировано в этом проходе:
    // выбор ОДНОГО значения из трёх — реальное визуальное решение (не просто
    // консолидация кода), которое надо проверять живьём на устройстве, не вслепую.
    val Sheet  = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val Search = RoundedCornerShape(28.dp)
    val Chip   = RoundedCornerShape(8.dp)
}
