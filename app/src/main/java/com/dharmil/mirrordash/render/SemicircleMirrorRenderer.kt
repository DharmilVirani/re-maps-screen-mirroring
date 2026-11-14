package com.dharmil.mirrordash.render

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.MainThread
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt

/** Composites the selected screen inside the semicircle-shaped TFT video area. */
class SemicircleMirrorRenderer(
    context: Context,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var closed = false
    private var projection: MediaProjection? = null
    private var outputDisplay: VirtualDisplay? = null
    private var captureDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var textureView: TextureView? = null
    private var sourceTexture: SurfaceTexture? = null
    private var sourceSurface: Surface? = null
    private var captureDensityDpi = DisplayMetrics.DENSITY_DEFAULT
    private var contentWidthPx = 1
    private var contentHeightPx = 1
    private var captureBufferWidthPx = 2
    private var captureBufferHeightPx = 2
    private var readyLatch: CountDownLatch? = null
    private var startError: AtomicReference<Throwable?>? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            mainHandler.post { resizeCapturedContent(width, height) }
        }
    }

    /** Must be called off the main thread because TextureView reports its Surface asynchronously. */
    fun start(
        mediaProjection: MediaProjection,
        encoderSurface: Surface,
        initialSourceWidthPx: Int,
        initialSourceHeightPx: Int,
        densityDpi: Int,
    ) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "The mirrored TFT renderer must be started off the main thread."
        }
        require(outputWidthPx > 0 && outputHeightPx > 0)
        require(encoderSurface.isValid) { "The H.264 encoder surface is not valid." }
        require(initialSourceWidthPx > 0 && initialSourceHeightPx > 0)

        projection = mediaProjection
        contentWidthPx = initialSourceWidthPx
        contentHeightPx = initialSourceHeightPx
        updateCaptureBufferSize(initialSourceWidthPx, initialSourceHeightPx)
        captureDensityDpi = densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        readyLatch = ready
        startError = failure
        mediaProjection.registerCallback(projectionCallback, mainHandler)

        runOnMainAndWait {
            if (closed) throw IOException("The mirrored TFT renderer was stopped before it started.")
            createPresentation(encoderSurface, ready, failure)
        }

        if (!ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            close()
            throw IOException("Timed out waiting for the TFT mirror surface.")
        }
        failure.get()?.let { error ->
            close()
            throw IOException("Could not start the semicircle screen mirror.", error)
        }
    }

    @MainThread
    private fun createPresentation(
        encoderSurface: Surface,
        ready: CountDownLatch,
        failure: AtomicReference<Throwable?>,
    ) {
        checkMainThread()
        try {
            val displayManager = appContext.getSystemService(DisplayManager::class.java)
            val display = checkNotNull(
                displayManager.createVirtualDisplay(
                    OUTPUT_DISPLAY_NAME,
                    outputWidthPx,
                    outputHeightPx,
                    captureDensityDpi,
                    encoderSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                ),
            ) { "Android could not create the TFT layout display." }
            outputDisplay = display

            val mirrorTexture = TextureView(appContext).apply {
                isOpaque = false
                clipToOutline = true
                outlineProvider = SemicircleOutlineProvider()
                surfaceTextureListener = MirrorSurfaceListener(ready, failure)
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    updateTextureTransform(view as TextureView)
                }
            }
            textureView = mirrorTexture

            val root = SemicircleFrame(appContext).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    mirrorTexture,
                    FrameLayout.LayoutParams(outputWidthPx, outputHeightPx),
                )
            }
            val window = Presentation(appContext, display.display).apply {
                setContentView(root)
                window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }
            presentation = window
            window.show()
        } catch (error: Throwable) {
            failure.set(error)
            ready.countDown()
        }
    }

    @MainThread
    private fun handleSurfaceAvailable(surfaceTexture: SurfaceTexture, ready: CountDownLatch) {
        checkMainThread()
        if (closed) {
            ready.countDown()
            return
        }
        try {
            surfaceTexture.setDefaultBufferSize(captureBufferWidthPx, captureBufferHeightPx)
            sourceTexture = surfaceTexture
            textureView?.let(::updateTextureTransform)
            val surface = Surface(surfaceTexture)
            sourceSurface = surface
            captureDisplay = checkNotNull(
                checkNotNull(projection).createVirtualDisplay(
                    CAPTURE_DISPLAY_NAME,
                    captureBufferWidthPx,
                    captureBufferHeightPx,
                    captureDensityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    mainHandler,
                ),
            ) { "Android could not capture the selected screen." }
        } catch (error: Throwable) {
            startError?.set(error)
        } finally {
            ready.countDown()
        }
    }

    @MainThread
    private fun resizeCapturedContent(width: Int, height: Int) {
        checkMainThread()
        if (closed || width <= 0 || height <= 0) return
        contentWidthPx = width
        contentHeightPx = height
        updateCaptureBufferSize(width, height)
        runCatching { sourceTexture?.setDefaultBufferSize(captureBufferWidthPx, captureBufferHeightPx) }
        runCatching { captureDisplay?.resize(captureBufferWidthPx, captureBufferHeightPx, captureDensityDpi) }
        textureView?.let(::updateTextureTransform)
    }

    private fun updateCaptureBufferSize(contentWidth: Int, contentHeight: Int) {
        val scale = min(outputWidthPx.toFloat() / contentWidth, outputHeightPx.toFloat() / contentHeight)
        captureBufferWidthPx = evenDimension((contentWidth * scale).roundToInt(), outputWidthPx)
        captureBufferHeightPx = evenDimension((contentHeight * scale).roundToInt(), outputHeightPx)
    }

    private fun evenDimension(value: Int, maximum: Int): Int =
        (value.coerceIn(2, maximum) / 2) * 2

    @MainThread
    private fun updateTextureTransform(view: TextureView) {
        checkMainThread()
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || captureBufferWidthPx <= 0 || captureBufferHeightPx <= 0) return

        val scale = min(viewWidth / captureBufferWidthPx, viewHeight / captureBufferHeightPx)
        val contentWidth = captureBufferWidthPx * scale
        val contentHeight = captureBufferHeightPx * scale
        val transform = Matrix().apply {
            setScale(scale, scale)
            postTranslate((viewWidth - contentWidth) / 2f, (viewHeight - contentHeight) / 2f)
        }
        view.setTransform(transform)
    }

    override fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            closeOnMain()
            return
        }
        runCatching { runOnMainAndWait(::closeOnMain) }
    }

    @MainThread
    private fun closeOnMain() {
        checkMainThread()
        if (closed) return
        closed = true
        projection?.let { activeProjection ->
            runCatching { activeProjection.unregisterCallback(projectionCallback) }
        }
        projection = null
        runCatching { captureDisplay?.release() }
        captureDisplay = null
        runCatching { sourceSurface?.release() }
        sourceSurface = null
        sourceTexture = null
        textureView?.surfaceTextureListener = null
        textureView = null
        presentation?.let { window ->
            if (window.isShowing) runCatching { window.dismiss() }
        }
        presentation = null
        runCatching { outputDisplay?.release() }
        outputDisplay = null
    }

    private fun runOnMainAndWait(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        mainHandler.post {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Timed out setting up the TFT mirror layout.")
        }
        failure.get()?.let { throw IOException("Could not set up the TFT mirror layout.", it) }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Presentation and TextureView operations must run on the main thread."
        }
    }

    private inner class MirrorSurfaceListener(
        private val ready: CountDownLatch,
        private val failure: AtomicReference<Throwable?>,
    ) : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            try {
                handleSurfaceAvailable(surface, ready)
            } catch (error: Throwable) {
                failure.set(error)
                ready.countDown()
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            if (!closed) {
                runCatching { captureDisplay?.release() }
                captureDisplay = null
                runCatching { sourceSurface?.release() }
                sourceSurface = null
                sourceTexture = null
            }
            return true
        }
    }

    private class SemicircleOutlineProvider : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val width = view.width.toFloat()
            val height = view.height.toFloat()
            if (width <= 0f || height <= 0f) {
                outline.setEmpty()
                return
            }
            outline.setPath(createSemicirclePath(width, height))
        }
    }

    private class SemicircleFrame(context: Context) : FrameLayout(context) {
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val path = createSemicirclePath(width.toFloat(), height.toFloat()).apply { close() }
            canvas.drawPath(path, arcPaint)
        }
    }

    private companion object {
        const val OUTPUT_DISPLAY_NAME = "MirrorDashSemicircleOutput"
        const val CAPTURE_DISPLAY_NAME = "MirrorDashSelectedScreenCapture"
        const val START_TIMEOUT_SECONDS = 12L

        fun createSemicirclePath(width: Float, height: Float): Path {
            val radius = height
            val centerX = width / 2f
            return Path().apply {
                moveTo(centerX - radius, radius)
                arcTo(RectF(centerX - radius, 0f, centerX + radius, radius * 2f), 180f, 180f, false)
                close()
            }
        }
    }
}
