package com.example.appbackup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllFilesAccess()
        val apps = BackupEngine(this, RootShell()).listApps() // listing needs no privileges

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val selected = remember { mutableStateListOf<String>() }
                var engine by remember { mutableStateOf<BackupEngine?>(null) }
                var mode by remember { mutableStateOf("Not connected. Choose Root or Shizuku.") }
                var status by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }

                fun setShell(s: Shell?, label: String) {
                    if (s == null) { mode = "$label failed."; engine = null; return }
                    engine = BackupEngine(this@MainActivity, s)
                    mode = if (s.isRoot) "$label: full backup (incl. private data)"
                    else "$label (ADB mode): no private data, rest works"
                }

                fun connectRoot() = scope.launch(Dispatchers.IO) {
                    mode = "Requesting root…"
                    setShell(ShellFactory.tryRoot(), "Root")
                }

                fun connectShizuku() = scope.launch(Dispatchers.IO) {
                    when {
                        !ShellFactory.shizukuRunning() ->
                            mode = "Shizuku isn't running. Open the Shizuku app and start it."
                        !ShellFactory.shizukuGranted() -> {
                            Shizuku.requestPermission(1)
                            mode = "Allow the permission, then tap Shizuku again."
                        }
                        else -> {
                            mode = "Connecting to Shizuku…"
                            setShell(ShellFactory.connectShizuku(applicationContext), "Shizuku")
                        }
                    }
                }

                fun runJob(action: (BackupEngine, String) -> String) = scope.launch {
                    val e = engine ?: return@launch
                    busy = true
                    withContext(Dispatchers.IO) {
                        selected.toList().forEach { pkg ->
                            status = "Working on $pkg…"
                            status = runCatching { action(e, pkg) }.getOrElse { "Error: ${it.message}" }
                        }
                    }
                    busy = false
                }

                Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                    Text(mode, style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { connectRoot() }) { Text("Root") }
                        OutlinedButton(onClick = { connectShizuku() }) { Text("Shizuku") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ready = engine != null && !busy && selected.isNotEmpty()
                        Button(enabled = ready, onClick = { runJob { e, p -> e.backup(p) } }) { Text("Backup") }
                        Button(enabled = ready, onClick = { runJob { e, p -> e.restore(p) } }) { Text("Restore") }
                    }
                    if (status.isNotEmpty()) Text(status, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    LazyColumn {
                        items(apps) { app ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (app.pkg in selected) selected.remove(app.pkg) else selected.add(app.pkg)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = app.pkg in selected, onCheckedChange = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(app.label)
                                    Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }
}
