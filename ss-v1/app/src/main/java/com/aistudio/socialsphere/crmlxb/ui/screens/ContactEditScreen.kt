@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aistudio.socialsphere.crmlxb.ui.screens
import androidx.compose.ui.graphics.Color

import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.ContactChannelDedup
import com.aistudio.socialsphere.crmlxb.data.ContactFormDraft
import com.aistudio.socialsphere.crmlxb.data.ContactFormDraftStore
import com.aistudio.socialsphere.crmlxb.data.DraftDate
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.utils.label
import com.aistudio.socialsphere.crmlxb.utils.relationRoleLabel
import com.aistudio.socialsphere.crmlxb.utils.inverseRelationRole
import com.aistudio.socialsphere.crmlxb.utils.roleOfLabel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import androidx.compose.ui.res.stringResource
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactEditScreen(
    contactId: String?,
    onNavigateBack: () -> Unit,
    // После СОЗДАНИЯ контакта открываем его карточку (фидбэк владельца), а не список.
    onCreated: ((String) -> Unit)? = null,
    // Тап по живой подсказке «похоже, уже есть» (2026-07-22) — открыть найденный
    // контакт, не теряя черновик текущей формы (она остаётся на стеке навигации).
    onNavigateToContact: ((String) -> Unit)? = null,
    // V2.2 прямой захват «Скан»: переход на camera/OCR-экран (ScanCardScreen),
    // который кладёт разобранный черновик в ContactDraftSession и возвращается
    // сюда же — сам экран сканирования не создаёт контакт (см. задачу V2.2).
    // null только в contact_edit (эта кнопка — только для НОВОГО контакта).
    onNavigateToScan: (() -> Unit)? = null
) {
    val isEditMode = contactId != null
    val ctxLabel = LocalContext.current
    val scope = rememberCoroutineScope()
    val originalContact = remember { contactId?.let { AppStateStore.getContact(it) } }
    val nowIso = { LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) }

    // Стабильный id: для нового контакта генерируется один раз,
    // чтобы связи (семья) могли ссылаться на него ещё до сохранения
    val editedContactId = remember { originalContact?.id ?: UUID.randomUUID().toString() }

    // ── V2.2: черновик из Скан/Контакты (ContactDraftSession) — только для
    // НОВОГО контакта, прочитан РОВНО один раз при открытии экрана (snapshot в
    // remember, до любого потребления полями ниже), затем сессия очищается —
    // повторная пересборка/открытие формы уже пустой не подставит его снова.
    // Камера/OCR/парсер (ScanCardScreen) и системный пикер контактов телефона
    // не тронуты — они лишь кладут готовый ImportContactCandidate в сессию.
    val draftCandidate = remember { if (!isEditMode) ContactDraftSession.pending else null }
    LaunchedEffect(Unit) { if (draftCandidate != null) ContactDraftSession.clear() }

    // ── Семья и отношения ──
    val originalRelations = remember {
        AppStateStore.contactRelations
            .filter { it.firstContactId == editedContactId || it.secondContactId == editedContactId }
            .toList()
    }
    var contactRelationsDraft by remember { mutableStateOf(originalRelations) }
    var showAddRelation by remember { mutableStateOf(false) }

    // ── Теги/важные даты/заметки — черновик-до-сохранения (V2.2), тот же
    // паттерн, что contactRelationsDraft выше: применяется ТОЛЬКО в buildAndSave(),
    // Отмена ничего не пишет. Тег-МЕМБЕРСТВО нельзя писать для контакта, которого
    // ещё нет в AppStateStore.contacts (новый контакт) — отложенная запись решает
    // это заодно с безопасным Cancel/Отмена для уже существующего контакта. ──
    var tagsDraft by remember { mutableStateOf(originalContact?.let { AppStateStore.tagsOfContact(it.id) } ?: emptyList<Tag>()) }
    // Черновик дат сразу содержит день рождения из Скан/Контакты, если распознан.
    var newDatesDraft by remember {
        mutableStateOf(
            draftCandidate?.birthday?.trim()?.takeIf { it.isNotBlank() }
                ?.let { listOf(NewContactDate(CalendarItemType.BIRTHDAY, CalendarItemType.BIRTHDAY.label(ctxLabel), it)) } ?: emptyList<NewContactDate>()
        )
    }
    var showAddDate by remember { mutableStateOf(false) }
    // Заметка-черновик сразу содержит «не потерять ни фразы» текст со Скана
    // (сырой OCR-текст/нераспознанное — см. ScanCardScreen.applyParsed) —
    // тот же принцип, что раньше жил ВНУТРИ старой отдельной формы скана.
    var newNotesDraft by remember {
        mutableStateOf(draftCandidate?.notes?.trim()?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList<String>())
    }
    var quickNoteText by remember { mutableStateOf("") }

    var firstName  by remember { mutableStateOf(originalContact?.firstName ?: draftCandidate?.firstName ?: "") }
    var lastName   by remember { mutableStateOf(originalContact?.lastName  ?: draftCandidate?.lastName  ?: "") }
    var middleName by remember { mutableStateOf(originalContact?.middleName ?: draftCandidate?.middleName ?: "") }
    // Структура имени как в Android-контактах (v13): приставка/суффикс/фонетика
    var namePrefix        by remember { mutableStateOf(originalContact?.namePrefix ?: draftCandidate?.namePrefix ?: "") }
    var nameSuffix         by remember { mutableStateOf(originalContact?.nameSuffix ?: draftCandidate?.nameSuffix ?: "") }
    var phoneticFirstName by remember { mutableStateOf(originalContact?.phoneticFirstName ?: draftCandidate?.phoneticFirstName ?: "") }
    var phoneticMiddleName by remember { mutableStateOf(originalContact?.phoneticMiddleName ?: draftCandidate?.phoneticMiddleName ?: "") }
    var phoneticLastName  by remember { mutableStateOf(originalContact?.phoneticLastName ?: draftCandidate?.phoneticLastName ?: "") }

    // Mutable lists for editing
    // Из Скан/Контакты номер/почта, распознанные дважды, не должны появиться дважды (профилактика на вводе).
    var phones     by remember { mutableStateOf(originalContact?.phones ?: draftCandidate?.phones?.map { it.copy(contactId = editedContactId) }?.let { ContactChannelDedup.dedupePhones(it) } ?: emptyList<ContactPhone>()) }
    var emails     by remember { mutableStateOf(originalContact?.emails ?: draftCandidate?.emails?.map { it.copy(contactId = editedContactId) }?.let { ContactChannelDedup.dedupeEmails(it) } ?: emptyList<ContactEmail>()) }
    var messengers by remember { mutableStateOf(originalContact?.messengers ?: emptyList<Messenger>()) }
    var draftAddresses by remember {
        mutableStateOf(
            originalContact?.addresses ?: draftCandidate?.addresses?.map { it.copy(ownerId = editedContactId) } ?: emptyList<Address>()
        )
    }

    // ── Живая подсказка о дублях, ПОКА контакт ещё черновик (не сохранён) ──
    // Дополняет (не заменяет) постфактум-экран «Объединить контакты»: та ищет
    // среди уже сохранённых, эта — сравнивает черновик формы с базой в реальном
    // времени. Ненавязчиво, не блокирует «Готово» (владелец явно просил
    // «продумай очень глубоко», 2026-07-22 — см. решение и пороги в
    // AppStateStore.findLiveDuplicateHints).
    var liveDuplicateHints by remember { mutableStateOf<List<AppStateStore.LiveDuplicateHint>>(emptyList()) }
    var dupHintsDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(firstName, lastName, phones, emails) {
        dupHintsDismissed = false
        kotlinx.coroutines.delay(300)
        liveDuplicateHints = AppStateStore.findLiveDuplicateHints(
            draftFirstName = firstName,
            draftLastName = lastName,
            draftPhones = phones.map { it.number },
            draftEmails = emails.map { it.email },
            excludeId = originalContact?.id
        )
    }

    // Company / work. Черновик несёт только ИМЯ компании (строка), не id — молча
    // создавать новую компанию ДО «Готово» нельзя (Отмена должна ничего не писать).
    // Точное совпадение по имени — да; иначе пользователь сам создаст через «+ Новая
    // компания» (имя уже видно ему текстом в заметке черновика, см. newNotesDraft выше).
    var selectedCompanyId by remember {
        mutableStateOf(
            originalContact?.companyRelations?.firstOrNull { it.isPrimary }?.companyId
                ?: originalContact?.companyRelations?.firstOrNull()?.companyId
                ?: draftCandidate?.companyName?.let { AppStateStore.findCompanyByName(it)?.id }
                ?: ""
        )
    }
    var companyPosition   by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.position ?: draftCandidate?.jobTitle ?: "") }
    var companyDept       by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.department ?: "") }
    var workNote          by remember { mutableStateOf(originalContact?.companyRelations?.firstOrNull()?.workNote ?: "") }
    // «Это адрес компании» (2026-07-23, решение владельца): id адреса из
    // draftAddresses (тип WORK), помеченного как совпадающий с адресом компании
    // выбранного места работы — officeAddressId был в схеме год, но нигде не
    // использовался. При смене компании сбрасывается (см. picker onPick ниже) —
    // старая привязка принадлежала другой компании.
    // ФИКС (баг §35, найден повторным аудитом): officeAddressId на relation — id
    // адреса КОМПАНИИ (после linkContactAddressToCompany), а чекбокс ниже сравнивается
    // с id адресов КОНТАКТА (draftAddresses) — разные id-пространства. Раньше это
    // приводило к тому, что чекбокс при повторном открытии формы всегда выглядел
    // невыбранным, а повторное сохранение без его касания стирало уже установленную
    // связь (draftAddresses.find по id компании ничего не находил). Здесь сразу при
    // инициализации разворачиваем officeAddressId обратно в id СВОЕГО адреса контакта
    // по тому же совпадению улица+город, что использует linkContactAddressToCompany.
    var linkedOfficeAddressId by remember {
        mutableStateOf(
            originalContact?.companyRelations?.firstOrNull { it.companyId == selectedCompanyId }?.officeAddressId?.let { officeAddrId ->
                val officeAddr = AppStateStore.addresses.find { it.id == officeAddrId }
                officeAddr?.let { oa ->
                    originalContact.addresses.firstOrNull {
                        it.addressLine.trim().equals(oa.addressLine.trim(), ignoreCase = true) &&
                            it.city.trim().equals(oa.city.trim(), ignoreCase = true)
                    }?.id
                }
            }
        )
    }

    // Classification
    // null = «связь не выбрана»: НОВЫЙ контакт стартует пустым (утверждённый прототип V2.2: «Связь — Добавить ›»),
    // а не с предвыбранным «Знакомый». У существующего контакта всегда хранимое значение (не обнуляется никогда).
    var relationshipType   by remember { mutableStateOf<RelationshipType?>(originalContact?.relationshipType) }
    // Свой тип отношений («Кум», «Тренер»…) — если непустой, показывается вместо стандартного
    var customRelationType by remember { mutableStateOf(originalContact?.customRelationshipType ?: "") }
    // Второстепенные типы отношений (v17, решение владельца 2026-07-23): мультивыбор,
    // не взаимоисключающий главный relationshipType — «и друг, и коллега».
    var secondaryRelationshipTypes by remember { mutableStateOf(originalContact?.secondaryRelationshipTypes ?: emptyList<RelationshipType>()) }
    var connectionLevel    by remember { mutableStateOf(originalContact?.connectionLevel    ?: ConnectionLevel.NORMAL) }
    // ImportanceLevel убран из UI ПОЛНОСТЬЮ (решение владельца 2026-07-23) — колонка
    // в БД остаётся (тот же прецедент, что и ConnectionLevel), просто эта форма
    // больше не даёт его смотреть/менять. Значение проносится из оригинала при
    // сохранении (см. buildAndSave): NORMAL для нового контакта, иначе как было.
    var socialRole         by remember { mutableStateOf(originalContact?.socialRole         ?: SocialRole.REGULAR) }
    var communicationRhythm by remember { mutableStateOf(originalContact?.communicationRhythm ?: CommunicationRhythm.NOT_TRACKED) }
    // Число дней для CommunicationRhythm.CUSTOM («раз в N дней») — v17. Хранится как
    // текст в UI-состоянии, парсится в Int только при сохранении (см. buildAndSave);
    // невалидный ввод откатывается к прежнему значению, не крашит.
    var customRhythmDaysText by remember { mutableStateOf(originalContact?.customRhythmDays?.toString() ?: "") }
    // ContactStatus/SocialRole: V2.2 убрал их UI из ЭТОЙ формы (см. отчёт задачи) —
    // значения проносятся как есть из оригинала при сохранении, колонки в БД не трогаются.

    // Фото: абсолютный путь копии в filesDir/photos (см. PhotoStorage)
    var photoUri        by remember { mutableStateOf(originalContact?.photoUri) }

    // New fields
    var nickname        by remember { mutableStateOf(originalContact?.nickname ?: "") }
    var profession      by remember { mutableStateOf(originalContact?.profession ?: "") }
    var nextStep        by remember { mutableStateOf(originalContact?.nextStep ?: "") }
    var canHelpWith     by remember { mutableStateOf(originalContact?.canHelpWith ?: "") }
    var iCanHelpWith    by remember { mutableStateOf(originalContact?.iCanHelpWith ?: "") }
    var talkingPoints   by remember { mutableStateOf(originalContact?.talkingPoints ?: "") }
    var meetContext     by remember { mutableStateOf(originalContact?.meetContext ?: "") }
    // «Семья без карточек» («сын Петя, телефона нет») — свободный текст. Раньше правился ТОЛЬКО в
    // инлайн-режиме карточки контакта (удалён 2026-10-08, единый редактор) — теперь здесь.
    var familyNote      by remember { mutableStateOf(originalContact?.familyNote ?: "") }
    var meetDate        by remember { mutableStateOf(originalContact?.meetDate ?: "") }

    // Freemium (2026-08): «сохранение контакта с телефоном — 3 бесплатных
    // в месяц» — см. buildAndSave() ниже.

    // Add phone dialog state
    var showAddPhone    by remember { mutableStateOf(false) }
    var newPhone        by remember { mutableStateOf("") }
    var newPhoneType    by remember { mutableStateOf(PhoneType.MOBILE) }

    // Add email dialog state
    var showAddEmail    by remember { mutableStateOf(false) }
    var newEmail        by remember { mutableStateOf("") }
    var newEmailType    by remember { mutableStateOf(EmailType.PERSONAL) }

    // Add messenger dialog state
    var showAddMessenger by remember { mutableStateOf(false) }
    var newMessenger     by remember { mutableStateOf("") }
    var newMessengerType by remember { mutableStateOf(MessengerType.TELEGRAM) }

    // PERSISTENCE PACK #2: «Готово» закрывает экран только ПОСЛЕ подтверждённого коммита в Room;
    // пока идёт запись — кнопка неактивна (двойной тап не создаёт вторую запись).
    var isSaving by remember { mutableStateOf(false) }

    // Черновик формы на диске и защита от потери ввода (жалоба владельца 2026-10-08) — см. блок
    // «Защита от потери ввода» ниже. finished = форма закончена (сохранена или осознанно отброшена):
    // после этого автосохранение черновика больше ничего не пишет.
    val appCtx = ctxLabel.applicationContext
    val draftKey = contactId ?: "new"
    var finished by remember { mutableStateOf(false) }

    fun buildAndSave() {
        if (isSaving) return
        // Связи с ДРУГИМИ компаниями (например, добавленные со стороны карточки
        // компании) сохраняем — раньше правка контакта затирала их одной основной,
        // и человек пропадал из остальных компаний.
        val otherCompRels = (originalContact?.companyRelations ?: emptyList())
            .filter { it.companyId != selectedCompanyId }
            .map { it.copy(isPrimary = false) }
        // Существующую связь ОБНОВЛЯЕМ copy() (баг: пересоздание затирало
        // employmentStatus/startDate/role/responsibilities/managedAccounts на
        // каждом сохранении формы и всё делало «Текущее»).
        val existingPrimaryRel = originalContact?.companyRelations
            ?.firstOrNull { it.companyId == selectedCompanyId }
        // «Это адрес компании»: рабочий адрес контакта, отмеченный флажком,
        // находит (или заводит) совпадающий адрес компании по улице+городу —
        // см. AppStateStore.linkContactAddressToCompany. Если отмеченный адрес
        // успели удалить из формы, ссылка просто не создаётся (без краша).
        val resolvedOfficeAddressId = linkedOfficeAddressId?.let { addrId ->
            draftAddresses.find { it.id == addrId }?.let { addr ->
                AppStateStore.linkContactAddressToCompany(selectedCompanyId, addr)
            }
        }
        val compRelList = if (selectedCompanyId.isNotBlank()) listOf(
            existingPrimaryRel?.copy(
                position   = companyPosition.ifBlank { null },
                department = companyDept.ifBlank { null },
                workNote   = workNote.ifBlank { null },
                officeAddressId = resolvedOfficeAddressId,
                isPrimary  = true
            ) ?: ContactCompanyRelation(
                id = UUID.randomUUID().toString(),
                contactId  = editedContactId,
                companyId  = selectedCompanyId,
                position   = companyPosition.ifBlank { null },
                department = companyDept.ifBlank { null },
                role       = null,
                employmentStatus = EmploymentStatus.CURRENT,
                startDate  = null, endDate = null,
                responsibilities = null, managedAccounts = null,
                workNote   = workNote.ifBlank { null },
                officeAddressId = resolvedOfficeAddressId,
                isPrimary  = true
            )
        ) + otherCompRels else originalContact?.companyRelations ?: emptyList()

        val newContact = Contact(
            id               = editedContactId,
            firstName        = firstName.trim(),
            lastName         = lastName.trim(),
            middleName       = middleName.trim().ifBlank { null },
            nickname         = nickname.trim().ifBlank { null },
            namePrefix       = namePrefix.trim().ifBlank { null },
            nameSuffix       = nameSuffix.trim().ifBlank { null },
            phoneticFirstName = phoneticFirstName.trim().ifBlank { null },
            phoneticMiddleName = phoneticMiddleName.trim().ifBlank { null },
            phoneticLastName  = phoneticLastName.trim().ifBlank { null },
            phones           = phones,
            emails           = emails,
            messengers       = messengers,
            companyRelations = compRelList,
            addresses        = draftAddresses,
            notes            = originalContact?.notes ?: emptyList(),
            gifts            = originalContact?.gifts ?: emptyList(),
            sizeInfo         = originalContact?.sizeInfo,
            personalDetails  = originalContact?.personalDetails ?: emptyList(),
            relationshipType  = storedRelationshipType(relationshipType),
            customRelationshipType = customRelationType.trim().ifBlank { null },
            secondaryRelationshipTypes = secondaryRelationshipTypes,
            connectionLevel   = connectionLevel,
            // Форма НЕ даёт менять важность — проносим оригинал (NORMAL для нового
            // контакта), см. комментарий у объявления var communicationRhythm выше.
            importanceLevel   = originalContact?.importanceLevel ?: ImportanceLevel.NORMAL,
            // ContactStatus/SocialRole: нет UI в этой форме (V2.2) — пронос оригинала.
            socialRole        = originalContact?.socialRole ?: SocialRole.REGULAR,
            communicationRhythm = communicationRhythm,
            // Невалидный/пустой ввод не переопределяет прежнее значение (без краша).
            customRhythmDays  = customRhythmDaysText.trim().toIntOrNull() ?: originalContact?.customRhythmDays,
            contactStatus    = originalContact?.contactStatus ?: ContactStatus.ACTIVE,
            nextStep         = nextStep.trim().ifBlank { null },
            canHelpWith      = canHelpWith.trim().ifBlank { null },
            iCanHelpWith     = iCanHelpWith.trim().ifBlank { null },
            talkingPoints    = talkingPoints.trim().ifBlank { null },
            meetContext      = meetContext.trim().ifBlank { null },
            meetDate         = meetDate.trim().ifBlank { null },
            // Легаси-поле (2026-08-18, аудит): форма редактирования больше не
            // показывает/не правит его — единственная точка входа тегов теперь
            // управляемая система AppStateStore.tags/tagMembers (OverviewTab,
            // решение владельца 2026-07-28). Значение только сохраняется как
            // есть, чтобы уже введённые владельцем легаси-теги не терялись —
            // см. migrateLegacyContactTagsIfNeeded() для их переноса в новую систему.
            tags             = originalContact?.tags ?: emptyList(),
            photoUri         = photoUri,
            // Поля, не редактируемые этой формой, ОБЯЗАНЫ проноситься из оригинала —
            // иначе каждое сохранение молча стирало их (реальный баг: lastContactDate
            // и deviceContactId терялись при любом редактировании контакта)
            lastContactDate  = originalContact?.lastContactDate,
            deviceContactId  = originalContact?.deviceContactId,
            deviceLookupKey  = originalContact?.deviceLookupKey,
            familyNote       = familyNote.trim().ifBlank { null },
            profession       = profession.trim().ifBlank { null },
            createdAt        = originalContact?.createdAt ?: nowIso(),
            updatedAt        = nowIso()
        )
        // base = снимок, ОТ КОТОРОГО начата правка (originalContact): store запишет только
        // то, что пользователь реально изменил в форме, поверх свежего канонического
        // состояния — телефоны/детали/дата контакта, изменённые независимо после открытия
        // формы, не откатываются (PERSISTENCE PACK #2).
        val writes = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
        writes += if (isEditMode) AppStateStore.updateContact(originalContact ?: newContact, newContact)
        else AppStateStore.addContact(newContact)

        // Связи (семья): применяем разницу только при сохранении
        val keptIds = contactRelationsDraft.map { it.id }.toSet()
        originalRelations.filter { it.id !in keptIds }
            .forEach { writes += AppStateStore.removeContactRelation(it.id) }
        val origIds = originalRelations.map { it.id }.toSet()
        contactRelationsDraft.filter { it.id !in origIds }
            .forEach { writes += AppStateStore.addContactRelation(it) }

        // Теги (V2.2): setContactTags сам диффит против канонического состояния —
        // для нового контакта в момент этого вызова editedContactId уже в
        // AppStateStore.contacts (addContact/updateContact выше мутируют список
        // синхронно, Deferred — только про запись в Room), поэтому порядок безопасен.
        writes += AppStateStore.setContactTags(editedContactId, tagsDraft.map { it.id }.toSet())

        // Важные даты (V2.2): новые события добавляются, существующие не трогаем (полное управление датами остаётся
        // на Calendar/Cheat Sheet). Событие собирается ТОЙ ЖЕ функцией, что при добавлении даты на карточке
        // (ContactDateEntry.kt): связь, повтор «каждый год», важность, напоминание — одинаково из обоих мест.
        newDatesDraft.forEach { d -> writes += addContactDate(appCtx, editedContactId, d) }

        // Заметки (V2.2 быстрое добавление): существующий Note/addNote — полная
        // история заметок остаётся на Contact Detail (не трогаем этой задачей).
        newNotesDraft.forEach { text ->
            writes += AppStateStore.addNote(
                Note(
                    id = UUID.randomUUID().toString(), contactId = editedContactId,
                    type = NoteType.GENERAL, text = text, isImportant = false,
                    createdAt = nowIso(), updatedAt = nowIso()
                )
            )
        }

        // Барьер: linkContactAddressToCompany выше ставит собственную запись без Deferred —
        // барьер, поставленный ПОСЛЕ неё, подтверждает и её коммит (очередь строго FIFO).
        writes += AppStateStore.writeBarrier()

        isSaving = true
        scope.launch {
            // Уход с экрана отменяет только это ОЖИДАНИЕ, не запись (она в DbWriteQueue).
            val committed = AppStateStore.awaitDurableCommit(*writes.toTypedArray())
            isSaving = false
            // Сбой: экран остаётся открытым с введёнными данными (сообщение показывает корневой
            // экран по AppStateStore.writeFailureCount), пользователь может сохранить повторно.
            if (!committed) return@launch
            // Сохранено и подтверждено Room: черновик больше не нужен (иначе при следующем открытии
            // формы предложили бы «продолжить» уже сохранённое).
            finished = true
            ContactFormDraftStore.clear(appCtx, draftKey)
            if (!isEditMode && onCreated != null) onCreated(editedContactId) else onNavigateBack()
        }
    }

    // ── Защита от потери ввода (жалоба владельца 2026-10-08: «не сохранив, я теряю данные») ──
    // 1) isDirty — форма отличается от исходной (или заполнена из Скан/Контакты и ещё не сохранена);
    // 2) Отмена/«Назад» при изменённой форме НЕ уходят молча, а спрашивают: Сохранить / Не сохранять / Продолжить;
    // 3) изменённая форма автосохраняется на диск (черновик) — ввод переживает смерть процесса;
    //    при следующем открытии формы предлагается продолжить с того же места.
    val formNow = ContactFormDraft(
        firstName = firstName, lastName = lastName, middleName = middleName,
        namePrefix = namePrefix, nameSuffix = nameSuffix,
        phoneticFirstName = phoneticFirstName, phoneticMiddleName = phoneticMiddleName, phoneticLastName = phoneticLastName,
        phones = phones, emails = emails, messengers = messengers, addresses = draftAddresses,
        selectedCompanyId = selectedCompanyId, companyPosition = companyPosition, companyDept = companyDept, workNote = workNote,
        linkedOfficeAddressId = linkedOfficeAddressId,
        relationshipType = relationshipType, customRelationType = customRelationType,
        secondaryRelationshipTypes = secondaryRelationshipTypes,
        connectionLevel = connectionLevel, socialRole = socialRole,
        communicationRhythm = communicationRhythm, customRhythmDaysText = customRhythmDaysText,
        photoUri = photoUri,
        nickname = nickname, profession = profession, nextStep = nextStep,
        canHelpWith = canHelpWith, iCanHelpWith = iCanHelpWith, talkingPoints = talkingPoints,
        meetContext = meetContext, familyNote = familyNote, meetDate = meetDate,
        tags = tagsDraft, newDates = newDatesDraft.map { DraftDate(it.title, it.startDate, it.type, it.remind) },
        newNotes = newNotesDraft, quickNoteText = quickNoteText,
        relations = contactRelationsDraft,
    )
    // null = заполнено из Скан/Контакты: сравнивать не с чем, форма «изменена» с самого начала.
    val formBaseline = remember { if (draftCandidate != null) null else formNow }
    val isDirty = !finished && (formBaseline == null || formNow != formBaseline)

    LaunchedEffect(formNow, isDirty, isSaving) {
        if (isDirty && !isSaving) {
            kotlinx.coroutines.delay(600)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ContactFormDraftStore.save(appCtx, draftKey, formNow, stillWanted = { !finished })
            }
        }
    }

    var storedDraft by remember { mutableStateOf<ContactFormDraft?>(null) }
    LaunchedEffect(Unit) {
        if (draftCandidate == null) {
            storedDraft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ContactFormDraftStore.load(appCtx, draftKey)
            }?.draft
        }
    }

    fun applyDraft(d: ContactFormDraft) {
        firstName = d.firstName; lastName = d.lastName; middleName = d.middleName
        namePrefix = d.namePrefix; nameSuffix = d.nameSuffix
        phoneticFirstName = d.phoneticFirstName; phoneticMiddleName = d.phoneticMiddleName; phoneticLastName = d.phoneticLastName
        phones = d.phones; emails = d.emails; messengers = d.messengers; draftAddresses = d.addresses
        selectedCompanyId = d.selectedCompanyId; companyPosition = d.companyPosition; companyDept = d.companyDept; workNote = d.workNote
        linkedOfficeAddressId = d.linkedOfficeAddressId
        relationshipType = d.relationshipType; customRelationType = d.customRelationType
        secondaryRelationshipTypes = d.secondaryRelationshipTypes
        connectionLevel = d.connectionLevel; socialRole = d.socialRole
        communicationRhythm = d.communicationRhythm; customRhythmDaysText = d.customRhythmDaysText
        photoUri = d.photoUri
        nickname = d.nickname; profession = d.profession; nextStep = d.nextStep
        canHelpWith = d.canHelpWith; iCanHelpWith = d.iCanHelpWith; talkingPoints = d.talkingPoints
        meetContext = d.meetContext; familyNote = d.familyNote; meetDate = d.meetDate
        tagsDraft = d.tags; newDatesDraft = d.newDates.map { NewContactDate(it.type, it.title, it.startDate, it.remind) }
        newNotesDraft = d.newNotes; quickNoteText = d.quickNoteText
        contactRelationsDraft = d.relations
    }

    var showLeavePrompt by remember { mutableStateOf(false) }
    fun leaveDiscarding() {
        finished = true
        ContactFormDraftStore.clear(appCtx, draftKey)
        onNavigateBack()
    }
    // Пока идёт сохранение — уход с экрана игнорируется: экран сам уйдёт по подтверждённой записи
    // (иначе «Отмена»/«Назад» во время записи давали второй переход назад поверх первого).
    fun requestLeave() {
        if (isSaving) return
        if (isDirty) showLeavePrompt = true else leaveDiscarding()
    }
    androidx.activity.compose.BackHandler(enabled = isDirty || isSaving) { if (!isSaving) showLeavePrompt = true }

    // Не над экраном PIN: при холодном старте под замком предложение появится после разблокировки.
    storedDraft?.takeIf { !AppLockGate.locked }?.let { d ->
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaDraftFoundDialog(
            onContinue = { applyDraft(d); storedDraft = null },
            onDiscard = { ContactFormDraftStore.clear(appCtx, draftKey); storedDraft = null },
        )
    }
    if (showLeavePrompt) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaUnsavedChangesDialog(
            onSave = if (firstName.isNotBlank() && !isSaving) ({ showLeavePrompt = false; buildAndSave() }) else null,
            onDiscard = { showLeavePrompt = false; leaveDiscarding() },
            onKeepEditing = { showLeavePrompt = false },
        )
    }

    // ── Dialogs ──────────────────────────────────────────────────────
    if (showAddRelation) {
        val relationRoles = listOf("Жена", "Муж", "Партнёр", "Мать", "Отец",
            "Сын", "Дочь", "Брат", "Сестра", "Родственник", "Друг", "Коллега")
        // ФИКС (2026-07-12): инверсия роли и подписи теперь общие с OverviewTab.kt
        // (utils/RelationLabels.kt) — раньше эта копия не локализовалась вовсе
        // (сырые русские строки в DropdownField на en/el).
        var newRelSelected  by remember { mutableStateOf<Contact?>(null) }
        var newRelOtherRole by remember { mutableStateOf("Родственник") }
        var newRelMyRole    by remember { mutableStateOf("Родственник") }
        var myRoleTouched   by remember { mutableStateOf(false) }
        val candidates = AppStateStore.contacts.filter { c ->
            c.id != editedContactId &&
            contactRelationsDraft.none { it.firstContactId == c.id || it.secondContactId == c.id }
        }

        // Шаг 1: канонический пикер (шторка + поиск + аватары) — единый дизайн
        // «добавить человека», как в OverviewTab; старый вложенный AlertDialog убран.
        if (newRelSelected == null) {
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                title = stringResource(R.string.ce_add_person),
                items = candidates
                    .sortedBy { "${it.firstName} ${it.lastName}".trim().lowercase() }
                    .map { c ->
                        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                            id = c.id,
                            title = "${c.firstName} ${c.lastName}".trim(),
                            subtitle = c.nickname?.takeIf { it.isNotBlank() }?.let { "«$it»" }
                                ?: c.customRelationshipType?.takeIf { it.isNotBlank() }
                                ?: c.relationshipType.label(ctxLabel)
                        )
                    },
                onPick = { picked -> newRelSelected = AppStateStore.getContact(picked.id) },
                onDismiss = { showAddRelation = false },
                searchPlaceholder = stringResource(R.string.ce_search_by_name),
                emptyText = stringResource(R.string.ce_no_contacts_avail)
            )
        } else newRelSelected?.let { sel ->
            // Шаг 2: роли (кто он мне / кто я ему)
            com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                title = "${sel.firstName} ${sel.lastName}".trim(),
                onDismiss = { showAddRelation = false; newRelSelected = null },
                confirmText = stringResource(R.string.common_add),
                onConfirm = {
                    contactRelationsDraft = contactRelationsDraft + ContactRelation(
                        id              = UUID.randomUUID().toString(),
                        firstContactId  = editedContactId,
                        secondContactId = sel.id,
                        firstRole       = newRelMyRole,
                        secondRole      = newRelOtherRole
                    )
                    newRelSelected = null
                    showAddRelation = false
                },
                secondaryText = stringResource(R.string.common_back),
                onSecondary = { newRelSelected = null }
            ) {
                val relSelName = "${sel.firstName} ${sel.lastName}".trim()
                val relOwnName = "${firstName} ${lastName}".trim()
                DropdownField(roleOfLabel(ctxLabel, relSelName, relOwnName), relationRoleLabel(ctxLabel, newRelOtherRole), relationRoles.map { relationRoleLabel(ctxLabel, it) }) { v ->
                    newRelOtherRole = relationRoles.firstOrNull { relationRoleLabel(ctxLabel, it) == v } ?: newRelOtherRole
                    if (!myRoleTouched) newRelMyRole = inverseRelationRole(newRelOtherRole)
                }
                DropdownField(roleOfLabel(ctxLabel, relOwnName, relSelName), relationRoleLabel(ctxLabel, newRelMyRole), relationRoles.map { relationRoleLabel(ctxLabel, it) }) { v ->
                    newRelMyRole = relationRoles.firstOrNull { relationRoleLabel(ctxLabel, it) == v } ?: newRelMyRole; myRoleTouched = true
                }
            }
        }
    }
    if (showAddPhone) {
        var newPhonePrimary by remember { mutableStateOf(phones.isEmpty()) }
        // Такой номер уже есть у контакта — второй раз не создаём (решение владельца 2026-10-10): сообщаем,
        // кнопка «Добавить» неактивна, введённое не теряется.
        val phoneExists = ContactChannelDedup.phoneAlreadyListed(phones, newPhone)
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_add_phone),
            onDismiss = { showAddPhone = false; newPhone = "" },
            confirmText = stringResource(R.string.common_add),
            confirmEnabled = !phoneExists,
            onConfirm = {
                if (newPhone.isNotBlank() && !phoneExists) {
                    val added = ContactPhone(UUID.randomUUID().toString(), editedContactId, newPhone.trim(), newPhoneType, newPhonePrimary)
                    phones = if (newPhonePrimary) (phones.map { it.copy(isPrimary = false) } + added) else phones + added
                    phones = phones.sortedByDescending { it.isPrimary }
                    newPhone = ""; showAddPhone = false
                }
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddPhone = false; newPhone = "" }
        ) {
            OutlinedTextField(
                value = newPhone, onValueChange = { newPhone = it }, keyboardOptions = PhoneKeyboard,
                label = { Text(stringResource(R.string.ce_number)) }, modifier = Modifier.fillMaxWidth(),
                isError = phoneExists,
                supportingText = if (phoneExists) ({ Text(stringResource(R.string.ce_phone_exists)) }) else null
            )
            DropdownField(stringResource(R.string.ce_type), newPhoneType.label(ctxLabel), PhoneType.values().map { it.label(ctxLabel) }) { v -> newPhoneType = PhoneType.values().firstOrNull { it.label(ctxLabel) == v } ?: PhoneType.MOBILE }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = newPhonePrimary, onCheckedChange = { newPhonePrimary = it })
                Text(stringResource(R.string.ce_make_primary), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showAddEmail) {
        var newEmailPrimary by remember { mutableStateOf(emails.isEmpty()) }
        // Такой адрес уже есть (сравнивается весь адрес, регистр букв не важен) — второй раз не создаём.
        val emailExists = ContactChannelDedup.emailAlreadyListed(emails, newEmail)
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_add_email),
            onDismiss = { showAddEmail = false; newEmail = "" },
            confirmText = stringResource(R.string.common_add),
            confirmEnabled = !emailExists,
            onConfirm = {
                if (newEmail.isNotBlank() && !emailExists) {
                    val added = ContactEmail(UUID.randomUUID().toString(), editedContactId, newEmail.trim(), newEmailType, newEmailPrimary)
                    emails = if (newEmailPrimary) (emails.map { it.copy(isPrimary = false) } + added) else emails + added
                    emails = emails.sortedByDescending { it.isPrimary }
                    newEmail = ""; showAddEmail = false
                }
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddEmail = false; newEmail = "" }
        ) {
            OutlinedTextField(
                value = newEmail, onValueChange = { newEmail = it }, keyboardOptions = EmailKeyboard,
                label = { Text(stringResource(R.string.ce_email)) }, modifier = Modifier.fillMaxWidth(),
                isError = emailExists,
                supportingText = if (emailExists) ({ Text(stringResource(R.string.ce_email_exists)) }) else null
            )
            DropdownField(stringResource(R.string.ce_type), newEmailType.label(ctxLabel), EmailType.values().map { it.label(ctxLabel) }) { v -> newEmailType = EmailType.values().firstOrNull { it.label(ctxLabel) == v } ?: EmailType.PERSONAL }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = newEmailPrimary, onCheckedChange = { newEmailPrimary = it })
                Text(stringResource(R.string.ce_make_primary), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showAddMessenger) {
        var newMessengerPrimary by remember { mutableStateOf(messengers.isEmpty()) }
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
            title = stringResource(R.string.ce_add_messenger),
            onDismiss = { showAddMessenger = false; newMessenger = "" },
            confirmText = stringResource(R.string.common_add),
            onConfirm = {
                if (newMessenger.isNotBlank()) {
                    val added = Messenger(UUID.randomUUID().toString(), editedContactId, newMessengerType, newMessenger.trim(), null, newMessengerPrimary)
                    messengers = if (newMessengerPrimary) (messengers.map { it.copy(isPrimary = false) } + added) else messengers + added
                    messengers = messengers.sortedByDescending { it.isPrimary }
                    newMessenger = ""; showAddMessenger = false
                }
            },
            secondaryText = stringResource(R.string.common_cancel),
            onSecondary = { showAddMessenger = false; newMessenger = "" }
        ) {
            Text(stringResource(R.string.ce_platform), style = MaterialTheme.typography.labelMedium, color = AppleTheme.colors.secondaryLabel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MessengerType.values().forEach { mt ->
                    FilterChip(
                        selected = newMessengerType == mt,
                        onClick = { newMessengerType = mt },
                        label = { Text(mt.label(ctxLabel)) }
                    )
                }
            }
            OutlinedTextField(value = newMessenger, onValueChange = { newMessenger = it }, label = { Text(stringResource(R.string.ce_username_number)) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = newMessengerPrimary, onCheckedChange = { newMessengerPrimary = it })
                Text(stringResource(R.string.ce_make_primary), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // V2.2: sticky/scroll-aware секционная навигация (Контакт/Работа/Связь/Ещё).
    // Якоря — Y-координата (в системе координат скролл-контейнера) начала каждой
    // секции; тап скроллит к ней, активная секция подсвечивается по текущей
    // позиции скролла. Чисто UI-слой поверх уже существующих секций — ни одна
    // секция физически не перемещена, только помечена границей.
    val scrollState = rememberScrollState()
    // positionInParent() внутри verticalScroll уже сдвинут текущим скроллом —
    // не годится как стабильный якорь. Берём positionInRoot() якоря и ТАКОЙ ЖЕ
    // скролл-контейнера, разницу прибавляем к scrollState.value в момент замера —
    // скролл-член взаимно сокращается, результат стабилен независимо от текущей
    // позиции скролла (стандартный приём для scroll-to-child без BringIntoView).
    var scrollContainerRootY by remember { mutableStateOf(0f) }
    var contactSectionY by remember { mutableStateOf(0f) }
    var workSectionY by remember { mutableStateOf(0f) }
    var connectionSectionY by remember { mutableStateOf(0f) }
    var moreSectionY by remember { mutableStateOf(0f) }
    val sections = listOf(
        0 to stringResource(R.string.ce_section_contact),
        1 to stringResource(R.string.ce_section_work),
        2 to stringResource(R.string.ce_section_connection),
        3 to stringResource(R.string.ce_section_more)
    )
    val activeSectionIndex = remember { derivedStateOf {
        val y = scrollState.value.toFloat()
        val ys = listOf(contactSectionY, workSectionY, connectionSectionY, moreSectionY)
        // Низ формы — всегда последняя секция: «Ещё» короткая и может не дотянуться до верха экрана.
        if (scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 2) ys.lastIndex
        else ys.indexOfLast { it <= y + 1f }.coerceAtLeast(0)
    } }

    // ── V2.2 (по прототипу «Fluid Form»): редкое свёрнуто в строки-сводки, выбор — в шторках ──
    var showRelSheet by remember { mutableStateOf(false) }
    var showRhythmSheet by remember { mutableStateOf(false) }
    // Последняя включённая периодичность: «Поддерживать связь» выключили и тут же включили — выбор не теряется.
    var lastOnRhythm by remember { mutableStateOf<CommunicationRhythm?>(communicationRhythm.takeIf { it != CommunicationRhythm.NOT_TRACKED }) }
    var addrExpanded by remember { mutableStateOf(false) }
    var familyExpanded by remember { mutableStateOf(false) }
    var metExpanded by remember { mutableStateOf(false) }
    var nextExpanded by remember { mutableStateOf(false) }
    var notesExpanded by remember { mutableStateOf(false) }

    // Шторка «Связь» — мультивыбор с ЧЕРНОВИКОМ: данные формы меняются только по «Готово».
    // Выйти можно лишь кнопками «Отмена»/«Готово» (dismissOnDrag=false): тап мимо и «Назад»
    // не теряют выбор молча (правило V2.2 «никакой тихой потери черновика»).
    if (showRelSheet) {
        RelationshipPickerSheet(
            initial = RelDraft(relationshipType, customRelationType.trim(), secondaryRelationshipTypes),
            onCancel = { showRelSheet = false },
            onDone = { d ->
                relationshipType = d.primary
                customRelationType = d.custom
                secondaryRelationshipTypes = d.secondary
                showRelSheet = false
            }
        )
    }

    // Шторка «Периодичность» — тот же принцип: черновик, применение по «Готово».
    if (showRhythmSheet) {
        val initialRhythm = remember { if (communicationRhythm == CommunicationRhythm.NOT_TRACKED) CommunicationRhythm.NO_SCHEDULE else communicationRhythm }
        val initialDays = remember { customRhythmDaysText }
        var dRhythm by remember { mutableStateOf(initialRhythm) }
        var dDays by remember { mutableStateOf(customRhythmDaysText) }
        FormChoiceSheet(
            title = stringResource(R.string.ce_periodicity),
            hasChanges = { dRhythm != initialRhythm || dDays != initialDays },
            onCancel = { showRhythmSheet = false },
            onDone = {
                communicationRhythm = dRhythm
                lastOnRhythm = dRhythm
                customRhythmDaysText = dDays
                showRhythmSheet = false
            }
        ) {
            CommunicationRhythm.values().filter { it != CommunicationRhythm.NOT_TRACKED }.forEach { r ->
                CheckRow(label = r.label(ctxLabel), checked = dRhythm == r, onClick = { dRhythm = r })
            }
            // CUSTOM («раз в N дней», v17): поле числа дней — прямо в шторке.
            if (dRhythm == CommunicationRhythm.CUSTOM) {
                OutlinedTextField(
                    value = dDays,
                    onValueChange = { new -> if (new.all { it.isDigit() }) dDays = new },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    label = { Text(stringResource(R.string.ce_custom_rhythm_days)) },
                    placeholder = { Text(stringResource(R.string.ce_custom_rhythm_days_hint)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small
                )
            }
        }
    }

    Scaffold(
        containerColor = AppleTheme.colors.groupedBackground,
        topBar = {}
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // ── Шапка (фиксированная): Отмена · заголовок · Готово — НАД вкладками (V2.2 по макету) ──
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
                    if (isEditMode) stringResource(R.string.ce_edit) else stringResource(R.string.ce_new_contact),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleTheme.colors.label
                )
                Button(
                    onClick = ::buildAndSave,
                    enabled = firstName.isNotBlank() && !isSaving,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    shape = SocialShape.Full,
                    colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.colors.brand, contentColor = Color.White),
                    modifier = Modifier.height(34.dp)
                ) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold) }
            }
            // Липкая строка вкладок — ВНЕ скролла, всегда на виду.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sections.forEach { (idx, label) ->
                    val isActive = activeSectionIndex.value == idx
                    Column(
                        modifier = Modifier.weight(1f)
                            .clickable {
                                val target = listOf(contactSectionY, workSectionY, connectionSectionY, moreSectionY)[idx]
                                scope.launch { scrollState.animateScrollTo(target.toInt().coerceAtLeast(0)) }
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            label, fontSize = 13.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            color = if (isActive) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.fillMaxWidth().height(2.dp)
                                .background(if (isActive) AppleTheme.colors.brand else Color.Transparent)
                        )
                    }
                }
            }
            HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { c -> scrollContainerRootY = c.positionInRoot().y }
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // ── V2.2: прямой захват — Скан/Контакты/Голос (только для НОВОГО
            // контакта; при редактировании уже существующего — не показываем).
            // Скан/Контакты переиспользуют существующие камеру/OCR/парсер и
            // системный пикер контактов телефона — внутренности не трогаем,
            // только маршрутизация результата в ЭТУ форму через ContactDraftSession.
            // Голос (фаза 1): on-device распознавание, ТОЛЬКО показ транскрипта —
            // ни одно поле формы и ни одна запись не затрагиваются (VoiceRecognitionSheet
            // не получает никаких колбэков с текстом). ──
            if (!isEditMode) {
                var showVoice by remember { mutableStateOf(false) }
                val devicePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.PickContact()
                ) { uri ->
                    if (uri != null) {
                        try {
                            val id = android.content.ContentUris.parseId(uri)
                            val cursor = ctxLabel.contentResolver.query(
                                android.provider.ContactsContract.Data.CONTENT_URI,
                                com.aistudio.socialsphere.crmlxb.utils.ContactImporter.DATA_PROJECTION,
                                "${android.provider.ContactsContract.Data.CONTACT_ID}=?", arrayOf(id.toString()), null
                            )
                            val rows = cursor?.use { com.aistudio.socialsphere.crmlxb.utils.ContactImporter.readDataRows(it, emptyMap()) } ?: emptyMap()
                            val candidate = rows[id.toString()]
                            if (candidate != null) {
                                firstName = candidate.firstName; lastName = candidate.lastName
                                middleName = candidate.middleName; namePrefix = candidate.namePrefix
                                nameSuffix = candidate.nameSuffix; phoneticFirstName = candidate.phoneticFirstName
                                phoneticMiddleName = candidate.phoneticMiddleName; phoneticLastName = candidate.phoneticLastName
                                if (candidate.phones.isNotEmpty()) phones = candidate.phones.map { it.copy(contactId = editedContactId) }
                                if (candidate.emails.isNotEmpty()) emails = candidate.emails.map { it.copy(contactId = editedContactId) }
                                if (candidate.addresses.isNotEmpty()) draftAddresses = candidate.addresses.map { it.copy(ownerId = editedContactId) }
                                candidate.jobTitle?.let { companyPosition = it }
                                candidate.companyName?.let { name ->
                                    AppStateStore.findCompanyByName(name)?.let { selectedCompanyId = it.id }
                                }
                                candidate.birthday?.trim()?.takeIf { it.isNotBlank() }?.let {
                                    newDatesDraft = newDatesDraft + NewContactDate(CalendarItemType.BIRTHDAY, CalendarItemType.BIRTHDAY.label(ctxLabel), it)
                                }
                                candidate.notes?.trim()?.takeIf { it.isNotBlank() }?.let { newNotesDraft = newNotesDraft + it }
                            }
                        } catch (e: SecurityException) {
                            // Разрешение отозвано между пикером и чтением — тихо ничего не подставляем.
                        }
                    }
                }
                val contactsPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted -> if (granted) devicePicker.launch(null) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CaptureActionButton(
                        icon = Icons.Outlined.DocumentScanner, label = stringResource(R.string.ce_capture_scan),
                        modifier = Modifier.weight(1f), onClick = { onNavigateToScan?.invoke() }
                    )
                    CaptureActionButton(
                        icon = Icons.Default.Contacts, label = stringResource(R.string.ce_capture_contacts),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (com.aistudio.socialsphere.crmlxb.utils.DeviceContacts.hasPermission(ctxLabel)) devicePicker.launch(null)
                            else contactsPermLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                        }
                    )
                    CaptureActionButton(
                        icon = Icons.Default.Mic, label = stringResource(R.string.ce_capture_voice),
                        modifier = Modifier.weight(1f), onClick = { showVoice = true }
                    )
                }
                if (showVoice) VoiceRecognitionSheet(onDismiss = { showVoice = false })
            }

            // ── Фото по центру (как в макете) ─────────────────────────
            // Рабочий выбор фото: системный фото-пикер → копия в filesDir
            // (URI пикера временный) → photoUri в состоянии/сохранении.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                var showPhotoMenu by remember { mutableStateOf(false) }
                val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        com.aistudio.socialsphere.crmlxb.utils.PhotoStorage
                            .saveContactPhoto(ctxLabel, uri, editedContactId)
                            ?.let { photoUri = it }
                    }
                }
                fun launchPhotoPicker() = photoPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
                Box(contentAlignment = Alignment.BottomEnd) {
                    val photoFile = photoUri?.let { java.io.File(it) }?.takeIf { it.exists() }
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape)
                            .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                            .clickable { if (photoFile != null) showPhotoMenu = true else launchPhotoPicker() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photoFile != null) {
                            coil.compose.AsyncImage(
                                model = photoFile, contentDescription = stringResource(R.string.ce_add_photo),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape)
                            )
                        } else {
                            val initial = (firstName.firstOrNull() ?: lastName.firstOrNull())?.uppercaseChar()
                            if (initial != null) {
                                Text(initial.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AppleTheme.colors.brand, fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(initial.toString()))
                            } else {
                                Icon(Icons.Default.Person, null, Modifier.size(36.dp), tint = AppleTheme.colors.brand.copy(alpha = 0.6f))
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(AppleTheme.colors.brand)
                            .clickable { if (photoFile != null) showPhotoMenu = true else launchPhotoPicker() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (photoFile != null) Icons.Default.Edit else Icons.Default.Add,
                            null, Modifier.size(14.dp), tint = Color.White)
                    }
                    DropdownMenu(expanded = showPhotoMenu, onDismissRequest = { showPhotoMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ce_photo_replace)) },
                            onClick = { showPhotoMenu = false; launchPhotoPicker() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ce_photo_remove), color = AppleTheme.colors.red) },
                            onClick = {
                                showPhotoMenu = false
                                com.aistudio.socialsphere.crmlxb.utils.PhotoStorage
                                    .deleteContactPhotos(ctxLabel, editedContactId)
                                photoUri = null
                            }
                        )
                    }
                }
                Text(
                    if (photoUri == null) stringResource(R.string.ce_add_photo)
                    else stringResource(R.string.ce_photo_replace),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppleTheme.colors.brand,
                    modifier = Modifier.clickable { if (photoUri != null) showPhotoMenu = true else launchPhotoPicker() }
                )
            }

            // ── Полная структура имени как в Android-контактах (v13): приставка/
            // имя/фамилия/отчество/суффикс/фонетика — владелец попросил «хочу как
            // в андроид, идентично» (5 полей структуры + 2 фонетических).
            // Фидбэк UX-аудита: редкие поля (приставка/суффикс/фонетика) не должны
            // опережать часто заполняемые — они свёрнуты под «Дополнительно». ──
            var advancedNameFieldsExpanded by remember { mutableStateOf(middleName.isNotBlank() || nickname.isNotBlank() || namePrefix.isNotBlank() || nameSuffix.isNotBlank() || phoneticFirstName.isNotBlank() || phoneticMiddleName.isNotBlank() || phoneticLastName.isNotBlank()) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // V2.2: Имя и Фамилия — ОДНА карточка, каждое поле во всю ширину; «Дополнительные данные
                // имени» — свёрнутая ссылка внутри той же карточки (по макету).
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        BareFieldColumn(
                            label = stringResource(R.string.ce_name_req), value = firstName,
                            onValueChange = { firstName = it }, keyboardOptions = CapWords,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fillInputWidth = true
                        )
                        HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
                        BareFieldColumn(
                            label = stringResource(R.string.ce_surname), value = lastName,
                            onValueChange = { lastName = it }, keyboardOptions = CapWords,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fillInputWidth = true
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { advancedNameFieldsExpanded = !advancedNameFieldsExpanded }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.ce_advanced_fields),
                                style = MaterialTheme.typography.labelLarge,
                                color = AppleTheme.colors.brand
                            )
                            Icon(
                                if (advancedNameFieldsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, Modifier.size(18.dp), tint = AppleTheme.colors.brand
                            )
                        }
                    }
                }
                if (liveDuplicateHints.isNotEmpty() && !dupHintsDismissed) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.brand.copy(alpha = 0.08f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.ce_dup_hint_title),
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    color = AppleTheme.colors.brand
                                )
                                Icon(
                                    Icons.Default.Close, stringResource(R.string.common_close),
                                    tint = AppleTheme.colors.secondaryLabel,
                                    modifier = Modifier.size(18.dp).clickable { dupHintsDismissed = true }
                                )
                            }
                            liveDuplicateHints.take(3).forEach { hint ->
                                val reasons = listOfNotNull(
                                    hint.byPhone?.let { stringResource(R.string.ce_dup_hint_reason_phone) },
                                    hint.byEmail?.let { stringResource(R.string.ce_dup_hint_reason_email) },
                                    if (hint.byNameSimilarity) stringResource(R.string.ce_dup_hint_reason_name) else null
                                ).joinToString(" · ")
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { onNavigateToContact?.invoke(hint.contact.id) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaAvatar(
                                        hint.contact.id, "${hint.contact.firstName} ${hint.contact.lastName}".trim(),
                                        size = 32.dp, fontSize = 13.sp
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${hint.contact.firstName} ${hint.contact.lastName}".trim(),
                                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                                        )
                                        if (reasons.isNotEmpty())
                                            Text(reasons, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                                    }
                                    Icon(Icons.Default.KeyboardArrowRight, null,
                                        Modifier.size(18.dp), tint = AppleTheme.colors.secondaryLabel)
                                }
                            }
                        }
                    }
                }
                if (advancedNameFieldsExpanded) {
                    // Отчество — как в телефонной книге (импортируется из vCard/устройства)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        BareFieldColumn(
                            label = stringResource(R.string.ce_middle_name), value = middleName,
                            onValueChange = { middleName = it }, keyboardOptions = CapWords,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fillInputWidth = true
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        BareFieldColumn(
                            label = stringResource(R.string.ce_nickname), value = nickname,
                            onValueChange = { nickname = it }, keyboardOptions = CapWords,
                            placeholder = stringResource(R.string.ce_nickname_hint),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fillInputWidth = true
                        )
                    }
                    // Приставка (Dr./г-н) — отдельная карточка, как в андроид-редакторе
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        BareFieldColumn(
                            label = stringResource(R.string.ce_name_prefix), value = namePrefix,
                            onValueChange = { namePrefix = it }, keyboardOptions = CapWords,
                            placeholder = stringResource(R.string.ce_name_prefix_hint),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fillInputWidth = true
                        )
                    }
                    // Суффикс (мл./ст.) — как в андроид-редакторе, идёт после отчества/фамилии
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        BareFieldColumn(
                            label = stringResource(R.string.ce_name_suffix), value = nameSuffix,
                            onValueChange = { nameSuffix = it }, keyboardOptions = CapWords,
                            placeholder = stringResource(R.string.ce_name_suffix_hint),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fillInputWidth = true
                        )
                    }
                    // Фонетические имя/отчество/фамилия — для языков вроде японского, где
                    // произношение не следует из письменной формы (как в андроид)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
                        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            BareFieldColumn(
                                label = stringResource(R.string.ce_phonetic_first_name), value = phoneticFirstName,
                                onValueChange = { phoneticFirstName = it }, keyboardOptions = CapWords,
                                placeholder = stringResource(R.string.ce_phonetic_hint),
                                modifier = Modifier.weight(1f).padding(12.dp),
                                fillInputWidth = true
                            )
                            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(AppleTheme.colors.separator))
                            BareFieldColumn(
                                label = stringResource(R.string.ce_phonetic_middle_name), value = phoneticMiddleName,
                                onValueChange = { phoneticMiddleName = it }, keyboardOptions = CapWords,
                                placeholder = stringResource(R.string.ce_phonetic_hint),
                                modifier = Modifier.weight(1f).padding(12.dp),
                                fillInputWidth = true
                            )
                            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(AppleTheme.colors.separator))
                            BareFieldColumn(
                                label = stringResource(R.string.ce_phonetic_last_name), value = phoneticLastName,
                                onValueChange = { phoneticLastName = it }, keyboardOptions = CapWords,
                                placeholder = stringResource(R.string.ce_phonetic_hint),
                                modifier = Modifier.weight(1f).padding(12.dp),
                                fillInputWidth = true
                            )
                        }
                    }
                }
            }

            // ── КОНТАКТ: телефоны и email — компактные карточки, пустое состояние = одна строка «+ Добавить» ──
            AureliaCapsLabel(
                stringResource(R.string.ce_section_contact), color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.onGloballyPositioned { c -> contactSectionY = scrollState.value + (c.positionInRoot().y - scrollContainerRootY) }
            )
            // Повторы ПОКАЗЫВАЮТСЯ, а не удаляются сами (решение владельца 2026-10-10): строка помечена
            // «уже есть выше», удалить её — красным крестиком, решает пользователь.
            val repeatMarker = stringResource(R.string.ce_repeat_marker)
            val repeatedPhoneIds = ContactChannelDedup.repeatedPhoneIds(phones)
            val repeatedEmailIds = ContactChannelDedup.repeatedEmailIds(emails)
            FormCard {
                phones.forEachIndexed { idx, phone ->
                    ContactItemRow(
                        icon = Icons.Default.Call, iconTint = AppleTheme.colors.brand, iconBg = AppleTheme.colors.brand.copy(alpha = 0.10f),
                        value = phone.number, onValueChange = { num -> phones = phones.toMutableList().also { it[idx] = phone.copy(number = num) } },
                        keyboardOptions = PhoneKeyboard,
                        label = phone.type.label(ctxLabel) + if (phone.id in repeatedPhoneIds) " · $repeatMarker" else "",
                        isPrimary = phone.isPrimary, onTogglePrimary = { phones = phones.mapIndexed { i, p -> p.copy(isPrimary = i == idx) }.sortedByDescending { it.isPrimary } },
                        onDelete = { phones = phones.toMutableList().also { it.removeAt(idx) } }
                    )
                }
                FormAddRow(stringResource(R.string.ce_add_phone)) { showAddPhone = true }
            }
            FormCard {
                emails.forEachIndexed { idx, email ->
                    ContactItemRow(
                        icon = Icons.Default.Email, iconTint = AureliaTheme.colors.gold, iconBg = AureliaTheme.colors.gold.copy(alpha = 0.14f),
                        value = email.email, onValueChange = { v -> emails = emails.toMutableList().also { it[idx] = email.copy(email = v) } },
                        keyboardOptions = EmailKeyboard,
                        label = email.type.label(ctxLabel) + if (email.id in repeatedEmailIds) " · $repeatMarker" else "",
                        isPrimary = email.isPrimary, onTogglePrimary = { emails = emails.mapIndexed { i, e -> e.copy(isPrimary = i == idx) }.sortedByDescending { it.isPrimary } },
                        onDelete = { emails = emails.toMutableList().also { it.removeAt(idx) } }
                    )
                }
                FormAddRow(stringResource(R.string.ce_add_email)) { showAddEmail = true }
            }

            // ── РАБОТА: компания → должность → чем занимается → «Рабочие детали» (свёрнуто) ──
            AureliaCapsLabel(
                stringResource(R.string.ce_section_work), color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.onGloballyPositioned { c -> workSectionY = scrollState.value + (c.positionInRoot().y - scrollContainerRootY) }
            )
            FormCard {
                var showCompanyDropdown by remember { mutableStateOf(false) }
                var showNewCompanyDialog by remember { mutableStateOf(false) }
                val companies = AppStateStore.companies
                val selectedCompanyName = companies.find { it.id == selectedCompanyId }?.name ?: ""

                OutlinedTextField(
                    value = selectedCompanyName, onValueChange = {},
                    label = { Text(stringResource(R.string.ce_company)) },
                    modifier = Modifier.fillMaxWidth().clickable { showCompanyDropdown = true },
                    enabled = false, shape = SocialShape.Small,
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = AppleTheme.colors.label,
                        disabledBorderColor = AppleTheme.colors.tertiaryLabel,
                        disabledLabelColor = AppleTheme.colors.secondaryLabel
                    )
                )
                // Выбор компании — канонический пикер с поиском, строкой «создать»
                // и пунктом «Без компании» (сброс) первой строкой списка.
                if (showCompanyDropdown) {
                    val noCompanyLabel = stringResource(R.string.ce_no_company)
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                        title = stringResource(R.string.ce_company),
                        items = buildList {
                            if (selectedCompanyId.isNotBlank()) add(
                                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                                    id = "", title = noCompanyLabel
                                )
                            )
                            companies.sortedBy { it.name.lowercase() }.forEach { company ->
                                add(com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(
                                    id = company.id,
                                    title = company.name,
                                    subtitle = company.industry.label(ctxLabel),
                                    isCompany = true
                                ))
                            }
                        },
                        onPick = { picked ->
                            // Смена компании — старая привязка «это адрес компании»
                            // принадлежала другой компании, сбрасываем.
                            if (picked.id != selectedCompanyId) linkedOfficeAddressId = null
                            selectedCompanyId = picked.id
                            showCompanyDropdown = false
                        },
                        onDismiss = { showCompanyDropdown = false },
                        searchPlaceholder = stringResource(R.string.ce_search_company),
                        emptyText = stringResource(R.string.picker_no_results),
                        createNewText = stringResource(R.string.ce_new_company),
                        onCreateNew = { showCompanyDropdown = false; showNewCompanyDialog = true }
                    )
                }
                if (showNewCompanyDialog) {
                    var newCompanyName by remember { mutableStateOf("") }
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.ce_new_company_title),
                        onDismiss = { showNewCompanyDialog = false },
                        confirmText = stringResource(R.string.ce_create),
                        confirmEnabled = newCompanyName.isNotBlank(),
                        onConfirm = {
                            val clean = newCompanyName.trim()
                            // Дедуп: компания с таким именем уже есть — выбираем её
                            val existing = AppStateStore.findCompanyByName(clean)
                            if (existing != null) {
                                selectedCompanyId = existing.id
                            } else {
                                val now = java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                val company = Company(
                                    id = UUID.randomUUID().toString(),
                                    name = clean,
                                    industry = Industry.OTHER,
                                    createdAt = now,
                                    updatedAt = now
                                )
                                AppStateStore.addCompany(company)
                                selectedCompanyId = company.id
                            }
                            showNewCompanyDialog = false
                        },
                        secondaryText = stringResource(R.string.common_cancel),
                        onSecondary = { showNewCompanyDialog = false }
                    ) {
                        OutlinedTextField(
                            value = newCompanyName,
                            onValueChange = { newCompanyName = it }, keyboardOptions = CapWords,
                            label = { Text(stringResource(R.string.ce_company_name_req)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = SocialShape.Small
                        )
                    }
                }
                if (selectedCompanyId.isNotBlank()) {
                    OutlinedTextField(value = companyPosition, onValueChange = { companyPosition = it }, keyboardOptions = CapSentences, label = { Text(stringResource(R.string.ce_position)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                }
                // Профессия БЕЗ компании (фидбэк владельца 2026-07-03: «не могу
                // добавить профессию, не указав компанию») — поле видно всегда.
                OutlinedTextField(
                    value = profession, onValueChange = { profession = it },
                    keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_profession)) },
                    placeholder = { Text(stringResource(R.string.ce_profession_hint)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small
                )
                if (selectedCompanyId.isNotBlank()) {
                    // «Рабочие детали» свёрнуты (V2.2) — отдел/заметка о работе реже заполняются сразу.
                    var workDetailsExpanded by remember { mutableStateOf(companyDept.isNotBlank() || workNote.isNotBlank()) }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { workDetailsExpanded = !workDetailsExpanded }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.ce_work_details), style = MaterialTheme.typography.labelLarge, color = AppleTheme.colors.brand)
                        Icon(if (workDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = AppleTheme.colors.brand)
                    }
                    if (workDetailsExpanded) {
                        OutlinedTextField(value = companyDept, onValueChange = { companyDept = it }, keyboardOptions = CapSentences, label = { Text(stringResource(R.string.ce_department)) },    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = SocialShape.Small)
                        OutlinedTextField(value = workNote,    onValueChange = { workNote    = it }, keyboardOptions = CapSentences, label = { Text(stringResource(R.string.ce_work_note)) }, modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small)
                    }
                }
            }

            // ── СВЯЗЬ: ОДНА карточка — отношения (шторка), «Поддерживать связь» (переключатель →
            // периодичность), теги. ContactStatus/SocialRole убраны из ЭТОЙ формы (решение V2.2) —
            // значения проносятся из оригинала в buildAndSave(), колонки в БД не меняются. ──
            AureliaCapsLabel(
                stringResource(R.string.ce_section_connection), color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.onGloballyPositioned { c -> connectionSectionY = scrollState.value + (c.positionInRoot().y - scrollContainerRootY) }
            )
            FormCard {
                // Отношения: единый мультивыбор (без терминов Primary/Secondary/Also) — строка-сводка → шторка.
                val relSummary = (
                    listOfNotNull(customRelationType.trim().ifBlank { relationshipType?.label(ctxLabel) }) +
                        secondaryRelationshipTypes.map { it.label(ctxLabel) }
                    ).joinToString(" · ")
                Row(
                    Modifier.fillMaxWidth().clickable { showRelSheet = true }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ce_relation_type), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
                        if (relSummary.isEmpty()) {
                            // Пустое состояние (новый контакт): «Добавить», как в прототипе.
                            Text(
                                stringResource(R.string.common_add), style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand
                            )
                        } else Text(
                            relSummary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                            color = AppleTheme.colors.label, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.secondaryLabel)
                }
                HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)

                // Поддерживать связь: NOT_TRACKED = выкл; включение даёт «Без расписания» (NO_SCHEDULE) или
                // последнюю выбранную периодичность. Модель данных не менялась.
                val stayOn = communicationRhythm != CommunicationRhythm.NOT_TRACKED
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ce_stay_in_touch), Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppleTheme.colors.label
                    )
                    Switch(
                        checked = stayOn,
                        onCheckedChange = { on ->
                            if (on) communicationRhythm = lastOnRhythm ?: CommunicationRhythm.NO_SCHEDULE
                            else { lastOnRhythm = communicationRhythm; communicationRhythm = CommunicationRhythm.NOT_TRACKED }
                        }
                    )
                }
                if (stayOn) {
                    val customDays = customRhythmDaysText.trim().toIntOrNull()
                    val rhythmText = if (communicationRhythm == CommunicationRhythm.CUSTOM && customDays != null)
                        stringResource(R.string.cd_rhythm_custom_days, customDays)
                    else communicationRhythm.label(ctxLabel)
                    Row(
                        Modifier.fillMaxWidth().clickable { showRhythmSheet = true }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ce_periodicity), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
                            Text(rhythmText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = AppleTheme.colors.secondaryLabel)
                    }
                    // «Следующий контакт» — ВСЕГДА вычисляется (StaleContacts.overdueDays), нигде не хранится
                    // как поле. Показываем только когда есть что показать.
                    val overdue = com.aistudio.socialsphere.crmlxb.utils.StaleContacts.overdueDays(
                        communicationRhythm, originalContact?.lastContactDate, customDays
                    )
                    if (overdue != null) {
                        Text(
                            stringResource(R.string.ce_next_contact_due),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppleTheme.colors.red
                        )
                    }
                }
                HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)

                // Теги (доступны прямо в Create/Edit). Управляемая система AppStateStore.tags/tagMembers — НЕ
                // легаси Contact.tags (§70/§55). Черновик-до-сохранения: членство пишется в buildAndSave().
                var showTagPicker by remember { mutableStateOf(false) }
                var showCreateTag by remember { mutableStateOf(false) }
                var newTagName by remember { mutableStateOf("") }
                var newTagCategory by remember { mutableStateOf("") }
                Text(stringResource(R.string.ce_tags), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tagsDraft.forEach { tag ->
                        AssistChip(
                            onClick = { tagsDraft = tagsDraft - tag },
                            label = { Text(tag.name, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                            shape = SocialShape.Full
                        )
                    }
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(AppleTheme.colors.brand.copy(alpha = 0.10f))
                            .clickable { showTagPicker = true },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Add, stringResource(R.string.tag_add), Modifier.size(18.dp), tint = AppleTheme.colors.brand) }
                }
                if (showTagPicker) {
                    val myTagIds = tagsDraft.map { it.id }.toSet()
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickerSheet(
                        title = stringResource(R.string.tag_picker_title),
                        items = AppStateStore.tags.filter { it.id !in myTagIds }.sortedBy { it.name.lowercase() }
                            .map { com.aistudio.socialsphere.crmlxb.ui.theme.AureliaPickItem(id = it.id, title = it.name, subtitle = it.category) },
                        onPick = { picked ->
                            AppStateStore.tags.find { it.id == picked.id }?.let { tagsDraft = tagsDraft + it }
                            showTagPicker = false
                        },
                        onDismiss = { showTagPicker = false },
                        searchPlaceholder = stringResource(R.string.tag_search_placeholder),
                        emptyText = stringResource(R.string.picker_no_results),
                        createNewText = stringResource(R.string.tag_create_new),
                        onCreateNew = { showTagPicker = false; showCreateTag = true }
                    )
                }
                if (showCreateTag) {
                    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaFormSheet(
                        title = stringResource(R.string.tag_create_new),
                        onDismiss = { showCreateTag = false; newTagName = ""; newTagCategory = "" },
                        confirmText = stringResource(R.string.common_add),
                        confirmEnabled = newTagName.isNotBlank(),
                        onConfirm = {
                            val created = AppStateStore.addTag(newTagName, newTagCategory.trim().takeIf { it.isNotBlank() })
                            if (created != null) tagsDraft = tagsDraft + created
                            newTagName = ""; newTagCategory = ""; showCreateTag = false
                        },
                        secondaryText = stringResource(R.string.common_cancel),
                        onSecondary = { showCreateTag = false; newTagName = ""; newTagCategory = "" }
                    ) {
                        OutlinedTextField(value = newTagName, onValueChange = { newTagName = it }, keyboardOptions = CapWords,
                            label = { Text(stringResource(R.string.ce_tag_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = newTagCategory, onValueChange = { newTagCategory = it }, keyboardOptions = CapWords,
                            label = { Text(stringResource(R.string.tag_category_optional)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
            }

            // ── ЕЩЁ: важные даты + строки-сводки (раскрываются на месте) + мессенджеры ──
            AureliaCapsLabel(
                stringResource(R.string.ce_section_more), color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier.onGloballyPositioned { c -> moreSectionY = scrollState.value + (c.positionInRoot().y - scrollContainerRootY) }
            )
            // Важные даты: существующий механизм CalendarItem + CalendarItemLink — НЕ поле Contact.
            FormCard {
                Text(stringResource(R.string.ce_important_dates), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
                val existingDates = remember(originalContact?.id) {
                    // Тот же отбор, что на карточке (ContactCardData.kt): форма и карточка не расходятся в том, что считать датой.
                    if (originalContact == null) emptyList() else contactCardDates(AppStateStore.calendarItems, originalContact.id)
                }
                existingDates.forEach { ci ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ci.title, style = MaterialTheme.typography.bodyMedium)
                        Text(ci.startDate, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                    }
                }
                newDatesDraft.forEachIndexed { idx, d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title.ifBlank { d.type.label(ctxLabel) }, style = MaterialTheme.typography.bodyMedium)
                            Text(d.startDate, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                        }
                        IconButton(onClick = { newDatesDraft = newDatesDraft.toMutableList().also { it.removeAt(idx) } }) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = AppleTheme.colors.red)
                        }
                    }
                }
                FormAddRow(stringResource(R.string.ce_add_date)) { showAddDate = true }
                if (showAddDate) {
                    // Единый шит «Добавить важную дату» — тот же, что на карточке (ContactDateEntry.kt).
                    ContactDateSheet(
                        onDismiss = { showAddDate = false },
                        onAdd = { newDatesDraft = newDatesDraft + it; showAddDate = false },
                    )
                }
            }

            // Адреса (UX адресов не менялся — тот же список и диалог, только свёрнуто в строку).
            SummaryRowCard(
                label = stringResource(R.string.ce_addresses),
                summary = draftAddresses.map { it.addressType.label(ctxLabel) }.distinct().joinToString(" · ").ifBlank { null },
                expanded = addrExpanded, onToggle = { addrExpanded = !addrExpanded }
            ) {
                var showAddrDialog by remember { mutableStateOf(false) }
                var editingAddr by remember { mutableStateOf<Address?>(null) }
                if (draftAddresses.isEmpty()) {
                    Text(
                        stringResource(R.string.ce_address_after_save),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel
                    )
                }
                draftAddresses.forEach { addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { editingAddr = addr; showAddrDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                addr.addressLine,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                listOf(addr.addressType.label(ctxLabel), addr.city, addr.postalCode.orEmpty(), addr.country)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel
                            )
                        }
                        IconButton(onClick = { draftAddresses = draftAddresses - addr }) {
                            Icon(Icons.Default.Close, stringResource(R.string.ce_remove_address),
                                Modifier.size(16.dp),
                                tint = AppleTheme.colors.secondaryLabel)
                        }
                    }
                    // «Это адрес компании» (2026-07-23, решение владельца) — только
                    // у рабочего адреса и только если компания выбрана; дедуп по
                    // улице+городу и создание/связывание — на сохранении формы
                    // (см. buildAndSave → AppStateStore.linkContactAddressToCompany).
                    if (addr.addressType == AddressType.WORK && selectedCompanyId.isNotBlank()) {
                        val companyName = AppStateStore.companies.find { it.id == selectedCompanyId }?.name.orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { linkedOfficeAddressId = if (linkedOfficeAddressId == addr.id) null else addr.id }
                                .padding(start = 4.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = linkedOfficeAddressId == addr.id,
                                // Тап обрабатывает Row целиком (см. clickable выше) —
                                // здесь null, чтобы не сработать дважды на одном тапе.
                                onCheckedChange = null
                            )
                            Text(
                                stringResource(R.string.ce_this_is_company_address, companyName),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel
                            )
                        }
                    }
                    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
                }
                TextButton(onClick = { editingAddr = null; showAddrDialog = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ce_address))
                }
                if (showAddrDialog) {
                    // ЕДИНЫЙ диалог адреса (AddressComponents.kt) — та же реализация,
                    // что на вкладке Связь; геокодинг внутри компонента.
                    AddressEditDialog(
                        base = editingAddr,
                        ownerId = editedContactId,
                        scope = scope,
                        onDismiss = { showAddrDialog = false; editingAddr = null },
                        onCommit = { a ->
                            draftAddresses = if (draftAddresses.none { it.id == a.id }) draftAddresses + a
                                else draftAddresses.map { if (it.id == a.id) a else it }
                        },
                        onGeocoded = { a ->
                            draftAddresses = draftAddresses.map { if (it.id == a.id) a else it }
                        }
                    )
                }
            }

            // Семья и связи
            SummaryRowCard(
                label = stringResource(R.string.ce_family_relations),
                summary = (contactRelationsDraft.mapNotNull { rel ->
                    val otherId = if (rel.firstContactId == editedContactId) rel.secondContactId else rel.firstContactId
                    AppStateStore.getContact(otherId)?.let { "${it.firstName} ${it.lastName}".trim() }
                } + listOfNotNull(familyNote.trim().takeIf { it.isNotBlank() })).joinToString(" · ").ifBlank { null },
                expanded = familyExpanded, onToggle = { familyExpanded = !familyExpanded }
            ) {
                if (contactRelationsDraft.isEmpty()) {
                    Text(stringResource(R.string.ce_no_related),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppleTheme.colors.secondaryLabel)
                }
                contactRelationsDraft.forEach { rel ->
                    val isFirst   = rel.firstContactId == editedContactId
                    val otherId   = if (isFirst) rel.secondContactId else rel.firstContactId
                    val otherRole = if (isFirst) rel.secondRole else rel.firstRole
                    val otherName = AppStateStore.getContact(otherId)
                        ?.let { "${it.firstName} ${it.lastName}".trim() } ?: stringResource(R.string.ce_contact_deleted)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(otherName, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(relationRoleLabel(ctxLabel, otherRole), style = MaterialTheme.typography.bodySmall,
                                color = AppleTheme.colors.secondaryLabel)
                        }
                        IconButton(onClick = {
                            contactRelationsDraft = contactRelationsDraft.filter { it.id != rel.id }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ce_remove_relation),
                                tint = AppleTheme.colors.red,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
                TextButton(onClick = { showAddRelation = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ce_add_person))
                }
                OutlinedTextField(
                    value = familyNote, onValueChange = { familyNote = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.cd_family_note)) },
                    placeholder = { Text(stringResource(R.string.cd_family_note_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small,
                    minLines = 2, maxLines = 4
                )
            }

            // Где познакомились
            SummaryRowCard(
                label = stringResource(R.string.ce_where_met),
                summary = listOf(meetContext.trim(), meetDate.trim()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                expanded = metExpanded, onToggle = { metExpanded = !metExpanded }
            ) {
                OutlinedTextField(
                    value = meetContext, onValueChange = { meetContext = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_meet_context)) },
                    placeholder = { Text(stringResource(R.string.ce_meet_context_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = meetDate, onValueChange = { meetDate = it },
                    label = { Text(stringResource(R.string.ce_meet_date)) },
                    placeholder = { Text("2024-11-15") },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small,
                    singleLine = true
                )
            }

            // Следующий шаг и польза
            SummaryRowCard(
                label = stringResource(R.string.ce_next_step_benefit),
                summary = listOf(nextStep, canHelpWith, iCanHelpWith, talkingPoints).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                expanded = nextExpanded, onToggle = { nextExpanded = !nextExpanded }
            ) {
                OutlinedTextField(
                    value = nextStep, onValueChange = { nextStep = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_next_step)) },
                    placeholder = { Text(stringResource(R.string.ce_next_step_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = canHelpWith, onValueChange = { canHelpWith = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_can_help)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = iCanHelpWith, onValueChange = { iCanHelpWith = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_i_can_help)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small
                )
                OutlinedTextField(
                    value = talkingPoints, onValueChange = { talkingPoints = it }, keyboardOptions = CapSentences,
                    label = { Text(stringResource(R.string.ce_talking_points)) },
                    placeholder = { Text(stringResource(R.string.ce_talking_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = SocialShape.Small,
                    minLines = 2
                )
            }

            // Заметки: быстрое добавление через существующий Note/addNote; полная история — на Contact Detail.
            val allNoteTexts = originalContact?.notes.orEmpty().sortedByDescending { it.createdAt }.map { it.text } + newNotesDraft
            SummaryRowCard(
                label = stringResource(R.string.ce_notes),
                summary = allNoteTexts.firstOrNull()?.let { first -> if (allNoteTexts.size > 1) "$first (+${allNoteTexts.size - 1})" else first },
                expanded = notesExpanded, onToggle = { notesExpanded = !notesExpanded }
            ) {
                val existingNotes = originalContact?.notes.orEmpty().sortedByDescending { it.createdAt }
                existingNotes.take(3).forEach { n ->
                    Text("• " + n.text, style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
                newNotesDraft.forEachIndexed { idx, note ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("• " + note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { newNotesDraft = newNotesDraft.toMutableList().also { it.removeAt(idx) } }) {
                            Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = AppleTheme.colors.red)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quickNoteText, onValueChange = { quickNoteText = it }, keyboardOptions = CapSentences,
                        placeholder = { Text(stringResource(R.string.ce_add_note)) },
                        modifier = Modifier.weight(1f), singleLine = true, shape = SocialShape.Small
                    )
                    IconButton(onClick = {
                        if (quickNoteText.isNotBlank()) { newNotesDraft = newNotesDraft + quickNoteText.trim(); quickNoteText = "" }
                    }) { Icon(Icons.Default.Add, stringResource(R.string.ce_add_note), tint = AppleTheme.colors.brand) }
                }
            }

            // Мессенджеры (в «Ещё», по макету)
            FormCard {
                Text(stringResource(R.string.ce_messengers), style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
                if (messengers.isEmpty()) {
                    Text(stringResource(R.string.ce_no_messengers), style = MaterialTheme.typography.bodySmall, color = AppleTheme.colors.secondaryLabel)
                }
                messengers.forEachIndexed { idx, m ->
                    ContactItemRow(
                        icon = Icons.AutoMirrored.Filled.Send, iconTint = AppleTheme.colors.red, iconBg = AppleTheme.colors.red.copy(alpha = 0.10f),
                        value = m.value, onValueChange = { v -> messengers = messengers.toMutableList().also { it[idx] = m.copy(value = v) } },
                        label = m.type.label(ctxLabel),
                        isPrimary = m.isPrimary, onTogglePrimary = { messengers = messengers.mapIndexed { i, mm -> mm.copy(isPrimary = i == idx) }.sortedByDescending { it.isPrimary } },
                        onDelete = { messengers = messengers.toMutableList().also { it.removeAt(idx) } }
                    )
                }
                FormAddRow(stringResource(R.string.ce_add_messenger)) { showAddMessenger = true }
            }

            Spacer(Modifier.height(32.dp))
        }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.Large,
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.brand)
            HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
            content()
        }
    }
}

/** Карточка-контейнер группы строк V2.2 (без заголовка и разделителя — подпись секции снаружи). */
@Composable
internal fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SocialShape.Large,
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * Строка-сводка V2.2: подпись + значение (или «Добавить») + шеврон; тап раскрывает содержимое НА МЕСТЕ
 * (аккордеон, как в прототипе). Скрытое содержимое остаётся в состоянии формы — сворачивание ничего не стирает.
 */
@Composable
internal fun SummaryRowCard(
    label: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SocialShape.Large,
        colors = CardDefaults.cardColors(containerColor = AppleTheme.colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
                    Text(
                        summary ?: stringResource(R.string.common_add),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        color = if (summary == null) AppleTheme.colors.brand else AppleTheme.colors.label,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.KeyboardArrowRight,
                    null, Modifier.size(18.dp), tint = AppleTheme.colors.secondaryLabel
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

/** Строка «＋ Добавить …» внутри карточки V2.2. */
@Composable
internal fun FormAddRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(SocialShape.R14).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(AppleTheme.colors.brand.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = AppleTheme.colors.brand)
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AppleTheme.colors.brand)
    }
}

/** Строка списка выбора с круглой отметкой (шторки «Связь» и «Периодичность»). */
@Composable
internal fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = AppleTheme.colors.label)
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (checked) AppleTheme.colors.brand else Color.Transparent)
                .border(1.5.dp, if (checked) AppleTheme.colors.brand else AppleTheme.colors.separator, CircleShape),
            contentAlignment = Alignment.Center
        ) { if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White) }
    }
    HorizontalDivider(color = AppleTheme.colors.separator, thickness = 0.5.dp)
}

