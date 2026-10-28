package com.aistudio.socialsphere.crmlxb

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Защита режима окна под клавиатуру (2026-10-05, FINAL CONTACT V2.2 CLEANUP, Issue 2).
 *
 * MainActivity — edge-to-edge, а NavHost сам обрабатывает IME через imePadding() (MainActivity.kt, §68).
 * При дефолтном `adjustPan` система ДОПОЛНИТЕЛЬНО панорамирует окно: на форме контакта (фиксированная
 * шапка + вкладки над скроллом) шапка уезжала за верх экрана, пока в фокусе глубокое поле. С
 * `adjustResize` окно не панорамируется, а за сдвиг отвечает imePadding — шапка остаётся на месте.
 * Проверено на эмуляторах API 35 и API 24 на экранах контакта/события/компании/заметки/поиска
 * (сравнение с `adjustPan` — в отчёте задачи). Этот тест не даёт молча вернуть дефолт правкой манифеста.
 */
@RunWith(RobolectricTestRunner::class)
class WindowSoftInputModeTest {

    @Test
    fun `MainActivity declares adjustResize so a pan does not double-handle imePadding`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, MainActivity::class.java), PackageManager.GET_META_DATA)
        assertEquals(
            "windowSoftInputMode главной Activity должен быть adjustResize (adjustPan двигает фиксированную шапку формы контакта за экран)",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        )
    }
}
