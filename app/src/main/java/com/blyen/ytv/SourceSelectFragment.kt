package com.blyen.ytv

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blyen.ytv.data.PlayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.regex.Pattern
import androidx.appcompat.widget.SwitchCompat
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import com.blyen.ytv.models.TVModel
import androidx.core.content.ContextCompat

class SourceSelectFragment : Fragment() {

    private lateinit var viewModel: MainViewModel
    private lateinit var channelNameText: TextView
    private lateinit var sourceCountText: TextView
    private lateinit var currentSourceText: TextView
    private lateinit var sourceRecyclerView: RecyclerView
    private lateinit var sourceAdapter: SourceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val hideDelay = 30_000L // 10秒后自动隐藏
    private var updateJobs: MutableList<Job> = mutableListOf()
    private lateinit var onSourceSelected: (Int, Boolean) -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = (requireActivity() as MainActivity).getViewModel()
    }

    @SuppressLint("GestureBackNavigation")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_source_select, container, false)
        channelNameText = view.findViewById(R.id.channel_name)
        sourceCountText = view.findViewById(R.id.source_count)
        currentSourceText = view.findViewById(R.id.current_source)
        sourceRecyclerView = view.findViewById(R.id.source_list)

        // 设置 RecyclerView 属性
        sourceRecyclerView.isFocusable = true
        sourceRecyclerView.isFocusableInTouchMode = true
        sourceRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // 设置根布局点击监听
        val rootView = view.findViewById<LinearLayout>(R.id.source_select_root)
        val contentView = view.findViewById<LinearLayout>(R.id.content)
        rootView.setOnClickListener {
            val contentRect = android.graphics.Rect()
            contentView.getGlobalVisibleRect(contentRect)
            val x = it.x.toInt()
            val y = it.y.toInt()
            if (!contentRect.contains(x, y)) {
                hideSelf()
            }
        }

        // 初始化 onSourceSelected
        onSourceSelected = { index, isChecked ->
            val tvModel = viewModel.groupModel.getCurrent()
            if (tvModel != null) {
                if (index == tvModel.videoIndexValue) {
                    tvModel.sourceUp()
                    (requireActivity() as MainActivity).playerFragment.switchSource(tvModel)
                    sourceAdapter.updateSelection(tvModel.videoIndexValue)
                } else {
                    tvModel.setVideoIndex(index)
                    tvModel.confirmVideoIndex()
                    (requireActivity() as MainActivity).playerFragment.switchSource(tvModel)
                    sourceAdapter.updateSelection(index)
                }
                hideSelf()
            } else {
                Log.w("SourceSelectFragment", "onSourceSelected: tvModel is null, skipping source selection")
            }
        }

        // 初始化 RecyclerView
        sourceRecyclerView.layoutManager = LinearLayoutManager(context)
        sourceAdapter = SourceAdapter(emptyList(), viewModel, requireContext(), onSourceSelected)
        sourceRecyclerView.adapter = sourceAdapter

        // 设置按键监听
        view.isFocusableInTouchMode = true
        view.isFocusable = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        hideSelf()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        scheduleAutoHide()
                        false
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        scheduleAutoHide() // 重置自动隐藏计时
                        false
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        val currentPosition = (sourceRecyclerView.layoutManager as LinearLayoutManager)
                            .findFirstCompletelyVisibleItemPosition()
                        if (currentPosition >= 0) {
                            onSourceSelected(currentPosition, true)
                        }
                        scheduleAutoHide()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        return view
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            Log.d("SourceSelectFragment", "Fragment shown, updating UI")
            updateUI()
            startDynamicUpdate()
            scheduleAutoHide()
            initializeFocus()
        } else {
            updateJobs.forEach { it.cancel() }
            updateJobs.clear()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.channelsOk.observe(viewLifecycleOwner) { loaded ->
            if (loaded) {
                Log.d("SourceSelectFragment", "Channels loaded, updating UI")
                updateUI()
                startDynamicUpdate()
                scheduleAutoHide()
                initializeFocus()
            } else {
                Log.d("SourceSelectFragment", "Channels not loaded yet")
                channelNameText.text = getString(R.string.loading)
                sourceCountText.text = getString(R.string.total_sources, 0)
                currentSourceText.text = getString(R.string.current_source, 0)
            }
        }

        if (viewModel.channelsOk.value == true) {
            Log.d("SourceSelectFragment", "Channels already loaded, updating UI")
            updateUI()
            startDynamicUpdate()
            scheduleAutoHide()
            initializeFocus()
        }
    }

    private fun initializeFocus() {
        view?.post {
            if (!isAdded || !isVisible) return@post
            sourceRecyclerView.isFocusable = true
            sourceRecyclerView.isFocusableInTouchMode = true
            val selectedIndex = viewModel.groupModel.getCurrent()?.videoIndexValue ?: 0
            sourceRecyclerView.smoothScrollToPosition(selectedIndex)
            view?.postDelayed({
                if (!isAdded || !isVisible) return@postDelayed
                val holder = sourceRecyclerView.findViewHolderForAdapterPosition(selectedIndex)
                if (holder != null) {
                    val switch = holder.itemView.findViewById<SwitchCompat>(R.id.source_switch)
                    switch.isFocusable = true
                    switch.isFocusableInTouchMode = true
                    switch.requestFocus()
                    Log.d("SourceSelectFragment", "Focus set on SwitchCompat at position $selectedIndex")
                } else {
                    sourceRecyclerView.requestFocus()
                    Log.w("SourceSelectFragment", "ViewHolder not found for position $selectedIndex, fallback to RecyclerView")
                }
            }, 500) // 增加延迟到500ms
        }
    }

    private fun updateUI() {
        val tvModel = (requireActivity() as MainActivity).playerFragment.tvModel ?: run {
            Log.w("SourceSelectFragment", "updateUI: PlayerFragment's tvModel is null")
            channelNameText.text = getString(R.string.no_channel_data)
            sourceCountText.text = getString(R.string.total_sources, 0)
            currentSourceText.text = getString(R.string.current_source, 0)
            sourceAdapter.updateSources(emptyList())
            return
        }
        val sources = tvModel.tv.uris.filter { it.isNotBlank() }
        Log.d("SourceSelectFragment", "updateUI: Channel=${tvModel.tv.title}, uris=${tvModel.tv.uris}, filtered sources=$sources, videoIndexValue=${tvModel.videoIndexValue}")
        channelNameText.text = getString(R.string.channel_name_with_tip, tvModel.tv.title)
        sourceCountText.text = getString(R.string.total_sources, sources.size)
        currentSourceText.text = getString(R.string.current_source, tvModel.videoIndexValue + 1)
        sourceAdapter.updateSources(sources.mapIndexed { index, url ->
            SourceInfo(
                index + 1,
                url,
                getString(R.string.unknown),
                -1,
                SP.getStableSources().any { it.uris.contains(url) },
                index == tvModel.videoIndexValue
            )
        })
    }

    private fun startDynamicUpdate() {
        updateJobs.forEach { it.cancel() }
        updateJobs.clear()
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        val sources = tvModel.tv.uris.filter { it.isNotBlank() }
        val limitedDispatcher = Dispatchers.IO.limitedParallelism(2)

        sources.forEachIndexed { index, url ->
            val job = lifecycleScope.launch(limitedDispatcher) {
                val resolution = try {
                    fetchResolution(url, index, tvModel)
                } catch (e: Exception) {
                    Log.e("SourceSelectFragment", "fetchResolution: Failed for URL=$url, error=${e.message}")
                    getString(R.string.unknown)
                }
                val ping = try {
                    withContext(Dispatchers.IO) { fetchPing(url) }
                } catch (e: Exception) {
                    Log.e("SourceSelectFragment", "fetchPing: Failed for URL=$url, error=${e.message}")
                    -1
                }
                val isStable = SP.getStableSources().any { it.uris.contains(url) }
                withContext(Dispatchers.Main) {
                    // 获取当前焦点位置
                    val currentFocusPosition = (sourceRecyclerView.layoutManager as LinearLayoutManager)
                        .findFirstCompletelyVisibleItemPosition()
                    // 仅对非焦点项更新
                    if (index != currentFocusPosition) {
                        sourceAdapter.updateSource(index + 1, resolution, ping, isStable)
                    }
                }
            }
            updateJobs.add(job)
        }
    }

    private suspend fun fetchResolution(url: String, index: Int, tvModel: TVModel): String {
        if (url.isBlank()) {
            Log.w("SourceSelectFragment", "fetchResolution: URL is blank")
            return getString(R.string.unknown)
        }

        // 检查缓存
        val cachedResolution = SP.getResolutionCache(url)
        if (cachedResolution != null) {
            Log.d("SourceSelectFragment", "fetchResolution: Cache hit for URL=$url, resolution=$cachedResolution")
            return cachedResolution
        }

        // 仅处理当前播放源
        if (url == tvModel.tv.uris.getOrNull(tvModel.videoIndexValue)) {
            if (tvModel.tv.playerType == PlayerType.IPTV) {
                try {
                    val resolution = withContext(Dispatchers.Main) {
                        (requireActivity() as MainActivity).playerFragment.getCurrentResolution()
                    }
                    if (resolution != null) {
                        val formattedResolution = formatResolution(resolution)
                        Log.d("SourceSelectFragment", "fetchResolution: Current source URL=$url, resolution=$formattedResolution")
                        withContext(Dispatchers.Main) {
                            SP.cacheResolution(url, formattedResolution)
                        }
                        return formattedResolution
                    }
                } catch (e: Exception) {
                    Log.e("SourceSelectFragment", "fetchResolution: Failed to get resolution from PlayerFragment for URL=$url, error=${e.message}")
                    return getString(R.string.unknown)
                }
            } else {
                Log.d("SourceSelectFragment", "fetchResolution: WebView source URL=$url, skipping resolution fetch")
                return getString(R.string.unknown)
            }
        }

        // 非当前播放源返回未知
        return getString(R.string.unknown)
    }

    private fun formatResolution(resolution: String?): String {
        if (resolution == null || !resolution.matches("\\d+x\\d+".toRegex())) {
            return getString(R.string.unknown)
        }
        val (width, height) = resolution.split("x").map { it.toIntOrNull() ?: 0 }
        return when {
            width >= 3840 && height >= 2160 -> "4K"
            width >= 2560 && height >= 1440 -> "2K"
            width >= 1920 && height >= 1080 -> "1080p"
            width >= 1280 && height >= 720 -> "720p"
            width >= 854 && height >= 480 -> "480p"
            width >= 640 && height >= 360 -> "360p"
            width >= 426 && height >= 240 -> "240p"
            else -> getString(R.string.unknown)
        }
    }

    private suspend fun fetchPing(url: String): Int = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            //Log.w("SourceSelectFragment", "fetchPing: URL is blank")
            return@withContext -1
        }
        try {
            val measurements = mutableListOf<Long>()
            repeat(3) {
                val startTime = System.nanoTime()
                val connection = URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.connect()
                val responseCode = connection.responseCode
                connection.disconnect()
                if (responseCode in 200..299) {
                    val durationMs = (System.nanoTime() - startTime) / 1_000_000
                    if (durationMs >= 1) measurements.add(durationMs)
                }
                delay(100)
            }
            if (measurements.isEmpty()) {
                //Log.w("SourceSelectFragment", "fetchPing: No valid measurements for URL=$url")
                return@withContext -1
            }
            val avgPing = measurements.average().toInt()
            //Log.d("SourceSelectFragment", "fetchPing: URL=$url, avgPing=$avgPing ms")

            if (url.endsWith(".m3u8")) {
                try {
                    val content = URL(url).readText()
                    val subUrlPattern = Pattern.compile("""^(?!#)(http[s]?://.*\.ts)$""", Pattern.MULTILINE)
                    val matcher = subUrlPattern.matcher(content)
                    if (matcher.find()) {
                        val segmentUrl = matcher.group(1)
                        val segmentStart = System.nanoTime()
                        val segmentConnection = URL(segmentUrl).openConnection() as java.net.HttpURLConnection
                        segmentConnection.connectTimeout = 3000
                        segmentConnection.readTimeout = 3000
                        segmentConnection.connect()
                        segmentConnection.inputStream.close()
                        segmentConnection.disconnect()
                        val segmentDuration = (System.nanoTime() - segmentStart) / 1_000_000
                        //Log.d("SourceSelectFragment", "fetchPing: Segment URL=$segmentUrl, duration=$segmentDuration ms")
                        return@withContext maxOf(avgPing, segmentDuration.toInt())
                    }
                } catch (e: Exception) {
                    //Log.w("SourceSelectFragment", "fetchPing: Failed to fetch M3U8 segment for URL=$url, error=${e.message}")
                }
            }

            avgPing
        } catch (e: java.net.UnknownHostException) {
            //Log.e("SourceSelectFragment", "fetchPing: DNS resolution failed for URL=$url, error=${e.message}")
            -1
        } catch (e: java.net.SocketTimeoutException) {
            //Log.e("SourceSelectFragment", "fetchPing: Timeout for URL=$url, error=${e.message}")
            -1
        } catch (e: Exception) {
            //Log.e("SourceSelectFragment", "fetchPing: Failed for URL=$url, error=${e.message}")
            -1
        }
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isAdded && !isHidden && sourceRecyclerView.findFocus() == null) {
                hideSelf()
            }
        }, hideDelay)
    }

    fun hideSelf() {
        if (isAdded && !isHidden) {
            updateJobs.forEach { it.cancel() }
            updateJobs.clear()
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this)
                .commitAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateJobs.forEach { it.cancel() }
        updateJobs.clear()
        handler.removeCallbacksAndMessages(null)
    }
}

