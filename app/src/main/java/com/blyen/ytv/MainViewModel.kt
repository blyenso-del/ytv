package com.blyen.ytv


import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blyen.ytv.Utils.getDateFormat
import com.blyen.ytv.data.Global.gson
import com.blyen.ytv.data.Global.typeTvList
import com.blyen.ytv.data.SourceType
import com.blyen.ytv.data.TV
import com.blyen.ytv.models.TVGroupModel
import com.blyen.ytv.models.TVListModel
import com.blyen.ytv.models.TVModel
import com.blyen.ytv.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import com.blyen.ytv.data.PlayerType
import okhttp3.Request


class MainViewModel : ViewModel() {

    private lateinit var context: Context

    private val _playTrigger = MutableLiveData<TVModel?>()
    val playTrigger: LiveData<TVModel?> get() = _playTrigger

    fun triggerPlay(tvModel: TVModel?) {
        _playTrigger.postValue(tvModel)
    }

    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    var listModel: List<TVModel> = emptyList()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var initialized = false

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun init(context: Context) {
        this.context = context

        if (groupModel.getAllList() == null || groupModel.getAllList()!!.tvList.value.isNullOrEmpty()) {
            groupModel.addTVListModel(TVListModel(context.getString(R.string.my_favorites), 0))
            groupModel.addTVListModel(TVListModel(context.getString(R.string.all_channels), 1))
        }

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        try {
            if (!cacheFile!!.exists()) {
                cacheFile!!.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cache file: ${e.message}", e)
        }

        // 远程频道列表 → 本地缓存（解析必须在 IO）
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .putString("active_source", "remote_channels.txt")
                    .apply()
                // 旧 stable 按 id 污染会把 IPTV 改成 WEBVIEW / 错误线路 → 黑屏
                SP.setStableSources(emptyList())
                SP.autoSwitchSource = true
                SP.fullScreenMode = true
                clearCacheChannels()

                val (str, sourceId) = loadChannelsText()
                if (str.isEmpty()) {
                    Log.e(TAG, "no channels available (remote+cache empty)")
                    withContext(Dispatchers.Main) {
                        _channelsOk.value = false
                        initialized = true
                    }
                    return@launch
                }
                tryStr2Channels(str, null, if (sourceId == "remote") REMOTE_CHANNELS_URL else "", sourceId)
                withContext(Dispatchers.Main) {
                    val first = listModel.firstOrNull()
                    if (first != null) {
                        groupModel.setCurrent(first)
                        groupModel.setPositionPlaying()
                        groupModel.getCurrentList()?.let {
                            it.setPosition(0)
                            it.setPositionPlaying()
                        }
                        first.setReady()
                        triggerPlay(first)
                        Log.i(
                            TAG,
                            "play source=$sourceId: ${first.tv.title} uris=${first.tv.uris.size} url=${first.getVideoUrl()}"
                        )
                    } else {
                        Log.w(TAG, "no channels after parse (source=$sourceId)")
                    }
                    _channelsOk.value = listModel.isNotEmpty()
                    initialized = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "init failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _channelsOk.value = false
                    initialized = true
                }
            }
        }
    }

    /**
     * 远程 M3U → 本地磁盘缓存。
     * @return Pair(文本, 来源标记 remote|cache)；均失败返回空串
     */
    private fun loadChannelsText(): Pair<String, String> {
        // 1) 远程
        try {
            val client = HttpClient.okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(REMOTE_CHANNELS_URL)
                .header("User-Agent", "YTV/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw java.io.IOException("http ${resp.codeAlias()}")
                }
                val body = resp.bodyAlias()?.string().orEmpty()
                if (!looksLikeM3u(body)) {
                    throw java.io.IOException("not m3u, len=${body.length}")
                }
                // 成功则写缓存，供下次离线/失败回退
                try {
                    File(appDirectory, REMOTE_CACHE_FILE).writeText(body)
                } catch (e: Exception) {
                    Log.w(TAG, "write remote cache: ${e.message}")
                }
                Log.i(TAG, "channels from remote: $REMOTE_CHANNELS_URL len=${body.length}")
                return body to "remote"
            }
        } catch (e: Exception) {
            Log.w(TAG, "remote channels failed: ${e.message}")
        }

        // 2) 上次成功下载的缓存
        try {
            val cache = File(appDirectory, REMOTE_CACHE_FILE)
            if (cache.exists()) {
                val body = cache.readText()
                if (looksLikeM3u(body)) {
                    Log.i(TAG, "channels from disk cache len=${body.length}")
                    return body to "cache"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "disk cache failed: ${e.message}")
        }

        return "" to "none"
    }

    private fun looksLikeM3u(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("#EXTM3U") || t.startsWith("#EXTINF") ||
            t.startsWith("http://") || t.startsWith("https://")
    }

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "") {
        try {
            if (str.isEmpty()) {
                Log.w(TAG, "Input string is empty for url=$url")
                R.string.channel_read_error.showToast()
                return
            }
            val targetFile = file ?: cacheFile
            Log.d(TAG, "tryStr2Channels: Input str length=${str.length}, url=$url id=$id")
            if (str2Channels(str)) {
                if (targetFile != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        targetFile.writeText(str)
                    }
                }
                cacheChannels = str
                if (url.isNotEmpty()) {
                    SP.configUrl = url
                }
                viewModelScope.launch {
                    withContext(Dispatchers.Main) {
                        _channelsOk.value = true
                    }
                }
            } else {
                Log.w(TAG, "str2Channels failed for url=$url")
                R.string.channel_import_error.showToast()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryStr2Channels: Failed for url=$url: ${e.message}", e)
            R.string.channel_read_error.showToast()
        }
    }

    private fun str2Channels(str: String): Boolean {
        if (initialized && str == cacheChannels) {
            Log.w(TAG, "same channels, skipping parsing")
            return true
        }

        if (str.isEmpty()) {
            Log.w(TAG, "Input string is empty")
            return false
        }

        R.string.parsing_live_source.showToast()

        val string = str
        if (string.isEmpty()) {
            Log.w(TAG, "channels is empty after processing")
            return false
        }

        val currentTvTitle = groupModel.getCurrent()?.tv?.title
        Log.d(TAG, "str2Channels: Saving currentTvTitle=$currentTvTitle")

        // 只收 IPTV 行；webview:// 整段丢弃
        val lines = string.split("\n", "\r\n", "\r").filter { it.isNotBlank() }
        val iptvLines = mutableListOf<String>()
        var lastWasExtinf = false
        var webviewSkipped = 0

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            if (trimmedLine.startsWith("#EXTM3U")) {
                iptvLines.add(trimmedLine)
                // EPG 已移除，忽略 x-tvg-url
                lastWasExtinf = false
            } else if (trimmedLine.startsWith("#EXTINF")) {
                iptvLines.add(trimmedLine)
                lastWasExtinf = true
            } else if (trimmedLine.startsWith("webview://")) {
                if (lastWasExtinf && iptvLines.isNotEmpty() && iptvLines.last().startsWith("#EXTINF")) {
                    iptvLines.removeAt(iptvLines.lastIndex)
                }
                webviewSkipped++
                lastWasExtinf = false
            } else if (trimmedLine.startsWith("#EXTVLCOPT")) {
                iptvLines.add(trimmedLine)
                lastWasExtinf = false
            } else if (!trimmedLine.startsWith("#")) {
                iptvLines.add(trimmedLine)
                lastWasExtinf = false
            }
        }
        if (webviewSkipped > 0) {
            Log.i(TAG, "str2Channels: skipped $webviewSkipped webview:// entries")
        }
        val webviewModels = emptyList<TVModel>()

        // 处理 IPTV 直播源
        val iptvList: List<TV> = if (iptvLines.isNotEmpty()) {
            val iptvContent = iptvLines.joinToString("\n")
            when {
                iptvContent.startsWith("[") -> {
                    try {
                        gson.fromJson(iptvContent, typeTvList) ?: emptyList()
                    } catch (e: Exception) {
                        Log.e(TAG, "IPTV JSON parsing failed: ${e.message}")
                        emptyList()
                    }
                }
                iptvContent.startsWith("#") -> {
                    val tvMap = mutableMapOf<String, MutableList<TV>>()
                    var currentTV: TV? = null
                    for (line in iptvLines) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.isEmpty()) continue

                        if (trimmedLine.startsWith("#EXTM3U")) {
                            continue
                        } else if (trimmedLine.startsWith("#EXTINF")) {
                            var lastKey: String? = null // 跟踪上一个频道的 key
                            if (currentTV != null && currentTV.uris.isNotEmpty()) {
                                val key = (currentTV.group + currentTV.name).ifEmpty { currentTV.title }
                                if (key != lastKey) {
                                    tvMap.computeIfAbsent(key) { mutableListOf() }.add(currentTV)
                                    lastKey = key
                                } else {
                                    tvMap[key]?.last()?.uris?.toMutableList()?.addAll(currentTV.uris)
                                }
                            }
                            currentTV = TV()
                            val info = trimmedLine.split(",", limit = 2)
                            if (info.size < 2) continue
                            currentTV = currentTV.copy(title = info.last().trim())

                            val extinf = info.first()
                            val nameStart = extinf.indexOf("tvg-name=\"") + 10
                            val nameEnd = extinf.indexOf("\"", nameStart)
                            currentTV = currentTV.copy(
                                name = if (nameStart > 9 && nameEnd > nameStart) {
                                    extinf.substring(nameStart, nameEnd)
                                } else {
                                    currentTV.title
                                }
                            )

                            val logoStart = extinf.indexOf("tvg-logo=\"") + 10
                            val logoEnd = extinf.indexOf("\"", logoStart)
                            currentTV = currentTV.copy(
                                logo = if (logoStart > 9 && logoEnd > logoStart) {
                                    extinf.substring(logoStart, logoEnd)
                                } else {
                                    ""
                                }
                            )

                            val numStart = extinf.indexOf("tvg-chno=\"") + 10
                            val numEnd = extinf.indexOf("\"", numStart)
                            currentTV = currentTV.copy(
                                number = if (numStart > 9 && numEnd > numStart) {
                                    extinf.substring(numStart, numEnd).toIntOrNull() ?: -1
                                } else {
                                    -1
                                }
                            )

                            val groupStart = extinf.indexOf("group-title=\"") + 13
                            val groupEnd = extinf.indexOf("\"", groupStart)
                            currentTV = currentTV.copy(
                                group = if (groupStart > 12 && groupEnd > groupStart) {
                                    extinf.substring(groupStart, groupEnd)
                                } else {
                                    ""
                                }
                            )
                        } else if (trimmedLine.startsWith("#EXTVLCOPT:http-")) {
                            if (currentTV != null) {
                                val keyValue = trimmedLine.substringAfter("#EXTVLCOPT:http-").split("=", limit = 2)
                                if (keyValue.size == 2) {
                                    currentTV = currentTV.copy(
                                        headers = (currentTV.headers ?: emptyMap()).toMutableMap().apply {
                                            this[keyValue[0]] = keyValue[1]
                                        }
                                    )
                                }
                            }
                        } else if (!trimmedLine.startsWith("#") && currentTV != null) {
                            currentTV = currentTV.copy(
                                uris = currentTV.uris.toMutableList().apply { add(trimmedLine) }
                            )
                        }
                    }

                    var lastKey: String? = null // 跟踪上一个频道的 key
                    if (currentTV != null && currentTV.uris.isNotEmpty()) {
                        val key = (currentTV.group + currentTV.name).ifEmpty { currentTV.title }
                        if (key != lastKey) {
                            tvMap.computeIfAbsent(key) { mutableListOf() }.add(currentTV)
                            lastKey = key
                        } else {
                            tvMap[key]?.last()?.uris?.toMutableList()?.addAll(currentTV.uris)
                        }
                    }

                    tvMap.values.map { tvs ->
                        val uris = tvs.flatMap { it.uris }.distinct()
                        TV(
                            id = -1,
                            name = tvs[0].name,
                            title = tvs[0].title,
                            description = null,
                            logo = tvs[0].logo,
                            image = null,
                            uris = uris,
                            videoIndex = 0,
                            headers = tvs[0].headers,
                            group = tvs[0].group,
                            sourceType = SourceType.UNKNOWN,
                            number = tvs[0].number,
                            child = emptyList(),
                            playerType = PlayerType.IPTV
                        )
                    }.filter { it.uris.isNotEmpty() }
                }
                else -> emptyList()
            }
        } else {
            emptyList()
        }

        if (iptvList.isEmpty() && webviewModels.isEmpty()) {
            Log.w(TAG, "str2Channels: Parsed TV list is empty")
            return false
        }

        // 合并 IPTV 和 WebView 频道 —— 必须同步写完 listModel，避免 PLAYBACK_ONLY 等调用方读到空列表
        val applyModels = {
            groupModel.setTVListModelList(
                listOf(
                    TVListModel(context.getString(R.string.my_favorites), 0),
                    TVListModel(context.getString(R.string.all_channels), 1)
                )
            )

            // 生成 IPTV TVModel
            val iptvModels = iptvList.mapIndexed { index, tv ->
                TVModel(tv.copy(id = index)).apply {
                    setLike(SP.getLike(index))
                    setGroupIndex(2)
                    listIndex = index
                }
            }

            // 合并所有 TVModel
            val modelMap = mutableMapOf<String, TVModel>()
            (iptvModels + webviewModels).forEach { tvModel ->
                val key = (tvModel.tv.group + tvModel.tv.name).ifEmpty { tvModel.tv.title }
                if (modelMap.containsKey(key)) {
                    modelMap[key]?.tv?.uris = (modelMap[key]?.tv?.uris.orEmpty() + tvModel.tv.uris).distinct()
                } else {
                    modelMap[key] = tvModel
                }
            }
            val listModelNew = modelMap.values.sortedBy { it.listIndex }.toMutableList()

            // 源未标序号时由 App 生成：最终列表唯一 1..N（id=0..N-1）
            // 必须在分组前写回，收藏/分组/全部分享同一套台号，避免重复或对不上
            listModelNew.forEachIndexed { index, tvModel ->
                tvModel.listIndex = index
                tvModel.tv = tvModel.tv.copy(id = index, number = index + 1)
            }

            val groupMap = mutableMapOf<String, MutableList<TVModel>>()
            listModelNew.forEach { tvModel ->
                val group = tvModel.tv.group.ifEmpty { context.getString(R.string.unknown) }
                groupMap.computeIfAbsent(group) { mutableListOf() }.add(tvModel)
            }

            groupMap.forEach { (group, tvModels) ->
                // 组内也按全局台号排序，菜单顺序与台号一致
                val sorted = tvModels.sortedBy { it.tv.number.takeIf { n -> n > 0 } ?: it.tv.id }
                val existingGroup = groupModel.tvGroupValue.find { it.getName() == group }
                if (existingGroup != null) {
                    existingGroup.setTVListModel(sorted)
                } else {
                    val newGroup = TVListModel(group, groupModel.tvGroupValue.size)
                    newGroup.setTVListModel(sorted)
                    groupModel.addTVListModel(newGroup)
                }
            }

            listModel = listModelNew
            groupModel.tvGroupValue[1].setTVListModel(listModelNew)

            // 仅当当前无有效 groupModel.current 或非稳定源时，恢复或设置默认
            val currentStableSource = SP.getStableSources().firstOrNull { it.id == groupModel.getCurrent()?.tv?.id }
            if (currentTvTitle != null) {
                val matchingTvModel = listModelNew.firstOrNull { it.tv.title == currentTvTitle }
                if (matchingTvModel != null) {
                    groupModel.setCurrent(matchingTvModel)
                    Log.d(TAG, "str2Channels: Restored groupModel.current to: ${matchingTvModel.tv.title}")
                }
            } else if (groupModel.getCurrent() == null || currentStableSource == null) {
                // 仅当无稳定源时设置默认频道
                if (listModelNew.isNotEmpty()) {
                    groupModel.setCurrent(listModelNew[0])
                    Log.d(TAG, "str2Channels: Set default groupModel.current to: ${listModelNew[0].tv.title}")
                }
            }

            Log.d(TAG, "str2Channels: Updated listModel size=${listModel.size}")
            groupModel.setChange()
        }

        // 解析已在调用线程完成；仅 UI 状态更新必须在主线程
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            applyModels()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            var applyError: Throwable? = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    applyModels()
                } catch (t: Throwable) {
                    applyError = t
                } finally {
                    latch.countDown()
                }
            }
            // 最多等 5 秒，避免永久卡死后台线程；勿在主线程调用本路径
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.e(TAG, "str2Channels: applyModels timed out on main")
            }
            applyError?.let { throw it }
        }

        return true
    }

    fun clearCacheChannels() {
        cacheChannels = ""
        Log.d(TAG, "clearCacheChannels: Cache cleared")
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "codechannels.txt"
        /** GitHub Pages 自定义域：sub.blyen.ccwu.cc */
        const val REMOTE_CHANNELS_URL = "https://sub.blyen.ccwu.cc/channels.txt"
        private const val REMOTE_CACHE_FILE = "channels_remote_cache.txt"
    }
}
