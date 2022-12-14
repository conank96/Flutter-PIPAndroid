package com.example.tub_app_overlays

import android.R
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.JSONMessageCodec
import kotlin.math.roundToInt


const val INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow"
const val CHANNEL_ID = "Overlay Channel"
const val NOTIFICATION_ID = 4579
const val MIN_SIZE = 200f
const val RATIO_VIDEO = 16f / 9f
const val MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f

class OverlayService : Service(), View.OnTouchListener {

    private var windowManager: WindowManager? = null
    private var flutterView: FlutterView? = null
    private val overlayMessageChannel = BasicMessageChannel(
        FlutterEngineCache.getInstance()[CACHE_TAG]!!.dartExecutor,
        MESSAGE_CHANNEL,
        JSONMessageCodec.INSTANCE
    )

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val szWindow: Point = Point()
    var scaleGestureDetector: ScaleGestureDetector? = null
    private var mScaleFactor = 1f

    private val displayMetrics = DisplayMetrics()
    private val width: Int get() = displayMetrics.widthPixels
    private val height: Int get() = displayMetrics.heightPixels


    private val params: WindowManager.LayoutParams get() = flutterView?.layoutParams as WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isCloseWindow = intent?.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false)
        if (isCloseWindow != null && isCloseWindow) {
            dismissOverlays()
            return START_STICKY
        }
        if (windowManager != null) {
            windowManager?.removeView(flutterView)
            windowManager = null
            stopSelf()
        }
        WindowConfig.serviceIsRunning = true
        val engine = FlutterEngineCache.getInstance()[CACHE_TAG]
        if (engine != null) {
            engine.lifecycleChannel.appIsResumed()

            flutterView = FlutterView(
                applicationContext, FlutterTextureView(
                    applicationContext
                )
            )
            flutterView?.let { view ->
                view.attachToFlutterEngine(engine)
                view.fitsSystemWindows = true
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.setBackgroundColor(Color.TRANSPARENT)
                scaleGestureDetector = ScaleGestureDetector(this, scaleListener)

                view.setOnTouchListener(this)
            }
        }

        overlayMessageChannel.setMessageHandler { message: Any?, _: BasicMessageChannel.Reply<Any?>? ->
            if (message == "close") {
                dismissOverlays()
            }
            WindowConfig.messenger.send(message)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        windowManager?.apply {
            defaultDisplay?.getSize(szWindow)
            val params = WindowManager.LayoutParams(
                WindowConfig.width,
                WindowConfig.height,
                WindowConfig.x,
                WindowConfig.y,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            defaultDisplay?.getMetrics(displayMetrics)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER
            }
            params.gravity = Gravity.CENTER or Gravity.START
            addView(flutterView, params)
        }

        return START_STICKY
    }

    private fun dismissOverlays() {
        if (windowManager != null) {
            windowManager?.removeView(flutterView)
            windowManager = null
            stopSelf()
        }
        WindowConfig.serviceIsRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, pendingFlags
        )
        val notifyIcon = getDrawableResourceId("mipmap", "launcher")
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("title")
                .setContentText("overlayContent")
                .setSmallIcon(if (notifyIcon == 0) R.drawable.arrow_up_float else notifyIcon)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0L))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        startForeground(NOTIFICATION_ID, notification)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)

            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun getDrawableResourceId(resType: String, name: String): Int {
        return applicationContext.resources.getIdentifier(
            String.format("ic_%s", name),
            resType,
            applicationContext.packageName
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        WindowConfig.serviceIsRunning = false
    }

    private fun resizeOverlay(scaleRatio: Float) {
        if (windowManager != null) {
            val w = (params.width * scaleRatio).toInt()
            val h = (params.height * scaleRatio).toInt()

            params.width = if (w < dpToPx(MIN_SIZE)) dpToPx(MIN_SIZE).toInt() else w

            val heightRatio = dpToPx(MIN_SIZE / RATIO_VIDEO)
            params.height = if (h < heightRatio) heightRatio.toInt() else h

            windowManager?.updateViewLayout(flutterView, params)
        }
    }


    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            Log.d("ManhNQ", "onScale: $mScaleFactor  -- ${detector.scaleFactor}")
            mScaleFactor *= detector.scaleFactor
            mScaleFactor = mScaleFactor.calculatorScale()

            resizeOverlay(mScaleFactor)
            return true
        }
    }


    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        scaleGestureDetector?.onTouchEvent(event)

        if (windowManager != null && WindowConfig.enableDrag) {

            when (event?.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false;
                    lastX = event.rawX;
                    lastY = event.rawY;
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        if (!dragging && dx * dx + dy * dy < 25) {
                            return false
                        }
                        lastX = event.rawX
                        lastY = event.rawY
                        val xx = params.x + dx.toInt()
                        val yy = params.y + dy.toInt()
                        params.x = xx.calculatorX(width, params.width)
                        params.y = yy.calculatorY(height, params.height)

                        windowManager?.updateViewLayout(flutterView, params)
                        dragging = true
                    }

                }
                else -> {
                    return false
                }
            }
            return false
        }
        return false
    }

    private fun Int.calculatorX(widthPx: Int, widthParams: Int): Int {
        val offset = widthPx - widthParams
        return if (this < 0) {
            0
        } else if (this > offset) {
            offset
        } else {
            this
        }
    }

    private fun Int.calculatorY(height: Int, heightParams: Int): Int {
        val offset = (height - heightParams) / 2 - 35
        return if (this < -offset) {
            -offset
        } else if (this > offset) {
            offset
        } else {
            this
        }
    }

    private fun Float.calculatorScale(): Float {
        return if (this > (width.toFloat() / params.width.toFloat())) {
            width.toFloat() / params.width.toFloat()
        } else {
            this
        }
    }

}

fun Context.dpToPx(dp: Float): Float {
    return (dp * resources.displayMetrics.density + 0.5f)
}

fun Context.pxToDp(px: Float): Int {
    return (px / resources.displayMetrics.density).roundToInt()
}