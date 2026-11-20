package com.pubg.fpsmeterpro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import java.net.InetSocketAddress
import java.net.Socket
import android.widget.TextView
import java.nio.ByteBuffer
import kotlin.math.abs

class MeterService : Service() {
    companion object {
        const val ACTION_START = "com.pubg.fpsmeterpro.START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "fps_meter_pro"
        private const val NOTIFICATION_ID = 741
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var wm: WindowManager? = null
    private var panel: TextView? = null
    private var refreshHz = 60f

    private var frameCount = 0
    private var changedCount = 0
    private var lastSignature: IntArray? = null
    private var intervalStart = 0L
    private var captureRate = 0
    private var changeRate = 0
    private var totalCapture = 0L
    private var samples = 0
    private var minCapture = Int.MAX_VALUE
    private var maxCapture = 0
    private var lastPanelUpdate = 0L
    private var pingThread: HandlerThread? = null
    private var pingHandler: Handler? = null
    @Volatile private var networkPingMs: Long = -1L
    @Volatile private var pingStatus: String = "جارٍ القياس"
    private var pingRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && projection == null) {
            createChannel()
            val notification = makeNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTIFICATION_ID, notification)
            beginProjection(intent)
        }
        return START_NOT_STICKY
    }

    private fun beginProjection(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: run { stopSelf(); return }

        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        refreshHz = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f

        thread = HandlerThread("ProFrameReader").also { it.start() }
        worker = Handler(thread!!.looper)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, worker)

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = imageReader
        intervalStart = SystemClock.elapsedRealtime()
        imageReader.setOnImageAvailableListener({ source ->
            var image: Image? = null
            try {
                image = source.acquireLatestImage()
                if (image != null) processFrame(image!!)
            } catch (_: Exception) {
            } finally {
                image?.close()
            }
        }, worker)

        virtualDisplay = projection?.createVirtualDisplay(
            "PUBG-FPS-Meter-Pro", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, worker
        )
        addOverlay()
    }

    private fun processFrame(image: Image) {
        frameCount++
        val signature = sampleImage(image)
        val previous = lastSignature
        if (previous != null) {
            var changed = 0
            for (i in signature.indices) if (abs(signature[i] - previous[i]) >= 14) changed++
            if (changed >= 4) changedCount++
        }
        lastSignature = signature

        val now = SystemClock.elapsedRealtime()
        if (now - intervalStart >= 1000L) {
            captureRate = frameCount
            changeRate = changedCount
            minCapture = minOf(minCapture, captureRate)
            maxCapture = maxOf(maxCapture, captureRate)
            totalCapture += captureRate
            samples++
            frameCount = 0
            changedCount = 0
            intervalStart = now
            refreshPanel()
        }
    }

    private fun sampleImage(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val cols = 12
        val rows = 7
        val result = IntArray(cols * rows)
        var index = 0
        for (gy in 0 until rows) {
            val y = ((gy + 1) * height / (rows + 1)).coerceIn(0, height - 1)
            for (gx in 0 until cols) {
                val x = ((gx + 1) * width / (cols + 1)).coerceIn(0, width - 1)
                val offset = y * rowStride + x * pixelStride
                result[index++] = if (offset + 2 < buffer.limit()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    (r * 3 + g * 6 + b) / 10
                } else 0
            }
        }
        return result
    }

    private fun addOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "PUBG FPS Meter Pro\nجارٍ القياس..."
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xE616202B.toInt())
            setPadding(16, 12, 16, 12)
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                    val params = v.layoutParams as WindowManager.LayoutParams
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = params.x; startY = params.y
                            touchX = event.rawX; touchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = startX + (event.rawX - touchX).toInt()
                            params.y = startY + (event.rawY - touchY).toInt()
                            try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
                            return true
                        }
                    }
                    return true
                }
            })
        }
        panel = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 100 }
        try { wm?.addView(view, params) } catch (_: Exception) {}
        startPingMonitor()
        refreshPanel()
    }

    // Measures TCP connection latency to Cloudflare's public edge.
    // This is a network-latency indicator, NOT PUBG server ping.
    private fun startPingMonitor() {
        pingThread = HandlerThread("NetworkPingMonitor").also { it.start() }
        pingHandler = Handler(pingThread!!.looper)
        pingRunnable = object : Runnable {
            override fun run() {
                var measured = -1L
                try {
                    Socket().use { socket ->
                        val start = SystemClock.elapsedRealtime()
                        socket.connect(InetSocketAddress("1.1.1.1", 443), 1800)
                        measured = SystemClock.elapsedRealtime() - start
                    }
                } catch (_: Exception) {
                    measured = -1L
                }
                networkPingMs = measured
                pingStatus = if (measured >= 0) "$measured ms" else "غير متاح"
                refreshPanel()
                pingHandler?.postDelayed(this, 3000L)
            }
        }
        pingHandler?.post(pingRunnable!!)
    }

    private fun refreshPanel() {
        val average = if (samples > 0) totalCapture / samples else 0L
        val min = if (minCapture == Int.MAX_VALUE) 0 else minCapture
        val label = "التقاط الشاشة: $captureRate /ث\nتغيّر الصورة: $changeRate /ث\nمتوسط الالتقاط: $average\nأقل/أعلى: $min/$maxCapture\nتحديث الشاشة: ${refreshHz.toInt()}Hz"
        val now = SystemClock.elapsedRealtime()
        if (now - lastPanelUpdate < 700L) return
        lastPanelUpdate = now
        panel?.post { panel?.text = label }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "PUBG FPS Meter Pro", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun makeNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL).setContentTitle("PUBG FPS Meter Pro يعمل")
                .setContentText("تحليل الشاشة محليًا").setSmallIcon(android.R.drawable.ic_menu_info_details).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("PUBG FPS Meter Pro يعمل")
                .setContentText("تحليل الشاشة محليًا").setSmallIcon(android.R.drawable.ic_menu_info_details).build()
        }
    }

    override fun onDestroy() {
        try { if (panel != null) wm?.removeView(panel) } catch (_: Exception) {}
        panel = null
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        pingRunnable?.let { pingHandler?.removeCallbacks(it) }
        pingThread?.quitSafely()
        projection = null
        super.onDestroy()
    }
}
