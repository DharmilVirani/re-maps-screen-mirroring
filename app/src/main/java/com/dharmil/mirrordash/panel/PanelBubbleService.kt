package com.dharmil.mirrordash.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.util.TypedValue
import com.dharmil.mirrordash.MainActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/** User-started floating control for turning the phone display off over another app. */
class PanelBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubbleView: TextView? = null
    private var foregroundStarted = false
    private var commandInFlight = false
    private var notificationText = "Display-off button is ready."
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY &&
                displayManager.getDisplay(displayId)?.state == Display.STATE_ON &&
                PanelPowerRecoveryStore.isRestorePending(this@PanelBubbleService)
            ) {
                PanelPowerRecoveryStore.setRestorePending(this@PanelBubbleService, false)
                notificationText = "Phone display is on; the floating button is ready."
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                setEnabled(true)
                if (!startForegroundIfNeeded()) return START_NOT_STICKY
                showBubble()
                return START_STICKY
            }

            ACTION_HIDE -> {
                if (commandInFlight) {
                    notificationText = "Wait for the display command to finish, then hide the bubble."
                    updateNotification()
                    return START_NOT_STICKY
                }
                setEnabled(false)
                removeBubble()
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf(startId)
                return START_NOT_STICKY
            }

            null -> {
                if (!isEnabled()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startForegroundIfNeeded()) return START_NOT_STICKY
                showBubble()
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeBubble()
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    private fun startForegroundIfNeeded(): Boolean {
        if (foregroundStarted) return true
        return runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(notificationText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            foregroundStarted = true
            true
        }.getOrElse {
            setEnabled(false)
            stopSelf()
            false
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) {
            notificationText = "Allow MirrorDash to display over other apps, then enable the bubble again."
            updateNotification()
            setEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return
        }

        val size = dp(58)
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size - dp(18)).coerceAtLeast(0)
            y = ((metrics.heightPixels - size) / 2).coerceAtLeast(0)
        }

        val bubble = TextView(this).apply {
            text = "⏻"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFFF2F5F6.toInt())
            contentDescription = "Turn phone display off. Drag to move this button."
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE17262C.toInt())
                setStroke(dp(2), 0xFF80CBC4.toInt())
            }
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { requestPanelOff() }
        }
        attachDragBehavior(bubble, params, size)

        runCatching { windowManager.addView(bubble, params) }
            .onSuccess {
                bubbleView = bubble
                notificationText = "Display-off button is ready. Tap it over Royal Enfield navigation."
                updateNotification()
            }
            .onFailure {
                notificationText = "MirrorDash could not show the floating button. Check Display over other apps."
                updateNotification()
                setEnabled(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf()
            }
    }

    private fun attachDragBehavior(
        bubble: TextView,
        params: WindowManager.LayoutParams,
        size: Int,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false

        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downX).roundToInt()
                    val deltaY = (event.rawY - downY).roundToInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) dragging = true
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        params.x = (startX + deltaX).coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
                        params.y = (startY + deltaY).coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun requestPanelOff() {
        if (commandInFlight) return
        if (!ShizukuPanelController.hasPermission()) {
            notificationText = "Start Shizuku and grant MirrorDash permission before using this button."
            updateNotification()
            return
        }
        if (!PanelPowerRecoveryStore.setRestorePending(this, true)) {
            notificationText = "Could not save panel recovery state; the display was left on."
            updateNotification()
            return
        }

        commandInFlight = true
        notificationText = "Turning the phone display off…"
        updateNotification()
        ShizukuPanelController.setPanelPower(this, on = false) { _, success, message ->
            commandInFlight = false
            notificationText = if (success) {
                "Phone display off; use the phone power key to wake it."
            } else {
                "Display-off failed: $message"
            }
            updateNotification()
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hideBubble = PendingIntent.getService(
            this,
            REQUEST_HIDE_BUBBLE,
            Intent(this, PanelBubbleService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MirrorDash display control")
            .setContentText(message)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Hide bubble",
                    hideBubble,
                ).build(),
            )
            .build()
    }

    private fun updateNotification() {
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(notificationText))
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Floating display control",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when the MirrorDash display-off bubble is active."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun removeBubble() {
        bubbleView?.let { view -> runCatching { windowManager.removeView(view) } }
        bubbleView = null
    }

    private fun setEnabled(enabled: Boolean) {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private fun isEnabled(): Boolean =
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).roundToInt()

    companion object {
        const val ACTION_SHOW = "com.dharmil.mirrordash.panel.SHOW_BUBBLE"
        private const val ACTION_HIDE = "com.dharmil.mirrordash.panel.HIDE_BUBBLE"
        private const val PREFERENCES_NAME = "panel_bubble"
        private const val KEY_ENABLED = "enabled"
        private const val NOTIFICATION_CHANNEL_ID = "panel_bubble"
        private const val NOTIFICATION_ID = 4203
        private const val REQUEST_OPEN_APP = 4204
        private const val REQUEST_HIDE_BUBBLE = 4205

        fun createShowIntent(context: android.content.Context): Intent =
            Intent(context, PanelBubbleService::class.java).setAction(ACTION_SHOW)
    }
}
