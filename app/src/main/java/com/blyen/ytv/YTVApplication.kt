package com.blyen.ytv

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast


class YTVApplication : Application() {

    companion object {
        private const val TAG = "YTVApplication"
        private lateinit var instance: YTVApplication

        @JvmStatic
        fun getInstance(): YTVApplication {
            return instance
        }
    }

    private lateinit var displayMetrics: DisplayMetrics
    private lateinit var realDisplayMetrics: DisplayMetrics
    private var width = 0
    private var height = 0
    private var shouldWidth = 0
    private var shouldHeight = 0
    private var ratio = 1.0
    private var videoWidth = 0
    private var videoHeight = 0
    private var density = 2.0f
    private var scale = 1.0f
    private var fullScreenModeListener: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        SP.init(this) // 确保在任何 SP 属性访问之前初始化

        displayMetrics = DisplayMetrics()
        realDisplayMetrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        windowManager.defaultDisplay.getRealMetrics(realDisplayMetrics)

        // 获取设备实际分辨率
        if (realDisplayMetrics.heightPixels > realDisplayMetrics.widthPixels) {
            width = realDisplayMetrics.heightPixels
            height = realDisplayMetrics.widthPixels
        } else {
            width = realDisplayMetrics.widthPixels
            height = realDisplayMetrics.heightPixels
        }

        density = Resources.getSystem().displayMetrics.density
        scale = displayMetrics.scaledDensity

        // 在 SP.init 后安全地访问 SP 属性
        SP.compactMenu = true
        if (BuildConfig.PLAYBACK_ONLY) {
            SP.bootStartup = false
            SP.autoSwitchSource = true
            SP.fullScreenMode = true
            // 软解在部分真机上会出现「有声无画」
            SP.softDecode = false
            SP.setStableSources(emptyList())
        }
        updateDisplayMetrics(SP.fullScreenMode)

        Thread.setDefaultUncaughtExceptionHandler(YTVExceptionHandler(this))
    }

    // 更新显示尺寸
    private fun updateDisplayMetrics(isFullScreen: Boolean) {
        // 16:9 模式（原有逻辑）
        if ((width.toDouble() / height) < (16.0 / 9.0)) {
            ratio = width * 2 / 1920.0 / density
            shouldWidth = width
            shouldHeight = (width * 9.0 / 16.0).toInt()
        } else {
            ratio = height * 2 / 1080.0 / density
            shouldHeight = height
            shouldWidth = (height * 16.0 / 9.0).toInt()
        }
        if (isFullScreen) {
            videoWidth = width
            videoHeight = height
        } else {
            videoWidth = shouldWidth
            videoHeight = shouldHeight
        }
    }

    fun setFullScreenModeListener(listener: () -> Unit) {
        fullScreenModeListener = listener
    }

    fun toggleFullScreenMode(isFullScreen: Boolean) {
        SP.fullScreenMode = isFullScreen
        updateDisplayMetrics(isFullScreen)
        Log.d(TAG, "Full screen mode: $isFullScreen, shouldWidth: $shouldWidth, shouldHeight: $shouldHeight")
        fullScreenModeListener?.invoke()
    }

    fun getDisplayMetrics(): DisplayMetrics {
        return displayMetrics
    }

    fun toast(message: CharSequence = "", duration: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, duration).show()
        }
    }

    fun shouldWidthPx(): Int {
        return shouldWidth
    }

    fun shouldHeightPx(): Int {
        return shouldHeight
    }

    fun videoWidthPx(): Int {
        return videoWidth
    }

    fun videoHeightPx(): Int {
        return videoHeight
    }

    fun dp2Px(dp: Int): Int {
        return (dp * ratio * density + 0.5f).toInt()
    }

    fun px2Px(px: Int): Int {
        return (px * ratio + 0.5f).toInt()
    }

    fun px2PxFont(px: Float): Float {
        return (px * ratio / scale).toFloat()
    }

    fun sp2Px(sp: Float): Float {
        return (sp * ratio * scale).toFloat()
    }

    override fun attachBaseContext(base: Context) {
        try {
            super.attachBaseContext(base)
        } catch (_: Exception) {
            super.attachBaseContext(base)
        }
    }
}
