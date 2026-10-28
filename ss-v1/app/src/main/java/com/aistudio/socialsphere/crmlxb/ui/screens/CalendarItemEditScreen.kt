@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.utils.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.ui.components.DatePickerField
import com.aistudio.socialsphere.crmlxb.ui.components.TimePickerField
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.model.ReminderTime
import com.aistudio.socialsphere.crmlxb.model.RecurrenceMode

/**
 * Статусы, которые редактор предлагает выбрать. Тот же canonical
 * CalendarCompletion.offersCompletion(), что и у кнопки «Готово» на экране
 * события — второго списка date-only типов не заводим. Для НОВОГО события
 * (isEditMode == false) «Выполнено» не предлагается вообще, независимо от
 * типа: create идёт через addCalendarItem(), а не через markCalendarItemCompleted()
 * (см. persistCalendarItemEdit) — событие должно сперва существовать как
 * обычное, completion выполняется уже существующим единым completion path.
 * internal (не private), чтобы юнит-тест мог проверить INVARIANT 2 напрямую:
 * DropdownMenu Material3 не отдаёт свои пункты в semantics-дерево под
 * Robolectric (подтверждено: количество кликабельных узлов не меняется до/после
 * открытия ЛЮБОГО DropdownMenu на этом экране, включая нетронутый список
 * важности), поэтому сам список опций — единственная тестируемая точка.
 */
internal fun selectableCalendarStatusOptions(type: CalendarItemType, isEditMode: Boolean): List<CalendarItemStatus> =
    CalendarItemStatus.values().filter {
        it != CalendarItemStatus.COMPLETED || (isEditMode && CalendarCompletion.offersCompletion(type))
    }

/**
 * Defense-in-depth save-path — transition- И type-aware: учитывает не только
 * rawStatus, но и isEditMode/originalItem.status/originalItem.type против
 * НОВОГО type, чтобы отличить настоящий НОВЫЙ переход в COMPLETED от уже
 * persisted значения. rawStatus != COMPLETED — sanitizer прозрачен (только
 * COMPLETED подлежит defense-in-depth, второго списка типов/статусов не заводим,
 * только canonical CalendarCompletion.offersCompletion()):
 *  1) create (isEditMode == false) → ACTIVE — «Выполнено» никогда не
 *     сохраняется сразу при создании, ни для какого типа (см.
 *     selectableCalendarStatusOptions); событие должно быть сперва создано
 *     обычным, а завершено — уже существующим единым completion path.
 *  2) EDIT, originalItem.status == COMPLETED (значение уже persisted, в т.ч.
 *     legacy date-only COMPLETED из версий ДО фикса INVARIANT 2) — это НЕ
 *     новый переход:
 *      а) type не менялся → COMPLETED сохраняется как есть (Data Safety,
 *         НЕЗАВИСИМО от того, поддерживает ли этот тип завершение сейчас —
 *         иначе легитимная legacy-запись молча портится обычной правкой);
 *      б) type изменился и ОБА типа (старый и новый) поддерживают completion →
 *         COMPLETED переносится (не новое completion-действие, оба конца
 *         перехода — валидные завершаемые типы);
 *      в) type изменился и хотя бы один из двух НЕ поддерживает completion →
 *         COMPLETED не переносится, сохраняется ACTIVE (иначе получится либо
 *         «завершённая дата», либо тихо потерянный interaction-completion).
 *  3) EDIT, originalItem.status != COMPLETED (настоящая ПОПЫТКА нового
 *     перехода) — разрешено ТОЛЬКО если НОВЫЙ type поддерживает completion;
 *     тогда persistCalendarItemEdit() увидит originalItem.status != COMPLETED
 *     && newItem.status == COMPLETED и сам уйдёт в общий markCalendarItemCompleted()
 *     path — здесь ничего дополнительно вызывать не нужно.
 */
internal fun sanitizeCalendarItemStatusForSave(
    isEditMode: Boolean,
    originalItem: CalendarItem?,
    type: CalendarItemType,
    rawStatus: CalendarItemStatus,
): CalendarItemStatus {
    if (rawStatus != CalendarItemStatus.COMPLETED) return rawStatus
    if (!isEditMode) return CalendarItemStatus.ACTIVE
    return if (originalItem != null && originalItem.status == CalendarItemStatus.COMPLETED) {
        val typeUnchanged = type == originalItem.type
        val bothTypesOfferCompletion = CalendarCompletion.offersCompletion(originalItem.type) &&
            CalendarCompletion.offersCompletion(type)
        if (typeUnchanged || bothTypesOfferCompletion) CalendarItemStatus.COMPLETED else CalendarItemStatus.ACTIVE
    } else {
        if (CalendarCompletion.offersCompletion(type)) CalendarItemStatus.COMPLETED else CalendarItemStatus.ACTIVE
    }
}

