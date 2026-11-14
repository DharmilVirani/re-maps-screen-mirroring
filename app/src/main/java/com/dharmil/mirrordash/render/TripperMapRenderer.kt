package com.dharmil.mirrordash.render

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import androidx.annotation.MainThread
import kotlin.math.min

/**
 * Owns an app-only display and its Presentation. The service must pass the
 * encoder input Surface and call every lifecycle method on the main thread.
 */
class TripperMapRenderer(
    context: Context,
    private val widthPx: Int,
    private val heightPx: Int,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var testView: RendererTestView? = null
    private var renderState = TftRenderState()

    @MainThread
    fun start(encoderSurface: Surface) {
        checkMainThread()
        check(virtualDisplay == null) { "Tripper renderer is already started" }
        require(widthPx > 0 && heightPx > 0)
        require(encoderSurface.isValid) { "The H.264 encoder surface is not valid" }

        val displayManager = appContext.getSystemService(DisplayManager::class.java)
        val densityDpi = appContext.resources.displayMetrics.densityDpi.coerceAtLeast(1)
        val display = checkNotNull(
            displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                widthPx,
                heightPx,
                densityDpi,
                encoderSurface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ),
        ) { "Android could not create the app-owned TFT render display." }

        try {
            val view = RendererTestView(appContext, widthPx, heightPx).apply {
                state = renderState
                startTicker()
            }
            val window = Presentation(appContext, display.display).apply {
                setContentView(view)
                window?.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
            }
            window.show()
            virtualDisplay = display
            presentation = window
            testView = view
        } catch (error: Throwable) {
            display.release()
            throw error
        }
    }

    @MainThread
    fun update(state: TftRenderState) {
        checkMainThread()
        renderState = state
        testView?.state = state
        testView?.invalidate()
    }

    @MainThread
    fun zoomBy(delta: Double) {
        checkMainThread()
        require(delta.isFinite())
        renderState = renderState.copy(zoom = (renderState.zoom + delta).coerceIn(3.0, 20.0))
        update(renderState)
    }

    @MainThread
    fun recenter() {
        checkMainThread()
        update(renderState.copy(status = "Renderer test · centered"))
    }

    @MainThread
    override fun close() {
        checkMainThread()
        testView?.stopTicker()
        testView = null
        presentation?.let { window ->
            if (window.isShowing) window.dismiss()
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TripperMapRenderer lifecycle must run on the main thread"
        }
    }

    private class RendererTestView(
        context: Context,
        private val outputWidth: Int,
        private val outputHeight: Int,
    ) : View(context) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E7E9.toInt() }
        private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB8C1C9.toInt()
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        private val minorRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCED5DA.toInt()
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF39800.toInt()
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB9C4CB.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        private val lowerPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE }
        private val etaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF16833C.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E4850.toInt() }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3478F6.toInt() }
        private val circlePath = Path()
        private val routePath = Path()
        private var startedAt = System.currentTimeMillis()
        var state: TftRenderState = TftRenderState()

        private val ticker = object : Runnable {
            override fun run() {
                invalidate()
                mainHandler.postDelayed(this, FRAME_TICK_MILLIS)
            }
        }

        fun startTicker() {
            mainHandler.removeCallbacks(ticker)
            mainHandler.post(ticker)
        }

        fun stopTicker() {
            mainHandler.removeCallbacks(ticker)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sx = width.toFloat() / outputWidth
            val sy = height.toFloat() / outputHeight
            canvas.save()
            canvas.scale(sx, sy)
            canvas.drawColor(0xFF0B0D10.toInt())
            drawCircularMap(canvas)
            drawLowerPanel(canvas)
            drawStatus(canvas)
            canvas.restore()
        }

        private fun drawCircularMap(canvas: Canvas) {
            val w = outputWidth.toFloat()
            val h = outputHeight.toFloat()
            val centerX = w / 2f
            val centerY = h * 0.40f
            val radius = min(w * 0.47f, h * 0.50f)
            circlePath.reset()
            circlePath.addCircle(centerX, centerY, radius, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(circlePath)
            canvas.drawColor(0xFFF0EFF1.toInt())
            drawBlocks(canvas, w, h)
            drawRoads(canvas, w, h)
            drawRoute(canvas, w, h)
            drawRider(canvas, centerX, centerY + h * 0.12f)
            canvas.restore()

            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF8F8F8.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawCircle(centerX, centerY, radius - 1.5f, border)
        }

        private fun drawBlocks(canvas: Canvas, w: Float, h: Float) {
            val block = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6E5E8.toInt() }
            val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8EAD8.toInt() }
            canvas.drawOval(RectF(w * 0.06f, h * 0.37f, w * 0.28f, h * 0.69f), green)
            for (row in 0..4) {
                for (column in 0..6) {
                    val left = w * 0.08f + column * w * 0.145f
                    val top = h * 0.04f + row * h * 0.13f
                    if ((row + column) % 4 != 0) {
                        canvas.drawRoundRect(
                            RectF(left, top, left + w * 0.095f, top + h * 0.06f),
                            3f,
                            3f,
                            block,
                        )
                    }
                }
            }
        }

        private fun drawRoads(canvas: Canvas, w: Float, h: Float) {
            for (index in 0..4) {
                val y = h * (0.12f + index * 0.14f)
                canvas.drawLine(w * 0.02f, y, w * 0.98f, y + h * 0.012f, minorRoadPaint)
            }
            for (index in 0..5) {
                val x = w * (0.08f + index * 0.17f)
                canvas.drawLine(x, h * 0.01f, x + w * 0.015f, h * 0.72f, roadPaint)
            }
            val crossRoad = Paint(roadPaint).apply { color = 0xFFD8B978.toInt(); strokeWidth = 5f }
            canvas.drawLine(w * 0.01f, h * 0.38f, w * 0.99f, h * 0.34f, crossRoad)
        }

        private fun drawRoute(canvas: Canvas, w: Float, h: Float) {
            routePath.reset()
            routePath.moveTo(-w * 0.10f, h * 0.58f)
            routePath.lineTo(w * 0.25f, h * 0.51f)
            routePath.lineTo(w * 0.29f, h * 0.40f)
            routePath.lineTo(w * 0.51f, h * 0.40f)
            routePath.lineTo(w * 0.51f, h * 0.70f)
            routePath.lineTo(w * 0.53f, h * 0.88f)
            canvas.drawPath(routePath, routePaint)
            val whiteOutline = Paint(routePaint).apply {
                color = AndroidColor.WHITE
                strokeWidth = 13f
            }
            canvas.drawPath(routePath, whiteOutline)
            canvas.drawPath(routePath, routePaint)
        }

        private fun drawRider(canvas: Canvas, x: Float, y: Float) {
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
            canvas.drawCircle(x, y + 2f, 20f, shadow)
            canvas.drawCircle(x, y, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
            val arrow = Path().apply {
                moveTo(x, y - 12f)
                lineTo(x - 10f, y + 9f)
                lineTo(x, y + 5f)
                lineTo(x + 10f, y + 9f)
                close()
            }
            canvas.drawPath(arrow, markerPaint)
        }

        private fun drawLowerPanel(canvas: Canvas) {
            val h = outputHeight.toFloat()
            val top = h * 0.74f
            canvas.drawRect(0f, top, outputWidth.toFloat(), h, lowerPanelPaint)
            val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFB7B9BB.toInt()
                strokeWidth = 1.5f
            }
            canvas.drawLine(0f, top, outputWidth.toFloat(), top, divider)
            etaPaint.textSize = h * 0.22f
            etaPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("RENDER TEST", outputWidth / 2f, top + h * 0.145f, etaPaint)
            nowPaint.textSize = h * 0.095f
            nowPaint.textAlign = Paint.Align.CENTER
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            canvas.drawText("${outputWidth}×${outputHeight}  ·  ${seconds}s", outputWidth / 2f, top + h * 0.245f, nowPaint)
        }

        private fun drawStatus(canvas: Canvas) {
            val h = outputHeight.toFloat()
            textPaint.textSize = h * 0.085f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("MIRRORDASH · APP-OWNED DISPLAY", outputWidth / 2f, h * 0.07f, textPaint)
            subTextPaint.textSize = h * 0.052f
            subTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(state.status.take(48), outputWidth / 2f, h * 0.12f, subTextPaint)
            drawAttribution(canvas)
        }

        private fun drawAttribution(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF4A5359.toInt()
                textSize = outputHeight * 0.035f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("Test pattern · no map data", outputWidth - 8f, outputHeight * 0.72f, paint)
        }

        companion object {
            private const val FRAME_TICK_MILLIS = 250L
        }
    }

    companion object {
        private const val DISPLAY_NAME = "MirrorDashOwnedNavigationDisplay"
    }
}
