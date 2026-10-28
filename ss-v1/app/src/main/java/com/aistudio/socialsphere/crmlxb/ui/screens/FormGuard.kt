package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * ЗАЩИТА НЕСОХРАНЁННОГО ВВОДА на полноэкранных формах (жалоба владельца 2026-10-08: «не сохранив, я
 * теряю данные; должно выскакивать сохранить или отменить»). Общий механизм для форм «Компания» и
 * «Событие» (у формы контакта — свой, с черновиком на диске, см. ContactEditScreen).
 *
 * [current] — снимок всех полей формы (data class / List с equals). «Изменена» — снимок отличается от
 * снимка первой композиции, ЛИБО экран восстановлен системой после выгрузки процесса из уже изменённого
 * состояния (поля на rememberSaveable вернулись, а «исходный» снимок в памяти — уже восстановленный).
 */
@Composable
fun rememberFormDirty(current: Any?): Boolean {
    val baseline = remember { current }
    var savedDirty by rememberSaveable { mutableStateOf(false) }
    val dirtyAtRestore = remember { savedDirty }
    val dirty = current != baseline || dirtyAtRestore
    SideEffect { savedDirty = dirty }
    return dirty
}

/**
 * «Отмена»/«Назад» на изменённой форме не уходят молча, а спрашивают «Сохранить / Не сохранять /
 * Продолжить ввод». Возвращает действие для кнопки «Отмена». Пока идёт сохранение, уход игнорируется
 * (экран сам уйдёт по подтверждённой записи — иначе второй переход назад поверх первого).
 */
@Composable
fun rememberLeaveGuard(
    dirty: Boolean,
    saving: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onLeave: () -> Unit,
): () -> Unit {
    var ask by remember { mutableStateOf(false) }
    val save by rememberUpdatedState(onSave)
    val leave by rememberUpdatedState(onLeave)
    androidx.activity.compose.BackHandler(enabled = dirty || saving) { if (!saving) ask = true }
    if (ask) {
        com.aistudio.socialsphere.crmlxb.ui.theme.AureliaUnsavedChangesDialog(
            onSave = if (canSave && !saving) ({ ask = false; save() }) else null,
            onDiscard = { ask = false; leave() },
            onKeepEditing = { ask = false },
        )
    }
    return {
        if (!saving) {
            if (dirty) ask = true else leave()
        }
    }
}