/**
 * Sanitizer сохранения recurrenceRule — то же назначение и тот же паттерн
 * тестируемости, что и sanitizeCalendarItemStatusForSave (DropdownMenu
 * Material3 недоступен для клика под Robolectric, поэтому сама функция —
 * единственная тестируемая точка).
 *
 * INVARIANT: правка, НЕ трогавшая recurrence-dropdown (recurrenceWasEdited ==
 * false), обязана сохранить original raw recurrenceRule ПОБАЙТОВО — даже если
 * RecurrenceMode.fromRule() не распознала его и свела к NONE (неизвестный/
 * будущий RRULE, легаси-строка). Раньше save всегда писал
 * recurrenceMode.toRRule(), то есть любая нераспознанная строка тихо
 * превращалась в null при сохранении любой не относящейся к recurrence правки
 * (например, названия). Только явное действие пользователя над recurrence
 * (recurrenceWasEdited == true) даёт право заменить raw-значение через
 * canonical toRRule() — тогда пользователь осознанно выбрал новое правило.
 */
internal fun sanitizeRecurrenceRuleForSave(
    recurrenceWasEdited: Boolean,
    originalRecurrenceRule: String?,
    recurrenceMode: RecurrenceMode,
): String? = if (recurrenceWasEdited) recurrenceMode.toRRule() else originalRecurrenceRule

/**
 * Sanitizer сохранения recurrenceExplicitlySet — тот же паттерн тестируемости,
 * что и sanitizeRecurrenceRuleForSave/sanitizeCalendarItemStatusForSave.
 *
 * 1. CREATE (isEditMode == false) — modern new rows: ВСЕГДА true, независимо
 *    от того, трогал ли пользователь recurrence-dropdown (даже «Не повторять»
 *    у нового CALL — это подтверждённое современным приложением состояние,
 *    настоящий NONE, а не legacy-unset).
 * 2. EDIT, recurrenceWasEdited == true — пользователь (или type-triggered
 *    auto-set, напр. BIRTHDAY→YEARLY) явно определил recurrence в ЭТОМ
 *    сохранении: true.
 * 3. EDIT, recurrenceWasEdited == false — recurrence в этом сохранении не
 *    трогали: сохраняем ORIGINAL флаг как есть (Data Safety, тот же принцип,
 *    что у sanitizeRecurrenceRuleForSave для raw rule) — legacy запись
 *    (false) остаётся legacy, уже explicit-NONE запись (true) остаётся true.
 */
internal fun sanitizeRecurrenceExplicitFlagForSave(
    isEditMode: Boolean,
    originalItem: CalendarItem?,
    recurrenceWasEdited: Boolean,
): Boolean {
    if (!isEditMode) return true
    if (recurrenceWasEdited) return true
    return originalItem?.recurrenceExplicitlySet ?: false
}

/**
 * requirement 12 (2026-09-17, postpone runtime semantics) → CALENDAR DATA SAFETY PACK: postpone-пара принадлежит КОНКРЕТНОЙ series
 * definition (effective recurrence mode + startDate anchor), и владеют ею операции postpone/completion, а не редактор. Политика
 * «правка recurrence/даты сбрасывает пару, несвязанные правки (title/notes/reminders/...) её сохраняют» живёт в слиянии
 * [com.aistudio.socialsphere.crmlxb.data.CalendarEditMerge] и сравнивает итоговую серию с ТЕКУЩЕЙ (а не с устаревшей серией
 * снимка редактора) — редактор пару не пересылает.
 */

/** Пункт выбора напоминаний, которому соответствует правило; null — форма такое правило показать и изменить не может. */
internal fun reminderTimeOf(r: ReminderRule): ReminderTime? = when {
    r.reminderType == ReminderType.AT_TIME -> ReminderTime.AT_EVENT
    r.reminderType == ReminderType.BEFORE && r.offsetValue == 10 && r.offsetUnit == ReminderOffsetUnit.MINUTES -> ReminderTime.MIN_10
    r.reminderType == ReminderType.BEFORE && r.offsetValue == 30 && r.offsetUnit == ReminderOffsetUnit.MINUTES -> ReminderTime.MIN_30
    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.HOURS -> ReminderTime.HOUR_1
    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.DAYS -> ReminderTime.DAY_1
    r.reminderType == ReminderType.BEFORE && r.offsetValue == 3 && r.offsetUnit == ReminderOffsetUnit.DAYS -> ReminderTime.DAY_3
    r.reminderType == ReminderType.BEFORE && r.offsetValue == 1 && r.offsetUnit == ReminderOffsetUnit.WEEKS -> ReminderTime.WEEK_1
    else -> null
}

/**
 * CALENDAR DATA SAFETY PACK: `edited` формы обязан отличаться от `base` ТОЛЬКО тем, что пользователь изменил. Форма собирает
 * связи и напоминания заново из того, что умеет показать (контакты + ОДНА компания; шесть пунктов напоминаний), поэтому всё
 * остальное она бы «удалила» в глазах diff base→edited. Невидимое для формы переносится как есть: связи с подарком/заметкой/
 * адресом, вторая и следующие связи с компаниями, напоминания нестандартного вида (CUSTOM_DATE_TIME, иные смещения).
 */
internal fun carryEditorInvisibleState(base: CalendarItem?, edited: CalendarItem): CalendarItem {
    if (base == null) return edited
    val extraCompanies = base.links.filter { it.targetType == CalendarTargetType.COMPANY }.drop(1)
    val invisibleLinks = base.links.filter { it.targetType != CalendarTargetType.CONTACT && it.targetType != CalendarTargetType.COMPANY } + extraCompanies
    val invisibleReminders = base.reminders.filter { reminderTimeOf(it) == null }
    return edited.copy(links = edited.links + invisibleLinks, reminders = edited.reminders + invisibleReminders)
}

