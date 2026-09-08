package com.notifybridge.net

import android.content.Context
import android.util.Log
import com.notifybridge.data.Battery
import com.notifybridge.data.Outbox
import com.notifybridge.data.Prefs
import com.notifybridge.work.UploadWorker
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Rut hang doi va day len server.
 *
 * Duong di nhanh: [kick] duoc goi ngay khi co thong bao moi -> gui trong ~0.4 giay.
 * Duong di du phong: neu that bai (mat mang, server tat) thi len lich lai bang
 * WorkManager, cong them mot lan quet dinh ky 15 phut.
 */
object Uploader {

    private const val TAG = "NotifyBridge"
    private const val BATCH_SIZE = 50
    private const val DEBOUNCE_MS = 400L

    private val executor = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
    }
    private val heartbeatExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var pending: ScheduledFuture<*>? = null

    /**
     * Yeu cau gui hang doi. Nhieu lan goi lien tiep duoc gop lai thanh mot lan gui,
     * vi Android hay ban ra nhieu thong bao cung luc.
     */
    fun kick(context: Context) {
        val app = context.applicationContext
        pending?.cancel(false)
        pending = executor.schedule({ drainQuietly(app) }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun drainQuietly(context: Context) {
        runCatching { drain(context) }.onFailure { Log.w(TAG, "drain that bai", it) }
    }

    /**
     * Gui het hang doi. Tra ve true neu da gui xong (hoac khong co gi de gui),
     * false neu con lai vi loi -> nguoi goi nen thu lai sau.
     */
    @Synchronized
    fun drain(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.isPaired) return true

        val outbox = Outbox.get(app)
        var sentTotal = 0

        while (true) {
            val rows = outbox.peek(BATCH_SIZE)
            if (rows.isEmpty()) break

            val events = JSONArray()
            for (row in rows) {
                runCatching { events.put(JSONObject(row.json)) }
            }

            try {
                val bat = Battery.read(app)
                val accepted = Api.upload(prefs.serverUrl, prefs.token, events, bat.percent, bat.charging)
                outbox.delete(rows.map { it.id })
                sentTotal += rows.size
                prefs.lastSyncAt = System.currentTimeMillis()
                prefs.lastError = null
                Log.d(TAG, "Da gui ${rows.size} thong bao, server ghi nhan $accepted")
            } catch (e: Api.ApiException) {
                // 401/403: token khong con dung -> giu lai hang doi, bao loi cho nguoi dung.
                prefs.lastError = e.message
                Log.w(TAG, "Server tu choi: ${e.code} ${e.message}")
                scheduleRetry(app)
                return false
            } catch (e: Exception) {
                prefs.lastError = e.message ?: "Khong ket noi duoc server"
                Log.w(TAG, "Loi mang khi gui", e)
                scheduleRetry(app)
                return false
            }
        }

        if (sentTotal > 0) prefs.sentCount += sentTotal
        return true
    }

    /** Bao cho server biet may van song (chay nen, khong chan luong goi). */
    fun sendHeartbeat(context: Context) {
        val app = context.applicationContext
        heartbeatExecutor.execute {
            val prefs = Prefs(app)
            if (!prefs.isPaired) return@execute
            val bat = Battery.read(app)
            runCatching { Api.heartbeat(prefs.serverUrl, prefs.token, bat.percent, bat.charging) }
                .onSuccess {
                    prefs.lastSyncAt = System.currentTimeMillis()
                    prefs.lastError = null
                }
                .onFailure { prefs.lastError = it.message }
        }
    }

    private fun scheduleRetry(context: Context) {
        runCatching { UploadWorker.enqueueRetry(context) }
            .onFailure { Log.w(TAG, "Khong len lich lai duoc", it) }
    }
}
