package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.CalendarItemType
import com.aistudio.socialsphere.crmlxb.model.Contact
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.utils.calendarDisplayTitle
import com.aistudio.socialsphere.crmlxb.utils.parseFlexibleDate

/**
 * Блок «Важные даты» контакта — ОДИН код для всех мест карточки (вкладка «Обзор» и вкладка «Подарки»).
 *
 * Жалоба владельца 2026-10-28: «приложение дало оповещение о дне рождения, привязанное к контакту, а в самом
 * контакте дня рождения нет; в одних контактах есть, в других нет». Корень: на вкладке «Обзор» (она открывается
 * первой) дат не было вовсе — только «Ближайшие события» (не больше 5, только будущие и активные), а «Важные даты»
 * лежали на вкладке «Подарки». Если у контакта хватало других событий или дата была прошедшей/завершённой — день
 * рождения на «Обзоре» не появлялся. Теперь «Обзор» показывает все даты контакта этим же блоком
 * ([contactCardDates]: без лимита, без фильтра по статусу и по «будущее»), а «Ближайшие» — только остальные события.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.contactImportantDatesItem(
    contact: Contact,
    onNavigateToCalendarItem: (String) -> Unit,
) {
    item {
        val today = java.time.LocalDate.now()
        val importantDates = contactCardDates(AppStateStore.calendarItems, contact.id)
        if (importantDates.isNotEmpty()) {
            CardBlock(title = stringResource(R.string.cd_important_dates)) {
                importantDates.forEach { date ->
                    val daysUntil = try {
                        val d = parseFlexibleDate(date.startDate) ?: error("bad date")
                        // Переносим на текущий год; недавно прошедшие (≤30 дн.)
                        // оставляем в прошлом — покажется «N дн. назад»
                        val thisYear = d.withYear(today.year)
                        val next = if (thisYear.isBefore(today.minusDays(30))) thisYear.plusYears(1) else thisYear
                        java.time.temporal.ChronoUnit.DAYS.between(today, next)
                    } catch (e: Exception) { null }

                    // «Через N дней» — отдельной строкой ПОД названием (фидбэк владельца:
                    // сбоку метка сжималась длинным заголовком и выглядела сбитой).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToCalendarItem(date.id) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        val emoji = when (date.type) {
                            CalendarItemType.BIRTHDAY    -> "🎂"
                            CalendarItemType.ANNIVERSARY -> "💍"
                            else                         -> "⭐"
                        }
                        Text(emoji, fontSize = 16.sp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                calendarDisplayTitle(date.title, date.type, LocalContext.current),
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (daysUntil != null) {
                                // Явная не-nullable копия: K2 не делал smart-cast,
                                // и -daysUntil давал ambiguity unaryMinus
                                val du: Long = daysUntil
                                Text(
                                    when {
                                        du == 0L -> stringResource(R.string.cd_today_party)
                                        du > 0L  -> String.format(stringResource(R.string.home_in_days), du)
                                        else     -> String.format(stringResource(R.string.home_days_ago), -du)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        du in 0L..7L  -> AppleTheme.colors.red
                                        du in 8L..30L -> AppleTheme.colors.orange
                                        else          -> AppleTheme.colors.secondaryLabel
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
