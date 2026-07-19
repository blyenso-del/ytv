package com.blyen.ytv

import android.annotation.SuppressLint
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import com.blyen.ytv.models.TVModel
import androidx.core.view.isVisible
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView


@Suppress("UNUSED_EXPRESSION", "DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var ok = 0
    internal var playerFragment = com.blyen.ytv.PlayerFragment()
    internal val errorFragment = com.blyen.ytv.ErrorFragment()
    internal val loadingFragment = com.blyen.ytv.LoadingFragment()
    internal var infoFragment = com.blyen.ytv.InfoFragment()
    internal var channelFragment = com.blyen.ytv.ChannelFragment()
    internal var timeFragment = com.blyen.ytv.TimeFragment()
    internal var menuFragment = com.blyen.ytv.MenuFragment()
    internal var sourceSelectFragment = com.blyen.ytv.SourceSelectFragment()

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideMenu = 10 * 1000L
    /** 连切只保留最后一次起播请求 */
    private var pendingPlayTv: TVModel? = null
    private var pendingPlayForce = false
    private val playSettleMs = 500L
    private val settlePlayRunnable = Runnable {
        val target = pendingPlayTv
        pendingPlayTv = null
        if (target != null) {
            dispatchPlayToPlayer(target)
        }
    }
    lateinit var gestureDetector: GestureDetector

    private var menuPressCount = 0
    private var lastMenuPressTime = 0L
    private val MENU_TAP_INTERVAL = 500L
    private var lastSwitchTime = 0L
    private val DEBOUNCE_INTERVAL = 2000L
    private var lastBackPressTime = 0L
    private val BACK_PRESS_INTERVAL = 2000L

    private val handleRightRunnable = Runnable {
        if (menuPressCount == 1) { // 仅单次按右键触发 sourceUp
            sourceUp()
        }
        menuPressCount = 0 // 重置计数
    }

    private val handleEnterRunnable = Runnable {
        if (menuPressCount == 1) { // 仅单次按键触发 menuFragment
            showFragment(menuFragment)
            menuActive()
        }
        menuPressCount = 0 // 重置计数
    }

    /** 触摸：双击打开菜单（设置页已移除） */
    private val handleTapRunnable = Runnable {
        if (menuPressCount >= 2) {
            showFragment(menuFragment)
            menuActive()
        }
        menuPressCount = 0
    }

    internal lateinit var viewModel: MainViewModel

    private var isSafeToPerformFragmentTransactions = false
    private var isLoadingInputVisible = false

    // 新增：禁用用户输入和画中画标志
    private var isInputDisabled = false

    fun setLoadingInputVisible(visible: Boolean) {
        isLoadingInputVisible = visible
    }

    private var lastSourceUpTime = 0L
    private val sourceUpDebounce = 400L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateFullScreenMode(SP.fullScreenMode)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // 初始化所有 Fragment
        if (savedInstanceState == null) {
            try {
                supportFragmentManager.beginTransaction()
                    .add(R.id.main_browse_fragment, loadingFragment)
                    .add(R.id.main_browse_fragment, playerFragment)
                    .add(R.id.main_browse_fragment, infoFragment)
                    .add(R.id.main_browse_fragment, channelFragment)
                    .add(R.id.main_browse_fragment, menuFragment)
                    .add(R.id.main_browse_fragment, sourceSelectFragment)
                    .hide(infoFragment)
                    .hide(channelFragment)
                    .hide(menuFragment)
                    .hide(sourceSelectFragment)
                    .commitNow()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to add fragments: ${e.message}")
                supportFragmentManager.beginTransaction()
                    .add(R.id.main_browse_fragment, loadingFragment)
                    .add(R.id.main_browse_fragment, playerFragment)
                    .add(R.id.main_browse_fragment, infoFragment)
                    .add(R.id.main_browse_fragment, channelFragment)
                    .add(R.id.main_browse_fragment, menuFragment)
                    .add(R.id.main_browse_fragment, sourceSelectFragment)
                    .hide(infoFragment)
                    .hide(channelFragment)
                    .hide(menuFragment)
                    .hide(sourceSelectFragment)
                    .commit()
            }
        }

        // 设置全屏模式监听器
        YTVApplication.getInstance().setFullScreenModeListener {
            if (playerFragment.isAdded) {
                playerFragment.onFullScreenModeChanged()
            }
        }

        // 设置手势检测
        gestureDetector = GestureDetector(this@MainActivity, GestureListener(this@MainActivity))

        // playTrigger：禁止每次 launch 新协程（连切会叠十几个 play 导致闪退）
        viewModel.playTrigger.observe(this@MainActivity) { tvModel ->
            tvModel?.let { requestPlayChannel(it) }
                ?: Log.w(TAG, "playTrigger received null TvModel")
        }

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // init 内部异步加载；不要在主线程同步等待重解析
                viewModel.init(this@MainActivity)

                // 等频道就绪（最长 20s），期间不阻塞 UI 消息队列以外的逻辑
                val ok = withTimeoutOrNull(20_000) {
                    viewModel.channelsOk.asFlow().first { it }
                    true
                } ?: false

                if (!ok) {
                    Log.w(TAG, "channelsOk wait timed out")
                    showFragment(menuFragment)
                    menuActive()
                    R.string.initialization_error.showToast()
                    return@launch
                }
                Log.d(TAG, "Channels loaded, channelsOk: ${viewModel.channelsOk.value}")

                // 只挂一次 watch，避免重复注册
                watch()
                menuFragment.update()
                menuFragment.updateList(viewModel.groupModel.positionValue)
                val tvModel = viewModel.groupModel.getCurrent()
                    ?: viewModel.listModel.firstOrNull()
                if (tvModel != null) {
                    viewModel.groupModel.setCurrent(tvModel)
                    viewModel.groupModel.setPositionPlaying()
                    viewModel.groupModel.getCurrentList()?.let {
                        it.setPosition(tvModel.listIndex.coerceAtLeast(0))
                        it.setPositionPlaying()
                    }
                    // 不重复 setReady+triggerPlay 若已在播（PlayerFragment 也会去重）
                    hideFragment(loadingFragment)
                    showFragment(playerFragment)
                    playerFragment.setSourceButtonVisibility(true)
                    if (viewModel.playTrigger.value?.tv?.id != tvModel.tv.id) {
                        tvModel.setReady()
                        viewModel.triggerPlay(tvModel)
                    }
                    Log.d(TAG, "channelsOk ready: ${tvModel.tv.title} url=${tvModel.getVideoUrl()}")
                } else {
                    showFragment(menuFragment)
                    menuActive()
                    Log.w(TAG, "No tvModel available, showing MenuFragment")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed: ${e.message}", e)
                showFragment(menuFragment)
                menuActive()
                R.string.initialization_error.showToast()
            }
        }
    }

    fun updateFullScreenMode(isFullScreen: Boolean) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowCompat.setDecorFitsSystemWindows(window, !isFullScreen)
            val params = window.attributes
            if (isFullScreen) {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = params
        } else {
            // API 23-27: 使用传统全屏方式
            if (isFullScreen) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
            } else {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                // 不需要额外设置 FLAG_LAYOUT_STABLE，清除 FLAG_FULLSCREEN 后系统会自动调整内容适应系统栏
            }
        }

        // 设置系统栏行为，兼容低版本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowInsetsController.systemBarsBehavior = if (isFullScreen) {
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        } else {
            window.decorView.systemUiVisibility = if (isFullScreen) {
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE // 恢复默认可见状态
            }
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.requestLayout()
        window.decorView.invalidate()
        Log.d(TAG, "updateFullScreenMode: isFullScreen=$isFullScreen")

        if (isSafeToPerformFragmentTransactions && playerFragment.isAdded && !playerFragment.isInPictureInPictureMode) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                playerFragment.onFullScreenModeChanged()
                playerFragment.view?.findViewById<View>(R.id.player_view)?.let { playerView ->
                    playerView.requestFocus()
                    playerView.requestLayout()
                }
                val displayMetrics = resources.displayMetrics
                Log.d(TAG, "Window size: width=${displayMetrics.widthPixels}, height=${displayMetrics.heightPixels}")
            }
        }
    }

    fun updateMenuSize() {
        menuFragment.updateSize()
    }

    fun updateMenu() {
        val menuFragment = supportFragmentManager.findFragmentByTag("MenuFragment") as? MenuFragment
        menuFragment?.update()
    }

    fun ready() {
        ok++
        if (ok == 2) {
            gestureDetector = GestureDetector(this, GestureListener(this))
            // 确保 Fragment 状态正确
            supportFragmentManager.beginTransaction()
                .hide(menuFragment)
                .hide(sourceSelectFragment)
                .commit()
            viewModel.groupModel.change.observe(this) { _ ->
                if (viewModel.groupModel.tvGroup.value != null) {
                    watch()
                    menuFragment.update()
                }
            }

            viewModel.channelsOk.observe(this) {
                if (it) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        menuFragment.update()
                        val currentGroup = viewModel.groupModel.positionValue
                        menuFragment.updateList(currentGroup)
                        viewModel.groupModel.isInLikeMode =
                            SP.defaultLike && viewModel.groupModel.positionValue == 0
                    }
                }
            }

            if (playerFragment.isAdded && !playerFragment.isHidden) {
                val currentTvModel = viewModel.groupModel.getCurrent()
                if (currentTvModel != null) {
                    playerFragment.play(currentTvModel)
                } else {
                    Log.w(TAG, "No current TV model available")
                }
            }
        }
    }

    private fun <T> LiveData<T>.throttle(durationMs: Long): LiveData<T> {
        val result = MutableLiveData<T>()
        var lastEmission = 0L
        observeForever { value ->
            val now = System.currentTimeMillis()
            if (now - lastEmission >= durationMs) {
                result.value = value
                lastEmission = now
            }
        }
        return result
    }

    private var channelWatchInstalled = false

    private fun watch() {
        // 只挂一次：setCurrent→change 会反复进这里，重复 observe 会在加载中菜单切台时叠爆
        if (channelWatchInstalled) {
            return
        }
        if (viewModel.listModel.isEmpty()) {
            return
        }
        channelWatchInstalled = true
        viewModel.listModel.forEach { tvModel ->
            // 为每个 tvModel 创建独立的防抖实例
            val errInfoThrottled = tvModel.errInfo.throttle(1000)
            errInfoThrottled.observe(this) { _ ->
                if (tvModel.errInfo.value != null && tvModel == viewModel.groupModel.getCurrent()) {
                    hideFragment(loadingFragment)
                    if (tvModel.errInfo.value == "") {
                        hideFragment(errorFragment)
                        showFragment(playerFragment)
                    } else {
                        Log.i(TAG, "${tvModel.tv.title} ${tvModel.errInfo.value.toString()}")
                        hideFragment(playerFragment)
                        errorFragment.setMsg(tvModel.errInfo.value.toString())
                        showFragment(errorFragment)
                    }
                }
            }

            val readyThrottled = tvModel.ready.throttle(1000)
            readyThrottled.observe(this) { _ ->
                if (tvModel.ready.value != null && tvModel == viewModel.groupModel.getCurrent()) {
                    hideFragment(errorFragment)
                    // PLAYBACK_ONLY：起播只走 playTrigger / requestPlayChannel
                    if (!BuildConfig.PLAYBACK_ONLY) {
                        requestPlayChannel(tvModel)
                    }
                    infoFragment.show(tvModel)
                    if (SP.channelNum) {
                        channelFragment.show(tvModel)
                    }
                }
            }

            tvModel.like.observe(this) { _ ->
                if (tvModel.like.value != null && tvModel.tv.id != -1) {
                    val liked = tvModel.like.value as Boolean
                    if (liked) {
                        viewModel.groupModel.getFavoritesList()?.replaceTVModel(tvModel)
                    } else {
                        viewModel.groupModel.getFavoritesList()?.removeTVModel(tvModel.tv.id)
                    }
                    SP.setLike(tvModel.tv.id, liked)
                }
            }
        }
        Log.i(TAG, "watch() installed once for ${viewModel.listModel.size} channels")
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // 新增：禁用用户输入时拦截触摸
        if (isInputDisabled) {
            Log.d(TAG, "Touch input blocked until listModel initialized")
            return true
        }

        if (event != null && menuFragment.isVisible) {
            return super.onTouchEvent(event)
        }
        if (event != null) {
            // 检查是否点击在 btn_source 上，若是则不处理
            val btnSource = playerFragment.view?.findViewById<View>(R.id.btn_source)
            if (btnSource != null && btnSource.isVisible) {
                val buttonRect = android.graphics.Rect()
                btnSource.getGlobalVisibleRect(buttonRect)
                if (buttonRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    return false // 让 PlayerFragment 的 btn_source 处理事件
                }
            }
            gestureDetector.onTouchEvent(event)
            return true
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener(context: Context) :
        GestureDetector.SimpleOnGestureListener() {

        private var screenWidth: Int
        private var screenHeight: Int
        private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

        private var maxVolume = 0

        init {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val displayMetrics = resources.displayMetrics
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }

        override fun onDown(e: MotionEvent): Boolean {
            playerFragment.hideVolumeNow()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return handleTapCount(1) // 单击记 1 次
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val sourceButton = playerFragment.view?.findViewById<View>(R.id.btn_source)
            if (sourceButton != null && sourceButton.isVisible) {
                val buttonRect = android.graphics.Rect()
                sourceButton.getGlobalVisibleRect(buttonRect)
                if (buttonRect.contains(e.x.toInt(), e.y.toInt())) {
                    sourceUp()
                    return true
                }
            }

            // 记录双击
            val currentTime = System.currentTimeMillis()
            val timeSinceLastTap = currentTime - lastMenuPressTime
            if (timeSinceLastTap <= MENU_TAP_INTERVAL) {
                menuPressCount += 2
            } else {
                menuPressCount = 2
            }
            lastMenuPressTime = currentTime

            // 延迟处理，等待可能的后续双击
            handler.removeCallbacks(handleTapRunnable)
            handler.postDelayed(handleTapRunnable, MENU_TAP_INTERVAL)

            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // 节目单已移除
            return
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY
            if (oldX > screenWidth / 3 && oldX < screenWidth * 2 / 3 && abs(newX - oldX) < abs(newY - oldY)) {
                if (velocityY > 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden)) {
                        prev()
                    }
                }
                if (velocityY < 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden)) {
                        next()
                    }
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }

        private var lastScrollTime: Long = 0
        private var decayFactor: Float = 1.0f

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY

            if (oldX < screenWidth / 3) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * decayFactor * 0.2 / screenHeight).toFloat()
                adjustBrightness(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            if (oldX > screenWidth * 2 / 3 && abs(distanceY) > abs(distanceX)) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * maxVolume * decayFactor * 0.2 / screenHeight).toInt()
                adjustVolume(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            return super.onScroll(e1, e2, distanceX, distanceY)
        }

        private fun adjustVolume(deltaVolume: Int) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            var newVolume = currentVolume + deltaVolume

            if (newVolume < 0) {
                newVolume = 0
            } else if (newVolume > maxVolume) {
                newVolume = maxVolume
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            playerFragment.setVolumeMax(maxVolume * 100)
            playerFragment.setVolume(newVolume.toInt() * 100, true)
            playerFragment.showVolume(View.VISIBLE)
        }

        private fun adjustBrightness(deltaBrightness: Float) {
            var brightness = window.attributes.screenBrightness

            brightness += deltaBrightness
            brightness = 0.1f.coerceAtLeast(0.9f.coerceAtMost(brightness))

            val attributes = window.attributes.apply {
                screenBrightness = brightness
            }
            window.attributes = attributes

            playerFragment.setVolumeMax(100)
            playerFragment.setVolume((brightness * 100).toInt())
            playerFragment.showVolume(View.VISIBLE)
        }
    }

    fun onPlayEnd() {
        val tvModel = viewModel.groupModel.getCurrent()!!
        if (SP.repeatInfo) {
            infoFragment.show(tvModel)
            if (SP.channelNum) {
                channelFragment.show(tvModel)
            }
        }
    }

    fun play(position: Int): Boolean {
        return if (position > -1 && position < viewModel.groupModel.getAllList()!!.size()) {
            val prevGroup = viewModel.groupModel.positionValue
            val tvModel = viewModel.groupModel.getPosition(position)

            tvModel?.setReady()
            viewModel.groupModel.setPositionPlaying()
            viewModel.groupModel.getCurrentList()?.setPositionPlaying()

            val currentGroup = viewModel.groupModel.positionValue
            if (currentGroup != prevGroup) {
                menuFragment.updateList(currentGroup)
            }
            true
        } else {
            R.string.channel_not_exist.showToast()
            false
        }
    }

    fun prev() {
        switchChannel(delta = -1)
    }

    fun next() {
        switchChannel(delta = 1)
    }

    /** 换台：台号/列表立刻更新；起播合并到停手后再做（不走叠协程的 triggerPlay） */
    private fun switchChannel(delta: Int) {
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel = if (delta < 0) {
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null) {
                viewModel.groupModel.getPrev(true)
            } else {
                viewModel.groupModel.getPrev()
            }
        } else {
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null) {
                viewModel.groupModel.getNext(true)
            } else {
                viewModel.groupModel.getNext()
            }
        }

        if (tvModel == null) {
            Log.w(TAG, "switchChannel($delta): no channel")
            return
        }
        // 不 notifyChange，避免重复 watch
        viewModel.groupModel.setCurrent(tvModel, notifyChange = false)
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()
        // 轻量 UI：避免每次 hide/show 多 Fragment
        if (errorFragment.isAdded && !errorFragment.isHidden) hideFragment(errorFragment)
        if (loadingFragment.isAdded && !loadingFragment.isHidden) hideFragment(loadingFragment)
        if (playerFragment.isAdded && playerFragment.isHidden) showFragment(playerFragment)

        try {
            infoFragment.show(tvModel)
            if (SP.channelNum) channelFragment.show(tvModel)
        } catch (e: Exception) {
            Log.w(TAG, "info show: ${e.message}")
        }

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }

        // 合并起播：连切只播最后一台
        requestPlayChannel(tvModel)
        Log.d(TAG, "switchChannel($delta) -> ${tvModel.tv.title}")
    }

    /**
     * 菜单选台：与上下键同一 settle 路径，但 settle 更短，加载中也强制换台。
     */
    fun selectChannelFromMenu(tvModel: TVModel) {
        viewModel.groupModel.setCurrent(tvModel, notifyChange = false)
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.let { list ->
            val listItems = list.tvList.value ?: emptyList()
            val idx = listItems.indexOfFirst { m -> m.tv.id == tvModel.tv.id }
            if (idx >= 0) {
                list.setPosition(idx)
                list.setPositionPlaying()
            }
        }
        if (errorFragment.isAdded && !errorFragment.isHidden) hideFragment(errorFragment)
        if (loadingFragment.isAdded && !loadingFragment.isHidden) hideFragment(loadingFragment)
        if (playerFragment.isAdded && playerFragment.isHidden) showFragment(playerFragment)
        try {
            infoFragment.show(tvModel)
            if (SP.channelNum) channelFragment.show(tvModel)
        } catch (_: Exception) {
        }
        // 菜单：100ms 即可起播（比上下连切短），加载中 force 换台
        requestPlayChannel(tvModel, settleMs = 100L, force = true)
        Log.i(TAG, "selectChannelFromMenu -> ${tvModel.tv.title}")
    }

    /** 统一起播入口：取消上一次请求，settleMs 后再 play */
    private fun requestPlayChannel(
        tvModel: TVModel,
        settleMs: Long = playSettleMs,
        force: Boolean = false
    ) {
        viewModel.groupModel.setCurrent(tvModel, notifyChange = false)
        pendingPlayTv = tvModel
        pendingPlayForce = force
        handler.removeCallbacks(settlePlayRunnable)
        if (!playerFragment.isAdded) {
            try {
                supportFragmentManager.beginTransaction()
                    .add(R.id.main_browse_fragment, playerFragment)
                    .commitNowAllowingStateLoss()
            } catch (e: Exception) {
                Log.e(TAG, "add PlayerFragment: ${e.message}", e)
            }
        }
        if (playerFragment.isAdded && playerFragment.isHidden) {
            showFragment(playerFragment)
        }
        handler.postDelayed(settlePlayRunnable, settleMs)
    }

    private fun dispatchPlayToPlayer(tvModel: TVModel) {
        val force = pendingPlayForce
        pendingPlayForce = false
        if (!playerFragment.isAdded) {
            Log.w(TAG, "dispatchPlay: PlayerFragment not added")
            return
        }
        val doPlay = {
            playerFragment.play(tvModel, force = force)
            Log.i(TAG, "dispatchPlay: ${tvModel.tv.title} force=$force")
        }
        if (playerFragment.view?.isAttachedToWindow != true) {
            handler.postDelayed({
                if (playerFragment.isAdded) doPlay()
            }, 100)
            return
        }
        doPlay()
    }

    // 更新 showFragment 方法，确保画中画模式下视图可见
    internal fun showFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }
        val transaction = supportFragmentManager.beginTransaction()
        if (!fragment.isAdded) {
            transaction.add(R.id.main_browse_fragment, fragment)
        } else if (!fragment.isHidden) {
            return
        }
        transaction.show(fragment)
        try {
            transaction.commitNow()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Fragment transaction failed, falling back to commit: ${e.message}")
            transaction.commit()
        }
        fragment.view?.visibility = View.VISIBLE
    }

    private fun hideFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions || !fragment.isAdded || fragment.isHidden) {
            return
        }
        val transaction = supportFragmentManager.beginTransaction()
        transaction.hide(fragment)
        try {
            transaction.commitNow()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Fragment hide transaction failed, falling back to commit: ${e.message}")
            transaction.commit()
        }
    }

    fun sourceUp() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSourceUpTime < sourceUpDebounce) {
            Log.d(TAG, "Debounced sourceUp for ${viewModel.groupModel.getCurrent()?.tv?.title}")
            return
        }
        lastSourceUpTime = currentTime

        var tvModel = viewModel.groupModel.getCurrent()
        if (tvModel == null) {
            Log.w(TAG, "sourceUp: tvModel is null, attempting to fix groupModel")
            if (viewModel.listModel.isNotEmpty()) {
                tvModel = viewModel.listModel[SP.channel.coerceIn(0, viewModel.listModel.size - 1)]
                viewModel.groupModel.setCurrent(tvModel)
                Log.d(TAG, "Fixed groupModel with tvModel: ${tvModel.tv.title}, uris: ${tvModel.tv.uris.size}")
            } else {
                Log.e(TAG, "sourceUp: listModel is empty")
                R.string.no_current_tv_model.showToast()
                return
            }
        }

        val urls = tvModel.tv.uris.filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            Log.w(TAG, "sourceUp: no available sources for ${tvModel.tv.title}")
            R.string.no_available_sources.showToast()
            return
        }
        if (urls.size <= 1) {
            // 单线路：重试当前 URL（错误恢复需要），不要静默 return
            Log.i(TAG, "sourceUp: single source retry for ${tvModel.tv.title}")
            playerFragment.play(tvModel)
            showSourceInfo(1, 1)
            return
        }

        // 只调用一次 nextVideo 和 switchSource
        tvModel.nextVideo()
        tvModel.confirmVideoIndex()
        playerFragment.play(tvModel)
        showSourceInfo(tvModel.videoIndexValue + 1, urls.size)
        Log.d(TAG, "sourceUp: switched to source ${tvModel.videoIndexValue + 1}, uris: ${tvModel.tv.uris.size}")
    }

    private fun showSourceInfo(sourceIndex: Int, totalSources: Int) {
        val toast = Toast.makeText(
            this,
            "线路 $sourceIndex / $totalSources",
            Toast.LENGTH_LONG
        )
        val textView = toast.view?.findViewById<TextView>(android.R.id.message)
        textView?.textSize = 30f
        toast.setGravity(Gravity.CENTER, 0, 0)
        toast.show()

        handler.postDelayed({
            toast.cancel()
        }, 5000)
    }

    fun menuActive() {
        handler.removeCallbacks(hideMenu)
        handler.postDelayed(hideMenu, delayHideMenu)
    }

    private val hideMenu = Runnable {
        if (!isFinishing && !supportFragmentManager.isStateSaved) {
            if (!menuFragment.isHidden) {
                supportFragmentManager.beginTransaction()
                    .hide(menuFragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    fun showTimeFragment() {
        if (SP.time) {
            showFragment(timeFragment)
        } else {
            hideFragment(timeFragment)
        }
    }

    private fun showChannel(channel: Int) {
        if (!menuFragment.isHidden) {
            return
        }
        channelFragment.show(channel)
    }


    private fun channelUp() {
        if ((!menuFragment.isAdded || menuFragment.isHidden)) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        }
    }

    private fun channelDown() {
        if ((!menuFragment.isAdded || menuFragment.isHidden)) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        }
    }

    private fun handleTapCount(tapCount: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTap = currentTime - lastMenuPressTime

        if (timeSinceLastTap <= MENU_TAP_INTERVAL) {
            menuPressCount += tapCount
        } else {
            menuPressCount = tapCount
        }
        lastMenuPressTime = currentTime

        // 延迟处理，等待可能的后续点击
        handler.removeCallbacks(handleTapRunnable)
        handler.postDelayed(handleTapRunnable, MENU_TAP_INTERVAL)
        return true
    }

    @SuppressLint("GestureBackNavigation")
    fun onKey(keyCode: Int): Boolean {
        // 优先检查 SourceSelectFragment 是否可见
        if (sourceSelectFragment.isAdded && sourceSelectFragment.isVisible) {
            when (keyCode) {
                KEYCODE_ESCAPE, KEYCODE_BACK -> {
                    sourceSelectFragment.hideSelf()
                    return true
                }
                KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN -> {
                    // 直接请求 RecyclerView 的焦点导航
                    val recyclerView = sourceSelectFragment.view?.findViewById<RecyclerView>(R.id.source_list)
                    val currentFocus = recyclerView?.findFocus() ?: recyclerView
                    val nextFocus = currentFocus?.focusSearch(
                        if (keyCode == KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                    )
                    nextFocus?.requestFocus()
                    return true
                }
                KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> {
                    // 忽略左右键
                    return true
                }
                KEYCODE_ENTER, KEYCODE_DPAD_CENTER -> {
                    // 触发当前焦点的点击
                    sourceSelectFragment.view?.findFocus()?.performClick()
                    return true
                }
                else -> {
                    // 其他按键分发到 RecyclerView
                    sourceSelectFragment.view?.findViewById<RecyclerView>(R.id.source_list)?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    return true
                }
            }
        }
        when (keyCode) {
            KEYCODE_ESCAPE, KEYCODE_BACK -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    hideFragment(menuFragment)
                    return true
                }
                if (false) {
                    showTimeFragment()
                    return true
                }
                if (channelFragment.isAdded && channelFragment.isVisible) {
                    channelFragment.hideSelf()
                    return true
                }
                if (sourceSelectFragment.isAdded && sourceSelectFragment.isVisible) {
                    sourceSelectFragment.hideSelf()
                    return true
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < BACK_PRESS_INTERVAL) {
                    finishAffinity()
                    return true
                }
                lastBackPressTime = currentTime
                R.string.press_back_exit.showToast()
                return true
            }
            KEYCODE_0, KEYCODE_1, KEYCODE_2, KEYCODE_3, KEYCODE_4,
            KEYCODE_5, KEYCODE_6, KEYCODE_7, KEYCODE_8, KEYCODE_9 -> {
                showChannel(keyCode - 7)
                return true
            }
            KEYCODE_BOOKMARK, KEYCODE_UNKNOWN, KEYCODE_HELP,
            KEYCODE_SETTINGS, KEYCODE_MENU -> {
                // 设置页已移除：MENU/SETTINGS 直接打开频道菜单
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                showFragment(menuFragment)
                menuActive()
                return true
            }
            KEYCODE_DPAD_UP, KEYCODE_CHANNEL_UP -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                if (false) {
                    return false
                }
                channelUp()
                return true
            }

            KEYCODE_DPAD_DOWN, KEYCODE_CHANNEL_DOWN -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                if (false) {
                    return false
                }
                channelDown()
                return true
            }

            KEYCODE_ENTER, KEYCODE_DPAD_CENTER -> {
                if (channelFragment.isAdded && channelFragment.isVisible) {
                    channelFragment.playNow()
                    return true
                }
                // 新增：处理连续按确认键逻辑
                val currentTime = System.currentTimeMillis()
                val timeSinceLastPress = currentTime - lastMenuPressTime

                if (timeSinceLastPress <= 400) { // 400ms 内连续按
                    menuPressCount++
                    if (menuPressCount >= 4) { // 连续按4次，显示 sourceSelectFragment
                        showFragment(sourceSelectFragment)
                        menuPressCount = 0
                        handler.removeCallbacks(handleEnterRunnable) // 取消可能的 menuFragment 显示
                        return true
                    }
                } else {
                    menuPressCount = 1 // 重置计数
                }
                lastMenuPressTime = currentTime

                // 延迟600ms检查是否显示 menuFragment
                handler.removeCallbacks(handleEnterRunnable)
                handler.postDelayed(handleEnterRunnable, 600)
                return true
            }

            KEYCODE_DPAD_LEFT -> {
                if (false) {
                    return false
                }
                // 节目单已移除
                return true
            }

            KEYCODE_DPAD_RIGHT -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                // 单次右键换源（设置入口已移除）
                menuPressCount = 1
                lastMenuPressTime = System.currentTimeMillis()
                handler.removeCallbacks(handleRightRunnable)
                handler.postDelayed(handleRightRunnable, 600)
                return true
            }
        }
        return false
    }

    // 处理主页按钮点击（圆圈虚拟按钮）
    override fun onUserLeaveHint() {
        if (isInputDisabled) {
            Log.d(TAG, "Picture-in-Picture blocked until listModel initialized")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            isTouchScreenDevice() &&
            playerFragment.isAdded && !playerFragment.isHidden) {
            playerFragment.enterPictureInPictureMode()
            Log.d(TAG, "Entering Picture-in-Picture mode via onUserLeaveHint")
        } else {
            Log.d(TAG, "Skipped Picture-in-Picture: SDK=${Build.VERSION.SDK_INT}, isTouchScreen=${isTouchScreenDevice()}, playerFragmentAdded=${playerFragment.isAdded}, playerFragmentHidden=${playerFragment.isHidden}")
            super.onUserLeaveHint()
        }
    }

    // 保留原有 onKeyDown，仅处理返回键
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isInputDisabled) {
            Log.d(TAG, "Key input blocked until listModel initialized, keyCode=$keyCode")
            return true
        }
        if (onKey(keyCode)) {
            return true
        }
        // 不调用 super.onKeyDown，阻止系统默认退出
        return false
    }

    // 新增：触摸屏检测方法，与 PlayerFragment 一致
    private fun isTouchScreenDevice(): Boolean {
        val packageManager = packageManager
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    // 处理画中画模式变化
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            hideFragment(menuFragment)
            hideFragment(channelFragment)
            hideFragment(infoFragment)
            hideFragment(timeFragment)
            hideFragment(errorFragment)
            hideFragment(loadingFragment)
            hideFragment(sourceSelectFragment)
            if (playerFragment.isAdded) {
                playerFragment.enterPictureInPictureMode()
            }
            showFragment(playerFragment)
            Log.d(TAG, "Entered Picture-in-Picture mode")
        } else {
            showFragment(playerFragment)
            if (playerFragment.isAdded) {
                playerFragment.exitPictureInPictureMode()
            }
            findViewById<View>(R.id.main_browse_fragment)?.requestFocus()
            showTimeFragment()
            if (SP.channelNum && viewModel.groupModel.getCurrent() != null) {
                channelFragment.show(viewModel.groupModel.getCurrent()!!)
            }
            Log.d(TAG, "Exited Picture-in-Picture mode, focus requested on main_browse_fragment")
        }
    }

    override fun onResume() {
        super.onResume()
        isSafeToPerformFragmentTransactions = true
        showTimeFragment()
    }

    // 在 onPause 中暂停播放并释放资源
    override fun onPause() {
        super.onPause()
        isSafeToPerformFragmentTransactions = false
    }

    override fun onStop() {
        super.onStop()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (playerFragment.isAdded && playerFragment.player != null && powerManager.isInteractive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                Log.d(TAG, "In Picture-in-Picture mode, skipping player release and process termination")
                return
            }
            playerFragment.player?.stop()
            playerFragment.player?.release()
            playerFragment.player = null
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    fun getViewModel(): MainViewModel {
        return viewModel
    }

    companion object {
        internal const val TAG = "MainActivity"
    }
}