// Долгоживущий scope: планирование напоминаний после commit не привязано к жизни экрана (тот же паттерн, что CalendarCompletion).
private val calendarEditScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

/**
 * Бизнес-развилка сохранения редактора: то же самое, что вызывает кнопка
 * «Готово» этого экрана — вынесено в отдельную функцию, чтобы юнит-тест мог
 * дёрнуть ровно этот код напрямую (см. комментарий у selectableCalendarStatusOptions
 * про недоступность DropdownMenu для клика под Robolectric в этом проекте).
 * Переход в COMPLETED — то же бизнес-действие, что и кнопка «Готово» на
 * CalendarItemDetailScreen/в уведомлении — CalendarCompletion.complete(), НЕ
 * отдельный recurring-completion path (requirement 5). Для effective NONE —
 * прежняя семантика (status=COMPLETED, lastContactDate, reminders сняты). Для
 * recurring — «Выполнено» в статус-dropdown означает «завершить ТЕКУЩЕЕ
 * occurrence»: status возвращается в исходный (не COMPLETED — серия остаётся
 * ACTIVE), CalendarCompletion сам проставляет lastCompletedOccurrenceDate
 * поверх ОСТАЛЬНЫХ правок newItem (title/reminders того же сохранения не
 * теряются — они применяются 3-way вместе с завершением).
 *
 * CALENDAR DATA SAFETY PACK: сохранение = 3-way слияние `текущее состояние + diff(originalItem → newItem)`
 * (AppStateStore.updateCalendarItem), а не «записать newItem целиком»: устаревший редактор не откатывает завершение, перенос,
 * чужую смену recurrence, удалённые связи и напоминания. Напоминания планируются ПОСЛЕ commit по ИТОГОВОМУ сохранённому
 * состоянию, снимаются — те, что стояли до сохранения. Возвращаемый Deferred завершается после commit И планирования.
 */
