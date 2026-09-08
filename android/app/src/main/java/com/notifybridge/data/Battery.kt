package com.notifybridge.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext

/** Muc pin hien tai. [percent] = -1 nghia la chua doc duoc. */
data class BatteryState(val percent: Int = -1, val charging: Boolean = false)

object Battery {

    /** Doc mot lan, khong can dang ky receiver. Dung khi gui len server. */
    fun read(context: Context): BatteryState {
        val app = context.applicationContext

        // ACTION_BATTERY_CHANGED la sticky broadcast: dang ky voi receiver null
        // se lay duoc gia tri gan nhat ngay lap tuc.
        val status = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val charging = status?.let {
            it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        } ?: false

        // Uu tien gia tri cua broadcast vi day dung la con so thanh trang thai
        // dang hien - nguoi dung so sanh voi cai do.
        val fromBroadcast = status?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        } ?: -1

        // Du phong cho may khong bao qua broadcast: hoi thang tang phan cung.
        val fromHardware = runCatching {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }.getOrDefault(-1)

        return BatteryState(
            percent = if (fromBroadcast in 0..100) fromBroadcast else fromHardware,
            charging = charging,
        )
    }
}

/**
 * Theo doi pin theo thoi gian thuc. Dung cho man hinh che do ngu - man hinh do
 * bat lien tuc nen phai nhin thay pin tut den dau.
 */
@Composable
fun rememberBatteryState(): State<BatteryState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Battery.read(context)) }

    DisposableEffect(Unit) {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                state.value = BatteryState(
                    percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                    charging = plugged != 0,
                )
            }
        }

        runCatching {
            app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        onDispose { runCatching { app.unregisterReceiver(receiver) } }
    }

    return state
}
