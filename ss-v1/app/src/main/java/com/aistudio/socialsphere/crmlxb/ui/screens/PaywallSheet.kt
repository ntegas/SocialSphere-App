package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.socialsphere.crmlxb.R
import com.aistudio.socialsphere.crmlxb.ui.theme.AppleTheme
import com.aistudio.socialsphere.crmlxb.ui.theme.AureliaSheet
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.ui.theme.aureliaPress
import com.aistudio.socialsphere.crmlxb.ui.theme.aureliaSerifFor
import com.aistudio.socialsphere.crmlxb.utils.BillingManager

/**
 * Socialsphere Pro — только подписка (владелец, 2026-08-16). Три места её
 * показывают с разным заголовком/подзаголовком под контекст (сканер/карта/
 * контакты), но одинаковым списком выгод и одинаковыми двумя CTA
 * (месяц/год) — единый источник правды для цены и офферов, не три копии.
 * Два CTA значит строится напрямую на AureliaSheet, а не на AureliaFormSheet
 * (та рассчитана ровно на один) — тот же приём, что PinSetupSheet/
 * PinVerifySheet в AppLockComponents.kt.
 */
@Composable
fun PaywallSheet(
    onDismiss: () -> Unit,
    titleRes: Int = R.string.paywall_title,
    subtitleRes: Int = R.string.paywall_subtitle,
) {
    val ctx = LocalContext.current
    AureliaSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = AppleTheme.colors.brand)
                Text(
                    stringResource(titleRes),
                    fontFamily = aureliaSerifFor(stringResource(titleRes)),
                    fontWeight = FontWeight.W700, fontSize = 20.sp, color = AppleTheme.colors.label
                )
            }
            Text(stringResource(subtitleRes), fontSize = 14.sp, color = AppleTheme.colors.secondaryLabel)

            Column(
                modifier = Modifier.fillMaxWidth().clip(SocialShape.R14)
                    .background(AppleTheme.colors.brand.copy(alpha = 0.08f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    R.string.paywall_bullet_unlimited_scans,
                    R.string.paywall_bullet_unlimited_map,
                    R.string.paywall_bullet_unlimited_contacts,
                    R.string.paywall_bullet_bulk_merge,
                ).forEach { bulletRes ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Default.CheckCircle, contentDescription = null,
                            tint = AppleTheme.colors.brand, modifier = Modifier.size(16.dp)
                        )
                        Text(stringResource(bulletRes), fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel)
                    }
                }
            }

            Button(
                onClick = {
                    (ctx as? android.app.Activity)?.let {
                        BillingManager.launchSubscriptionPurchase(it, BillingManager.BASE_PLAN_ANNUAL)
                    }
                },
                shape = SocialShape.R14,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.paywall_annual_cta), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.paywall_annual_price) + " · " + stringResource(R.string.paywall_annual_badge),
                        fontSize = 11.sp
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    (ctx as? android.app.Activity)?.let {
                        BillingManager.launchSubscriptionPurchase(it, BillingManager.BASE_PLAN_MONTHLY)
                    }
                },
                shape = SocialShape.R14,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) { Text(stringResource(R.string.paywall_monthly_cta) + " · " + stringResource(R.string.paywall_monthly_price)) }

            Text(
                stringResource(R.string.paywall_dismiss),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = AppleTheme.colors.secondaryLabel,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .aureliaPress(onClick = onDismiss)
                    .padding(6.dp),
            )
        }
    }
}
