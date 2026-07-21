package com.blyen.ytv.models

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.blyen.ytv.SP
import com.blyen.ytv.data.SourceType
import com.blyen.ytv.data.TV
import com.blyen.ytv.requests.HttpClient
import kotlin.math.max
import kotlin.math.min
import android.util.Log

class TVModel(var tv: TV) : ViewModel() {
    var retryTimes = 0
    var retryMaxTimes = 10

    private fun hostLooksLikeLiveHls(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h.contains("qd.je")
                || h.contains("852851")
                || h.contains("163189")
                || h.contains("hidns")
                || h.contains("cdn")
                || h.contains("cc.cd")
                || h.contains("m3u8")
                || h.endsWith(".m3u8")
    }

    private var _groupIndex = 0
    val groupIndex: Int
        get() = if (SP.showAllChannels || _groupIndex == 0) _groupIndex else _groupIndex - 1

    fun setGroupIndex(index: Int) {
        _groupIndex = index
    }

    fun setVideoIndex(index: Int) {
        _videoIndex.value = max(0, min(tv.uris.size - 1, index))
    }

    fun setSourceType(type: SourceType) {
        tv.sourceType = type
        sourceTypeIndex = sourceTypeList.indexOf(type)
    }

    fun getGroupIndexInAll(): Int {
        return _groupIndex
    }

    fun sourceUp() {
        val validUris = tv.uris.filter { it.isNotBlank() }
        if (validUris.isEmpty()) return
        val newIndex = (videoIndexValue + 1) % validUris.size
        setVideoIndex(newIndex)
        confirmVideoIndex()
    }

    var listIndex = 0

    private var sourceTypeList: List<SourceType> =
        listOf(
            SourceType.UNKNOWN,
        )
    private var sourceTypeIndex = 0

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    fun setErrInfo(info: String) {
        _errInfo.value = info
    }

    private val _videoIndex = MutableLiveData<Int>()
    val videoIndex: LiveData<Int>
        get() = _videoIndex
    val videoIndexValue: Int
        get() = _videoIndex.value ?: 0

    fun getVideoUrl(): String? {
        if (videoIndexValue >= tv.uris.size) {
            return null
        }

        return tv.uris[videoIndexValue]
    }

    private val _like = MutableLiveData<Boolean>()
    val like: LiveData<Boolean>
        get() = _like

    fun setLike(liked: Boolean) {
        _like.value = liked
    }

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    fun setReady(retry: Boolean = false) {
        if (!retry) {
            setErrInfo("")
            retryTimes = 0

            _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
            sourceTypeIndex =
                max(0, min(sourceTypeList.size - 1, sourceTypeList.indexOf(tv.sourceType)))
        }
        _ready.value = true
    }

    private var userAgent = ""

    private var _httpDataSource: DataSource.Factory? = null
    private var _mediaItem: MediaItem? = null