/**
 * Шторка выбора с ЧЕРНОВИКОМ: «Отмена» / «Готово». dismissOnDrag = false — тап мимо шторки и жест «Назад»
 * её НЕ закрывают (иначе выбор терялся бы молча): выйти можно только явной кнопкой.
 */
@Composable
internal fun FormChoiceSheet(
    title: String,
    subtitle: String? = null,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    listScroll: ScrollState = rememberScrollState(),
    // Выбор в шторке изменён, но ещё не применён: «Назад»/тап мимо не выбрасывают его молча, а спрашивают
    // «Сохранить / Не сохранять / Продолжить» (жалоба 2026-10-08). «Сохранить» = «Готово».
    hasChanges: (() -> Boolean)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var submitted by remember { mutableStateOf(false) }
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet(
        onDismiss = onCancel,
        dismissOnDrag = false,
        hasUnsavedInput = hasChanges?.let { check -> { !submitted && check() } },
        onSaveRequest = { if (!submitted) { submitted = true; onDone() } },
        // «Отмена»/«Готово» закреплены внизу шторки — всегда на виду, в том числе над клавиатурой
        // (поле «своего» типа / числа дней).
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.secondaryLabel,
                    modifier = Modifier.clip(SocialShape.R14).clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 12.dp)
                )
                Button(
                    onClick = { if (!submitted) { submitted = true; onDone() } },
                    enabled = !submitted,
                    shape = SocialShape.R14,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.Bold) }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                fontFamily = com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor(title), fontWeight = FontWeight.W700,
                fontSize = 20.sp, color = AppleTheme.colors.label
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppleTheme.colors.secondaryLabel)
            // Список прокручивается отдельно (heightIn ДО verticalScroll — конечная высота внутри
            // прокручиваемой шторки).
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(listScroll),
                content = content
            )
        }
    }
}

