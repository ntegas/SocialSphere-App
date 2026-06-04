package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.data.AppStateStore
import com.example.data.local.SocialsphereDatabase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = SocialsphereDatabase.getDatabase(applicationContext)
        AppStateStore.initialize(db)

        enableEdgeToEdge()
        setContent {
            // FIX: use 'by' delegate so recomposition triggers on change
            val currentLanguage by AppSettings.currentLanguage
            val isDarkTheme     by AppSettings.isDarkTheme

            MyApplicationTheme(darkTheme = isDarkTheme) {
                // key() forces full subtree recreation when language changes
                key(currentLanguage) {
                    LocalizedApp(language = currentLanguage) {
                        SocialsphereApp()
                    }
                }
            }
        }
    }
}

@Composable
fun SocialsphereApp() {
    val navController = rememberNavController()
    
    val navigationItems = listOf(
        Triple("home", R.string.nav_home, Icons.Default.Home),
        Triple("contacts", R.string.nav_contacts, Icons.Default.Contacts),
        Triple("companies", R.string.nav_companies, Icons.Default.Business),
        Triple("calendar", R.string.nav_calendar, Icons.Default.CalendarToday),
        Triple("map", R.string.nav_map, Icons.Default.Map)
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            
            val showBottomBar = navigationItems.any { it.first == currentDestination?.route }
            
            if (showBottomBar) {
                NavigationBar {
                    navigationItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.third, contentDescription = stringResource(item.second)) },
                            label = { Text(stringResource(item.second), maxLines = 1) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.first } == true,
                            onClick = {
                                navController.navigate(item.first) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    onNavigateToSettings    = { navController.navigate("settings") },
                    onNavigateToContact     = { id -> navController.navigate("contact_detail/$id") },
                    onNavigateToCompany     = { id -> navController.navigate("company_detail/$id") },
                    onNavigateToCalendarItem = { id -> navController.navigate("calendar_item_detail/$id") }
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
            composable("calendar_item_create") {
                CalendarItemEditScreen(
                    calendarItemId = null,
                    onNavigateBack = { navController.popBackStack() }
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
                    onNavigateToPrivacy = { navController.navigate("settings_privacy") }
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
                    onNavigateToCreateCalendarItem = { navController.navigate("calendar_item_create") },
                    onNavigateToContact = { otherId -> navController.navigate("contact_detail/$otherId") },
                    onNavigateToCheatSheet = { navController.navigate("cheat_sheet/$contactId") }
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
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("contact_edit/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId")
                ContactEditScreen(
                    contactId = contactId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("company_detail/{companyId}") { backStackEntry ->
                val companyId = backStackEntry.arguments?.getString("companyId") ?: return@composable
                CompanyDetailScreen(
                    companyId = companyId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToContact = { contactId -> navController.navigate("contact_detail/$contactId") },
                    onNavigateToEdit = { navController.navigate("company_edit/$companyId") },
                    onNavigateToCreateCalendarItem = { navController.navigate("calendar_item_create") }
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
        
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        androidx.compose.runtime.LaunchedEffect(activity?.intent) {
            val calendarItemId = activity?.intent?.getStringExtra("calendarItemId")
            if (calendarItemId != null) {
                navController.navigate("calendar_item_detail/$calendarItemId")
                activity.intent?.removeExtra("calendarItemId")
            }
        }
    }
}
