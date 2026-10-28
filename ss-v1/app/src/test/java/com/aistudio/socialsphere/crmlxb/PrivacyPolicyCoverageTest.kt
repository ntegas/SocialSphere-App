package com.aistudio.socialsphere.crmlxb

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Аудит 2026-10-28: политика конфиденциальности отстала от приложения (в ней не было камеры, микрофона, биометрии),
 * а ссылки на неё в приложении не было. Системное правило, делающее это невозможным: каждое разрешение манифеста
 * обязано упоминаться в тексте политики — в английской и греческой версиях (основные языки продукта, §77 базы знаний).
 * Новое разрешение без записи в [coverage] и без упоминания в политике роняет тест (нужно осознанно обновить и
 * политику, и страницу на GitHub Pages).
 */
class PrivacyPolicyCoverageTest {

    /** разрешение → (слова для английской политики, слова для греческой); достаточно любого слова из набора. */
    private val coverage: Map<String, Pair<List<String>, List<String>>> = mapOf(
        "READ_CONTACTS" to (listOf("Contacts (read)") to listOf("Επαφές (ανάγνωση)")),
        "CAMERA" to (listOf("Camera") to listOf("Κάμερα")),
        "RECORD_AUDIO" to (listOf("Microphone") to listOf("Μικρόφωνο")),
        "ACCESS_FINE_LOCATION" to (listOf("Location") to listOf("Τοποθεσία")),
        "ACCESS_COARSE_LOCATION" to (listOf("Location") to listOf("Τοποθεσία")),
        "READ_CALENDAR" to (listOf("Calendar") to listOf("Ημερολόγιο")),
        "WRITE_CALENDAR" to (listOf("Calendar") to listOf("Ημερολόγιο")),
        "POST_NOTIFICATIONS" to (listOf("Notifications") to listOf("Ειδοποιήσεις")),
        "SCHEDULE_EXACT_ALARM" to (listOf("exact alarms") to listOf("ακριβή ξυπνητήρια")),
        "RECEIVE_BOOT_COMPLETED" to (listOf("after reboot") to listOf("μετά από επανεκκίνηση")),
        "WAKE_LOCK" to (listOf("reminders") to listOf("υπενθυμίσεις")),
        "USE_BIOMETRIC" to (listOf("Biometrics") to listOf("Βιομετρικά")),
        "INTERNET" to (listOf("Internet") to listOf("Διαδίκτυο")),
        "ACCESS_NETWORK_STATE" to (listOf("network state") to listOf("κατάσταση δικτύου")),
    )

    private fun find(vararg candidates: String): File? = candidates.map { File(it) }.firstOrNull { it.exists() }

    private val manifest = find("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
    private val en = find("../../PRIVACY_POLICY.md", "../PRIVACY_POLICY.md", "PRIVACY_POLICY.md")
    private val el = find("../../PRIVACY_POLICY_EL.md", "../PRIVACY_POLICY_EL.md", "PRIVACY_POLICY_EL.md")

    private fun manifestPermissions(): List<String> =
        Regex("""<uses-permission\s+android:name="android\.permission\.([A-Z_]+)"""")
            .findAll(manifest!!.readText()).map { it.groupValues[1] }.toList()

    @Test fun everyManifestPermissionIsMapped_soANewOneCannotSlipInUnnoticed() {
        assumeTrue("манифест не найден (тест запущен вне репозитория)", manifest != null)
        val unmapped = manifestPermissions().filter { it !in coverage }
        if (unmapped.isNotEmpty()) fail("Разрешения $unmapped не описаны в политике конфиденциальности: добавьте их в " +
            "PRIVACY_POLICY.md / PRIVACY_POLICY_EL.md и в таблицу coverage этого теста, затем обновите страницу GitHub Pages.")
    }

    @Test fun bothPolicyVersionsMentionEveryPermissionTheAppDeclares() {
        assumeTrue("политика не найдена (тест запущен вне репозитория)", manifest != null && en != null && el != null)
        val enText = en!!.readText()
        val elText = el!!.readText()
        manifestPermissions().forEach { p ->
            val (enWords, elWords) = coverage[p] ?: return@forEach
            assertTrue("английская политика не упоминает $p (ищу: $enWords)", enWords.any { enText.contains(it, ignoreCase = true) })
            assertTrue("греческая политика не упоминает $p (ищу: $elWords)", elWords.any { elText.contains(it, ignoreCase = true) })
        }
    }

    @Test fun policyHasUpToDateContactAndMatchesTheInAppEmail() {
        assumeTrue("политика не найдена", en != null && el != null)
        listOf(en!!, el!!).forEach {
            assertTrue("${it.name}: нет контактной почты ${AppLinks.SUPPORT_EMAIL}", it.readText().contains(AppLinks.SUPPORT_EMAIL))
        }
    }

    @Test fun policyIsNotRussian_productLanguagesAreEnglishAndGreek() {
        assumeTrue("политика не найдена", en != null && el != null)
        val cyr = Regex("[А-Яа-яЁё]")
        listOf(en!!, el!!).forEach {
            assertTrue("${it.name}: в политике не должно быть русского текста (§77)", !cyr.containsMatchIn(it.readText()))
        }
    }
}
