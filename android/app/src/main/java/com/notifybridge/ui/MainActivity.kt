package com.notifybridge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notifybridge.data.Prefs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotifyBridgeTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val snackbar = remember { SnackbarHostState() }

    // Tang len de bat man hinh ve lai sau khi ghep doi / huy ghep doi.
    var revision by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }

    val paired = remember(revision) { prefs.isPaired }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Notify Bridge") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (!paired) {
                PairScreen(
                    prefs = prefs,
                    snackbar = snackbar,
                    onPaired = { revision++ },
                )
            } else {
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("Trạng thái", style = MaterialTheme.typography.labelLarge) },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("Bộ lọc app", style = MaterialTheme.typography.labelLarge) },
                    )
                }
                when (tab) {
                    0 -> StatusScreen(
                        prefs = prefs,
                        snackbar = snackbar,
                        onUnpaired = {
                            tab = 0
                            revision++
                        },
                    )

                    else -> AppFilterScreen(prefs = prefs)
                }
            }
        }
    }
}
