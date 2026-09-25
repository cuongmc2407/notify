package com.notifybridge.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifybridge.net.Uploader
import com.notifybridge.service.NotifyListenerService

/** Sau khi khoi dong may hoac cap nhat app: dang ky lai lich va gui not hang doi. */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        /** Xiaomi, HTC... ban action nay thay cho BOOT_COMPLETED khi khoi dong nhanh. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                UploadWorker.schedulePeriodic(context)
                Uploader.kick(context)
                NotifyListenerService.ensureConnected(context)
            }
        }
    }
}
