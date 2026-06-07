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
    val isNotificationsEnabled = mutableStateOf(true)
    val isDarkTheme = mutableStateOf(false)
}

@Composable
fun LocalizedApp(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val locale = Locale(language.code)
    Locale.setDefault(locale)
    
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    
    val localizedContext = context.createConfigurationContext(config)
    CompositionLocalProvider(
        LocalContext provides localizedContext
    ) {
        content()
    }
}