    @OptIn(UnstableApi::class)
    fun getMediaItem(): MediaItem? {
        _mediaItem = getVideoUrl()?.let {
            val uri = Uri.parse(it) ?: return@let null
            val path = uri.path ?: return@let null
            val scheme = uri.scheme ?: return@let null

            val okHttpDataSource = OkHttpDataSource.Factory(HttpClient.okHttpClient)
            // 默认浏览器 UA，避免部分 CDN 拒绝 OkHttp 默认 UA 导致黑屏
            val defaultHeaders = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Connection" to "keep-alive",
            )
            tv.headers?.forEach { (key, value) ->
                defaultHeaders[key] = value
                if (key.equals("user-agent", ignoreCase = true)) {
                    userAgent = value
                }
            }
            if (userAgent.isEmpty()) {
                userAgent = defaultHeaders["User-Agent"] ?: ""
            }
            okHttpDataSource.setDefaultRequestProperties(defaultHeaders)

            _httpDataSource = okHttpDataSource

            val lowerPath = path.lowercase()
            val lowerUrl = it.lowercase()
            sourceTypeList = if (lowerPath.endsWith(".m3u8") || lowerUrl.contains("m3u8")
                || lowerUrl.contains("mpegurl")
                || hostLooksLikeLiveHls(uri.host)
            ) {
                // 无扩展名的直播入口（如 cdn.qd.je/.../hoy）强制 HLS，避免先走 Progressive 黑屏
                listOf(SourceType.HLS)
            } else if (lowerPath.endsWith(".mpd")) {
                listOf(SourceType.DASH)
            } else if (scheme.lowercase() == "rtsp") {
                listOf(SourceType.RTSP)
            } else if (scheme.lowercase() == "rtmp") {
                listOf(SourceType.RTMP)
            } else if (scheme.lowercase() == "rtp") {
                listOf(SourceType.RTP)
            } else if (scheme.lowercase() == "http" || scheme.lowercase() == "https") {
                // 直播优先 HLS；误判时再试 Progressive
                listOf(SourceType.HLS, SourceType.PROGRESSIVE)
            } else {
                listOf(SourceType.PROGRESSIVE)
            }
            // URL 变化时重置类型；同 URL 保留 nextSourceType 推进结果
            if (_mediaItem?.localConfiguration?.uri?.toString() != it) {
                sourceTypeIndex = 0
            } else {
                sourceTypeIndex = max(0, min(sourceTypeList.size - 1, sourceTypeIndex))
            }

            // 对齐 GitHub YourTV：默认 MediaItem，不强制 LiveConfiguration（避免贴边过紧卡顿）
            MediaItem.Builder().setUri(it).build()
        }
        return _mediaItem
    }

    fun getSourceTypeDefault(): SourceType {
        return tv.sourceType
    }

    fun getSourceTypeCurrent(): SourceType {
        sourceTypeIndex = max(0, min(sourceTypeList.size - 1, sourceTypeIndex))
        return sourceTypeList[sourceTypeIndex]
    }

    fun sourceTypeListSize(): Int = sourceTypeList.size

    fun nextSourceType(): Boolean {
        if (sourceTypeList.isEmpty()) return true
        sourceTypeIndex = (sourceTypeIndex + 1) % sourceTypeList.size

        return sourceTypeIndex == sourceTypeList.size - 1
    }

    fun confirmSourceType() {
        // TODO save default sourceType
        tv.sourceType = getSourceTypeCurrent()
    }

    fun confirmVideoIndex() {
        tv.videoIndex = videoIndexValue
    }

    @OptIn(UnstableApi::class)
    fun getMediaSource(): MediaSource? {
        if (sourceTypeList.isEmpty()) {
            return null
        }

        if (_mediaItem == null) {
            return null
        }
        val mediaItem = _mediaItem!!

        if (_httpDataSource == null) {
            return null
        }
        val httpDataSource = _httpDataSource!!

        return when (getSourceTypeCurrent()) {
            SourceType.HLS -> HlsMediaSource.Factory(httpDataSource)
                // 与 GitHub 一致保留重试；chunkless 加快无 INIT 的 master 起播
                .setLoadErrorHandlingPolicy(
                    androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3)
                )
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            SourceType.RTSP -> if (userAgent.isEmpty()) {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            } else {
                RtspMediaSource.Factory().setUserAgent(userAgent).createMediaSource(mediaItem)
            }

            SourceType.RTMP -> {
                val rtmpDataSource = RtmpDataSource.Factory()
                ProgressiveMediaSource.Factory(rtmpDataSource)
                    .createMediaSource(mediaItem)
            }

            SourceType.RTP -> null

            SourceType.DASH -> DashMediaSource.Factory(httpDataSource).createMediaSource(mediaItem)
            SourceType.PROGRESSIVE -> ProgressiveMediaSource.Factory(httpDataSource)
                .createMediaSource(mediaItem)

            else -> null
        }
    }

    fun isLastVideo(): Boolean {
        return videoIndexValue == tv.uris.size - 1
    }

    fun nextVideo(): Boolean {
        if (tv.uris.isEmpty()) {
            return false
        }

        _videoIndex.value = (videoIndexValue + 1) % tv.uris.size
        sourceTypeList = listOf(
            SourceType.UNKNOWN,
        )
        Log.d(TAG, "nextVideo: title=${tv.title}, new videoIndex=$videoIndexValue, url=${tv.uris.getOrNull(videoIndexValue)}")
        return isLastVideo()
    }

    fun update(t: TV) {
        tv = t
    }

    init {
        _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
        _like.value = SP.getLike(tv.id)
    }

    companion object {
        private const val TAG = "TVModel"
    }
}