/**
 * Черновик выбора типов отношений (шторка «Связь»). Хранение прежнее: relationshipType — «главный»
 * стандартный тип (или OTHER при своём), custom — свой тип, secondary — остальные выбранные.
 * Логика переключения — ТА ЖЕ, что была у пилюль: новый тип становится главным, прежний главный
 * понижается во второстепенные, а не исчезает; ВЫБРАННЫЙ единственный тип снять нельзя (отмена — кнопкой «Отмена»).
 * Исключение (исправлено в V2.2): если главный — СВОЙ тип, отмеченный стандартный не вытесняет его, а добавляется.
 * [primary] == null — «ничего не выбрано» (новый контакт, прототип V2.2): первый отмеченный тип становится главным.
 */
internal data class RelDraft(
    val primary: RelationshipType?,
    val custom: String,
    val secondary: List<RelationshipType>
) {
    fun toggled(rt: RelationshipType): RelDraft {
        val primaryIsCustom = custom.isNotBlank()
        val isCurrentPrimary = !primaryIsCustom && rt == primary
        val isCurrentSecondary = rt in secondary
        return when {
            isCurrentPrimary ->
                if (secondary.isNotEmpty()) copy(primary = secondary.first(), secondary = secondary.drop(1)) else this
            isCurrentSecondary -> copy(secondary = secondary - rt)
            // Свой тип главный: отмеченный стандартный ДОБАВЛЯЕТСЯ к нему (в мультивыборе нельзя молча терять
            // уже выбранное; хранение то же, что даёт withCustom — OTHER+custom и список secondary).
            primaryIsCustom -> copy(secondary = (secondary + rt).distinct())
            else -> copy(primary = rt, custom = "", secondary = (secondary + listOfNotNull(primary) - rt).distinct())
        }
    }

    /** Снять свой тип можно, только если есть другой — он становится главным (иначе без изменений). */
    fun customToggled(): RelDraft =
        if (custom.isNotBlank() && secondary.isNotEmpty()) copy(primary = secondary.first(), custom = "", secondary = secondary.drop(1)) else this

    /** Свой тип: совпадение без учёта регистра берёт УЖЕ существующее написание («Кум»/«кум» не плодятся). */
    fun withCustom(typed: String, existing: List<String>): RelDraft {
        val value = existing.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
        return copy(primary = RelationshipType.OTHER, custom = value, secondary = secondary - RelationshipType.OTHER)
    }
}

