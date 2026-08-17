package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.aistudio.socialsphere.crmlxb.ui.theme.SocialShape
import com.aistudio.socialsphere.crmlxb.utils.BillingManager
import com.aistudio.socialsphere.crmlxb.utils.ExternalActionHandler

/** Socialsphere Pro — статус подписки + покупка/управление (2026-08). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionSettingsScreen(onNavigateBack: () -> Unit) {
    val ctx = LocalContext.current
    val isPremium by AppSettings.subscriptionActive

    Scaffold(containerColor = AppleTheme.colors.groupedBackground, topBar = {}) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaBackButton(stringResource(R.string.common_back)) { onNavigateBack() }
                com.aistudio.socialsphere.crmlxb.ui.theme.AureliaScreenTitle(text = stringResource(R.string.sub_screen_title), fontSize = 28.sp)
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // ── Статус ──
                Row(
                    modifier = Modifier.fillMaxWidth().clip(SocialShape.R14)
                        .background(if (isPremium) AppleTheme.colors.brand.copy(alpha = 0.10f) else AppleTheme.colors.card)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.WorkspacePremium, null, tint = AppleTheme.colors.brand)
                    Text(
                        stringResource(if (isPremium) R.string.sub_status_active else R.string.sub_status_free),
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppleTheme.colors.label
                    )
                }

                // ── Использование лимитов (видно и на Pro — там просто «безлимит») ──
                Column(
                    modifier = Modifier.fillMaxWidth().clip(SocialShape.R14).background(AppleTheme.colors.card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val scansLeft = AppSettings.scansRemainingThisMonth()
                    val contactsLeft = AppSettings.contactsWithPhoneRemainingThisMonth()
                    UsageRow(
                        if (isPremium) stringResource(R.string.sub_unlimited)
                        else stringResource(R.string.sub_scans_remaining, scansLeft, 4)
                    )
                    UsageRow(
                        if (isPremium) stringResource(R.string.sub_unlimited)
                        else stringResource(R.string.sub_contacts_remaining, contactsLeft, 3)
                    )
                    UsageRow(
                        if (isPremium) stringResource(R.string.sub_unlimited)
                        else stringResource(R.string.sub_map_limit, AppSettings.FREE_MAP_MARKER_LIMIT)
                    )
                }

                if (isPremium) {
                    OutlinedButton(
                        onClick = {
                            ExternalActionHandler.openWebsite(
                                ctx,
                                "https://play.google.com/store/account/subscriptions?sku=${BillingManager.SUBSCRIPTION_PRODUCT_ID}&package=${ctx.packageName}"
                            )
                        },
                        shape = SocialShape.R14,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sub_manage_subscription))
                    }
                } else {
                    listOf(
                        R.string.paywall_bullet_unlimited_scans,
                        R.string.paywall_bullet_unlimited_map,
                        R.string.paywall_bullet_unlimited_contacts,
                        R.string.paywall_bullet_bulk_merge,
                    ).forEach { bulletRes ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = AppleTheme.colors.brand, modifier = Modifier.size(16.dp))
                            Text(stringResource(bulletRes), fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel)
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
                            Text(stringResource(R.string.paywall_annual_price) + " · " + stringResource(R.string.paywall_annual_badge), fontSize = 11.sp)
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
                }
            }
        }
    }
}

@Composable
private fun UsageRow(text: String) {
    Text(text, fontSize = 13.sp, color = AppleTheme.colors.secondaryLabel)
}
