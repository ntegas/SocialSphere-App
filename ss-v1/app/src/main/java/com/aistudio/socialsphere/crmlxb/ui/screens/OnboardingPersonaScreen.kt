package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape

/**
 * Шаг 2 тура — «для кого это». 4 сценария использования вместо списка
 * функций: каждый — реальная выгода, привязанная к уже существующему полю/
 * экрану приложения (см. таблицу в SOCIALSPHERE_KNOWLEDGE.md), не выдумана.
 * Переключение вкладок — тап (не свайп): конфликта с внешним свайпом
 * HorizontalPager тура нет.
 */
private data class Persona(
    val icon: ImageVector,
    val labelRes: Int,
    val bullet1Res: Int,
    val bullet2Res: Int,
)

private val PERSONAS = listOf(
    Persona(Icons.Default.Home, R.string.onboarding_persona_personal, R.string.onboarding_persona_personal_b1, R.string.onboarding_persona_personal_b2),
    Persona(Icons.Default.TrendingUp, R.string.onboarding_persona_sales, R.string.onboarding_persona_sales_b1, R.string.onboarding_persona_sales_b2),
    Persona(Icons.Default.Star, R.string.onboarding_persona_clients, R.string.onboarding_persona_clients_b1, R.string.onboarding_persona_clients_b2),
    Persona(Icons.Default.Diversity3, R.string.onboarding_persona_network, R.string.onboarding_persona_network_b1, R.string.onboarding_persona_network_b2),
)

@Composable
fun OnboardingPersonaScreen(onNext: () -> Unit, onSkip: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(ObBg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.onboarding_brand).uppercase(),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = ObGold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.onboarding_skip),
                    fontSize = 14.sp, color = ObMuted,
                    modifier = Modifier.clip(SocialShape.Medium).clickable(onClick = onSkip).padding(8.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                stringResource(R.string.onboarding_persona_title),
                fontFamily = AureliaSerif, fontWeight = FontWeight.W800,
                fontSize = 28.sp, lineHeight = 34.sp, color = ObTx
            )

            Spacer(Modifier.height(20.dp))

            // horizontalScroll, не fillMaxWidth-Row: на длинных переводах
            // (EL/RU заметно длиннее английского черновика) чипы не должны
            // сжиматься и переносить текст на 2 строки — лучше прокрутка.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PERSONAS.forEachIndexed { i, p ->
                    val isSel = i == selected
                    Text(
                        stringResource(p.labelRes),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        color = if (isSel) ObBg else ObMuted,
                        modifier = Modifier
                            .clip(SocialShape.Medium)
                            .background(if (isSel) ObBrand else ObCard)
                            .clickable { selected = i }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // scroll, не Column(weight(1f)) фиксированной высоты — RU/EL текст
            // длиннее английского черновика (feedback_column_scroll_weight).
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val persona = PERSONAS[selected]
                PersonaBulletRow(persona.icon, stringResource(persona.bullet1Res))
                Spacer(Modifier.height(16.dp))
                PersonaBulletRow(persona.icon, stringResource(persona.bullet2Res))
            }

            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = SocialShape.R18,
                colors = ButtonDefaults.buttonColors(containerColor = ObBrand, contentColor = ObBg)
            ) {
                Text(stringResource(R.string.onboarding_next), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PersonaBulletRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(40.dp).clip(SocialShape.Medium).background(ObCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = ObGold)
        }
        Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = ObTx, modifier = Modifier.weight(1f))
    }
}
