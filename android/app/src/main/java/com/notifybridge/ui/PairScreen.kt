package com.notifybridge.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.notifybridge.data.DeviceId
import com.notifybridge.data.Prefs
import com.notifybridge.net.Api
import com.notifybridge.net.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PairScreen(
    prefs: Prefs,
    snackbar: SnackbarHostState,
    onPaired: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.serverUrl) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(prefs.deviceName) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ghép đôi với server", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trên máy tính:", style = MaterialTheme.typography.titleSmall)
                Text("1. Mở web dashboard và đăng nhập.", style = MaterialTheme.typography.bodyMedium)
                Text("2. Bấm nút “＋ Thêm điện thoại”.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Nhập địa chỉ và mã 6 số hiện ra vào đây.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Địa chỉ server") },
            placeholder = { Text("https://notify.tenmien.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = code,
            onValueChange = { input -> code = input.filter(Char::isDigit).take(6) },
            label = { Text("Mã ghép đôi (6 số)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Tên máy này") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                val server = Prefs.normalizeUrl(url)
                when {
                    server.isEmpty() -> scope.launch { snackbar.showSnackbar("Chưa nhập địa chỉ server") }
                    code.length != 6 -> scope.launch { snackbar.showSnackbar("Mã ghép đôi phải đủ 6 số") }
                    else -> scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                Api.pair(
                                    baseUrl = server,
                                    code = code,
                                    deviceName = name.trim().ifEmpty { Prefs.defaultDeviceName() },
                                    model = Build.MODEL ?: "",
                                    androidVersion = Build.VERSION.RELEASE ?: "",
                                    hardwareId = DeviceId.hardwareId(context),
                                )
                            }
                        }
                        busy = false

                        result
                            .onSuccess { pair ->
                                prefs.serverUrl = server
                                prefs.deviceId = pair.deviceId
                                prefs.token = pair.token
                                prefs.deviceName = pair.deviceName
                                prefs.lastError = null
                                Uploader.sendHeartbeat(context)
                                snackbar.showSnackbar(
                                    if (pair.reused) "Đã nhận lại máy cũ — giữ nguyên lịch sử thông báo"
                                    else "Đã ghép đôi thành công"
                                )
                                onPaired()
                            }
                            .onFailure { e ->
                                snackbar.showSnackbar(e.message ?: "Ghép đôi thất bại")
                            }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Kết nối")
            }
        }

        Text(
            "Mã ghép đôi chỉ dùng được một lần và hết hạn sau 10 phút.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
