package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif

// Тёмная палитра онбординга (макет Aurelia, тёмный экран приветствия).
private val ObBg    = Color(0xFF0E0D0A) // тёплый уголь-фон
private val ObTx    = Color(0xFFF3EFE8) // основной текст
private val ObMuted = Color(0x99F3EFE8) // .60 подписи
private val ObBrand = Color(0xFF5FB894) // осветлённый малахит
private val ObGold  = Color(0xFFD7B468)
private val ObCard  = Color(0x0DF3EFE8) // .05 подложка иконок

/**
 * Экран приветствия (первый запуск). Тёмный, редакционный: монограмма-бренд,
 * крупный Playfair-заголовок «Помните каждого, кто важен», три ценностных
 * пункта и кнопка «Начать». По нажатию — колбэк, который ставит флаг
 * onboardingCompleted и уводит в приложение.
 */
@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(ObBg).statusBarsPadding().navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // ── Бренд ──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(ObBrand, Color(0xFF1C6B4C)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Diversity3, null, Modifier.size(22.dp), tint = Color.White)
                }
                Text(
                    stringResource(R.string.onboarding_brand).uppercase(),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = ObGold
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Заголовок + подзаголовок ──
            Text(
                stringResource(R.string.onboarding_headline),
                fontFamily = AureliaSerif, fontWeight = FontWeight.W800,
                fontSize = 40.sp, lineHeight = 46.sp, color = ObTx
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.onboarding_subtitle),
                fontSize = 16.sp, lineHeight = 24.sp, color = ObMuted
            )

            Spacer(Modifier.height(32.dp))

            // ── Ценностные пункты ──
            FeatureRow(Icons.Default.Person, stringResource(R.string.onboarding_feature_contacts))
            Spacer(Modifier.height(16.dp))
            FeatureRow(Icons.Default.Notifications, stringResource(R.string.onboarding_feature_calendar))
            Spacer(Modifier.height(16.dp))
            FeatureRow(Icons.Default.AutoAwesome, stringResource(R.string.onboarding_feature_cheat))

            Spacer(Modifier.weight(1f))

            // ── Кнопка «Начать» ──
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ObBrand, contentColor = ObBg)
            ) {
                Text(stringResource(R.string.onboarding_start), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(ObCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = ObGold)
        }
        Text(text, fontSize = 15.sp, lineHeight = 20.sp, color = ObTx, modifier = Modifier.weight(1f))
    }
}
