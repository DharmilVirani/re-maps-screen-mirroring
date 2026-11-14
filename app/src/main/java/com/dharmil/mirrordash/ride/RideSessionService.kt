package com.dharmil.mirrordash.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.MainThread
import com.dharmil.mirrordash.MainActivity
import com.dharmil.mirrordash.projection.SessionPhase
import com.dharmil.mirrordash.projection.SessionUiState
import com.dharmil.mirrordash.projection.SessionUiStateStore
import com.dharmil.mirrordash.render.TripperMapRenderer
import com.dharmil.mirrordash.tripper.TripperProfile
import com.dharmil.mirrordash.tripper.TripperSession
import com.dharmil.mirrordash.tripper.TripperTransport
import com.dharmil.mirrordash.tripper.ValidatedTripperProfile
import com.dharmil.mirrordash.tripper.video.EncodedFrame
import com.dharmil.mirrordash.tripper.video.H264Encoder
import com.dharmil.mirrordash.tripper.video.TripperVideoSpec
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary, user-started service for proving app-owned virtual-display output
 * on the target phone and TFT. Route/map readiness is added after this gate.
 */
class RideSessionService : Service() {
    private data class OwnedResources(
        val transport: TripperTransport?,
        val connectingTransport: TripperTransport?,
        val session: TripperSession?,
        val encoder: H264Encoder?,
        val renderer: TripperMapRenderer?,
        val connectingRenderer: TripperMapRenderer?,
        val wakeLock: PowerManager.WakeLock?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamWorker = Executors.newFixedThreadPool(4)
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val stopStarted = AtomicBoolean(false)
    private val connectionStarted = AtomicBoolean(false)
    private val resourcesLock = Any()

    @Volatile private var activeNetwork: Network? = null
    @Volatile private var activeTransport: TripperTransport? = null
    @Volatile private var connectingTransport: TripperTransport? = null
    @Volatile private var activeSession: TripperSession? = null
    @Volatile private var activeEncoder: H264Encoder? = null
    @Volatile private var activeRenderer: TripperMapRenderer? = null
    @Volatile private var connectingRenderer: TripperMapRenderer? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var foregroundStarted = false

    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RENDERER_SPIKE -> startRendererSpike(intent)
            ACTION_STOP -> stopSession("Renderer test stopped.", failed = false)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!stopStarted.get()) stopSession("Renderer test stopped.", failed = false)
        super.onDestroy()
    }

    @MainThread
    private fun startRendererSpike(intent: Intent) {
        if (foregroundStarted || stopStarted.get()) return
        val profile = runCatching { intent.readProfile().validate() }.getOrElse { error ->
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.ERROR, error.message ?: "Check the Tripper connection details."),
            )
            stopSelf()
            return
        }

        try {
            startForegroundCompat(buildNotification("Starting renderer test"))
            SessionUiStateStore.update(
                SessionUiState(SessionPhase.CONNECTING_WIFI, "Choose the Tripper Wi-Fi network in Android's prompt."),
            )
            updateNotification("Waiting for Tripper Wi-Fi")
            requestTripperNetwork(profile)
        } catch (error: Exception) {
            stopSession(error.message ?: "Could not start the renderer test service.", failed = true)
        }
    }

    private fun requestTripperNetwork(profile: ValidatedTripperProfile) {
        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(profile.ssid)
        if (profile.wifiPassphrase.isNotEmpty()) specifierBuilder.setWpa2Passphrase(profile.wifiPassphrase)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (stopStarted.get() || !connectionStarted.compareAndSet(false, true)) return
                activeNetwork = network
                mainHandler.post {
                    if (stopStarted.get()) return@post
                    SessionUiStateStore.update(
                        SessionUiState(SessionPhase.AUTHENTICATING, "Tripper Wi-Fi connected. Authenticating with the TFT."),
                    )
                    updateNotification("Authenticating with Tripper")
                }
                streamWorker.execute { connectAndStart(network, profile) }
            }

            override fun onUnavailable() {
                mainHandler.post {
                    stopSession("Android could not connect to the selected Tripper Wi-Fi.", failed = true)
                }
            }

            override fun onLost(network: Network) {
                if (activeNetwork == network) {
                    mainHandler.post { stopSession("Tripper Wi-Fi disconnected.", failed = true) }
                }
            }
        }
        networkCallback = callback
        connectivityManager.requestNetwork(request, callback, NETWORK_REQUEST_TIMEOUT_MILLIS)
    }

    private fun connectAndStart(network: Network, profile: ValidatedTripperProfile) {
        var localTransport: TripperTransport? = null
        var localSession: TripperSession? = null
        var localEncoder: H264Encoder? = null
        var localRenderer: TripperMapRenderer? = null
        var adopted = false
        try {
            if (stopStarted.get()) return
            val transport = TripperTransport.open(
                network,
                profile.bikeAddress,
                profile.controlPort,
                profile.responsePort,
                profile.videoPort,
            )
            localTransport = transport
            val registered = synchronized(resourcesLock) {
                if (stopStarted.get()) false else {
                    connectingTransport = transport
                    true
                }
            }
            if (!registered) return

            val session = TripperSession(profile, transport)
            localSession = session
            session.authenticate()
            if (stopStarted.get()) return
            session.beginProjection()
            if (stopStarted.get()) return

            acquirePartialWakeLock()
            val streamingSession = session
            val encoder = H264Encoder(
                TripperVideoSpec(profile.width, profile.height, profile.frameRate, profile.bitrate),
                object : H264Encoder.Listener {
                    override fun onEncodedFrame(frame: EncodedFrame) {
                        try {
                            streamingSession.sendEncodedFrame(frame)
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
                },
            )
            localEncoder = encoder
            val encoderSurface = encoder.start()
            if (stopStarted.get()) return

            val renderer = TripperMapRenderer(applicationContext, profile.width, profile.height)
            localRenderer = renderer
            val rendererRegistered = synchronized(resourcesLock) {
                if (stopStarted.get()) false else {
                    connectingRenderer = renderer
                    true
                }
            }
            if (!rendererRegistered) return
            runOnMainBlocking {
                if (!stopStarted.get()) renderer.start(encoderSurface)
            }
            if (stopStarted.get()) return

            val accepted = synchronized(resourcesLock) {
                if (stopStarted.get()) false else {
                    activeTransport = transport
                    activeSession = session
                    activeEncoder = encoder
                    activeRenderer = renderer
                    connectingTransport = null
                    connectingRenderer = null
                    true
                }
            }
            if (!accepted) return
            adopted = true
            localTransport = null
            localSession = null
            localEncoder = null
            localRenderer = null

            mainHandler.post {
                if (stopStarted.get()) return@post
                SessionUiStateStore.update(
                    SessionUiState(SessionPhase.STREAMING, "App-owned renderer test is streaming to the Tripper."),
                )
                updateNotification("Renderer test is streaming to the Tripper")
                runCatching {
                    startControlReceiver()
                    timer.scheduleAtFixedRate(
                        { runCatching { activeSession?.keepAlive() } },
                        KEEPALIVE_INITIAL_DELAY_SECONDS,
                        KEEPALIVE_PERIOD_SECONDS,
                        TimeUnit.SECONDS,
                    )
                }.onFailure { error ->
                    stopSession(error.message ?: "Could not start Tripper control monitoring.", failed = true)
                }
            }
        } catch (error: Exception) {
            mainHandler.post {
                stopSession(error.message ?: "Could not connect to the Tripper display.", failed = true)
            }
        } finally {
            if (!adopted) {
                localRenderer?.let { renderer -> runCatching { runOnMainBlocking { renderer.close() } } }
                runCatching { localSession?.stopProjection() }
                runCatching { localEncoder?.close() }
                runCatching { localTransport?.close() }
                synchronized(resourcesLock) {
                    if (connectingTransport === localTransport) connectingTransport = null
                    if (connectingRenderer === localRenderer) connectingRenderer = null
                }
            }
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

    private fun acquirePartialWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:MirrorDashRide")
        lock.setReferenceCounted(false)
        lock.acquire(SESSION_WAKE_LOCK_TIMEOUT_MILLIS)
        synchronized(resourcesLock) {
            if (stopStarted.get()) {
                runCatching { lock.release() }
            } else {
                wakeLock = lock
            }
        }
    }

    @MainThread
    private fun stopSession(message: String, failed: Boolean) {
        if (!stopStarted.compareAndSet(false, true)) return
        SessionUiStateStore.update(
            SessionUiState(
                phase = if (failed) SessionPhase.ERROR else SessionPhase.IDLE,
                message = message,
            ),
        )
        runCatching { networkCallback?.let(connectivityManager::unregisterNetworkCallback) }
        networkCallback = null
        timer.shutdownNow()

        val owned = synchronized(resourcesLock) {
            OwnedResources(
                transport = activeTransport,
                connectingTransport = connectingTransport,
                session = activeSession,
                encoder = activeEncoder,
                renderer = activeRenderer,
                connectingRenderer = connectingRenderer,
                wakeLock = wakeLock,
            ).also {
                activeTransport = null
                connectingTransport = null
                activeSession = null
                activeEncoder = null
                activeRenderer = null
                connectingRenderer = null
                wakeLock = null
            }
        }
        activeNetwork = null
        // Presentation and VirtualDisplay lifecycle belongs on the main thread.
        runCatching { owned.renderer?.close() }
        if (owned.connectingRenderer !== owned.renderer) runCatching { owned.connectingRenderer?.close() }

        streamWorker.execute {
            // Keep control transport open until the TFT receives projection-stop packets.
            runCatching { owned.session?.stopProjection() }
            runCatching { owned.encoder?.close() }
            runCatching { owned.transport?.close() }
            if (owned.connectingTransport !== owned.transport) runCatching { owned.connectingTransport?.close() }
            runCatching { owned.wakeLock?.release() }
            mainHandler.post {
                foregroundStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                streamWorker.shutdown()
            }
        }
    }

    private fun runOnMainBlocking(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val task = FutureTask<Unit> { block() }
        check(mainHandler.post(task)) { "Android main thread is not accepting renderer work" }
        try {
            task.get(MAIN_THREAD_RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
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
            .setContentTitle("MirrorDash renderer test")
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action("Stop", ACTION_STOP, REQUEST_STOP))
            .build()
    }

    private fun action(title: String, action: String, requestCode: Int): Notification.Action {
        val pendingIntent = PendingIntent.getService(
            this,
            requestCode,
            createActionIntent(this, action),
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
            "MirrorDash ride test",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows status and Stop for the app-owned Tripper renderer test." }
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
        private const val ACTION_RENDERER_SPIKE = "com.dharmil.mirrordash.action.RENDERER_SPIKE"
        private const val ACTION_STOP = "com.dharmil.mirrordash.action.RIDE_STOP"
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
        private const val NOTIFICATION_CHANNEL_ID = "mirrordash_renderer_test"
        private const val NOTIFICATION_ID = 41
        private const val NETWORK_REQUEST_TIMEOUT_MILLIS = 30_000
        private const val KEEPALIVE_INITIAL_DELAY_SECONDS = 1L
        private const val KEEPALIVE_PERIOD_SECONDS = 3L
        private const val SESSION_WAKE_LOCK_TIMEOUT_MILLIS = 8L * 60L * 60L * 1_000L
        private const val MAIN_THREAD_RENDER_TIMEOUT_SECONDS = 10L
        private const val REQUEST_OPEN_APP = 400
        private const val REQUEST_STOP = 401

        fun createRendererSpikeIntent(context: Context, profile: TripperProfile): Intent =
            Intent(context, RideSessionService::class.java).apply {
                action = ACTION_RENDERER_SPIKE
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

        fun createActionIntent(context: Context, action: String = ACTION_STOP): Intent =
            Intent(context, RideSessionService::class.java).setAction(action)
    }
}
