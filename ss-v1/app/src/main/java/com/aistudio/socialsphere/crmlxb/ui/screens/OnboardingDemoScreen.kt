package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.Crossfade
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSerif
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape

/**
 * Шаг 3 тура — «демо». НЕ живые HomeScreen/ContactsScreen/... (не тянем в тур
 * реальный ViewModel/БД пустого нового пользователя) и НЕ скриншоты (раунд 3
 * плана — не масштабируется на 3 языка, историю см. в SOCIALSPHERE_KNOWLEDGE.md
 * §First-Run Onboarding). Это упрощённые демо-макеты: каждый кусок текста —
 * через stringResource(), включая имена демо-контактов (onboarding_demo_name_*)
 * — при смене языка приложения меняется весь экран разом, гарантированно.
 * Переключение между 4 мини-сценариями — тап по вкладке, не автоплей (осознанно
 * упрощено против исходного плана с pulse+crossfade поверх PNG — то требовало
 * таймеров и легко ломается; здесь просто и надёжно).
 */
private enum class DemoTab { HOME, CONTACTS, DETAIL, CALENDAR }

// Светлая палитра демо — реальная Aurelia-палитра приложения (AureliaDesignSystem.kt),
// НЕ тёмная ObBg-палитра тура: с этого шага человек смотрит на макет настоящего
// светлого интерфейса, а не на редакционную обложку.
private val ApBg = Color(0xFFF1EDE6)
private val ApCard = Color(0xFFFCFBF8)
private val ApInk = Color(0xFF1B1A16)
private val ApInk2 = Color(0xFF6B6558)
private val ApAccent = Color(0xFF1C6B4C)
private val ApGold = Color(0xFFB68A36)
private val AvatarColors = listOf(Color(0xFFB68A36), Color(0xFF7A9E86), Color(0xFF9B6FB0))

@Composable
fun OnboardingDemoScreen(onNext: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = DemoTab.values()

    val name1 = stringResource(R.string.onboarding_demo_name_1)
    val name2 = stringResource(R.string.onboarding_demo_name_2)
    val name3 = stringResource(R.string.onboarding_demo_name_3)
    val names = listOf(name1, name2, name3)

    Box(Modifier.fillMaxSize().background(ObBg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { i, t ->
                    val isSel = i == tab
                    Text(
                        demoTabLabel(t),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        color = if (isSel) ObBg else ObMuted,
                        modifier = Modifier
                            .clip(SocialShape.Medium)
                            .background(if (isSel) ObBrand else ObCard)
                            .clickable { tab = i }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(SocialShape.R18)
                    .background(ApBg)
                    .padding(16.dp)
            ) {
                Crossfade(targetState = tabs[tab], label = "onboarding_demo") { t ->
                    when (t) {
                        DemoTab.HOME -> DemoHome(names)
                        DemoTab.CONTACTS -> DemoContacts(names)
                        DemoTab.DETAIL -> DemoDetail(name1)
                        DemoTab.CALENDAR -> DemoCalendar()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

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
private fun demoTabLabel(t: DemoTab): String = when (t) {
    DemoTab.HOME -> stringResource(R.string.home_today)
    DemoTab.CONTACTS -> stringResource(R.string.nav_contacts)
    DemoTab.DETAIL -> stringResource(R.string.cd_tab_overview)
    DemoTab.CALENDAR -> stringResource(R.string.nav_calendar)
}

@Composable
private fun DemoAvatar(name: String, colorIdx: Int, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val initials = name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString("").uppercase()
    Box(
        Modifier.size(size).clip(CircleShape).background(AvatarColors[colorIdx % AvatarColors.size]),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.6).sp)
    }
}

@Composable
private fun DemoHome(names: List<String>) {
    Column {
        Text(stringResource(R.string.home_today), fontFamily = AureliaSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp, color = ApInk)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { Box(Modifier.weight(1f).height(36.dp).clip(SocialShape.Medium).background(ApCard)) }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.home_recently_added), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ApInk)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            names.forEachIndexed { i, n ->
                Column(
                    Modifier.weight(1f).clip(SocialShape.Medium).background(ApCard).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DemoAvatar(n, i)
                    Spacer(Modifier.height(4.dp))
                    Text(n, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = ApInk, lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.home_need_attention), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ApInk)
    }
}

@Composable
private fun DemoContacts(names: List<String>) {
    Column {
        Text(stringResource(R.string.nav_contacts), fontFamily = AureliaSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = ApInk)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(SocialShape.Medium).background(ApCard).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.contacts_search_placeholder), fontSize = 11.sp, color = ApInk2)
        }
        Spacer(Modifier.height(10.dp))
        names.forEach { n ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(SocialShape.Medium).background(ApCard).padding(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DemoAvatar(n, names.indexOf(n), 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(n, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ApInk)
            }
        }
    }
}

@Composable
private fun DemoDetail(name: String) {
    Column {
        DemoAvatar(name, 0, 44.dp)
        Spacer(Modifier.height(8.dp))
        Text(name, fontFamily = AureliaSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ApInk)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DemoChip(stringResource(R.string.lbl_relationship_type_family), true)
            DemoChip(stringResource(R.string.lbl_contact_status_active), false)
            DemoChip(stringResource(R.string.lbl_social_role_regular), false)
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(SocialShape.Medium).background(ApCard).padding(10.dp)) {
            Text(stringResource(R.string.lbl_calendar_item_type_meeting).uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ApAccent, letterSpacing = 0.5.sp)
            Text(stringResource(R.string.onboarding_demo_event), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ApInk)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.cd_dossier).uppercase(),
            fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ApGold, letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun DemoChip(text: String, on: Boolean) {
    Text(
        text, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        color = if (on) Color.White else ApInk2,
        modifier = Modifier
            .clip(SocialShape.Medium)
            .background(if (on) ApAccent else ApCard)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun DemoCalendar() {
    Column {
        Text(stringResource(R.string.nav_calendar), fontFamily = AureliaSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = ApInk)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(SocialShape.Medium).background(ApCard).padding(10.dp)) {
            Text(
                stringResource(R.string.lbl_calendar_item_type_meeting).uppercase(),
                fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ApAccent, letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.onboarding_demo_event), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ApInk)
        }
    }
}
