package com.notifybridge.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifybridge.data.rememberBatteryState
import kotlinx.coroutines.withTimeoutOrNull

/** Phai giu tay bao lau moi mo khoa. Du dai de khong bao gio cham nham. */
private const val HOLD_TO_UNLOCK_MS = 3000L

/**
 * Lop phu den kin man hinh khi che do ngu dang bat.
 *
 * Nuot toan bo su kien cham nen khong bam nham vao gi ben duoi duoc. Loi ra duy
 * nhat trong app la giu tay 3 giay. (Loi ra cua he thong: giu dong thoi nut Quay lai
 * va Tong quan de bo ghim man hinh, hoac tat may.)
 */
@Composable
fun SleepOverlay(onUnlock: () -> Unit) {
    var holding by remember { mutableStateOf(false) }

    // Thanh tien trinh chay day trong dung khoang thoi gian phai giu,
    // de nguoi dung biet la may co nhan tay chu khong phai treo.
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) HOLD_TO_UNLOCK_MS.toInt() else 200),
        label = "unlock",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // requireUnconsumed = false: nhan ca su kien da bi ai do doc qua.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    holding = true

                    // withTimeoutOrNull tra ve null nghia la het 3 giay ma tay
                    // van chua nhac len -> mo khoa.
                    val released = withTimeoutOrNull(HOLD_TO_UNLOCK_MS) {
                        waitForUpOrCancellation()
                    }

                    holding = false
                    if (released == null) onUnlock()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Pin la thu duy nhat de to: may cam sac ca ngay thi can liec mot cai
            // la biet no con song hay sap het.
            val battery by rememberBatteryState()
            if (battery.percent >= 0) {
                Text(
                    (if (battery.charging) "⚡ " else "") + battery.percent + "%",
                    color = when {
                        battery.charging -> Color(0xFF34C77B).copy(alpha = 0.75f)
                        battery.percent <= 15 -> Color(0xFFF0736F).copy(alpha = 0.85f)
                        else -> Color.White.copy(alpha = 0.6f)
                    },
                    fontSize = 34.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (battery.charging) "đang sạc" else "không sạc",
                    color = Color.White.copy(alpha = 0.28f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }

            Text(
                "Chế độ ngủ",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                if (holding) "Giữ nguyên tay…" else "Giữ tay 3 giây để mở khoá",
                color = Color.White.copy(alpha = if (holding) 0.75f else 0.35f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )

            if (holding) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
            }

            Text(
                "Màn hình vẫn đang nhận thông báo bình thường.",
                color = Color.White.copy(alpha = 0.22f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