data class SourceInfo(
    val index: Int,
    val url: String,
    val resolution: String,
    val ping: Int,
    val isStable: Boolean,
    val isSelected: Boolean = false
)

class SourceAdapter(
    private var sources: List<SourceInfo>,
    private val viewModel: MainViewModel,
    private val context: android.content.Context,
    private val onSourceSelected: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<SourceAdapter.SourceViewHolder>() {

    inner class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val sourceSwitch: SwitchCompat = itemView.findViewById(R.id.source_switch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source, parent, false)
        return SourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val source = sources[position]
        val pingText = if (source.ping == -1) context.getString(R.string.timeout) else "${source.ping}ms"
        // 设置 ping 值的底色和文字颜色
        val (backgroundColor, textColor) = when {
            source.ping == -1 -> Pair(0xFFFF0000.toInt(), 0xFFFFFFFF.toInt()) // 红色底，白色字
            source.ping <= 500 -> Pair(0xFF4CAF50.toInt(), 0xFFFFFFFF.toInt()) // 绿色底，白色字
            source.ping <= 1000 -> Pair(0xFFFFFFFF.toInt(), 0xFF000000.toInt()) // 白色底，黑色字
            else -> Pair(0xFFFFEB3B.toInt(), 0xFFFFFFFF.toInt()) // 黄色底，白色字
        }

        // 创建 SpannableString 设置 ping 部分的颜色
        val text = context.getString(R.string.source_info, source.index, source.resolution, pingText)
        val spannable = SpannableString(text)
        val pingLabel = "Ping:"
        val pingStart = text.indexOf(pingLabel) + pingLabel.length
        val pingEnd = text.length // 直接到字符串末尾，避免硬编码长度
        if (pingStart >= pingLabel.length && pingEnd <= text.length) {
            spannable.setSpan(BackgroundColorSpan(backgroundColor), pingStart, pingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(textColor), pingStart, pingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        holder.sourceSwitch.text = spannable
        holder.sourceSwitch.isChecked = source.isSelected
        holder.sourceSwitch.setOnClickListener {
            onSourceSelected(source.index - 1, holder.sourceSwitch.isChecked)
        }

        // 增强焦点文字反馈
        holder.sourceSwitch.setOnFocusChangeListener { _, hasFocus ->
            holder.sourceSwitch.setTextColor(
                ContextCompat.getColor(context, if (hasFocus) R.color.focus else R.color.title_blur)
            )
            if (hasFocus) {
                holder.sourceSwitch.text = spannable // 确保焦点时文字刷新
            }
        }

    }

    override fun getItemCount(): Int = sources.size

    fun updateSources(newSources: List<SourceInfo>) {
        val tvModel = viewModel.groupModel.getCurrent()
        val currentIndex = tvModel?.videoIndexValue ?: -1
        sources = newSources.map { source ->
            source.copy(isSelected = source.index - 1 == currentIndex)
        }
        notifyDataSetChanged()
    }

    fun updateSource(index: Int, resolution: String, ping: Int, isStable: Boolean) {
        val tvModel = viewModel.groupModel.getCurrent()
        val currentIndex = tvModel?.videoIndexValue ?: -1
        val position = sources.indexOfFirst { it.index == index }
        if (position != -1) {
            sources = sources.toMutableList().apply {
                this[position] = SourceInfo(
                    index,
                    sources[position].url,
                    resolution,
                    ping,
                    isStable,
                    index - 1 == currentIndex
                )
            }
            notifyItemChanged(position)
        }
    }

    fun getSourceAt(position: Int): SourceInfo {
        return sources[position]
    }

    // 新增方法：更新选中状态
    fun updateSelection(newSelectedIndex: Int) {
        sources = sources.map { source ->
            source.copy(isSelected = source.index - 1 == newSelectedIndex)
        }
        notifyDataSetChanged()
    }
}