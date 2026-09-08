package com.notifybridge.ui

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

        /* ----- quyen can cap ----- */
        PermissionCard(
            title = "Quyền đọc thông báo",
            ok = listenerOn,
            okText = "Đã cấp",
            missingText = "Bắt buộc — không có quyền này app không đọc được thông báo nào.",
            actionLabel = if (listenerOn) "Mở cài đặt" else "Cấp quyền",
            onAction = { context.startActivity(notificationAccessIntent()) },
        )

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
