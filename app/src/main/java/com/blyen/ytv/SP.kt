package com.blyen.ytv


import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.blyen.ytv.data.Global
import com.blyen.ytv.data.StableSource
import androidx.core.content.edit
import android.content.pm.PackageManager
import android.app.UiModeManager
import android.content.res.Configuration

object SP {
    private const val TAG = "SP"
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"
    private const val KEY_CHANNEL_NUM = "channel_num"
    private const val KEY_TIME = "time"
    private const val KEY_BOOT_STARTUP = "boot_startup"
    private const val KEY_POSITION = "position"
    private const val KEY_POSITION_GROUP = "position_group"
    private const val KEY_REPEAT_INFO = "repeat_info"
    private const val KEY_CONFIG_URL = "config"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_DEFAULT_LIKE = "default_like"
    private const val KEY_DISPLAY_SECONDS = "display_seconds"
    private const val KEY_SHOW_ALL_CHANNELS = "show_all_channels"
    private const val KEY_COMPACT_MENU = "compact_menu"
    private const val KEY_LIKE = "like"
    private const val KEY_PROXY = "proxy"
    private const val KEY_VERSION = "version"
    private const val KEY_LOG_TIMES = "log_times"
    private const val KEY_SOFT_DECODE = "soft_decode"
    private const val KEY_AUTO_SWITCH_SOURCE = "auto_switch_source"
    private const val PREF_NAME = "YTV"
    private const val KEY_STABLE_SOURCES = "stable_sources"
    private const val KEY_SHOW_SOURCE_BUTTON = "show_source_button"
    private const val KEY_ENABLE_SCREEN_OFF_AUDIO = "enable_screen_off_audio"
    private const val KEY_FULL_SCREEN_MODE = "full_screen_mode"
    private const val RESOLUTION_CACHE_PREFIX = "resolution_"
    private const val RESOLUTION_CACHE_TIMESTAMP_PREFIX = "resolution_timestamp_"
    private const val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 小时

    // 移除静态常量，改用动态默认值
    private var DEFAULT_SOFT_DECODE: Boolean = false
    internal var DEFAULT_FULL_SCREEN_MODE: Boolean = true

    const val DEFAULT_ENABLE_SCREEN_OFF_AUDIO = true
    const val DEFAULT_SHOW_SOURCE_BUTTON = true
    const val DEFAULT_AUTO_SWITCH_SOURCE = false
    const val DEFAULT_CHANNEL_REVERSAL = false
    const val DEFAULT_CHANNEL_NUM = false
    const val DEFAULT_TIME = true
    const val DEFAULT_BOOT_STARTUP = false
    const val DEFAULT_CONFIG_URL = ""
    const val DEFAULT_PROXY = ""
    const val DEFAULT_CHANNEL = 0
    const val DEFAULT_SHOW_ALL_CHANNELS = false
    const val DEFAULT_COMPACT_MENU = true
    const val DEFAULT_DISPLAY_SECONDS = true
    const val DEFAULT_LOG_TIMES = 10

    // 0 favorite; 1 all
    const val DEFAULT_POSITION_GROUP = 1
    const val DEFAULT_POSITION = 0
    const val DEFAULT_REPEAT_INFO = true

    private lateinit var sp: SharedPreferences
    private val gson = Global.gson
    private val typeStableSourceList = Global.typeStableSourceList

    // 判断设备是否为触摸屏设备
    @SuppressLint("ServiceCast")
    private fun isTouchScreenDevice(context: Context): Boolean {
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    // 初始化默认值
    private fun initDefaultValues(context: Context) {
        DEFAULT_SOFT_DECODE = isTouchScreenDevice(context) // 触摸屏为 true，电视为 false
        DEFAULT_FULL_SCREEN_MODE = isTouchScreenDevice(context) // 触摸屏为 true，电视为 false
    }

    fun init(context: Context) {
        // 先初始化默认值
        initDefaultValues(context)

        sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 初始化 showSourceButton
        if (!sp.contains(KEY_SHOW_SOURCE_BUTTON)) {
            sp.edit(commit = true) { putBoolean(KEY_SHOW_SOURCE_BUTTON, DEFAULT_SHOW_SOURCE_BUTTON) }
        }

        if (!sp.contains(KEY_SOFT_DECODE)) {
            sp.edit(commit = true) { putBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE) }
        }
    }

