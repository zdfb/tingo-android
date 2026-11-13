package io.github.zdfb.tingo.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.zdfb.tingo.watch.recording.WearRecordingService

class WearHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var recording by remember { mutableStateOf(false) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    startService(Intent(this, WearRecordingService::class.java))
                    recording = true
                }
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(if (recording) "正在录音" else "听过", style = MaterialTheme.typography.titleLarge)
                        Text(if (recording) "手机端实时转写中" else "连接手机后实时转写", modifier = Modifier.padding(top = 8.dp))
                        Button(
                            onClick = {
                                if (recording) {
                                    stopService(Intent(this@WearHomeActivity, WearRecordingService::class.java))
                                    recording = false
                                } else if (ContextCompat.checkSelfPermission(this@WearHomeActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    startService(Intent(this@WearHomeActivity, WearRecordingService::class.java))
                                    recording = true
                                } else {
                                    permission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text(if (recording) "结束录音" else "开始录音")
                        }
                    }
                }
            }
        }
    }
}
