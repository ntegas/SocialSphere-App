package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aistudio.socialsphere.crmlxb.data.RestorePreview

/**
 * RESTORE PREVIEW & SELECTIVE MERGE PACK (2026-09-22): состояние экрана предпросмотра восстановления,
 * переживает навигацию — тот же паттерн, что [ImportSession] для обычного импорта контактов.
 *
 * [selectedKeys] хранится НЕЗАВИСИМО от текущего фильтра/поиска экрана (requirement 25/T15) — смена
 * фильтра или поискового запроса никогда не трогает этот список, только что видно на экране.
 */
object RestorePreviewSession {
    var preview by mutableStateOf<RestorePreview?>(null)
    val selectedKeys = mutableStateListOf<String>()

    fun clear() {
        preview = null
        selectedKeys.clear()
    }

    fun toggle(key: String) {
        if (selectedKeys.contains(key)) selectedKeys.remove(key) else selectedKeys.add(key)
    }

    fun setSelected(key: String, selected: Boolean) {
        if (selected) { if (!selectedKeys.contains(key)) selectedKeys.add(key) }
        else selectedKeys.remove(key)
    }

    fun isSelected(key: String): Boolean = selectedKeys.contains(key)
}
