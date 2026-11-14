package com.dharmil.mirrordash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku
import com.dharmil.mirrordash.panel.PanelPowerRecoveryStore
import com.dharmil.mirrordash.panel.PanelBubbleService
import com.dharmil.mirrordash.panel.ShizukuPanelController
import com.dharmil.mirrordash.projection.ProjectionService
import com.dharmil.mirrordash.projection.SessionPhase
import com.dharmil.mirrordash.projection.SessionUiState
import com.dharmil.mirrordash.projection.SessionUiStateStore
import com.dharmil.mirrordash.tripper.TripperProfile
import com.dharmil.mirrordash.ride.RideSessionService

private const val SHIZUKU_PERMISSION_REQUEST = 701

class MainActivity : ComponentActivity() {
    private var pendingProfile: TripperProfile? = null
    private var pendingRendererProfile: TripperProfile? = null

    private val bubbleNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) requestBubbleOverlayPermission() else updateMessage(
            "Allow MirrorDash notifications so the active display-off bubble can be stopped from its notification.",
        )
    }

    private val bubbleOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            startPanelBubbleService()
        } else {
            updateMessage("Allow MirrorDash to display over other apps, then tap Show display-off bubble again.")
        }
    }

    private val rendererPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val profile = pendingRendererProfile
        pendingRendererProfile = null
        if (results.values.all { it } && profile != null) {
            startRendererSpike(profile)
        } else {
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, "Allow Nearby Wi-Fi and notifications to start the renderer test."),
            )
        }
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            launchProjectionConsent()
        } else {
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, "Allow nearby Wi-Fi and notifications, then try again."),
            )
        }
    }

    private val projectionConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val profile = pendingProfile
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null || profile == null) {
            pendingProfile = null
            SessionUiStateStore.update(SessionUiState(message = "Screen sharing was cancelled."))
            return@registerForActivityResult
        }

        runCatching {
            startForegroundService(
                ProjectionService.createStartIntent(this, result.resultCode, data, profile),
            )
        }.onFailure { error ->
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, error.message ?: "Could not start the projection service."),
            )
        }
        pendingProfile = null
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            updateMessage(
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    "Shizuku is ready for panel control."
                } else {
                    "Shizuku permission was denied. Mirroring can still run with the panel on."
                },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        if (PanelPowerRecoveryStore.isRestorePending(this) && getSystemService(PowerManager::class.java).isInteractive) {
            PanelPowerRecoveryStore.setRestorePending(this, false)
        }
        if (PanelPowerRecoveryStore.isRestorePending(this)) {
            SessionUiStateStore.update(
                SessionUiState(
                    phase = SessionPhase.ERROR,
                    message = "A previous panel-off may need restoring. Tap Panel on after reopening MirrorDash.",
                    panelOff = true,
                ),
            )
        }
        setContent {
            val session by SessionUiStateStore.state.collectAsState()
            val shizukuReady = ShizukuPanelController.hasPermission()
            val debugBuild = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            MirrorDashTheme {
                MirrorDashScreen(
                    session = session,
                    shizukuReady = shizukuReady,
                    showRendererSpike = debugBuild,
                    onStart = ::requestStart,
                    onStop = {
                        startService(ProjectionService.createActionIntent(this, ProjectionService.ACTION_STOP))
                        startService(RideSessionService.createActionIntent(this))
                    },
                    onStartRendererSpike = ::requestRendererSpike,
                    onOpenMaps = ::openMaps,
                    onRequestShizuku = ::requestShizukuPermission,
                    onPanelOff = { startService(ProjectionService.createActionIntent(this, ProjectionService.ACTION_PANEL_OFF)) },
                    onPanelOn = { startService(ProjectionService.createActionIntent(this, ProjectionService.ACTION_PANEL_ON)) },
                    onShowPanelBubble = ::requestPanelBubble,
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun requestStart(profile: TripperProfile) {
        val validProfile = runCatching { profile.validate() }
        if (validProfile.isFailure) {
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, validProfile.exceptionOrNull()?.message ?: "Check the connection details."),
            )
            return
        }
        pendingProfile = profile
        SessionUiStateStore.update(
            SessionUiState(SessionPhase.REQUESTING_CAPTURE, "Preparing Wi-Fi and screen-sharing permissions."),
        )
        val required = buildList {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (required.isNotEmpty()) runtimePermissionLauncher.launch(required.toTypedArray()) else launchProjectionConsent()
    }

    private fun requestRendererSpike(profile: TripperProfile) {
        val validProfile = runCatching { profile.validate() }
        if (validProfile.isFailure) {
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, validProfile.exceptionOrNull()?.message ?: "Check the Tripper settings."),
            )
            return
        }
        pendingRendererProfile = profile
        val required = buildList {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (required.isEmpty()) {
            pendingRendererProfile = null
            startRendererSpike(profile)
        } else {
            rendererPermissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun startRendererSpike(profile: TripperProfile) {
        SessionUiStateStore.update(
            SessionUiState(SessionPhase.CONNECTING_WIFI, "Starting the app-owned renderer test."),
        )
        runCatching {
            startForegroundService(RideSessionService.createRendererSpikeIntent(this, profile))
        }.onFailure { error ->
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, error.message ?: "Could not start the renderer test."),
            )
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        SessionUiStateStore.update(
            SessionUiState(SessionPhase.REQUESTING_CAPTURE, "Choose Google Maps in the Android screen-sharing prompt."),
        )
        projectionConsentLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestShizukuPermission() {
        runCatching {
            if (!Shizuku.pingBinder()) {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) {
                    startActivity(launch)
                    updateMessage("Start Shizuku, return here, tap Set up again and grant MirrorDash access.")
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download")))
                    updateMessage("Install and start Shizuku, then return here and tap Set up again.")
                }
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                updateMessage("Shizuku is ready for panel control.")
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
        }.onFailure { error ->
            updateMessage(error.message ?: "Shizuku is unavailable.")
        }
    }

    private fun requestPanelBubble() {
        if (!ShizukuPanelController.hasPermission()) {
            updateMessage("Set up Shizuku and grant MirrorDash permission before enabling the display-off bubble.")
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            bubbleNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestBubbleOverlayPermission()
    }

    private fun requestBubbleOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            startPanelBubbleService()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", packageName, null),
        )
        runCatching { bubbleOverlayPermissionLauncher.launch(intent) }
            .onFailure { updateMessage("Open Android Settings and allow MirrorDash to display over other apps.") }
    }

    private fun startPanelBubbleService() {
        runCatching {
            startForegroundService(PanelBubbleService.createShowIntent(this))
            updateMessage("Display-off bubble is ready. Open Royal Enfield navigation and tap the floating power button when parked.")
        }.onFailure { error ->
            updateMessage(error.message ?: "Could not start the display-off bubble.")
        }
    }

    private fun openMaps() {
        val mapsIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (mapsIntent == null) {
            updateMessage("Google Maps is not installed.")
            return
        }
        startActivity(mapsIntent)
    }

    private fun updateMessage(message: String) {
        SessionUiStateStore.update(SessionUiStateStore.state.value.copy(message = message))
    }

}

@Composable
private fun MirrorDashTheme(content: @Composable () -> Unit) {
    val colors: ColorScheme = darkColorScheme(
        primary = Color(0xFF80CBC4),
        onPrimary = Color(0xFF003733),
        secondary = Color(0xFFB0C9C7),
        onSecondary = Color(0xFF1B3533),
        background = Color(0xFF101419),
        onBackground = Color(0xFFF2F5F6),
        surface = Color(0xFF1B2229),
        onSurface = Color(0xFFF2F5F6),
        surfaceVariant = Color(0xFF29333A),
        onSurfaceVariant = Color(0xFFBBC6CB),
        outline = Color(0xFF52616A),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun MirrorDashScreen(
    session: SessionUiState,
    shizukuReady: Boolean,
    showRendererSpike: Boolean,
    onStart: (TripperProfile) -> Unit,
    onStop: () -> Unit,
    onStartRendererSpike: (TripperProfile) -> Unit,
    onOpenMaps: () -> Unit,
    onRequestShizuku: () -> Unit,
    onPanelOff: () -> Unit,
    onPanelOn: () -> Unit,
    onShowPanelBubble: () -> Unit,
) {
    var profile by remember { mutableStateOf(TripperProfile()) }
    val active = session.phase in setOf(
        SessionPhase.REQUESTING_CAPTURE,
        SessionPhase.CONNECTING_WIFI,
        SessionPhase.AUTHENTICATING,
        SessionPhase.STREAMING,
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MirrorDash", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text("Send a Maps window into the TFT semicircle", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Start a route in Maps and rotate the phone to landscape before connecting.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            StatusPanel(session)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle("Bike connection")
                    InputField("Tripper Wi-Fi name", profile.ssid, { profile = profile.copy(ssid = it) })
                    InputField(
                        "Renderer-test Wi-Fi password (optional)", profile.wifiPassphrase,
                        { profile = profile.copy(wifiPassphrase = it) },
                        password = true,
                    )
                    InputField("TFT IPv4 address", profile.bikeIp, { profile = profile.copy(bikeIp = it) })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InputField("Control UDP", profile.controlPort, { profile = profile.copy(controlPort = it) }, number = true, modifier = Modifier.weight(1f))
                        InputField("Response UDP", profile.responsePort, { profile = profile.copy(responsePort = it) }, number = true, modifier = Modifier.weight(1f))
                    }
                    InputField("Video UDP", profile.videoPort, { profile = profile.copy(videoPort = it) }, number = true)
                    Text(
                        "For Connect & mirror, join this Wi-Fi in Android settings first. MirrorDash waits for this SSID and uses the local link for Tripper traffic.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Text(
                        "The selected Maps window is fitted inside a semicircular mask. Maps' own ETA and distance stay in the mirrored image; MirrorDash does not save or upload the screen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Text(
                        "The video is not encrypted, and the TFT is not cryptographically verified. Connect only to a Tripper Wi-Fi network you trust.",
                        color = Color(0xFFF0B86E),
                        fontSize = 13.sp,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle("Stream format")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InputField("Width", profile.width, { profile = profile.copy(width = it) }, number = true, modifier = Modifier.weight(1f))
                        InputField("Height", profile.height, { profile = profile.copy(height = it) }, number = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InputField("Frames per second", profile.frameRate, { profile = profile.copy(frameRate = it) }, number = true, modifier = Modifier.weight(1f))
                        InputField("Bitrate (bps)", profile.bitrate, { profile = profile.copy(bitrate = it) }, number = true, modifier = Modifier.weight(1f))
                    }
                    Text(
                        "Test ceiling: 1280 × 720 at 30 fps and 5,000,000 bps. These are MirrorDash limits, not a confirmed TFT limit; verify while parked.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionTitle("Phone panel control")
                    Text(
                        if (shizukuReady) "Shizuku permission is ready." else "Panel-only off needs Shizuku and its user-approved permission.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRequestShizuku, modifier = Modifier.weight(1f).height(50.dp)) {
                            Text(if (shizukuReady) "Shizuku ready" else "Set up")
                        }
                        OutlinedButton(
                            onClick = onPanelOff,
                            enabled = shizukuReady && active,
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text("Panel off") }
                        OutlinedButton(
                            onClick = onPanelOn,
                            enabled = shizukuReady,
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) { Text("Panel on") }
                    }
                    OutlinedButton(
                        onClick = onShowPanelBubble,
                        enabled = shizukuReady,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text("Show display-off bubble") }
                    Text(
                        "Allow Display over other apps when Android asks. Enable the bubble before starting Royal Enfield navigation; then open RE, connect the TFT and start your route. Tap the floating power button to turn off the panel without leaving RE. Drag it to move; hide it from the ongoing notification.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Text(
                        "Panel-off does not itself lock the phone. On the tested phone, the power key wakes to the lock screen. Panel-off behavior varies by device and may stop screen projection.",
                        color = Color(0xFFF0B86E),
                        fontSize = 13.sp,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onStart(profile) },
                    enabled = !active,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Connect & mirror", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.width(92.dp).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Stop") }
            }

            if (showRendererSpike) {
                OutlinedButton(
                    onClick = { onStartRendererSpike(profile) },
                    enabled = !active,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Debug: test app-owned TFT renderer") }
            }

            OutlinedButton(
                onClick = onOpenMaps,
                enabled = active,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Return to Google Maps") }

            Text(
                "Set up and test while parked. Keep the phone securely mounted. This app does not make the phone safe to handle while riding.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusPanel(session: SessionUiState) {
    val statusColor = when (session.phase) {
        SessionPhase.STREAMING -> MaterialTheme.colorScheme.primary
        SessionPhase.ERROR -> MaterialTheme.colorScheme.error
        else -> Color(0xFFF0B86E)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(session.phase.label, color = statusColor, fontWeight = FontWeight.SemiBold)
            Text(session.message, color = MaterialTheme.colorScheme.onSurface)
            if (session.panelOff) Text("Phone panel is off", color = statusColor, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    number: Boolean = false,
    password: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

private val SessionPhase.label: String
    get() = when (this) {
        SessionPhase.IDLE -> "Ready"
        SessionPhase.REQUESTING_CAPTURE -> "Screen sharing"
        SessionPhase.CONNECTING_WIFI -> "Connecting to Tripper Wi-Fi"
        SessionPhase.AUTHENTICATING -> "Starting Tripper projection"
        SessionPhase.STREAMING -> "Mirroring Maps"
        SessionPhase.ERROR -> "Needs attention"
    }
