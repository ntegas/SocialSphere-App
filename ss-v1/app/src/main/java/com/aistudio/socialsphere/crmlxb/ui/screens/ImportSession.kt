package com.aistudio.socialsphere.crmlxb.ui.screens

import androidx.compose.runtime.mutableStateListOf
import com.aistudio.socialsphere.crmlxb.utils.ImportContactCandidate

object ImportSession {
    val candidates = mutableStateListOf<ImportContactCandidate>()
    
    fun clear() {
        candidates.clear()
    }
}
