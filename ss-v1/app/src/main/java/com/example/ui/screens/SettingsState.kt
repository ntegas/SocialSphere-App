package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class AppLanguage(val code: String, val displayName: String) {
    RUSSIAN("ru", "Русский"),
    ENGLISH("en", "English"),
    GREEK("el", "Ελληνικά")
}

object AppSettings {
    val currentLanguage = mutableStateOf(AppLanguage.RUSSIAN)
    val isNotificationsEnabled     = mutableStateOf(true)
    val isDarkTheme                = mutableStateOf(false)

    // Notification timing preferences
    val defaultReminderTime        = mutableStateOf("за 1 день")
    val birthdayReminderTimes      = mutableStateOf(setOf("в день события", "за 1 день"))
    val giftReminderTime           = mutableStateOf("за 3 дня")
    val meetingReminderTime        = mutableStateOf("за 1 час")
    val callReminderTime           = mutableStateOf("за 10 минут")
    val showOverdue                = mutableStateOf(true)
    val repeatOverdueVisually      = mutableStateOf(false)
}

@Composable
fun LocalizedApp(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val locale = Locale(language.code)

    // Apply locale globally
    Locale.setDefault(locale)

    // Create a localized context with updated configuration
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    val localizedContext = context.createConfigurationContext(config)

    // Also update the base context resources so stringResource() picks it up
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)

    CompositionLocalProvider(
        LocalContext provides localizedContext
    ) {
        content()
    }
}
