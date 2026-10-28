package com.aistudio.socialsphere.crmlxb.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.model.*
import com.aistudio.socialsphere.crmlxb.data.local.*
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.MergeResolution
import com.aistudio.socialsphere.crmlxb.utils.searchNormalize
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * BATCH 4 (backup/restore durability): неизменяемый план восстановления —
 * уже распарсенные и ОТСОЕДИНЁННЫЕ от изменяемого состояния приложения данные,
 * которые применяются одной транзакцией в [AppStateStore.restoreBackup].
 * Отдельный тип (а не BackupData из ExportManager) — чтобы слой data не зависел
 * от слоя utils и чтобы частичное восстановление (только заметки / только
 * календарь) выражалось тем же типом, просто с одним заполненным полем.
 */
data class RestorePlan(
    val companies: List<Company> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val calendarItems: List<CalendarItem> = emptyList(),
    val contactRelations: List<ContactRelation> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    val groupMembers: List<ContactGroupMember> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val tagMembers: List<ContactTagMember> = emptyList(),
    val notes: List<Note> = emptyList(),
    val gifts: List<GiftIdea> = emptyList(),
    /** EXPLICIT RECOVERY PACK: история удалений, идущая ВМЕСТЕ с backup (деление между устройствами). */
    val tombstones: List<Tombstone> = emptyList()
)

object AppStateStore {
    val contacts        = mutableStateListOf<Contact>()
    val companies       = mutableStateListOf<Company>()
    val calendarItems   = mutableStateListOf<CalendarItem>()
    val notes           = mutableStateListOf<Note>()
    val gifts           = mutableStateListOf<GiftIdea>()
    /** EXPLICIT RECOVERY PACK (2026-09-22): история намеренных удалений — см. TombstoneEntityType. Не
     *  показывается в обычном UI, читается только при подготовке/применении restore. */
    val tombstones      = mutableStateListOf<Tombstone>()
    val companyRelations    = mutableStateListOf<ContactCompanyRelation>()
    val contactRelations    = mutableStateListOf<ContactRelation>()
    val addresses           = mutableStateListOf<Address>()
    val sizeInfos           = mutableStateListOf<SizeInfo>()
    val personalDetails     = mutableStateListOf<PersonalDetail>()
    // Группы контактов (как в телефонной книге) + членство
    val groups              = mutableStateListOf<ContactGroup>()
    val groupMembers        = mutableStateListOf<ContactGroupMember>()
    // Теги контактов (плоский управляемый список, зеркало групп) + членство
    val tags                = mutableStateListOf<Tag>()
    val tagMembers          = mutableStateListOf<ContactTagMember>()

    private var database: SocialsphereDatabase? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false

    // BATCH 2B (data integrity correction) — ОДИН глобальный write pipeline
    // вместо Batch 2's трёх per-aggregate-type `Dispatchers.IO.limitedParallelism(1)`.
    // Причина замены и устройство — см. DbWriteQueue.kt целиком (коротко:
    // limitedParallelism(1) освобождает свой "слот", когда команда
    // ПРИОСТАНАВЛИВАЕТСЯ — а `db.withTransaction` внутри именно приостанавливается,
    // переключаясь на собственный executor Room — так что вторая команда могла
    // начать выполняться до коммита первой, несмотря на parallelism=1; это
    // была scheduler-эвристика, не structural guarantee). ОДИН глобальный
    // писатель (не три отдельных) — потому что реальные операции пересекают
    // агрегаты (mergeContacts/convertContactToCompany трогают Calendar,
    // deleteContact/deleteCompany тоже) — три независимые очереди делают
    // cross-aggregate порядок между НИМИ неопределённым же самым способом.
    private val dbWriteQueue = DbWriteQueue(scope)

    /**
     * Единая точка для ЛЮБОЙ записи в БД. Возвращает [Deferred] — caller может
     * проигнорировать (текущая fire-and-forget семантика UI, INVARIANT D3)
     * либо `await()`, когда нужен подтверждённый commit (готовит API к Batch 3).
     * Атомарность многошаговых операций (delete-then-reinsert и т.п.) — ответственность
     * САМОГО `block`: он должен сам обернуть себя в `db.withTransaction {}`,
     * если логически является одной агрегатной операцией (см. вызовы ниже) —
     * НЕ оборачивается здесь автоматически, чтобы не заворачивать в транзакцию
     * тривиальные одностроковые записи ради формальности (Batch 2B §18).
     */
    private fun enqueueWrite(isRead: Boolean = false, block: suspend (db: SocialsphereDatabase) -> Unit): Deferred<Unit> {
        val deferred = dbWriteQueue.enqueue {
            // PERSISTENCE PACK #2: тестовые швы (ОДИН проверяемый writer, а не второй):
            // управляемая задержка коммита и инъекция сбоя. В проде оба null.
            writeGateForTests?.await()
            writeFaultOnceForTests?.let { fault -> writeFaultOnceForTests = null; throw fault }
            writeFaultForTests?.let { throw it }
            val db = db() ?: throw IllegalStateException("AppStateStore: database not initialized")
            block(db)
        }
        // ПОСЛЕ постановки в очередь (см. writeAcceptSeq): чтение resync, поставленное позже, гарантированно стоит за этой записью.
        if (!isRead) writeAcceptSeq.incrementAndGet()
        // Диагностика failure — здесь, не внутри DbWriteQueue (которая намеренно
        // не знает про android.util.Log, чтобы оставаться чистым JVM-тестируемым
        // классом, см. её комментарий). invokeOnCompletion ничего не глотает —
        // caller, который решит await() сам, всё равно увидит исключение.
        deferred.invokeOnCompletion { cause ->
            if (cause != null) {
                android.util.Log.e("AppStateStore", "DB write command failed", cause)
                // BACKUP PACK #3: сбой ЧТЕНИЯ (снимок для backup) — не сбой сохранения: без тоста и без пересборки
                if (!isRead) onWriteFailed()
            }
        }
        return deferred
    }

    // ── PERSISTENCE PACK #2: контракт «сохранено» ─────────────────────────────
    // Оптимистичное изменение памяти (мгновенный отклик) и ПОДТВЕРЖДЁННОЕ сохранение
    // (Room закоммитил) — разные вещи. Публичные писатели возвращают Deferred<Unit>,
    // который завершается только ПОСЛЕ реального коммита (либо исключением). Экран,
    // закрывающий критичное сохранение, обязан дождаться его через
    // [awaitDurableCommit]; ожидание отменяемо (уход с экрана), а сама запись живёт в
    // долгоживущем DbWriteQueue и от отмены ожидающего НЕ отменяется.
    //
    // Политика сбоя (минимальная, без retry-подсистемы): если коммит не удался, память
    // больше не может быть «единственной правдой» — после сбоя она пересобирается из
    // Room (то, что реально сохранено), а [writeFailureCount] растёт, чтобы корневой
    // экран показал явное сообщение. Экран, ждавший коммит, остаётся открытым с
    // введёнными данными — их можно сохранить повторно.

    /** Тестовый шов: пока не null — КАЖДАЯ writer-команда ждёт его перед выполнением
     *  (моделирует «Room ещё не закоммитил»). Только для тестов. */
    @Volatile internal var writeGateForTests: CompletableDeferred<Unit>? = null

    /** Тестовый шов: пока не null — каждая writer-команда падает с этим исключением
     *  (моделирует сбой БД/диска). Только для тестов. */
    @Volatile internal var writeFaultForTests: Throwable? = null

    /** Тестовый шов: падает ровно ОДНА (первая по порядку исполнения) writer-команда, затем шов сам обнуляется —
     *  моделирует «сбой A, а принятая позже B проходит» без гонки за момент снятия [writeFaultForTests]. */
    @Volatile internal var writeFaultOnceForTests: Throwable? = null

    /** Тестовый шов: вызывается на КАЖДОМ проходе перечитывания памяти из Room ([resyncMemoryFromRoom], [reloadFromDb]) ПОСЛЕ
     *  чтения снимка и ДО применения его к памяти (окно, в котором успевает быть принята новая запись). Только для тестов. */
    @Volatile internal var resyncAfterReadHookForTests: (suspend (PersistedState) -> Unit)? = null

    /** CALENDAR CONCURRENCY PROOF & FIX (2026-09-23): вызывается ВНУТРИ [updateCalendarItem]/[markCalendarItemCompleted]/
     *  [postponeCalendarItemOccurrence] СРАЗУ после чтения канонического calendarItems[idx], ДО вычисления замены и записи
     *  обратно в память — модель «конкурентная операция читает то же каноническое состояние, пока эта ещё не записала своё».
     *  Обычный (не suspend) блокирующий колбэк — намеренно НЕ suspend, чтобы его можно было безопасно вызывать из тела,
     *  защищённого обычным JVM-lock'ом (synchronized), где suspend-приостановка была бы небезопасна. В проде всегда null. */
    @Volatile internal var calendarRaceHookForTests: (() -> Unit)? = null

    /**
     * CALENDAR CONCURRENCY PROOF & FIX (2026-09-23): единый lock для read-modify-write трёх
     * calendar-писателей ([updateCalendarItem]/[markCalendarItemCompleted]/[postponeCalendarItemOccurrence]),
     * мутирующих один и тот же элемент [calendarItems]. Каждый из них читает calendarItems[idx],
     * вычисляет замену и пишет её обратно СИНХРОННО — но раньше НИКАК не координировался с двумя
     * другими: DbWriteQueue сериализует только Room-коммит (внутри enqueueWrite), а не эту
     * последовательность ДО enqueue. Конкурентный вызов двух из них для ОДНОГО и того же события
     * (напр. «Готово» из уведомления + «Перенести» с экрана события, или редактор + фоновая
     * бизнес-операция) мог читать одно каноническое состояние ДО того, как другая записала своё —
     * независимое изменение той, что закоммитится РАНЬШЕ, молча стиралось полной заменой строки той,
     * что закоммитится ПОЗЖЕ (Room `insertCalendarItem` — REPLACE, не partial update).
     *
     * Обычный JVM-monitor (`synchronized`), не coroutine Mutex: вся защищённая секция (чтение
     * canonical -> вычисление замены -> запись в память -> enqueueWrite) НЕ содержит ни одной
     * suspend-точки — enqueueWrite сам не suspend, регистрирует команду через неблокирующий
     * `Channel.trySend` и возвращает управление немедленно; сам Room-коммит выполняется позже,
     * АСИНХРОННО, внутри собственной корутины-потребителя DbWriteQueue, вне этого lock'а. Поэтому
     * удержание monitor'а никогда не пересекает suspend/смену диспетчера — safe, без риска
     * IllegalMonitorStateException и без deadlock с самим DbWriteQueue.
     */
    private val calendarMutationLock = Any()

    /** Растёт при каждом неудавшемся коммите; корневой экран показывает сообщение по изменению. */
    var writeFailureCount by mutableStateOf(0)
        private set

    private val resyncScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Счётчик ПРИНЯТЫХ записей (растёт в [enqueueWrite] ПОСЛЕ постановки в очередь). Оптимистичное изменение памяти
     *  делает вызывающий ДО постановки, поэтому «счётчик не изменился» = «с момента X ни одна запись не принята и
     *  памяти нечего терять». Опора [resyncMemoryFromRoom]. */
    private val writeAcceptSeq = java.util.concurrent.atomic.AtomicLong(0)

