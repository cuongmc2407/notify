package com.notifybridge.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * "Che do ngu": man hinh khong bao gio tu tat, va do sang ha xuong muc thap nhat.
 *
 * Dung khi ban de mot may cu cam sac lam tram trung chuyen thong bao — man hinh
 * sang lien tuc nen he thong khong bao gio ngu, thong bao ve gan nhu tuc thi,
 * ma van gan nhu khong nhin thay anh sang trong phong toi.
 *
 * Chi co tac dung khi app dang mo. Thoat app la he thong tra lai do sang cu.
 */

/** Do sang toi thieu. Dung 0.01 thay vi 0 de tren mot so may man hinh khong den han. */
private const val MIN_BRIGHTNESS = 0.01f

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun Activity.applySleepMode(enabled: Boolean) {
    val params = window.attributes
    params.screenBrightness =
        if (enabled) MIN_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = params

    if (enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            // Roi khoi giao dien thi luon tra man hinh ve binh thuong,
            // tranh de nguoi dung ket voi mot man hinh toi om khong tat duoc.
            activity?.applySleepMode(false)
        }
    }
}
