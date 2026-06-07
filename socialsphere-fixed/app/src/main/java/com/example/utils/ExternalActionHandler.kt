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
        
        val intent = if (!messenger.link.isNullOrBlank()) {
             Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(messenger.link)
            }
        } else {
             // Fallback to value if link is empty but it might be a link
             var valueUrl = messenger.value
             if (valueUrl.startsWith("http") || valueUrl.startsWith("www.") || valueUrl.startsWith("t.me/")) {
                 if (!valueUrl.startsWith("http")) valueUrl = "https://$valueUrl"
                 Intent(Intent.ACTION_VIEW).apply {
                     data = Uri.parse(valueUrl)
                 }
             } else {
                 Toast.makeText(context, "Недостаточно данных для открытия", Toast.LENGTH_SHORT).show()
                 return
             }
        }
        
        safelyStartIntent(context, intent)
    }

    private fun safelyStartIntent(context: Context, intent: Intent) {
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Нет приложения для выполнения действия", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Нет приложения для выполнения действия", Toast.LENGTH_SHORT).show()
        }
    }
}
