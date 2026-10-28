package com.aistudio.socialsphere.crmlxb

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * Переход на вкладку нижней панели — канонический паттерн: один navigate с popUpTo(start){saveState} + restoreState +
 * singleTop (состояние каждой вкладки сохраняется при уходе и возвращается при возврате).
 * Единственное место, где это записано: нижняя панель и «Открыть контакты/компании» из результата импорта идут сюда.
 */
internal fun NavController.navigateToTab(route: String) {
    val alreadyHere = currentDestination?.hierarchy?.any { it.route == route } == true
    if (alreadyHere) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Выйти из «побочного» потока (настройки → импорт → результат) на корень стека и открыть вкладку тем же путём, что
 * нижняя панель.
 *
 * Баг эмулятора (Contact UX Baseline, доказан 2026-10-28): результат импорта открывал вкладку голым
 * `navigate("contacts") { popUpTo("home") }` — без saveState/restoreState. После этого стек вкладок оказывался в
 * состоянии, которого нижняя панель не ожидает, и тап по «Home» открывал «Contacts» до перезапуска приложения.
 */
internal fun NavController.openTabFromFlow(route: String) {
    popBackStack(graph.findStartDestination().id, inclusive = false)
    navigateToTab(route)
}
