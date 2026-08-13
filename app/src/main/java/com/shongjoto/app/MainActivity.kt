package com.shongjoto.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shongjoto.app.capture.CaptureLog
import com.shongjoto.app.capture.ScreenCaptureService
import com.shongjoto.app.overlay.DebugOverlayState
import com.shongjoto.app.overlay.OverlayService

class MainActivity : ComponentActivity() {

    private val hasOverlayPermission = mutableStateOf(false)
    private val isAccessibilityServiceEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(hasOverlayPermission, isAccessibilityServiceEnabled)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Neither the overlay-permission nor the accessibility-settings screens return a
        // reliable result code, so just recheck both whenever the app comes back to the
        // foreground. (The debug overlay switch doesn't need a resync here — it reads
        // DebugOverlayState directly, which OverlayService keeps accurate regardless of how
        // the overlay was dismissed.)
        hasOverlayPermission.value = Settings.canDrawOverlays(this)
        isAccessibilityServiceEnabled.value =
            isAccessibilityServiceEnabled(this, ScreenCaptureService::class.java)
    }
}

private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices =
        accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { serviceInfo ->
        serviceInfo.resolveInfo.serviceInfo.packageName == context.packageName &&
            serviceInfo.resolveInfo.serviceInfo.name == serviceClass.name
    }
}

@Composable
private fun MainScreen(
    hasOverlayPermissionState: MutableState<Boolean>,
    isAccessibilityServiceEnabledState: MutableState<Boolean>
) {
    val context = LocalContext.current
    val hasOverlayPermission by hasOverlayPermissionState
    val overlayShowing by DebugOverlayState.isShowing
    val isAccessibilityServiceEnabled by isAccessibilityServiceEnabledState

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
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
                        // Don't set DebugOverlayState here — OverlayService is the single
                        // source of truth for it, updated on every real show/hide regardless
                        // of trigger (switch, tap-to-dismiss, or auto-timeout).
                        val serviceIntent = Intent(context, OverlayService::class.java)
                        if (checked) {
                            context.startService(serviceIntent)
                        } else {
                            context.stopService(serviceIntent)
                        }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            Text(
                text = if (isAccessibilityServiceEnabled) {
                    "Accessibility service: ENABLED"
                } else {
                    "Enable the Shongjoto accessibility service to start the screenshot capture loop."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (!isAccessibilityServiceEnabled) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Open accessibility settings")
                }
            } else {
                val entries = CaptureLog.entries
                if (entries.isEmpty()) {
                    Text(
                        text = "Waiting for the first capture…",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "Recent captures (newest first, interval since previous):",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    entries.forEachIndexed { index, entry ->
                        val interval = if (index < entries.lastIndex) {
                            "+${entry.timestampMs - entries[index + 1].timestampMs}ms"
                        } else {
                            "—"
                        }
                        Text(
                            text = "${entry.outcome}  $interval",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
