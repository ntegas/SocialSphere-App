package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.model.Messenger

object ExternalActionHandler {

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
        var formattedUrl = url
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
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
            com.example.model.MessengerType.TELEGRAM -> {
                // Try app deep link first, fallback to web
                "tg://resolve?domain=$value"
            }
            com.example.model.MessengerType.WHATSAPP -> {
                // value can be phone number or username
                val phone = value.filter { it.isDigit() }
                if (phone.isNotEmpty())
                    "https://wa.me/$phone"
                else
                    "https://wa.me/$value"
            }
            com.example.model.MessengerType.VIBER -> {
                val phone = value.filter { it.isDigit() }
                if (phone.isNotEmpty()) "viber://chat?number=%2B$phone"
                else null
            }
            com.example.model.MessengerType.SIGNAL -> {
                // Signal doesn't have a reliable deep link by username; open app
                val intent = context.packageManager.getLaunchIntentForPackage("org.thoughtcrime.securesms")
                if (intent != null) {
                    safelyStartIntent(context, intent)
                    return
                }
                null
            }
            com.example.model.MessengerType.MESSENGER -> {
                "https://m.me/$value"
            }
            com.example.model.MessengerType.OTHER -> {
                // Try as URL if it looks like one
                if (value.startsWith("http") || value.startsWith("t.me") || value.startsWith("www."))
                    if (!value.startsWith("http")) "https://$value" else value
                else null
            }
        }

        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(uri) }
            // For Telegram: try app deep link, fall back to web
            if (messenger.type == com.example.model.MessengerType.TELEGRAM) {
                try {
                    context.startActivity(intent)
                    return
                } catch (_: Exception) {}
                // Fallback to web
                safelyStartIntent(context, Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://t.me/$value")
                })
            } else {
                safelyStartIntent(context, intent)
            }
        } else {
            Toast.makeText(context, "Не удалось открыть ${messenger.type.label()}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safelyStartIntent(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Нет приложения для выполнения действия", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось выполнить действие", Toast.LENGTH_SHORT).show()
        }
    }
}
