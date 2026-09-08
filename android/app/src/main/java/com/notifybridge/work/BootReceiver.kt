package com.notifybridge.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifybridge.net.Uploader

/** Sau khi khoi dong may hoac cap nhat app: dang ky lai lich va gui not hang doi. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                UploadWorker.schedulePeriodic(context)
                Uploader.kick(context)
            }
        }
    }
}
