package com.notifybridge.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Dinh danh on dinh cho may nay, de server nhan ra van la may cu sau khi
 * go app cai lai (luc do token cu da mat cung du lieu app).
 *
 * Dung ANDROID_ID: tu Android 8 tro len gia tri nay rieng theo bo
 * (khoa ky app, tai khoan nguoi dung, may) va **giu nguyen qua cac lan go/cai lai**,
 * chi doi khi khoi phuc cai dat goc. Vi vay du app bi xoa sach du lieu thi lan
 * ghep doi sau server van gan dung vao ban ghi cu thay vi de ra mot may trung lap.
 *
 * Gia tri bi bam SHA-256 truoc khi gui di - server khong bao gio thay ANDROID_ID that.
 */
object DeviceId {

    private const val PREF_FALLBACK = "hardware_id_fallback"

    @SuppressLint("HardwareIds")
    fun hardwareId(context: Context): String {
        val app = context.applicationContext

        val androidId = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        // Mot so may (hoac may ao) tra ve null / chuoi rong / gia tri loi noi tieng
        // "9774d56d682e549c". Luc do sinh mot ma ngau nhien va giu lai trong app.
        val raw = if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") {
            val sp = app.getSharedPreferences("notify_bridge", Context.MODE_PRIVATE)
            sp.getString(PREF_FALLBACK, null) ?: UUID.randomUUID().toString().also {
                sp.edit().putString(PREF_FALLBACK, it).apply()
            }
        } else {
            androidId
        }

        return sha256("notify-bridge|$raw")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
