package com.notifybridge

import android.app.Application
import com.notifybridge.data.Prefs
import com.notifybridge.net.Uploader
import com.notifybridge.work.UploadWorker

class NotifyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        UploadWorker.schedulePeriodic(this)
        if (Prefs(this).isPaired) Uploader.kick(this)
    }
}
