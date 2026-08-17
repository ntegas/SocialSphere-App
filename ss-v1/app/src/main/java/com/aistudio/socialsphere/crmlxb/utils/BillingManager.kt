package com.aistudio.socialsphere.crmlxb.utils

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.aistudio.socialsphere.crmlxb.ui.screens.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Socialsphere Pro — только подписка (владелец, 2026-08-16: отменил более
 * ранний вариант с разовой покупкой «Pro» отдельно от подписки «Pro+» —
 * теперь ОДИН товар с двумя base plan'ами: месяц/год). Тонкая обёртка над
 * Play Billing Library 8 (см. libs.versions.toml — v9 конфликтует с
 * зафиксированным Kotlin-пином проекта) — связь/покупка/восстановление, статус хранится в
 * AppSettings.subscriptionActive, как и остальные настройки приложения.
 * Нет своего сервера — восстановление статуса ПОСЛЕ переустановки идёт
 * ИСКЛЮЧИТЕЛЬНО через queryPurchasesAsync() у самого Google Play, другого
 * источника правды нет.
 */
object BillingManager {

    /** Play Console: один товар-подписка с двумя base plan'ами —
     *  monthly (9€/мес) и annual (эффективно 7€/мес, оплата раз в год). */
    const val SUBSCRIPTION_PRODUCT_ID = "socialsphere_pro"
    const val BASE_PLAN_MONTHLY = "monthly"
    const val BASE_PLAN_ANNUAL = "annual"

    private val scope = CoroutineScope(Dispatchers.IO)
    private var client: BillingClient? = null

    private val listener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    fun init(context: Context) {
        if (client != null) return
        client = BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    restorePurchases()
                } else {
                    // Не удалось подключиться (нет Play Store и т.п.) — не блокируем
                    // пользователя вечным «неопределённым» статусом, считаем бесплатным.
                    AppSettings.entitlementResolved.value = true
                }
            }
            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() сам переподключится — no-op.
            }
        })
    }

    /** Спрашивает у Google Play, что уже оформлено — ЕДИНСТВЕННЫЙ способ
     *  вернуть статус подписки после переустановки приложения (нет своего
     *  сервера). Звать на каждом холодном старте (MainActivity.onCreate). */
    fun restorePurchases() {
        val c = client ?: return
        scope.launch {
            try {
                val subs = c.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
                )
                subs.purchasesList.forEach { handlePurchase(it) }

                val hasActiveSub = subs.purchasesList.any {
                    it.products.contains(SUBSCRIPTION_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                AppSettings.subscriptionActive.value = hasActiveSub
                // Без своего сервера/Play Developer API у клиента нет надёжного способа
                // узнать ТОЧНУЮ дату следующего продления — не подделываем значение.
                if (!hasActiveSub) AppSettings.subscriptionExpiryEpoch.value = 0L
            } finally {
                AppSettings.entitlementResolved.value = true
            }
        }
    }

    /** Запрашивает карточку подписки у Play и сразу открывает диалог покупки.
     *  @param basePlanId BASE_PLAN_MONTHLY или BASE_PLAN_ANNUAL — какой offer выбрать. */
    fun launchSubscriptionPurchase(activity: Activity, basePlanId: String) {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUBSCRIPTION_PRODUCT_ID)
                        .setProductType(ProductType.SUBS)
                        .build()
                )
            )
            .build()
        scope.launch {
            val result = c.queryProductDetails(params)
            val details = result.productDetailsList?.firstOrNull() ?: return@launch
            val token = details.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId == basePlanId }
                ?.offerToken
                ?: details.subscriptionOfferDetails?.firstOrNull()?.offerToken
                ?: return@launch
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(token)
                .build()
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()
            // launchBillingFlow должен звать UI-поток — activity сама планирует показ.
            activity.runOnUiThread { c.launchBillingFlow(activity, flowParams) }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.products.contains(SUBSCRIPTION_PRODUCT_ID)) {
            AppSettings.subscriptionActive.value = true
        }

        // Неподтверждённая подписка автоматически возвращается пользователю
        // деньгами Google Play через 3 дня — acknowledgePurchase() обязателен.
        if (!purchase.isAcknowledged) {
            val c = client ?: return
            scope.launch {
                c.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                )
            }
        }
    }
}
