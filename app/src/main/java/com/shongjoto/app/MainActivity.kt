package com.shongjoto.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shongjoto.app.overlay.OverlayService

class MainActivity : ComponentActivity() {

    private val hasOverlayPermission = mutableStateOf(false)
    private val overlayShowing = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(hasOverlayPermission, overlayShowing)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The overlay-permission settings screen doesn't return a reliable result code,
        // so just recheck whenever the app comes back to the foreground.
        hasOverlayPermission.value = Settings.canDrawOverlays(this)
        // If this activity is visible and interactive again, the overlay can't still be
        // showing (it would be covering this screen) — resync in case it was dismissed
        // by the debug tap-to-dismiss or auto-timeout rather than the switch itself.
        overlayShowing.value = false
    }
}

@Composable
private fun MainScreen(
    hasOverlayPermissionState: MutableState<Boolean>,
    overlayShowingState: MutableState<Boolean>
) {
    val context = LocalContext.current
    val hasOverlayPermission by hasOverlayPermissionState
    var overlayShowing by overlayShowingState

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Shongjoto", style = MaterialTheme.typography.headlineMedium)

            if (!hasOverlayPermission) {
                Text(
                    text = "Grant the \"display over other apps\" permission to test the blur overlay.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
                Button(onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) {
                    Text("Grant overlay permission")
                }
            } else {
                Text(
                    text = if (overlayShowing) "Overlay: SHOWING" else "Overlay: HIDDEN",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )
                Text(
                    text = "Debug: Blur Overlay",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Switch(
                    checked = overlayShowing,
                    onCheckedChange = { checked ->
                        overlayShowing = checked
                        val serviceIntent = Intent(context, OverlayService::class.java)
                        if (checked) {
                            context.startService(serviceIntent)
                        } else {
                            context.stopService(serviceIntent)
                        }
                    }
                )
            }
        }
    }
}
