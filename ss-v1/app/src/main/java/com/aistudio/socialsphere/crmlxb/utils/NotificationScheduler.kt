package com.aistudio.socialsphere.crmlxb.utils

import com.aistudio.socialsphere.crmlxb.R
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.model.CalendarItem
import com.aistudio.socialsphere.crmlxb.model.ReminderRule
import com.aistudio.socialsphere.crmlxb.model.ReminderType
import com.aistudio.socialsphere.crmlxb.model.ReminderOffsetUnit
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.data.local.toDomain

object NotificationScheduler {

    /**
     * Ставит будильник: точный, если есть разрешение (Android <12 — всегда;
     * 12+ — по canScheduleExactAlarms()), иначе неточный, но переживающий doze
     * (setAndAllowWhileIdle). Проверяем разрешение ЗАРАНЕЕ, чтобы не ловить
     * SecurityException на каждом будильнике (он спамил лог).
     */
    private fun scheduleAlarm(am: AlarmManager, triggerAtMillis: Long, pi: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                return
            } catch (e: SecurityException) {
                Log.w("NotificationScheduler", "Exact alarm denied at runtime, falling back to inexact")
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    /**
     * AppSettings, гарантированно инициализированный. Планировщик вызывается и из
     * фоновых путей, где MainActivity не запускалась (BOOT_COMPLETED, будильники):
     * там AppSettings.init() ещё никто не вызвал, и первое же чтение настройки
     * бросало IllegalStateException("AppSettings.init() not called") — после
     * перезагрузки rescheduleAll() падал на первом напоминании и не восстанавливал
     * ни одного. init() идемпотентен; каждое чтение настроек в планировщике идёт
     * через эту функцию, чтобы ни один вызывающий не зависел от MainActivity.
     */
    private fun settings(context: Context): AppSettings {
        AppSettings.init(context)
        return AppSettings
    }

    /**
     * INVARIANT: будильник напоминания может существовать только для
     * `CalendarItem.status == ACTIVE`. Этот барьер — ЕДИНСТВЕННОЕ место, где
     * правило проверяется: любой caller (rescheduleReminders/rescheduleAll,
     * будущие пути) автоматически получает его бесплатно, вместо того чтобы
     * каждый экран (CalendarItemEditScreen, «Перенести» в
     * CalendarItemDetailScreen) дублировал одну и ту же проверку статуса.
     * Раньше её не было вовсе: полный выпадающий список статусов в редакторе
     * позволял сохранить событие сразу как COMPLETED/CANCELLED, а кнопка
     * «Перенести» не проверяла статус — оба пути молча ставили будильник
     * событию, которое пользователь уже считает завершённым/отменённым.
     */
    fun scheduleReminder(context: Context, calendarItem: CalendarItem, reminderRule: ReminderRule) {
        trySchedule(context, calendarItem, reminderRule)
    }

    /** true — будильник поставлен; false — по канону его быть не должно (не ACTIVE, master OFF, время прошло/невалидно).
     *  Reconcile использует результат, чтобы СНЯТЬ будильник, который канон уже не предполагает. */
    private fun trySchedule(context: Context, calendarItem: CalendarItem, reminderRule: ReminderRule): Boolean {
        if (calendarItem.status != com.aistudio.socialsphere.crmlxb.model.CalendarItemStatus.ACTIVE) return false
        if (!settings(context).isNotificationsEnabled.value) return false

        NotificationHelper.createNotificationChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val scheduled = calculateNotificationTime(calendarItem, reminderRule)
        if (scheduled == null || scheduled.triggerAtMillis < System.currentTimeMillis()) {
            return false // Date is in the past or invalid
        }

        // Будильник — только СИГНАЛ «пора проверить событие»: id события,
        // идентичность напоминания (notificationId = событие|правило) и, для
        // recurring-режимов, occurrenceDate — техническая identity КОНКРЕТНОГО
        // occurrence, относительно которого посчитан этот alarm (requirement 6:
        // «Готово» из уведомления должно завершить именно ЕГО, а не occurrence,
        // вычисленное заново на момент нажатия — событие может прозвонить в
        // среду, а «Готово» нажато позже). Ни заголовок, ни имена, ни телефон
        // сюда не кладутся: будильник может сработать через недели, когда
        // контакт уже переименован, сменил номер или удалён, а событие
        // выполнено/отменено/удалено. Всё содержимое уведомления
        // NotificationReceiver собирает из Room в момент срабатывания
        // (resolveCalendarReminder). Раньше здесь «запекались» title/content/phone/
        // channel, причём имена и телефон брались из памяти AppStateStore — после
        // перезагрузки она пуста, и восстановленные напоминания приходили без них.
        val intent = alarmIntent(context, AlarmKind.REMINDER, calendarItem.id, reminderRule.id).apply {
            putExtra("calendarItemId", calendarItem.id)
            putExtra("notificationId", getNotificationId(calendarItem.id, reminderRule.id))
            scheduled.occurrenceDate?.let { putExtra("occurrenceDate", it.toString()) }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getNotificationId(calendarItem.id, reminderRule.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(alarmManager, scheduled.triggerAtMillis, pendingIntent)
        return true
    }

    // ── ALARM IDENTITY (Calendar Runtime & Alarm Lifecycle Pack) ─────────────────────────────────────────
    // Раньше PendingIntent будильника адресовался ТОЛЬКО requestCode = hash(calendarItemId|ruleId); extras в identity
    // Android НЕ входят (Intent.filterEquals их игнорирует), поэтому два разных напоминания с одинаковым int-хэшем получали
    // ОДИН PendingIntent, и второе молча заменяло первое. Теперь identity интента — компонент + data-URI
    // `socialsphere-alarm://<kind>/<calendarItemId>/<discriminator>`; requestCode остаётся стабильным, но столкновение
    // requestCode больше не сливает разные логические напоминания. Один и тот же построитель — для постановки, отмены и
    // перепостановки. Никакого пользовательского текста в identity нет, только стабильные id из Room.
    private const val ALARM_URI_SCHEME = "socialsphere-alarm"

    private enum class AlarmKind(val authority: String) {
        /** Основной будильник напоминания (правило события); discriminator = id правила. */
        REMINDER("reminder"),
        /** «Отложить» конкретного показанного уведомления; discriminator = notificationId. Живёт НЕЗАВИСИМО от REMINDER:
         *  reconcile/правка события перепоставляют основной будильник и не затирают отложенный (и наоборот). */
        SNOOZE("snooze"),
    }

    private fun alarmIntent(context: Context, kind: AlarmKind, calendarItemId: String?, discriminator: String): Intent =
        Intent(context, NotificationReceiver::class.java).apply {
            data = android.net.Uri.Builder().scheme(ALARM_URI_SCHEME).authority(kind.authority)
                .appendPath(calendarItemId ?: "-").appendPath(discriminator).build()
        }

    private fun reminderPendingIntent(context: Context, calendarItemId: String, reminderRuleId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, getNotificationId(calendarItemId, reminderRuleId),
            alarmIntent(context, AlarmKind.REMINDER, calendarItemId, reminderRuleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Identity будильников, поставленных ДО этого пакета (только компонент + requestCode, без data). После обновления
     *  приложения такой будильник мог пережить систему — снимаем и его, иначе рядом встал бы дубль под новой identity. */
    private fun legacyReminderPendingIntent(context: Context, calendarItemId: String, reminderRuleId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, getNotificationId(calendarItemId, reminderRuleId),
            Intent(context, NotificationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private const val STALE_REQUEST_CODE = 770001

    /** Единая идентичность будильника ежедневной проверки — используется и
     *  планированием (scheduleStaleCheck), и безусловной отменой (cancelStaleCheck),
     *  чтобы оба всегда адресовали ровно один и тот же PendingIntent. */
    private fun staleCheckPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StaleContactsReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, STALE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Планирует ежедневную проверку «пора связаться» на ближайшие 10:00.
     *  Receiver после показа переустанавливает себя на следующий день. */
    fun scheduleStaleCheck(context: Context) {
        val settings = settings(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = staleCheckPendingIntent(context)
        val anyDaily = settings.remindStaleContacts.value ||
            settings.remindBirthdays.value || settings.remindNoNextStep.value
        if (!settings.isNotificationsEnabled.value || !anyDaily) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(10, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val fireAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        scheduleAlarm(alarmManager, fireAt, pendingIntent)
    }

    /**
     * Безусловная отмена ежедневной проверки — БЕЗ учёта настроек и БЕЗ
     * переустановки (в отличие от scheduleStaleCheck, у которого cancel —
     * лишь одна из веток). Используется wipeAllData(): после полного удаления
     * данных будильник не должен ни сработать со старым состоянием, ни
     * автоматически переустановиться внутри самого wipe — его восстановление
     * происходит обычным lifecycle приложения (BootReceiver/включение
     * настройки), не как часть удаления данных.
     */
    fun cancelStaleCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(staleCheckPendingIntent(context))
    }

    /**
     * Единая точка отмены ВСЕХ scheduled alarms, способных позже показать или
     * использовать удалённые пользовательские данные: будильники напоминаний
     * КАЖДОГО переданного CalendarItem + ежедневная проверка «пора связаться».
     * Вызывается wipeAllData() ДО очистки Room — caller (экран приватности)
     * не должен знать requestCode ни одного типа будильника, вся адресация
     * (getNotificationId/STALE_REQUEST_CODE) остаётся здесь же, где и
     * создаётся, той же логикой, что использует обычная отмена/reschedule.
     */
    fun cancelAllUserDataAlarms(context: Context, calendarItems: List<CalendarItem>) {
        calendarItems.forEach { item -> cancelAllRemindersForCalendarItem(context, item.reminders) }
        cancelStaleCheck(context)
    }

    /**
     * OFF cleanup master-переключателя уведомлений — Room-backed, НЕ in-memory
     * снимок. В отличие от wipeAllData() (которому нужен именно снимок ДО
     * удаления Room, потому что после wipe Room уже пуста и читать оттуда
     * нечего — cancelAllUserDataAlarms(List<CalendarItem>) выше остаётся
     * НЕТРОНУТ и продолжает обслуживать именно этот сценарий), здесь Room не
     * удаляется — она persisted и неизменна на момент вызова, поэтому нет
     * причины полагаться на то, что AppStateStore.calendarItems успела
     * загрузиться в память (может быть пуста в холодном состоянии — та же
     * причина, по которой reconcileAllUserDataAlarms() читает db, а не
     * память, для ON). Переиспользует те же canonical примитивы отмены
     * (cancelAllRemindersForCalendarItem/cancelStaleCheck), меняется только
     * источник списка событий/правил.
     */
    suspend fun cancelAllUserDataAlarmsFromRoom(context: Context, db: SocialsphereDatabase) {
        val calendars = db.calendarDao().getAllCalendarItems()
        val reminderRules = db.calendarDao().getReminderRules()
        calendars.forEach { entity ->
            val reminders = reminderRules.filter { it.calendarItemId == entity.id }.map { it.toDomain() }
            cancelAllRemindersForCalendarItem(context, reminders)
        }
        cancelStaleCheck(context)
    }

    /**
     * «Отложить»: переустановить то же уведомление на сутки вперёд. Переносим все
     * extra из исходного интента (без action), чтобы кнопки звонка/SMS остались.
     * Тот же canonical барьер master-флага, что и у scheduleReminder/
     * scheduleStaleCheck — не полагаемся на то, что caller (NotificationReceiver.
     * ACTION_SNOOZE) сам проверит: планировщик защищает инвариант сам, иначе
     * уже показанное уведомление можно отложить ПОСЛЕ выключения master
     * notifications и получить новый alarm в обход единственного места, где
     * это правило должно проверяться.
     */
    fun scheduleSnooze(context: Context, src: Intent) {
        if (!settings(context).isNotificationsEnabled.value) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nid = src.getIntExtra("notificationId", 0)
        val fireAt = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val calendarItemId = src.getStringExtra("calendarItemId")
        // SNOOZE-identity (см. ALARM IDENTITY): отложенный будильник НЕ занимает identity основного напоминания — reconcile
        // и правка события не затирают snooze, а snooze не затирает будильник другого напоминания. Повторное «Отложить» того
        // же уведомления заменяет прежний snooze (одно отложенное срабатывание на уведомление). Snooze — разовое
        // срабатывание и в Room не хранится: после перезагрузки/остановки приложения оно не восстанавливается.
        val intent = alarmIntent(context, AlarmKind.SNOOZE, calendarItemId, nid.toString()).apply {
            putExtra("notificationId", nid)
            if (calendarItemId != null) {
                // Напоминание о событии: как и у исходного будильника — только
                // идентичность, содержимое соберётся из Room при срабатывании.
                putExtra("calendarItemId", calendarItemId)
                // occurrenceDate (recurring-события) переносится из исходного
                // уведомления — «Отложить», а затем «Готово» позже обязаны
                // завершить ТО ЖЕ occurrence, не вычисленное заново (requirement E).
                src.getStringExtra("occurrenceDate")?.let { putExtra("occurrenceDate", it) }
            } else {
                // Уведомление без события (легаси, отложенное до нового набора кнопок):
                // собрать его заново не из чего, поэтому переносим показанный текст.
                putExtra("title", src.getStringExtra("title"))
                putExtra("content", src.getStringExtra("content"))
                // Без этого «Пора связаться»/«День рождения» после «Отложить» переоткрывались
                // бы в дефолтном канале событий вместо своего (баг найден вместе с §36).
                src.getStringExtra("channel")?.let { putExtra("channel", it) }
                src.getStringExtra("phone")?.let { putExtra("phone", it) }
            }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, nid, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleAlarm(alarmManager, fireAt, pendingIntent)
    }

    fun cancelReminder(context: Context, calendarItemId: String, reminderRuleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Та же identity, что при постановке; плюс legacy-identity (переходный период после обновления приложения).
        alarmManager.cancel(reminderPendingIntent(context, calendarItemId, reminderRuleId))
        cancelLegacyReminder(context, calendarItemId, reminderRuleId)
    }

    private fun cancelLegacyReminder(context: Context, calendarItemId: String, reminderRuleId: String) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(legacyReminderPendingIntent(context, calendarItemId, reminderRuleId))
    }

    fun cancelAllRemindersForCalendarItem(context: Context, oldReminders: List<ReminderRule>) {
        oldReminders.forEach {
            cancelReminder(context, it.calendarItemId, it.id)
        }
    }

    /**
     * Сначала отменяет ВСЕ старые будильники события (по старому списку правил —
     * старые id могли отличаться от новых после правки), затем пытается
     * поставить актуальные. Если `calendarItem.status != ACTIVE`, ни один новый
     * будильник не появится — это гарантирует барьер внутри scheduleReminder(),
     * не отдельная проверка здесь: caller (экран редактирования, «Перенести»)
     * может сохранить событие с любым статусом, не заботясь об инварианте сам.
     */
    fun rescheduleReminders(context: Context, contextOldReminders: List<ReminderRule>, calendarItem: CalendarItem) {
        cancelAllRemindersForCalendarItem(context, contextOldReminders)
        calendarItem.reminders.forEach {
            scheduleReminder(context, calendarItem, it)
        }
    }

    suspend fun rescheduleAll(context: Context, db: SocialsphereDatabase) {
        val calendars = db.calendarDao().getAllCalendarItems()
        val calendarLinks = db.calendarDao().getCalendarItemLinks()
        val reminderRules = db.calendarDao().getReminderRules()

        calendars.forEach { entity ->
            // Одно сломанное событие/правило не должно отменять восстановление остальных.
            try {
                val links = calendarLinks.filter { it.calendarItemId == entity.id }.map { it.toDomain() }
                val reminders = reminderRules.filter { it.calendarItemId == entity.id }.map { it.toDomain() }
                val item = entity.toDomain().copy(links = links, reminders = reminders)
                // Только ACTIVE получает будильник (баг §34) — барьер живёт внутри trySchedule(), единственное место правила.
                item.reminders.forEach { rule ->
                    try {
                        // Room — канон: будильник обязан ОТРАЖАТЬ пересчитанное состояние. Если правило по канону больше не
                        // даёт будильника (после смены часового пояса/времени событие оказалось в прошлом, событие
                        // завершено/отменено, master OFF), стоявший до системного события будильник СНИМАЕТСЯ —
                        // иначе он сработал бы по устаревшему epoch. Если даёт — та же identity просто заменяется (без дублей).
                        if (trySchedule(context, item, rule)) cancelLegacyReminder(context, item.id, rule.id)
                        else cancelReminder(context, item.id, rule.id)
                    } catch (e: Exception) {
                        Log.e("NotificationScheduler", "rescheduleAll: item ${item.id} rule ${rule.id} skipped", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("NotificationScheduler", "rescheduleAll: item ${entity.id} skipped", e)
            }
        }
    }

    /**
     * ЕДИНЫЙ canonical reconcile-path Calendar/Reminder alarms: master-переключатель
     * уведомлений (OFF → ON), restoreBackup() (после restore) И обычный старт
     * приложения/перезагрузка — один и тот же вызов, три разные реализации
     * правил планирования не заводятся (см. BootReceiver/MainActivity/
     * AppStateStore.restoreBackup — все вызывают именно эту функцию).
     * Каноничный источник — Room (rescheduleAll читает db напрямую, НЕ
     * in-memory AppStateStore.calendarItems), поэтому корректно работает и из
     * холодного состояния, когда память ещё пуста (BroadcastReceiver до
     * MainActivity, или сразу после restore). rescheduleAll уже фильтрует по
     * status == ACTIVE и (через scheduleReminder → calculateNotificationTime)
     * по прошедшему времени, а master-флаг проверяется ВНУТРИ scheduleReminder/
     * scheduleStaleCheck — единственный барьер везде, второй список/проверка
     * здесь не заводятся. FLAG_UPDATE_CURRENT у PendingIntent делает повторные
     * вызовы идемпотентными: OFF→ON→ON или повторный старт не плодят дубликаты.
     * Шаги изолированы каждый в своём try/catch (перенесено из BootReceiver,
     * теперь общее для всех callers): сбой восстановления напоминаний не
     * должен отменять ежедневную проверку «пора связаться», и наоборот.
     */
    suspend fun reconcileAllUserDataAlarms(context: Context, db: SocialsphereDatabase) {
        try {
            rescheduleAll(context, db)
        } catch (e: Exception) {
            Log.e("NotificationScheduler", "reconcileAllUserDataAlarms: rescheduleAll failed", e)
        }
        try {
            scheduleStaleCheck(context)
        } catch (e: Exception) {
            Log.e("NotificationScheduler", "reconcileAllUserDataAlarms: scheduleStaleCheck failed", e)
        }
    }

    private fun getNotificationId(calendarItemId: String, reminderRuleId: String): Int {
        return (calendarItemId + "|" + reminderRuleId).hashCode()
    }

    /**
     * Будет ли это напоминание реально отправлено. ЕДИНЫЙ источник истины с
     * scheduleReminder(): та же calculateNotificationTime + то же сравнение с «сейчас».
     * ФИКС §28: UI раньше сравнивал только ДАТУ события и показывал «Запланировано»
     * для напоминаний, чьё время уже прошло и будильник НЕ ставился (подтверждено
     * dumpsys alarm) — владелец видел «Запланировано» у мёртвого напоминания.
     */
    fun isReminderScheduled(calendarItem: CalendarItem, reminderRule: ReminderRule): Boolean {
        val scheduled = calculateNotificationTime(calendarItem, reminderRule) ?: return false
        return scheduled.triggerAtMillis >= System.currentTimeMillis()
    }

    /** Результат calculateNotificationTime: время срабатывания + occurrence,
     *  относительно которого оно посчитано (null для CUSTOM_DATE_TIME — там
     *  нет понятия occurrence, абсолютная точка времени). */
    private data class ScheduledOccurrence(val triggerAtMillis: Long, val occurrenceDate: LocalDate?)

    // Небольшой защитный предел на поиск occurrence, чьё reminder-time ещё не
    // прошло (см. calculateNotificationTime). Реальные offset'ы в этом проекте —
    // максимум WEEK_1 (7 дней, см. ReminderTime в CalendarItemEditScreen), поэтому
    // при DAILY-событии цикл заканчивается за ≤8 итераций; предел — чисто defensive
    // backstop против необычных/импортированных данных, не ожидается, что он
    // когда-либо реально достигается. Каждая итерация — O(1) вызов калькулятора
    // (не сканирование дней), так что даже предел не даёт квадратичной стоимости.
    private const val MAX_OCCURRENCE_SEARCH_ITERATIONS = 500

    /**
     * INVARIANT: планировщик сам НЕ вычисляет recurrence — единственный источник
     * дат occurrence для ЛЮБОГО из NONE/DAILY/WEEKLY/MONTHLY/YEARLY —
     * canonical RecurrenceCalculator (см. её собственный файл и тесты). Здесь
     * только: anchor = ОРИГИНАЛЬНЫЙ calendarItem.startDate (никогда — уже
     * вычисленный occurrence, иначе см. anti-drift инвариант калькулятора для
     * MONTHLY 31-го числа и YEARLY 29 февраля), режим — через
     * effectiveRecurrenceMode() (единая compatibility policy: legacy BIRTHDAY/
     * ANNIVERSARY/NAMEDAY без recurrenceRule по-прежнему YEARLY, а не отдельная
     * type-проверка здесь), затем time-of-day + offset поверх occurrence-даты,
     * а не наоборот.
     *
     * CUSTOM_DATE_TIME/NONE — не occurrence-based напоминания вообще (абсолютная
     * точка времени и «нет напоминания» соответственно), поэтому обрабатываются
     * до обращения к recurrence и никогда не продвигаются калькулятором. Раньше
     * (до этого фикса) YEARLY-ветка применялась безусловно и могла «продвинуть»
     * даже CUSTOM_DATE_TIME на следующий год — так как CUSTOM_DATE_TIME нигде не
     * создаётся текущим редактором и представляет абсолютную дату, а не
     * привязанную к occurrence события, это было непреднамеренным побочным
     * эффектом, не сохраняю его.
     *
     * Первый кандидат ищется через canonical CalendarItem.pendingOccurrence()
     * (та же функция, что и effectiveDate()/CalendarCompletion) — учитывает
     * lastCompletedOccurrenceDate: уже завершённое occurrence не получит
     * повторный alarm (requirement 3). Anchor внутри неё — calendarItem.startDate,
     * НЕ lastCompletedOccurrenceDate (anti-drift не нарушается).
     *
     * requirement 9 (2026-09-17, postpone runtime semantics): триггер-время
     * считается от EFFECTIVE даты occurrence (после postpone — от даты
     * переноса), а occurrence identity в результирующем ScheduledOccurrence —
     * ВСЕГДА seriesDate (исходная дата серии), НЕ effectiveDate. Пример: weekly
     * Sep16 10:00, postponed → Sep18, «за 1 день» — trigger Sep17 10:00, но
     * payload.occurrenceDate = Sep16 (completion должна завершить именно его).
     * Это касается ТОЛЬКО первого кандидата — если его reminder-time уже
     * прошло и цикл ниже ищет следующее occurrence через raw calculator,
     * дальнейшие occurrence — всегда regular (postpone переносит максимум одно,
     * текущее pending, occurrence), поэтому identity/timing там совпадают.
     */
    private fun calculateNotificationTime(calendarItem: CalendarItem, reminderRule: ReminderRule): ScheduledOccurrence? {
        try {
            if (reminderRule.reminderType == ReminderType.NONE) return null

            if (reminderRule.reminderType == ReminderType.CUSTOM_DATE_TIME) {
                val exact = reminderRule.exactDateTime?.takeIf { it.isNotEmpty() } ?: return null
                val exactDateTime = LocalDateTime.parse(exact, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                return ScheduledOccurrence(exactDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), null)
            }

            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

            // Anchor. startDate по проекту — ISO, но в данных бывают legacy-формы
            // («15 08 2025», «30 мая»); parseFlexibleDate их разбирает, иначе напоминание
            // тихо терялось (DateTimeParseException). null — форму не распознать → пропуск.
            val anchorDate = parseFlexibleDate(calendarItem.startDate) ?: run {
                Log.w("NotificationScheduler", "Unparseable startDate: '${calendarItem.startDate}' (item ${calendarItem.id})")
                return null
            }
            val baseTime = if (calendarItem.isAllDay) {
                // Default all day time is 09:00
                LocalTime.of(9, 0)
            } else if (!calendarItem.startTime.isNullOrEmpty()) {
                LocalTime.parse(calendarItem.startTime, timeFormatter)
            } else {
                LocalTime.of(9, 0)
            }

            val recurrenceMode = effectiveRecurrenceMode(calendarItem.type, calendarItem.recurrenceRule, calendarItem.recurrenceExplicitlySet)
            val now = LocalDateTime.now()
            val firstPending = calendarItem.pendingOccurrence(now.toLocalDate())
            // timingDate — база для расчёта trigger-времени (effectiveDate, учитывает
            // postpone); identityDate — occurrence identity в результате (seriesDate,
            // НИКОГДА не effectiveDate, requirement 9). Для первого кандидата могут
            // различаться; для всех последующих (см. конец цикла) — всегда совпадают.
            var timingDate: LocalDate? = firstPending?.effectiveDate
            var identityDate: LocalDate? = firstPending?.seriesDate

            repeat(MAX_OCCURRENCE_SEARCH_ITERATIONS) {
                val tDate = timingDate
                    ?: return null // NONE уже прошёл ИЛИ калькулятор больше не находит occurrence — не ищем несуществующее
                val iDate = identityDate ?: tDate

                val eventTime = LocalDateTime.of(tDate, baseTime)   // эффективное время occurrence (после postpone — перенесённое)
                var candidate = eventTime
                when (reminderRule.reminderType) {
                    ReminderType.AT_TIME -> { /* candidate remains occurrence time */ }
                    ReminderType.BEFORE -> {
                        val value = reminderRule.offsetValue?.toLong() ?: return null
                        candidate = when (reminderRule.offsetUnit) {
                            ReminderOffsetUnit.MINUTES -> candidate.minusMinutes(value)
                            ReminderOffsetUnit.HOURS -> candidate.minusHours(value)
                            ReminderOffsetUnit.DAYS -> candidate.minusDays(value)
                            ReminderOffsetUnit.WEEKS -> candidate.minusWeeks(value)
                            else -> candidate
                        }
                    }
                    else -> Unit // NONE/CUSTOM_DATE_TIME уже обработаны выше — сюда не попадают
                }

                if (!candidate.isBefore(now)) {
                    val millis = candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    // occurrenceDate identity нужна только recurring-режимам — для
                    // NONE «занятие» неотличимо от status и не несёт смысла completion.
                    val identity = if (recurrenceMode == RecurrenceMode.NONE) null else iDate
                    return ScheduledOccurrence(millis, identity)
                }

                // MISSED PRE-REMINDER (Calendar Runtime & Alarm Lifecycle Pack): BEFORE-триггер текущего pending occurrence уже
                // прошёл, НО само эффективное occurrence ещё впереди. Раньше здесь occurrence пропускалось и напоминание уходило
                // на СЛЕДУЮЩЕЕ регулярное — тогда как Calendar/Upcoming продолжает показывать именно это (например, перенесённое
                // Sep18 при «за 1 день» и postpone в Sep17 15:00): UI и AlarmManager описывали разные occurrence. Теперь —
                // fallback на время самого occurrence; identity остаётся seriesDate (payload/«Готово» не меняются). Не
                // «сейчас»: немедленный catch-up-fallback показывался бы повторно при каждом reconcile/старте. К следующему
                // occurrence переходим, только когда прошло и само эффективное время.
                if (reminderRule.reminderType == ReminderType.BEFORE && !eventTime.isBefore(now)) {
                    val identity = if (recurrenceMode == RecurrenceMode.NONE) null else iDate
                    return ScheduledOccurrence(eventTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), identity)
                }

                // Occurrence уже наступило/прошло (AT_TIME, либо и время самого occurrence позади) —
                // просим calculator о СЛЕДУЮЩЕМ occurrence
                // после ТЕКУЩЕЙ SERIES identity (iDate, не tDate — следующее regular
                // occurrence всегда считается от серии, не от даты переноса), а не
                // считаем сдвиг сами. Дальше identity/timing всегда совпадают —
                // postpone переносит максимум одно (текущее pending) occurrence.
                val next = RecurrenceCalculator.nextOccurrenceOnOrAfter(anchorDate, recurrenceMode, iDate.plusDays(1))
                timingDate = next
                identityDate = next
            }
            return null
        } catch (e: Exception) {
            Log.e("NotificationScheduler", "Error calculating date", e)
            return null
        }
    }
}
