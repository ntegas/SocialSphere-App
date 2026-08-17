package com.aistudio.socialsphere.crmlxb.ui.screens

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Socialsphere Pro — freemium-квоты (2026-08-16). Один @Test-метод намеренно
 * (не несколько): AppSettings — object-синглтон на весь JVM-процесс с
 * `by lazy`-полями, которые нельзя переинициализировать между тестами (тот же
 * класс проблемы, что уже пойман и задокументирован для AppStateStore в
 * AppStateStoreRestoreTest.kt — там лечится resetForTests(), но для
 * AppSettings это означало бы сбрасывать ДЕСЯТКИ несвязанных lazy-настроек
 * ради трёх полей freemium — непропорционально). Один тест — гонка исключена
 * по построению, независимо от порядка запуска.
 */
@RunWith(RobolectricTestRunner::class)
class AppSettingsSubscriptionTest {

    @Test
    fun freemiumQuotas_rolloverAndUsageTracking() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.init(ctx)

        // ── Сканы: базовая квота 4/мес, списание уменьшает остаток ──
        assertEquals(4, AppSettings.scansRemainingThisMonth())
        AppSettings.recordScanUsed()
        AppSettings.recordScanUsed()
        assertEquals(2, AppSettings.scansRemainingThisMonth())

        // ── Контакты с телефоном: базовая квота 3/мес ──
        assertEquals(3, AppSettings.contactsWithPhoneRemainingThisMonth())
        AppSettings.recordContactWithPhoneUsed()
        assertEquals(2, AppSettings.contactsWithPhoneRemainingThisMonth())
        AppSettings.recordContactWithPhoneUsed()
        AppSettings.recordContactWithPhoneUsed()
        assertEquals(0, AppSettings.contactsWithPhoneRemainingThisMonth())
        // Не уходит в минус даже если списать сверх квоты.
        AppSettings.recordContactWithPhoneUsed()
        assertEquals(0, AppSettings.contactsWithPhoneRemainingThisMonth())

        // ── Смена месяца сбрасывает счётчик (владелец переехал в новый месяц) ──
        // AppSettings.scanQuotaMonthKey — уже сконструированный PersistedMutableState;
        // он читает SharedPreferences РОВНО ОДИН РАЗ, при первом обращении (см. класс
        // PersistedMutableState) и никогда не перечитывает диск на каждый .value —
        // подменить сохранённый месяц «из-под» него, не пересоздавая процесс, нельзя.
        // Реальный rollover в проде происходит МЕЖДУ запусками приложения (новый
        // процесс → новый PersistedMutableState → свежее чтение). Проверяем саму
        // логику rollover в изоляции — monthlyRemaining()/monthlyRecordUsed()
        // (internal специально для этого) с локально сконструированными
        // mutableStateOf, без завязки на кэш AppSettings.
        val nowKey = java.time.YearMonth.now().toString()

        // Ключ месяца УЖЕ текущий, использовано всё — 0 осталось, без сброса.
        assertEquals(0, AppSettings.monthlyRemaining(mutableStateOf(4), mutableStateOf(nowKey), 4))

        // Ключ месяца устаревший (данные из другого периода, «использовано 4» —
        // хвост из прошлого месяца) → обязан сброситься на полную квоту И
        // переписать сам ключ на текущий месяц.
        val staleUsed = mutableStateOf(4)
        val staleMonthKey = mutableStateOf("2000-01")
        assertEquals(4, AppSettings.monthlyRemaining(staleUsed, staleMonthKey, 4))
        assertEquals(nowKey, staleMonthKey.value)
        assertEquals(0, staleUsed.value)

        // В пределах ТЕКУЩЕГО месяца — списание накапливается, не сбрасывается.
        val sameMonth = mutableStateOf(nowKey)
        val sameMonthUsed = mutableStateOf(0)
        AppSettings.monthlyRecordUsed(sameMonthUsed, sameMonth, 4)
        AppSettings.monthlyRecordUsed(sameMonthUsed, sameMonth, 4)
        assertEquals(2, AppSettings.monthlyRemaining(sameMonthUsed, sameMonth, 4))

        // ── Подписка снимает оба лимита (Int.MAX_VALUE = «безлимит») ──
        AppSettings.subscriptionActive.value = true
        assertEquals(Int.MAX_VALUE, AppSettings.scansRemainingThisMonth())
        assertEquals(Int.MAX_VALUE, AppSettings.contactsWithPhoneRemainingThisMonth())
        // recordScanUsed() — no-op для подписчика, не должен падать и не должен
        // портить счётчик на будущее (если подписка закончится, лимит обязан
        // считаться от РЕАЛЬНОГО использования, не от накопленного «в фоне» во
        // время Pro). До активации подписки уже было списано 2 скана (строки
        // 31-32) — отключение подписки должно вернуть ровно этот же остаток
        // (2), а не полную квоту: recordScanUsed() во время Pro не добавил
        // третье списание.
        AppSettings.recordScanUsed()
        AppSettings.subscriptionActive.value = false
        assertEquals(2, AppSettings.scansRemainingThisMonth())
    }
}