    private fun onWriteFailed() {
        // ОДНА серия восстановления на серию сбоев подряд. Новый сбой её не дублирует: запись, принятая после постановки
        // чтения, заставляет серию перечитать Room ещё раз (см. resyncMemoryFromRoom), так что его откат не теряется.
        if (!resyncScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                resyncMemoryFromRoom(onApplied = { resyncScheduled.set(false) })
            } catch (t: Throwable) {
                resyncScheduled.set(false)
                android.util.Log.e("AppStateStore", "resync after failed write failed", t)
            } finally {
                withContext(Dispatchers.Main) { writeFailureCount++ }
            }
        }
    }

    /**
     * DATA SAFETY MICRO-PACK: восстановление памяти из Room, УПОРЯДОЧЕННОЕ относительно единого писателя.
     *
     * Раньше после сбоя: барьер -> `reloadFromDb()` ВНЕ очереди -> безусловная замена ВСЕЙ памяти. Запись B, принятая после
     * барьера (память уже оптимистично изменена, в очередь встала, Room ещё её не видит), терялась в памяти: снимок читался
     * без неё и перезаписывал память. Дальше «обычная» правка другого поля брала канонический контакт ИЗ ПАМЯТИ
     * (updateContact) и записывала в Room старое значение — потеря уже закоммиченных данных.
     *
     * Теперь: (1) чтение снимка — команда единого писателя (FIFO): выполняется после ВСЕХ ранее принятых записей и не
     * пересекается ни с одной; (2) снимок применяется к памяти на Main только если с момента постановки чтения НЕ принята ни
     * одна запись ([writeAcceptSeq]) — иначе память содержит оптимистичное изменение, которого в снимке ещё нет, снимок
     * отбрасывается и чтение ставится заново (уже ЗА принятой записью). Условие и замена памяти выполняются одним Main-блоком,
     * поэтому запись с Main-потока не может вклиниться между проверкой и заменой. Сходимость: как только окно без новых
     * принятых записей больше цикла «очередь -> Main», память == Room по всем принятым записям. Второго писателя и общего
     * mutex нет. Читающая команда ставится в очередь напрямую (как прежний барьер): она не подчиняется тестовым швам
     * gate/fault и не считается «принятой записью».
     *
     * [onApplied] выполняется в том же Main-блоке, что и замена памяти (снятие флага серии сбоев — атомарно с ней).
     * Не звать изнутри команды писателя: ожидание своей же очереди — самоблокировка (§41).
     */
    private suspend fun resyncMemoryFromRoom(onApplied: () -> Unit = {}) {
        while (true) {
            val acceptedBefore = writeAcceptSeq.get()
            val box = CompletableDeferred<PersistedState>()
            dbWriteQueue.enqueue {
                val db = db() ?: throw IllegalStateException("AppStateStore: database not initialized")
                box.complete(db.withTransaction { readRoomSnapshot(db).toPersistedState() })
            }.await()
            val state = box.await()
            resyncAfterReadHookForTests?.invoke(state)
            val applied = withContext(Dispatchers.Main) {
                if (writeAcceptSeq.get() != acceptedBefore) false
                else { applyPersistedState(state); onApplied(); true }
            }
            if (applied) return
        }
    }

    /** Барьер: завершается, когда закоммичены ВСЕ ранее поставленные записи (очередь строго
     *  FIFO). Не заменяет проверку самих записей — только добавляет к ним тех, у кого нет
     *  собственного Deferred. */
    fun writeBarrier(): Deferred<Unit> = enqueueWrite { }

    /**
     * Дождаться подтверждённого коммита ВСЕХ переданных записей. true — все записи
     * лежат в Room; false — хотя бы одна упала (память уже пересобрана из Room,
     * см. политику выше), экран должен остаться открытым. CancellationException
     * ожидающей корутины пробрасывается — сама запись при этом продолжается.
     */
    suspend fun awaitDurableCommit(vararg writes: Deferred<*>): Boolean {
        var ok = true
        for (w in writes) {
            try { w.await() }
            catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
            catch (_: Throwable) { ok = false }
        }
        return ok
    }

    // true пока идёт первичная загрузка из БД. Экраны показывают спиннер вместо
    // «ничего не найдено», иначе на холодном старте пустой экран выглядел как
    // потеря данных.
    var isLoading by mutableStateOf(true)
        private set

    @Synchronized
    fun initialize(context: Context, db: SocialsphereDatabase) {
        if (isInitialized) return
        database = db
        appContext = context.applicationContext
        isInitialized = true
        scope.launch { loadInitialData() }
    }

    /** Откуда брать БД, когда хранилище поднимает не MainActivity, а
     *  BroadcastReceiver в холодном процессе. В проде — тот же синглтон, что
     *  у MainActivity; тесты подменяют на in-memory БД. */
    internal var databaseProvider: (Context) -> SocialsphereDatabase =
        { SocialsphereDatabase.getDatabase(it) }

    /** БД для прямого чтения из receiver'а: уже подключённая, иначе та же, что
     *  подключится при инициализации. */
    internal fun databaseFor(context: Context): SocialsphereDatabase =
        database ?: databaseProvider(context.applicationContext)

    // Завершается в finally loadInitialData() — сигнал «память совпадает с Room».
    // CompletableDeferred, а не опрос isLoading: ожидание без delay(). ВАЖНО:
    // сама загрузка (reloadFromDb) переключается на Dispatchers.Main, поэтому
    // ensureLoaded() нельзя ждать, БЛОКИРУЯ главный поток (runBlocking на Main =
    // взаимная блокировка, §57). Receiver ждёт на Dispatchers.IO — главный свободен.
    @Volatile private var initialLoad = CompletableDeferred<Unit>()

    /**
     * INTERACTION SEMANTICS (кнопки уведомлений): гарантирует, что хранилище
     * подключено к БД И первичная загрузка завершена, прежде чем выполнять
     * бизнес-действие из BroadcastReceiver.
     *
     * Receiver может проснуться в холодном процессе, где MainActivity не
     * запускалась: памяти нет, а loadInitialData() читает Room ВНЕ очереди
     * записи. Если бы действие записало раньше, чем загрузка закончилась,
     * устаревший снимок загрузки перезаписал бы память (событие снова ACTIVE),
     * и следующая правка из интерфейса вернула бы это в Room. Поэтому сначала
     * ждём загрузку, затем выполняем ТОТ ЖЕ публичный путь, что и экран, через
     * единый DbWriteQueue.
     */
    suspend fun ensureLoaded(context: Context) {
        synchronized(this) {
            if (!isInitialized) initialize(context, databaseProvider(context.applicationContext))
        }
        initialLoad.await()
    }

    /**
     * Только для тестов — сбрасывает `isInitialized`, чтобы следующий
     * `initialize()` реально переподключился к новой (тестовой) БД.
     *
     * ФИКС (2026-08-12): `isInitialized`-гвард в initialize() верно защищает
     * прод-сценарий (повторный onCreate() Activity не должен пере-грузить всё
     * состояние заново) — но AppStateStore это `object`-синглтон, живущий на
     * весь JVM-процесс. В Robolectric-тестах один и тот же JVM/classloader
     * гоняет НЕСКОЛЬКО тестовых методов подряд — второй `@Test` в том же
     * классе вызывает `initialize(ctx, db2)`, гвард молча возвращает управление
     * без переподключения, и `database` НАВСЕГДА остаётся указывать на `db`
     * ПЕРВОГО теста — уже закрытую его `@After` (`db.close()`) к моменту
     * второго теста. Все фоновые `scope.launch { db()?.let {...} }` второго
     * теста молча падают на закрытой БД, и тест ловит это как ложный таймаут
     * опроса — выглядит как регресс кода, хотя на самом деле баг в тестовой
     * обвязке. Вызывать из `@Before`/`@After` тестов, НЕ из прод-кода.
     */
    internal fun resetForTests() {
        isInitialized = false
        database = null
        resyncScheduled.set(false)
        initialLoad = CompletableDeferred()
        // BATCH 2D: isLoading НЕ сбрасывался — раз выставленный в false первым
        // же тестом, оставался false навсегда для всех следующих тестов этого
        // же JVM-процесса. Раньше это было не важно (seed/migrate/repair были
        // fire-and-forget, loadInitialData() успевал "закончиться" почти
        // мгновенно) — теперь, когда все три await() свой enqueued commit
        // (D-001/D-002/D-003), окно, в которое фоновый loadInitialData()
        // текущего теста ещё выполняется, стало заметно шире. Тестам нужен
        // надёжный сигнал "фоновый loadInitialData() ЭТОГО теста реально
        // закончился" — сброс здесь в true даёт его (см. AppStateStoreDataIntegrityTest.setup()).
        isLoading = true
    }

    // Безопасный доступ к БД — не крашит если не инициализирована
    private fun db(): SocialsphereDatabase? {
        return database ?: run {
            android.util.Log.e("AppStateStore", "Database not initialized")
            null
        }
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    fun generateId(): String = UUID.randomUUID().toString()

    // ──────────────────────────────────────────────────────────
    //  INITIAL LOAD
    // ──────────────────────────────────────────────────────────
    private suspend fun loadInitialData() {
        // ФИКС (2026-07-23, решение владельца): демоданные больше НИКОГДА не
        // заводятся при установке — раньше здесь заполнялась вся демо-БД
        // (DemoDataProvider), если контактов ещё не было. Новая установка
        // теперь стартует с пустого состояния навсегда.
        try {
            db() ?: return
            reloadFromDb()
            // Сидирование дефолтных тегов — ПОСЛЕ реальной загрузки tags из БД
            // выше (reloadFromDb уже наполнил tags), иначе tags.isEmpty()
            // проверился бы раньше, чем список реально загружен, и сидирование
            // срабатывало бы на каждом холодном старте заново.
            appContext?.let { seedDefaultTagsIfNeeded(it) }
            // Миграция легаси Contact.tags → управляемая система (аудит
            // 2026-08-18) — тоже ПОСЛЕ reloadFromDb, читает уже загруженные
            // contacts/tags.
            migrateLegacyContactTagsIfNeeded()
            // ФИКС (2026-08-12, §52 KNOWLEDGE.md): самовосстановление связи
            // дата-событие→контакт по имени из заголовка — ПОСЛЕ reloadFromDb,
            // т.к. работает по уже загруженным calendarItems/contacts.
            repairMissingContactLinksFromTitles()
            // Уже сохранённые повторы номеров/email приложение САМО не удаляет (решение владельца
            // 2026-10-10: «сам тупо удалять не надо»): их видно в форме контакта и в «Поиске дублей»,
            // удаляет пользователь. Дубли не СОЗДАЮТСЯ — см. ContactChannelDedup на путях ввода.
        } finally {
            // Данные уже в памяти (reloadFromDb) — ожидающие в ensureLoaded() могут продолжать.
            initialLoad.complete(Unit)
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    /**
     * Контакты, у которых один и тот же номер/email записан несколько раз (для экрана «Поиск дублей»).
     * Только ЧТЕНИЕ: ничего не меняет и не удаляет — решает и удаляет пользователь.
     */
    fun contactsWithRepeatedChannels(): List<Contact> =
        contacts.filter { ContactChannelDedup.hasRepeats(it) }

    private val DATE_EVENT_TYPES_FOR_LINK_REPAIR = setOf(
        CalendarItemType.BIRTHDAY, CalendarItemType.ANNIVERSARY,
        CalendarItemType.NAMEDAY, CalendarItemType.IMPORTANT_DATE
    )

    /**
     * Самовосстановление связи «дата-событие → контакт» по имени, запечённому
     * в заголовке (владелец, 2026-08-12: «оно в календаре всё равно пишет,
     * чей это день рождения — почему оно обратно не присоединится к
     * контакту?»). До этого фикса имя в заголовке («День рождения: Иван
     * Петров») было мёртвым текстом — нигде не читалось обратно, даже когда
     * реальная связь `CalendarItemLink` на этот контакт отсутствовала (старые
     * бэкапы до появления links, события без выбора контакта при ручном
     * создании и т.п.) — контакт при этом никогда не показывал такое событие
     * в «Ближайших» (см. §52).
     *
     * Работает ТОЛЬКО при ОДНОЗНАЧНОМ совпадении полного имени: 0 или ≥2
     * кандидатов — ничего не делает, не гадает (привязать не того человека
     * хуже, чем оставить как есть — это про чужие личные данные). Идемпотентна:
     * повторный вызов на уже привязанных событиях находит 0 кандидатов.
     */
    internal suspend fun repairMissingContactLinksFromTitles() {
        val ctx = appContext ?: return
        if (db() == null) return
        // Кандидат — дата, ни одна связь которой не ведёт на СУЩЕСТВУЮЩИЙ контакт: связи нет вовсе либо она
        // указывает на удалённый/пересозданный контакт (на карточке такая дата не видна, в Календаре — видна).
        val knownContactIds = contacts.mapTo(HashSet()) { it.id }
        val candidates = calendarItems.filter { item ->
            item.type in DATE_EVENT_TYPES_FOR_LINK_REPAIR &&
                item.links.none { it.targetType == CalendarTargetType.CONTACT && it.targetId in knownContactIds }
        }
        if (candidates.isEmpty()) return
        val contactsNow = contacts.toList()
        val newLinksByItemId = mutableMapOf<String, CalendarItemLink>()
        candidates.forEach { item ->
            val name = com.aistudio.socialsphere.crmlxb.utils.extractNameFromDateTitle(item.title, item.type, ctx)
            val match = DateLinkMatch.uniqueContactFor(item.title, name, contactsNow) ?: return@forEach
            newLinksByItemId[item.id] = CalendarItemLink(
                id = generateId(), calendarItemId = item.id,
                targetType = CalendarTargetType.CONTACT, targetId = match.id
            )
        }
        if (newLinksByItemId.isEmpty()) return
        // BATCH 2E (2E-003): DB commit СНАЧАЛА, in-memory calendarItems —
        // ТОЛЬКО после подтверждённого await(). Раньше in-memory обновлялся
        // (withContext(Main) ниже) ДО await() — при сбое DB-транзакции
        // UI/память показали бы событие "починенным", а Room осталась бы
        // нетронутой (memory/DB divergence после сбоя фонового maintenance —
        // недопустимо для background-задачи без явного действия пользователя).
        // Один batch-insert одним DAO-вызовом — withTransaction не нужен.
        enqueueWrite { db -> db.calendarDao().insertCalendarItemLinks(newLinksByItemId.values.map { it.toEntity() }) }.await()
        withContext(Dispatchers.Main) {
            newLinksByItemId.forEach { (itemId, link) ->
                val idx = calendarItems.indexOfFirst { it.id == itemId }
                if (idx >= 0) calendarItems[idx] = calendarItems[idx].copy(links = calendarItems[idx].links + link)
            }
        }
    }

    /**
     * Память = то, что реально закоммичено в Room. BACKUP PACK #3: читается ЕДИНЫМ согласованным
     * снимком (одна read-транзакция, см. [readRoomSnapshot]) тем же построителем, что и backup, —
     * плоские списки и вложенные проекции собираются по persisted id из одного и того же состояния.
     */
    suspend fun reloadFromDb() {
        val db = db() ?: return
        val state = db.withTransaction { readRoomSnapshot(db).toPersistedState() }
        resyncAfterReadHookForTests?.invoke(state)
        withContext(Dispatchers.Main) { applyPersistedState(state) }
    }

    /** Заменяет ВСЮ память снимком Room. Только на Main. Безусловна: вызывающий отвечает за то, что снимок не старее принятых
     *  записей — старт и тесты ([reloadFromDb]) либо проверка [writeAcceptSeq] в [resyncMemoryFromRoom]. */
    private fun applyPersistedState(state: PersistedState) {
        groups.clear();          groups.addAll(state.groups)
        groupMembers.clear();    groupMembers.addAll(state.groupMembers)
        tombstones.clear();      tombstones.addAll(state.tombstones)
        tags.clear();            tags.addAll(state.tags)
        tagMembers.clear();      tagMembers.addAll(state.tagMembers)
        notes.clear();           notes.addAll(state.notes)
        gifts.clear();           gifts.addAll(state.gifts)
        companyRelations.clear();companyRelations.addAll(state.companyRelations)
        contactRelations.clear();contactRelations.addAll(state.contactRelations)
        addresses.clear();       addresses.addAll(state.addresses)
        sizeInfos.clear();       sizeInfos.addAll(state.sizeInfos)
        personalDetails.clear(); personalDetails.addAll(state.personalDetails)
        companies.clear();       companies.addAll(state.companies)
        calendarItems.clear();   calendarItems.addAll(state.calendarItems)
        contacts.clear();        contacts.addAll(state.contacts)
    }

    /**
     * BACKUP PACK #3: согласованный снимок persisted-состояния для полного backup.
     *
     * Порядок: все ранее ПРИНЯТЫЕ записи -> барьер -> одна read-транзакция -> сериализация вызывающим.
     * Чтение — команда единого писателя (DbWriteQueue строго FIFO): она стартует только после того, как
     * закоммичены все записи, поставленные раньше, и во время чтения никакая другая запись не выполняется;
     * внутри — `db.withTransaction`, чтобы снимок был логически согласованным даже если чтение однажды
     * вынесут из очереди. Не читает память AppStateStore вообще (она оптимистична и может отставать).
     * Сбой чтения не считается сбоем сохранения (нет тоста/пересборки памяти).
     */
    suspend fun readPersistedState(): PersistedState {
        val box = CompletableDeferred<PersistedState>()
        enqueueWrite(isRead = true) { db ->
            box.complete(db.withTransaction { readRoomSnapshot(db).toPersistedState() })
        }.await()
        return box.await()
    }

    /** Версия схемы открытой Room-БД (для проверки совместимости backup ДО любой записи). */
    internal fun roomSchemaVersion(): Int? = try { database?.openHelper?.readableDatabase?.version } catch (_: Exception) { null }


    // ──────────────────────────────────────────────────────────
    //  DB WRITE HELPERS
    // ──────────────────────────────────────────────────────────
    private suspend fun addContactDb(contact: Contact) {
        val db = db() ?: return
        db.contactDao().insertContact(contact.toEntity())
        if (contact.phones.isNotEmpty())          db.contactDao().insertPhones(contact.phones.map { it.toEntity() })
        if (contact.emails.isNotEmpty())          db.contactDao().insertEmails(contact.emails.map { it.toEntity() })
        if (contact.messengers.isNotEmpty())      db.contactDao().insertMessengers(contact.messengers.map { it.toEntity() })
        if (contact.companyRelations.isNotEmpty())db.contactDao().insertCompanyRelations(contact.companyRelations.map { it.toEntity() })
        if (contact.addresses.isNotEmpty())       db.addressDao().insertAddresses(contact.addresses.map { it.toEntity() })
        if (contact.personalDetails.isNotEmpty()) db.contactDao().insertPersonalDetails(contact.personalDetails.map { it.toEntity() })
        contact.sizeInfo?.let { db.contactDao().insertSizeInfo(it.toEntity()) }
    }

    private suspend fun addCompanyDb(company: Company) {
        val db = db() ?: return
        db.companyDao().insertCompany(company.toEntity())
        if (company.addresses.isNotEmpty()) db.addressDao().insertAddresses(company.addresses.map { it.toEntity() })
        if (company.phones.isNotEmpty())    db.contactDao().insertPhones(company.phones.map { it.toCompanyEntity(company.id) })
        if (company.emails.isNotEmpty())    db.contactDao().insertEmails(company.emails.map { it.toCompanyEntity(company.id) })
    }

    private suspend fun addCalendarItemDb(item: CalendarItem) {
        val db = db() ?: return
        db.calendarDao().insertCalendarItem(item.toEntity())
        if (item.links.isNotEmpty())     db.calendarDao().insertCalendarItemLinks(item.links.map { it.copy(calendarItemId = item.id).toEntity() })
        if (item.reminders.isNotEmpty()) db.calendarDao().insertReminderRules(item.reminders.map { it.toEntity() })
    }

    // ──────────────────────────────────────────────────────────
    //  CONTACTS CRUD
    // ──────────────────────────────────────────────────────────
    fun getContactById(id: String): Contact? = contacts.find { it.id == id }
    fun getContact(id: String): Contact?     = getContactById(id)

    fun addContact(contact: Contact): Deferred<Unit> {
        val c = contact.copy(createdAt = nowIso(), updatedAt = nowIso())
        contacts.add(c)
        // Карта/карточка компании читают ГЛОБАЛЬНЫЕ addresses/companyRelations —
        // синхронизируем сразу, иначе связь контакт↔компания и адреса на карте
        // не видны до перезапуска приложения (аналогично updateContact).
        addresses.addAll(c.addresses)
        companyRelations.addAll(c.companyRelations)
        // ФИКС (аудит 2026-08-12): та же синхронизация нужна и для gifts/
        // personalDetails — раньше синхронизировались только через отдельные
        // addGift()/addNote(), но НЕ когда контакт создаётся СРАЗУ с уже
        // заполненными списками (как делает импорт через ContactNoteCodec) —
        // GiftsTab читает ГЛОБАЛЬНЫЙ AppStateStore.gifts, значит был бы виден
        // в БД, но не на экране до перезапуска. Тот же паттерн, что уже
        // применён в restoreContact() для notes/gifts.
        gifts.addAll(c.gifts)
        personalDetails.addAll(c.personalDetails)
        // BATCH 2 (data integrity): addContactDb() само по себе делает несколько
        // раздельных insert'ов (contact/phones/emails/messengers/companyRelations/
        // addresses/personalDetails/sizeInfo) — та же partial-write щель, что
        // была у updateContact/deleteContact, только на СОЗДАНИИ, а не изменении.
        // Симметричный fix, не только для update/delete (см. §56 SOCIALSPHERE_KNOWLEDGE.md).
        return enqueueWrite { db -> db.withTransaction { addContactDb(c) } }
    }

    /**
     * PERSISTENCE PACK #2 (2026-09-21): единственный публичный способ сохранить правку контакта.
     *
     * Раньше `updateContact(contact)` был одновременно «представлением состояния» и командой
     * «замени ВСЁ»: любой устаревший снимок (экран редактора открыт минуту назад, «обновить из
     * телефона» держит копию) перезаписывал независимо изменённые телефоны/email/мессенджеры/
     * детали/адреса, потому что отличить «пользователь это правил» от «это просто лежало в
     * снимке» было нечем.
     *
     * Теперь вызывающий передаёт [base] — снимок, ОТ КОТОРОГО он начал правку, и [edited] —
     * то, что хочет сохранить. Записывается только РАЗНИЦА edited−base, наложенная на
     * каноническое состояние из хранилища (3-way merge):
     *  • скаляр, не менявшийся относительно base, остаётся каноническим (свежим);
     *  • списки с id (телефоны, email, мессенджеры, личные детали, адреса, связи с компаниями)
     *    сливаются по id: удалено = было в base и нет в edited; добавлено/изменено = edited
     *    отличается от base; всё остальное берётся каноническим;
     *  • поле, забытое в слиянии, деградирует к прежнему «побеждает edited» (безопасный отказ).
     * Если каноническое состояние совпадает с base (нет вмешательства) — результат равен edited,
     * поведение прежнее. Дальше применяются reconcile-правила Pack #1 (gifts/sizeInfo/notes/
     * координаты/lastContactDate/связи с компаниями).
     */
    fun updateContact(base: Contact, edited: Contact): Deferred<Unit> {
        val current = contacts.find { it.id == edited.id } ?: return CompletableDeferred(Unit)
        return writeContact(mergeContactEdit(current, base, edited))
    }

    /** Слияние правки в каноническое состояние — см. [updateContact]. Чистая функция. */
    internal fun mergeContactEdit(canonical: Contact, base: Contact, edited: Contact): Contact {
        // Скаляр: не менялся относительно base -> каноническое (свежее) значение.
        fun <T> pick(b: T, e: T, c: T): T = if (e == b) c else e
        return edited.copy(
            firstName = pick(base.firstName, edited.firstName, canonical.firstName),
            lastName = pick(base.lastName, edited.lastName, canonical.lastName),
            middleName = pick(base.middleName, edited.middleName, canonical.middleName),
            nickname = pick(base.nickname, edited.nickname, canonical.nickname),
            namePrefix = pick(base.namePrefix, edited.namePrefix, canonical.namePrefix),
            nameSuffix = pick(base.nameSuffix, edited.nameSuffix, canonical.nameSuffix),
            phoneticFirstName = pick(base.phoneticFirstName, edited.phoneticFirstName, canonical.phoneticFirstName),
            phoneticMiddleName = pick(base.phoneticMiddleName, edited.phoneticMiddleName, canonical.phoneticMiddleName),
            phoneticLastName = pick(base.phoneticLastName, edited.phoneticLastName, canonical.phoneticLastName),
            photoUri = pick(base.photoUri, edited.photoUri, canonical.photoUri),
            relationshipType = pick(base.relationshipType, edited.relationshipType, canonical.relationshipType),
            customRelationshipType = pick(base.customRelationshipType, edited.customRelationshipType, canonical.customRelationshipType),
            secondaryRelationshipTypes = pick(base.secondaryRelationshipTypes, edited.secondaryRelationshipTypes, canonical.secondaryRelationshipTypes),
            connectionLevel = pick(base.connectionLevel, edited.connectionLevel, canonical.connectionLevel),
            importanceLevel = pick(base.importanceLevel, edited.importanceLevel, canonical.importanceLevel),
            socialRole = pick(base.socialRole, edited.socialRole, canonical.socialRole),
            communicationRhythm = pick(base.communicationRhythm, edited.communicationRhythm, canonical.communicationRhythm),
            customRhythmDays = pick(base.customRhythmDays, edited.customRhythmDays, canonical.customRhythmDays),
            contactStatus = pick(base.contactStatus, edited.contactStatus, canonical.contactStatus),
            lastContactDate = pick(base.lastContactDate, edited.lastContactDate, canonical.lastContactDate),
            nextStep = pick(base.nextStep, edited.nextStep, canonical.nextStep),
            familyNote = pick(base.familyNote, edited.familyNote, canonical.familyNote),
            profession = pick(base.profession, edited.profession, canonical.profession),
            tags = pick(base.tags, edited.tags, canonical.tags),
            canHelpWith = pick(base.canHelpWith, edited.canHelpWith, canonical.canHelpWith),
            iCanHelpWith = pick(base.iCanHelpWith, edited.iCanHelpWith, canonical.iCanHelpWith),
            talkingPoints = pick(base.talkingPoints, edited.talkingPoints, canonical.talkingPoints),
            meetContext = pick(base.meetContext, edited.meetContext, canonical.meetContext),
            meetDate = pick(base.meetDate, edited.meetDate, canonical.meetDate),
            deviceContactId = pick(base.deviceContactId, edited.deviceContactId, canonical.deviceContactId),
            deviceLookupKey = pick(base.deviceLookupKey, edited.deviceLookupKey, canonical.deviceLookupKey),
            createdAt = pick(base.createdAt, edited.createdAt, canonical.createdAt),
            phones = mergeById(canonical.phones, base.phones, edited.phones) { it.id },
            emails = mergeById(canonical.emails, base.emails, edited.emails) { it.id },
            messengers = mergeById(canonical.messengers, base.messengers, edited.messengers) { it.id },
            personalDetails = mergeById(canonical.personalDetails, base.personalDetails, edited.personalDetails) { it.id },
            addresses = mergeById(canonical.addresses, base.addresses, edited.addresses) { it.id },
            companyRelations = mergeById(canonical.companyRelations, base.companyRelations, edited.companyRelations) { it.id },
        )
    }

    /** 3-way слияние списка с id (см. [updateContact]). Без вмешательства (canonical == base)
     *  результат — ровно edited, в его порядке. */
    private fun <T> mergeById(canonical: List<T>, base: List<T>, edited: List<T>, id: (T) -> String): List<T> {
        if (edited == base) return canonical
        if (canonical == base) return edited
        val baseById = base.associateBy(id)
        val editedById = edited.associateBy(id)
        val removed = baseById.keys - editedById.keys
        val canonicalIds = canonical.mapTo(HashSet(), id)
        val merged = canonical.filter { id(it) !in removed }.map { c ->
            val e = editedById[id(c)]
            // правка пользователя (edited != base) побеждает; иначе — каноническое значение
            if (e != null && e != baseById[id(c)]) e else c
        }
        val added = edited.filter { e -> id(e) !in canonicalIds && e != baseById[id(e)] }
        return merged + added
    }

    private fun writeContact(contact: Contact): Deferred<Unit> {
        // P0.1 (2026-09-21): GiftIdea — САМОСТОЯТЕЛЬНЫЙ агрегат (таблица gift_ideas,
        // CRUD addGift/updateGift/deleteGift/restoreGift). Каноничный источник —
        // глобальный AppStateStore.gifts (Room -> reloadFromDb -> gifts). Contact.gifts
        // — только производная проекция и у переданного объекта может быть
        // УСТАРЕВШЕЙ (снимок Compose, слияние дублей, restoreContact). Поэтому
        // обновление Contact не создаёт и не удаляет подарки: вложенный список
        // ПЕРЕВЫЧИСЛЯЕТСЯ из канонического. Раньше здесь стояло
        // gifts.removeAll{contactId}; gifts.addAll(c.gifts) — устаревший снимок
        // выкидывал подарки из памяти (GiftsTab, JSON-бэкап), а устаревший снимок
        // с уже удалённым подарком воскрешал его.
        // P1 (2026-09-21): SizeInfo — тоже САМОСТОЯТЕЛЬНЫЙ агрегат (таблица size_infos,
        // независимый писатель setSizeInfo, каноничный список AppStateStore.sizeInfos,
        // который читают диалог размеров и вкладка «Подарки»). Тот же принцип, что
        // у gifts: вложенный Contact.sizeInfo — производная проекция, у переданного
        // объекта может быть УСТАРЕВШЕЙ, поэтому перевычисляется из канонического
        // списка, а updateContact НЕ пишет в size_infos вовсе (см. транзакцию ниже).
        // Раньше здесь стояло deleteSizeInfoForContact + повторная вставка из
        // вложенного поля: пустой снимок УДАЛЯЛ строку из Room (память ещё
        // показывала размеры — до рестарта), снимок со старыми значениями
        // ОТКАТЫВАЛ строку к ним.
        // OWNERSHIP PACK #1 (2026-09-21): то же правило для ВСЕХ данных контакта, у
        // которых есть собственный независимый писатель. Каноничное текущее
        // состояние побеждает устаревший вложенный снимок:
        //  • notes — вложенная проекция перевычисляется (Room notes здесь не трогаются);
        //  • координаты адресов (setAddressCoords) — переносятся в тот же адрес снимка,
        //    если текст адреса не менялся (withCanonicalCoords);
        //  • lastContactDate (markContactedNow/завершение события пишут его через DAO) —
        //    берётся более поздняя из двух дат, снимок не откатывает её;
        //  • связи с компанией (mergeCompanies/deleteCompany меняют их независимо) —
        //    reconcileCompanyRelations не воскрешает связь с удалённой компанией и не
        //    откатывает перенос на компанию-«выжившую».
        // Намеренная правка телефонов/email/мессенджеров/деталей/текста адресов/состава
        // связей по-прежнему идёт списком из аргумента — у них нет независимого
        // писателя, и отличить «правку» от «устаревшего снимка» без версии снимка
        // нельзя (см. отчёт пакета).
        val idx = contacts.indexOfFirst { it.id == contact.id }
        val current = contacts.getOrNull(idx)
        val c = contact.copy(
            gifts = gifts.filter { it.contactId == contact.id },
            sizeInfo = sizeInfos.find { it.contactId == contact.id },
            notes = notes.filter { it.contactId == contact.id },
            lastContactDate = laterIsoDate(contact.lastContactDate, current?.lastContactDate),
            addresses = withCanonicalCoords(contact.addresses, AddressOwnerType.CONTACT, contact.id),
            companyRelations = reconcileCompanyRelations(contact.id, contact.companyRelations),
            updatedAt = nowIso(),
        )
        if (idx >= 0) {
            contacts[idx] = c
            // Карта читает ГЛОБАЛЬНЫЙ addresses — синхронизируем, иначе
            // новые/изменённые адреса невидимы на карте до перезапуска
            addresses.removeAll { it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT }
            addresses.addAll(c.addresses)
            // Карточка компании читает ГЛОБАЛЬНЫЙ companyRelations — синхронизируем,
            // иначе связанный контакт не виден в компании до перезапуска
            companyRelations.removeAll { it.contactId == c.id }
            companyRelations.addAll(c.companyRelations)
            // personalDetails читаются из ГЛОБАЛЬНОГО списка и принадлежат агрегату
            // Contact (пишутся ниже вместе с контактом) — синхронизируются как раньше.
            // gifts здесь НАМЕРЕННО не синхронизируются с c.gifts — см. P0.1 выше.
            personalDetails.removeAll { it.contactId == c.id }
            personalDetails.addAll(c.personalDetails)
            // BATCH 2/2B (data integrity, Root Cause A/B): delete-then-reinsert —
            // одна атомарная транзакция (Root Cause A), через единый глобальный
            // writer (Root Cause B, см. DbWriteQueue.kt).
            return enqueueWrite { db ->
                db.withTransaction {
                    db.contactDao().deletePhonesForContact(c.id)
                    db.contactDao().deleteEmailsForContact(c.id)
                    db.contactDao().deleteMessengersForContact(c.id)
                    db.contactDao().deleteCompanyRelationsForContact(c.id)
                    db.contactDao().deletePersonalDetailsForContact(c.id)
                    // size_infos здесь НЕ трогаются (ни delete, ни insert) — P1 выше:
                    // sizeInfo = null для addContactDb, канонической строкой владеет
                    // только setSizeInfo/слияние/restore/удаление контакта.
                    db.addressDao().deleteAddressesForOwner(c.id, AddressOwnerType.CONTACT.name)
                    addContactDb(c.copy(sizeInfo = null))
                }
            }
        }
        return CompletableDeferred(Unit)
    }

    // ── OWNERSHIP PACK #1: reconcile-хелперы — каноничное текущее состояние
    //    побеждает устаревший вложенный снимок (см. комментарий в updateContact) ──

    private fun canonicalAddresses(ownerType: AddressOwnerType, ownerId: String): List<Address> =
        addresses.filter { it.ownerId == ownerId && it.ownerType == ownerType }

    /** Тот же критерий «адрес изменён», что у редактора адреса (AddressComponents):
     *  координаты сбрасываются ТОЛЬКО при смене улицы/города/страны. */
    private fun sameLocationText(a: Address, b: Address): Boolean =
        a.addressLine.trim() == b.addressLine.trim() &&
            a.city.trim() == b.city.trim() &&
            a.country.trim() == b.country.trim()

    /** Координаты — часть сохранённого Address, но пишутся независимо (setAddressCoords).
     *  Устаревший снимок без координат НЕ стирает их: если у канонического адреса с тем же
     *  id координаты есть, а в переданном их нет и текст адреса не менялся — переносим
     *  канонические. Намеренная правка (текст изменён / координаты заданы) побеждает. */
    private fun withCanonicalCoords(passed: List<Address>, ownerType: AddressOwnerType, ownerId: String): List<Address> {
        val canonicalById = canonicalAddresses(ownerType, ownerId).associateBy { it.id }
        return passed.map { p ->
            val canon = canonicalById[p.id]
            if (canon != null && p.latitude == null && p.longitude == null &&
                canon.latitude != null && canon.longitude != null && sameLocationText(p, canon)
            ) p.copy(latitude = canon.latitude, longitude = canon.longitude) else p
        }
    }

    /** Более поздняя из двух ISO-дат (yyyy-MM-dd…): lastContactDate пишут независимо
     *  (markContactedNow, завершение события), обычный updateContact её не откатывает.
     *  Намеренно менять её через updateContact не умеет ни один экран. */
    private fun laterIsoDate(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        a >= b -> a
        else -> b
    }

    /** Связи контакта с компаниями меняют и независимо (mergeCompanies переносит их на
     *  компанию-«выжившую», deleteCompany удаляет). Устаревший снимок:
     *   • связь с несуществующей компанией и без канонической замены — отбрасывается
     *     (иначе updateContact воскрешал бы связь с уже удалённой компанией);
     *   • связь с несуществующей компанией, но с тем же id в каноническом состоянии —
     *     берётся каноническая (перенос слияния не откатывается).
     *  Связь с существующей компанией — намеренная правка, остаётся как передана. */
    private fun reconcileCompanyRelations(contactId: String, passed: List<ContactCompanyRelation>): List<ContactCompanyRelation> {
        val canonicalById = companyRelations.filter { it.contactId == contactId }.associateBy { it.id }
        return passed.mapNotNull { r ->
            if (companies.any { it.id == r.companyId }) r
            else canonicalById[r.id]?.takeIf { canon -> companies.any { it.id == canon.companyId } }
        }
    }

    /** Вложенные адреса владельца — проекция глобального addresses: после независимой
     *  записи (setAddressCoords) её нужно обновить, иначе бэкап, слияние и любой
     *  updateContact/updateCompany берут из вложенного списка устаревшие значения. */
    private fun syncNestedAddress(updated: Address) {
        when (updated.ownerType) {
            AddressOwnerType.CONTACT -> {
                val i = contacts.indexOfFirst { it.id == updated.ownerId }
                if (i >= 0) contacts[i] = contacts[i].copy(addresses = contacts[i].addresses.map { if (it.id == updated.id) updated else it })
            }
            AddressOwnerType.COMPANY -> {
                val i = companies.indexOfFirst { it.id == updated.ownerId }
                if (i >= 0) companies[i] = companies[i].copy(addresses = companies[i].addresses.map { if (it.id == updated.id) updated else it })
            }
        }
    }

    /** Вложенный Contact.companyRelations — проекция глобального companyRelations;
     *  mergeCompanies/deleteCompany/undoMergeCompanies меняют глобальный список,
     *  поэтому проекцию затронутых сотрудников пересобираем из него. */
    private fun resyncNestedCompanyRelations(contactIds: Set<String>) {
        contactIds.forEach { id ->
            val i = contacts.indexOfFirst { it.id == id }
            if (i >= 0) contacts[i] = contacts[i].copy(companyRelations = companyRelations.filter { it.contactId == id })
        }
    }

    /**
     * Восстановление контакта из бэкапа БЕЗ перезаписи дат createdAt/updatedAt
     * (в отличие от addContact/updateContact). Idempotent: сначала чистит детей,
     * потом вставляет как есть — повторный импорт того же файла не плодит дубли.
     */
    /** Private DB-only helper (не enqueue, НЕ открывает свою транзакцию —
     *  вызывающий решает) — используется restoreContact() (внутри её
     *  enqueueWrite+withTransaction) И напрямую из undoMerge()'s собственной
     *  writer-команды (§21 nested write rule: изнутри уже идущей команды
     *  нельзя enqueue заново — можно только звать такие private helpers). */
    private suspend fun restoreContactInTransaction(db: SocialsphereDatabase, contact: Contact) {
        db.contactDao().deletePhonesForContact(contact.id)
        db.contactDao().deleteEmailsForContact(contact.id)
        db.contactDao().deleteMessengersForContact(contact.id)
        db.contactDao().deleteCompanyRelationsForContact(contact.id)
        db.contactDao().deletePersonalDetailsForContact(contact.id)
        db.contactDao().deleteSizeInfoForContact(contact.id)
        db.addressDao().deleteAddressesForOwner(contact.id, AddressOwnerType.CONTACT.name)
        addContactDb(contact)
    }

    fun restoreContact(contact: Contact): Deferred<Unit> {
        val idx = contacts.indexOfFirst { it.id == contact.id }
        if (idx >= 0) contacts[idx] = contact else contacts.add(contact)
        addresses.removeAll { it.ownerId == contact.id && it.ownerType == AddressOwnerType.CONTACT }
        addresses.addAll(contact.addresses)
        // BATCH 2/2B (data integrity): тот же паттерн, что updateContact —
        // атомарно и через единый глобальный writer.
        val written = enqueueWrite { db -> db.withTransaction { restoreContactInTransaction(db, contact) } }
        // ФИКС (2026-08-12, У61-класс: addContactDb никогда не писал notes/gifts —
        // они живут в отдельных таблицах, не в самой contact-строке, addContactDb
        // молча их пропускал так же, как раньше пропускал notes до §44). Здесь же
        // подстраховка на случай СТАРОГО бэкапа без плоских top-level notes/gifts
        // (Contact.notes/gifts вложенные — они были в JSON всегда, раз Contact
        // строится из них при каждой выгрузке): восстанавливаем через те же
        // restoreNote/restoreGift, что и импорт плоского списка — upsert по id,
        // повторный вызов из data.notes/data.gifts ниже безопасен (идемпотентно).
        // Не через global writer (см. Batch 2B: notes/gifts — единичные Room-
        // операции, не участвующие в cross-aggregate ordering конфликтах).
        contact.notes.forEach { restoreNote(it) }
        contact.gifts.forEach { restoreGift(it) }
        return written
    }

    /** Восстановление группы из бэкапа: upsert по id, сохраняя точные значения
     *  (в отличие от renameGroup/addGroup, которые генерируют id/updatedAt заново). */
    fun restoreGroup(group: ContactGroup) {
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) groups[idx] = group else groups.add(group)
        // BATCH 2C: через единый глобальный writer, конфликтует с deleteGroup.
        enqueueWrite { db -> insertContactGroupInTransaction(db, group) }
    }

    /** Восстановление членства в группе из бэкапа: добавляем, только если такой
     *  записи ещё нет (как contactRelations — join-строка неизменяема). */
    fun restoreGroupMember(member: ContactGroupMember) {
        if (groupMembers.none { it.id == member.id }) {
            groupMembers.add(member)
            // BATCH 2C: через единый глобальный writer.
            enqueueWrite { db -> db.contactDao().insertContactGroupMembers(listOf(member.toEntity())) }
        }
    }

    /** Восстановление тега из бэкапа: upsert по id, сохраняя точные значения
     *  (в отличие от addTag/renameTag, которые генерируют id/updatedAt заново)
     *  — точный аналог restoreGroup. */
    fun restoreTag(tag: Tag) {
        val idx = tags.indexOfFirst { it.id == tag.id }
        if (idx >= 0) tags[idx] = tag else tags.add(tag)
        // BATCH 2C: через единый глобальный writer, конфликтует с deleteTag.
        enqueueWrite { db -> insertTagInTransaction(db, tag) }
    }

    /** Восстановление членства в теге из бэкапа: добавляем, только если такой
     *  записи ещё нет — точный аналог restoreGroupMember. */
    fun restoreTagMember(member: ContactTagMember) {
        if (tagMembers.none { it.id == member.id }) {
            tagMembers.add(member)
            // BATCH 2C: через единый глобальный writer.
            enqueueWrite { db -> db.contactDao().insertContactTagMembers(listOf(member.toEntity())) }
        }
    }

    /** Восстановление связи (семья и др.) из снапшота отмены слияния: как
     *  restoreGroupMember — добавляем, только если такой записи ещё нет. */
    fun restoreContactRelation(relation: ContactRelation) {
        if (contactRelations.none { it.id == relation.id }) {
            contactRelations.add(relation)
            // BATCH 2C: через единый глобальный writer, конфликтует с deleteContact.
            enqueueWrite { db -> db.contactDao().insertContactRelations(listOf(relation.toEntity())) }
        }
    }

    /**
     * BATCH 4 (4-001) + BACKUP INTEGRITY PACK #3: восстановление бэкапа как ОДНА атомарная операция
     * с ЯВНОЙ семантикой (полное описание — шапка RestoreMerge.kt).
     *
     * Одна команда единого писателя -> одна `db.withTransaction`: текущее состояние читается из Room
     * ВНУТРИ транзакции (все ранее принятые записи уже закоммичены, конкурентных записей нет), чистый
     * [RestoreMerger] строит план слияния (FRESH: backup 1:1; MERGE: data-safe по устойчивым id, конфликты
     * не разрешаются угадыванием), план применяется upsert'ами — ничего не удаляется и не очищается.
     * Сбой на любом шаге -> Room откатывает ВСЮ транзакцию, память не трогается, исключение уходит
     * вызывающему. Только после подтверждённого коммита: reloadFromDb() (память = Room), реконсиляция
     * легаси-тегов и внешние побочные эффекты (будильники) — их сбой НЕ откатывает уже закоммиченные данные.
     *
     * Внутри транзакции — только private-хелперы и DAO напрямую: публичные restore-/add-функции звать
     * отсюда нельзя, они сами enqueue (§21 nested write rule).
     */
    suspend fun restoreBackup(plan: RestorePlan, authorization: RestoreAuthorization = RestoreAuthorization.SafeMerge): RestoreResult {
        // Снимок ДО транзакции — для отмены alarms СТАРОГО состояния после restore
        // (см. конец функции). calendarItems (память) не мутируется нигде до reloadFromDb() ниже.
        val calendarItemsBeforeRestore = calendarItems.toList()
        // Одна логическая отметка времени на всё восстановление.
        val now = nowIso()
        val outcome = CompletableDeferred<RestoreResult>()

        enqueueWrite { db ->
            db.withTransaction {
                val current = readRoomSnapshot(db).toPersistedState()
                val (writes, result) = RestoreMerger({ seedTagSemanticKey(it) }, now).merge(current, plan, authorization)
                applyRestoreWrites(db, writes)
                outcome.complete(result)
            }
        }.await()
        val result = outcome.await()

        // Память — ТОЛЬКО после подтверждённого коммита: перечитывание из Room даёт гарантированную
        // согласованность памяти с ним (вложенные Contact.notes/gifts/адреса/размеры — из того же снимка).
        // DATA SAFETY MICRO-PACK: тем же упорядоченным путём, что и восстановление после сбоя записи, а не голым
        // reloadFromDb(): запись, принятая пользователем между коммитом restore и заменой памяти, не затирается снимком,
        // прочитанным без неё (см. resyncMemoryFromRoom). Результат для вызывающего прежний: память == Room.
        resyncMemoryFromRoom()

        // BATCH 5 (5-002): восстановленные легаси-теги приводим к канонической
        // управляемой системе ДО возврата управления. Это часть контракта
        // «успех = данные уже согласованы»: бэкап мог быть снят до появления
        // управляемых тегов и нести их только в Contact.tags. Реконсиляция
        // намеренно НЕ смотрит на историческую защёлку миграции — иначе
        // возвращается P1-баг «переустановил, восстановил, теги застряли».
        // Не fire-and-forget (§23): функция сама await'ит свой коммит, и её
        // исключение уйдёт вызывающему, не дав объявить restore успешным.
        reconcileLegacyTagsIntoManaged()

        // Calendar/Reminder alarms — ПОСЛЕ полностью завершённой transaction
        // (await() выше) и ПОСЛЕ reloadFromDb(): явный happens-before
        // «persistent write finished → reconcile читает Room» (§60). Room —
        // canonical source, не промежуточное in-memory состояние.
        // 1) Сначала отменяем alarms ДО-restore состояния (снимок
        //    calendarItemsBeforeRestore) — иначе событие, заменённое или
        //    удалённое бэкапом, не имеет строки в Room и никогда не
        //    встретится в forward-pass reconcile ниже, а его старый alarm
        //    остался бы висеть («old scheduled state → canonical Room state»,
        //    а не просто «добавить alarms из backup»).
        // 2) Затем восстанавливаем актуальные alarms единым canonical
        //    reconcile-path (NotificationScheduler.reconcileAllUserDataAlarms —
        //    тот же, что использует master-переключатель уведомлений и
        //    обычный старт приложения; второй реализации правил планирования
        //    здесь не заводится).
        // Data Safety приоритетнее alarm scheduling (§60): обе операции —
        // best effort в своих try/catch и НЕ откатывают уже восстановленные
        // данные — они гарантированно сохранены (транзакция и reloadFromDb()
        // выше уже завершены). Ошибка диагностируема через Log.e; следующий
        // reconcile (например, обычный старт приложения) способен досоздать
        // пропущенные alarms.
        appContext?.let { ctx ->
            try {
                com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
                    .cancelAllUserDataAlarms(ctx, calendarItemsBeforeRestore)
            } catch (e: Exception) {
                android.util.Log.e("AppStateStore", "restoreBackup: отмена будильников старого состояния не удалась", e)
            }
            val database = db()
            if (database != null) {
                try {
                    com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
                        .reconcileAllUserDataAlarms(ctx, database)
                } catch (e: Exception) {
                    android.util.Log.e("AppStateStore", "restoreBackup: reconcile будильников не удался", e)
                }
            }
        }
        return result
    }

    /**
     * RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22): применяет РОВНО те записи, что пользователь явно
     * выбрал в preview (см. RestorePreview.kt) — одна транзакция, одна Deferred. В отличие от [restoreBackup]
     * (SAFE MERGE автоматически добавляет всё backup-only), здесь КАЖДАЯ строка — из явного [SelectiveApplyPlan],
     * построенного чистой функцией [buildApplyPlan] из выбора пользователя; ничего сверх выбранного не пишется.
     * Сбой на любом шаге -> Room откатывает ВСЮ транзакцию, ничего из выбранного не применяется (requirement 22).
     * Память — ТОЛЬКО после подтверждённого коммита, тем же упорядоченным resyncMemoryFromRoom(), что и остальные
     * пути (requirement 23) — memory НЕ строится вручную из UI-выбора. Будильники — ПОСЛЕ коммита, обычным
     * reconcileAllUserDataAlarms (requirement 24), внутри транзакции планирование не идёт.
     */
    suspend fun applySelectiveRestore(plan: SelectiveApplyPlan): Int {
        val outcome = CompletableDeferred<Int>()
        val deferred = enqueueWrite { db ->
            db.withTransaction {
                // ФИКС (аудит перед тестами T12): restoreContactInTransaction -> addContactDb НЕ пишет
                // notes/gifts (они живут в собственных таблицах, тот же §61-класс, что у addContactDb/
                // addContact — см. комментарий у restoreContact()). Без этого дочерние заметки/подарки
                // отсутствующего контакта молча терялись бы при whole-contact restore (requirement 11/18:
                // «выбор Contact включает необходимые дочерние записи», без orphan и без потери данных).
                // Timestamps НЕ перезаписываются на «сейчас» — restore возвращает данные как они были в
                // backup (тот же принцип, что restoreNote/restoreGift/restoreContact при обычном FRESH/
                // SAFE MERGE restore), а не создаёт их заново.
                plan.wholeContacts.forEach { c ->
                    restoreContactInTransaction(db, c)
                    c.notes.forEach { insertNoteInTransaction(db, it) }
                    c.gifts.forEach { insertGiftInTransaction(db, it) }
                }
                plan.wholeCompanies.forEach { restoreCompanyInTransaction(db, it) }
                // STANDALONE GROUPS MICROFIX (2026-09-24): явный выбор «Restore group» — вставляется ДО связей/
                // членства ниже (родитель раньше ребёнка, тот же порядок, что contacts/companies выше).
                plan.wholeGroups.forEach { insertContactGroupInTransaction(db, it) }
                if (plan.childPhones.isNotEmpty()) db.contactDao().insertPhones(plan.childPhones.map { it.toEntity() })
                if (plan.childEmails.isNotEmpty()) db.contactDao().insertEmails(plan.childEmails.map { it.toEntity() })
                if (plan.childMessengers.isNotEmpty()) db.contactDao().insertMessengers(plan.childMessengers.map { it.toEntity() })
                if (plan.childAddresses.isNotEmpty()) db.addressDao().insertAddresses(plan.childAddresses.map { it.toEntity() })
                if (plan.childPersonalDetails.isNotEmpty()) db.contactDao().insertPersonalDetails(plan.childPersonalDetails.map { it.toEntity() })
                if (plan.childCompanyRelations.isNotEmpty()) db.contactDao().insertCompanyRelations(plan.childCompanyRelations.map { it.toEntity() })
                plan.childSizeInfos.forEach { db.contactDao().insertSizeInfo(it.toEntity()) }
                // Скаляр-конфликты «использовать backup» — точечный REPLACE строки-владельца (не трогает дочерние таблицы).
                plan.scalarContactUpdates.forEach { db.contactDao().insertContact(it.toEntity()) }
                plan.scalarCompanyUpdates.forEach { db.companyDao().insertCompany(it.toEntity()) }
                plan.scalarCalendarUpdates.forEach { db.calendarDao().insertCalendarItem(it.toEntity()) }
                // TAGS BACKUP RECOVERY PACK: определение тега — ТОЛЬКО если его не было (dependency),
                // ДО членства (REPLACE по id — идемпотентно на повторный restore, requirement T6).
                plan.tagDefinitions.forEach { insertTagInTransaction(db, it) }
                if (plan.tagMemberships.isNotEmpty()) db.contactDao().insertContactTagMembers(plan.tagMemberships.map { it.toEntity() })
                // RESTORE PREVIEW REMAINING DATA COVERAGE (2026-09-24): связи между контактами — обе стороны
                // к этому моменту уже вставлены (whole-контакты выше, существующие — уже в Room). Группа —
                // тот же порядок «dependency перед membership», что у тегов.
                if (plan.contactRelationsToAdd.isNotEmpty()) db.contactDao().insertContactRelations(plan.contactRelationsToAdd.map { it.toEntity() })
                plan.groupDefinitions.forEach { insertContactGroupInTransaction(db, it) }
                if (plan.groupMemberships.isNotEmpty()) db.contactDao().insertContactGroupMembers(plan.groupMemberships.map { it.toEntity() })
                // Дети СУЩЕСТВУЮЩЕГО события (не whole-missing — те уже incl. в addCalendarItemDb ниже).
                if (plan.childCalendarReminders.isNotEmpty()) db.calendarDao().insertReminderRules(plan.childCalendarReminders.map { it.toEntity() })
                if (plan.childCalendarLinks.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(plan.childCalendarLinks.map { it.toEntity() })
                restoreMidTransactionHookForTests?.invoke()
                plan.wholeCalendarItems.forEach { addCalendarItemDb(it) }
                plan.standaloneNotes.forEach { insertNoteInTransaction(db, it) }
                plan.standaloneGifts.forEach { insertGiftInTransaction(db, it) }
                if (plan.tombstonesToClear.isNotEmpty())
                    plan.tombstonesToClear.forEach { db.tombstoneDao().delete(it.type.name, it.id) }
                outcome.complete(plan.totalSelected)
            }
        }
        deferred.await()
        val applied = outcome.await()

        // Память — упорядоченным путём (§60/Data Safety Micro-Pack), не вручную из UI-выбора.
        resyncMemoryFromRoom()

        if (plan.touchesCalendar) {
            appContext?.let { ctx ->
                val database = db()
                if (database != null) {
                    try {
                        com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler.reconcileAllUserDataAlarms(ctx, database)
                    } catch (e: Exception) {
                        android.util.Log.e("AppStateStore", "applySelectiveRestore: reconcile будильников не удался", e)
                    }
                }
            }
        }
        return applied
    }

    /** Тестовый шов: вызывается ВНУТРИ restore-транзакции после записи контактов/компаний/связей/тегов и
     *  ДО записи подарков/событий/заметок — модель сбоя посередине restore. В проде null. */
    @Volatile internal var restoreMidTransactionHookForTests: (suspend () -> Unit)? = null

    /** Применяет план слияния upsert'ами (REPLACE по id). Ничего не удаляет. Только внутри транзакции. */
    private suspend fun applyRestoreWrites(db: SocialsphereDatabase, w: RestoreWrites) {
        // Порядок — по графу ссылок: родители раньше детей, join-строки после обеих сторон, notes последними.
        // Внешних ключей в схеме нет (schemas/*.json: "foreignKeys":[]), порядок — гигиена, не требование БД.
        w.companies.forEach { addCompanyDb(it) }
        w.contacts.forEach { addContactDb(it) }
        if (w.contactRelations.isNotEmpty()) db.contactDao().insertContactRelations(w.contactRelations.map { it.toEntity() })
        w.groups.forEach { insertContactGroupInTransaction(db, it) }
        if (w.groupMembers.isNotEmpty()) db.contactDao().insertContactGroupMembers(w.groupMembers.map { it.toEntity() })
        // insertTag — REPLACE (Daos.kt): та же строка с уже дополненной категорией — upsert одной строки
        w.tags.forEach { insertTagInTransaction(db, it) }
        if (w.tagMembers.isNotEmpty()) db.contactDao().insertContactTagMembers(w.tagMembers.map { it.toEntity() })
        restoreMidTransactionHookForTests?.invoke()
        w.gifts.forEach { insertGiftInTransaction(db, it) }
        w.calendarItems.forEach { addCalendarItemDb(it) }   // upsert события+ссылок+напоминаний, без delete
        w.notes.forEach { insertNoteInTransaction(db, it) }
        // EXPLICIT RECOVERY PACK: единственное МЕСТО в restore, которое реально delete — и то только tombstone-
        // строки, которые пользователь ЯВНО восстановил (SELECTIVE/RESTORE_ALL). Сама восстановленная запись
        // выше уже upsert'нута (w.contacts/w.notes/... содержит её) — удаление tombstone лишь снимает маркер.
        if (w.tombstonesToUpsert.isNotEmpty()) db.tombstoneDao().insertAll(w.tombstonesToUpsert.map { it.toEntity() })
        w.tombstonesToClear.forEach { db.tombstoneDao().delete(it.type.name, it.id) }
    }

    /** EXPLICIT RECOVERY PACK: один tombstone для in-memory списка — вызывающий сам решает, добавлять ли
     *  его в свой `db.withTransaction` (см. каждый delete* ниже). Одна отметка времени на всё действие. */
    private fun recordTombstone(type: TombstoneEntityType, id: String, now: String): Tombstone {
        val t = Tombstone(type, id, now)
        tombstones.removeAll { it.entityType == type && it.entityId == id }
        tombstones.add(t)
        return t
    }

    fun deleteContact(contactId: String): Deferred<Unit> {
        // EXPLICIT RECOVERY PACK: одна отметка времени на само удаление + его tombstone.
        val deletedAt = nowIso()
        val tombstone = recordTombstone(TombstoneEntityType.CONTACT, contactId, deletedAt)
        // Каскад: собираем id связей ДО удаления из памяти, чтобы потом
        // вычистить их и из БД (иначе оставались сиротами).
        val relationIds = contactRelations
            .filter { it.firstContactId == contactId || it.secondContactId == contactId }
            .map { it.id }
        // BATCH 2B (Confirmed Problem B — cross-aggregate atomicity): раньше
        // очистка calendar-ссылок шла через ПУБЛИЧНЫЙ updateCalendarItem() —
        // отдельная enqueued команда со своей ОТДЕЛЬНОЙ транзакцией от удаления
        // контакта. Сценарий: контакт удалён и закоммичен, процесс убит ДО
        // того, как отдельная calendar-команда дошла до очереди/закоммитилась —
        // CalendarItem остаётся ссылаться на уже удалённый контакт. Теперь
        // считаем новые версии затронутых событий здесь же, применяем
        // in-memory мутацию НАПРЯМУЮ (не через updateCalendarItem — это была бы
        // вторая enqueued команда, см. §31 nested write rule запрещает
        // enqueue изнутри уже начатой команды), а DB-часть уходит в ТУ ЖЕ
        // транзакцию, что и удаление контакта, ниже.
        val affectedCalendarItems = calendarItems
            .filter { ci -> ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId } }
            .toList()
            .map { ci ->
                ci.copy(
                    links = ci.links.filterNot { it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId },
                    updatedAt = nowIso() // тот же эффект, что updateCalendarItem() давал раньше
                )
            }
        affectedCalendarItems.forEach { ci ->
            val i = calendarItems.indexOfFirst { it.id == ci.id }
            if (i >= 0) calendarItems[i] = ci
        }
        contacts.removeAll { it.id == contactId }
        notes.removeAll { it.contactId == contactId }
        gifts.removeAll { it.contactId == contactId }
        sizeInfos.removeAll { it.contactId == contactId }
        personalDetails.removeAll { it.contactId == contactId }
        addresses.removeAll { it.ownerId == contactId && it.ownerType == AddressOwnerType.CONTACT }
        companyRelations.removeAll { it.contactId == contactId }
        contactRelations.removeAll { it.firstContactId == contactId || it.secondContactId == contactId }
        groupMembers.removeAll { it.contactId == contactId }
        tagMembers.removeAll { it.contactId == contactId }
        // BATCH 2/2B (data integrity): весь каскад удаления, ВКЛЮЧАЯ calendar-
        // link cleanup — одна атомарная транзакция через единый глобальный writer.
        return enqueueWrite { db ->
            db.withTransaction {
                affectedCalendarItems.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }
                db.contactDao().deleteGroupMembersForContact(contactId)
                db.contactDao().deleteTagMembersForContact(contactId)
                db.contactDao().deleteContact(contactId)
                db.contactDao().deletePhonesForContact(contactId)
                db.contactDao().deleteEmailsForContact(contactId)
                db.contactDao().deleteMessengersForContact(contactId)
                db.contactDao().deleteCompanyRelationsForContact(contactId)
                db.contactDao().deletePersonalDetailsForContact(contactId)
                db.contactDao().deleteSizeInfoForContact(contactId)
                relationIds.forEach { db.contactDao().deleteContactRelation(it) }
                db.addressDao().deleteAddressesForOwner(contactId, AddressOwnerType.CONTACT.name)
                db.noteDao().deleteNotesForContact(contactId)
                db.giftDao().deleteGiftsForContact(contactId)
                db.tombstoneDao().insert(tombstone.toEntity())
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  ДУБЛИКАТЫ: поиск и слияние
    // ──────────────────────────────────────────────────────────
    // internal (не private) — виден юнит-тестам дедупликации в том же модуле
    // (PhoneDedupeTest), прод-логика/видимость снаружи модуля не меняется.
    internal fun phoneDigits(s: String): String = s.filter { it.isDigit() }.takeLast(10)

    /** Пара возможных дублей + ПРИЧИНА совпадения (фидбэк 2026-07-04: «непонятно
     *  чем связаны» — совпадал канал, не показанный в превью). */
    data class DuplicateMatch(
        val a: Contact,
        val b: Contact,
        val byPhone: String? = null, // совпавший номер (последние цифры)
        val byEmail: String? = null, // совпавший email
    )

    // Общие ящики офисов — совпадение по ним НЕ признак дубля людей
    private val genericEmailLocalParts = setOf(
        "info", "office", "sales", "support", "contact", "hello", "mail", "admin", "hr"
    )

    /** Пары возможных дублей: совпадение по нормализованному телефону (≥7 цифр,
     *  НЕ общий рабочий у обоих) или по email (НЕ generic-ящик типа info@).
     *  Без повторов и без пар «сам с собой». */
    fun findDuplicatePairs(): List<DuplicateMatch> {
        val list = contacts.toList()
        val result = mutableListOf<DuplicateMatch>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                // Телефон: пары (цифры, тип); общий РАБОЧИЙ у обоих — не дубль
                val ap = a.phones.map { phoneDigits(it.number) to it.type }.filter { it.first.length >= 7 }
                val bp = b.phones.map { phoneDigits(it.number) to it.type }.filter { it.first.length >= 7 }
                val phoneHit = ap.firstNotNullOfOrNull { (d, at) ->
                    bp.firstOrNull { (bd, bt) ->
                        bd == d && !(at == PhoneType.WORK && bt == PhoneType.WORK)
                    }?.let { d }
                }
                // Email: generic-ящики (info@, office@…) пропускаем
                fun cleanEmails(c: Contact) = c.emails
                    .map { it.email.trim().lowercase() }
                    .filter { it.isNotBlank() && it.substringBefore("@") !in genericEmailLocalParts }
                val emailHit = cleanEmails(a).firstOrNull { it in cleanEmails(b) }
                if (phoneHit != null || emailHit != null) {
                    result.add(DuplicateMatch(a, b, byPhone = phoneHit, byEmail = emailHit))
                }
            }
        }
        return result
    }

    /** Расстояние Дамерау-Левенштейна (с учётом транспозиции соседних букв) —
     *  для «похоже написано» при живой проверке на дубли (см. findLiveDuplicateHints).
     *  В кодовой базе не было примитива нечёткого сравнения строк — этот
     *  написан специально под задачу, полный DP без верхней границы (имена —
     *  десятки символов, контактов — сотни, пересчёт при вводе не заметен). */
    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length; val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val d = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) d[i][0] = i
        for (j in 0..lb) d[0][j] = j
        for (i in 1..la) {
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[la][lb]
    }

    /** Подсказка «похоже, уже есть» при вводе черновика — см. findLiveDuplicateHints. */
    data class LiveDuplicateHint(
        val contact: Contact,
        val byPhone: String? = null,
        val byEmail: String? = null,
        val byNameSimilarity: Boolean = false,
    )

    /** Живая (при вводе, ДО сохранения) проверка на дубли — для экрана
     *  создания/редактирования контакта. Дополняет, не заменяет,
     *  findDuplicatePairs(): та ищет постфактум среди уже сохранённых
     *  контактов; эта сравнивает ещё не сохранённый черновик формы против
     *  базы. Телефон/email переиспользуют ровно ту же логику совпадения,
     *  что и findDuplicatePairs (см. коммент там) — только направление
     *  сравнения другое (черновик × база, а не база × база).
     *  excludeId — id самого редактируемого контакта (при правке существующего,
     *  не при создании), чтобы контакт не «находил дубль самого себя».
     */
    fun findLiveDuplicateHints(
        draftFirstName: String,
        draftLastName: String,
        draftPhones: List<String>,
        draftEmails: List<String>,
        excludeId: String? = null,
    ): List<LiveDuplicateHint> {
        val draftPhoneDigits = draftPhones.map { phoneDigits(it) }.filter { it.length >= 7 }.toSet()
        val draftEmailsClean = draftEmails.map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.substringBefore("@") !in genericEmailLocalParts }.toSet()
        // Похожесть имени триггерим только когда заполнены И имя, И фамилия —
        // одно имя («Мария») совпало бы с половиной базы, шума будет больше
        // чем пользы (см. предложение аудита 2026-07-22).
        val nameCheckEligible = draftFirstName.isNotBlank() && draftLastName.isNotBlank()
        val draftFullName = listOf(draftFirstName, draftLastName)
            .filter { it.isNotBlank() }.joinToString(" ").searchNormalize()

        val hints = mutableListOf<LiveDuplicateHint>()
        contacts.forEach { c ->
            if (c.id == excludeId) return@forEach
            val phoneHit = c.phones.map { phoneDigits(it.number) }
                .firstOrNull { it.length >= 7 && it in draftPhoneDigits }
            val emailHit = c.emails.map { it.email.trim().lowercase() }
                .firstOrNull { it.isNotBlank() && it.substringBefore("@") !in genericEmailLocalParts && it in draftEmailsClean }
            var nameSimilar = false
            if (nameCheckEligible) {
                val candidateFullName = listOf(c.firstName, c.lastName)
                    .filter { it.isNotBlank() }.joinToString(" ").searchNormalize()
                if (candidateFullName.isNotBlank()) {
                    val threshold = if (draftFullName.length <= 6) 1 else 2
                    nameSimilar = damerauLevenshtein(draftFullName, candidateFullName) <= threshold
                }
            }
            if (phoneHit != null || emailHit != null || nameSimilar) {
                hints.add(LiveDuplicateHint(c, byPhone = phoneHit, byEmail = emailHit, byNameSimilarity = nameSimilar))
            }
        }
        return hints
    }

    /** «Полнота» контакта — при слиянии по умолчанию keep — самый заполненный. */
    internal fun contactScore(c: Contact): Int =
        c.phones.size + c.emails.size + c.messengers.size + c.addresses.size +
        c.notes.size + c.gifts.size + c.companyRelations.size +
        (if (c.nickname.isNullOrBlank()) 0 else 1)

    /** Свободный текст при слиянии: пусто → другое; совпадают (без учёта
     *  регистра) → одно; иначе — по умолчанию ОБА через " / " (ничего не
     *  теряется молча), если явный выбор пользователя (MergeResolution) не
     *  сузил список включаемых значений. */
    private fun combineChosenText(key: String, values: List<Pair<String, String?>>, included: Set<String>?): String? {
        val nonBlank = values.mapNotNull { (id, v) -> v?.trim()?.takeIf { it.isNotBlank() }?.let { id to it } }
        if (nonBlank.isEmpty()) return null
        val distinct = nonBlank.map { it.second.lowercase() }.distinct()
        if (distinct.size == 1) return nonBlank.first().second
        val chosen = if (included != null) nonBlank.filter { it.first in included } else nonBlank
        // Защита: если пользователь снял все чипы (или ключ ещё не решён иначе) —
        // не терять данные молча, использовать все непустые значения.
        val finalList = chosen.ifEmpty { nonBlank }
        return finalList.joinToString(" / ") { it.second }
    }

    /** Одиночное строковое поле: если у всех выбранных контактов значение
     *  совпадает или пусто у части — авто-подстановка без вопроса. Если
     *  расходится — берётся explicit resolution.choiceWinners[key], иначе
     *  первое непустое по порядку (совместимо с прежним поведением). */
    private fun pickSingle(key: String, values: List<Pair<String, String?>>, winner: String?): String? {
        val nonBlank = values.filter { !it.second.isNullOrBlank() }
        if (nonBlank.isEmpty()) return null
        if (nonBlank.map { it.second }.distinct().size == 1) return nonBlank.first().second
        return nonBlank.firstOrNull { it.first == winner }?.second ?: nonBlank.first().second
    }

    private fun <T> pickEnum(values: List<Pair<String, T>>, winner: String?): T {
        if (values.map { it.second }.distinct().size == 1) return values.first().second
        return values.firstOrNull { it.first == winner }?.second ?: values.first().second
    }

    /** Полный «снимок» состояния до слияния — для отмены (Snackbar «Отменить»
     *  на ~10 сек после операции). Хранится только в памяти вызывающего экрана,
     *  БД не трогает — если экран закрыт/забыт, снимок просто теряется. */
    data class MergeUndoSnapshot(
        val contacts: List<Contact>,
        val notes: List<Note>,
        val gifts: List<GiftIdea>,
        val relations: List<ContactRelation>,
        val calendarItems: List<CalendarItem>,
        val groupMembers: List<ContactGroupMember>,
        val tagMembers: List<ContactTagMember>
    )

    /**
     * Слияние 2-3 контактов в один. Списочные поля (телефоны/email/адреса/
     * заметки/подарки/теги/связи/группы) объединяются честно — union с дедупом,
     * ничего не теряется. Одиночные конфликтующие поля (имя, фамилия, статус,
     * важность, соц.роль, ритм, фото, тип отношений и т.д.) — если у выбранных
     * контактов значения расходятся, побеждает choiceWinners[key] из resolution;
     * если resolution не содержит ключ — первое непустое (старое поведение,
     * гарантирует что кнопка «Слить» может быть нажата и без явного решения
     * по каждому пункту). Свободный текст — combineChosenText (см. выше).
     *
     * Все изменения в БД идут ОДНОЙ Room-транзакцией (db.withTransaction) —
     * либо весь merge применяется, либо (при сбое/убийстве процесса) ни один
     * из участвующих контактов не тронут на диске. Раньше update/delete шли
     * раздельными scope.launch без транзакции — для 2-3 контактов это окно
     * несогласованности на диске умножалось на число удаляемых контактов.
     *
     * Возвращает снимок для отмены (null, если слияние не выполнено —
     * например, id не найдены или их меньше 2/больше 3).
     */
    fun mergeContacts(contactIds: List<String>, resolution: MergeResolution = MergeResolution()): MergeUndoSnapshot? {
        val uniqueIds = contactIds.distinct()
        if (uniqueIds.size < 2 || uniqueIds.size > 3) return null
        val group = uniqueIds.mapNotNull { getContact(it) }
        if (group.size != uniqueIds.size) return null

        val keep = group.maxByOrNull { contactScore(it) } ?: group.first()
        val keepId = keep.id
        val others = group.filter { it.id != keepId }
        val otherIds = others.map { it.id }
        val allPairs = group.map { it.id to it }

        // ── снимок ДО любых мутаций ──
        val snapshotNotes = notes.filter { it.contactId in uniqueIds }.toList()
        val snapshotGifts = gifts.filter { it.contactId in uniqueIds }.toList()
        val snapshotRelations = contactRelations.filter {
            it.firstContactId in uniqueIds || it.secondContactId in uniqueIds
        }.toList()
        val snapshotCalendarItems = calendarItems.filter { ci ->
            ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId in uniqueIds }
        }.toList()
        val snapshotGroupMembers = groupMembers.filter { it.contactId in uniqueIds }.toList()
        val snapshotTagMembers = tagMembers.filter { it.contactId in uniqueIds }.toList()
        val snapshot = MergeUndoSnapshot(
            contacts = group, notes = snapshotNotes, gifts = snapshotGifts,
            relations = snapshotRelations, calendarItems = snapshotCalendarItems,
            groupMembers = snapshotGroupMembers, tagMembers = snapshotTagMembers
        )

        // ── списочные поля: union с дедупом (как раньше, обобщено на N) ──
        val mergedPhones = keep.phones.toMutableList()
        val mergedEmails = keep.emails.toMutableList()
        val mergedMessengers = keep.messengers.toMutableList()
        val mergedCompRels = keep.companyRelations.toMutableList()
        val mergedAddresses = keep.addresses.toMutableList()
        val mergedPd = keep.personalDetails.toMutableList()
        others.forEach { other ->
            other.phones.forEach { p ->
                val d = phoneDigits(p.number)
                if (d.isEmpty() || mergedPhones.none { phoneDigits(it.number) == d })
                    mergedPhones.add(p.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.emails.forEach { e ->
                if (mergedEmails.none { it.email.equals(e.email, true) })
                    mergedEmails.add(e.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.messengers.forEach { m ->
                if (mergedMessengers.none { it.type == m.type && it.value.equals(m.value, true) })
                    mergedMessengers.add(m.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            // Дедуп по ПОЛНОМУ совпадению записи, не только companyId — раньше
            // вторая связь с той же компанией отбрасывалась целиком, даже если
            // должность/отдел/заметка различались (реальная потеря данных).
            other.companyRelations.forEach { r ->
                val dup = mergedCompRels.any {
                    it.companyId == r.companyId && it.position == r.position &&
                    it.department == r.department && it.role == r.role &&
                    it.employmentStatus == r.employmentStatus && it.startDate == r.startDate &&
                    it.endDate == r.endDate && it.responsibilities == r.responsibilities &&
                    it.managedAccounts == r.managedAccounts && it.workNote == r.workNote
                }
                if (!dup) mergedCompRels.add(r.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.addresses.forEach { a ->
                if (mergedAddresses.none { it.addressLine.equals(a.addressLine, true) && it.city.equals(a.city, true) })
                    mergedAddresses.add(a.copy(id = generateId(), ownerId = keepId))
            }
            other.personalDetails.forEach { pd ->
                if (mergedPd.none { it.category == pd.category && it.value.equals(pd.value, true) })
                    mergedPd.add(pd.copy(id = generateId(), contactId = keepId))
            }
        }

        fun winner(key: String) = resolution.choiceWinners[key]
        fun text(key: String, getter: (Contact) -> String?) =
            combineChosenText(key, allPairs.map { it.first to getter(it.second) }, resolution.textIncluded[key])

        // Размеры (SizeInfo) — раньше выбирался целый объект одного контакта,
        // подполя другого терялись целиком. Теперь каждое подполе комбинируется
        // текстом отдельно (см. TEXT_MERGE_FIELDS), собираем объект обратно,
        // только если хоть одно подполе непустое.
        val sizeClothing = text("sizeClothing") { it.sizeInfo?.clothingSize }
        val sizeShoe = text("sizeShoe") { it.sizeInfo?.shoeSize }
        val sizeRing = text("sizeRing") { it.sizeInfo?.ringSize }
        val sizeOther = text("sizeOther") { it.sizeInfo?.other }
        val mergedSizeInfo = if (sizeClothing == null && sizeShoe == null && sizeRing == null && sizeOther == null) null
            else SizeInfo(generateId(), keepId, sizeClothing, sizeShoe, sizeRing, sizeOther)

        val merged = keep.copy(
            // Структура имени и дата знакомства — как остальной свободный текст:
            // при расхождении оба значения остаются рядом через " / ", ничего не
            // пропадает молча (фидбэк владельца 2026-07-13: «если в одном только
            // имя, а в другом имя и фамилия — хочу иметь оба, а не выбор одного»).
            firstName = text("firstName") { it.firstName } ?: keep.firstName,
            lastName  = text("lastName") { it.lastName } ?: keep.lastName,
            middleName = text("middleName") { it.middleName },
            namePrefix = text("namePrefix") { it.namePrefix },
            nameSuffix = text("nameSuffix") { it.nameSuffix },
            phoneticFirstName = text("phoneticFirstName") { it.phoneticFirstName },
            phoneticMiddleName = text("phoneticMiddleName") { it.phoneticMiddleName },
            phoneticLastName = text("phoneticLastName") { it.phoneticLastName },
            meetDate = text("meetDate") { it.meetDate },
            // Фото и enum-поля модели физически одноместные (Contact хранит
            // ровно одно значение) — тут выбор неизбежен. Что не выбрано,
            // MergeResolveScreen сохраняет отдельной заметкой, чтобы информация
            // не пропадала совсем — просто не может остаться "живым" полем.
            photoUri = pickSingle("photoUri", allPairs.map { it.first to it.second.photoUri }, winner("photoUri")),
            relationshipType = pickEnum(allPairs.map { it.first to it.second.relationshipType }, winner("relationshipType")),
            importanceLevel = pickEnum(allPairs.map { it.first to it.second.importanceLevel }, winner("importanceLevel")),
            socialRole = pickEnum(allPairs.map { it.first to it.second.socialRole }, winner("socialRole")),
            communicationRhythm = pickEnum(allPairs.map { it.first to it.second.communicationRhythm }, winner("communicationRhythm")),
            contactStatus = pickEnum(allPairs.map { it.first to it.second.contactStatus }, winner("contactStatus")),
            // LEGACY (UI больше не показывает) и внутренний sync-id — тем же
            // безопасным правилом «первое непустое», что и раньше, без per-field
            // экрана (нечего показывать пользователю осмысленно).
            connectionLevel = keep.connectionLevel,
            deviceContactId = (listOf(keep) + others).firstOrNull { it.deviceContactId != null }?.deviceContactId,
            deviceLookupKey = (listOf(keep) + others).firstOrNull { it.deviceContactId != null }?.deviceLookupKey
                ?: (listOf(keep) + others).firstNotNullOfOrNull { it.deviceLookupKey },
            // TODO (см. Models.kt): свой тип отношений станет списком — сознательно
            // не включаем в per-field выбор сейчас, чтобы не переписывать дважды.
            customRelationshipType = keep.customRelationshipType ?: others.firstNotNullOfOrNull { it.customRelationshipType },
            nickname = text("nickname") { it.nickname },
            profession = text("profession") { it.profession },
            nextStep = text("nextStep") { it.nextStep },
            canHelpWith = text("canHelpWith") { it.canHelpWith },
            iCanHelpWith = text("iCanHelpWith") { it.iCanHelpWith },
            talkingPoints = text("talkingPoints") { it.talkingPoints },
            meetContext = text("meetContext") { it.meetContext },
            familyNote = text("familyNote") { it.familyNote },
            lastContactDate = allPairs.mapNotNull { it.second.lastContactDate }.maxOrNull(),
            // ФИКС (Data Safety Residual Audit, TAG-3 — Legacy Tags Compatibility & Phone Sync Safety
            // Pack, 2026-09-23): union легаси-полей ВСЕХ сливаемых контактов был избыточным и опасным
            // путём — managed-теги и так корректно объединяются отдельно, ниже, через
            // contactTagMembershipDiff (mergedTagIds из snapshotTagMembers ВСЕХ сливаемых контактов).
            // Union легаси-строк мог потом (через отдельный вызов reconcileLegacyTagsIntoManaged())
            // повторно создать managed membership из залежавшегося легаси-поля СЛИВАЕМОГО контакта —
            // без единого выбора пользователя. keep.tags — только собственное поле выжившего контакта,
            // ничего чужого не приносится.
            tags = keep.tags,
            phones = mergedPhones, emails = mergedEmails, messengers = mergedMessengers,
            companyRelations = mergedCompRels, addresses = mergedAddresses, personalDetails = mergedPd,
            sizeInfo = mergedSizeInfo
        )

        val notesToReassign = snapshotNotes.filter { it.contactId in otherIds }.map { it.copy(contactId = keepId) }
        val giftsToReassign = snapshotGifts.filter { it.contactId in otherIds }.map { it.copy(contactId = keepId) }

        val relationUpdates = mutableListOf<ContactRelation>()
        val relationRemovals = mutableListOf<String>()
        snapshotRelations.forEach { rel ->
            val nf = if (rel.firstContactId in otherIds) keepId else rel.firstContactId
            val ns = if (rel.secondContactId in otherIds) keepId else rel.secondContactId
            if (nf == ns) { relationRemovals.add(rel.id); return@forEach }
            if (nf == rel.firstContactId && ns == rel.secondContactId) return@forEach // не задета слиянием
            val dupExists = snapshotRelations.any {
                it.id != rel.id &&
                ((it.firstContactId == nf && it.secondContactId == ns) || (it.firstContactId == ns && it.secondContactId == nf))
            }
            if (dupExists) relationRemovals.add(rel.id)
            else relationUpdates.add(rel.copy(firstContactId = nf, secondContactId = ns))
        }

        val calendarUpdates = snapshotCalendarItems.map { ci ->
            ci.copy(links = ci.links.map {
                if (it.targetType == CalendarTargetType.CONTACT && it.targetId in otherIds) it.copy(targetId = keepId) else it
            }.distinctBy { it.targetType to it.targetId })
        }

        val mergedGroupIds = snapshotGroupMembers.map { it.groupId }.toSet()
        val mergedTagIds = snapshotTagMembers.map { it.tagId }.toSet()
        // ФИКС (найдено TEST GROUP M/undoMerge regression, Batch 2C): раньше
        // здесь вызывались ПУБЛИЧНЫЕ setContactGroups(keepId, ...)/
        // setContactTags(keepId, ...) — своя ОТДЕЛЬНАЯ enqueued DB-команда со
        // СВОИМ generateId() для новых membership-строк, — а транзакция ниже
        // (главная транзакция merge'а) заново удаляла и заново вставляла те
        // же keepId-строки СВОИМ ОТДЕЛЬНЫМ generateId(). Итог: в памяти
        // membership-строка получала один id, а в БД реально осевшая
        // строка — другой. Для обычного чтения это было незаметно (и память,
        // и БД согласны, КАКАЯ пара контакт+группа существует), но ломало
        // undoMerge(): contactGroupMembershipDiff/contactTagMembershipDiff
        // берут "toRemove" из ТЕКУЩЕГО in-memory списка и удаляют из БД ПО
        // ЭТОМУ id — раз БД хранила другой id, DELETE не находил строку, и
        // membership удалённого при merge контакта "дублировалось" рядом с
        // восстановленным после undo. Фикс — тот же приём, что уже применён
        // к undoMerge: считаем diff ЧИСТОЙ функцией один раз, применяем И в
        // памяти, И в БД (в той же транзакции merge'а) ОДНИМИ и теми же
        // объектами — id гарантированно совпадает.
        val (groupToRemove, groupToAdd) = contactGroupMembershipDiff(keepId, mergedGroupIds)
        val (tagToRemove, tagToAdd) = contactTagMembershipDiff(keepId, mergedTagIds)

        // ── применяем в памяти (синхронно, как везде в этом файле) ──
        val keepIdx = contacts.indexOfFirst { it.id == keepId }
        // P0.1: вложенный Contact.gifts выжившего контакта — проекция того же
        // канонического набора, что транзакция ниже пишет в Room: его собственные
        // подарки + переназначенные от сливаемых (merged.gifts — копия keep.gifts и
        // иначе не знала бы о переназначенных). Строится из снимка, не из памяти.
        val mergedKeepGifts = snapshotGifts.filter { it.contactId == keepId } + giftsToReassign
        if (keepIdx >= 0) contacts[keepIdx] = merged.copy(gifts = mergedKeepGifts, updatedAt = nowIso())
        otherIds.forEach { oid -> contacts.removeAll { it.id == oid } }
        addresses.removeAll { it.ownerId == keepId && it.ownerType == AddressOwnerType.CONTACT }
        addresses.addAll(merged.addresses)
        otherIds.forEach { oid -> addresses.removeAll { it.ownerId == oid && it.ownerType == AddressOwnerType.CONTACT } }
        companyRelations.removeAll { it.contactId == keepId }
        companyRelations.addAll(merged.companyRelations)
        otherIds.forEach { oid -> companyRelations.removeAll { it.contactId == oid } }
        notesToReassign.forEach { n -> val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n }
        giftsToReassign.forEach { g -> val i = gifts.indexOfFirst { it.id == g.id }; if (i >= 0) gifts[i] = g }
        relationRemovals.forEach { rid -> contactRelations.removeAll { it.id == rid } }
        relationUpdates.forEach { r -> val i = contactRelations.indexOfFirst { it.id == r.id }; if (i >= 0) contactRelations[i] = r }
        calendarUpdates.forEach { ci -> val i = calendarItems.indexOfFirst { it.id == ci.id }; if (i >= 0) calendarItems[i] = ci }
        groupMembers.removeAll(groupToRemove.toSet()); groupMembers.addAll(groupToAdd)
        otherIds.forEach { oid -> groupMembers.removeAll { it.contactId == oid } }
        tagMembers.removeAll(tagToRemove.toSet()); tagMembers.addAll(tagToAdd)
        otherIds.forEach { oid -> tagMembers.removeAll { it.contactId == oid } }
        sizeInfos.removeAll { it.contactId == keepId || it.contactId in otherIds }
        merged.sizeInfo?.let { sizeInfos.add(it) }
        personalDetails.removeAll { it.contactId == keepId || it.contactId in otherIds }
        personalDetails.addAll(merged.personalDetails)

        // ── одна транзакция на весь merge: либо всё, либо (при сбое) ничего ──
        // BATCH 2B: обёртка теперь — enqueueWrite на ЕДИНЫЙ глобальный writer
        // (не отдельный contact-only диспетчер) — сериализовано против ЛЮБОЙ
        // другой Contact/Company/Calendar записи, не только Contact. Содержимое
        // транзакции (сам алгоритм merge) не тронуто.
        enqueueWrite { db ->
            db.withTransaction {
            db.contactDao().deletePhonesForContact(keepId)
                db.contactDao().deleteEmailsForContact(keepId)
                db.contactDao().deleteMessengersForContact(keepId)
                db.contactDao().deleteCompanyRelationsForContact(keepId)
                db.contactDao().deletePersonalDetailsForContact(keepId)
                db.contactDao().deleteSizeInfoForContact(keepId)
                db.addressDao().deleteAddressesForOwner(keepId, AddressOwnerType.CONTACT.name)

                db.contactDao().insertContact(merged.toEntity())
                if (merged.phones.isNotEmpty()) db.contactDao().insertPhones(merged.phones.map { it.toEntity() })
                if (merged.emails.isNotEmpty()) db.contactDao().insertEmails(merged.emails.map { it.toEntity() })
                if (merged.messengers.isNotEmpty()) db.contactDao().insertMessengers(merged.messengers.map { it.toEntity() })
                if (merged.companyRelations.isNotEmpty()) db.contactDao().insertCompanyRelations(merged.companyRelations.map { it.toEntity() })
                if (merged.addresses.isNotEmpty()) db.addressDao().insertAddresses(merged.addresses.map { it.toEntity() })
                if (merged.personalDetails.isNotEmpty()) db.contactDao().insertPersonalDetails(merged.personalDetails.map { it.toEntity() })
                merged.sizeInfo?.let { db.contactDao().insertSizeInfo(it.toEntity()) }

                // Реассайн заметок/подарков ДО удаления «других» контактов —
                // иначе последующий deleteNotesForContact(oid) снёс бы их.
                if (notesToReassign.isNotEmpty()) db.noteDao().insertNotes(notesToReassign.map { it.toEntity() })
                if (giftsToReassign.isNotEmpty()) db.giftDao().insertGifts(giftsToReassign.map { it.toEntity() })

                relationRemovals.forEach { db.contactDao().deleteContactRelation(it) }
                if (relationUpdates.isNotEmpty()) db.contactDao().insertContactRelations(relationUpdates.map { it.toEntity() })

                calendarUpdates.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }

                applyGroupMembershipDiffInTransaction(db, groupToRemove, groupToAdd)
                applyTagMembershipDiffInTransaction(db, tagToRemove, tagToAdd)

                otherIds.forEach { oid ->
                    db.contactDao().deleteGroupMembersForContact(oid)
                    db.contactDao().deleteTagMembersForContact(oid)
                    db.contactDao().deletePhonesForContact(oid)
                    db.contactDao().deleteEmailsForContact(oid)
                    db.contactDao().deleteMessengersForContact(oid)
                    db.contactDao().deleteCompanyRelationsForContact(oid)
                    db.contactDao().deletePersonalDetailsForContact(oid)
                    db.contactDao().deleteSizeInfoForContact(oid)
                    db.addressDao().deleteAddressesForOwner(oid, AddressOwnerType.CONTACT.name)
                    db.noteDao().deleteNotesForContact(oid)
                    db.giftDao().deleteGiftsForContact(oid)
                    db.contactDao().deleteContact(oid)
                }
            }
        }

        return snapshot
    }

    /** Freemium (2026-08, Pro/Pro+): «слить все дубли одним тапом». НЕ меняет
     *  и не обходит cap в mergeContacts() (2-3 id — это часть модели
     *  разрешения конфликтов, не UI-ограничение) — просто вызывает её в цикле
     *  парами, как и обычное ручное слияние по одной паре.
     *  Транзитивные цепочки (A дублирует B, B дублирует C, но не A~C напрямую)
     *  решаются за НЕСКОЛЬКО проходов, не ручной кластеризацией: после
     *  слияния A+B «уцелевший» контакт наследует ВСЕ телефоны/email обеих
     *  сторон (см. mergedPhones/mergedEmails выше), поэтому findDuplicatePairs()
     *  на следующем проходе САМ находит его как дубль C, если раньше C
     *  дублировал именно те данные, что были у B. Останавливаемся, когда
     *  проход не даёт ни одного слияния, либо на предохранителе 20 проходов.
     *  @return сколько пар успешно слито. */
    fun mergeAllDuplicates(): Int {
        var totalMerged = 0
        repeat(20) {
            val pairs = findDuplicatePairs()
            if (pairs.isEmpty()) return totalMerged
            var mergedThisPass = 0
            pairs.forEach { pair ->
                // getContact() внутри mergeContacts безопасно вернёт null и
                // пропустит пару, если один из id уже поглощён более ранним
                // слиянием в ЭТОМ ЖЕ проходе — не крашит, не дублирует.
                if (mergeContacts(listOf(pair.a.id, pair.b.id)) != null) mergedThisPass++
            }
            totalMerged += mergedThisPass
            if (mergedThisPass == 0) return totalMerged
        }
        return totalMerged
    }

    /** Отмена слияния по снимку (Snackbar «Объединено · Отменить», ~10 сек).
     *  BATCH 2C (§20-21): раньше вызывала СМЕСЬ публичных enqueue-функций
     *  (restoreContact/updateCalendarItem/setContactGroups/setContactTags —
     *  каждая своя отдельная writer-команда) И голых scope.launch в обход
     *  writer вообще (notes/gifts/relations) — ни одна общая гарантия
     *  порядка/атомарности не покрывала undo merge КАК ЦЕЛОЕ (partial-undo
     *  на сбое/смерти процесса был возможен). Теперь — ОДНА writer-команда,
     *  ОДНА Room-транзакция на все DB-мутации restore; используются ТОЛЬКО
     *  private *InTransaction helpers — никогда public enqueue-функции
     *  (иначе enqueue изнутри уже идущей команды, см. §21 nested write
     *  rule = deadlock). In-memory мутации остаются синхронными, как и раньше;
     *  diff для групп/тегов считается ДО применения (та же логика, что
     *  contactGroupMembershipDiff/contactTagMembershipDiff используют сами по
     *  себе), результат применяется и в памяти, и (внутри одной транзакции) в БД. */
    fun undoMerge(snapshot: MergeUndoSnapshot): Deferred<Unit> {
        snapshot.contacts.forEach { c ->
            val idx = contacts.indexOfFirst { it.id == c.id }
            if (idx >= 0) contacts[idx] = c else contacts.add(c)
            addresses.removeAll { it.ownerId == c.id && it.ownerType == AddressOwnerType.CONTACT }
            addresses.addAll(c.addresses)
            // OWNERSHIP PACK #1: mergeContacts менял ГЛОБАЛЬНЫЕ sizeInfos/companyRelations/
            // personalDetails, а undo возвращал только вложенное и Room — память
            // расходилась с Room (после отмены слияния размеры показывались слитыми, а
            // диалог размеров, беря existing из памяти, создавал вторую строку size_infos
            // у того же контакта). Возвращаем глобальные проекции к снимку — тем же
            // набором, что restoreContactInTransaction пишет в Room.
            sizeInfos.removeAll { it.contactId == c.id }
            c.sizeInfo?.let { sizeInfos.add(it) }
            companyRelations.removeAll { it.contactId == c.id }
            companyRelations.addAll(c.companyRelations)
            personalDetails.removeAll { it.contactId == c.id }
            personalDetails.addAll(c.personalDetails)
        }
        snapshot.notes.forEach { n ->
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = n else notes.add(n)
        }
        snapshot.gifts.forEach { g ->
            val i = gifts.indexOfFirst { it.id == g.id }
            if (i >= 0) gifts[i] = g else gifts.add(g)
        }
        snapshot.relations.forEach { rel ->
            val i = contactRelations.indexOfFirst { it.id == rel.id }
            if (i >= 0) contactRelations[i] = rel else contactRelations.add(rel)
        }
        val calendarUpdates = calendarLinksRestoredFrom(snapshot.calendarItems)   // CALENDAR DATA SAFETY PACK: только связи
        calendarUpdates.forEach { ci ->
            val i = calendarItems.indexOfFirst { it.id == ci.id }
            if (i >= 0) calendarItems[i] = ci
        }
        val byContactGroups = snapshot.groupMembers.groupBy { it.contactId }
        val groupDiffs = snapshot.contacts.map { c ->
            contactGroupMembershipDiff(c.id, byContactGroups[c.id].orEmpty().map { it.groupId }.toSet())
        }
        groupDiffs.forEach { (toRemove, toAdd) -> groupMembers.removeAll(toRemove.toSet()); groupMembers.addAll(toAdd) }

        // Теги — точно тот же приём, что и группы выше: каждый контакт (и keep,
        // и восстановленные others) возвращается к СВОЕМУ исходному набору тегов
        // из снимка ДО слияния (не просто добавляем недостающее — restoreTagMember
        // здесь не подходит, т.к. не убрал бы у keepId лишние теги, доставшиеся
        // от объединения с others; честный diff в обе стороны, как setContactTags).
        val byContactTags = snapshot.tagMembers.groupBy { it.contactId }
        val tagDiffs = snapshot.contacts.map { c ->
            contactTagMembershipDiff(c.id, byContactTags[c.id].orEmpty().map { it.tagId }.toSet())
        }
        tagDiffs.forEach { (toRemove, toAdd) -> tagMembers.removeAll(toRemove.toSet()); tagMembers.addAll(toAdd) }

        return enqueueWrite { db ->
            db.withTransaction {
                snapshot.contacts.forEach { restoreContactInTransaction(db, it) }
                snapshot.notes.forEach { insertNoteInTransaction(db, it) }
                snapshot.gifts.forEach { insertGiftInTransaction(db, it) }
                snapshot.relations.forEach { db.contactDao().insertContactRelations(listOf(it.toEntity())) }
                calendarUpdates.forEach { replaceCalendarLinksInTransaction(db, it) }
                groupDiffs.forEach { (toRemove, toAdd) -> applyGroupMembershipDiffInTransaction(db, toRemove, toAdd) }
                tagDiffs.forEach { (toRemove, toAdd) -> applyTagMembershipDiffInTransaction(db, toRemove, toAdd) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  ГРУППЫ КОНТАКТОВ (как в телефонной книге)
    // ──────────────────────────────────────────────────────────

    /** Private DB-only helper (не enqueue) — используется addGroup/renameGroup
     *  (через enqueueWrite) И restoreGroup/undoMerge (напрямую, уже внутри их
     *  собственной writer-команды — см. §21 nested write rule). */
    private suspend fun insertContactGroupInTransaction(db: SocialsphereDatabase, group: ContactGroup) {
        db.contactDao().insertContactGroup(group.toEntity())
    }

    /** Создать группу; при совпадении имени (без регистра) возвращает существующую. */
    fun addGroup(name: String): ContactGroup? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        groups.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        val g = ContactGroup(generateId(), clean, nowIso(), nowIso())
        groups.add(g)
        // BATCH 2C: конфликтует с deleteGroup/undoMerge той же группы —
        // через единый глобальный writer, не голый scope.launch.
        enqueueWrite { db -> insertContactGroupInTransaction(db, g) }
        return g
    }

    fun renameGroup(groupId: String, newName: String): Deferred<Unit> {
        val clean = newName.trim()
        if (clean.isBlank()) return CompletableDeferred(Unit)
        val idx = groups.indexOfFirst { it.id == groupId }
        if (idx < 0) return CompletableDeferred(Unit)
        val g = groups[idx].copy(name = clean, updatedAt = nowIso())
        groups[idx] = g
        return enqueueWrite { db -> insertContactGroupInTransaction(db, g) }
    }

    /** Удалить группу вместе с членством (контакты не трогаются).
     *  BATCH 2C (§12): удаление членства + удаление самой группы — одна
     *  логическая операция, теперь одна транзакция одной writer-команды. */
    fun deleteGroup(groupId: String): Deferred<Unit> {
        groups.removeAll { it.id == groupId }
        groupMembers.removeAll { it.groupId == groupId }
        return enqueueWrite { db ->
            db.withTransaction {
                db.contactDao().deleteGroupMembersForGroup(groupId)
                db.contactDao().deleteContactGroup(groupId)
            }
        }
    }

    fun groupsOfContact(contactId: String): List<ContactGroup> {
        val ids = groupMembers.filter { it.contactId == contactId }.map { it.groupId }.toSet()
        return groups.filter { it.id in ids }
    }

    fun contactIdsInGroup(groupId: String): Set<String> =
        groupMembers.filter { it.groupId == groupId }.map { it.contactId }.toSet()

    /** Diff чистая функция (без DB) — переиспользуется setContactGroups()
     *  (сама делает enqueue) и undoMerge() (складывает результат в СВОЮ,
     *  единую writer-команду — см. §21 nested write rule). */
    private fun contactGroupMembershipDiff(
        contactId: String, groupIds: Set<String>
    ): Pair<List<ContactGroupMember>, List<ContactGroupMember>> {
        val current = groupMembers.filter { it.contactId == contactId }
        val toRemove = current.filter { it.groupId !in groupIds }
        val toAdd = groupIds
            .filter { gid -> current.none { it.groupId == gid } }
            .map { gid -> ContactGroupMember(generateId(), gid, contactId) }
        return toRemove to toAdd
    }

    /** Private DB-only helper — вызывается И из setContactGroups() (обёрнуто в
     *  enqueueWrite), И напрямую из undoMerge()'s собственной writer-команды —
     *  сама НИКОГДА не enqueue (§21: enqueue изнутри уже идущей команды —
     *  верный способ получить deadlock). */
    private suspend fun applyGroupMembershipDiffInTransaction(
        db: SocialsphereDatabase, toRemove: List<ContactGroupMember>, toAdd: List<ContactGroupMember>
    ) {
        toRemove.forEach { db.contactDao().deleteGroupMember(it.id) }
        if (toAdd.isNotEmpty()) db.contactDao().insertContactGroupMembers(toAdd.map { it.toEntity() })
    }

    /** Полностью задать набор групп контакта (диалог с чекбоксами).
     *  BATCH 2C (§11): delete-old + insert-new — одна logical operation,
     *  теперь одна транзакция одной writer-команды (была: 2 отдельные DAO
     *  операции в одном НЕтранзакционном scope.launch). */
    fun setContactGroups(contactId: String, groupIds: Set<String>): Deferred<Unit> {
        val (toRemove, toAdd) = contactGroupMembershipDiff(contactId, groupIds)
        groupMembers.removeAll(toRemove.toSet())
        groupMembers.addAll(toAdd)
        return enqueueWrite { db -> db.withTransaction { applyGroupMembershipDiffInTransaction(db, toRemove, toAdd) } }
    }

    // ──────────────────────────────────────────────────────────
    //  ТЕГИ КОНТАКТОВ (плоский управляемый список, зеркало групп выше —
    //  в отличие от Contact.tags/allTags() выше, легаси свободного текста:
    //  тег здесь — Entity со своим id, есть у всех контактов сразу при
    //  переименовании, полноценный CRUD, опциональная категория) ──
    // ──────────────────────────────────────────────────────────

    /** Private DB-only helper (не enqueue) — используется addTag/renameTag/
     *  restoreTag (через enqueueWrite) И потенциально undoMerge, аналогично
     *  insertContactGroupInTransaction. */
    private suspend fun insertTagInTransaction(db: SocialsphereDatabase, tag: Tag) {
        db.contactDao().insertTag(tag.toEntity())
    }

    /** Создать тег; при совпадении имени (без регистра) возвращает существующий —
     *  точный аналог addGroup. */
    fun addTag(name: String, category: String? = null): Tag? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        tags.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        // BATCH 5 (последний подтверждённый дефект): после обычного точного
        // совпадения имени — семантическое совпадение ВСТРОЕННОГО seed-тега
        // через тот же resolver, которым пользуется restoreBackup(). Иначе на
        // RU-установке с тегом «Электрик» вызов addTag("Electrician") создавал
        // второй Tag, а restore затем считал эти же два имени ОДНИМ тегом.
        // Существующий тег возвращается как есть: ни id, ни отображаемое имя
        // (язык установки), ни категория не меняются.
        seedTagSemanticKey(clean)?.let { semanticKey ->
            tags.firstOrNull { seedTagSemanticKey(it.name) == semanticKey }?.let { return it }
        }
        val cleanCategory = category?.trim()?.takeIf { it.isNotBlank() }
        val t = Tag(generateId(), clean, cleanCategory, nowIso(), nowIso())
        tags.add(t)
        // BATCH 2C: конфликтует с deleteTag/undoMerge того же тега.
        enqueueWrite { db -> insertTagInTransaction(db, t) }
        return t
    }

    /** Переименовать/сменить категорию тега — правит ОДНУ запись Tag, видно у
     *  всех контактов сразу (связь через id, не копия текста). */
    fun renameTag(id: String, newName: String, newCategory: String?): Deferred<Unit> {
        val clean = newName.trim()
        if (clean.isBlank()) return CompletableDeferred(Unit)
        val idx = tags.indexOfFirst { it.id == id }
        if (idx < 0) return CompletableDeferred(Unit)
        val cleanCategory = newCategory?.trim()?.takeIf { it.isNotBlank() }
        val t = tags[idx].copy(name = clean, category = cleanCategory, updatedAt = nowIso())
        tags[idx] = t
        return enqueueWrite { db -> insertTagInTransaction(db, t) }
    }

    /** Удалить тег вместе со всем членством (контакты не трогаются). БЕЗ
     *  предупреждения — вызывающий UI-код сам должен показать диалог с числом
     *  затронутых контактов ДО вызова этой функции (тот же принцип, что deleteGroup).
     *  BATCH 2C (§15): удаление членства + удаление самого тега — одна
     *  транзакция одной writer-команды. */
    fun deleteTag(id: String): Deferred<Unit> {
        tags.removeAll { it.id == id }
        tagMembers.removeAll { it.tagId == id }
        return enqueueWrite { db ->
            db.withTransaction {
                db.contactDao().deleteTagMembersForTag(id)
                db.contactDao().deleteTag(id)
            }
        }
    }

    fun tagsOfContact(contactId: String): List<Tag> {
        val ids = tagMembers.filter { it.contactId == contactId }.map { it.tagId }.toSet()
        return tags.filter { it.id in ids }
    }

    fun contactIdsWithTag(tagId: String): Set<String> =
        tagMembers.filter { it.tagId == tagId }.map { it.contactId }.toSet()

    /** Union всех контактов, у которых есть хотя бы один тег данной категории. */
    fun contactIdsWithCategory(category: String): Set<String> {
        val tagIds = tags.filter { it.category == category }.map { it.id }.toSet()
        return tagMembers.filter { it.tagId in tagIds }.map { it.contactId }.toSet()
    }

    /** Diff чистая функция (без DB) — аналог contactGroupMembershipDiff. */
    private fun contactTagMembershipDiff(
        contactId: String, tagIds: Set<String>
    ): Pair<List<ContactTagMember>, List<ContactTagMember>> {
        val current = tagMembers.filter { it.contactId == contactId }
        val toRemove = current.filter { it.tagId !in tagIds }
        val toAdd = tagIds
            .filter { tid -> current.none { it.tagId == tid } }
            .map { tid -> ContactTagMember(generateId(), tid, contactId) }
        return toRemove to toAdd
    }

    /** Private DB-only helper — вызывается И из setContactTags() (enqueueWrite),
     *  И напрямую из undoMerge()'s собственной writer-команды (§21). */
    private suspend fun applyTagMembershipDiffInTransaction(
        db: SocialsphereDatabase, toRemove: List<ContactTagMember>, toAdd: List<ContactTagMember>
    ) {
        toRemove.forEach { db.contactDao().deleteTagMember(it.id) }
        if (toAdd.isNotEmpty()) db.contactDao().insertContactTagMembers(toAdd.map { it.toEntity() })
    }

    /** Полностью задать набор тегов контакта (диалог с чекбоксами) — точный
     *  аналог setContactGroups: diff toAdd/toRemove, повторный вызов с тем же
     *  набором не плодит дубли записей.
     *  BATCH 2C (§14): delete-old + insert-new — одна транзакция одной
     *  writer-команды (была: 2 отдельные DAO операции в НЕтранзакционном scope.launch). */
    fun setContactTags(contactId: String, tagIds: Set<String>): Deferred<Unit> {
        val (toRemove, toAdd) = contactTagMembershipDiff(contactId, tagIds)
        tagMembers.removeAll(toRemove.toSet())
        tagMembers.addAll(toAdd)
        return enqueueWrite { db -> db.withTransaction { applyTagMembershipDiffInTransaction(db, toRemove, toAdd) } }
    }

    /** Отсортированный список уникальных непустых категорий среди существующих тегов. */
    fun distinctCategories(): List<String> =
        tags.mapNotNull { it.category?.trim()?.takeIf { s -> s.isNotBlank() } }
            .distinct().sortedBy { it.lowercase() }

    /** Сидирование стандартного набора тегов (v18, 2026-07-28) — ровно один раз
     *  за всю жизнь установки, и только если тегов ещё вообще нет (не «ever
     *  seeded», а «isEmpty сейчас» — то есть если владелец успел создать и
     *  удалить руками все теги до первого прогона этой функции, случайно она
     *  не сработает: AppSettings.defaultTagsSeeded уже true к тому моменту).
     *  Названия/категории — через строковые ресурсы (локализация 3 языков). */
    /** Стартовый набор тегов (2026-07-11, прямой запрос владельца): не «статус
     *  в CRM» (VIP/клиент/партнёр — это уже есть через RelationshipType), а
     *  РОД ЗАНЯТИЙ/УСЛУГА человека — «кто чем занимается» (электрик, продаёт
     *  масло, сдаёт квартиру...), чтобы находить одним словом по фильтру. */
    // BATCH 2D (D-003): раньше 15х addTag() — каждый своя ОТДЕЛЬНАЯ enqueued
    // команда — а defaultTagsSeeded взводился СРАЗУ после того, как эти
    // команды были только ПОСТАВЛЕНЫ в очередь, не после реального commit'а.
    // Смерть процесса между постановкой в очередь и коммитом оставляла бы
    // часть сида невыполненной в БД с уже взведённым флагом — сид пропустился
    // бы навсегда. Теперь: план строится ОДИН раз в памяти, применяется И в
    // памяти, И в БД одной writer-командой/одной транзакцией, флаг — только
    // после успешного await(). Идемпотентность не полагается на один флаг:
    // защита от дублей — проверка tags.isNotEmpty() НИЖЕ (тот же признак, что
    // и раньше) — если сид уже прошёл (или владелец создал теги руками),
    // функция вообще не строит план заново, при любом состоянии флага.
    suspend fun seedDefaultTagsIfNeeded(context: Context) {
        if (AppSettings.defaultTagsSeeded.value) return
        if (tags.isNotEmpty()) { AppSettings.defaultTagsSeeded.value = true; return }
        val now = nowIso()
        // Имена и категории берём из SEED_TAG_DEFINITIONS — единственного
        // реестра встроенных тегов. Раньше те же 15 тегов были перечислены
        // дважды — здесь и отдельно для resolver'а семантической
        // идентичности, и новый встроенный тег, добавленный только в один
        // список, молча выпадал бы из другого механизма.
        val defaultTags = SEED_TAG_DEFINITIONS.map { def ->
            Tag(generateId(), context.getString(def.nameRes), context.getString(def.categoryRes), now, now)
        }
        // BATCH 2E (2E-002): DB commit СНАЧАЛА — in-memory tags и флаг только
        // ПОСЛЕ подтверждённого await(). Раньше tags.addAll(defaultTags)
        // выполнялось ДО await() — при сбое транзакции идемпотентная защита
        // ВЫШЕ (tags.isNotEmpty()) на следующем запуске увидела бы "теги уже
        // есть" (они в памяти, хоть и не в Room) и взвела бы флаг, хотя Room
        // осталась пустой — сид пропустился бы навсегда.
        enqueueWrite { db -> db.withTransaction { defaultTags.forEach { insertTagInTransaction(db, it) } } }.await()
        tags.addAll(defaultTags)
        AppSettings.defaultTagsSeeded.value = true
    }

    /** Одно определение встроенного (seed) тега: СТАБИЛЬНЫЙ семантический
     *  ключ (имя самого ресурса — не зависит ни от локали, ни от UUID
     *  конкретной установки) плюс ресурсы отображаемого имени и
     *  категории. В БД семантический ключ НЕ хранится (схема Room не
     *  менялась) — он выводится из имени по ресурсам во время работы. */
    internal data class SeedTagDefinition(
        val semanticKey: String,
        val nameRes: Int,
        val categoryRes: Int
    )

    /** ЕДИНСТВЕННЫЙ продакшн-реестр встроенных тегов. Потребители:
     *  1) сам сид (seedDefaultTagsIfNeeded выше);
     *  2) resolver семантической идентичности (seedTagSemanticKey — его
     *     зовут addTag() и restoreBackup());
     *  3) тест масштабируемости.
     *  Новый встроенный тег добавляется РОВНО здесь (плюс строковые
     *  ресурсы) — второго списка, который надо синхронизировать вручную,
     *  больше нет.
     *  internal (не private) — тест перебирает именно этот список, поэтому
     *  новый тег попадает под проверку сам, без правки теста. */
    internal val SEED_TAG_DEFINITIONS: List<SeedTagDefinition> = listOf(
        SeedTagDefinition("seed_tag_electrician", R.string.seed_tag_electrician, R.string.seed_category_home_services),
        SeedTagDefinition("seed_tag_plumber", R.string.seed_tag_plumber, R.string.seed_category_home_services),
        SeedTagDefinition("seed_tag_handyman", R.string.seed_tag_handyman, R.string.seed_category_home_services),
        SeedTagDefinition("seed_tag_psychologist", R.string.seed_tag_psychologist, R.string.seed_category_home_services),
        SeedTagDefinition("seed_tag_massage_therapist", R.string.seed_tag_massage_therapist, R.string.seed_category_home_services),
        SeedTagDefinition("seed_tag_tutor", R.string.seed_tag_tutor, R.string.seed_category_home_services),
        SeedTagDefinition("seed_tag_driver", R.string.seed_tag_driver, R.string.seed_category_home_services),
        SeedTagDefinition("seed_tag_accountant", R.string.seed_tag_accountant, R.string.seed_category_finance),
        SeedTagDefinition("seed_tag_financial_advisor", R.string.seed_tag_financial_advisor, R.string.seed_category_finance),
        SeedTagDefinition("seed_tag_realtor", R.string.seed_tag_realtor, R.string.seed_category_real_estate),
        SeedTagDefinition("seed_tag_landlord", R.string.seed_tag_landlord, R.string.seed_category_real_estate),
        SeedTagDefinition("seed_tag_tenant", R.string.seed_tag_tenant, R.string.seed_category_real_estate),
        SeedTagDefinition("seed_tag_programmer", R.string.seed_tag_programmer, R.string.seed_category_it),
        SeedTagDefinition("seed_tag_seller", R.string.seed_tag_seller, R.string.seed_category_trade),
        SeedTagDefinition("seed_tag_fishing", R.string.seed_tag_fishing, R.string.seed_category_hobby)
    )

    /** Значения ресурсов в рамках процесса не меняются, а построение карты
     *  дёргает createConfigurationContext по разу на (ресурс × локаль) —
     *  считаем один раз. Кэшируется ТОЛЬКО успешно построенная карта: пустую
     *  (appContext ещё не задан) кэшировать нельзя, иначе resolver навсегда
     *  останется слепым. */
    private var seedTagAliasCache: Map<String, String>? = null

    /**
     * BATCH 5 (последний подтверждённый дефект): «нормализованное локализованное
     * имя -> семантический ключ» для встроенных seed-тегов ВО ВСЕХ
     * поддерживаемых локалях разом — независимо от текущего языка устройства.
     *
     * Ни языки, ни сами теги здесь НЕ дублируются: теги берутся из
     * SEED_TAG_DEFINITIONS (единственный реестр встроенных тегов, из которого
     * работает и сам сид), а варианты имён — через utils.localizedVariants(),
     * который перебирает AppLanguage — канонический реестр языков
     * приложения (тот же механизм уже решает ровно эту задачу для
     * CalendarItemType.labelKey(): узнать «запечённое» значение вне
     * зависимости от локали). Поэтому новый язык = новый AppLanguage +
     * values-xx/strings.xml, а новый встроенный тег = одна строка в
     * SEED_TAG_DEFINITIONS; ни addTag(), ни restoreBackup(), ни сам алгоритм
     * сопоставления править не требуется.
     *
     * НАМЕРЕННО ограничено именами из SEED_TAG_DEFINITIONS. Никакого
     * fuzzy-сопоставления и никакого словаря переводов произвольных
     * пользовательских тегов здесь нет: «Investor»/«Инвестор», «Doctor»/«Врач» —
     * обычные пользовательские теги и остаются разными.
     *
     * PRODUCT RULE (записано явно, потому что схема Room НЕ хранит
     * origin/systemKey и происхождение строки по ней не восстановить):
     * ЛЮБОЙ перевод имени встроенного тега — ЗАРЕЗЕРВИРОВАННЫЙ алиас.
     * То есть если на RU-установке с встроенной «Рыбалкой» пользователь сам
     * введёт «Fishing», он получит ИМЕННО её, а не второй тег — решение
     * осознанное: именно оно гасит задвоение встроенных тегов при смене
     * языка и даёт ОДИНАКОВУЮ семантику на всех путях (addTag/restoreBackup).
     * Обратная сторона правила: свой ОТДЕЛЬНЫЙ тег с именем, совпадающим с
     * переводом встроенного, завести нельзя. Если когда-нибудь потребуется
     * разрешить и такое — нужно будет хранить origin/systemKey в схеме
     * (сейчас намеренно не делается, схема Room не менялась).
     */
    private fun seedTagAliases(): Map<String, String> {
        seedTagAliasCache?.let { return it }
        val context = appContext ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        SEED_TAG_DEFINITIONS.forEach { def ->
            com.aistudio.socialsphere.crmlxb.utils.localizedVariants(context, def.nameRes).forEach { variant ->
                result[variant.trim().lowercase()] = def.semanticKey
            }
        }
        seedTagAliasCache = result
        return result
    }

    /**
     * ЕДИНЫЙ resolver семантической идентичности встроенного тега: любое его
     * локализованное имя -> стабильный семантический ключ ресурса
     * («seed_tag_electrician»), либо null для обычного пользовательского тега.
     *
     * Один механизм на оба пути, которые обязаны понимать идентичность
     * одинаково — addTag() (создание) и restoreBackup() (восстановление).
     * Раньше addTag() сравнивал только текст имени, поэтому на RU-установке с
     * тегом «Электрик» вызов addTag("Electrician") создавал ВТОРУЮ строку, а
     * restore тех же двух имён считал их одним тегом — прямое противоречие.
     */
    internal fun seedTagSemanticKey(name: String): String? =
        seedTagAliases()[name.trim().lowercase()]

    // ── Свои типы отношений («статусы») — фидбэк владельца 2026-07-05: создаёт
    // свой тип, а он «не входит в фильтры, не ищется, не редактируется, не
    // удаляется» — customRelationshipType был голым текстовым полем без CRUD,
    // в отличие от групп. Добавляем тот же набор операций, что и для групп. ──

    /** Отсортированный список уникальных пользовательских типов отношений в использовании. */
    fun distinctCustomRelationshipTypes(): List<String> =
        contacts.mapNotNull { it.customRelationshipType?.trim()?.takeIf { s -> s.isNotBlank() } }
            .distinct().sortedBy { it.lowercase() }

    // BATCH 5C (5C-001): allTags() (легаси Contact.tags свободного текста)
    // удалена вместе с её единственным потребителем — параллельной legacy-
    // секцией фильтра в ContactsScreen/DuplicatesScreen. Managed Tag +
    // ContactTagMember (tagsOfContact/distinctCategories ниже) — единственный
    // runtime-источник тегов; legacy-поле остаётся только входом
    // совместимости и приводится к канону reconcileLegacyTagsIntoManaged().

    /** Разовая миграция легаси Contact.tags (свободный текст, поле больше не
     *  редактируется никаким UI) в управляемую систему Tag/tagMembers — иначе
     *  теги, которые владелец успел ввести ДО фикса 2026-08-18, остались бы
     *  навсегда невидимыми (данные в БД есть, но ни одна экран их не показывает).
     *  Каждое уникальное значение (без учёта регистра) становится одним Tag,
     *  каждый контакт с этим значением получает членство — как ручное
     *  добавление тега через "+ Тег", просто одним проходом при первом запуске
     *  после обновления. */
    // BATCH 2D (D-002): раньше вызывала ПУБЛИЧНЫЕ addTag()/setContactTags() —
    // каждая своя ОТДЕЛЬНАЯ enqueued DB-команда, — а legacyContactTagsMigrated
    // взводился СРАЗУ после того, как эти команды были только ПОСТАВЛЕНЫ в
    // очередь, не после реального commit'а. Смерть процесса между постановкой
    // в очередь и коммитом оставляла бы часть миграции невыполненной в БД с
    // уже взведённым флагом — следующий запуск НАВСЕГДА пропускал бы миграцию.
    // Теперь: план строится ЧИСТО в памяти (без DB — новые Tag те же, что
    // раньше давал бы addTag(): дедуп по имени без учёта регистра), применяется
    // И в памяти, И в БД одной writer-командой/одной транзакцией, флаг — только
    // после успешного await(). Идемпотентно на повтор: currentIds.add(...)
    // возвращает false для тега, уже являющегося членством контакта (в памяти
    // это видно сразу после первого успешного прохода — новых Tag/Member не
    // создаётся повторно).
    /**
     * BATCH 5 (5-001): ИСТОРИЧЕСКАЯ одноразовая миграция старой установленной
     * базы. Только она владеет защёлкой [AppSettings.legacyContactTagsMigrated];
     * саму работу делает [reconcileLegacyTagsIntoManaged].
     *
     * Защёлка НЕ должна блокировать реконсиляцию ВХОДЯЩИХ данных (restore,
     * импорт) — это разные понятия. Раньше они были одним целым, и это давало
     * P1-баг: на чистой переустановке первый холодный старт видел пустую базу,
     * план миграции получался пустым, флаг взводился навсегда — а теги из
     * восстановленного следом бэкапа оставались легаси-только уже никогда не
     * мигрируя. Теперь входящие данные реконсилируются по факту их появления
     * (см. вызовы в restoreBackup и в импорте), независимо от этого флага.
     */
    suspend fun migrateLegacyContactTagsIfNeeded() {
        if (AppSettings.legacyContactTagsMigrated.value) return
        reconcileLegacyTagsIntoManaged()
        // BATCH 2E-гарантия сохранена: флаг ставится только после того, как
        // реконсиляция реально закоммитила (или ей нечего было коммитить) —
        // при сбое транзакции исключение уйдёт наверх и флаг не взведётся.
        AppSettings.legacyContactTagsMigrated.value = true
    }

    /**
     * BATCH 5 (5-001): приведение легаси-тегов ([Contact.tags]) к канонической
     * управляемой системе (Tag + ContactTagMember). Идемпотентна: повторный
     * вызов не создаёт ни дублей тегов, ни дублей членства — имя тега ищется
     * среди уже существующих без учёта регистра, членство добавляется только
     * если его ещё нет.
     *
     * Легаси-поле НЕ очищается: оно остаётся только как совместимость/вход
     * (старые бэкапы, vCard-заметки), но каноническим состоянием рантайма
     * больше не является.
     *
     * Вызывается: исторической миграцией при старте, после восстановления
     * бэкапа и после импорта — то есть всюду, где в приложении могли появиться
     * легаси-теги.
     */
    suspend fun reconcileLegacyTagsIntoManaged() {
        val newTags = mutableListOf<Tag>()
        val newMembers = mutableListOf<ContactTagMember>()
        val now = nowIso()
        // BATCH 5 (§64): членство группируем по contactId ОДИН раз, а не зовём
        // tagsOfContact() внутри цикла по контактам (это был полный проход по
        // tagMembers на каждый контакт). Имена тегов — тоже одна карта.
        val memberTagIdsByContact = tagMembers.groupBy { it.contactId }
            .mapValues { (_, v) -> v.map { it.tagId }.toMutableSet() }
        val tagIdByLowerName = tags.associateTo(mutableMapOf()) { it.name.lowercase() to it.id }
        // BATCH 5B (5B-001): контакты, чьё легаси-поле обработано и подлежит
        // гашению. Собирается для ЛЮБОГО контакта с непустым Contact.tags, а не
        // только для тех, кому реально создали членство: если членство уже
        // существует, поле всё равно потреблено — иначе оно продолжит
        // пересоздавать снятый пользователем тег при каждом следующем импорте.
        val contactIdsToConsume = mutableListOf<String>()
        contacts.forEach { c ->
            if (c.tags.isEmpty()) return@forEach
            contactIdsToConsume.add(c.id)
            val currentIds = memberTagIdsByContact[c.id]?.toMutableSet() ?: mutableSetOf()
            c.tags.forEach { legacyName ->
                val clean = legacyName.trim()
                if (clean.isBlank()) return@forEach
                // Регистронезависимое совпадение по имени — существующее правило
                // управляемой системы, не меняем (§55). Пустые/пробельные имена
                // отсеяны выше, поэтому фиктивных тегов не возникает (§53).
                val tagId = tagIdByLowerName.getOrPut(clean.lowercase()) {
                    Tag(generateId(), clean, null, now, now).also { newTags.add(it) }.id
                }
                if (currentIds.add(tagId)) {
                    newMembers.add(ContactTagMember(generateId(), tagId, c.id))
                }
            }
        }
        // BATCH 2E (2E-001, §4 early-exit rule): этот early-exit безопасен
        // ИМЕННО потому, что newTags/newMembers посчитаны из in-memory tags/
        // tagMembers, а эти списки мутируются ТОЛЬКО после подтверждённого
        // commit'а (или не мутируются вообще, если commit'а не было). Поэтому
        // "план пуст" здесь означает "уже commit'нуто" на durable-основании,
        // а не на оптимистичной догадке — лишний DB-read не нужен.
        // BATCH 5: флаг здесь больше не трогается — им владеет
        // migrateLegacyContactTagsIfNeeded(); эта функция идемпотентна и
        // безопасна для повторного вызова на любых входящих данных.
        // BATCH 5B: выходим, только если делать нечего ВООБЩЕ — включая гашение
        // легаси-поля. Раньше выход происходил при пустом плане тегов, и поле
        // оставалось жить, воскрешая снятые пользователем теги.
        if (newTags.isEmpty() && newMembers.isEmpty() && contactIdsToConsume.isEmpty()) return
        // BATCH 2E (2E-001): DB commit СНАЧАЛА — in-memory tags/tagMembers и
        // флаг ставятся ТОЛЬКО после подтверждённого await(). Раньше
        // tags.addAll()/tagMembers.addAll() выполнялись ДО await() — при сбое
        // транзакции in-memory состояние "выглядело смигрированным"
        // (currentIds.add(...) на повторном вызове видел бы это membership уже
        // существующим), функция решила бы, что делать нечего, и НАВСЕГДА
        // взвела бы флаг — хотя Room так и не закоммитила ничего. Теперь при
        // сбое: Room не тронута, tags/tagMembers не тронуты, повторный вызов
        // строит план заново с нуля корректно (retry safe).
        // BATCH 5B (5B-001): создание тегов/членства И гашение потреблённого
        // легаси-поля — ОДНА транзакция. Иначе возможен разрыв: теги записаны,
        // поле не погашено (или наоборот) — и то и другое возвращает
        // воскрешение либо теряет данные.
        enqueueWrite { db ->
            db.withTransaction {
                newTags.forEach { insertTagInTransaction(db, it) }
                if (newMembers.isNotEmpty()) db.contactDao().insertContactTagMembers(newMembers.map { it.toEntity() })
                if (contactIdsToConsume.isNotEmpty()) db.contactDao().clearLegacyTagsForContacts(contactIdsToConsume)
            }
        }.await()
        tags.addAll(newTags)
        tagMembers.addAll(newMembers)
        // Память — только после подтверждённого коммита, тем же правилом, что и
        // выше. Легаси-поле гасим и в памяти, иначе следующий вызов построит
        // план из устаревшего снимка.
        val consumed = contactIdsToConsume.toSet()
        if (consumed.isNotEmpty()) {
            contacts.forEachIndexed { i, c ->
                if (c.id in consumed && c.tags.isNotEmpty()) contacts[i] = c.copy(tags = emptyList())
            }
        }
    }

    /**
     * BATCH 5B (5B-005): детерминированный барьер завершения записей.
     *
     * Единственный consumer очереди выполняет команды строго по порядку и не
     * начинает следующую, пока предыдущая полностью не вернулась. Поэтому
     * ожидание ПУСТОЙ команды, поставленной последней, означает, что все ранее
     * поставленные записи уже закоммичены. Нужен там, где вызывающий обязан
     * вернуть управление только после durable-состояния, но сам мог не
     * поставить ни одной записи (например импорт без единого тега — там
     * реконсиляция уходит на ранний выход и не даёт точки ожидания).
     *
     * Не delay(), не второй писатель. Звать только СНАРУЖИ команды писателя:
     * изнутри уже выполняющейся команды это самоблокировка (§41).
     */
    suspend fun awaitPendingWrites() {
        enqueueWrite { }.await()
    }

    /** Переименовать свой тип отношений ВЕЗДЕ, где он используется (все контакты разом). */
    fun renameCustomRelationshipType(oldName: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank() || clean == oldName) return
        contacts.filter { it.customRelationshipType == oldName }.forEach {
            updateContact(it, it.copy(customRelationshipType = clean))
        }
    }

    /** Удалить свой тип отношений: у всех контактов с этим значением он сбрасывается
     *  (relationshipType остаётся как есть — обычно OTHER, контакт не теряется). */
    fun deleteCustomRelationshipType(name: String) {
        contacts.filter { it.customRelationshipType == name }.forEach {
            updateContact(it, it.copy(customRelationshipType = null))
        }
    }

    /**
     * Преобразование контакта в компанию (фидбэк владельца: «много контактов,
     * которые на самом деле компании»). Телефоны/email/адреса переезжают в
     * компанию (новые id — чужие PK не переиспользуем), заметки пере-привязываются
     * ДО deleteContact (его каскад чистит notes по contactId). Контакт удаляется.
     * @return id созданной компании или null (контакт не найден / пустое имя).
     */
    // ФИКС (аудит 2026-07-06): раньше переносились ТОЛЬКО phones/emails/addresses/
    // notes, а фото, привязанные события календаря и т.д. молча пропадали вместе
    // с удалённым контактом — диалог подтверждения предупреждал не обо всём.
    // Company НЕ имеет полей под messengers/gifts/personalDetails/sizeInfo/tags/
    // familyNote/profession/contactRelations/группы (это личные, не деловые
    // понятия — добавлять их значило бы превращать Company в клон Contact), так
    // что эти поля переносить некуда — они по-прежнему теряются, но теперь
    // честно и полностью перечислены в тексте подтверждения (cd_convert_company_text).
    // Перенесены дополнительно: фото → логотип компании (поле уже существовало,
    // просто не заполнялось), ссылки календарных событий на контакт → на компанию
    // (тот же паттерн ретаргетинга, что при слиянии дублей, см. mergeContacts).
    // ФИКС (аудит 2026-08-11, жалоба «опять пропали все заметки после
    // обновления»): раньше company-insert / note-reassign / calendar-relink /
    // contact-delete шли ПЯТЬЮ независимыми scope.launch без объединяющей
    // транзакции (через addCompany/updateCompany/updateNote/updateCalendarItem/
    // deleteContact) — если процесс убивался между записью reassign'а заметки
    // и записью удаления контакта (например, ровно во время обновления
    // приложения), deleteNotesForContact(contactId) внутри deleteContact мог
    // снести заметку, которая должна была уже переехать на компанию. Тот же
    // класс гонки, что mergeContacts()/mergeCompanies() уже закрыли
    // db.withTransaction — здесь применяем тот же паттерн: in-memory мутации
    // синхронны как везде, а ВСЯ запись в БД — одна атомарная транзакция.
    fun convertContactToCompany(contactId: String): String? {
        val c = getContact(contactId) ?: return null
        val name = listOfNotNull(c.firstName, c.middleName, c.lastName)
            .joinToString(" ").trim()
            .ifBlank { c.nickname?.trim().orEmpty() }
        if (name.isBlank()) return null
        val companyId = generateId()
        val company = Company(
            id = companyId,
            name = name,
            logoUri = c.photoUri,
            industry = Industry.OTHER,
            phones = c.phones.map { it.copy(id = generateId(), contactId = companyId, isPrimary = false) },
            emails = c.emails.map { it.copy(id = generateId(), contactId = companyId, isPrimary = false) },
            addresses = c.addresses.map {
                it.copy(id = generateId(), ownerId = companyId, ownerType = AddressOwnerType.COMPANY)
            },
            createdAt = nowIso(), updatedAt = nowIso()
        )
        val reassignedNotes = notes.filter { it.contactId == contactId }.toList()
            .map { it.copy(contactId = null, companyId = companyId) }
        // Ссылки событий: targetType CONTACT/contactId → COMPANY/companyId.
        val calendarUpdates = calendarItems.filter { ci -> ci.links.any { it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId } }
            .toList().map { ci ->
                val newLinks = ci.links.map {
                    if (it.targetType == CalendarTargetType.CONTACT && it.targetId == contactId)
                        it.copy(targetType = CalendarTargetType.COMPANY, targetId = companyId)
                    else it
                }.distinctBy { it.targetType to it.targetId }
                ci.copy(links = newLinks)
            }
        // Каскад удаления контакта (как в deleteContact) — id связей считаем
        // ДО мутаций в памяти.
        val relationIds = contactRelations
            .filter { it.firstContactId == contactId || it.secondContactId == contactId }
            .map { it.id }

        // ── применяем в памяти (синхронно, как везде в этом файле) ──
        companies.add(company)
        addresses.addAll(company.addresses)
        reassignedNotes.forEach { n -> val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n }
        calendarUpdates.forEach { ci -> val i = calendarItems.indexOfFirst { it.id == ci.id }; if (i >= 0) calendarItems[i] = ci }
        contacts.removeAll { it.id == contactId }
        gifts.removeAll { it.contactId == contactId }
        sizeInfos.removeAll { it.contactId == contactId }
        personalDetails.removeAll { it.contactId == contactId }
        addresses.removeAll { it.ownerId == contactId && it.ownerType == AddressOwnerType.CONTACT }
        companyRelations.removeAll { it.contactId == contactId }
        contactRelations.removeAll { it.firstContactId == contactId || it.secondContactId == contactId }
        groupMembers.removeAll { it.contactId == contactId }
        tagMembers.removeAll { it.contactId == contactId }

        // ── одна транзакция на всю конвертацию: либо всё, либо (при сбое) ничего ──
        // BATCH 2B: единственная функция, трогающая ОБА агрегата разом —
        // с единым глобальным writer'ом (вместо Batch 2's двух вложенных
        // per-type диспетчеров) это теперь просто одна enqueueWrite, как и
        // везде: сериализовано относительно ЛЮБОЙ другой Contact/Company/
        // Calendar записи автоматически, без специального порядка захвата.
        enqueueWrite { db ->
            db.withTransaction {
                db.companyDao().insertCompany(company.toEntity())
                if (company.addresses.isNotEmpty()) db.addressDao().insertAddresses(company.addresses.map { it.toEntity() })
                if (company.phones.isNotEmpty())    db.contactDao().insertPhones(company.phones.map { it.toCompanyEntity(company.id) })
                if (company.emails.isNotEmpty())    db.contactDao().insertEmails(company.emails.map { it.toCompanyEntity(company.id) })

                // Реассайн заметок ДО удаления контакта — иначе deleteNotesForContact
                // ниже снёс бы их (см. комментарий над функцией).
                if (reassignedNotes.isNotEmpty()) db.noteDao().insertNotes(reassignedNotes.map { it.toEntity() })

                calendarUpdates.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }

                db.contactDao().deleteGroupMembersForContact(contactId)
                db.contactDao().deleteTagMembersForContact(contactId)
                db.contactDao().deleteContact(contactId)
                db.contactDao().deletePhonesForContact(contactId)
                db.contactDao().deleteEmailsForContact(contactId)
                db.contactDao().deleteMessengersForContact(contactId)
                db.contactDao().deleteCompanyRelationsForContact(contactId)
                db.contactDao().deletePersonalDetailsForContact(contactId)
                db.contactDao().deleteSizeInfoForContact(contactId)
                relationIds.forEach { db.contactDao().deleteContactRelation(it) }
                db.addressDao().deleteAddressesForOwner(contactId, AddressOwnerType.CONTACT.name)
                db.noteDao().deleteNotesForContact(contactId)
                db.giftDao().deleteGiftsForContact(contactId)
            }
        }
        return companyId
    }

    // ──────────────────────────────────────────────────────────
    //  COMPANIES CRUD
    // ──────────────────────────────────────────────────────────
    fun getCompanyById(id: String): Company? = companies.find { it.id == id }
    fun getCompany(id: String): Company?     = getCompanyById(id)

    // ЕДИНАЯ нормализация имени компании для дедупа (trim + ignoreCase) —
    // раньше три экрана (WorkplaceComponents, ContactEditScreen, ImportScreens)
    // дублировали это сравнение независимо, а CompanyEditScreen его не делал
    // вовсе, из-за чего «Электрик» и «электрик » превращались в две компании.
    fun findCompanyByName(name: String): Company? =
        companies.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun addCompany(company: Company): Deferred<Unit> {
        val c = company.copy(createdAt = nowIso(), updatedAt = nowIso())
        companies.add(c)
        // OWNERSHIP PACK #1: как addContact — адреса новой компании сразу попадают в
        // ГЛОБАЛЬНЫЙ addresses (его читают карта/геокодер и setAddressCoords); раньше
        // они появлялись там только после перезапуска, и координаты, посчитанные
        // геокодером для только что созданной компании, молча не записывались.
        addresses.addAll(c.addresses)
        // BATCH 2 (data integrity): симметрично addContact — см. §56 SOCIALSPHERE_KNOWLEDGE.md.
        return enqueueWrite { db -> db.withTransaction { addCompanyDb(c) } }
    }

    /**
     * OWNERSHIP PACK #1: адреса компании имеют независимых писателей —
     * setAddressCoords (координаты) и linkContactAddressToCompany (создаёт адрес
     * офиса, пока у редактора компании на руках уже устаревший снимок). Поэтому:
     *  • координаты канонического адреса не стираются устаревшим снимком
     *    (withCanonicalCoords);
     *  • канонический адрес, которого нет в edited, СОХРАНЯЕТСЯ, если его не было и в base
     *    (создан независимо после открытия редактора) — явное удаление = «был в base,
     *    нет в edited» (в Pack #1 это передавалось набором removedAddressIds).
     * PERSISTENCE PACK #2 (2026-09-21): тот же контракт, что у [updateContact] — вызывающий
     * передаёт [base] (снимок, от которого начата правка) и [edited]; записывается разница
     * edited−base поверх канонического состояния. Поэтому:
     *  • скаляр, который правила другая операция после открытия редактора, не откатывается
     *    (S1 сохранил имя, S2 позже — описание: оба выживают);
     *  • телефоны/email/адреса сливаются по id: удаление = был в base и нет в edited
     *    (заменило [removedAddressIds] из Pack #1), новое независимо добавленное сохраняется.
     */
    fun updateCompany(base: Company, edited: Company): Deferred<Unit> {
        val canonical = companies.find { it.id == edited.id } ?: return CompletableDeferred(Unit)
        // Канонический адрес — глобальный addresses (его независимо создаёт
        // linkContactAddressToCompany и дополняет координатами setAddressCoords).
        val canonicalAddresses = canonicalAddresses(AddressOwnerType.COMPANY, edited.id)
        return writeCompany(mergeCompanyEdit(canonical, canonicalAddresses, base, edited))
    }

    /** Слияние правки компании в каноническое состояние — см. [updateCompany]. Чистая функция. */
    internal fun mergeCompanyEdit(canonical: Company, canonicalAddresses: List<Address>, base: Company, edited: Company): Company {
        fun <T> pick(b: T, e: T, c: T): T = if (e == b) c else e
        return edited.copy(
            name = pick(base.name, edited.name, canonical.name),
            logoUri = pick(base.logoUri, edited.logoUri, canonical.logoUri),
            industry = pick(base.industry, edited.industry, canonical.industry),
            description = pick(base.description, edited.description, canonical.description),
            website = pick(base.website, edited.website, canonical.website),
            createdAt = pick(base.createdAt, edited.createdAt, canonical.createdAt),
            phones = mergeById(canonical.phones, base.phones, edited.phones) { it.id },
            emails = mergeById(canonical.emails, base.emails, edited.emails) { it.id },
            addresses = mergeById(canonicalAddresses, base.addresses, edited.addresses) { it.id },
        )
    }

    private fun writeCompany(company: Company): Deferred<Unit> {
        val c = company.copy(
            addresses = withCanonicalCoords(company.addresses, AddressOwnerType.COMPANY, company.id),
            updatedAt = nowIso(),
        )
        val idx = companies.indexOfFirst { it.id == c.id }
        if (idx >= 0) {
            companies[idx] = c
            // Симметрично контактам: глобальный addresses для карты
            addresses.removeAll { it.ownerId == c.id && it.ownerType == AddressOwnerType.COMPANY }
            addresses.addAll(c.addresses)
            // BATCH 2/2B (data integrity): атомарная запись через единый
            // глобальный writer — раньше delete-then-reinsert шёл без транзакции.
            return enqueueWrite { db ->
                db.withTransaction {
                    db.addressDao().deleteAddressesForOwner(c.id, AddressOwnerType.COMPANY.name)
                    db.companyDao().deletePhonesForCompany(c.id)
                    db.companyDao().deleteEmailsForCompany(c.id)
                    addCompanyDb(c)
                }
            }
        }
        return CompletableDeferred(Unit)
    }

    /**
     * Восстановление компании из снапшота отмены слияния (mergeCompanies) БЕЗ
     * перезаписи дат createdAt/updatedAt — аналог restoreContact. Idempotent:
     * чистит детей компании, потом вставляет как есть.
     */
    /** Private DB-only helper (не enqueue) — используется restoreCompany() И
     *  напрямую из undoMergeCompanies()'s собственной writer-команды (§21). */
    private suspend fun restoreCompanyInTransaction(db: SocialsphereDatabase, company: Company) {
        db.companyDao().deletePhonesForCompany(company.id)
        db.companyDao().deleteEmailsForCompany(company.id)
        db.addressDao().deleteAddressesForOwner(company.id, AddressOwnerType.COMPANY.name)
        addCompanyDb(company)
    }

    fun restoreCompany(company: Company): Deferred<Unit> {
        val idx = companies.indexOfFirst { it.id == company.id }
        if (idx >= 0) companies[idx] = company else companies.add(company)
        addresses.removeAll { it.ownerId == company.id && it.ownerType == AddressOwnerType.COMPANY }
        addresses.addAll(company.addresses)
        // BATCH 2/2B (data integrity): тот же паттерн, что restoreContact.
        return enqueueWrite { db -> db.withTransaction { restoreCompanyInTransaction(db, company) } }
    }

    fun deleteCompany(companyId: String): Deferred<Unit> {
        // EXPLICIT RECOVERY PACK: одна отметка времени на само удаление + его tombstone.
        val deletedAt = nowIso()
        val tombstone = recordTombstone(TombstoneEntityType.COMPANY, companyId, deletedAt)
        // Каскад (баг §35, найден повторным аудитом): раньше связи с контактами
        // и ссылки календарных событий на удаляемую компанию оставались сиротами
        // — тем же классом бага, что deleteContact уже чинил для контактов.
        // BATCH 2B (Confirmed Problem B, симметрично deleteContact): calendar-
        // link cleanup сворачивается в ТУ ЖЕ транзакцию, что удаление компании,
        // а не идёт через отдельную enqueued команду (публичный updateCalendarItem).
        val affectedCalendarItems = calendarItems
            .filter { ci -> ci.links.any { it.targetType == CalendarTargetType.COMPANY && it.targetId == companyId } }
            .toList()
            .map { ci ->
                ci.copy(
                    links = ci.links.filterNot { it.targetType == CalendarTargetType.COMPANY && it.targetId == companyId },
                    updatedAt = nowIso()
                )
            }
        affectedCalendarItems.forEach { ci ->
            val i = calendarItems.indexOfFirst { it.id == ci.id }
            if (i >= 0) calendarItems[i] = ci
        }
        val employeeIds = companyRelations.filter { it.companyId == companyId }.map { it.contactId }.toSet()
        companies.removeAll { it.id == companyId }
        addresses.removeAll { it.ownerId == companyId && it.ownerType == AddressOwnerType.COMPANY }
        companyRelations.removeAll { it.companyId == companyId }
        // OWNERSHIP PACK #1: вложенный Contact.companyRelations сотрудников — проекция
        // этого списка; без пересборки любой следующий updateContact воскрешал связь с
        // удалённой компанией (строка снова появлялась в Room).
        resyncNestedCompanyRelations(employeeIds)
        // ФИКС (аудит 2026-08-11): notes удалялись из БД, но не из in-memory
        // списка — «заметка-призрак» в UI до перезапуска приложения.
        notes.removeAll { it.companyId == companyId }
        // BATCH 2/2B (data integrity): весь каскад удаления, включая calendar-
        // link cleanup — одна атомарная транзакция через единый глобальный writer.
        return enqueueWrite { db ->
            db.withTransaction {
                affectedCalendarItems.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }
                db.companyDao().deleteCompany(companyId)
                db.addressDao().deleteAddressesForOwner(companyId, AddressOwnerType.COMPANY.name)
                db.companyDao().deletePhonesForCompany(companyId)
                db.companyDao().deleteEmailsForCompany(companyId)
                db.noteDao().deleteNotesForCompany(companyId)
                db.contactDao().deleteCompanyRelationsForCompany(companyId)
                db.tombstoneDao().insert(tombstone.toEntity())
            }
        }
    }

    /**
     * «Это адрес компании» (2026-07-23, решение владельца): контакт помечает
     * свой рабочий адрес как адрес компании, где он работает. Ищем среди уже
     * существующих адресов компании совпадение по нормализованной улице+городу
     * (та же логика, что и дедуп при слиянии компаний, см. mergeCompanies) —
     * если есть, просто возвращаем его id, НЕ плодя копию; если нет, заводим
     * один новый адрес компании (тип OFFICE) и возвращаем его id. Использующий
     * код сохраняет этот id в ContactCompanyRelation.officeAddressId.
     */
    fun linkContactAddressToCompany(companyId: String, address: Address): String {
        val existing = addresses.firstOrNull {
            it.ownerId == companyId && it.ownerType == AddressOwnerType.COMPANY &&
                it.addressLine.trim().equals(address.addressLine.trim(), ignoreCase = true) &&
                it.city.trim().equals(address.city.trim(), ignoreCase = true)
        }
        if (existing != null) return existing.id
        val newAddr = address.copy(
            id = generateId(), ownerType = AddressOwnerType.COMPANY, ownerId = companyId,
            addressType = AddressType.OFFICE
        )
        addresses.add(newAddr)
        // ФИКС: Company.addresses — это снимок, а не производное от глобального
        // addresses (getCompanyById просто ищет по companies) — без этой строки
        // CompanyEditScreen/CompanyDetailScreen не увидели бы новый адрес до
        // перезапуска приложения (reloadFromDb). Тот же паттерн синхронизации,
        // что и в updateCompany/mergeCompanies выше.
        val companyIdx = companies.indexOfFirst { it.id == companyId }
        if (companyIdx >= 0) companies[companyIdx] = companies[companyIdx].copy(addresses = companies[companyIdx].addresses + newAddr)
        // BATCH 2C (§8): через единый глобальный writer — иначе возможен
        // сценарий "deleteCompany закоммитился → этот поздний insert всё ещё
        // выполняется → адрес-сирота воскресает" (deleteCompany уже чистит
        // адреса компании в СВОЕЙ транзакции; без общего writer порядок между
        // ними не гарантирован).
        enqueueWrite { db -> db.addressDao().insertAddresses(listOf(newAddr.toEntity())) }
        return newAddr.id
    }

    // ──────────────────────────────────────────────────────────
    //  ДУБЛИКАТЫ КОМПАНИЙ: поиск и слияние (точный аналог секции
    //  «ДУБЛИКАТЫ» контактов выше — см. комментарии там)
    // ──────────────────────────────────────────────────────────

    /** «Полнота» компании — при слиянии по умолчанию keep — самая заполненная
     *  (аналог contactScore). Считает и сотрудников (companyRelations), т.к.
     *  это не поле модели Company, а глобальный список по companyId. */
    internal fun companyScore(c: Company): Int =
        c.phones.size + c.emails.size + c.addresses.size +
        companyRelations.count { it.companyId == c.id } +
        (if (c.description.isNullOrBlank()) 0 else 1)

    /** Пара возможных дублей компаний + причина совпадения (аналог DuplicateMatch
     *  для контактов): нормализованное название, сайт (без протокола/www/
     *  завершающего слэша), телефон (последние цифры) или email компании. */
    data class CompanyDuplicateMatch(
        val a: Company,
        val b: Company,
        val byName: Boolean = false,
        val byWebsite: String? = null,
        val byPhone: String? = null,
        val byEmail: String? = null
    )

    private fun normalizedWebsite(url: String?): String? {
        val v = url?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return v.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    }

    /** Пары возможных дублей компаний: совпадение по нормализованному названию,
     *  сайту, телефону (≥7 цифр) или email. Без повторов и без пар «сам с собой». */
    fun findCompanyDuplicatePairs(): List<CompanyDuplicateMatch> {
        val list = companies.toList()
        val result = mutableListOf<CompanyDuplicateMatch>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val an = a.name.trim().searchNormalize()
                val bn = b.name.trim().searchNormalize()
                val nameHit = an.isNotBlank() && an == bn
                val aw = normalizedWebsite(a.website)
                val bw = normalizedWebsite(b.website)
                val websiteHit = if (!aw.isNullOrBlank() && aw == bw) aw else null
                val ap = a.phones.map { phoneDigits(it.number) }.filter { it.length >= 7 }
                val bp = b.phones.map { phoneDigits(it.number) }.filter { it.length >= 7 }
                val phoneHit = ap.firstOrNull { it in bp }
                val ae = a.emails.map { it.email.trim().lowercase() }.filter { it.isNotBlank() }
                val be = b.emails.map { it.email.trim().lowercase() }.filter { it.isNotBlank() }
                val emailHit = ae.firstOrNull { it in be }
                if (nameHit || websiteHit != null || phoneHit != null || emailHit != null) {
                    result.add(CompanyDuplicateMatch(a, b, nameHit, websiteHit, phoneHit, emailHit))
                }
            }
        }
        return result
    }

    /** Снимок ДО слияния компаний — для отмены (аналог MergeUndoSnapshot). */
    data class CompanyMergeUndoSnapshot(
        val companies: List<Company>,
        val notes: List<Note>,
        val calendarItems: List<CalendarItem>,
        val companyRelations: List<ContactCompanyRelation>
    )

    /**
     * Слияние 2-3 компаний в одну — точный аналог mergeContacts (см. подробный
     * комментарий там). Списочные поля самой компании (телефоны/email/адреса)
     * объединяются честно — union с дедупом. Сотрудники (ContactCompanyRelation)
     * переносятся со всех сливаемых компаний на итоговую; дедуп — по паре
     * (contactId, position), НЕ по одному companyId (иначе вторая должность
     * того же контакта на другой позиции терялась бы целиком — тот же принцип,
     * что и полный дедуп companyRelations внутри mergeContacts). Заметки и
     * календарные события ретаргетятся на итоговую компанию (тот же паттерн
     * ретаргетинга, что в convertContactToCompany/mergeContacts). Одиночные
     * конфликтующие поля (industry, logoUri) — явный выбор через
     * resolution.choiceWinners, иначе первое непустое; свободный текст
     * (name/description/website) — combineChosenText, как у контактов.
     * Всё — одной Room-транзакцией: либо весь merge применяется, либо (при
     * сбое) ни одна из участвующих компаний не тронута на диске.
     *
     * Возвращает снимок для отмены (null, если слияние не выполнено — id не
     * найдены или их меньше 2/больше 3).
     */
    fun mergeCompanies(companyIds: List<String>, resolution: MergeResolution = MergeResolution()): CompanyMergeUndoSnapshot? {
        val uniqueIds = companyIds.distinct()
        if (uniqueIds.size < 2 || uniqueIds.size > 3) return null
        val group = uniqueIds.mapNotNull { getCompany(it) }
        if (group.size != uniqueIds.size) return null

        val keep = group.maxByOrNull { companyScore(it) } ?: group.first()
        val keepId = keep.id
        val others = group.filter { it.id != keepId }
        val otherIds = others.map { it.id }
        val allPairs = group.map { it.id to it }

        // ── снимок ДО любых мутаций ──
        val snapshotNotes = notes.filter { it.companyId in uniqueIds }.toList()
        val snapshotCalendarItems = calendarItems.filter { ci ->
            ci.links.any { it.targetType == CalendarTargetType.COMPANY && it.targetId in uniqueIds }
        }.toList()
        val snapshotRelations = companyRelations.filter { it.companyId in uniqueIds }.toList()
        val snapshot = CompanyMergeUndoSnapshot(
            companies = group, notes = snapshotNotes,
            calendarItems = snapshotCalendarItems, companyRelations = snapshotRelations
        )

        // ── списочные поля самой компании: union с дедупом (как у контактов) ──
        val mergedPhones = keep.phones.toMutableList()
        val mergedEmails = keep.emails.toMutableList()
        val mergedAddresses = keep.addresses.toMutableList()
        others.forEach { other ->
            other.phones.forEach { p ->
                val d = phoneDigits(p.number)
                if (d.isEmpty() || mergedPhones.none { phoneDigits(it.number) == d })
                    mergedPhones.add(p.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.emails.forEach { e ->
                if (mergedEmails.none { it.email.equals(e.email, true) })
                    mergedEmails.add(e.copy(id = generateId(), contactId = keepId, isPrimary = false))
            }
            other.addresses.forEach { a ->
                if (mergedAddresses.none { it.addressLine.equals(a.addressLine, true) && it.city.equals(a.city, true) })
                    mergedAddresses.add(a.copy(id = generateId(), ownerId = keepId))
            }
        }

        fun winner(key: String) = resolution.choiceWinners[key]
        fun text(key: String, getter: (Company) -> String?) =
            combineChosenText(key, allPairs.map { it.first to getter(it.second) }, resolution.textIncluded[key])

        val merged = keep.copy(
            name = text("name") { it.name } ?: keep.name,
            description = text("description") { it.description },
            website = text("website") { it.website },
            // Фото и enum-поле модели физически одноместные — выбор неизбежен,
            // что не выбрано, MergeResolveScreen сохраняет заметкой (см. mergeContacts).
            logoUri = pickSingle("logoUri", allPairs.map { it.first to it.second.logoUri }, winner("logoUri")),
            industry = pickEnum(allPairs.map { it.first to it.second.industry }, winner("industry")),
            phones = mergedPhones, emails = mergedEmails, addresses = mergedAddresses
        )

        // ── сотрудники (ContactCompanyRelation): переносим "чужие" на keepId,
        //    дедуп по (contactId, position) — НЕ по одному companyId ──
        val keepOwnRelations = snapshotRelations.filter { it.companyId == keepId }
        val otherRelations = snapshotRelations.filter { it.companyId in otherIds }
        val mergedRelations = keepOwnRelations.toMutableList()
        val relationsToDelete = mutableListOf<String>()
        val relationsToRetarget = mutableListOf<ContactCompanyRelation>()
        otherRelations.forEach { r ->
            val posKey = r.position?.trim()?.lowercase().orEmpty()
            val dup = mergedRelations.any {
                it.contactId == r.contactId && (it.position?.trim()?.lowercase().orEmpty()) == posKey
            }
            if (dup) {
                relationsToDelete.add(r.id)
            } else {
                val retargeted = r.copy(companyId = keepId, isPrimary = false)
                mergedRelations.add(retargeted)
                relationsToRetarget.add(retargeted)
            }
        }

        val notesToReassign = snapshotNotes.filter { it.companyId in otherIds }.map { it.copy(companyId = keepId) }

        val calendarUpdates = snapshotCalendarItems.map { ci ->
            ci.copy(links = ci.links.map {
                if (it.targetType == CalendarTargetType.COMPANY && it.targetId in otherIds) it.copy(targetId = keepId) else it
            }.distinctBy { it.targetType to it.targetId })
        }

        // ── применяем в памяти (синхронно, как везде в этом файле) ──
        val keepIdx = companies.indexOfFirst { it.id == keepId }
        if (keepIdx >= 0) companies[keepIdx] = merged.copy(updatedAt = nowIso())
        otherIds.forEach { oid -> companies.removeAll { it.id == oid } }
        addresses.removeAll { it.ownerId == keepId && it.ownerType == AddressOwnerType.COMPANY }
        addresses.addAll(merged.addresses)
        otherIds.forEach { oid -> addresses.removeAll { it.ownerId == oid && it.ownerType == AddressOwnerType.COMPANY } }
        relationsToDelete.forEach { rid -> companyRelations.removeAll { it.id == rid } }
        relationsToRetarget.forEach { r -> val i = companyRelations.indexOfFirst { it.id == r.id }; if (i >= 0) companyRelations[i] = r }
        // OWNERSHIP PACK #1: проекция сотрудников — из обновлённого глобального списка
        // (иначе следующий updateContact сотрудника откатывал перенос в Room).
        resyncNestedCompanyRelations(otherRelations.map { it.contactId }.toSet())
        notesToReassign.forEach { n -> val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n }
        calendarUpdates.forEach { ci -> val i = calendarItems.indexOfFirst { it.id == ci.id }; if (i >= 0) calendarItems[i] = ci }

        // ── одна транзакция на весь merge: либо всё, либо (при сбое) ничего ──
        // BATCH 2B: обёртка — enqueueWrite на единый глобальный writer.
        // Содержимое транзакции не тронуто.
        enqueueWrite { db ->
            db.withTransaction {
            db.addressDao().deleteAddressesForOwner(keepId, AddressOwnerType.COMPANY.name)
                db.companyDao().deletePhonesForCompany(keepId)
                db.companyDao().deleteEmailsForCompany(keepId)

                db.companyDao().insertCompany(merged.toEntity())
                if (merged.phones.isNotEmpty()) db.contactDao().insertPhones(merged.phones.map { it.toCompanyEntity(keepId) })
                if (merged.emails.isNotEmpty()) db.contactDao().insertEmails(merged.emails.map { it.toCompanyEntity(keepId) })
                if (merged.addresses.isNotEmpty()) db.addressDao().insertAddresses(merged.addresses.map { it.toEntity() })

                relationsToDelete.forEach { rid -> db.contactDao().deleteCompanyRelation(rid) }
                if (relationsToRetarget.isNotEmpty()) db.contactDao().insertCompanyRelations(relationsToRetarget.map { it.toEntity() })

                // Реассайн заметок ДО удаления «других» компаний — иначе
                // последующий deleteNotesForCompany(oid) снёс бы их.
                if (notesToReassign.isNotEmpty()) db.noteDao().insertNotes(notesToReassign.map { it.toEntity() })

                calendarUpdates.forEach { ci ->
                    db.calendarDao().deleteLinksForItem(ci.id)
                    if (ci.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(ci.links.map { it.toEntity() })
                }

                otherIds.forEach { oid ->
                    db.addressDao().deleteAddressesForOwner(oid, AddressOwnerType.COMPANY.name)
                    db.companyDao().deletePhonesForCompany(oid)
                    db.companyDao().deleteEmailsForCompany(oid)
                    db.noteDao().deleteNotesForCompany(oid)
                    db.companyDao().deleteCompany(oid)
                }
            }
        }

        return snapshot
    }

    /** Freemium (2026-08, Pro/Pro+) — зеркало mergeAllDuplicates() для компаний,
     *  та же логика: цикл по findCompanyDuplicatePairs(), несколько проходов
     *  до фикс-точки, cap 2-3 id в mergeCompanies() не трогаем. */
    fun mergeAllCompanyDuplicates(): Int {
        var totalMerged = 0
        repeat(20) {
            val pairs = findCompanyDuplicatePairs()
            if (pairs.isEmpty()) return totalMerged
            var mergedThisPass = 0
            pairs.forEach { pair ->
                if (mergeCompanies(listOf(pair.a.id, pair.b.id)) != null) mergedThisPass++
            }
            totalMerged += mergedThisPass
            if (mergedThisPass == 0) return totalMerged
        }
        return totalMerged
    }

    /** Отмена слияния компаний по снимку (аналог undoMerge для контактов) —
     *  Snackbar «Объединено · Отменить». BATCH 2C (§22): та же переделка, что
     *  undoMerge() — одна writer-команда, одна Room-транзакция, только
     *  private *InTransaction helpers (не public enqueue-функции — §21). */
    fun undoMergeCompanies(snapshot: CompanyMergeUndoSnapshot): Deferred<Unit> {
        snapshot.companies.forEach { c ->
            val idx = companies.indexOfFirst { it.id == c.id }
            if (idx >= 0) companies[idx] = c else companies.add(c)
            addresses.removeAll { it.ownerId == c.id && it.ownerType == AddressOwnerType.COMPANY }
            addresses.addAll(c.addresses)
        }
        snapshot.notes.forEach { n ->
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = n else notes.add(n)
        }
        val calendarUpdates = calendarLinksRestoredFrom(snapshot.calendarItems)   // CALENDAR DATA SAFETY PACK: только связи
        calendarUpdates.forEach { ci ->
            val i = calendarItems.indexOfFirst { it.id == ci.id }
            if (i >= 0) calendarItems[i] = ci
        }
        snapshot.companyRelations.forEach { r ->
            val i = companyRelations.indexOfFirst { it.id == r.id }
            if (i >= 0) companyRelations[i] = r else companyRelations.add(r)
        }
        resyncNestedCompanyRelations(snapshot.companyRelations.map { it.contactId }.toSet()) // OWNERSHIP PACK #1
        return enqueueWrite { db ->
            db.withTransaction {
                snapshot.companies.forEach { restoreCompanyInTransaction(db, it) }
                snapshot.notes.forEach { insertNoteInTransaction(db, it) }
                calendarUpdates.forEach { replaceCalendarLinksInTransaction(db, it) }
                snapshot.companyRelations.forEach { db.contactDao().insertCompanyRelations(listOf(it.toEntity())) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  CALENDAR CRUD
    // ──────────────────────────────────────────────────────────
    fun getCalendarItemById(id: String): CalendarItem? = calendarItems.find { it.id == id }

    fun addCalendarItem(item: CalendarItem): Deferred<Unit> {
        val c = item.copy(createdAt = nowIso(), updatedAt = nowIso())
        calendarItems.add(c)
        // BATCH 2 (data integrity): симметрично addContact — см. §56 SOCIALSPHERE_KNOWLEDGE.md.
        return enqueueWrite { db -> db.withTransaction { addCalendarItemDb(c) } }
    }

    /** Private DB-only helper (не enqueue) — используется updateCalendarItem()/
     *  restoreCalendarItem() (внутри их enqueueWrite+withTransaction) И
     *  напрямую из undoMerge()'s/undoMergeCompanies' собственной writer-команды. */
    private suspend fun updateCalendarItemInTransaction(db: SocialsphereDatabase, item: CalendarItem) {
        db.calendarDao().deleteLinksForItem(item.id)
        db.calendarDao().deleteRemindersForItem(item.id)
        addCalendarItemDb(item)
    }

    /**
     * CALENDAR DATA SAFETY PACK: единственный публичный способ сохранить ПРАВКУ события (редактор, «Перенести» разового события).
     *
     * Раньше `updateCalendarItem(item)` был командой «замени ВСЁ»: устаревший снимок редактора откатывал завершение
     * (status/lastCompleted), перенос (postpone-пара), чужую смену recurrence и воскрешал удалённые связи/напоминания
     * (delete-then-reinsert links/reminders из снимка). Теперь вызывающий передаёт [base] — снимок, ОТ КОТОРОГО начата правка,
     * и [edited] — что пользователь хочет сохранить; поверх канонического состояния из хранилища ложится только РАЗНИЦА
     * edited−base (3-way, см. [CalendarEditMerge]). Completion и postpone — отдельные писатели
     * ([markCalendarItemCompleted], [postponeCalendarItemOccurrence]); restore из backup — отдельный путь.
     * Запись — та же одна writer-команда и одна Room-транзакция, но из СЛИТОГО объекта, а не из снимка.
     */
    fun updateCalendarItem(base: CalendarItem, edited: CalendarItem): Deferred<Unit> = synchronized(calendarMutationLock) {
        val idx = calendarItems.indexOfFirst { it.id == edited.id }
        if (idx < 0) return@synchronized CompletableDeferred(Unit)
        calendarRaceHookForTests?.invoke()
        val merged = CalendarEditMerge.merge(calendarItems[idx], base, edited, ::calendarLinkTargetExists).copy(updatedAt = nowIso())
        calendarItems[idx] = merged
        // BATCH 2/2B (data integrity): атомарная запись через единый глобальный writer.
        enqueueWrite { db -> db.withTransaction { updateCalendarItemInTransaction(db, merged) } }
    }

    /** Итог сохранения правки, ЕЩЁ не записанный: что получится из [edited] поверх текущего состояния. null — события нет.
     *  Нужен вызывающим бизнес-операциям, чтобы валидировать/планировать по слитому состоянию, а не по устаревшему снимку. */
    internal fun previewCalendarEdit(base: CalendarItem, edited: CalendarItem): CalendarItem? =
        calendarItems.find { it.id == edited.id }?.let { CalendarEditMerge.merge(it, base, edited, ::calendarLinkTargetExists) }

    /**
     * Отмена слияния контактов/компаний (снимок ~10 сек, только в памяти экрана) возвращает событию ТОЛЬКО ЕГО СВЯЗИ — это
     * единственное, что слияние меняло. Раньше событие восстанавливалось ЦЕЛИКОМ по снимку: завершение/перенос/правка,
     * сделанные в окне отмены, откатывались, а уже удалённое событие воскресало. Теперь связи ложатся поверх КАНОНИЧЕСКОГО
     * события; события, которого больше нет, пропускаются.
     */
    private fun calendarLinksRestoredFrom(snapshotItems: List<CalendarItem>): List<CalendarItem> =
        snapshotItems.mapNotNull { snap -> calendarItems.find { it.id == snap.id }?.copy(links = snap.links, updatedAt = nowIso()) }

    private suspend fun replaceCalendarLinksInTransaction(db: SocialsphereDatabase, item: CalendarItem) {
        db.calendarDao().deleteLinksForItem(item.id)
        if (item.links.isNotEmpty()) db.calendarDao().insertCalendarItemLinks(item.links.map { it.copy(calendarItemId = item.id).toEntity() })
    }

    /** Связь календаря обязана указывать на существующую сущность (иначе — сирота, см. воскрешение удалённых контактов). */
    private fun calendarLinkTargetExists(link: CalendarItemLink): Boolean = when (link.targetType) {
        CalendarTargetType.CONTACT -> contacts.any { it.id == link.targetId }
        CalendarTargetType.COMPANY -> companies.any { it.id == link.targetId }
        CalendarTargetType.GIFT -> gifts.any { it.id == link.targetId }
        CalendarTargetType.NOTE -> notes.any { it.id == link.targetId }
        CalendarTargetType.ADDRESS -> addresses.any { it.id == link.targetId }
    }

    /**
     * Восстановление события из бэкапа БЕЗ перезаписи createdAt/updatedAt —
     * аналог restoreContact. Upsert по id; ссылки/напоминания персистятся в БД
     * тем же путём (delete-then-reinsert), но исходные даты бэкапа не трогаются.
     * ЯВНЫЙ restore-путь: снимок здесь авторитетен по определению — в UI-правках не используется (у него нет вызывающих
     * в production; путь restore из JSON-backup идёт через RestoreMerger/applyRestoreWrites).
     */
    fun restoreCalendarItem(item: CalendarItem): Deferred<Unit> {
        val idx = calendarItems.indexOfFirst { it.id == item.id }
        if (idx >= 0) calendarItems[idx] = item else calendarItems.add(item)
        // BATCH 2/2B (data integrity): тот же паттерн, что updateCalendarItem.
        return enqueueWrite { db -> db.withTransaction { updateCalendarItemInTransaction(db, item) } }
    }

    /**
     * Отметить событие выполненным. Если это реальное общение
     * (звонок / встреча / сообщение), у связанных контактов обновляется
     * «последний контакт» = сегодня.
     */
    // BATCH 2F (2F-002): завершение события и обновление «последнего контакта»
    // у связанных контактов — ОДНО бизнес-действие, но персистилось 1+N
    // отдельными writer-командами: updateCalendarItem() (своя команда+транзакция)
    // плюс markContactedNow() (ещё по одной команде НА КАЖДЫЙ контакт). Порядок
    // был детерминирован, атомарности не было: сбой после первой команды
    // оставлял бы событие COMPLETED со СТАРЫМ lastContactDate у контактов (или
    // наоборот при другом раскладе). Теперь всё — одна writer-команда и одна
    // Room-транзакция.
    // Deadlock audit (§25): внутри транзакции НЕ вызываются public
    // updateCalendarItem()/markContactedNow() (они сами enqueue'ят) — только
    // private updateCalendarItemInTransaction() и прямой DAO-вызов.
    /**
     * occurrenceDate == null — прежняя non-recurring семантика: status =
     * COMPLETED (вся серия/разовое событие завершено). occurrenceDate != null —
     * recurring occurrence completion (одно занятие серии): status остаётся тем
     * же, что был у item (ожидается ACTIVE — caller/CalendarCompletion уже
     * провалидировал это), lastCompletedOccurrenceDate = occurrenceDate. Тот же
     * единственный contact-side-effect (CALL/MEETING/MESSAGE → lastContactDate)
     * для ОБОИХ случаев — второй алгоритм не заводится (см. CalendarCompletion).
     * Возвращает Deferred<Unit> — caller может await(), чтобы гарантировать
     * commit ДО следующего шага (напр. reschedule reminders на следующее
     * occurrence не должен опираться на устаревшее Room-состояние).
     */
    fun markCalendarItemCompleted(item: CalendarItem, occurrenceDate: LocalDate? = null, editBase: CalendarItem? = null): Deferred<Unit> = synchronized(calendarMutationLock) {
        // §24: один логический timestamp на всё действие. Раньше nowIso()
        // вызывался ТРИЖДЫ (здесь, внутри updateCalendarItem, внутри
        // markContactedNow) — событие и контакты могли получить разные метки.
        val now = nowIso()
        val today = now.take(10)
        // CALENDAR DATA SAFETY PACK: completion — ОТДЕЛЬНЫЙ писатель и применяется к КАНОНИЧЕСКОМУ состоянию события, а не к
        // снимку вызывающего: устаревший снимок (экран, редактор, отложенное уведомление) не откатывает независимо
        // изменённые ссылки/напоминания/recurrence и не двигает lastCompleted назад. Если completion происходит В ТОМ ЖЕ
        // сохранении редактора ([editBase] != null, [item] = edited) — сначала 3-way слияние правок, затем completion:
        // одна команда, одна транзакция, правки и завершение вместе.
        val idx = calendarItems.indexOfFirst { it.id == item.id }
        val canonical = calendarItems.getOrNull(idx)
        calendarRaceHookForTests?.invoke()
        val basis = when {
            canonical == null -> item
            editBase != null -> CalendarEditMerge.merge(canonical, editBase, item, ::calendarLinkTargetExists)
            else -> canonical
        }
        val completed = if (occurrenceDate != null)
            // requirement 10 (2026-09-17, postpone runtime semantics): успешное
            // completion occurrence атомарно очищает postpone-пару — max один
            // active override на item, и завершённый occurrence больше не может
            // быть тем occurrence, который эта пара переносит (см. CalendarPostpone/
            // CalendarUtils.pendingOccurrence — seriesDate завершаемого occurrence
            // всегда совпадает с postponedFromOccurrenceDate, если пара была).
            // lastCompleted монотонен: более поздняя уже записанная дата не откатывается.
            basis.copy(
                lastCompletedOccurrenceDate = laterIsoDate(basis.lastCompletedOccurrenceDate, occurrenceDate.toString()),
                postponedFromOccurrenceDate = null,
                postponedToDate = null,
                updatedAt = now,
            )
        else
            basis.copy(status = CalendarItemStatus.COMPLETED, updatedAt = now)
        val isInteraction = basis.type == CalendarItemType.CALL ||
            basis.type == CalendarItemType.MEETING ||
            basis.type == CalendarItemType.MESSAGE
        // §23 snapshot safety: неизменяемый список id снимается ДО enqueue из
        // канонического состояния, а не читается из mutable-состояния внутри
        // отложенной команды. Фильтр «контакт есть в памяти» повторяет прежнее
        // поведение markContactedNow (он писал только для найденных).
        val interactionContactIds = if (isInteraction) {
            basis.links
                .filter { it.targetType == CalendarTargetType.CONTACT }
                .map { it.targetId }
                .distinct()
                .filter { id -> contacts.any { it.id == id } }
                .toList()
        } else emptyList()

        // ── in-memory (синхронно, как везде в этом файле) ──
        if (idx >= 0) calendarItems[idx] = completed
        interactionContactIds.forEach { id ->
            val cidx = contacts.indexOfFirst { it.id == id }
            if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(lastContactDate = today)
        }

        // Условие записи события в БД — как в прежнем updateCalendarItem():
        // только если событие есть в памяти (иначе оно молча ничего не писало).
        val itemExists = idx >= 0
        if (!itemExists && interactionContactIds.isEmpty()) return@synchronized CompletableDeferred(Unit)
        enqueueWrite { db ->
            db.withTransaction {
                if (itemExists) updateCalendarItemInTransaction(db, completed)
                interactionContactIds.forEach { id ->
                    db.contactDao().updateLastContactDate(id, today, now)
                }
            }
        }
    }

    /**
     * v20 runtime (2026-09-17): переносит ТОЛЬКО текущее pending occurrence
     * recurring-серии — persist postponedFromOccurrenceDate/postponedToDate.
     * startDate/recurrenceRule/lastCompletedOccurrenceDate НЕ трогаются
     * (requirement 5.6) — copy() оригинала, тот же паттерн записи, что
     * markCalendarItemCompleted/updateCalendarItem: in-memory синхронно, Room
     * атомарно через единый enqueueWrite.
     */
    fun postponeCalendarItemOccurrence(item: CalendarItem, seriesDate: LocalDate, targetDate: LocalDate): Deferred<Unit> = synchronized(calendarMutationLock) {
        // CALENDAR DATA SAFETY PACK: пара пишется ПОВЕРХ канонического состояния (не снимка вызывающего) — устаревший
        // снимок не откатывает независимо изменённые ссылки/напоминания/статус.
        val idx = calendarItems.indexOfFirst { it.id == item.id }
        if (idx < 0) return@synchronized CompletableDeferred(Unit)
        calendarRaceHookForTests?.invoke()
        val updated = calendarItems[idx].copy(
            postponedFromOccurrenceDate = seriesDate.toString(),
            postponedToDate = targetDate.toString(),
            updatedAt = nowIso(),
        )
        calendarItems[idx] = updated
        enqueueWrite { db -> db.withTransaction { updateCalendarItemInTransaction(db, updated) } }
    }

    /** Проставить «последний контакт» = сегодня для одного или нескольких
     *  контактов. Используется быстрыми действиями (звонок/сообщение) и
     *  ручной отметкой «Связался» в списке «Нужно связаться».
     *  BATCH 2B (Confirmed Problem D): раньше писал в БД напрямую через
     *  голый scope.launch, в обход любой ordering policy — гонка против
     *  конкурентного updateContact() ЭТОГО ЖЕ контакта могла записать
     *  lastContactDate не в том порядке. Теперь — через единый глобальный
     *  writer, как и остальные Contact-мутации. Один DAO-вызов на контакт —
     *  withTransaction не нужен (§18, не многошаговая операция). */
    fun markContactedNow(contactIds: List<String>): Deferred<Unit> {
        val now = nowIso()
        val today = now.take(10)
        // PERSISTENCE PACK #2: один Deferred на всё действие — одна writer-команда и одна
        // транзакция на пачку контактов (раньше N отдельных команд, и вызывающий не мог
        // дождаться коммита ни одной из них).
        val touched = contactIds.filter { id ->
            val idx = contacts.indexOfFirst { it.id == id }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(lastContactDate = today)
            idx >= 0
        }
        if (touched.isEmpty()) return CompletableDeferred(Unit)
        return enqueueWrite { db ->
            db.withTransaction { touched.forEach { db.contactDao().updateLastContactDate(it, today, now) } }
        }
    }
    fun markContactedNow(contactId: String): Deferred<Unit> = markContactedNow(listOf(contactId))

    fun deleteCalendarItem(itemId: String): Deferred<Unit> {
        // EXPLICIT RECOVERY PACK: одна отметка времени на само удаление + его tombstone.
        val tombstone = recordTombstone(TombstoneEntityType.CALENDAR_ITEM, itemId, nowIso())
        calendarItems.removeAll { it.id == itemId }
        // BATCH 2/2B (data integrity): атомарная запись через единый глобальный writer.
        return enqueueWrite { db ->
            db.withTransaction {
                db.calendarDao().deleteCalendarItem(itemId)
                db.calendarDao().deleteLinksForItem(itemId)
                db.calendarDao().deleteRemindersForItem(itemId)
                db.tombstoneDao().insert(tombstone.toEntity())
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  NOTES CRUD
    // ──────────────────────────────────────────────────────────
    /** Полное удаление всех данных пользователя: все таблицы Room +
     *  in-memory списки. Демоданные больше не сеются — после перезапуска
     *  приложение остаётся пустым (v14, DemoDataProvider удалён).
     *  BATCH 2C (§17, P1): `clearAllTables()` теперь идёт ЧЕРЕЗ единый
     *  глобальный writer, не в обход него голым scope.launch — иначе запись,
     *  поставленная в очередь ДО wipe, могла выполниться ПОСЛЕ него (или
     *  наоборот), и порядок относительно wipe не соответствовал бы порядку
     *  вызова. `onDone(true)` теперь вызывается ТОЛЬКО после реального
     *  завершения (await), не после одного лишь запуска — раньше `db()?.
     *  clearAllTables()` на неинициализированной БД (`db()==null`) тихо
     *  ничего не делал, но ВСЁ РАВНО докладывал `dbOk=true` и стирал
     *  in-memory состояние — данные бы «вернулись» после перезапуска, а
     *  владелец думал, что всё стёр (нарушение INVARIANT D5). */
    // BATCH 2E (2E-004): DB clear и in-memory clear теперь ОДНА writer-команда,
    // не db.clearAllTables() как отдельная команда + отдельная корутина,
    // чистящая память ПОСЛЕ. Раньше между "writer-команда завершилась" (сразу
    // после clearAllTables()) и "память очищена" (в отдельном scope.launch)
    // было окно, в которое writer УЖЕ был свободен обрабатывать СЛЕДУЮЩУЮ
    // команду в очереди — если это вставляло данные, они успевали
    // закоммититься в уже очищенную Room, и только потом in-memory clear
    // выполнялся поверх — Room не пуста, память пуста (обратный вариант
    // "воскрешения", тот же класс бага, что D-001/setAddressCoords). Теперь
    // memory-clear — часть ТОЙ ЖЕ enqueued команды: следующая команда очереди
    // физически не может начаться, пока не выполнится ВЕСЬ блок ниже —
    // гарантия DbWriteQueue (следующая итерация for-цикла ждёт полного
    // возврата текущей, см. DbWriteQueueTest.commandDoesNotStartUntilPreviousFullyCompletes).
    // Deadlock audit: withContext(Main) здесь НЕ enqueue'ит другую writer-
    // команду и не ждёт ничего кроме своих же синхронных clear() — не
    // nested-writer вызов, короткая операция, безопасно.
    // BATCH (Calendar/Reminder scheduling): AlarmManager — тоже пользовательские
    // данные (INVARIANT из «Работы над ошибками» — Room очищен ⇒ связанные
    // alarms тоже отсутствуют), а не только строки в Room. Отмена — СИНХРОННО
    // здесь, ДО enqueueWrite, а не после: requestCode каждого будильника
    // напоминания зависит от calendarItemId+reminderRuleId, которые существуют
    // только пока calendarItems (память) ещё не очищены writer-командой ниже —
    // после wipe их уже неоткуда взять. Единая точка адресации — canonical
    // NotificationScheduler.cancelAllUserDataAlarms(); экран приватности не
    // знает requestCode ни одного типа будильника.
    // Порядок «cancel ДО wipe» безопасен и в другую сторону: cancel() — best
    // effort и НЕ блокирует и не отменяет сам wipe. Если AlarmManager.cancel()
    // почему-то бросит (на практике не происходит для валидного PendingIntent —
    // cancel() не требует специальных разрешений), данные пользователя всё
    // равно должны быть удалены — пропустить Room-очистку «на всякий случай»
    // опаснее, чем оставить единичный неотменённый будильник, от которого и
    // так защищает второй уровень (Batch 7.2, defense in depth):
    // NotificationReceiver.resolveCalendarReminder() читает Room, не находит
    // CalendarItem — уведомление не показывается, даже если физическая отмена
    // здесь почему-то не сработала.
    fun wipeAllData(onDone: (Boolean) -> Unit = {}) {
        val calendarItemsSnapshot = calendarItems.toList()
        // Оба cleanup'а — best effort, каждый в СВОЁМ try/catch (сбой одного
        // не должен пропустить другой), и ни один не блокирует Room wipe ниже.
        // cancelAll() уведомлений — тем же canonical cleanup path: после
        // полного удаления данных ни одно уже показанное уведомление (с
        // запечённым в него текстом удалённого контакта/события) больше не
        // актуально; перечислять notificationId вручную не нужно — системный
        // NotificationManager.cancelAll() (через NotificationHelper).
        appContext?.let { ctx ->
            try {
                com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
                    .cancelAllUserDataAlarms(ctx, calendarItemsSnapshot)
            } catch (e: Exception) {
                android.util.Log.e("AppStateStore", "wipeAllData: отмена будильников не удалась, Room всё равно чистим", e)
            }
            try {
                com.aistudio.socialsphere.crmlxb.utils.NotificationHelper.cancelAll(ctx)
            } catch (e: Exception) {
                android.util.Log.e("AppStateStore", "wipeAllData: снятие уведомлений не удалось, Room всё равно чистим", e)
            }
            // Черновики формы контакта — тоже личные данные пользователя: после «стереть всё» не остаются.
            ContactFormDraftStore.clearAll(ctx)
        }

        val wipeCommand = enqueueWrite { db ->
            db.clearAllTables()
            withContext(Dispatchers.Main) {
                contacts.clear(); companies.clear(); calendarItems.clear()
                notes.clear(); gifts.clear(); companyRelations.clear()
                contactRelations.clear(); addresses.clear()
                sizeInfos.clear(); personalDetails.clear()
                groups.clear(); groupMembers.clear()
                tags.clear(); tagMembers.clear()
            }
        }
        scope.launch {
            // dbOk=false здесь означает и DB, и память НЕ тронуты — если
            // db.clearAllTables() бросает, withContext(Main){...} внутри той
            // же команды не выполняется (см. DbWriteQueue: catch отменяет всю
            // команду через completeExceptionally, блок не продолжается).
            val dbOk = try { wipeCommand.await(); true } catch (e: Exception) { false }
            withContext(Dispatchers.Main) { onDone(dbOk) }
        }
    }

    /** Сохранение размеров: глобальный список (его читает вкладка Подарки),
     *  копия в контакте и Room — всё синхронно. */
    fun setSizeInfo(contactId: String, sizeInfo: SizeInfo): Deferred<Unit> {
        val idx = sizeInfos.indexOfFirst { it.contactId == contactId }
        if (idx >= 0) sizeInfos[idx] = sizeInfo else sizeInfos.add(sizeInfo)
        val cidx = contacts.indexOfFirst { it.id == contactId }
        if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(sizeInfo = sizeInfo)
        // BATCH 2C: конфликтует с deleteSizeInfoForContact из deleteContact/mergeContacts/
        // restore. updateContact в size_infos НЕ пишет (P1, 2026-09-21) — единственный
        // независимый писатель размеров именно этот метод.
        return enqueueWrite { db -> db.contactDao().insertSizeInfo(sizeInfo.toEntity()) }
    }

    /** Сохраняет вычисленные геокодером координаты адреса в память и БД, чтобы
     *  точка на карте не пропадала и не геокодилась заново при каждом открытии.
     *  BATCH 2C (§8): раньше это писало через голый scope.launch — ровно тот
     *  сценарий, что явно назван недопустимым: deleteContact/deleteCompany
     *  коммитятся и чистят адреса, а этот поздний геокодер-insert выполняется
     *  ПОСЛЕ и "воскрешает" уже удалённый адрес. Через единый writer оба идут
     *  в submission order. */
    fun setAddressCoords(addressId: String, lat: Double, lng: Double): Deferred<Unit> {
        val i = addresses.indexOfFirst { it.id == addressId }
        if (i < 0) return CompletableDeferred(Unit)
        if (addresses[i].latitude != null && addresses[i].longitude != null) return CompletableDeferred(Unit)
        val updated = addresses[i].copy(latitude = lat, longitude = lng)
        addresses[i] = updated
        // OWNERSHIP PACK #1: вложенный Contact.addresses/Company.addresses — проекция
        // глобального списка; без этого бэкап, слияние и любой снимок владельца видели
        // адрес без координат и стирали их при следующей записи.
        syncNestedAddress(updated)
        return enqueueWrite { db -> db.addressDao().insertAddresses(listOf(updated.toEntity())) }
    }

    // ── Связи между контактами (семья и др.) ─────────────────
    fun addContactRelation(relation: ContactRelation): Deferred<Unit> {
        if (contactRelations.any { it.id == relation.id }) return CompletableDeferred(Unit)
        contactRelations.add(relation)
        // BATCH 2C: конфликтует с deleteContact (удаляет связи по contactId).
        return enqueueWrite { db -> db.contactDao().insertContactRelations(listOf(relation.toEntity())) }
    }

    fun removeContactRelation(relationId: String): Deferred<Unit> {
        contactRelations.removeAll { it.id == relationId }
        return enqueueWrite { db -> db.contactDao().deleteContactRelation(relationId) }
    }

    fun addNote(note: Note): Deferred<Unit> {
        // BATCH 2F (§24): ОДИН логический timestamp на всё бизнес-действие —
        // и Note.createdAt/updatedAt, и lastContactDate контакта. Раньше
        // now.take(10) считался от того же now, но сам факт двух разных
        // DB-команд допускал расхождение; теперь это одна величина в одной
        // транзакции.
        val now = nowIso()
        val today = now.take(10)
        val n = note.copy(createdAt = now, updatedAt = now)
        notes.add(n)
        // Заметка всегда добавляется в контакт. «Последний контакт» обновляем
        // только для заметок-общения, НЕ для структурной информации
        // (подарок / личная деталь / дата-событие) — semantics 1:1 как раньше,
        // Batch 2F меняет ТОЛЬКО атомарность, не правило «что считается общением».
        val countsAsContact = n.type != NoteType.GIFT &&
            n.type != NoteType.PERSONAL_DETAIL &&
            n.type != NoteType.DATE_EVENT
        val cidx = n.contactId?.let { cid -> contacts.indexOfFirst { it.id == cid } } ?: -1
        if (cidx >= 0) {
            contacts[cidx] = contacts[cidx].copy(
                notes           = contacts[cidx].notes + n,
                lastContactDate = if (countsAsContact) today else contacts[cidx].lastContactDate
            )
        }
        // Условие ровно как в старом коде: contactId задан И контакт реально
        // найден в памяти И заметка считается общением.
        val interactionContactId = if (cidx >= 0 && countsAsContact) n.contactId else null

        // BATCH 2F (2F-001): вставка Note и обновление lastContactDate — ОДНО
        // пользовательское действие, но персистились ДВУМЯ отдельными
        // writer-командами. Порядок был детерминирован (Batch 2B), однако
        // атомарности не было: сбой второй команды оставлял бы
        // lastContactDate = сегодня при ОТСУТСТВУЮЩЕЙ заметке — CRM считает
        // общение состоявшимся, а записи о нём нет. Теперь обе мутации — одна
        // writer-команда и одна Room-транзакция.
        // Deadlock audit (§25): внутри блока НЕ вызывается ни markContactedNow(),
        // ни addNote(), ни другая public-функция, которая сама enqueue'ит —
        // только private insertNoteInTransaction() и прямой DAO-вызов.
        if (interactionContactId == null) {
            // Не-interaction заметка: один SQL-write, лишняя транзакция не нужна (§7).
            return enqueueWrite { db -> insertNoteInTransaction(db, n) }
        } else {
            return enqueueWrite { db ->
                db.withTransaction {
                    insertNoteInTransaction(db, n)
                    db.contactDao().updateLastContactDate(interactionContactId, today, now)
                }
            }
        }
    }

    /** Private DB-only helper (не enqueue) — используется всеми Note-мутациями
     *  через enqueueWrite И напрямую из undoMerge()'s собственной writer-команды. */
    private suspend fun insertNoteInTransaction(db: SocialsphereDatabase, note: Note) {
        db.noteDao().insertNotes(listOf(note.toEntity()))
    }

    fun updateNote(note: Note): Deferred<Unit> {
        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note
        note.contactId?.let { cid ->
            val cidx = contacts.indexOfFirst { it.id == cid }
            if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
                notes = contacts[cidx].notes.map { if (it.id == note.id) note else it }
            )
        }
        // lastContactDate намеренно не трогаем: правка старой записи — не новая активность
        return enqueueWrite { db -> insertNoteInTransaction(db, note) }
    }

    fun deleteNote(noteId: String): Deferred<Unit> {
        val n = notes.find { it.id == noteId }
        // EXPLICIT RECOVERY PACK: одна отметка времени на само удаление + его tombstone.
        val tombstone = recordTombstone(TombstoneEntityType.NOTE, noteId, nowIso())
        notes.removeAll { it.id == noteId }
        n?.contactId?.let { cid ->
            val idx = contacts.indexOfFirst { it.id == cid }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(
                notes = contacts[idx].notes.filter { it.id != noteId }
            )
        }
        return enqueueWrite { db -> db.withTransaction { db.noteDao().deleteNote(noteId); db.tombstoneDao().insert(tombstone.toEntity()) } }
    }

    /**
     * Восстановление заметки из бэкапа БЕЗ перезаписи createdAt/updatedAt —
     * аналог restoreContact/restoreGroup/restoreTag. Upsert по id, синхронизирует
     * вложенный Contact.notes (как addNote/updateNote) для contactId-заметок;
     * companyId-заметки живут только в плоском списке (Company их не хранит
     * вложенно — см. §44 SOCIALSPHERE_KNOWLEDGE.md).
     */
    fun restoreNote(note: Note): Deferred<Unit> {
        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note else notes.add(note)
        note.contactId?.let { cid ->
            val cidx = contacts.indexOfFirst { it.id == cid }
            if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
                notes = contacts[cidx].notes.filter { it.id != note.id } + note
            )
        }
        return enqueueWrite { db -> insertNoteInTransaction(db, note) }
    }

    // ──────────────────────────────────────────────────────────
    //  GIFTS CRUD
    // ──────────────────────────────────────────────────────────
    /** Private DB-only helper (не enqueue) — используется всеми Gift-мутациями
     *  через enqueueWrite И напрямую из undoMerge()'s собственной writer-команды. */
    private suspend fun insertGiftInTransaction(db: SocialsphereDatabase, gift: GiftIdea) {
        db.giftDao().insertGifts(listOf(gift.toEntity()))
    }

    /** Восстановление подарка из бэкапа — точный аналог restoreNote (upsert по id,
     *  синхронизирует и плоский список, и вложенное Contact.gifts, и БД). */
    fun restoreGift(gift: GiftIdea): Deferred<Unit> {
        val idx = gifts.indexOfFirst { it.id == gift.id }
        if (idx >= 0) gifts[idx] = gift else gifts.add(gift)
        val cidx = contacts.indexOfFirst { it.id == gift.contactId }
        if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
            gifts = contacts[cidx].gifts.filter { it.id != gift.id } + gift
        )
        return enqueueWrite { db -> insertGiftInTransaction(db, gift) }
    }

    fun addGift(gift: GiftIdea): Deferred<Unit> {
        gifts.add(gift)
        val idx = contacts.indexOfFirst { it.id == gift.contactId }
        if (idx >= 0) contacts[idx] = contacts[idx].copy(gifts = contacts[idx].gifts + gift)
        return enqueueWrite { db -> insertGiftInTransaction(db, gift) }
    }

    fun updateGift(gift: GiftIdea): Deferred<Unit> {
        val idx = gifts.indexOfFirst { it.id == gift.id }
        if (idx >= 0) gifts[idx] = gift
        val cidx = contacts.indexOfFirst { it.id == gift.contactId }
        if (cidx >= 0) contacts[cidx] = contacts[cidx].copy(
            gifts = contacts[cidx].gifts.map { if (it.id == gift.id) gift else it }
        )
        return enqueueWrite { db -> insertGiftInTransaction(db, gift) }
    }

    fun deleteGift(giftId: String): Deferred<Unit> {
        val g = gifts.find { it.id == giftId }
        // EXPLICIT RECOVERY PACK: одна отметка времени на само удаление + его tombstone.
        val tombstone = recordTombstone(TombstoneEntityType.GIFT, giftId, nowIso())
        gifts.removeAll { it.id == giftId }
        g?.let { gift ->
            val idx = contacts.indexOfFirst { it.id == gift.contactId }
            if (idx >= 0) contacts[idx] = contacts[idx].copy(
                gifts = contacts[idx].gifts.filter { it.id != giftId }
            )
        }
        return enqueueWrite { db -> db.withTransaction { db.giftDao().deleteGift(giftId); db.tombstoneDao().insert(tombstone.toEntity()) } }
    }
}
