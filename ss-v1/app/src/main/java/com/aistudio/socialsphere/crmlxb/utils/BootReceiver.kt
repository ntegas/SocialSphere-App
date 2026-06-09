package com.aistudio.socialsphere.crmlxb.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistudio.socialsphere.crmlxb.data.local.SocialsphereDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val db = SocialsphereDatabase.getDatabase(context)
            NotificationScheduler.rescheduleAll(context, db)
        }
    }
}
