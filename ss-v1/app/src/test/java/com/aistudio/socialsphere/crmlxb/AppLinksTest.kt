package com.aistudio.socialsphere.crmlxb

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ссылки «Политика конфиденциальности» и «Написать разработчику» в Настройки → Приватность. */
@RunWith(RobolectricTestRunner::class)
class AppLinksTest {

    @Test fun policyLinkIsAnHttpsViewIntent() {
        assertTrue("Play требует публичный https-адрес", AppLinks.PRIVACY_POLICY_URL.startsWith("https://"))
        val i = AppLinks.privacyPolicyIntent()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(AppLinks.PRIVACY_POLICY_URL, i.dataString)
    }

    @Test fun contactIntentOnlyCarriesTheAddressAndTheVersion() {
        val i = AppLinks.contactDeveloperIntent("1.8.2")
        assertEquals(Intent.ACTION_SENDTO, i.action)
        assertEquals("mailto:${AppLinks.SUPPORT_EMAIL}", i.dataString)
        assertEquals("SocialSphere 1.8.2", i.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue("в письмо не подставляется ничего, кроме темы", i.extras!!.keySet() == setOf(Intent.EXTRA_SUBJECT))
    }
}
