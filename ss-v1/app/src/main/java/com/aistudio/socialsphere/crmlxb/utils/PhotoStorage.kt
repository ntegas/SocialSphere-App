package com.aistudio.socialsphere.crmlxb.utils

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Фото контактов. URI из системного фото-пикера временный (доступ теряется
 * после процесса), поэтому картинка сразу копируется в filesDir/photos —
 * в Contact.photoUri хранится АБСОЛЮТНЫЙ ПУТЬ к нашей копии.
 */
object PhotoStorage {

    private fun dir(context: Context): File =
        File(context.filesDir, "photos").apply { mkdirs() }

    /** Копирует выбранное фото внутрь приложения; вернёт путь или null при ошибке. */
    fun saveContactPhoto(context: Context, source: Uri, contactId: String): String? = try {
        // Имя с меткой времени: старый файл не перезаписываем на месте, чтобы
        // Coil не показал закешированную картинку по совпавшему пути.
        val file = File(dir(context), "${contactId}_${System.currentTimeMillis()}.jpg")
        val ok = context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        if (ok && file.length() > 0) {
            deleteContactPhotos(context, contactId, except = file.name)
            file.absolutePath
        } else {
            file.delete(); null
        }
    } catch (e: Exception) {
        null
    }

    /** Удаляет фото контакта (все версии, кроме опционально указанной). */
    fun deleteContactPhotos(context: Context, contactId: String, except: String? = null) {
        dir(context).listFiles()?.forEach { f ->
            if (f.name.startsWith("${contactId}_") && f.name != except) f.delete()
        }
    }
}
