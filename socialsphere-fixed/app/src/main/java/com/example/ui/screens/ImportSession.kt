package com.example.ui.screens

import androidx.compose.runtime.mutableStateListOf
import com.example.utils.ImportContactCandidate

object ImportSession {
    val candidates = mutableStateListOf<ImportContactCandidate>()
    
    fun clear() {
        candidates.clear()
    }
}
