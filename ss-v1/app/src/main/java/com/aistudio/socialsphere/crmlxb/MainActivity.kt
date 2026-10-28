package com.aistudio.socialsphere.crmlxb

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aistudio.socialsphere.crmlxb.ui.screens.*
import com.aistudio.socialsphere.crmlxb.data.AppStateStore
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import com.aistudio.socialsphere.crmlxb.utils.findActivity
import kotlinx.coroutines.launch

// AppCompatActivity (наследник FragmentActivity — требование androidx.biometric
// BiometricPrompt для разблокировки «Защищено» по-прежнему выполнено) — нужна
// для per-app языка через AppCompatDelegate.setApplicationLocales (2026-07-22):
// раньше локаль подменялась вручную через createConfigurationContext и
// оборачивала Compose-дерево (LocalizedApp) — именно эта самодельная схема
// была источником старого бага «язык путается при переключении» (LocalContext
// внутри нёе не был настоящим ContextWrapper над Activity). AppCompatDelegate
// пересоздаёт Activity целиком при смене языка — LocalContext.current везде
// снова корректный, ручное оборачивание не нужно.
class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    // Запрос разрешения на показ уведомлений (Android 13+). Результат не критичен:
    // если откажут — уведомления просто не покажутся, логика планирования цела.
    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ДО super.onCreate(): AppCompatDelegate должен знать локаль до того,
        // как Activity начнёт применять Configuration/ресурсы.
        AppSettings.init(applicationContext)
        AppSettings.applyLocale(AppSettings.currentLanguage.value)
        super.onCreate(savedInstanceState)

        val db = SocialsphereDatabase.getDatabase(applicationContext)
        AppStateStore.initialize(applicationContext, db)
        // Startup reconciliation: тот же canonical reconcile-path, что и у
        // master-переключателя уведомлений и restoreBackup() — читает Room
        // напрямую (db, не AppStateStore.calendarItems — не зависит от
        // AppStateStore.initialize()'s асинхронного loadInitialData()),
        // восстанавливает потерянные вне приложения будильники (Android мог
        // снять их без BOOT_COMPLETED — force-stop/обновление и т.п.) и
        // ежедневную проверку «пора связаться» — второй отдельный вызов
        // scheduleStaleCheck() здесь больше не нужен, reconcile его включает.
        // Идемпотентно (FLAG_UPDATE_CURRENT) — повторные старты не плодят
        // дубликаты; ACTIVE-фильтр и барьер master-флага — внутри
        // scheduleReminder(), второе правило здесь не заводится.
        lifecycleScope.launch {
            com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
                .reconcileAllUserDataAlarms(applicationContext, db)
        }

        // Android 13+: без POST_NOTIFICATIONS уведомления НЕ показываются. Раньше
        // разрешение просили только при добавлении напоминания к событию — поэтому
        // «пора связаться» и пр. могли молча не приходить. Просим при старте.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        enableEdgeToEdge()
        setContent {
            val isDarkTheme by AppSettings.isDarkTheme

            // ФИКС (аудит 2026-08-11, PIN_BIOMETRIC_UX_PROMPT.md §3.1): без FLAG_SECURE
            // содержимое приложения видно в скриншоте карточки в Recents/переключателе
            // задач и снимается сторонними скриншот-инструментами. Реактивно, не статично
            // в onCreate() — владелец явно попросил ставить его ТОЛЬКО пока включена хотя бы
            // одна из защит (блокировка приложения / приватность записей), не всегда: иначе
            // теряется возможность верифицировать UI скриншотами, пока владелец сам не
            // включил защиту. Включаешь защиту — флаг встаёт немедленно; выключаешь — снимается.
            val appLockOn by AppSettings.appLockEnabled
            val bioLockOn by AppSettings.biometricLock
            androidx.compose.runtime.LaunchedEffect(appLockOn, bioLockOn) {
                if (appLockOn || bioLockOn) {
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            com.aistudio.socialsphere.crmlxb.ui.theme.AppleAppTheme(darkTheme = isDarkTheme) {
                SocialsphereApp()
            }
        }
    }
}

