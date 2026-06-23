package com.aistudio.socialsphere.crmlxb.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.aistudio.socialsphere.crmlxb.model.Messenger
import android.provider.ContactsContract

object ExternalActionHandler {

    /** Ищет Activity в цепочке обёрток контекста. Локализованный контекст
     *  (createConfigurationContext) не является Activity — startActivity из него
     *  без флага NEW_TASK бросает AndroidRuntimeException, которую try-catch
     *  глотал молча. Из-за этого «не работали» кнопки звонка/SMS/почты/карты. */
    private fun Context.findActivity(): Activity? {
        var ctx: Context = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** Запускает intent корректно из любого контекста. @return true при успехе. */
    fun startIntentSafely(context: Context, intent: Intent): Boolean {
        return try {
            val activity = context.findActivity()
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openDialer(context: Context, phoneNumber: String?) {
        if (phoneNumber.isNullOrBlank()) {
            Toast.makeText(context, "Телефон не указан", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
        }
        safelyStartIntent(context, intent)
    }

    fun openSms(context: Context, phoneNumber: String?) {
        if (phoneNumber.isNullOrBlank()) {
            Toast.makeText(context, "Телефон не указан", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$phoneNumber")
        }
        safelyStartIntent(context, intent)
    }

    fun openEmail(context: Context, email: String?) {
        if (email.isNullOrBlank()) {
            Toast.makeText(context, "Email не указан", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$email")
        }
        safelyStartIntent(context, intent)
    }

    fun openWebsite(context: Context, url: String?) {
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "Сайт не указан", Toast.LENGTH_SHORT).show()
            return
        }
        var formattedUrl = url.trim()
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        // Белый список схем: только http/https. Защищает от javascript:/intent:/
        // file:/content: из сохранённого поля сайта (в т.ч. из чужого бэкапа).
        val scheme = Uri.parse(formattedUrl).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(context, "Недопустимая ссылка", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(formattedUrl)
        }
        safelyStartIntent(context, intent)
    }

    fun openRoute(context: Context, address: String?) {
        if (address.isNullOrBlank()) {
            Toast.makeText(context, "Адрес не указан", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        }
        safelyStartIntent(context, intent)
    }

    fun openRouteByCoordinates(context: Context, latitude: Double, longitude: Double, label: String? = null) {
        val uriStr = if (label.isNullOrBlank()) {
            "geo:$latitude,$longitude?q=$latitude,$longitude"
        } else {
            "geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(uriStr)
        }
        safelyStartIntent(context, intent)
    }

    fun openMessenger(context: Context, messenger: Messenger?) {
        if (messenger == null || messenger.value.isBlank()) {
            Toast.makeText(context, "Мессенджер не настроен", Toast.LENGTH_SHORT).show()
            return
        }

        // Use explicit link if provided
        if (!messenger.link.isNullOrBlank()) {
            safelyStartIntent(context, Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(messenger.link)
            })
            return
        }

        val value = messenger.value.trimStart('@').trim()

        // Generate deep link by messenger type
        val uri: String? = when (messenger.type) {
            com.aistudio.socialsphere.crmlxb.model.MessengerType.TELEGRAM -> {
                // Try app deep link first, fallback to web
                "tg://resolve?domain=$value"
            }
            com.aistudio.socialsphere.crmlxb.model.MessengerType.WHATSAPP -> {
                // value can be phone number or username
                val phone = value.filter { it.isDigit() }
                if (phone.isNotEmpty())
                    "https://wa.me/$phone"
                else
                    "https://wa.me/$value"
            }
            com.aistudio.socialsphere.crmlxb.model.MessengerType.VIBER -> {
                val phone = value.filter { it.isDigit() }
                if (phone.isNotEmpty()) "viber://chat?number=%2B$phone"
                else null
            }
            com.aistudio.socialsphere.crmlxb.model.MessengerType.SIGNAL -> {
                // Signal doesn't have a reliable deep link by username; open app
                val intent = context.packageManager.getLaunchIntentForPackage("org.thoughtcrime.securesms")
                if (intent != null) {
                    safelyStartIntent(context, intent)
                    return
                }
                null
            }
            com.aistudio.socialsphere.crmlxb.model.MessengerType.MESSENGER -> {
                "https://m.me/$value"
            }
            com.aistudio.socialsphere.crmlxb.model.MessengerType.OTHER -> {
                // Try as URL if it looks like one
                if (value.startsWith("http") || value.startsWith("t.me") || value.startsWith("www."))
                    if (!value.startsWith("http")) "https://$value" else value
                else null
            }
        }

        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(uri) }
            // For Telegram: try app deep link, fall back to web
            if (messenger.type == com.aistudio.socialsphere.crmlxb.model.MessengerType.TELEGRAM) {
                if (startIntentSafely(context, intent)) return
                // Fallback to web
                safelyStartIntent(context, Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://t.me/$value")
                })
            } else {
                safelyStartIntent(context, intent)
            }
        } else {
            Toast.makeText(context, "Не удалось открыть ${messenger.type.labelKey()}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Способ A: запись контакта в телефонную книгу через системный экран.
     * ACTION_INSERT_OR_EDIT даёт пользователю выбрать «создать новый» или
     * «дополнить существующий», поэтому дубли не плодятся. Особых разрешений
     * (WRITE_CONTACTS) НЕ требуется — пользователь сам подтверждает сохранение.
     */
    fun saveToPhoneContacts(
        context: Context,
        fullName: String,
        phones: List<String>,
        emails: List<String>,
        company: String? = null,
        jobTitle: String? = null
    ) {
        fun Intent.fillContact(): Intent = apply {
            if (fullName.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, fullName)
            phones.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            phones.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
            phones.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE, it) }
            emails.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            emails.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it) }
            company?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            jobTitle?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
        }
        // INSERT_OR_EDIT — выбрать новый или дополнить существующий
        val editIntent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        }.fillContact()
        if (startIntentSafely(context, editIntent)) return
        // Фолбэк ACTION_INSERT — всегда открывает экран создания контакта
        val insertIntent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
        }.fillContact()
        if (!startIntentSafely(context, insertIntent)) {
            Toast.makeText(context, "Нет приложения Контакты", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safelyStartIntent(context: Context, intent: Intent) {
        if (!startIntentSafely(context, intent)) {
            Toast.makeText(context, "Нет приложения для выполнения действия", Toast.LENGTH_SHORT).show()
        }
    }
}
