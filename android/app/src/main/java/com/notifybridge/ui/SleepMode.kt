package com.notifybridge.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * "Che do ngu": bien may thanh mot tram trung chuyen khong the cham nham.
 *
 * Khi bat:
 *  - do sang ep xuong muc thap nhat may cho phep
 *  - man hinh khong bao gio tu tat
 *  - an thanh trang thai va thanh dieu huong
 *  - ghim man hinh (lock task) de khong thoat ra app khac duoc
 *  - mot lop phu den chan toan bo cham (xem SleepOverlay)
 *
 * Chi mo khoa duoc bang cach giu tay 3 giay tren man hinh.
 */

private const val TAG = "NotifyBridge"

/** 0f = muc toi nhat ma may cho phep (van con sang, khong phai tat han). */
private const val MIN_BRIGHTNESS = 0f

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Activity.isInLockTask(): Boolean {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
}

fun Activity.applySleepMode(enabled: Boolean) {
    /* ----- do sang + giu man hinh sang ----- */
    val params = window.attributes
    params.screenBrightness =
        if (enabled) MIN_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = params

    if (enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /* ----- an / hien thanh he thong ----- */
    val insets = WindowCompat.getInsetsController(window, window.decorView)
    if (enabled) {
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        insets.show(WindowInsetsCompat.Type.systemBars())
    }

    /* ----- ghim man hinh ----- */
    // Khong phai may nao cung cho ghim (bi chinh sach cua to chuc chan, hoac
    // nguoi dung tu choi hop thoai xac nhan). Ghim that bai thi lop phu den van
    // chan cham nham - chi la co the thoat ra app khac duoc.
    try {
        if (enabled) {
            if (!isInLockTask()) startLockTask()
        } else {
            if (isInLockTask()) stopLockTask()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Khong ghim/bo ghim duoc man hinh", e)
    }
}

/**
 * Gan che do ngu vao vong doi cua man hinh. Dat o goc cay giao dien de doi tab
 * khong lam mat hieu luc.
 */
@Composable
fun SleepModeEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context.findActivity()
        activity?.applySleepMode(enabled)
        onDispose {
            // Roi khoi giao dien thi luon go khoa: khong bao gio de nguoi dung
            // ket lai voi mot man hinh den khong bam duoc gi.
            activity?.applySleepMode(false)
        }
    }
}
