package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape

/**
 * Первый запуск целиком — хост шагов тура. Заменяет одиночный OnboardingScreen
 * в MainActivity.SocialsphereApp(). Флаг AppSettings.onboardingCompleted
 * ставится РОВНО в одном месте — кнопка на экране FINAL. Важно: SocialsphereApp()
 * читает этот же флаг через `by` (State) и перерисовывается в тот же момент,
 * когда флаг меняется, — если бы флаг ставился раньше (например, на «Пропустить»
 * шага импорта, сразу переходя на FINAL), тур пропал бы из композиции мгновенно,
 * ещё до того как FINAL успел бы отрендериться (проверено на устройстве — баг,
 * не гипотеза). Импорт-завершение и «Пропустить» только переводят step = FINAL,
 * саму отметку «тур пройден» ставит исключительно кнопка на этом экране.
 *
 * Не HorizontalPager: вся навигация — по кнопкам «Далее»/«Пропустить»/системный
 * «Назад» (тот же выбор, что для вкладок персон и демо-сценариев) — меньше
 * риска конфликта жестов с под-шагами импорта (ImportContactsScreen →
 * ImportPreviewScreen → ImportDuplicatesScreen).
 *
 * Ни один шаг тура не пишет в БД, кроме настоящего импорта (тот же код, что
 * и в Настройках, ImportScreens.kt не тронут ни одной правкой).
 */
private enum class TourStep { COVER, PERSONA, DEMO, IMPORT_INTRO, IMPORT_CONTACTS, IMPORT_PREVIEW, IMPORT_DUPLICATES, FINAL }

@Composable
fun OnboardingTourScreen(onFinish: () -> Unit) {
    var step by remember { mutableStateOf(TourStep.COVER) }

    when (step) {
        TourStep.COVER -> {
            OnboardingScreen(onStart = { step = TourStep.PERSONA })
        }
        TourStep.PERSONA -> {
            BackHandler { step = TourStep.COVER }
            OnboardingPersonaScreen(
                onNext = { step = TourStep.DEMO },
                onSkip = { step = TourStep.IMPORT_INTRO }
            )
        }
        TourStep.DEMO -> {
            BackHandler { step = TourStep.PERSONA }
            OnboardingDemoScreen(onNext = { step = TourStep.IMPORT_INTRO })
        }
        TourStep.IMPORT_INTRO -> {
            BackHandler { step = TourStep.DEMO }
            OnboardingImportIntroScreen(
                onImportNow = { step = TourStep.IMPORT_CONTACTS },
                onSkip = { step = TourStep.FINAL }
            )
        }
        TourStep.IMPORT_CONTACTS -> {
            BackHandler { step = TourStep.IMPORT_INTRO }
            ImportContactsScreen(
                onNavigateBack = { step = TourStep.IMPORT_INTRO },
                onNavigateToPreview = { step = TourStep.IMPORT_PREVIEW }
            )
        }
        TourStep.IMPORT_PREVIEW -> {
            BackHandler { step = TourStep.IMPORT_CONTACTS }
            ImportPreviewScreen(
                onNavigateBack = { step = TourStep.IMPORT_CONTACTS },
                onNavigateToResult = { step = TourStep.FINAL },
                onNavigateToDuplicates = { step = TourStep.IMPORT_DUPLICATES }
            )
        }
        TourStep.IMPORT_DUPLICATES -> {
            BackHandler { step = TourStep.IMPORT_PREVIEW }
            ImportDuplicatesScreen(
                onNavigateBack = { step = TourStep.IMPORT_PREVIEW },
                onNavigateToResult = { step = TourStep.FINAL }
            )
        }
        TourStep.FINAL -> {
            OnboardingFinalScreen(onFinish = {
                AppSettings.onboardingCompleted.value = true
                onFinish()
            })
        }
    }
}

/**
 * Шаг 4а — вводная карточка перед импортом (тот же тёмный макет, что и у
 * персон/демо). Реальное действие начинается на следующем шаге (IMPORT_CONTACTS
 * — настоящий ImportContactsScreen, без единой правки в ImportScreens.kt).
 */
@Composable
private fun OnboardingImportIntroScreen(onImportNow: () -> Unit, onSkip: () -> Unit) {
    Box(Modifier.fillMaxSize().background(ObBg).statusBarsPadding().navigationBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Text(
                stringResource(R.string.onboarding_brand).uppercase(),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = ObGold
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.onboarding_step_import_title),
                fontFamily = AureliaSerif, fontWeight = FontWeight.W800,
                fontSize = 30.sp, lineHeight = 36.sp, color = ObTx
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.onboarding_step_import_body),
                fontSize = 16.sp, lineHeight = 24.sp, color = ObMuted
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "🔒 " + stringResource(R.string.onboarding_import_privacy),
                fontSize = 13.sp, lineHeight = 18.sp, color = ObMuted
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onImportNow,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = SocialShape.R18,
                colors = ButtonDefaults.buttonColors(containerColor = ObBrand, contentColor = ObBg)
            ) {
                Text(stringResource(R.string.onboarding_import_cta), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.onboarding_import_skip),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ObMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable(onClick = onSkip)
            )
        }
    }
}

@Composable
private fun OnboardingFinalScreen(onFinish: () -> Unit) {
    val imported = ImportResultStats.contactsImported
    Box(Modifier.fillMaxSize().background(ObBg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.onboarding_finish_title),
                fontFamily = AureliaSerif, fontWeight = FontWeight.W800,
                fontSize = 34.sp, lineHeight = 40.sp, color = ObTx
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (imported > 0) stringResource(R.string.onboarding_finish_imported, imported)
                else stringResource(R.string.onboarding_finish_empty),
                fontSize = 16.sp, lineHeight = 24.sp, color = ObMuted
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = SocialShape.R18,
                colors = ButtonDefaults.buttonColors(containerColor = ObBrand, contentColor = ObBg)
            ) {
                Text(stringResource(R.string.onboarding_start), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(20.dp))
            }
        }
    }
}
