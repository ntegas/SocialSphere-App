package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate

/**
 * V2.2 CONTACT CREATE/EDIT: держит разобранный черновик (Скан или «Контакты» —
 * один выбранный контакт телефонной книги) между экраном-источником и
 * ContactEditScreen, который его прочитает как prefill. Тот же паттерн, что
 * RestorePreviewSession/ImportSession — Compose Navigation не проносит сложные
 * объекты через route-аргументы.
 */
object ContactDraftSession {
    var pending by mutableStateOf<ImportContactCandidate?>(null)

    fun clear() {
        pending = null
    }
}