    var enableScreenOffAudio: Boolean
        get() = sp.getBoolean(KEY_ENABLE_SCREEN_OFF_AUDIO, DEFAULT_ENABLE_SCREEN_OFF_AUDIO)
        set(value) = sp.edit() { putBoolean(KEY_ENABLE_SCREEN_OFF_AUDIO, value) }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, DEFAULT_CHANNEL_REVERSAL)
        set(value) = sp.edit() { putBoolean(KEY_CHANNEL_REVERSAL, value) }

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, DEFAULT_CHANNEL_NUM)
        set(value) = sp.edit() { putBoolean(KEY_CHANNEL_NUM, value) }

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, DEFAULT_TIME)
        set(value) = sp.edit() { putBoolean(KEY_TIME, value) }

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, DEFAULT_BOOT_STARTUP)
        set(value) = sp.edit() { putBoolean(KEY_BOOT_STARTUP, value) }

    var positionGroup: Int
        get() = sp.getInt(KEY_POSITION_GROUP, DEFAULT_POSITION_GROUP)
        set(value) = sp.edit() { putInt(KEY_POSITION_GROUP, value) }

    var position: Int
        get() = sp.getInt(KEY_POSITION, DEFAULT_POSITION)
        set(value) = sp.edit() { putInt(KEY_POSITION, value) }

    var repeatInfo: Boolean
        get() = sp.getBoolean(KEY_REPEAT_INFO, DEFAULT_REPEAT_INFO)
        set(value) = sp.edit() { putBoolean(KEY_REPEAT_INFO, value) }

    var configUrl: String?
        get() = sp.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL)
        set(value) = sp.edit() { putString(KEY_CONFIG_URL, value) }

    var channel: Int
        get() = sp.getInt(KEY_CHANNEL, DEFAULT_CHANNEL)
        set(value) = sp.edit() { putInt(KEY_CHANNEL, value) }

    var compactMenu: Boolean
        get() = sp.getBoolean(KEY_COMPACT_MENU, DEFAULT_COMPACT_MENU)
        set(value) = sp.edit() { putBoolean(KEY_COMPACT_MENU, value) }

    var showAllChannels: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL_CHANNELS, DEFAULT_SHOW_ALL_CHANNELS)
        set(value) = sp.edit() { putBoolean(KEY_SHOW_ALL_CHANNELS, value) }

    var defaultLike: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_LIKE, false)
        set(value) = sp.edit() { putBoolean(KEY_DEFAULT_LIKE, value) }

    var displaySeconds: Boolean
        get() = sp.getBoolean(KEY_DISPLAY_SECONDS, DEFAULT_DISPLAY_SECONDS)
        set(value) = sp.edit() { putBoolean(KEY_DISPLAY_SECONDS, value) }

    var softDecode: Boolean
        get() = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
        set(value) {
            if (sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE) != value) {
                sp.edit(commit = true) { putBoolean(KEY_SOFT_DECODE, value) }
            }
        }

    fun getLike(id: Int): Boolean {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())
        return stringSet?.contains(id.toString()) ?: false
    }

    fun setLike(id: Int, liked: Boolean) {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (liked) {
            stringSet.add(id.toString())
        } else {
            stringSet.remove(id.toString())
        }

        sp.edit() { putStringSet(KEY_LIKE, stringSet) }
    }

    var proxy: String?
        get() = sp.getString(KEY_PROXY, DEFAULT_PROXY)
        set(value) = sp.edit() { putString(KEY_PROXY, value) }

    var version: String?
        get() = sp.getString(KEY_VERSION, "")
        set(value) = sp.edit() { putString(KEY_VERSION, value) }

    var logTimes: Int
        get() = sp.getInt(KEY_LOG_TIMES, DEFAULT_LOG_TIMES)
        set(value) = sp.edit() { putInt(KEY_LOG_TIMES, value) }

    var autoSwitchSource: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH_SOURCE, DEFAULT_AUTO_SWITCH_SOURCE)
        set(value) = sp.edit() { putBoolean(KEY_AUTO_SWITCH_SOURCE, value) }

    var stableSources: String?
        get() = sp.getString(KEY_STABLE_SOURCES, null)
        set(value) = sp.edit() { putString(KEY_STABLE_SOURCES, value) }

    var showSourceButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_SOURCE_BUTTON, DEFAULT_SHOW_SOURCE_BUTTON)
        set(value) = sp.edit() { putBoolean(KEY_SHOW_SOURCE_BUTTON, value) }

    var fullScreenMode: Boolean
        get() {
            if (!::sp.isInitialized) {
                return DEFAULT_FULL_SCREEN_MODE // 默认值，防止未初始化时崩溃
            }
            return sp.getBoolean(KEY_FULL_SCREEN_MODE, DEFAULT_FULL_SCREEN_MODE)
        }
        set(value) {
            if (::sp.isInitialized) {
                sp.edit().putBoolean(KEY_FULL_SCREEN_MODE, value).apply()
            }
        }

    fun getStableSources(): List<StableSource> {
        val json = stableSources ?: return emptyList()
        return try {
            val sources = gson.fromJson(json, typeStableSourceList) as List<StableSource>
            sources.filter {
                System.currentTimeMillis() - it.timestamp < 7 * 24 * 60 * 60 * 1000
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setStableSources(sources: List<StableSource>) {
        val json = gson.toJson(sources, typeStableSourceList)
        stableSources = json
    }

    fun getResolutionCache(url: String): String? {
        val key = RESOLUTION_CACHE_PREFIX + url.hashCode()
        val timestampKey = RESOLUTION_CACHE_TIMESTAMP_PREFIX + url.hashCode()
        val cachedResolution = sp.getString(key, null)
        val timestamp = sp.getLong(timestampKey, 0)
        return if (cachedResolution != null && System.currentTimeMillis() - timestamp < CACHE_DURATION) {
            cachedResolution
        } else {
            null
        }
    }

    fun cacheResolution(url: String, resolution: String) {
        val key = RESOLUTION_CACHE_PREFIX + url.hashCode()
        val timestampKey = RESOLUTION_CACHE_TIMESTAMP_PREFIX + url.hashCode()
        sp.edit().apply {
            putString(key, resolution)
            putLong(timestampKey, System.currentTimeMillis())
            apply()
        }
    }

}