internal fun persistCalendarItemEdit(
    context: android.content.Context,
    isEditMode: Boolean,
    originalItem: CalendarItem?,
    newItem: CalendarItem,
): kotlinx.coroutines.Deferred<*> {
    val edited = if (isEditMode) carryEditorInvisibleState(originalItem, newItem) else newItem
    val editBase = if (isEditMode) originalItem ?: AppStateStore.getCalendarItemById(newItem.id) else null
    // Слитое состояние — то, что реально сохранится: серию/режим определяем по нему, а не по устаревшему снимку.
    val view = if (editBase != null) AppStateStore.previewCalendarEdit(editBase, edited) ?: edited else edited
    val isCompletionTransition = isEditMode &&
        (originalItem?.status ?: CalendarItemStatus.ACTIVE) != CalendarItemStatus.COMPLETED &&
        newItem.status == CalendarItemStatus.COMPLETED
    if (isCompletionTransition) {
        val mode = effectiveRecurrenceMode(view.type, view.recurrenceRule, view.recurrenceExplicitlySet)
        val isRecurring = mode != RecurrenceMode.NONE
        // targetOccurrenceDate фиксируется из originalItem — того occurrence,
        // которое пользователь реально видел и решил завершить в этом
        // сохранении, а не заново вычисленное после применения правок.
        // pendingOccurrence().seriesDate (не старый pendingOccurrenceDate()) —
        // если occurrence active postponed, завершается ИСХОДНОЕ occurrence,
        // не дата переноса (postpone runtime semantics, 2026-09-17).
        val target = if (isRecurring) originalItem?.pendingOccurrence()?.seriesDate else null
        val itemForCompletion = if (isRecurring)
            edited.copy(status = originalItem?.status ?: CalendarItemStatus.ACTIVE)
        else edited
        // Если в ЭТОМ ЖЕ сохранении ПОЛЬЗОВАТЕЛЬ сменил серию (recurrence или дату-anchor), target (occurrence исходной серии)
        // почти никогда не occurrence новой серии — валидировать его нужно против ИСХОДНОЙ (originalItem) серии, иначе
        // occursOn отклонит валидный target (см. CalendarCompletion.OccurrenceSeries). Если серию не менял пользователь
        // (её могла изменить только параллельная операция) — обычная проверка против ТЕКУЩЕЙ серии: stale identity -> no-op.
        val userChangedSeries = originalItem != null && (
            effectiveRecurrenceMode(edited.type, edited.recurrenceRule, edited.recurrenceExplicitlySet) !=
                effectiveRecurrenceMode(originalItem.type, originalItem.recurrenceRule, originalItem.recurrenceExplicitlySet) ||
                edited.startDate != originalItem.startDate)
        val occurrenceSeries = if (isRecurring && userChangedSeries && originalItem != null) {
            val originalMode = effectiveRecurrenceMode(originalItem.type, originalItem.recurrenceRule, originalItem.recurrenceExplicitlySet)
            val originalAnchor = parseFlexibleDate(originalItem.startDate)
            if (originalAnchor != null)
                CalendarCompletion.OccurrenceSeries.FixedSeries(originalAnchor, originalMode)
            else CalendarCompletion.OccurrenceSeries.CurrentSeries
        } else CalendarCompletion.OccurrenceSeries.CurrentSeries
        // oldReminders не передаётся: снимать нужно будильники, которые РЕАЛЬНО стоят (текущие правила события), а не правила
        // устаревшего снимка. CalendarCompletion.complete() сам отменяет их и (для recurring) переставляет на следующее
        // pending occurrence по итоговому состоянию — отдельный rescheduleReminders() здесь НЕ вызывается, иначе naïve
        // reschedule (без occurrence-awareness) перезаписал бы правильный результат.
        return CalendarCompletion.complete(context, itemForCompletion, target, null, occurrenceSeries, editBase = editBase)
    }
    val previousReminders = AppStateStore.getCalendarItemById(newItem.id)?.reminders ?: originalItem?.reminders ?: emptyList()
    val write = when {
        isEditMode -> AppStateStore.updateCalendarItem(editBase ?: return kotlinx.coroutines.CompletableDeferred(Unit), edited)
        else -> AppStateStore.addCalendarItem(newItem)
    }
    return calendarEditScope.async {
        write.await()
        AppStateStore.getCalendarItemById(newItem.id)?.let {
            com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler.rescheduleReminders(context, previousReminders, it)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarItemEditScreen(
    calendarItemId: String?,
    onNavigateBack: () -> Unit,
    prefillContactId: String? = null,
    prefillCompanyId: String? = null
) {
    val isEditMode = calendarItemId != null
    // PERSISTENCE PACK #2: «Готово» закрывает экран только после подтверждённого коммита.
    val saveScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    val originalItem = remember { calendarItemId?.let { AppStateStore.calendarItems.find { item -> item.id == it } } }

    val ctxLabel = LocalContext.current
    // rememberSaveable: введённое переживает выгрузку процесса системой (телефон заблокирован) — экран
    // восстанавливается навигацией вместе с полями (2026-10-08).
    var title by rememberSaveable { mutableStateOf(originalItem?.title ?: "") }
    var description by rememberSaveable { mutableStateOf(originalItem?.description ?: "") }
    var type by rememberSaveable { mutableStateOf(originalItem?.type ?: CalendarItemType.MEETING) }
    var importance by rememberSaveable { mutableStateOf(originalItem?.importance ?: ImportanceLevel.NORMAL) }
    var status by rememberSaveable { mutableStateOf(originalItem?.status ?: CalendarItemStatus.ACTIVE) }

    // Новое событие: дата по умолчанию — СЕГОДНЯ, как в Google Calendar (пустой
    // даты не бывает). Баг §30/#79: пустой startDate сохранялся — событие уходило
    // в БД, но не показывалось ни в Ленте, ни в Месяце, напоминание не считалось.
    var startDate by rememberSaveable {
        mutableStateOf(originalItem?.startDate ?: java.time.LocalDate.now().toString())
    }
    var startTime by rememberSaveable { mutableStateOf(originalItem?.startTime ?: "") }
    var endDate by rememberSaveable { mutableStateOf(originalItem?.endDate ?: "") }
    var endTime by rememberSaveable { mutableStateOf(originalItem?.endTime ?: "") }
    var isAllDay by rememberSaveable { mutableStateOf(originalItem?.isAllDay ?: false) }

    // effectiveRecurrenceMode() — та же compatibility policy, что и у
    // отображения/планирования: legacy BIRTHDAY/ANNIVERSARY/NAMEDAY без
    // recurrenceRule открываются с уже выбранным «Каждый год» (соответствует
    // тому, как событие реально ведёт себя в календаре/уведомлениях), а не
    // молча с «Не повторять». Это влияет ТОЛЬКО на начальное отображение
    // dropdown — raw recurrenceRule (originalRecurrenceRule ниже) не трогается,
    // пока пользователь явно не изменит recurrence (см. recurrenceWasEdited).
    var recurrenceMode by rememberSaveable {
        mutableStateOf(effectiveRecurrenceMode(type, originalItem?.recurrenceRule, originalItem?.recurrenceExplicitlySet ?: false))
    }
    // Сырое persisted значение ДО парсинга — нужно, чтобы неизвестный/будущий
    // RRULE (fromRule() -> NONE для всего, что не входит в известный список)
    // не превращался в null при сохранении правки, не касавшейся recurrence
    // (баг: правка title стирала неизвестный recurrenceRule). recurrenceWasEdited
    // — единственный источник истины «пользователь реально трогал recurrence»;
    // только тогда canonical toRRule() имеет право заменить raw-значение.
    val originalRecurrenceRule = remember { originalItem?.recurrenceRule }
    var recurrenceWasEdited by rememberSaveable { mutableStateOf(false) }
    var reminders by remember { mutableStateOf(originalItem?.reminders ?: emptyList()) }

    var linkedContacts by remember {
        mutableStateOf<List<Contact>>(
            buildList {
                originalItem?.links
                    ?.filter { it.targetType == CalendarTargetType.CONTACT }
                    ?.forEach { l -> AppStateStore.getContact(l.targetId)?.let { add(it) } }
                if (originalItem == null) prefillContactId?.let { id ->
                    AppStateStore.getContact(id)?.let { add(it) }
                }
            }.distinctBy { it.id }
        )
    }
    var linkedCompany by remember {
        mutableStateOf<Company?>(
            originalItem?.links?.firstOrNull { it.targetType == CalendarTargetType.COMPANY }
                ?.let { AppStateStore.getCompany(it.targetId) }
                ?: if (originalItem == null) prefillCompanyId?.let { AppStateStore.getCompany(it) } else null
        )
    }

    var showContactDropdown by remember { mutableStateOf(false) }
    var showCompanyDropdown by remember { mutableStateOf(false) }
    var showRecurrenceDropdown by remember { mutableStateOf(false) }
    var showImportanceMenu by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    var showReminderSheet by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    var selectedReminders by remember(reminders) {
        val initialSelection = mutableSetOf<ReminderTime>()
        if (reminders.isNotEmpty()) {
            // Тот же reminderTimeOf(), что решает, какие правила форма умеет показать (остальные — carryEditorInvisibleState).
            reminders.forEach { r -> reminderTimeOf(r)?.let { initialSelection.add(it) } }
        } else if (!isEditMode) {
            // Новое событие: подставляем НАСТОЯЩИЙ дефолт из Настроек по типу события —
            // раньше эти 4 настройки (Подарки/Встречи/Звонки/Общее) нигде не читались,
            // и форма всегда стартовала с «без напоминания» независимо от выбора
            // владельца в Настройках (баг §35/§36). ON_DAY → AT_EVENT: для all-day
            // событий (дни рождения) AT_TIME и так резолвится в 09:00 того же дня
            // (см. NotificationScheduler.calculateNotificationTime), т.е. семантически
            // это одно и то же значение.
            fun ReminderTime.orAtEvent() = if (this == ReminderTime.ON_DAY) ReminderTime.AT_EVENT else this
            when (type) {
                CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
                CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE ->
                    initialSelection.addAll(AppSettings.birthdayReminderTimes.value.map { it.orAtEvent() })
                CalendarItemType.GIFT -> initialSelection.add(AppSettings.giftReminderTime.value)
                CalendarItemType.MEETING, CalendarItemType.COMPANY_EVENT ->
                    initialSelection.add(AppSettings.meetingReminderTime.value)
                CalendarItemType.CALL, CalendarItemType.MESSAGE ->
                    initialSelection.add(AppSettings.callReminderTime.value)
                else -> initialSelection.add(AppSettings.defaultReminderTime.value)
            }
        }
        if (initialSelection.isEmpty() && isEditMode) initialSelection.add(ReminderTime.AT_EVENT)
        else if (initialSelection.isEmpty()) initialSelection.add(ReminderTime.NONE)
        mutableStateOf(initialSelection.toSet())
    }

    LaunchedEffect(originalItem) {
        if (originalItem != null) {
            linkedContacts = originalItem.links
                .filter { it.targetType == CalendarTargetType.CONTACT }
                .mapNotNull { AppStateStore.getContact(it.targetId) }
                .distinctBy { it.id }
            val companyLink = originalItem.links.find { it.targetType == CalendarTargetType.COMPANY }
            if (companyLink != null) {
                linkedCompany = AppStateStore.getCompany(companyLink.targetId)
            }
            // recurrenceMode уже инициализирован через RecurrenceMode.fromRule
        }
    }

    // «Готово» и «Сохранить» из вопроса о несохранённом вводе — одно и то же сохранение.
    fun saveItem() {
        if (isSaving || startDate.isBlank()) return
        val itemId = originalItem?.id ?: java.util.UUID.randomUUID().toString()

        val links = mutableListOf<CalendarItemLink>()
        linkedContacts.forEach { c -> links.add(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, targetType = CalendarTargetType.CONTACT, targetId = c.id)) }
        linkedCompany?.let { links.add(CalendarItemLink(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, targetType = CalendarTargetType.COMPANY, targetId = it.id)) }

        val newReminderRules = mutableListOf<ReminderRule>()
        if (!selectedReminders.contains(ReminderTime.NONE)) {
            selectedReminders.forEach { opt ->
                val rule = when (opt) {
                    ReminderTime.AT_EVENT -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.AT_TIME)
                    ReminderTime.MIN_10 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 10, offsetUnit = ReminderOffsetUnit.MINUTES)
                    ReminderTime.MIN_30 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 30, offsetUnit = ReminderOffsetUnit.MINUTES)
                    ReminderTime.HOUR_1 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.HOURS)
                    ReminderTime.DAY_1 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.DAYS)
                    ReminderTime.DAY_3 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 3, offsetUnit = ReminderOffsetUnit.DAYS)
                    ReminderTime.WEEK_1 -> ReminderRule(id = java.util.UUID.randomUUID().toString(), calendarItemId = itemId, reminderType = ReminderType.BEFORE, offsetValue = 1, offsetUnit = ReminderOffsetUnit.WEEKS)
                    else -> null
                }
                if (rule != null) newReminderRules.add(rule)
            }
        }

        val recurrenceStr = sanitizeRecurrenceRuleForSave(
            recurrenceWasEdited, originalRecurrenceRule, recurrenceMode
        )
        val recurrenceExplicit = sanitizeRecurrenceExplicitFlagForSave(
            isEditMode, originalItem, recurrenceWasEdited
        )

        val savedStatus = sanitizeCalendarItemStatusForSave(isEditMode, originalItem, type, status)

        val newItem = CalendarItem(
            id = itemId,
            title = title,
            description = description.takeIf { it.isNotBlank() },
            type = type,
            startDate = startDate,
            startTime = startTime.takeIf { it.isNotBlank() },
            endDate = endDate.takeIf { it.isNotBlank() },
            endTime = endTime.takeIf { it.isNotBlank() },
            isAllDay = isAllDay,
            importance = importance,
            status = savedStatus,
            // colorKey в форме сейчас не редактируется — сохраняем как
            // было, а не хардкодим null (см. У60)
            colorKey = originalItem?.colorKey,
            links = links,
            recurrenceRule = recurrenceStr,
            recurrenceExplicitlySet = recurrenceExplicit,
            // Не редактируется в этом batch'е (schema-foundation only) —
            // сохраняем как было, тот же паттерн, что colorKey (У60).
            lastCompletedOccurrenceDate = originalItem?.lastCompletedOccurrenceDate,
            // Postpone-пара (v20, persistence-foundation only, 2026-09-17) —
            // редактор их ещё не показывает и не меняет, сохраняем как было.
            postponedFromOccurrenceDate = originalItem?.postponedFromOccurrenceDate,
            postponedToDate = originalItem?.postponedToDate,
            reminders = newReminderRules,
            createdAt = originalItem?.createdAt ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            updatedAt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        isSaving = true
        val pending = persistCalendarItemEdit(context, isEditMode, originalItem, newItem)
        saveScope.launch {
            // Уход с экрана отменяет только ожидание, не запись (она в DbWriteQueue).
            val committed = AppStateStore.awaitDurableCommit(pending)
            isSaving = false
            // Сбой: остаёмся на форме (сообщение показывает корневой экран).
            if (committed) onNavigateBack()
        }
    }

    // Владелец 2026-10-10: «день рождения/важная дата есть в календаре — должна быть и на контакте». Без связи с
    // контактом дата видна только в Календаре, поэтому перед сохранением спрашиваем (а не сохраняем молча).
    var showNoContactPrompt by remember { mutableStateOf(false) }
    fun requestSave() {
        if (isSaving || startDate.isBlank()) return
        if (dateNeedsContact(type, linkedContacts.isNotEmpty(), linkedCompany != null)) showNoContactPrompt = true
        else saveItem()
    }
    if (showNoContactPrompt) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaDateWithoutContactDialog(
            onPickContact = { showNoContactPrompt = false; showContactDropdown = true },
            onSaveAnyway = { showNoContactPrompt = false; saveItem() },
            onDismiss = { showNoContactPrompt = false },
        )
    }

    // Защита несохранённого ввода (2026-10-08): «Отмена»/«Назад» на изменённой форме спрашивают.
    val formDirty = rememberFormDirty(
        listOf<Any?>(title, description, type, importance, status, startDate, startTime, endDate, endTime, isAllDay,
            recurrenceMode, selectedReminders, linkedContacts.map { it.id }, linkedCompany?.id)
    )
    val requestLeave = rememberLeaveGuard(
        dirty = formDirty, saving = isSaving, canSave = startDate.isNotBlank(),
        onSave = { requestSave() }, onLeave = onNavigateBack,
    )

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
      Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // ── Шапка: Отмена · заголовок · Готово — ЗАКРЕПЛЕНА над прокруткой (2026-10-08: главная
            // кнопка всегда на виду, как у формы контакта; раньше уезжала вверх вместе с формой) ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.clickable { requestLeave() }
                )
                Text(
                    if (isEditMode) stringResource(R.string.cie_edit) else stringResource(R.string.cie_new_event),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label
                )
                Button(
                    onClick = { requestSave() },
                    // Вторая линия защиты #79: в правке поле даты можно стереть —
                    // без даты событие «невидимо», поэтому Готово неактивна.
                    enabled = startDate.isNotBlank() && !isSaving,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    shape = SocialShape.Full,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold)
                }
            }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Основное: название · тип · важность ──
            SectionCard(stringResource(R.string.cie_basic)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    BareFieldColumn(
                        label = stringResource(R.string.cie_title), value = title,
                        onValueChange = { title = it }, keyboardOptions = CapSentences,
                        placeholder = type.label(ctxLabel),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                // Тип — пилюли (как в макете), вместо листа
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AureliaCaption(stringResource(R.string.cie_event_type))
                    PillChoiceRow(
                        options = CalendarItemType.values().map { it.label(ctxLabel) },
                        selected = type.label(ctxLabel),
                        onSelect = { v ->
                            val picked = CalendarItemType.values().firstOrNull { it.label(ctxLabel) == v } ?: return@PillChoiceRow
                            type = picked
                            if (picked == CalendarItemType.BIRTHDAY) { isAllDay = true; recurrenceMode = RecurrenceMode.YEARLY; recurrenceWasEdited = true }
                        }
                    )
                }
                // Важность — компактная строка
                Box {
                    EventListRow(
                        label = stringResource(R.string.cie_importance),
                        value = importance.label(ctxLabel),
                        onClick = { showImportanceMenu = true }
                    )
                    DropdownMenu(expanded = showImportanceMenu, onDismissRequest = { showImportanceMenu = false }) {
                        ImportanceLevel.values().forEach { imp ->
                            DropdownMenuItem(text = { Text(imp.label(ctxLabel)) }, onClick = { importance = imp; showImportanceMenu = false })
                        }
                    }
                }
                if (isEditMode) {
                    Box {
                        EventListRow(
                            label = stringResource(R.string.cie_status),
                            value = status.label(ctxLabel),
                            onClick = { showStatusMenu = true }
                        )
                        DropdownMenu(expanded = showStatusMenu, onDismissRequest = { showStatusMenu = false }) {
                            selectableCalendarStatusOptions(type, isEditMode).forEach { stat ->
                                DropdownMenuItem(text = { Text(stat.label(ctxLabel)) }, onClick = { status = stat; showStatusMenu = false })
                            }
                        }
                    }
                }
            }

            // ── Когда: весь день · начало · конец · повтор ──
            SectionCard(stringResource(R.string.cie_datetime)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cie_all_day), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isAllDay, onCheckedChange = { isAllDay = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DatePickerField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = stringResource(R.string.cie_start_date),
                        modifier = Modifier.weight(1f),
                        // Год может быть неизвестен для дат-годовщин — «--MM-DD»
                        allowNoYear = type in listOf(
                            CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
                            CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE
                        )
                    )
                    if (!isAllDay) {
                        TimePickerField(
                            value = startTime,
                            onValueChange = { startTime = it },
                            label = stringResource(R.string.cie_time),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DatePickerField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = stringResource(R.string.cie_end_date),
                        modifier = Modifier.weight(1f)
                    )
                    if (!isAllDay) {
                        TimePickerField(
                            value = endTime,
                            onValueChange = { endTime = it },
                            label = stringResource(R.string.cie_time_opt),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Повтор — компактная строка
                Box {
                    val recurLabel = when (recurrenceMode) {
                        RecurrenceMode.NONE -> stringResource(R.string.rec_none)
                        RecurrenceMode.DAILY -> stringResource(R.string.rec_daily)
                        RecurrenceMode.WEEKLY -> stringResource(R.string.rec_weekly)
                        RecurrenceMode.MONTHLY -> stringResource(R.string.rec_monthly)
                        RecurrenceMode.YEARLY -> stringResource(R.string.rec_yearly)
                    }
                    EventListRow(
                        label = stringResource(R.string.cie_recurrence),
                        value = recurLabel,
                        leadingIcon = Icons.Default.Autorenew,
                        onClick = { showRecurrenceDropdown = true }
                    )
                    DropdownMenu(expanded = showRecurrenceDropdown, onDismissRequest = { showRecurrenceDropdown = false }) {
                        RecurrenceMode.entries.forEach { mode ->
                            val lbl = when (mode) {
                                RecurrenceMode.NONE -> stringResource(R.string.rec_none)
                                RecurrenceMode.DAILY -> stringResource(R.string.rec_daily)
                                RecurrenceMode.WEEKLY -> stringResource(R.string.rec_weekly)
                                RecurrenceMode.MONTHLY -> stringResource(R.string.rec_monthly)
                                RecurrenceMode.YEARLY -> stringResource(R.string.rec_yearly)
                            }
                            DropdownMenuItem(text = { Text(lbl) }, onClick = { recurrenceMode = mode; recurrenceWasEdited = true; showRecurrenceDropdown = false })
                        }
                    }
                }
            }

            // ── С кем: человек · компания ──
            SectionCard(stringResource(R.string.cie_linked_with)) {
                if (linkedContacts.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        linkedContacts.forEach { c ->
                            InputChip(
                                selected = true,
                                onClick = { linkedContacts = linkedContacts.filter { it.id != c.id } },
                                label = { Text("${c.firstName} ${c.lastName}".trim()) },
                                trailingIcon = {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cie_clear), modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
                // ФИКС (аудит 2026-07-06): раньше здесь был голый DropdownMenu без
                // поиска для компании (и с самодельным поиском для контакта) —
                // единственное место, не мигрировавшее на канонический
                // AureliaPickerSheet (везде остальных пикерах — он).
                EventListRow(
                    label = stringResource(R.string.cie_add_person),
                    value = "",
                    leadingIcon = Icons.Default.PersonAdd,
                    onClick = { showContactDropdown = true }
                )
                if (showContactDropdown) {
                    // ФИКС (глубокий аудит 2026-07-06): контакт с пустыми firstName/lastName
                    // (только nickname) давал title="" — невидимая строка в пикере, не
                    // находимая поиском ни по чему. Фоллбэк на nickname, затем на
                    // общую заглушку "Без имени" (уже используется для этого же случая
                    // в другом месте кодовой базы, imp_no_name).
                    val noNameLabel = stringResource(R.string.imp_no_name)
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                        title = stringResource(R.string.cie_add_person),
                        items = AppStateStore.contacts
                            .filter { c -> linkedContacts.none { it.id == c.id } }
                            .map {
                                val fullName = "${it.firstName} ${it.lastName}".trim()
                                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                                    id = it.id,
                                    title = fullName.ifBlank { it.nickname?.trim().orEmpty().ifBlank { noNameLabel } }
                                )
                            },
                        onPick = { picked ->
                            AppStateStore.contacts.find { it.id == picked.id }?.let { c -> linkedContacts = linkedContacts + c }
                            showContactDropdown = false
                        },
                        onDismiss = { showContactDropdown = false },
                        searchPlaceholder = stringResource(R.string.ce_search_contact),
                        emptyText = stringResource(R.string.picker_no_results)
                    )
                }
                EventListRow(
                    label = stringResource(R.string.cie_company),
                    value = linkedCompany?.name ?: "—",
                    onClick = { showCompanyDropdown = true },
                    trailing = {
                        if (linkedCompany != null) {
                            IconButton(onClick = { linkedCompany = null }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cie_clear), modifier = Modifier.size(16.dp))
                            }
                        } else {
                            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
                        }
                    }
                )
                if (showCompanyDropdown) {
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                        title = stringResource(R.string.cie_company),
                        items = AppStateStore.companies.map {
                            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(id = it.id, title = it.name, isCompany = true)
                        },
                        onPick = { picked ->
                            linkedCompany = AppStateStore.companies.find { it.id == picked.id }
                            showCompanyDropdown = false
                        },
                        onDismiss = { showCompanyDropdown = false },
                        searchPlaceholder = stringResource(R.string.ce_search_company),
                        emptyText = stringResource(R.string.picker_no_results)
                    )
                }
            }

            // ── Детали: напоминание · заметка ──
            SectionCard(stringResource(R.string.cie_reminders)) {
                // Сводка выбранных напоминаний (можно несколько) — одной строкой,
                // тап открывает лист с галочками. Раньше чипы выглядели как
                // «выбери одно», хотя выбор множественный.
                val reminderSummary = run {
                    val active = selectedReminders.filter { it != ReminderTime.NONE }
                    if (active.isEmpty()) ReminderTime.NONE.label(ctxLabel)
                    else active.joinToString(", ") { it.label(ctxLabel) }
                }
                EventListRow(
                    label = stringResource(R.string.cie_reminders),
                    value = reminderSummary,
                    onClick = { showReminderSheet = true }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cie_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            if (showReminderSheet) {
                ReminderPickerSheet(
                    selected = selectedReminders,
                    onToggle = { opt ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            opt != ReminderTime.NONE && !selectedReminders.contains(opt)) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        val next = selectedReminders.toMutableSet()
                        if (opt == ReminderTime.NONE) {
                            next.clear(); next.add(ReminderTime.NONE)
                        } else {
                            next.remove(ReminderTime.NONE)
                            if (next.contains(opt)) next.remove(opt) else next.add(opt)
                            if (next.isEmpty()) next.add(ReminderTime.NONE)
                        }
                        selectedReminders = next
                    },
                    onDismiss = { showReminderSheet = false }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
      }
    }
}