/**
 * ЕДИНАЯ шторка «Связь» (тип отношений): мультивыбор с ЧЕРНОВИКОМ + «+ Своё» с подсказками.
 * Используется и формой ContactEditScreen (применяет результат к состоянию формы), и шапкой карточки
 * контакта (применяет сразу в AppStateStore) — РАНЬШЕ у карточки была своя копия (выпадающий список +
 * отдельная шторка второстепенных), которая расходилась с формой (решение владельца 2026-10-08:
 * один механизм — один код). Выйти можно лишь кнопками «Отмена»/«Готово» (dismissOnDrag=false).
 */
@Composable
internal fun RelationshipPickerSheet(
    initial: RelDraft,
    onCancel: () -> Unit,
    onDone: (RelDraft) -> Unit,
) {
    val ctxLabel = LocalContext.current
    var draft by remember { mutableStateOf(initial) }
    var customFieldOpen by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }
    val relScroll = rememberScrollState()
    // Поле «своего» типа раскрывается под списком — прокручиваем к нему, чтобы оно не осталось за краем.
    LaunchedEffect(customFieldOpen) {
        if (customFieldOpen) { kotlinx.coroutines.delay(80); relScroll.animateScrollTo(relScroll.maxValue) }
    }
    val existingCustom = remember { AppStateStore.distinctCustomRelationshipTypes() }
    val customSuggestions = remember(customInput) {
        val q = customInput.trim()
        if (q.isBlank()) existingCustom else existingCustom.filter { it.contains(q, ignoreCase = true) }
    }
    FormChoiceSheet(
        title = stringResource(R.string.ce_relation_type),
        subtitle = stringResource(R.string.ce_relation_sheet_hint),
        onCancel = onCancel,
        listScroll = relScroll,
        // Свой тип, напечатанный, но не подтверждённый кнопкой «Добавить», не теряется при «Готово».
        onDone = { onDone(if (customInput.isNotBlank()) draft.withCustom(customInput.trim(), existingCustom) else draft) },
        hasChanges = { draft != initial || customInput.isNotBlank() }
    ) {
        RelationshipType.values().forEach { rt ->
            CheckRow(
                label = rt.label(ctxLabel),
                checked = (draft.custom.isBlank() && rt == draft.primary) || rt in draft.secondary,
                onClick = { draft = draft.toggled(rt) }
            )
        }
        if (draft.custom.isNotBlank()) {
            CheckRow(label = draft.custom, checked = true, onClick = { draft = draft.customToggled() })
        }
        // «+ Своё» — ввод прямо в шторке, с подсказками уже созданных своих типов
        // (тот же принцип «своё значение = полный CRUD», что в фильтрах и поиске).
        Text(
            stringResource(R.string.ce_relation_custom_add),
            color = AppleTheme.colors.brand, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().clickable { customFieldOpen = !customFieldOpen }.padding(vertical = 12.dp)
        )
        if (customFieldOpen) {
            OutlinedTextField(
                value = customInput, onValueChange = { customInput = it }, keyboardOptions = CapSentences,
                label = { Text(stringResource(R.string.ce_relation_custom_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            if (customSuggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    customSuggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = { customInput = suggestion },
                            label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                            shape = SocialShape.Full
                        )
                    }
                }
            }
            TextButton(
                enabled = customInput.isNotBlank(),
                onClick = {
                    draft = draft.withCustom(customInput.trim(), existingCustom)
                    customInput = ""; customFieldOpen = false
                }
            ) { Text(stringResource(R.string.common_add)) }
        }
    }
}

