package com.notifybridge.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.notifybridge.net.Uploader
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bao muc pin len server ngay khi no thay doi.
 *
 * Truoc day pin chi di kem heartbeat cua WorkManager, ma WorkManager som nhat
 * cung 15 phut moi chay mot lan va con bi Doze gian ra them - nen tren dashboard
 * pin gan nhu chi nhuc nhich moi khi may co thong bao moi.
 *
 * Android ban ACTION_BATTERY_CHANGED moi lan pin doi. Bat lay su kien do va chi
 * gui khi con so phan tram (hoac trang thai sac) that su khac lan truoc, nen mot
 * chu ky xa het pin chi ton khoang 100 request.
 */
object BatteryWatcher {

    private const val TAG = "NotifyBridge"

    /** Khoang cach toi thieu giua hai lan gui, phong khi cam/rut sac lien tuc. */
    private const val MIN_INTERVAL_MS = 30_000L

    private val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }

    private var receiver: BroadcastReceiver? = null

    /** Gia tri da bao len server lan gan nhat. */
    private var sentPercent = -1
    private var sentCharging: Boolean? = null
    private var lastSentAt = 0L

    /** Gia tri moi nhat doc duoc, co the chua kip bao. */
    private var latestPercent = -1
    private var latestCharging = false
    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return

                val percent = level * 100 / scale
                val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                onReading(app, percent, charging)
            }
        }

        runCatching { app.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
            .onSuccess {
                receiver = r
                Log.d(TAG, "Bat dau theo doi pin")
            }
            .onFailure { Log.w(TAG, "Khong dang ky duoc receiver pin", it) }
    }

    @Synchronized
    fun stop(context: Context) {
        val r = receiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(r) }
        receiver = null
    }

    @Synchronized
    private fun onReading(app: Context, percent: Int, charging: Boolean) {
        latestPercent = percent
        latestCharging = charging

        if (percent == sentPercent && charging == sentCharging) return

        val waitMs = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastSentAt)
        if (waitMs <= 0) {
            report(app)
            return
        }

        // Dang trong khoang cho: hoan lai chu KHONG bo. Bo thi con so se dung im
        // tren dashboard cho toi tan lan pin doi ke tiep.
        if (pending == null) {
            pending = scheduler.schedule({ report(app) }, waitMs, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    private fun report(app: Context) {
        pending = null
        if (latestPercent == sentPercent && latestCharging == sentCharging) return

        sentPercent = latestPercent
        sentCharging = latestCharging
        lastSentAt = System.currentTimeMillis()

        Log.d(TAG, "Pin doi -> $sentPercent%${if (latestCharging) " (dang sac)" else ""}, bao len server")
        Uploader.sendHeartbeat(app)
    }
}