// Состояние блокировки приложения — НЕ персистентное (нарочно): после смерти
// процесса приложение должно снова запросить PIN/биометрию, это безопасный
// дефолт. backgroundedAt переживает только сворачивание/разворачивание в
// рамках одного процесса — ровно то, что нужно для grace-периода.
private object AppLockState {
    var unlocked by mutableStateOf(false)
    var backgroundedAt: Long? = null
}

// Не перезапрашиваем PIN/биометрию, если приложение свернули меньше чем на
// это время — иначе ExternalActionHandler.startIntentSafely (звонок/SMS/карты)
// внешним интентом сворачивает приложение и тут же запирал бы его снова.
private const val APP_LOCK_GRACE_MS = 30_000L

/**
 * Экран PIN/биометрии отдельным полноэкранным окном ПОВЕРХ приложения (см. SocialsphereApp): состояние
 * приложения под ним не теряется. «Назад» не закрывает замок (сворачивает приложение), тап мимо — тоже.
 */
@Composable
private fun AppLockOverlay(onUnlocked: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                w.setDimAmount(0f)
            }
        }
        androidx.activity.compose.BackHandler { view.context.findActivity()?.moveTaskToBack(true) }
        Box(Modifier.fillMaxSize()) { AppLockScreen(onUnlocked = onUnlocked) }
    }
}

/**
 * Экраны правки берут исходник (контакт/компанию/событие) из памяти ОДИН раз при открытии. После выгрузки
 * процесса система восстанавливает стек экранов раньше, чем данные загрузятся из базы: форма правки
 * открылась бы пустой, а сохранение пустой формы не изменило бы ничего — набранное молча пропало бы
 * (2026-10-08). Поэтому такие экраны ждут окончания первичной загрузки.
 */
@Composable
private fun AfterDataLoaded(content: @Composable () -> Unit) {
    if (AppStateStore.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme.colors.brand)
        }
    } else content()
}

