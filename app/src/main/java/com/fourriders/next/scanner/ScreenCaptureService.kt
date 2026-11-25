package com.fourriders.next.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * User-consented live screen OCR. One MediaProjection session is kept alive
 * until the user stops it or Android revokes the permission.
 * Frames are processed locally and never uploaded.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_OCR_RESULT = "com.fourriders.next.SCREEN_OCR_RESULT"
        const val ACTION_STOP_CAPTURE = "com.fourriders.next.STOP_LIVE_OCR"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_OCR_TEXT = "capture_ocr_text"
        private const val CHANNEL_ID = "screen_scanner"
        private const val NOTIFICATION_ID = 4102
        private const val OCR_INTERVAL_MS = 1400L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var ocrInFlight = false
    private var lastOcrAt = 0L
    private var stopped = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAPTURE) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (projection != null) return START_STICKY

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = getResultData(intent)
        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            broadcastStatus("OCR FAILED • SCREEN PERMISSION MISSING")
            stopCapture()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                val now = System.currentTimeMillis()
                if (stopped || ocrInFlight || now - lastOcrAt < OCR_INTERVAL_MS) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val frameImage = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                ocrInFlight = true
                lastOcrAt = now
                var frame: Bitmap? = null
                try {
                    val plane = frameImage.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val paddedWidth = rowStride / pixelStride
                    val fullBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    fullBitmap.copyPixelsFromBuffer(buffer)
                    frame = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                    fullBitmap.recycle()
                } catch (_: Exception) {
                    ocrInFlight = false
                    broadcastStatus("OCR FAILED • FRAME READ ERROR")
                } finally {
                    frameImage.close()
                }

                val bitmap = frame
                if (bitmap == null) return@setOnImageAvailableListener
                broadcastStatus("LIVE OCR • ANALYSING SCREEN")
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        if (result.text.isNotBlank()) {
                            broadcastResult(result.text)
                            broadcastStatus("LIVE OCR • TEXT FOUND")
                        } else {
                            broadcastStatus("LIVE OCR • NO TEXT IN FRAME")
                        }
                    }
                    .addOnFailureListener {
                        broadcastStatus("LIVE OCR FAILED • TRY AGAIN")
                    }
                    .addOnCompleteListener {
                        recognizer.close()
                        bitmap.recycle()
                        ocrInFlight = false
                    }
            }, handler)

            projection?.registerCallback(projectionCallback, handler)
            virtualDisplay = projection?.createVirtualDisplay(
                "4riders-next-live-ocr", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
            broadcastStatus("LIVE OCR ACTIVE • ON-DEVICE")
        } catch (_: Exception) {
            broadcastStatus("OCR FAILED • COULD NOT START CAPTURE")
            stopCapture()
        }
        return START_STICKY
    }

    private fun getResultData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun broadcastResult(text: String) {
        sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName).putExtra(EXTRA_OCR_TEXT, text))
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(
            Intent(LiveOfferAccessibilityService.ACTION_SCANNER_STATUS)
                .setPackage(packageName)
                .putExtra(LiveOfferAccessibilityService.EXTRA_SCANNER_STATUS, status)
        )
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        handler.removeCallbacksAndMessages(null)
        broadcastStatus("LIVE OCR STOPPED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "4riders live screen scanner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("4riders Next • Live OCR")
            .setContentText("Membaca skrin dengan kebenaran anda. Tekan Stop dalam aplikasi untuk berhenti.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
