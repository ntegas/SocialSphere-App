package com.aistudio.socialsphere.crmlxb

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        // Ежедневное «пора связаться» (по ритму общения)
        com.aistudio.socialsphere.crmlxb.utils.NotificationScheduler
            .scheduleStaleCheck(applicationContext)

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

        // ФИКС (аудит 2026-08-11, PIN_BIOMETRIC_UX_PROMPT.md §3.1): без FLAG_SECURE
        // содержимое приложения (контакты, заметки) видно в скриншоте карточки в
        // Recents/переключателе задач и снимается сторонними скриншот-инструментами —
        // даже когда AppLockScreen прямо сейчас не показан. Стандартная практика для
        // любого приложения с PIN/биометрией и личными данными. Ставим ВСЕГДА (не
        // только при включённой защите) — та же приватность-по-умолчанию, что уже
        // выбрана для allowBackup=false/отсутствия сетевой поверхности.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()
        setContent {
            val isDarkTheme by AppSettings.isDarkTheme

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

    if (AppSettings.appLockEnabledSafe() && !AppLockState.unlocked) {
        AppLockScreen(onUnlocked = { AppLockState.unlocked = true })
        return
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                val onNavClick: (String) -> Unit = { route ->
                    val alreadyHere = currentDestination?.hierarchy?.any { it.route == route } == true
                    if (!alreadyHere) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
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
                ScanCardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onCreated = { id ->
                        navController.popBackStack()
                        navController.navigate("contact_detail/$id")
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
                CalendarItemEditScreen(
                    calendarItemId = null,
                    onNavigateBack = { navController.popBackStack() },
                    prefillContactId = backStackEntry.arguments?.getString("contactId"),
                    prefillCompanyId = backStackEntry.arguments?.getString("companyId")
                )
            }
            composable("calendar_item_edit/{calendarItemId}") { backStackEntry ->
                val calendarItemId = backStackEntry.arguments?.getString("calendarItemId")
                CalendarItemEditScreen(
                    calendarItemId = calendarItemId,
                    onNavigateBack = { navController.popBackStack() }
                )
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
                    onNavigateToResolve = { ids -> navController.navigate("merge_resolve/${ids.joinToString(",")}") }
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
                    onNavigateToImportContacts = { navController.navigate("import_contacts") }
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
                    onNavigateToContacts = { navController.navigate("contacts") { popUpTo("home") } },
                    onNavigateToCompanies = { navController.navigate("companies") { popUpTo("home") } },
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
                    onNavigateToContact = { otherId -> navController.navigate("contact_detail/$otherId") }
                )
            }
            composable("contact_edit/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId")
                ContactEditScreen(
                    contactId = contactId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToContact = { otherId -> navController.navigate("contact_detail/$otherId") }
                )
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
                CompanyEditScreen(
                    companyId = companyId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
        
        // ФИКС: тот же баг, что и в BiometricGate (2026-07-05, доработано 2026-07-11) —
        // LocalContext.current здесь — createConfigurationContext из LocalizedApp,
        // НЕ ContextWrapper над Activity — findActivity() из него до Activity в
        // принципе не доходит (подтверждено живым тестом). LocalView.current.context
        // LocalizedApp не подменяет — оттуда findActivity() работает.
        val activity = androidx.compose.ui.platform.LocalView.current.context.findActivity()
        androidx.compose.runtime.LaunchedEffect(activity?.intent) {
            val calendarItemId = activity?.intent?.getStringExtra("calendarItemId")
            if (calendarItemId != null) {
                navController.navigate("calendar_item_detail/$calendarItemId")
                activity.intent?.removeExtra("calendarItemId")
            }

            // Входящий приём контакта: кто-то поделился vCard из системного
            // share-sheet (ACTION_SEND) или открыл .vcf «через» это приложение
            // (ACTION_VIEW). Переиспользуем существующий парсер/флоу предпросмотра
            // импорта — тот же путь, что и у ручного «Импорт из файла» (2026-07-22).
            val incoming = activity?.intent
            val incomingAction = incoming?.action
            val vcardMime = incoming?.type == "text/vcard" || incoming?.type == "text/x-vcard"
            val isIncomingShare = vcardMime &&
                (incomingAction == android.content.Intent.ACTION_SEND || incomingAction == android.content.Intent.ACTION_VIEW)
            if (activity != null && incoming != null && isIncomingShare) {
                val uri = if (incomingAction == android.content.Intent.ACTION_SEND)
                    androidx.core.content.IntentCompat.getParcelableExtra(incoming, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else incoming.data
                if (uri != null) {
                    val candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val content = activity.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.readText() ?: ""
                            com.aistudio.socialsphere.crmlxb.utils.parseVCard(content)
                        } catch (e: Exception) { emptyList() }
                    }
                    if (candidates.isNotEmpty()) {
                        ImportSession.clear()
                        ImportSession.candidates.addAll(candidates)
                        navController.navigate("import_preview")
                    }
                }
                // Сбрасываем action — иначе повторная рекомпозиция/поворот экрана
                // переиграл бы тот же импорт снова.
                activity.intent?.action = android.content.Intent.ACTION_MAIN
            }
        }
    }
}
