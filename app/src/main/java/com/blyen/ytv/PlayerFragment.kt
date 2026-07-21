package com.blyen.ytv

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.blyen.ytv.data.SourceType
import com.blyen.ytv.databinding.PlayerBinding
import com.blyen.ytv.models.TVModel
import androidx.media3.ui.PlayerView
import com.blyen.ytv.data.StableSource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.blyen.ytv.data.TV
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Rational
import androidx.core.view.isVisible
import com.blyen.ytv.data.PlayerType
import android.view.Gravity
import android.widget.TextView
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.blyen.ytv.requests.HttpClient
import java.io.File
import java.util.concurrent.Executors


class PlayerFragment : Fragment() {
    private lateinit var viewModel: MainViewModel
    fun setViewModel(viewModel: MainViewModel) {
        this.viewModel = viewModel
    }
    private var consecutiveHeavySkips = 0
    private val maxHeavySkips = 5
    private val stablePlaybackDuration = 30_000L
    private var isStable = false
    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!
    internal var player: ExoPlayer? = null
    internal var tvModel: TVModel? = null
    private val aspectRatio = 16f / 9f
    internal var isInPictureInPictureMode = false
    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideVolume = 2 * 1000L
    // 新增：缓冲检测变量
    private val bufferingThreshold = 5
    private val bufferingDurationThreshold = 8_000L
    private val switchCooldown = 15_000L
    private val stablePlaybackThreshold = 10_000L
    private var bufferingStartTime = 0L
    private var bufferingCount = 0
    private var lastSwitchTime = 0L
    private var playbackStartTime = 0L
    private val bufferingTimestamps = mutableListOf<Long>()
    private var lastBufferingTime = 0L
    private var isSourceButtonVisible = false
    private var lastSwitchSourceTime = 0L
    private val switchSourceDebounce = 400L
    // 新增：播放停止检测变量
    private var lastStopTime = 0L
    private val stopDurationThreshold = 5_000L
    // PLAYBACK_ONLY：缩短冷却，避免首线失败后长时间黑屏卡死
    private val retryCooldown = if (BuildConfig.PLAYBACK_ONLY) 3_000L else 30_000L
    private val checkPlaybackInterval = if (BuildConfig.PLAYBACK_ONLY) 8_000L else 15_000L
    /** 解码/内存类错误连续恢复次数，防止 4K 源在电视上连环 prepare 闪退 */
    private var decoderFailStreak = 0
    private val maxDecoderFailStreak = 2
    /**
     * 起播后长时间进不了 READY/播放 → 必须 cancel 网络 + 异步 release，
     * 否则 HLS 一直拉分片 / 主线程 release 卡死 → 死机。
     */
    /**
     * 加载超时底线（GitHub 无此项；保留防假死，不按 4K 双模式）。
     * 正常流畅靠默认缓冲 + 换台 abort，不靠贴边/狂 seek。
     */
    private val loadTimeoutMs = 30_000L
    private var activeLoadTimeoutMs = loadTimeoutMs
    private var loadWatchGeneration = -1
    private var loadDeadlineElapsed = 0L
    private var consecutiveLoadTimeouts = 0
    private val maxConsecutiveLoadTimeouts = 2
    private val loadTimeoutRunnable = Runnable {
        onLoadTimeout()
    }
    private var everPlayedCurrent = false
    private var trackSelector: DefaultTrackSelector? = null
    /** release 卡主线程是死机常见原因，放到后台 */
    private val playerReleaseExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "exo-release").apply { isDaemon = true }
    }
    /**
     * 快速切台：generation 递增作废旧错误回调；
     * pending + coalesce 只 prepare 最后一台，避免上一个还在 BUFFERING 就叠解码器闪退。
     */
    private var playGeneration = 0
    /** 当前已 prepare 的 generation；错误回调若落后则丢弃 */
    private var preparedGeneration = 0
    private var pendingPlayModel: TVModel? = null
    /** 连切合并窗口：只 prepare 最后一台 */
    private val channelZapCoalesceMs = 550L
    private var lastPlayInternalAt = 0L
    private var isPreparing = false
    private val playLatestRunnable = Runnable {
        val model = pendingPlayModel
        pendingPlayModel = null
        if (model != null) {
            // 对齐 GitHub：复用 player，只 softStop 再 prepare（全量 release 易切台后起不来）
            softStopCurrent("settle-play")
            playInternal(model, playGeneration)
        }
    }

    /** 可 pull 的调试日志（本机 logcat 常被滤空） */
    private fun dbg(msg: String) {
        Log.i(TAG, msg)
        try {
            val ctx = context ?: return
            val f = File(ctx.filesDir, "player_debug.log")
            f.appendText("${System.currentTimeMillis()} $msg\n")
            // 控制体积
            if (f.length() > 256 * 1024) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(400).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    private fun isStalePlay(generation: Int): Boolean = generation != playGeneration
    // 定义保存间隔（例如 5 分钟，防止频繁保存）
    private var lastPauseTime = 0L
    private val stableSourceCheckRunnable = Runnable {
        if (isPreparing || pendingPlayModel != null) return@Runnable
        if (player?.isPlaying == true && tvModel != null &&
            System.currentTimeMillis() - playbackStartTime >= stablePlaybackDuration &&
            bufferingCount == 0 && tvModel!!.retryTimes == 0) {
            isStable = true
            saveStableSource(tvModel!!)
            Log.d(TAG, "Stable source saved via stableSourceCheckRunnable: ${tvModel!!.tv.title}")
        }
    }

    /**
     * 立刻停网 + 解绑 Surface，**异步 release** ExoPlayer。
     * 主线程 stop/release 在解码器半死时会直接把 UI 卡死。
     */
    /**
     * Activity settle 窗口内也可调用：立刻停网 + 异步 release，避免 4K 仍在拉片。
     */
    @OptIn(UnstableApi::class)
    fun abortForZap() {
        // Activity settle：只停流不销毁 player，对齐 GitHub 切台
        softStopCurrent("activity-zap")
    }

    /**
     * 主线程立刻静音+暂停。必须在丢弃 player 引用的同一拍执行，
     * 否则异步 release 排队期间旧台声音会继续出（叠音）。
     */
    @OptIn(UnstableApi::class)
    private fun silencePlayerImmediate(p: ExoPlayer?) {
        if (p == null) return
        try {
            p.volume = 0f
        } catch (_: Exception) {
        }
        try {
            p.playWhenReady = false
        } catch (_: Exception) {
        }
        try {
            p.pause()
        } catch (_: Exception) {
        }
    }

    /**
     * 换台 soft-stop：静音 + stop + clearMedia + cancel 网络，**保留 ExoPlayer 实例**。
     * 对齐 GitHub switchSource；比全量 release 更不易切台后起不来。
     */
    @OptIn(UnstableApi::class)
    private fun softStopCurrent(reason: String) {
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.removeCallbacks(loadTimeoutRunnable)
        isPreparing = false
        loadWatchGeneration = -1
        loadDeadlineElapsed = 0L
        everPlayedCurrent = false
        preparedGeneration = -1
        bufferingCount = 0
        bufferingStartTime = 0L
        bufferingTimestamps.clear()
        lastStopTime = 0L
        try {
            HttpClient.cancelAllCalls()
        } catch (_: Exception) {
        }
        val p = player
        if (p != null) {
            silencePlayerImmediate(p)
            try {
                p.stop()
            } catch (_: Exception) {
            }
            try {
                p.clearMediaItems()
            } catch (_: Exception) {
            }
        }
        dbg("softStop: $reason gen=$playGeneration player=${p != null}")
    }

    /** 主线程静音后，后台 release；仅超时/错误/销毁使用 */
    @OptIn(UnstableApi::class)
    private fun releasePlayerAsync(p: ExoPlayer?, reason: String) {
        if (p == null) return
        silencePlayerImmediate(p)
        playerReleaseExecutor.execute {
            try {
                try {
                    p.volume = 0f
                } catch (_: Exception) {
                }
                try {
                    p.playWhenReady = false
                } catch (_: Exception) {
                }
                p.release()
            } catch (e: Exception) {
                Log.w(TAG, "async player.release ($reason): ${e.message}")
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun abortCurrentPlayback(reason: String) {
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.removeCallbacks(loadTimeoutRunnable)
        isPreparing = false
        loadWatchGeneration = -1
        loadDeadlineElapsed = 0L
        everPlayedCurrent = false
        val p = player
        player = null
        silencePlayerImmediate(p)
        try {
            if (_binding != null) {
                binding.playerView.player = null
            }
        } catch (_: Exception) {
        }
        try {
            HttpClient.cancelAllCalls()
        } catch (_: Exception) {
        }
        releasePlayerAsync(p, reason)
        lastStopTime = 0L
        bufferingCount = 0
        bufferingStartTime = 0L
        bufferingTimestamps.clear()
        preparedGeneration = -1
        dbg("abortCurrentPlayback: $reason gen=$playGeneration")
    }

    private fun scheduleLoadTimeout(generation: Int) {
        handler.removeCallbacks(loadTimeoutRunnable)
        loadWatchGeneration = generation
        val ms = activeLoadTimeoutMs
        loadDeadlineElapsed = android.os.SystemClock.elapsedRealtime() + ms
        handler.postDelayed(loadTimeoutRunnable, ms)
        Log.i(TAG, "loadTimeout scheduled ${ms}ms gen=$generation")
    }

    private fun cancelLoadTimeout() {
        handler.removeCallbacks(loadTimeoutRunnable)
        loadWatchGeneration = -1
        loadDeadlineElapsed = 0L
    }

    /**
     * 超时硬杀：停网 + 异步 release + 提示（GitHub 无此项；仅作假死底线）。
     * 不连环自动换台。
     */
    @OptIn(UnstableApi::class)
    private fun onLoadTimeout() {
        if (pendingPlayModel != null) {
            cancelLoadTimeout()
            return
        }
        val p = player
        val tv = tvModel
        if (p != null && p.isPlaying) {
            consecutiveLoadTimeouts = 0
            cancelLoadTimeout()
            return
        }
        val stillLoading = p != null && (
                p.playbackState == Player.STATE_BUFFERING
                        || p.playbackState == Player.STATE_IDLE
                        || !p.isPlaying
                )
        if (!stillLoading) {
            consecutiveLoadTimeouts = 0
            return
        }
        consecutiveLoadTimeouts++
        Log.w(
            TAG,
            "LOAD TIMEOUT HARD KILL #${consecutiveLoadTimeouts}: ${tv?.tv?.title} state=${p?.playbackState} url=${tv?.getVideoUrl()}"
        )
        abortCurrentPlayback("load-timeout")
        handler.postDelayed({
            try {
                tv?.setErrInfo(R.string.play_error.getString())
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "加载超时，请换台", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
            }
        }, 200)
        // 可再试一条线路
        if (consecutiveLoadTimeouts < maxConsecutiveLoadTimeouts && tv != null && !tv.isLastVideo()) {
            handler.postDelayed({
                if (pendingPlayModel != null) return@postDelayed
                if (player != null) return@postDelayed
                try {
                    tv.nextVideo()
                    tv.confirmVideoIndex()
                    play(tv, force = true)
                } catch (e: Exception) {
                    Log.e(TAG, "timeout next line: ${e.message}")
                }
            }, 600)
        } else {
            consecutiveLoadTimeouts = 0
        }
    }

    @OptIn(UnstableApi::class)
    fun enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Picture-in-Picture mode not supported on API ${Build.VERSION.SDK_INT}")
            return
        }
        if (!isTouchScreenDevice()) {
            Log.d(TAG, "Picture-in-Picture mode skipped: Not a touchscreen device")
            return
        }
        // 获取视频的实际宽高比
        val aspectRatio = run {
            val videoSize = player?.videoSize
            if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                val ratio = videoSize.width.toFloat() / videoSize.height
                when {
                    ratio > 2.39f -> Rational(239, 100)
                    ratio < 1 / 2.39f -> Rational(100, 239)
                    else -> Rational(videoSize.width, videoSize.height)
                }
            } else {
                Rational(16, 9)
            }
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .build()
        try {
            requireActivity().enterPictureInPictureMode(params)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to enter Picture-in-Picture mode: ${e.message}")
            return
        }
        if (_binding != null) {
            binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.useController = false
            binding.playerView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            binding.playerView.requestLayout()
            binding.playerView.requestFocus()
            if (player == null && tvModel != null) {
                updatePlayer()
                playInternal(tvModel!!, playGeneration)
                Log.d(TAG, "Player was null, reinitialized for ${tvModel!!.tv.title}")
            } else if (player?.isPlaying == false && tvModel != null) {
                player?.playWhenReady = true
                Log.d(TAG, "enterPictureInPictureMode: Playback resumed for ${tvModel!!.tv.title}")
            }
            setSourceButtonVisibility(false)
            binding.icon.visibility = View.GONE
            binding.volume.visibility = View.GONE
            binding.playerView.clearFocus()
        }
        isInPictureInPictureMode = true
        Log.d(TAG, "Entered Picture-in-Picture mode with aspectRatio=$aspectRatio, playerType=${tvModel?.tv?.playerType}")
    }

    @OptIn(UnstableApi::class)
    fun exitPictureInPictureMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Picture-in-Picture mode not supported on API ${Build.VERSION.SDK_INT}")
            return
        }
        if (_binding != null) {
            isInPictureInPictureMode = false
            setSourceButtonVisibility(isTouchScreenDevice() && SP.showSourceButton)
            onFullScreenModeChanged()
            Log.d(TAG, "Exiting Picture-in-Picture mode, btn_source visible=${binding.btnSource.isVisible}")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            updatePlayer()
            binding.playerView.isFocusable = true
            binding.playerView.isFocusableInTouchMode = true
            binding.playerView.requestFocus()
            Log.d(TAG, "PlayerView focus requested: isFocusable=${binding.playerView.isFocusable}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PlayerFragment view: ${e.message}", e)
        }
        // 勿再次 updatePlayer()：会 release 刚创建的 ExoPlayer 并可能导致进程崩溃
        (activity as MainActivity).ready()

        val btnSource = view.findViewById<Button>(R.id.btn_source)
        val btnUp = view.findViewById<Button>(R.id.btn_channel_up)
        val btnDown = view.findViewById<Button>(R.id.btn_channel_down)

        // PLAYBACK_ONLY / 触摸屏：常显换台与切线按钮
        setSourceButtonVisibility(true)

        btnUp?.setOnClickListener {
            (activity as? MainActivity)?.let {
                // 上键 = 上一台（与 channelUp 默认一致）
                if (SP.channelReversal) it.next() else it.prev()
            }
        }
        btnDown?.setOnClickListener {
            (activity as? MainActivity)?.let {
                if (SP.channelReversal) it.prev() else it.next()
            }
        }
        // 单击切线；长按打开线路列表
        btnSource.setOnClickListener {
            (activity as? MainActivity)?.sourceUp()
            Log.d(TAG, "btn_source click -> sourceUp")
        }
        btnSource.setOnLongClickListener {
            val mainActivity = activity as? MainActivity
            mainActivity?.showFragment(mainActivity.sourceSelectFragment)
            true
        }

        // 防止 PlayerView 吞掉控件区域触摸
        binding.playerView.setOnTouchListener { _, event ->
            val controls = view.findViewById<View>(R.id.channel_controls)
            val rect = android.graphics.Rect()
            controls?.getGlobalVisibleRect(rect)
            if (controls != null && controls.visibility == View.VISIBLE
                && rect.contains(event.rawX.toInt(), event.rawY.toInt())
            ) {
                controls.dispatchTouchEvent(event)
                true
            } else {
                (activity as? MainActivity)?.gestureDetector?.onTouchEvent(event) ?: false
                true
            }
        }
    }

    // 控制右侧控制条（上/下换台 + 切线）可见性
    @OptIn(UnstableApi::class)
    fun setSourceButtonVisibility(visible: Boolean) {
        val controls = binding.root.findViewById<View>(R.id.channel_controls)
        val btnSource = binding.root.findViewById<Button>(R.id.btn_source) ?: return
        val btnUp = binding.root.findViewById<Button>(R.id.btn_channel_up)
        val btnDown = binding.root.findViewById<Button>(R.id.btn_channel_down)
        // 非画中画时始终显示，方便上下换台
        val shouldShow = visible && !isInPictureInPictureMode
        val vis = if (shouldShow) View.VISIBLE else View.GONE
        controls?.visibility = vis
        listOf(btnSource, btnUp, btnDown).forEach { b ->
            b?.visibility = vis
            b?.isFocusable = shouldShow
            b?.isEnabled = shouldShow
            b?.isFocusableInTouchMode = shouldShow
        }
        isSourceButtonVisible = shouldShow
        Log.d(TAG, "setSourceButtonVisibility: shouldShow=$shouldShow")
    }

    // 新增：播放状态回调接口
    interface PlaybackCallback {
        fun onPlaybackStarted()
    }

    private var playbackCallback: PlaybackCallback? = null

    // 新增：设置回调
    fun setPlaybackCallback(callback: PlaybackCallback) {
        this.playbackCallback = callback
    }

    @OptIn(UnstableApi::class)
    fun updatePlayer(heavyOnly4k: Boolean = false) {
        if (context == null) {
            Log.e(TAG, "context == null")
            return
        }
        // heavyOnly4k 参数保留兼容调用方，播放策略已与 GitHub 对齐为单一默认路径
        @Suppress("UNUSED_PARAMETER")
        val _unusedHeavy = heavyOnly4k

        val ctx = requireContext()
        val playerView = binding.playerView
        val renderersFactory = DefaultRenderersFactory(ctx)
        val playerMediaCodecSelector = PlayerMediaCodecSelector()
        renderersFactory.setMediaCodecSelector(playerMediaCodecSelector)
        // PLAYBACK_ONLY：强制硬解（优于 GitHub 可软解，避免有声无画）
        val preferSoft = !BuildConfig.PLAYBACK_ONLY && SP.softDecode && isTouchScreenDevice()
        renderersFactory.setExtensionRendererMode(
            if (preferSoft) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        )
        renderersFactory.setEnableDecoderFallback(true)

        // 旧 player：主线程立刻静音，再异步 release（防换台叠音；GitHub 主线程 release 易卡）
        val old = player
        player = null
        playerView.player = null
        releasePlayerAsync(old, "updatePlayer")

        activeLoadTimeoutMs = loadTimeoutMs

        // 对齐 GitHub：不自定义 LoadControl，用 Media3 默认缓冲（更贴近上游起播/抗抖）
        // 多档仍 cap 1080p，避免手机硬扛 4K 死机（GitHub 无 cap，此处保留为最优）
        val ts = DefaultTrackSelector(ctx)
        ts.parameters = ts.buildUponParameters()
            .setMaxVideoSize(1920, 1080)
            .setMaxVideoBitrate(8_000_000)
            .setExceedVideoConstraintsIfNecessary(true)
            .setForceHighestSupportedBitrate(false)
            .build()
        trackSelector = ts

        player = ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(ts)
            .build()
        player?.repeatMode = Player.REPEAT_MODE_OFF
        player?.playWhenReady = true
        player?.volume = 1f
        try {
            player?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ false
            )
        } catch (_: Exception) {
        }
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!isInPictureInPictureMode) {
                    updatePlayerViewLayout()
                }
                Log.i(TAG, "Video size changed: ${videoSize.width}x${videoSize.height}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!
                if (isPlaying) {
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0
                    decoderFailStreak = 0
                    consecutiveLoadTimeouts = 0
                    everPlayedCurrent = true
                    cancelLoadTimeout()
                    bufferingCount = 0
                    bufferingStartTime = 0L
                    bufferingTimestamps.clear()
                    lastBufferingTime = 0L
                    playbackStartTime = System.currentTimeMillis()
                    playbackCallback?.onPlaybackStarted()
                    lastStopTime = 0L
                    consecutiveHeavySkips = 0
                    try {
                        player?.volume = 1f
                    } catch (_: Exception) {
                    }
                    dbg("is playing: ${tv.tv.title} #${tv.tv.number}")

                } else {
                    isStable = false
                    playbackStartTime = 0L
                    lastStopTime = System.currentTimeMillis()
                    dbg("playback stop: ${tv.tv.title}")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    consecutiveLoadTimeouts = 0
                    if (player?.isPlaying == true) {
                        cancelLoadTimeout()
                    }
                }
                if (state == Player.STATE_BUFFERING && loadWatchGeneration < 0 && preparedGeneration == playGeneration) {
                    scheduleLoadTimeout(playGeneration)
                }

                if (!SP.autoSwitchSource) return
                if (tvModel == null || player == null) {
                    return
                }
                // 加载中不要跑「不流畅切源」逻辑，避免与 loadTimeout 叠加重试
                if (state == Player.STATE_BUFFERING && player?.currentPosition == 0L) {
                    return
                }

                val currentTime = System.currentTimeMillis()

                // 检查是否处于播放稳定期（启动或切换源后10秒内不监控缓冲）
                if (currentTime - playbackStartTime < stablePlaybackThreshold) {
                    if (state == Player.STATE_READY) {
                        // 播放稳定后更新开始时间
                        playbackStartTime = currentTime
                    }
                    return
                }

                // 检测缓冲状态
                if (state == Player.STATE_BUFFERING) {
                    // 过滤快速重复缓冲（小于500ms的忽略）
                    if (currentTime - lastBufferingTime < 500L) {
                        return
                    }

                    if (bufferingStartTime == 0L) {
                        bufferingStartTime = currentTime
                    }
                    lastBufferingTime = currentTime
                    bufferingTimestamps.add(currentTime)
                    // 统计最近10秒内的缓冲次数
                    bufferingCount = bufferingTimestamps.count { it >= currentTime - 10_000L }
                    val bufferingDuration = currentTime - bufferingStartTime

                    // 清理过旧的时间戳
                    bufferingTimestamps.removeAll { it < currentTime - 10_000L }

                    // 检查是否需要切换源
                    if ((bufferingCount >= bufferingThreshold && currentTime - lastSwitchTime >= switchCooldown) ||
                        (bufferingDuration >= bufferingDurationThreshold && currentTime - lastSwitchTime >= switchCooldown)) {
                        if (tvModel!!.retryTimes < tvModel!!.retryMaxTimes && player!!.currentPosition > 0) {
                            Log.i(TAG, "Non-smooth playback detected: bufferingCount=$bufferingCount, duration=$bufferingDuration")
                            (activity as MainActivity).sourceUp()
                            lastSwitchTime = currentTime
                            playbackStartTime = currentTime // 重置播放开始时间
                            bufferingCount = 0
                            bufferingStartTime = 0L
                            bufferingTimestamps.clear()
                            lastBufferingTime = 0L
                        }
                    }
                } else if (state == Player.STATE_READY) {
                    // 播放流畅时重置缓冲变量（如果持续流畅超过2秒）
                    if (currentTime - lastBufferingTime >= 2_000L) {
                        bufferingStartTime = 0L
                        bufferingCount = 0
                        bufferingTimestamps.clear()
                        lastBufferingTime = 0L
                    }
                } else if (state == Player.STATE_ENDED) {
                    // 播放结束时重置所有变量
                    bufferingStartTime = 0L
                    bufferingCount = 0
                    bufferingTimestamps.clear()
                    lastBufferingTime = 0L
                    playbackStartTime = 0L
                    lastStopTime = currentTime // 记录停止时间
                    Log.w(TAG, "${tvModel!!.tv.title} playback ended, marking for retry, lastStopTime=$lastStopTime, cooldownRemaining=${if (currentTime - lastSwitchTime < retryCooldown) retryCooldown - (currentTime - lastSwitchTime) else 0}")
                    // 优化：立即触发重试
                    if (!isInPictureInPictureMode) {
                        Log.w(TAG, "${tvModel!!.tv.title} ended, retrying immediately")
                        switchSource(tvModel!!)
                        lastSwitchTime = currentTime
                        lastStopTime = 0L
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    (activity as MainActivity).onPlayEnd()
                }
                // 不在 discontinuity 上再 seek，避免 seek 连环 BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                // 快速切台后旧流的错误一律丢弃，避免对已取消的 prepare 连环 recover
                if (pendingPlayModel != null || preparedGeneration != playGeneration) {
                    Log.w(
                        TAG,
                        "Ignore stale player error: code=${error.errorCode} prepared=$preparedGeneration gen=$playGeneration pending=${pendingPlayModel != null}"
                    )
                    return
                }
                Log.w(TAG, "Player error: ${error.errorCode}, message=${error.message}, cause=${error.cause?.message}")
                lastStopTime = System.currentTimeMillis()

                // 纯播放：任何错误都推进 类型→线路→下一台，禁止卡死黑屏
                if (BuildConfig.PLAYBACK_ONLY && tvModel != null) {
                    val tv = tvModel!!
                    val now = System.currentTimeMillis()
                    if (now - lastSwitchTime < 2000L) {
                        Log.w(TAG, "PLAYBACK_ONLY error debounce, skip: ${tv.tv.title}")
                        return
                    }
                    lastSwitchTime = now
                    lastStopTime = 0L
                    val isDecoderish = error.errorCode in listOf(
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                        PlaybackException.ERROR_CODE_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    ) || (error.message?.contains("Decoder", ignoreCase = true) == true)
                            || (error.message?.contains("OMX", ignoreCase = true) == true)
                            || (error.message?.contains("OutOfMemory", ignoreCase = true) == true)
                            || (error.cause?.message?.contains("codec", ignoreCase = true) == true)
                            || (error.cause is OutOfMemoryError)
                    if (isDecoderish) {
                        decoderFailStreak++
                    }
                    Log.w(
                        TAG,
                        "PLAYBACK_ONLY recover: ${tv.tv.title} code=${error.errorCode} decoderStreak=$decoderFailStreak retry=${tv.retryTimes} idx=${tv.videoIndexValue}"
                    )
                    // 解码/内存错误：少重试同 URL，尽快换线；不自动跳下一台（4K 连环拖死）
                    if (isDecoderish && decoderFailStreak >= maxDecoderFailStreak) {
                        decoderFailStreak = 0
                        abortCurrentPlayback("decoder-fail")
                        if (!tv.isLastVideo()) {
                            tv.nextVideo()
                            tv.confirmVideoIndex()
                            tv.retryTimes = 0
                            play(tv)
                        } else {
                            tv.setErrInfo(R.string.play_error.getString())
                            try {
                                if (isAdded && context != null) {
                                    Toast.makeText(requireContext(), "解码失败，请手动换台", Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                            }
                        }
                        return
                    }
                    // 1) 先试同 URL 换协议类型（HLS→PROGRESSIVE）；解码类错误跳过
                    if (!isDecoderish && tv.retryTimes < 2 && tv.sourceTypeListSize() > 1) {
                        tv.nextSourceType()
                        tv.retryTimes++
                        play(tv)
                        return
                    }
                    // 2) 换下一条线路
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.confirmVideoIndex()
                        tv.retryTimes = 0
                        play(tv)
                        return
                    }
                    // 3) 整台失败：停住提示，不自动连跳频道（切台必须用户可控）
                    abortCurrentPlayback("all-fail")
                    tv.setErrInfo(R.string.play_error.getString())
                    try {
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), "加载失败，请手动换台", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                    }
                    return
                }

                Log.w(TAG, "Marking for retry: lastStopTime=$lastStopTime, cooldownRemaining=${if (System.currentTimeMillis() - lastSwitchTime < retryCooldown) retryCooldown - (System.currentTimeMillis() - lastSwitchTime) else 0}")
                if (System.currentTimeMillis() - lastSwitchTime >= retryCooldown) {
                    Log.w(TAG, "${tvModel?.tv?.title} error, retrying immediately")
                    tvModel?.let { switchSource(it) }
                    lastSwitchTime = System.currentTimeMillis()
                    lastStopTime = 0L
                }
                // 首次使用检测：无稳定源且 cacheFile 不存在
                // val isFirstUse = SP.getStableSources().isEmpty() && !File(requireContext().filesDir, "cacheFile").exists()
                val isFirstUse = SP.getStableSources().isEmpty()
                if (isFirstUse && tvModel != null) {
                    if (tvModel!!.retryTimes < 3) { // 限制为 3 次
                        tvModel!!.nextSourceType() // 尝试下一个源类型
                        tvModel!!.setReady(true)
                        tvModel!!.retryTimes++
                        Log.i(TAG, "First use: Error detected, switching source for ${tvModel!!.tv.title}")
                        (activity as MainActivity).sourceUp()
                        lastSwitchTime = System.currentTimeMillis()
                        // 快速超时：3 秒后若未播放，触发下一次切换
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp()
                                Log.i(TAG, "First use: 3s timeout, retry switching for ${tvModel!!.tv.title}")
                            }
                        }, 3_000L)
                        return
                    } else if (!tvModel!!.isLastVideo()) {
                        tvModel!!.nextVideo() // 尝试下一个视频源
                        tvModel!!.setReady(true)
                        tvModel!!.retryTimes = 0
                        Log.i(TAG, "First use: All source types failed, switching video for ${tvModel!!.tv.title}")
                        (activity as MainActivity).sourceUp()
                        lastSwitchTime = System.currentTimeMillis()
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp()
                                Log.i(TAG, "First use: 3s timeout, retry switching for ${tvModel!!.tv.title}")
                            }
                        }, 3_000L)
                        return
                    } else {
                        // 所有源无效，尝试下一个频道
                        val nextChannel = viewModel.groupModel.getNext()
                        if (nextChannel != null) {
                            nextChannel.setReady()
                            viewModel.groupModel.setCurrent(nextChannel)
                            switchSource(nextChannel)
                            Log.d(TAG, "First use: Fell back to next channel: ${nextChannel.tv.title}")
                            lastSwitchTime = System.currentTimeMillis()
                            handler.postDelayed({
                                if (player?.isPlaying != true) {
                                    (activity as MainActivity).sourceUp()
                                    Log.i(TAG, "First use: 3s timeout, retry switching for ${nextChannel.tv.title}")
                                }
                            }, 3_000L)
                            return
                        } else {
                            tvModel!!.setErrInfo(R.string.play_error.getString())
                            Log.w(TAG, "First use: No next channel available")
                            return
                        }
                    }
                }

                if (!SP.autoSwitchSource) {
                    Log.w(TAG, "Auto-switch disabled, ignoring error: ${error.message}")
                    return
                }
                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                // 扩展错误码：HLS 常见 404/超时/清单解析 也要恢复
                if (error.errorCode !in listOf(
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    )) {
                    Log.w(TAG, "Non-supported error: ${error.errorCode}, ignoring")
                    return
                }

                val tv = tvModel!!
                if (tv.retryTimes < tv.retryMaxTimes) {
                    var last = true
                    if (tv.getSourceTypeDefault() == SourceType.UNKNOWN) {
                        last = tv.nextSourceType()
                    }
                    tv.setReady(true)
                    if (last) {
                        tv.retryTimes++
                    }
                    Log.i(
                        TAG,
                        "Retry ${tv.videoIndex.value} ${tv.getSourceTypeCurrent()} ${tv.retryTimes}/${tv.retryMaxTimes}"
                    )
                    if (System.currentTimeMillis() - lastSwitchTime >= switchCooldown) {
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp()
                            } else {
                                Log.d(TAG, "Playback recovered, no need to switch")
                            }
                        }, 2_000L)
                        lastSwitchTime = System.currentTimeMillis()
                    }
                } else {
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                        (activity as MainActivity).sourceUp()
                    } else {
                        // Fallback to stable source
                        lifecycleScope.launch(Dispatchers.Main) {
                            val stableSource = selectRandomStableSource()
                            if (stableSource != null) {
                                val newTvModel = TVModel(
                                    TV(
                                        id = stableSource.id,
                                        name = stableSource.name,
                                        title = stableSource.title,
                                        description = stableSource.description,
                                        logo = stableSource.logo,
                                        image = stableSource.image,
                                        uris = stableSource.uris,
                                        videoIndex = stableSource.videoIndex,
                                        headers = stableSource.headers,
                                        group = stableSource.group,
                                        sourceType = SourceType.valueOf(stableSource.sourceType),
                                        number = stableSource.number,
                                        child = stableSource.child,
                                        playerType = stableSource.playerType,
                                        block = stableSource.block,
                                        script = stableSource.script,
                                        selector = stableSource.selector,
                                        started = stableSource.started,
                                        finished = stableSource.finished
                                    )
                                ).apply {
                                    setLike(SP.getLike(stableSource.id))
                                    setGroupIndex(2)
                                    listIndex = 0
                                }
                                viewModel.groupModel.setCurrent(newTvModel)
                                switchSource(newTvModel)
                                Log.d(TAG, "Fell back to stable source: ${newTvModel.tv.title}, url: ${newTvModel.getVideoUrl()}")
                                return@launch
                            }
                            // No stable sources, try next channel
                            val nextChannel = viewModel.groupModel.getNext()
                            if (nextChannel != null) {
                                nextChannel.setReady()
                                viewModel.groupModel.setCurrent(nextChannel)
                                switchSource(nextChannel)
                                Log.d(TAG, "Fell back to next channel: ${nextChannel.tv.title}")
                            } else {
                                tv.setErrInfo(R.string.play_error.getString())
                                Log.w(TAG, "No stable sources or next channel available")
                            }
                        }
                    }
                }
            }
        })

        playerView.player = player
        // 不在此自动 play，避免与 playInternal 重入（切台闪退主因之一）

        // 修复：移动定时器启动到末尾
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
        handler.postDelayed(stableSourceCheckRunnable, stablePlaybackDuration)
    }

    @OptIn(UnstableApi::class)
    private fun updatePlayerViewLayout() {
        val playerView = binding.playerView
        val app = YTVApplication.getInstance()
        // 纯播放 / 全屏：始终铺满，避免手机上 videoWidth 计算异常导致 0 尺寸黑屏
        val isFullScreen = BuildConfig.PLAYBACK_ONLY || SP.fullScreenMode

        playerView.resizeMode = if (isFullScreen) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        val layoutParams = FrameLayout.LayoutParams(
            if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoWidthPx(),
            if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoHeightPx()
        ).apply {
            gravity = Gravity.CENTER // 确保居中
        }
        playerView.layoutParams = layoutParams

        playerView.requestLayout()
        playerView.post {
            Log.i(TAG, "Updated PlayerView layout: fullScreen=$isFullScreen, w=${playerView.width}, h=${playerView.height}")
        }
    }

    @OptIn(UnstableApi::class)
    fun onFullScreenModeChanged() {
        if (!isAdded || isInPictureInPictureMode || _binding == null) {
            Log.d(TAG, "onFullScreenModeChanged skipped: isAdded=$isAdded, isInPiP=$isInPictureInPictureMode, binding=${_binding}")
            return
        }
        val app = YTVApplication.getInstance()
        updatePlayerViewLayout()
        binding.playerView.visibility = View.VISIBLE
        binding.playerView.requestFocus()
        binding.playerView.isFocusable = true
        binding.playerView.isFocusableInTouchMode = true
        // 强制刷新整个布局
        binding.root.requestLayout()
        binding.root.requestFocus()
        // 验证窗口尺寸
        val displayMetrics = resources.displayMetrics
        Log.d(TAG, "onFullScreenModeChanged: fullScreen=${SP.fullScreenMode}, videoWidthPx=${app.videoWidthPx()}, videoHeightPx=${app.videoHeightPx()}, screenWidth=${displayMetrics.widthPixels}, screenHeight=${displayMetrics.heightPixels}")
    }

    private val checkPlaybackRunnable = object : Runnable {
        @OptIn(UnstableApi::class)
        override fun run() {
            val currentTime = System.currentTimeMillis()
            if (tvModel == null || !isResumed || pendingPlayModel != null) {
                Log.d(TAG, "Playback check skipped: tvModel=$tvModel, isResumed=$isResumed, pendingZap=${pendingPlayModel != null}")
                handler.postDelayed(this, checkPlaybackInterval)
                return
            }
            if (isInPictureInPictureMode || (lastPauseTime > lastStopTime && currentTime - lastPauseTime < stopDurationThreshold)) {
                Log.d(TAG, "Playback check skipped: recent pause at $lastPauseTime or in PiP mode")
                handler.postDelayed(this, checkPlaybackInterval)
                return
            }
            val isPlaying = player?.let {
                (it.isPlaying == true && it.playbackState == Player.STATE_READY && it.playWhenReady == true).also { playing ->
                    if (!playing && lastStopTime == 0L) {
                        lastStopTime = System.currentTimeMillis()
                        Log.d(TAG, "IPTV playback stopped, marking lastStopTime=$lastStopTime")
                    }
                }
            } ?: false
            val stopDuration = if (lastStopTime > 0) currentTime - lastStopTime else 0L
            val cooldownRemaining = if (currentTime - lastSwitchTime < retryCooldown) {
                retryCooldown - (currentTime - lastSwitchTime)
            } else 0L
            Log.d(TAG, "Playback check: isPlaying=$isPlaying, lastStopTime=$lastStopTime, " +
                    "stopDuration=$stopDuration, cooldownRemaining=$cooldownRemaining, " +
                    "isResumed=$isResumed, isInPip=$isInPictureInPictureMode, playerType=${tvModel!!.tv.playerType}, " +
                    "bufferingCount=$bufferingCount, retryTimes=${tvModel!!.retryTimes}, " +
                    "playbackDuration=${if (playbackStartTime > 0) currentTime - playbackStartTime else 0L}")
            if (!isPlaying && lastStopTime > 0 && stopDuration >= stopDurationThreshold && cooldownRemaining == 0L) {
                Log.w(TAG, "${tvModel!!.tv.title} stopped/stuck ${stopDurationThreshold / 1000}s")
                lastSwitchTime = currentTime
                lastStopTime = 0L
                // 长时间无画面：走超时释放，不要 switchSource 死循环
                if (BuildConfig.PLAYBACK_ONLY) {
                    loadWatchGeneration = playGeneration
                    preparedGeneration = playGeneration
                    onLoadTimeout()
                } else {
                    switchSource(tvModel!!)
                }
            } else if (isPlaying && stopDuration == 0L && cooldownRemaining == 0L) {
                // Check for stable source saving
                if (currentTime - playbackStartTime >= stablePlaybackDuration && // 播放持续 30 秒
                    bufferingCount == 0 && tvModel!!.retryTimes == 0) {
                    isStable = true
                    saveStableSource(tvModel!!)
                    Log.d(TAG, "Stable source saved: ${tvModel!!.tv.title}, playerType=${tvModel!!.tv.playerType}, isPlaying=$isPlaying")
                }
            }
            handler.postDelayed(this, checkPlaybackInterval)
        }
    }

    private fun selectRandomStableSource(): StableSource? {
        val stableSources = SP.getStableSources()
        return if (stableSources.isNotEmpty()) {
            stableSources.sortedByDescending { it.timestamp }.firstOrNull()
        } else {
            null
        }
    }

    private fun saveStableSource(tvModel: TVModel) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentUrl = tvModel.getVideoUrl() ?: run {
                    Log.w(TAG, "Failed to save stable source: ${tvModel.tv.title}, no valid URL")
                    return@launch
                }
                val tv = tvModel.tv
                Log.d(TAG, "Preparing to save stable source: ${tv.title}, videoIndex=${tvModel.videoIndexValue}, url=$currentUrl")
                val newSource = StableSource(
                    id = tv.id,
                    name = tv.name,
                    title = tv.title,
                    description = tv.description,
                    logo = tv.logo,
                    image = tv.image,
                    uris = listOf(currentUrl),
                    videoIndex = tvModel.videoIndexValue,
                    headers = tv.headers,
                    group = tv.group,
                    sourceType = tvModel.getSourceTypeCurrent().name,
                    number = tv.number,
                    child = tv.child,
                    timestamp = System.currentTimeMillis(),
                    playerType = tv.playerType,
                    block = tv.block,
                    script = tv.script,
                    selector = tv.selector,
                    started = tv.started,
                    finished = tv.finished
                )
                val currentSources = SP.getStableSources()
                // 检查 id、playerType、uris 和 videoIndex 是否完全相同
                val existingSource = currentSources.firstOrNull { it.id == newSource.id }
                if (existingSource != null &&
                    existingSource.playerType == newSource.playerType &&
                    existingSource.uris == newSource.uris &&
                    existingSource.videoIndex == newSource.videoIndex
                ) {
                    Log.d(TAG, "Skipping save stable source: ${newSource.title}, identical to existing (playerType=${newSource.playerType}, url=$currentUrl, videoIndex=${newSource.videoIndex})")
                    return@launch
                }
                // 保存新源，覆盖同 id 的旧源
                val updatedSources = (currentSources.filter { it.id != newSource.id } + newSource)
                    .sortedByDescending { it.timestamp }.take(200)
                SP.setStableSources(updatedSources)
                Log.d(TAG, "Saved stable source: ${newSource.title}, playerType=${newSource.playerType}, url=$currentUrl, videoIndex=${newSource.videoIndex}, uris=${newSource.uris}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save stable source: ${tvModel.tv.title}, error=${e.message}", e)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun ensurePlaying() {
        player?.run {
            if (!isPlaying && tvModel != null) {
                try {
                    seekToDefaultPosition()
                } catch (_: Exception) {
                }
                prepare()
                playWhenReady = true
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun switchSource(tvModel: TVModel) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwitchSourceTime < switchSourceDebounce) {
            Log.d(TAG, "Debounced switchSource for ${tvModel.tv.title}")
            return
        }
        lastSwitchSourceTime = currentTime
        playbackStartTime = currentTime

        val totalSources = tvModel.tv.uris.filter { it.isNotBlank() }.size
        val sourceIndex = tvModel.videoIndexValue + 1
        try {
            val toast = Toast.makeText(
                requireContext(),
                "线路 $sourceIndex / $totalSources",
                Toast.LENGTH_LONG
            )
            val textView = toast.view?.findViewById<TextView>(android.R.id.message)
            textView?.textSize = 30f
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
            handler.postDelayed({ toast.cancel() }, 5000)
        } catch (_: Exception) {
        }

        // 切线统一走 play()：release 旧 player + 合并，避免 player==null 静默失败
        Log.i(TAG, "switchSource -> play: ${tvModel.tv.title} idx=${tvModel.videoIndexValue}")
        play(tvModel)
    }

    /**
     * 对外起播入口。
     * 连切期间 **绝不 release/prepare**（只记最后一台 + 软暂停），
     * 停手后 playLatestRunnable 里再 release 并起播一次。
     */
    @OptIn(UnstableApi::class)
    fun play(tvModel: TVModel, force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val sameChannel = this.tvModel?.tv?.id == tvModel.tv.id
                && this.tvModel?.videoIndexValue == tvModel.videoIndexValue
                && this.tvModel?.getVideoUrl() == tvModel.getVideoUrl()
        // 仅非 force 且已在 READY/播放 时跳过；加载中菜单 force 必须换台
        if (!force && player != null && sameChannel && pendingPlayModel == null && !isPreparing &&
            (player?.isPlaying == true || player?.playbackState == Player.STATE_READY)
        ) {
            Log.d(TAG, "Skip duplicate play for ${tvModel.tv.title}")
            return
        }

        val switchingAway = force || (this.tvModel != null && this.tvModel?.tv?.id != tvModel.tv.id)
        val loading = player?.playbackState == Player.STATE_BUFFERING
                || player?.playbackState == Player.STATE_IDLE
                || isPreparing
        pendingPlayModel = tvModel
        this.tvModel = tvModel
        playGeneration++
        val gen = playGeneration
        lastSwitchSourceTime = currentTime
        decoderFailStreak = 0
        preparedGeneration = -1 // 作废旧错误回调
        everPlayedCurrent = false

        // 立刻 soft-stop 旧流（保留 player），settle 只合并最终 prepare
        if (player != null || isPreparing || switchingAway || loading) {
            softStopCurrent("user-zap")
        }

        handler.removeCallbacks(playLatestRunnable)
        val actualDelay = when {
            force -> 50L
            player == null && gen <= 1 && !switchingAway -> 0L
            else -> 200L
        }
        dbg("play schedule: ${tvModel.tv.title} delay=${actualDelay}ms gen=$gen force=$force #${tvModel.tv.number}")
        if (actualDelay <= 0L) {
            pendingPlayModel = null
            softStopCurrent("first-play")
            playInternal(tvModel, gen)
        } else {
            handler.postDelayed(playLatestRunnable, actualDelay)
        }
    }

    @OptIn(UnstableApi::class)
    private fun playInternal(tvModel: TVModel, generation: Int) {
        if (generation != playGeneration) {
            Log.d(TAG, "playInternal drop stale gen=$generation current=$playGeneration")
            return
        }
        if (!isAdded || _binding == null) {
            Log.w(TAG, "playInternal: fragment not ready")
            return
        }
        lastSwitchSourceTime = System.currentTimeMillis()
        lastPlayInternalAt = lastSwitchSourceTime
        this.tvModel = tvModel
        if (BuildConfig.PLAYBACK_ONLY) {
            if (tvModel.tv.playerType != PlayerType.IPTV) {
                tvModel.tv = tvModel.tv.copy(playerType = PlayerType.IPTV)
            }
        } else {
            val stableSource = SP.getStableSources().firstOrNull { it.id == tvModel.tv.id }
            if (stableSource != null) {
                tvModel.tv = tvModel.tv.copy(
                    playerType = stableSource.playerType,
                    videoIndex = stableSource.videoIndex
                )
                tvModel.setVideoIndex(stableSource.videoIndex)
            }
        }
        Log.i(TAG, "playInternal: ${tvModel.tv.title} url=${tvModel.getVideoUrl()} gen=$generation")

        // WebView 模块已移除：强制按 IPTV 处理
        if (tvModel.tv.playerType == PlayerType.WEBVIEW) {
            Log.w(TAG, "WEBVIEW channel coerced to IPTV: ${tvModel.tv.title}")
            tvModel.tv = tvModel.tv.copy(playerType = PlayerType.IPTV)
        }

        // IPTV
        try {
            binding.playerView.visibility = View.VISIBLE
            // 对齐 GitHub IPTV：ALWAYS；起播慢时可见转圈（比 WHEN_PLAYING 更贴近上游）
            binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            binding.playerView.bringToFront()
            updatePlayerViewLayout()
            binding.playerView.requestFocus()

            if (isStalePlay(generation)) return

            val videoUrl = tvModel.tv.uris.getOrNull(tvModel.videoIndexValue)
            if (videoUrl.isNullOrBlank()) {
                tvModel.setErrInfo(R.string.play_error.getString())
                return
            }
            isPreparing = true
            dbg("playInternal: ${tvModel.tv.title} #${tvModel.tv.number} url=$videoUrl gen=$generation")
            startExoPlayback(tvModel, generation, videoUrl)
        } catch (t: Throwable) {
            isPreparing = false
            dbg("playInternal fatal: ${t.message}")
            if (!isStalePlay(generation)) {
                try {
                    tvModel.setErrInfo(R.string.play_error.getString())
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 过高清/超高码率：不 prepare，直接换线/换台，避免卡死无法切台。
     */
    @OptIn(UnstableApi::class)
    private fun skipHeavyOrFailedStream(tvModel: TVModel, generation: Int, toastMsg: String) {
        if (isStalePlay(generation)) return
        abortCurrentPlayback("skip-heavy")
        consecutiveHeavySkips++
        try {
            if (isAdded && context != null) {
                Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
        }
        if (consecutiveHeavySkips >= maxHeavySkips) {
            consecutiveHeavySkips = 0
            tvModel.setErrInfo(R.string.play_error.getString())
            return
        }
        handler.postDelayed({
            if (pendingPlayModel != null) return@postDelayed
            if (!isStalePlay(generation) && playGeneration != generation) {
                // 用户已另选台
                return@postDelayed
            }
            try {
                if (!tvModel.isLastVideo()) {
                    tvModel.nextVideo()
                    tvModel.confirmVideoIndex()
                    play(tvModel, force = true)
                } else {
                    val next = viewModel.groupModel.getNext()
                    if (next != null && next.tv.id != tvModel.tv.id) {
                        viewModel.groupModel.setCurrent(next, notifyChange = false)
                        play(next, force = true)
                    } else {
                        tvModel.setErrInfo(R.string.play_error.getString())
                        consecutiveHeavySkips = 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "skipHeavy next: ${e.message}")
                tvModel.setErrInfo(R.string.play_error.getString())
            }
        }, 300)
    }

    @OptIn(UnstableApi::class)
    private fun startExoPlayback(
        tvModel: TVModel,
        generation: Int,
        videoUrl: String,
        retried: Boolean = false
    ) {
        if (isStalePlay(generation) || !isAdded || _binding == null) {
            isPreparing = false
            return
        }
        try {
            if (player == null) {
                updatePlayer()
            }
            if (isStalePlay(generation) || player == null) {
                isPreparing = false
                return
            }
            binding.playerView.player = player
            val mediaItem = tvModel.getMediaItem()
            if (mediaItem == null) {
                isPreparing = false
                tvModel.setErrInfo(R.string.play_error.getString())
                return
            }
            val mediaSource = tvModel.getMediaSource()
            val p = player!!
            // GitHub 风格：同实例上换 Media（softStop 已 clear）
            try {
                p.stop()
                p.clearMediaItems()
            } catch (_: Exception) {
            }
            if (mediaSource != null) {
                p.setMediaSource(mediaSource)
            } else {
                p.setMediaItem(mediaItem)
            }
            if (isStalePlay(generation)) {
                isPreparing = false
                return
            }
            p.volume = 1f
            p.prepare()
            p.playWhenReady = true
            preparedGeneration = generation
            isPreparing = false
            consecutiveHeavySkips = 0
            dbg("IPTV prepare ok: ${tvModel.tv.title} #${tvModel.tv.number} url=$videoUrl gen=$generation")
            scheduleLoadTimeout(generation)
            handler.removeCallbacks(checkPlaybackRunnable)
            handler.removeCallbacks(stableSourceCheckRunnable)
            handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
            handler.postDelayed(stableSourceCheckRunnable, stablePlaybackDuration)
        } catch (e: Exception) {
            isPreparing = false
            cancelLoadTimeout()
            dbg("startExoPlayback failed: ${e.message} retried=$retried")
            if (!isStalePlay(generation) && !retried) {
                abortCurrentPlayback("prepare-fail")
                updatePlayer()
                startExoPlayback(tvModel, generation, videoUrl, retried = true)
            } else if (!isStalePlay(generation)) {
                tvModel.setErrInfo(R.string.play_error.getString())
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun updateSource() {
        tvModel?.let { model ->
            player?.run {
                stop()
                clearMediaItems()
                play(model)
            }
        }
    }

    private fun isTouchScreenDevice(): Boolean {
        val context = context ?: return false
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    @OptIn(UnstableApi::class)
    class PlayerMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            ).toMutableList()
            if (infos.isEmpty()) return infos

            // 仅 API23 老机允许整表软解；电视上 4K HEVC 绝不能强制 c2.android.hevc 软解
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                val softwareCodecs = infos.filter { !it.hardwareAccelerated }
                if (softwareCodecs.isNotEmpty()) {
                    Log.d(TAG, "API 23 detected, using software codecs for $mimeType")
                    return softwareCodecs.toMutableList()
                }
            }
            // 手机可软解；电视/纯播放始终优先硬解
            if (SP.softDecode && !BuildConfig.PLAYBACK_ONLY) {
                val softwareCodecs = infos.filter { !it.hardwareAccelerated }
                if (softwareCodecs.isNotEmpty()) {
                    return softwareCodecs.toMutableList()
                }
            }

            // 音视频统一硬件优先、软解垫底，避免视频 HW + 音频 SW 时钟域分裂导致音画不同步
            if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
                val hw = infos.filter { it.hardwareAccelerated }
                val sw = infos.filter { !it.hardwareAccelerated }
                if (hw.isNotEmpty()) {
                    return (hw + sw).toMutableList()
                }
                return infos
            }
            return infos
        }
    }

    @OptIn(UnstableApi::class)
    fun getCurrentResolution(): String? {
        return if (tvModel?.tv?.playerType == PlayerType.IPTV) {
            player?.videoSize?.let { videoSize ->
                if (videoSize.width > 0 && videoSize.height > 0) "${videoSize.width}x${videoSize.height}" else null
            }
        } else {
            null // WebView 源无分辨率信息
        }
    }

    fun showVolume(visibility: Int) {
        binding.icon.visibility = visibility
        binding.volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = requireContext()
        binding.volume.progress = progress
        binding.icon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (volume) {
                    if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
                } else {
                    R.drawable.light_mode_24px
                }
            )
        )
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private val hideVolumeRunnable = Runnable {
        binding.icon.visibility = View.GONE
        binding.volume.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == false) {
            try {
                player?.seekToDefaultPosition()
            } catch (_: Exception) {
            }
            player?.prepare()
            player?.play()
        }
        // 确保定时器运行
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
    }

    override fun onPause() {
        super.onPause()
        if (!SP.enableScreenOffAudio && player != null) {
            player?.pause()
            Log.d(TAG, "Paused player due to SP.enableScreenOffAudio=false")
        }
        lastPauseTime = System.currentTimeMillis()
    }

    // 添加广播接收器
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (!SP.enableScreenOffAudio && player != null) {
                    player?.pause()
                    Log.d(TAG, "Paused player on SCREEN_OFF in ${if (isInPictureInPictureMode) "PiP" else "Full-Screen"} mode")
                }
            } else if (intent.action == Intent.ACTION_SCREEN_ON) {
                if (!SP.enableScreenOffAudio && player != null) {
                    player?.playWhenReady = true
                    Log.d(TAG, "Resumed player on SCREEN_ON in ${if (isInPictureInPictureMode) "PiP" else "Full-Screen"} mode")
                }
            }
        }
    }

    // 在 onCreate 中注册
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            requireActivity().registerReceiver(screenReceiver, filter)
            Log.d(TAG, "Screen broadcast receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen broadcast receiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        abortCurrentPlayback("onDestroy")
        try {
            requireActivity().unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.removeCallbacks(playLatestRunnable)
        abortCurrentPlayback("onDestroyView")
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}