/**
 * Единственное место, где «связь не выбрана» (новый контакт) становится хранимым значением.
 * Contact.relationshipType — не-null (Room-колонка NOT NULL, ~19 потребителей), поэтому «пусто» без смены
 * модели не представимо. Берётся уже существующий в проекте маркер «не указано» — OTHER (его же пишет
 * массовый импорт, он же фолбэк safeEnum в Mappers), а НЕ «Знакомый»: ложная связь не записывается.
 * Выбранное значение (в т.ч. у существующего контакта) возвращается как есть.
 */
internal fun storedRelationshipType(chosen: RelationshipType?): RelationshipType = chosen ?: RelationshipType.OTHER

/** Подпись-капс над секцией (как в макете: «ТЕЛЕФОН», «EMAIL»). */
@Composable
fun AureliaCaption(text: String) {
    // Тонкий алиас канонической caps-подписи из общего слоя
    // (ui/theme/AureliaComponents.kt) — единая реализация на всё приложение.
    com.aistudio.socialsphere.crmlxb.ui.theme.AureliaCapsLabel(
        text, color = AppleTheme.colors.secondaryLabel
    )
}

/**
 * Поле без рамки внутри общей карточки (для парных имя/фамилия и прозвища).
 *
 * [fillInputWidth] — растянуть САМО поле ввода на всю ширину колонки (тап/фокус по всей строке,
 * а не только по узкому EditText под подписью). Включено только на форме контакта V2.2; у
 * остальных потребителей (форма события) поведение прежнее — поле по ширине содержимого.
 */