@Composable
fun SocialsphereApp() {
    // Первый запуск — тур из нескольких шагов (обложка → персона → демо →
    // импорт → финал). Флаг ставится внутри тура (финал или «Пропустить»
    // на шаге импорта) — этот блок сам ничего не решает, только гейтит.
    val onboardingDone by AppSettings.onboardingCompleted
    if (!onboardingDone) {
        OnboardingTourScreen(onFinish = {})
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> AppLockState.backgroundedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val bgAt = AppLockState.backgroundedAt
                    if (bgAt != null && System.currentTimeMillis() - bgAt > APP_LOCK_GRACE_MS) {
                        AppLockState.unlocked = false
                    }
                    AppLockState.backgroundedAt = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ЗАМОК — ПОВЕРХ ПРИЛОЖЕНИЯ, а не вместо него (жалоба владельца 2026-10-08: «когда телефон
    // блокируется, оно сбивается и открывается с главной страницы, а я был в середине контакта и
    // записывал данные — потерял»). Раньше при запросе PIN здесь стоял ранний `return`: NavHost
    // выпадал из композиции, стек экранов и ВСЕ несохранённые поля форм (remember) пропадали,
    // после разблокировки приложение собиралось заново с «Главной». Теперь содержимое остаётся
    // в композиции (состояние живёт), а экран PIN рисуется отдельным окном-Dialog ПОВЕРХ всех
    // окон (включая шторки/диалоги, которые тоже отдельные окна и иначе остались бы видны над замком).
    val appLocked = AppSettings.appLockEnabledSafe() && !AppLockState.unlocked
    androidx.compose.runtime.SideEffect { AppLockGate.locked = appLocked }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(appLocked) {
        // Фокус поля под замком не должен принимать ввод и держать клавиатуру над экраном PIN.
        if (appLocked) { focusManager.clearFocus(force = true); keyboardController?.hide() }
    }

    val navController = rememberNavController()

    val navigationItems = listOf(
        Triple("home", R.string.nav_home, Icons.Default.Home),
        Triple("contacts", R.string.nav_contacts, Icons.Default.Contacts),
        Triple("companies", R.string.nav_companies, Icons.Default.Business),
        Triple("calendar", R.string.nav_calendar, Icons.Default.CalendarToday),
        Triple("map", R.string.nav_map, Icons.Default.Map)
    )

    val navBackStackEntryOuter by navController.currentBackStackEntryAsState()
    val showBottomBarOuter = navigationItems.any { it.first == navBackStackEntryOuter?.destination?.route }

    if (appLocked) AppLockOverlay(onUnlocked = { AppLockState.unlocked = true })

    Scaffold(
        // Под замком содержимое скрыто для сервисов доступности (экран PIN — отдельное окно сверху).
        modifier = Modifier.fillMaxSize().then(
            if (appLocked) Modifier.clearAndSetSemantics { } else Modifier
        ),
        // ФИКС «слишком большой отступ сверху на всех экранах»: внешний Scaffold
        // по умолчанию добавлял инсет статус-бара в innerPadding, а Scaffold КАЖДОГО
        // экрана внутри NavHost добавлял его ещё раз — отступ удваивался. Внешний
        // не должен трогать верх: статус-бар обрабатывают сами экраны.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            val showBottomBar = navigationItems.any { it.first == currentDestination?.route }

            if (showBottomBar) {
                // Навигация по вкладке — канонический паттерн нижней навигации Android:
                // один navigate с popUpTo(start){saveState} + restoreState + singleTop.
                // Раньше перед ним стоял «пробный» popBackStack(route), который при
                // отсутствии маршрута на стеке (обычный случай — popUpTo(start) не
                // копит вкладки) писал в лог «Ignoring popBackStack to route …»
                // (безвредно, но шумно и засоряло стек). Переключение вкладок с
                // сохранением/восстановлением состояния — идентично.
                // Сам переход — в TabNavigation.kt (то же использует «Открыть контакты/компании» из результата импорта).
                val onNavClick: (String) -> Unit = { route -> navController.navigateToTab(route) }
                // Кастомный таб-бар по макету Aurelia: матовый бумажный фон, верхняя
                // волосяная линия, иконки 23 + подписи 10sp, активный — малахит,
                // неактивный — приглушённый. Без «пилюли»-индикатора Material.
                Column(Modifier.fillMaxWidth().background(com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme.colors.barBlur)) {
                    androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme.colors.separator)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        navigationItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.first } == true
                            val tint = if (selected) com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme.colors.brand
                                       else com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme.colors.tertiaryLabel
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) { onNavClick(item.first) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(item.third, contentDescription = stringResource(item.second),
                                    tint = tint, modifier = Modifier.size(23.dp))
                                Spacer(Modifier.height(4.dp))
                                Text(stringResource(item.second), fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = tint, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            // FIX (фидбэк владельца 2026-07-04): клавиатура перекрывала поля ввода
            // по всему приложению — edge-to-edge (enableEdgeToEdge) + обнулённые
            // contentWindowInsets на внешнем Scaffold означают, что НИЧТО не
            // подстраивалось под IME. imePadding() чинит это для всех экранов —
            // НО только там, где нет соседнего нижнего меню: на 5 вкладках
            // (Home/Contacts/...) bottomBar не двигается с клавиатурой, а
            // imePadding здесь дополнительно сжимал контент НАД ним — между
            // контентом и неподвижным меню появлялась серая полоса фона,
            // растущая вместе с клавиатурой (фидбэк владельца 2026-07-05).
            // На вкладках search живёт в верхнем баре — сдвигать контент вверх
            // там не нужно вообще; на push-экранах (формы/детали) bottomBar
            // скрыт, конфликта нет — imePadding работает как задумано.
            modifier = Modifier.padding(innerPadding)
                .then(if (showBottomBarOuter) Modifier else Modifier.imePadding())
        ) {
            composable("home") {
                HomeScreen(
                    onNavigateToSettings     = { navController.navigate("settings") },
                    onNavigateToContact      = { id -> navController.navigate("contact_detail/$id") },
                    onNavigateToCompany      = { id -> navController.navigate("company_detail/$id") },
                    onNavigateToCalendarItem = { id -> navController.navigate("calendar_item_detail/$id") },
                    onNavigateToCalendar     = { navController.navigate("calendar") },
                    onNavigateToContacts     = { navController.navigate("contacts") },
                    onNavigateToScan         = { navController.navigate("scan_card") }
                )
            }
            composable("scan_card") {
                // V2.2: скан больше не создаёт контакт сам — кладёт разобранный
                // черновик в ContactDraftSession и уходит на единую форму
                // Create/Edit (contact_create), которая его прочитает как prefill.
                ScanCardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onDraftReady = {
                        navController.navigate("contact_create") { popUpTo("scan_card") { inclusive = true } }
                    }
                )
            }
            composable("contacts") {
                ContactsScreen(
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToContact = { contactId -> navController.navigate("contact_detail/$contactId") },
                    onNavigateToCreateContact = { navController.navigate("contact_create") }
                )
            }
            composable("companies") {
                CompaniesScreen(
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToCompany = { companyId -> navController.navigate("company_detail/$companyId") },
                    onNavigateToCreateCompany = { navController.navigate("company_create") }
                )
            }
            composable("calendar") {
                CalendarScreen(
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToCalendarItem = { calendarItemId -> navController.navigate("calendar_item_detail/$calendarItemId") },
                    onNavigateToCreateCalendarItem = { navController.navigate("calendar_item_create") }
                )
            }
            composable("calendar_item_detail/{calendarItemId}") { backStackEntry ->
                val calendarItemId = backStackEntry.arguments?.getString("calendarItemId") ?: return@composable
                CalendarItemDetailScreen(
                    calendarItemId = calendarItemId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToContact = { contactId -> navController.navigate("contact_detail/$contactId") },
                    onNavigateToCompany = { companyId -> navController.navigate("company_detail/$companyId") },
                    onNavigateToEdit = { navController.navigate("calendar_item_edit/$calendarItemId") }
                )
            }
            composable(
                "calendar_item_create?contactId={contactId}&companyId={companyId}",
                arguments = listOf(
                    navArgument("contactId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("companyId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                AfterDataLoaded {
                    CalendarItemEditScreen(
                        calendarItemId = null,
                        onNavigateBack = { navController.popBackStack() },
                        prefillContactId = backStackEntry.arguments?.getString("contactId"),
                        prefillCompanyId = backStackEntry.arguments?.getString("companyId")
                    )
                }
            }
            composable("calendar_item_edit/{calendarItemId}") { backStackEntry ->
                val calendarItemId = backStackEntry.arguments?.getString("calendarItemId")
                AfterDataLoaded {
                    CalendarItemEditScreen(
                        calendarItemId = calendarItemId,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
            composable("map") {
                MapScreen(
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToContact = { contactId -> navController.navigate("contact_detail/$contactId") },
                    onNavigateToCompany = { companyId -> navController.navigate("company_detail/$companyId") }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToLanguage = { navController.navigate("settings_language") },
                    onNavigateToNotifications = { navController.navigate("settings_notifications") },
                    onNavigateToCalendar = { navController.navigate("settings_calendar") },
                    onNavigateToImportExport = { navController.navigate("settings_import_export") },
                    onNavigateToAppearance = { navController.navigate("settings_appearance") },
                    onNavigateToPrivacy = { navController.navigate("settings_privacy") },
                    onNavigateToDuplicates = { navController.navigate("settings_duplicates") },
                    onNavigateToCompanyDuplicates = { navController.navigate("company_duplicates") },
                    onNavigateToContactDisplay = { navController.navigate("settings_contact_display") }
                )
            }
            composable("settings_duplicates") {
                DuplicatesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToResolve = { ids -> navController.navigate("merge_resolve/${ids.joinToString(",")}") },
                    onNavigateToEdit = { id -> navController.navigate("contact_edit/$id") }
                )
            }
            composable("merge_resolve/{ids}") { backStackEntry ->
                val ids = backStackEntry.arguments?.getString("ids")?.split(",").orEmpty()
                MergeResolveScreen(
                    contactIds = ids,
                    // Слияние выполнено — сразу открыть карточку итогового контакта
                    // (владелец правит объединённые поля тут же), иначе (отмена
                    // слияния через Snackbar / не выполнено) — назад к выбору.
                    onDone = { mergedId ->
                        if (mergedId != null) {
                            navController.navigate("contact_detail/$mergedId") {
                                popUpTo("settings_duplicates") { inclusive = true }
                            }
                        } else {
                            navController.popBackStack("settings_duplicates", inclusive = false)
                        }
                    }
                )
            }
            composable("company_duplicates") {
                CompanyDuplicatesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToResolve = { ids -> navController.navigate("company_merge_resolve/${ids.joinToString(",")}") }
                )
            }
            composable("company_merge_resolve/{ids}") { backStackEntry ->
                val ids = backStackEntry.arguments?.getString("ids")?.split(",").orEmpty()
                CompanyMergeResolveScreen(
                    companyIds = ids,
                    // Тот же паттерн, что и у слияния контактов: успех — сразу
                    // карточка итоговой компании, отмена/неудача — назад к выбору.
                    onDone = { mergedId ->
                        if (mergedId != null) {
                            navController.navigate("company_detail/$mergedId") {
                                popUpTo("company_duplicates") { inclusive = true }
                            }
                        } else {
                            navController.popBackStack("company_duplicates", inclusive = false)
                        }
                    }
                )
            }
            composable("settings_language") {
                LanguageSettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("settings_notifications") {
                NotificationSettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("settings_calendar") {
                CalendarSettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("settings_contact_display") {
                ContactDisplaySettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("settings_import_export") {
                ImportExportSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToImportContacts = { navController.navigate("import_contacts") },
                    onNavigateToRestorePreview = { navController.navigate("restore_preview") }
                )
            }
            // RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22): непустая БД -> сюда, а не
            // автоматический merge сразу после выбора файла (requirement 28 — ключевое изменение UX).
            composable("restore_preview") {
                RestorePreviewScreen(
                    onNavigateBack = {
                        RestorePreviewSession.clear()
                        navController.popBackStack()
                    },
                    onRestoreComplete = { navController.popBackStack("settings_import_export", inclusive = false) }
                )
            }
            composable("import_contacts") {
                ImportContactsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPreview = { navController.navigate("import_preview") }
                )
            }
            composable("import_preview") {
                ImportPreviewScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToResult = { navController.navigate("import_result") { popUpTo("settings") } },
                    onNavigateToDuplicates = { navController.navigate("import_duplicates") }
                )
            }
            composable("import_duplicates") {
                ImportDuplicatesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToResult = { navController.navigate("import_result") { popUpTo("settings") } }
                )
            }
            composable("import_result") {
                ImportResultScreen(
                    onNavigateToContacts = { navController.openTabFromFlow("contacts") },
                    onNavigateToCompanies = { navController.openTabFromFlow("companies") },
                    onNavigateToSettings = { navController.navigate("settings") { popUpTo("home") } }
                )
            }
            composable("settings_appearance") {
                AppearanceSettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("settings_privacy") {
                PrivacySettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable("contact_detail/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactDetailScreen(
                    contactId = contactId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToCalendarItem = { calendarItemId -> navController.navigate("calendar_item_detail/$calendarItemId") },
                    onNavigateToEdit = { navController.navigate("contact_edit/$contactId") },
                    onNavigateToCreateCalendarItem = { navController.navigate("calendar_item_create?contactId=$contactId") },
                    onNavigateToContact = { otherId -> navController.navigate("contact_detail/$otherId") },
                    onNavigateToCheatSheet = { navController.navigate("cheat_sheet/$contactId") },
                    onNavigateToCompany = { companyId -> navController.navigate("company_detail/$companyId") }
                )
            }
            composable("cheat_sheet/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                CheatSheetScreen(
                    contactId = contactId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("contact_create") {
                ContactEditScreen(
                    contactId = null,
                    onNavigateBack = { navController.popBackStack() },
                    // Созданный контакт открываем сразу (форма уходит со стека)
                    onCreated = { id ->
                        navController.popBackStack()
                        navController.navigate("contact_detail/$id")
                    },
                    // Тап по живой подсказке «похоже, уже есть» — форма остаётся
                    // на стеке (черновик не теряется), просто уходим вперёд на найденный контакт.
                    onNavigateToContact = { otherId -> navController.navigate("contact_detail/$otherId") },
                    onNavigateToScan = { navController.navigate("scan_card") }
                )
            }
            composable("contact_edit/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId")
                AfterDataLoaded {
                    ContactEditScreen(
                        contactId = contactId,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToContact = { otherId -> navController.navigate("contact_detail/$otherId") }
                    )
                }
            }
            composable("company_detail/{companyId}") { backStackEntry ->
                val companyId = backStackEntry.arguments?.getString("companyId") ?: return@composable
                CompanyDetailScreen(
                    companyId = companyId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToContact = { contactId -> navController.navigate("contact_detail/$contactId") },
                    onNavigateToEdit = { navController.navigate("company_edit/$companyId") },
                    onNavigateToCreateCalendarItem = { navController.navigate("calendar_item_create?companyId=$companyId") }
                )
            }
            composable("company_create") {
                CompanyEditScreen(
                    companyId = null,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("company_edit/{companyId}") { backStackEntry ->
                val companyId = backStackEntry.arguments?.getString("companyId")
                AfterDataLoaded {
                    CompanyEditScreen(
                        companyId = companyId,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
        
        // ФИКС: тот же баг, что и в BiometricGate (2026-07-05, доработано 2026-07-11) —
        // LocalContext.current здесь — createConfigurationContext из LocalizedApp,
        // НЕ ContextWrapper над Activity — findActivity() из него до Activity в
        // принципе не доходит (подтверждено живым тестом). LocalView.current.context
        // LocalizedApp не подменяет — оттуда findActivity() работает.
        val activity = androidx.compose.ui.platform.LocalView.current.context.findActivity()

        // PERSISTENCE PACK #2: коммит записи в Room не удался — память уже пересобрана из БД
        // (AppStateStore.onWriteFailed), пользователю явно сообщаем, что показано реально
        // сохранённое, а не оптимистичное состояние. Экран, ждавший коммит, остался открытым.
        val failureContext = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(AppStateStore.writeFailureCount) {
            if (AppStateStore.writeFailureCount > 0) {
                android.widget.Toast.makeText(
                    failureContext, failureContext.getString(R.string.write_failed_resynced),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        androidx.compose.runtime.LaunchedEffect(activity?.intent) {
            val calendarItemId = activity?.intent?.getStringExtra("calendarItemId")
            if (calendarItemId != null) {
                navController.navigate("calendar_item_detail/$calendarItemId")
                activity.intent?.removeExtra("calendarItemId")
            }

            // Входящий приём контакта: кто-то поделился vCard из системного share-sheet (ACTION_SEND) или
            // открыл .vcf «через» это приложение (ACTION_VIEW). PHONE / VCF PACK #4: КАНОНИЧЕСКИЙ путь
            // VcfImport (ContentResolver → БАЙТЫ → декодирование по ENCODING/CHARSET → разбор) — тот же,
            // что и у ручного «Импорт → vCard»; собственного чтения/парсинга здесь больше нет.
            val incoming = activity?.intent
            if (activity != null && incoming != null) {
                val load = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.aistudio.socialsphere.crmlxb.utils.VcfImport.loadFromIntent(activity, incoming)
                }
                if (load != null) {
                    when (load) {
                        is com.aistudio.socialsphere.crmlxb.utils.VcfImport.Load.Loaded -> {
                            ImportSession.clear()
                            ImportSession.candidates.addAll(load.candidates)
                            navController.navigate("import_preview")
                        }
                        // Раньше молча ничего не происходило — пользователь не понимал, что случилось
                        com.aistudio.socialsphere.crmlxb.utils.VcfImport.Load.Empty ->
                            android.widget.Toast.makeText(activity, activity.getString(R.string.imp_no_vcard_contacts), android.widget.Toast.LENGTH_LONG).show()
                        is com.aistudio.socialsphere.crmlxb.utils.VcfImport.Load.Error ->
                            android.widget.Toast.makeText(activity, activity.getString(R.string.imp_read_error, load.message), android.widget.Toast.LENGTH_LONG).show()
                    }
                    // Сбрасываем action — иначе повторная рекомпозиция/поворот экрана переиграл бы тот же импорт.
                    activity.intent?.action = android.content.Intent.ACTION_MAIN
                }
            }
        }
    }
}
