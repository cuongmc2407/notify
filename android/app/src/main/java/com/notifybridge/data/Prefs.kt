package com.notifybridge.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/** Cai dat cua app, luu trong SharedPreferences. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Dia chi server, vi du https://notify.tenmien.com (khong co dau / o cuoi). */
    var serverUrl: String
        get() = sp.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER_URL, normalizeUrl(value)).apply()

    var deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = sp.edit().putString(KEY_DEVICE_ID, value).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName()
        set(value) = sp.edit().putString(KEY_DEVICE_NAME, value).apply()

    /** Cong tac tong: tat thi khong ghi nhan them thong bao nao. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Co chuyen tiep ca thong bao "dang chay" (nhac, tai file, dieu huong) hay khong. */
    var forwardOngoing: Boolean
        get() = sp.getBoolean(KEY_FORWARD_ONGOING, false)
        set(value) = sp.edit().putBoolean(KEY_FORWARD_ONGOING, value).apply()

    /** Cac package bi tat, khong chuyen tiep. */
    var blockedPackages: Set<String>
        get() = sp.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_BLOCKED, value).apply()

    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastError: String?
        get() = sp.getString(KEY_LAST_ERROR, null)
        set(value) = sp.edit().putString(KEY_LAST_ERROR, value).apply()

    /** Tong so thong bao da gui thanh cong (chi de hien thi). */
    var sentCount: Long
        get() = sp.getLong(KEY_SENT_COUNT, 0L)
        set(value) = sp.edit().putLong(KEY_SENT_COUNT, value).apply()

    val isPaired: Boolean
        get() = serverUrl.isNotEmpty() && token.isNotEmpty()

    fun setBlocked(pkg: String, blocked: Boolean) {
        val next = blockedPackages.toMutableSet()
        if (blocked) next.add(pkg) else next.remove(pkg)
        blockedPackages = next
    }

    fun clearPairing() {
        sp.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_LAST_SYNC)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val NAME = "notify_bridge"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FORWARD_ONGOING = "forward_ongoing"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_SENT_COUNT = "sent_count"

        fun defaultDeviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "Dien thoai Android" }

        /** Bo dau "/" thua, tu them https:// neu nguoi dung chi go ten mien. */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            return url
        }
    }
}