@Composable
fun BareFieldColumn(
    label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    placeholder: String? = null,
    fillInputWidth: Boolean = false,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppleTheme.colors.secondaryLabel)
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange, keyboardOptions = keyboardOptions,
            modifier = if (fillInputWidth) Modifier.fillMaxWidth() else Modifier,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppleTheme.colors.label, fontWeight = FontWeight.SemiBold),
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleTheme.colors.brand),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = AppleTheme.colors.tertiaryLabel)
                }
                inner()
            }
        )
    }
}

/**
 * Ряд-пилюли для короткого enum-выбора (тип отношений, важность, ритм и т.п.).
 * [goldFor] — набор лейблов, которые при выборе подсвечиваются золотом+звездой
 * (используется для верхнего уровня важности «Ключевой»), остальные — заливка брендом.
 */
@Composable
fun PillChoiceRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    goldFor: Set<String> = emptySet(),
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSel = opt == selected
            val isGold = isSel && opt in goldFor
            // Яркое золото важности (importanceKey) вместо бледного базового
            val goldTone = AppleTheme.colors.importanceKey
            val bg = when { isGold -> goldTone.copy(alpha = 0.14f); isSel -> AppleTheme.colors.brand; else -> AppleTheme.colors.card }
            val borderColor = when { isGold -> goldTone; isSel -> AppleTheme.colors.brand; else -> AppleTheme.colors.separator }
            val textColor = when { isGold -> goldTone; isSel -> Color.White; else -> AppleTheme.colors.label }
            Row(
                modifier = Modifier
                    .clip(SocialShape.Full)
                    .background(bg)
                    .border(1.dp, borderColor, SocialShape.Full)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isGold) Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = AureliaTheme.colors.gold)
                Text(opt, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, color = textColor)
            }
        }
    }
}

