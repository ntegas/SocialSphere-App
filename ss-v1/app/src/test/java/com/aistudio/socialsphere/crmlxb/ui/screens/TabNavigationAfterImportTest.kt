package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aistudio.socialsphere.crmlxb.navigateToTab
import com.aistudio.socialsphere.crmlxb.openTabFromFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Баг эмулятора (Contact UX Baseline): после «Import results → Contacts» нижняя вкладка «Home» ничего не делала /
 * открывала «Contacts» до перезапуска приложения. Тест гоняет настоящий NavController с теми же маршрутами и теми же
 * вызовами, что MainActivity, и проверяет ИТОГ — какой экран открыт, а не внутреннюю логику.
 *
 * Причина доказана воспроизведением (2026-10-28, прежний вызов `navigate("contacts") { popUpTo("home") }` — без
 * saveState/restoreState): стек шёл [home, settings, import_result] → [home, contacts] → (вкладка «Компании»)
 * [home, companies] → (вкладка «Home») [home, contacts] — вместо Главной открывался «Contacts». Этот же сценарий на
 * новом пути (openTabFromFlow) даёт «home». Сам баг в набор не закреплён (тест на «сломано» был бы хрупким к версии
 * библиотеки навигации); защита — тесты ниже на ИТОГ нового пути.
 *
 * Отдельный класс (один Compose UI-класс на запуск Gradle — §60.11).
 */
@RunWith(RobolectricTestRunner::class)
class TabNavigationAfterImportTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun render() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                listOf("home", "contacts", "companies", "calendar", "map", "settings", "import_result")
                    .forEach { r -> composable(r) { Text(r) } }
            }
        }
        compose.waitForIdle()
    }

    private fun go(block: NavHostController.() -> Unit) {
        compose.runOnUiThread { nav.block() }
        compose.waitForIdle()
    }

    private fun route() = nav.currentBackStackEntry?.destination?.route
    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    /** Путь пользователя до результата импорта: Главная → Настройки → … → Результат (стек схлопнут до settings). */
    private fun reachImportResult() {
        go { navigate("settings") }
        go { navigate("import_result") { popUpTo("settings") } }
        assertEquals("import_result", route())
    }

    @Test fun newWay_openContactsFromResult_thenAllTabsWork() {
        render(); reachImportResult()
        go { openTabFromFlow("contacts") }
        assertEquals("contacts", route())
        go { navigateToTab("home") }
        assertEquals("«Home» открывает Главную (стек: ${stack()})", "home", route())
        go { navigateToTab("contacts") }
        assertEquals("contacts", route())
        go { navigateToTab("companies") }
        go { navigateToTab("home") }
        assertEquals("home", route())
    }

    @Test fun newWay_companiesFromResult_thenHome() {
        render(); reachImportResult()
        go { openTabFromFlow("companies") }
        assertEquals("companies", route())
        go { navigateToTab("home") }
        assertEquals("home", route())
    }

    @Test fun tabsStillWork_withoutAnyImportFlow() {
        render()
        go { navigateToTab("contacts") }; go { navigateToTab("calendar") }; go { navigateToTab("home") }
        assertEquals("home", route())
        go { navigateToTab("map") }; go { navigateToTab("contacts") }
        assertEquals("contacts", route())
    }
}
