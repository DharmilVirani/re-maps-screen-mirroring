package com.dharmil.mirrordash.projection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.annotation.MainThread
import com.dharmil.mirrordash.MainActivity
import com.dharmil.mirrordash.panel.PanelPowerRecoveryStore
import com.dharmil.mirrordash.panel.ShizukuPanelController
import com.dharmil.mirrordash.render.SemicircleMirrorRenderer
import com.dharmil.mirrordash.tripper.TripperProfile
import com.dharmil.mirrordash.tripper.TripperSession
import com.dharmil.mirrordash.tripper.TripperTransport
import com.dharmil.mirrordash.tripper.ValidatedTripperProfile
import com.dharmil.mirrordash.tripper.video.EncodedFrame
import com.dharmil.mirrordash.tripper.video.H264Encoder
import com.dharmil.mirrordash.tripper.video.TripperVideoSpec
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProjectionService : Service() {
    private data class OwnedResources(
        val transport: TripperTransport?,
        val connectingTransport: TripperTransport?,
        val session: TripperSession?,
        val encoder: H264Encoder?,
        val renderer: SemicircleMirrorRenderer?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamWorker = Executors.newFixedThreadPool(3)
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val stopStarted = AtomicBoolean(false)
    private val connectionStarted = AtomicBoolean(false)
    private val resourcesLock = Any()

    @Volatile private var activeNetwork: Network? = null
    @Volatile private var activeTransport: TripperTransport? = null
    @Volatile private var connectingTransport: TripperTransport? = null
    @Volatile private var activeSession: TripperSession? = null
    @Volatile private var activeEncoder: H264Encoder? = null
    @Volatile private var activeRenderer: SemicircleMirrorRenderer? = null
    @Volatile private var panelOffApplied = false

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_STOP -> stopSession("Mirroring stopped.", failed = false)
            ACTION_PANEL_OFF -> requestPanelPower(on = false, stopAfterAction = !foregroundStarted, startId = startId)
            ACTION_PANEL_ON -> requestPanelPower(on = true, stopAfterAction = !foregroundStarted, startId = startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession("MirrorDash was closed.", failed = false)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (!stopStarted.get()) stopSession("Mirroring stopped.", failed = false)
        streamWorker.shutdownNow()
        timer.shutdownNow()
        super.onDestroy()
    }

    @MainThread
    private fun startProjection(intent: Intent) {
        if (foregroundStarted || stopStarted.get()) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        val profile = runCatching { intent.readProfile().validate() }.getOrElse { error ->
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, error.message ?: "Check the Tripper connection details."),
            )
            stopSelf()
            return
        }
        if (resultCode == 0 || resultData == null) {
            SessionUiStateStore.update(SessionUiState(SessionPhase.ERROR, "Android screen-sharing consent is missing."))
            stopSelf()
            return
        }
        try {
            startForegroundCompat(buildNotification("Starting screen capture"))
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = checkNotNull(manager.getMediaProjection(resultCode, resultData)) {
                "Android did not grant a screen-sharing session."
            }
            mediaProjection = projection
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopSession("Android stopped screen sharing.", failed = false) }
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, mainHandler)
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.CONNECTING_WIFI, "Checking the phone's connection to the Tripper Wi-Fi."),
            )
            updateNotification("Waiting for Tripper Wi-Fi")
            requestTripperNetwork(profile)
        } catch (error: Exception) {
            stopSession(error.message ?: "Could not start Android screen capture.", failed = true)
        }
    }

    private fun requestTripperNetwork(profile: ValidatedTripperProfile) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                    considerTripperNetwork(network, capabilities, profile)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                considerTripperNetwork(network, networkCapabilities, profile)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
                considerTripperNetwork(network, capabilities, profile, linkProperties)
            }

            override fun onLost(network: Network) {
                if (activeNetwork == network) {
                    mainHandler.post { stopSession("Tripper Wi-Fi disconnected.", failed = true) }
                }
            }
        }
        networkCallback = callback
        SessionUiStateStore.update(
            SessionUiState(
                SessionPhase.CONNECTING_WIFI,
                "Waiting for ${profile.ssid}. Connect the phone to this Wi-Fi in Android settings if needed.",
            ),
        )
        updateNotification("Waiting for Tripper Wi-Fi")
        connectivityManager.registerNetworkCallback(request, callback)
        connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                considerTripperNetwork(network, capabilities, profile)
            }
        }
        timer.schedule({
            if (!connectionStarted.get() && !stopStarted.get()) {
                mainHandler.post {
                    stopSession(
                        "No connected Wi-Fi route to ${profile.bikeAddress.hostAddress}. Join ${profile.ssid} in Android settings, then try again.",
                        failed = true,
                    )
                }
            }
        }, MANUAL_WIFI_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun considerTripperNetwork(
        network: Network,
        capabilities: NetworkCapabilities,
        profile: ValidatedTripperProfile,
        linkProperties: LinkProperties? = connectivityManager.getLinkProperties(network),
    ) {
        if (stopStarted.get() || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val hasBikeRoute = linkProperties?.routes.orEmpty().any { route ->
            route.destination.prefixLength in 1..32 && route.destination.contains(profile.bikeAddress)
        }
        if (!hasBikeRoute || !connectionStarted.compareAndSet(false, true)) return

        activeNetwork = network
        SessionUiStateStore.update(
            SessionUiState(SessionPhase.AUTHENTICATING, "Wi-Fi route to the TFT found. Authenticating with the TFT."),
        )
        updateNotification("Authenticating with Tripper")
        streamWorker.execute { connectAndStart(network, profile) }
    }

    private fun connectAndStart(network: Network, profile: ValidatedTripperProfile) {
        var transport: TripperTransport? = null
        var encoder: H264Encoder? = null
        var renderer: SemicircleMirrorRenderer? = null
        try {
            if (stopStarted.get()) return
            transport = TripperTransport.open(
                network,
                profile.bikeAddress,
                profile.controlPort,
                profile.responsePort,
                profile.videoPort,
            )
            val registered = synchronized(resourcesLock) {
                if (stopStarted.get()) false else {
                    connectingTransport = transport
                    true
                }
            }
            if (!registered) return
            val session = TripperSession(profile, transport)
            session.authenticate()
            if (stopStarted.get()) return
            session.beginProjection()
            if (stopStarted.get()) {
                session.stopProjection()
                return
            }

            acquirePartialWakeLock()
            val videoSpec = TripperVideoSpec(profile.width, profile.height, profile.frameRate, profile.bitrate)
            encoder = H264Encoder(videoSpec, object : H264Encoder.Listener {
                override fun onEncodedFrame(frame: EncodedFrame) {
                    try {
                        session.sendEncodedFrame(frame)
                    } catch (error: Exception) {
                        mainHandler.post {
                            stopSession(error.message ?: "Could not send a video frame to the TFT.", failed = true)
                        }
                    }
                }

                override fun onEncoderError(error: Throwable) {
                    mainHandler.post {
                        stopSession(error.message ?: "The phone's H.264 encoder stopped.", failed = true)
                    }
                }
            })
            val surface = encoder.start()
            if (stopStarted.get()) {
                encoder.close()
                session.stopProjection()
                return
            }
            val windowManager = getSystemService(WindowManager::class.java)
            val sourceBounds = windowManager.maximumWindowMetrics.bounds
            val density = resources.configuration.densityDpi.takeIf { it > 0 } ?: 1
            val mirrorRenderer = SemicircleMirrorRenderer(this, profile.width, profile.height)
            renderer = mirrorRenderer
            mirrorRenderer.start(
                checkNotNull(mediaProjection),
                surface,
                sourceBounds.width(),
                sourceBounds.height(),
                density,
            )

            if (stopStarted.get()) {
                renderer.close()
                encoder.close()
                session.stopProjection()
                return
            }

            val adopted = synchronized(resourcesLock) {
                if (stopStarted.get()) false else {
                    activeTransport = transport
                    activeSession = session
                    activeEncoder = encoder
                    activeRenderer = renderer
                    connectingTransport = null
                    true
                }
            }
            if (!adopted) return
            transport = null
            encoder = null
            renderer = null
            mainHandler.post {
                if (stopStarted.get()) return@post
                SessionUiStateStore.update(
                    SessionUiState(SessionPhase.STREAMING, "Maps is streaming inside the TFT semicircle."),
                )
                updateNotification("Maps is streaming to the Tripper display")
                startControlReceiver()
                timer.scheduleAtFixedRate({
                    runCatching { activeSession?.keepAlive() }
                }, KEEPALIVE_INITIAL_DELAY_SECONDS, KEEPALIVE_PERIOD_SECONDS, TimeUnit.SECONDS)
            }
        } catch (error: Exception) {
            mainHandler.post {
                stopSession(error.message ?: "Could not connect to the Tripper display.", failed = true)
            }
        } finally {
            transport?.let { localTransport ->
                synchronized(resourcesLock) {
                    if (connectingTransport === localTransport) connectingTransport = null
                }
            }
            runCatching { renderer?.close() }
            runCatching { encoder?.close() }
            runCatching { transport?.close() }
        }
    }

    private fun startControlReceiver() {
        val transport = activeTransport ?: return
        val session = activeSession ?: return
        streamWorker.execute {
            while (!stopStarted.get()) {
                try {
                    session.handleIncoming(transport.receiveControl())
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (error: IOException) {
                    if (!stopStarted.get()) {
                        mainHandler.post { stopSession("Tripper control connection ended.", failed = true) }
                    }
                    return@execute
                } catch (error: Exception) {
                    if (!stopStarted.get()) {
                        mainHandler.post { stopSession(error.message ?: "Tripper control connection failed.", failed = true) }
                    }
                    return@execute
                }
            }
        }
    }

    @MainThread
    private fun requestPanelPower(on: Boolean, stopAfterAction: Boolean = false, startId: Int = 0) {
        if (!on && SessionUiStateStore.state.value.phase != SessionPhase.STREAMING) {
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, "Start a Maps stream before turning the phone panel off."),
            )
            if (stopAfterAction) finishOneShotAction(startId)
            return
        }
        if (!on && !PanelPowerRecoveryStore.setRestorePending(this, true)) {
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, "Could not save panel recovery state. The phone panel was left on."),
            )
            if (stopAfterAction) finishOneShotAction(startId)
            return
        }
        ShizukuPanelController.setPanelPower(this, on) { requestedOn, success, message ->
            var resultMessage = message
            if (success && requestedOn) {
                panelOffApplied = false
                if (!PanelPowerRecoveryStore.setRestorePending(this, false)) {
                    resultMessage = "$message Recovery state could not be cleared."
                }
            } else if (success) {
                panelOffApplied = true
            }
            if (!requestedOn && success && stopStarted.get()) {
                ShizukuPanelController.setPanelPower(this, true) { _, restored, restoreMessage ->
                    if (restored) {
                        panelOffApplied = false
                        PanelPowerRecoveryStore.setRestorePending(this, false)
                    }
                    SessionUiStateStore.update(
                        SessionUiState(
                            phase = if (restored) SessionPhase.IDLE else SessionPhase.ERROR,
                            message = if (restored) "Mirroring stopped and phone panel restored." else restoreMessage,
                            panelOff = !restored,
                        ),
                    )
                }
                return@setPanelPower
            }
            SessionUiStateStore.update(
                SessionUiState(
                    phase = if (requestedOn && success && stopAfterAction) SessionPhase.IDLE else SessionUiStateStore.state.value.phase,
                    message = resultMessage,
                    panelOff = if (requestedOn) !success && PanelPowerRecoveryStore.isRestorePending(this) else success,
                ),
            )
            updateNotification(if (success && !requestedOn) "Phone panel off; stream remains active" else resultMessage)
            if (stopAfterAction) finishOneShotAction(startId)
        }
    }

    private fun finishOneShotAction(startId: Int) {
        if (!foregroundStarted && stopStarted.compareAndSet(false, true)) stopSelf(startId)
    }

    @MainThread
    private fun stopSession(message: String, failed: Boolean) {
        if (!stopStarted.compareAndSet(false, true)) return
        val panelMayBeOff = panelOffApplied || PanelPowerRecoveryStore.isRestorePending(this)
        SessionUiStateStore.update(
            SessionUiState(
                phase = if (failed) SessionPhase.ERROR else SessionPhase.IDLE,
                message = message,
                panelOff = panelMayBeOff,
            ),
        )
        val owned = synchronized(resourcesLock) {
            OwnedResources(activeTransport, connectingTransport, activeSession, activeEncoder, activeRenderer).also {
                activeTransport = null
                connectingTransport = null
                activeSession = null
                activeEncoder = null
                activeRenderer = null
            }
        }
        runCatching { networkCallback?.let(connectivityManager::unregisterNetworkCallback) }
        networkCallback = null
        runCatching { owned.session?.stopProjection() }
        runCatching { owned.transport?.close() }
        runCatching { owned.connectingTransport?.close() }
        runCatching { owned.renderer?.close() }
        runCatching { owned.encoder?.close() }
        projectionCallback?.let { callback -> runCatching { mediaProjection?.unregisterCallback(callback) } }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        activeNetwork = null
        if (panelMayBeOff) {
            ShizukuPanelController.setPanelPower(this, true) { _, success, restoreMessage ->
                if (success) {
                    panelOffApplied = false
                    PanelPowerRecoveryStore.setRestorePending(this, false)
                }
                val restoredMessage = when {
                    !success -> "$message $restoreMessage"
                    PanelPowerRecoveryStore.isRestorePending(this) -> "$message Phone panel restored; recovery reminder could not be cleared."
                    else -> "$message Phone panel restored."
                }
                SessionUiStateStore.update(
                    SessionUiState(
                        phase = if (failed) SessionPhase.ERROR else SessionPhase.IDLE,
                        message = restoredMessage,
                        panelOff = !success,
                    ),
                )
            }
        }
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun acquirePartialWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:MirrorDashProjection")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification(message: String) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MirrorDash")
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action("Panel off", ACTION_PANEL_OFF, REQUEST_PANEL_OFF))
            .addAction(action("Panel on", ACTION_PANEL_ON, REQUEST_PANEL_ON))
            .addAction(action("Stop", ACTION_STOP, REQUEST_STOP))
            .build()
    }

    private fun action(title: String, action: String, requestCode: Int): Notification.Action {
        val intent = createActionIntent(this, action)
        val pendingIntent = PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            title,
            pendingIntent,
        ).build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Maps projection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows and controls an active Tripper screen projection." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun Intent.readProfile() = TripperProfile(
        ssid = getStringExtra(EXTRA_SSID).orEmpty(),
        wifiPassphrase = getStringExtra(EXTRA_PASSPHRASE).orEmpty(),
        bikeIp = getStringExtra(EXTRA_BIKE_IP).orEmpty(),
        controlPort = getStringExtra(EXTRA_CONTROL_PORT).orEmpty(),
        responsePort = getStringExtra(EXTRA_RESPONSE_PORT).orEmpty(),
        videoPort = getStringExtra(EXTRA_VIDEO_PORT).orEmpty(),
        width = getStringExtra(EXTRA_WIDTH).orEmpty(),
        height = getStringExtra(EXTRA_HEIGHT).orEmpty(),
        frameRate = getStringExtra(EXTRA_FRAME_RATE).orEmpty(),
        bitrate = getStringExtra(EXTRA_BITRATE).orEmpty(),
    )

    companion object {
        const val ACTION_START = "com.dharmil.mirrordash.action.START"
        const val ACTION_STOP = "com.dharmil.mirrordash.action.STOP"
        const val ACTION_PANEL_OFF = "com.dharmil.mirrordash.action.PANEL_OFF"
        const val ACTION_PANEL_ON = "com.dharmil.mirrordash.action.PANEL_ON"

        private const val EXTRA_RESULT_CODE = "capture_result_code"
        private const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val EXTRA_SSID = "tripper_ssid"
        private const val EXTRA_PASSPHRASE = "tripper_passphrase"
        private const val EXTRA_BIKE_IP = "tripper_bike_ip"
        private const val EXTRA_CONTROL_PORT = "tripper_control_port"
        private const val EXTRA_RESPONSE_PORT = "tripper_response_port"
        private const val EXTRA_VIDEO_PORT = "tripper_video_port"
        private const val EXTRA_WIDTH = "video_width"
        private const val EXTRA_HEIGHT = "video_height"
        private const val EXTRA_FRAME_RATE = "video_frame_rate"
        private const val EXTRA_BITRATE = "video_bitrate"
        private const val NOTIFICATION_CHANNEL_ID = "mirrordash_projection"
        private const val NOTIFICATION_ID = 30
        private const val MANUAL_WIFI_WAIT_TIMEOUT_SECONDS = 120L
        private const val KEEPALIVE_INITIAL_DELAY_SECONDS = 1L
        private const val KEEPALIVE_PERIOD_SECONDS = 3L
        private const val REQUEST_OPEN_APP = 100
        private const val REQUEST_PANEL_OFF = 101
        private const val REQUEST_PANEL_ON = 102
        private const val REQUEST_STOP = 103

        fun createStartIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            profile: TripperProfile,
        ): Intent = Intent(context, ProjectionService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_SSID, profile.ssid)
            putExtra(EXTRA_PASSPHRASE, profile.wifiPassphrase)
            putExtra(EXTRA_BIKE_IP, profile.bikeIp)
            putExtra(EXTRA_CONTROL_PORT, profile.controlPort)
            putExtra(EXTRA_RESPONSE_PORT, profile.responsePort)
            putExtra(EXTRA_VIDEO_PORT, profile.videoPort)
            putExtra(EXTRA_WIDTH, profile.width)
            putExtra(EXTRA_HEIGHT, profile.height)
            putExtra(EXTRA_FRAME_RATE, profile.frameRate)
            putExtra(EXTRA_BITRATE, profile.bitrate)
        }

        fun createActionIntent(context: Context, action: String): Intent =
            Intent(context, ProjectionService::class.java).setAction(action)
    }
}