/** Кнопка прямого захвата (Скан/Контакты/Голос) наверху Create-формы (V2.2): компактная плитка-иконка + подпись. */
@Composable
fun CaptureActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(SocialShape.R14)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(SocialShape.R14).background(AppleTheme.colors.brand.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(22.dp), tint = AppleTheme.colors.brand) }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.colors.label)
    }
}

/**
 * Как [PillChoiceRow], но мультивыбор (V2.2 Relationship: единый контрол без
 * визуального различия «главный/второстепенный» — решение о том, что считается
 * главным типом, принимает вызывающий код через порядок применения onToggle).
 */
@Composable
fun MultiPillChoiceRow(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSel = opt in selected
            val bg = if (isSel) AppleTheme.colors.brand else AppleTheme.colors.card
            val borderColor = if (isSel) AppleTheme.colors.brand else AppleTheme.colors.separator
            val textColor = if (isSel) Color.White else AppleTheme.colors.label
            Row(
                modifier = Modifier
                    .clip(SocialShape.Full)
                    .background(bg)
                    .border(1.dp, borderColor, SocialShape.Full)
                    .clickable { onToggle(opt) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(opt, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, color = textColor)
            }
        }
    }
}

/**
 * Строка контактного метода (телефон/email/мессенджер) с иконкой-плиткой —
 * как в макете. Сохраняет прежнее поведение: инлайн-правка значения,
 * необязательный переключатель «основной» (звезда) и удаление.
 */
