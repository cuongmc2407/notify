package com.notifybridge.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notifybridge.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppRow(val pkg: String, val label: String, val system: Boolean)

@Composable
fun AppFilterScreen(prefs: Prefs) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val apps = remember { mutableStateListOf<AppRow>() }
    val blocked = remember { mutableStateListOf<String>().apply { addAll(prefs.blockedPackages) } }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadApps(context.packageManager, context.packageName) }
        apps.clear()
        apps.addAll(loaded)
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val visible = remember(query, apps.size) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps.toList()
        else apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Tìm ứng dụng") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Bat/tat hang loat. Khi dang tim kiem thi chi tac dong len danh sach
        // dang hien, nen co the go "google" roi tat het cac app cua Google.
        fun setAll(allow: Boolean) {
            val pkgs = visible.map { it.pkg }.toSet()
            val next = prefs.blockedPackages.toMutableSet()
            if (allow) next.removeAll(pkgs) else next.addAll(pkgs)
            prefs.blockedPackages = next
            blocked.clear()
            blocked.addAll(next)
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                if (query.isBlank()) "Bật = chuyển tiếp thông báo của app đó"
                else "Đang lọc: ${visible.size} ứng dụng — nút bên dưới chỉ áp dụng cho danh sách này",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { setAll(true) }, modifier = Modifier.weight(1f)) {
                    Text("Bật tất cả (${visible.size})")
                }
                TextButton(onClick = { setAll(false) }, modifier = Modifier.weight(1f)) {
                    Text("Tắt tất cả (${visible.size})")
                }
            }
        }

        HorizontalDivider()

        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.pkg }) { app ->
                val isBlocked = app.pkg in blocked
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            app.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            app.pkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = !isBlocked,
                        onCheckedChange = { allow ->
                            if (allow) blocked.remove(app.pkg) else blocked.add(app.pkg)
                            prefs.setBlocked(app.pkg, !allow)
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private fun loadApps(pm: PackageManager, selfPackage: String): List<AppRow> =
    pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .asSequence()
        // Bo chinh app nay: thong bao cua no von da khong bao gio duoc chuyen tiep.
        .filter { it.enabled && it.packageName != selfPackage }
        .map { info ->
            AppRow(
                pkg = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName),
                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        // App nguoi dung cai truoc, app he thong xuong duoi; trong moi nhom xep theo ten.
        .sortedWith(compareBy<AppRow> { it.system }.thenBy { it.label.lowercase() })
        .toList()
