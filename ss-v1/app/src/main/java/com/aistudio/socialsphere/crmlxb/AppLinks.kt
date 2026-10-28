package com.aistudio.socialsphere.crmlxb

import android.content.Intent
import android.net.Uri

/**
 * Внешние адреса приложения — одно место. Политика конфиденциальности лежит на GitHub Pages (ветка gh-pages
 * репозитория ntegas/SocialSphere-App; исходник текста — PRIVACY_POLICY.md / PRIVACY_POLICY_EN.md в корне репозитория).
 * Этот же адрес вписан в Play Console → «Содержание приложения» → «Политика конфиденциальности».
 */
object AppLinks {
    const val PRIVACY_POLICY_URL = "https://ntegas.github.io/SocialSphere-App/"
    const val SUPPORT_EMAIL = "ntega@hotmail.com"

    fun privacyPolicyIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))

    /** Письмо разработчику: только адрес и тема с версией — никаких данных пользователя в письмо не подставляется. */
    fun contactDeveloperIntent(versionName: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
            .putExtra(Intent.EXTRA_SUBJECT, "SocialSphere $versionName")
}