@Composable
fun ContactItemRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    isPrimary: Boolean? = null,
    onTogglePrimary: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R14)
            .background(AppleTheme.colors.card)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape.R10).background(iconBg),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(18.dp), tint = iconTint) }
        OutlinedTextField(
            value = value, onValueChange = onValueChange, keyboardOptions = keyboardOptions,
            label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent
            )
        )
        if (isPrimary == true && onTogglePrimary == null) {
            // Статичный индикатор «основной» без возможности переключить (как было раньше)
            Icon(Icons.Default.Star, contentDescription = stringResource(R.string.ce_make_primary),
                modifier = Modifier.size(16.dp).padding(end = 6.dp), tint = AppleTheme.colors.brand)
        } else if (isPrimary != null && onTogglePrimary != null) {
            IconButton(onClick = onTogglePrimary, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (isPrimary) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.ce_make_primary),
                    modifier = Modifier.size(16.dp),
                    tint = if (isPrimary) AppleTheme.colors.brand else AppleTheme.colors.secondaryLabel
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.common_delete), Modifier.size(16.dp), tint = AppleTheme.colors.red)
        }
    }
}

@Composable
fun DropdownField(label: String, selectedValue: String, options: List<String>, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selectedValue, onValueChange = {},
            label = { Text(label) }, modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            enabled = false, shape = SocialShape.Small,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = AppleTheme.colors.label,
                disabledBorderColor = AppleTheme.colors.tertiaryLabel,
                disabledLabelColor = AppleTheme.colors.secondaryLabel
            )
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
            }
        }
    }
}

internal val CapWords = androidx.compose.foundation.text.KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words
)
// ФИКС (2026-07-13, фидбэк владельца: при быстром вводе адреса — «Афины»
// прочиталось как «Афимы» при переходе фокуса в соседнее поле «Индекс»).
// В коде НЕТ логики, которая читает индекс как приоритет над городом —
// наиболее вероятная причина: автокоррекция IME на малознакомых именах
// собственных при смене фокуса между соседними полями (Compose Foundation
// KeyboardOptions.autoCorrect по умолчанию true, нигде явно не отключён).
// Для полей адреса (Улица/Город/Район/Страна — не для индекса, там не
// словарный ввод) отключаем автокоррекцию явно.
internal val CapWordsNoCorrect = androidx.compose.foundation.text.KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
    autoCorrect = false
)
internal val CapSentences = androidx.compose.foundation.text.KeyboardOptions(
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
)
// Цифровая раскладка телефона (+ * # и цифры) и почтовая (@, без автокапитализации)
// для соответствующих полей ввода — как в макете.
internal val PhoneKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
)
internal val EmailKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
)
internal val UrlKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
)
