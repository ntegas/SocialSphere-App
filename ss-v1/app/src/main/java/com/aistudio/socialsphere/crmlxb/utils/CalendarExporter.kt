package com.aistudio.socialsphere.crmlxb.utils

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Односторонний экспорт наших событий (ДР, звонки, встречи…) в системный
 * календарь телефона через CalendarContract. Требует READ_CALENDAR +
 * WRITE_CALENDAR (запрашиваются в UI до вызова). Повторный экспорт не плодит
 * дубли: пропускаем событие, если в целевом календаре уже есть запись с тем же
 * заголовком и временем начала.
 */
object CalendarExporter {

    data class Result(
        val inserted: Int = 0,
        val skipped: Int = 0,
        val ok: Boolean = false,
        val noCalendar: Boolean = false
    )

    /** Первый доступный для записи календарь устройства. */
    private fun writableCalendarId(context: Context): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        return try {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                val idIdx  = c.getColumnIndex(CalendarContract.Calendars._ID)
                val accIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                while (c.moveToNext()) {
                    val acc = if (accIdx >= 0) c.getInt(accIdx) else 0
                    if (acc >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                        return c.getLong(idIdx)
                }
                null
            }
        } catch (e: Exception) { null }
    }

    /** Время начала события в миллисекундах. Весь день → полночь UTC. */
    private fun startMillis(item: CalendarItem): Long? = try {
        val date = LocalDate.parse(item.startDate.take(10))
        if (item.isAllDay) {
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            val time = item.startTime?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)
            date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    } catch (e: Exception) { null }

    private fun eventExists(context: Context, calId: Long, title: String, dtStart: Long): Boolean {
        val sel = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DTSTART}=?"
        val args = arrayOf(calId.toString(), title, dtStart.toString())
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID), sel, args, null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) { false }
    }

    fun exportAll(context: Context): Result {
        val calId = writableCalendarId(context) ?: return Result(noCalendar = true)
        var inserted = 0
        var skipped = 0
        val dayMs = 24L * 60 * 60 * 1000
        AppStateStore.calendarItems.toList().forEach { item ->
            val start = startMillis(item) ?: return@forEach
            val title = calendarDisplayTitle(item.title, item.type, context)
            if (eventExists(context, calId, title, start)) { skipped++; return@forEach }
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                if (item.isAllDay) {
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.DTEND, start + dayMs)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    put(CalendarContract.Events.DTEND, start + 60L * 60 * 1000)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                // RRULE как есть («FREQ=YEARLY» для ДР) — системный календарь
                // понимает тот же формат, что хранится у нас.
                item.recurrenceRule?.takeIf { it.isNotBlank() }
                    ?.let { put(CalendarContract.Events.RRULE, it) }
                item.description?.takeIf { it.isNotBlank() }
                    ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            try {
                context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                inserted++
            } catch (e: Exception) { /* пропускаем отдельное событие */ }
        }
        return Result(inserted = inserted, skipped = skipped, ok = true)
    }
}