// ── Apple-style: цвет типа события + компактная строка списка + пикер типа ──

// Палитра типов событий Aurelia (терракот/тил/малахит/золото) вместо iOS-цветов.
// Свой цвет из Настроек (фидбэк 2026-07-04) переопределяет встроенный — см.
// AppSettings.calendarTypeColors + пикер в CalendarSettingsScreen.
internal fun eventTypeColor(t: CalendarItemType): Color {
    AppSettings.calendarTypeColors.value[t.name]?.let { return Color(it) }
    return when (t) {
        CalendarItemType.BIRTHDAY -> Color(0xFFB68A36) // золото (точно по макету)
        CalendarItemType.CALL     -> Color(0xFF5E8C66) // сейдж (точно по макету)
        CalendarItemType.MEETING  -> Color(0xFF1C6B4C) // малахит/акцент (по макету var(--ac))
        CalendarItemType.GIFT     -> Color(0xFFC45D34) // терракот
        else                      -> Color(0xFF1C6B4C)
    }
}

/**
 * Компактная строка формы в стиле Apple: слева подпись (+опц. точка/иконка),
 * справа значение серым и шеврон/кастомный trailing. Вся строка кликабельна.
 */
@Composable
private fun EventListRow(
    label: String,
    value: String,
    leadingDot: Color? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingDot != null) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(leadingDot))
            Spacer(Modifier.width(10.dp))
        }
        if (leadingIcon != null) {
            Icon(leadingIcon, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTheme.colors.secondaryLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        if (trailing != null) trailing()
        else Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.tertiaryLabel)
    }
}

/**
 * Лист выбора напоминаний — МНОЖЕСТВЕННЫЙ выбор с галочками и явной подписью
 * «можно несколько». «Без напоминания» взаимоисключающее с остальными.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPickerSheet(
    selected: Set<ReminderTime>,
    onToggle: (ReminderTime) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    // MIN_30/DAY_3 добавлены (§36), чтобы значения из Настроек «Встречи»/«Подарки»
    // были представимы в этом же пикере — иначе подставленный дефолт не совпадал
    // бы ни с одним чипом.
    val options = listOf(
        ReminderTime.NONE, ReminderTime.AT_EVENT, ReminderTime.MIN_10, ReminderTime.MIN_30,
        ReminderTime.HOUR_1, ReminderTime.DAY_1, ReminderTime.DAY_3, ReminderTime.WEEK_1
    )
    ModalBottomSheet(onDismissRequest = onDismiss, shape = SocialShape.Sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.cie_reminders), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.cie_reminder_multi),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppleTheme.colors.secondaryLabel
                )
            }
            options.forEach { opt ->
                val checked = selected.contains(opt)
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(opt) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(opt.label(ctx), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (checked) Icon(Icons.Default.Check, null, tint = AppleTheme.colors.brand)
                }
            }
        }
    }
}
