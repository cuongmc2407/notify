package com.notifybridge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.notifybridge.data.Outbox
import com.notifybridge.data.Prefs
import com.notifybridge.net.Uploader
import com.notifybridge.service.NotifyListenerService
import com.notifybridge.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusScreen(
    prefs: Prefs,
    snackbar: SnackbarHostState,
    sleepMode: Boolean,
    onSleepModeChange: (Boolean) -> Unit,
    onUnpaired: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var refresh by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var forwardOngoing by remember { mutableStateOf(prefs.forwardOngoing) }
    var pendingCount by remember { mutableLongStateOf(0L) }

    val listenerOn = remember(refresh) { NotifyListenerService.isEnabled(context) }
    var listenerConnected by remember { mutableStateOf(NotifyListenerService.connected) }
    val batteryFree = remember(refresh) { isIgnoringBatteryOptimizations(context) }
    val lastSyncAt = remember(refresh) { prefs.lastSyncAt }
    val lastError = remember(refresh) { prefs.lastError }
    val sentCount = remember(refresh) { prefs.sentCount }

    // Doc lai trang thai moi khi quay lai man hinh (nguoi dung vua cap quyen xong).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refresh) {
        pendingCount = withContext(Dispatchers.IO) { Outbox.get(context).count() }
    }

    // Listener ket noi/ngat bat dong bo (he thong bind lai sau vai giay),
    // nen hoi lai dinh ky thay vi chi luc quay lai man hinh.
    LaunchedEffect(Unit) {
        NotifyListenerService.ensureConnected(context)
        while (true) {
            listenerConnected = NotifyListenerService.connected
            delay(2_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /* ----- cong tac tong ----- */
        Card {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Chuyển tiếp thông báo", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (enabled) "Đang bật" else "Đang tắt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.enabled = it
                        },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Gửi cả thông báo đang chạy", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Nhạc đang phát, đang tải file, chỉ đường…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = forwardOngoing,
                        onCheckedChange = {
                            forwardOngoing = it
                            prefs.forwardOngoing = it
                        },
                    )
                }
            }
        }

        /* ----- che do ngu ----- */
        Card {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Chế độ ngủ", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Khoá máy lại làm trạm trung chuyển: màn hình tối nhất, " +
                                "chặn mọi chạm nhầm, không thoát ra app khác được.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sleepMode, onCheckedChange = onSleepModeChange)
                }

                Text(
                    "Mở khoá: giữ tay 3 giây lên màn hình. " +
                        "Nếu kẹt, giữ đồng thời nút Quay lại và Tổng quan để bỏ ghim màn hình.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "Nhớ cắm sạc — màn hình sáng liên tục rất tốn pin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        /* ----- quyen can cap ----- */
        if (listenerOn && !listenerConnected) {
            // Quyen van con nhung he thong khong bind listener (hay gap tren Xiaomi
            // sau khi khoi dong lai may): thong bao hien tren may ma khong ve web.
            PermissionCard(
                title = "Chưa nghe được thông báo",
                ok = false,
                okText = "",
                missingText = "Đã cấp quyền nhưng hệ thống chưa chạy dịch vụ đọc thông báo — " +
                    "thông báo sẽ không lên web. App đang tự kết nối lại; nếu vẫn thấy dòng này, " +
                    "tắt rồi bật lại quyền đọc thông báo.",
                actionLabel = "Mở cài đặt",
                onAction = { context.startActivity(notificationAccessIntent()) },
            )
        } else {
            PermissionCard(
                title = "Quyền đọc thông báo",
                ok = listenerOn,
                okText = "Đã cấp, đang nghe thông báo",
                missingText = "Bắt buộc — không có quyền này app không đọc được thông báo nào.",
                actionLabel = if (listenerOn) "Mở cài đặt" else "Cấp quyền",
                onAction = { context.startActivity(notificationAccessIntent()) },
            )
        }

        if (isXiaomi()) {
            HintCard(
                title = "Tự khởi động (Xiaomi)",
                text = "Bắt buộc trên Xiaomi/Redmi/POCO — thiếu quyền này, sau khi tắt nguồn " +
                    "bật lại máy sẽ không chạy dịch vụ đọc thông báo. Bật \"Tự khởi động\" cho " +
                    "app, và trong Tiết kiệm pin chọn \"Không giới hạn\".",
                actionLabel = "Mở Tự khởi động",
                onAction = { openXiaomiAutostart(context) },
            )
        }

        PermissionCard(
            title = "Bỏ tối ưu pin",
            ok = batteryFree,
            okText = "Đã bỏ",
            missingText = "Nên bật — nếu không, hệ thống có thể tạm dừng app khi màn hình tắt.",
            actionLabel = "Mở cài đặt",
            onAction = { context.startActivity(batteryOptimizationIntent(context)) },
        )

        /* ----- tinh trang ket noi ----- */
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Kết nối", style = MaterialTheme.typography.titleMedium)
                InfoRow("Server", prefs.serverUrl)
                InfoRow("Tên máy", prefs.deviceName)
                InfoRow("Đang chờ gửi", if (pendingCount == 0L) "0 (đã gửi hết)" else "$pendingCount thông báo")
                InfoRow("Đã gửi", "$sentCount thông báo")
                InfoRow("Đồng bộ lần cuối", formatTime(lastSyncAt))
                if (!lastError.isNullOrBlank()) {
                    Text(
                        "Lỗi gần nhất: $lastError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        /* ----- hanh dong ----- */
        Button(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val uid = "test-" + System.currentTimeMillis()
                        val json = JSONObject()
                            .put("uid", uid)
                            .put("package", "com.notifybridge")
                            .put("appName", "Notify Bridge")
                            .put("title", "Gửi thử")
                            .put("body", "Thông báo thử từ ${prefs.deviceName}")
                            .put("category", "msg")
                            .put("postedAt", System.currentTimeMillis())
                            .toString()
                        Outbox.get(context).enqueue(uid, json)
                        Uploader.drain(context)
                    }
                    refresh++
                    val err = prefs.lastError
                    snackbar.showSnackbar(
                        if (err.isNullOrBlank()) "Đã gửi thử — kiểm tra trên web dashboard"
                        else "Gửi thất bại: $err"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Gửi thử")
        }

        OutlinedButton(
            onClick = {
                UploadWorker.runNow(context)
                scope.launch {
                    snackbar.showSnackbar("Đang gửi lại hàng đợi…")
                }
                refresh++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Gửi lại hàng đợi ngay")
        }

        TextButton(
            onClick = {
                prefs.clearPairing()
                scope.launch {
                    withContext(Dispatchers.IO) { Outbox.get(context).clear() }
                    snackbar.showSnackbar("Đã huỷ ghép đôi")
                    onUnpaired()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Huỷ ghép đôi máy này", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    ok: Boolean,
    okText: String,
    missingText: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (ok) "✓  " else "!  ") + title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (ok) okText else missingText,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Nhu [PermissionCard] nhung cho thu app khong tu kiem tra duoc trang thai. */
@Composable
private fun HintCard(
    title: String,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("•  $title", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun formatTime(ms: Long): String =
    if (ms <= 0) "chưa lần nào"
    else SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date(ms))

private fun notificationAccessIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun isXiaomi(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")

/**
 * MIUI/HyperOS khong co API doc trang thai "Tu khoi dong", chi mo duoc man hinh
 * cai dat. Man hinh do doi ten qua cac ban nen thu lan luot, cuoi cung la trang
 * thong tin app (co muc "Tu khoi dong" o do).
 */
private fun openXiaomiAutostart(context: Context) {
    val candidates = listOf(
        Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        ),
        Intent("miui.intent.action.OP_AUTO_START"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )
    for (intent in candidates) {
        val ok = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun batteryOptimizationIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
