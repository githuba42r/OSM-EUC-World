package com.a42r.eucosmandplugin.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.a42r.eucosmandplugin.service.EucWorldService

/**
 * Receiver to start the EUC World service on device boot.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check if auto-start is enabled
            val prefs = context.getSharedPreferences("euc_world_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start_on_boot", false)) {
                EucWorldService.start(context)
            }
        }
    }
}
