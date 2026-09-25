package com.notifybridge

import android.app.Application
import com.notifybridge.data.Prefs
import com.notifybridge.net.Uploader
import com.notifybridge.service.NotifyListenerService
import com.notifybridge.work.UploadWorker

class NotifyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        UploadWorker.schedulePeriodic(this)
        if (Prefs(this).isPaired) Uploader.kick(this)
        // Tien trinh vua duoc danh thuc (mo app, khoi dong may, WorkManager...):
        // nhan dip kiem tra listener co dang chay khong.
        NotifyListenerService.ensureConnected(this)
    }
}
