package com.blyen.ytv

import android.content.Context
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blyen.ytv.databinding.MenuBinding
import com.blyen.ytv.models.TVListModel
import com.blyen.ytv.models.TVModel


class MenuFragment : Fragment(), GroupAdapter.ItemListener, TVListAdapter.ItemListener {
    private var _binding: MenuBinding? = null
    private val binding get() = _binding!!

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var listAdapter: TVListAdapter

    private var groupWidth = 0
    private var listWidth = 0

    private lateinit var viewModel: MainViewModel
    /** 当前右侧列表实际展示的分组（焦点可能已变，group position 可能还没跟上） */
    private var displayedListModel: TVListModel? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = MenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        val application = context.applicationContext as YTVApplication
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        groupAdapter = GroupAdapter(context, binding.group, viewModel.groupModel)
        binding.group.adapter = groupAdapter
        binding.group.layoutManager = LinearLayoutManager(context)
        groupWidth = application.px2Px(binding.group.layoutParams.width)
        binding.group.layoutParams.width = if (SP.compactMenu) {
            groupWidth * 2 / 3
        } else {
            groupWidth
        }
        groupAdapter.setItemListener(this)

        listAdapter = TVListAdapter(context, binding.list, this)
        listAdapter.setItemListener(this)
        binding.list.adapter = listAdapter
        binding.list.layoutManager = LinearLayoutManager(context)
        listWidth = application.px2Px(binding.list.layoutParams.width)
        binding.list.layoutParams.width = if (SP.compactMenu) {
            listWidth * 4 / 5
        } else {
            listWidth
        }

        var lastClickTime = 0L
        binding.menu.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime > 1000) {
                hideSelf()
                lastClickTime = currentTime
            }
            (activity as? MainActivity)?.menuActive()
        }

        groupAdapter.focusable(true)
        listAdapter.focusable(true)

        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.post { requestFocus() }

        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true

        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val mainActivity = activity as? MainActivity
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        mainActivity?.menuActive()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        mainActivity?.menuActive()
                    }
                }
            }
        }

        val onTouchListener = View.OnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.requestFocus()
                (activity as? MainActivity)?.menuActive()
                //Log.d(TAG, "Touch on ${v.id}, focus requested")
            }
            false
        }

        binding.group.addOnScrollListener(scrollListener)
        binding.list.addOnScrollListener(scrollListener)
        binding.menu.setOnTouchListener(onTouchListener)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
            view?.post { requestFocus() }
        } else {
            view?.post {
                groupAdapter.visible = false
                listAdapter.setVisible(false)
            }
        }
    }

    private fun getList(): TVListModel? {
        if (!this::viewModel.isInitialized) {
            return null
        }
        if (viewModel.groupModel.getCurrentList() == null) {
            viewModel.groupModel.setPosition(0)
        }
        return viewModel.groupModel.getCurrentList()
    }

    fun update() {
        view?.post {
            groupAdapter.changed()
            getList()?.let { listModel ->
                listAdapter.update(listModel)
                //Log.d(TAG, "MenuFragment: Updated list with ${listModel.tvList.value?.size} items")
            }
        }
    }

    fun updateSize() {
        view?.post {
            binding.group.layoutParams.width = if (SP.compactMenu) {
                groupWidth * 4 / 5
            } else {
                groupWidth
            }
            binding.list.layoutParams.width = if (SP.compactMenu) {
                listWidth * 4 / 5
            } else {
                listWidth
            }
        }
    }

    fun updateList(position: Int) {
        if (!this::viewModel.isInitialized) {
            return
        }
        viewModel.groupModel.setPosition(position)
        SP.positionGroup = position
        viewModel.groupModel.getCurrentList()?.let {
            displayedListModel = it
            listAdapter.update(it)
            listAdapter.toPosition(it.positionPlayingValue)
        }
    }

    private fun hideSelf() {
        if (!isAdded || activity == null || requireActivity().isFinishing) {
            //Log.w(TAG, "hideSelf: Fragment not added or Activity finishing, skipping")
            return
        }
        try {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this)
                .commitAllowingStateLoss()
            //Log.d(TAG, "MenuFragment hidden")
        } catch (e: IllegalStateException) {
            //Log.e(TAG, "hideSelf: Failed to commit transaction", e)
        }
    }

    // GroupAdapter.ItemListener
    override fun onItemFocusChange(listTVModel: TVListModel, hasFocus: Boolean) {
        if (hasFocus) {
            displayedListModel = listTVModel
            // 同步分组 position，避免右侧展示 B 组、getCurrentList 仍是 A 组
            val groups = viewModel.groupModel.tvGroupValue
            val idx = groups.indexOfFirst { it === listTVModel || it.getName() == listTVModel.getName() }
            if (idx >= 0) {
                viewModel.groupModel.setPosition(idx)
            }
            listAdapter.update(listTVModel)
            (activity as? MainActivity)?.menuActive()
        }
    }

    // TVListAdapter.ItemListener
    override fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean) {
    }

    // GroupAdapter.ItemListener
    override fun onItemClicked(position: Int) {
        if (!this::viewModel.isInitialized) return
        updateList(position)
        groupAdapter.focusable(true)
        listAdapter.focusable(false)
        binding.group.requestFocus()
        groupAdapter.scrollToPositionAndSelect(position)
        (activity as? MainActivity)?.menuActive() // 重置计时器
        //Log.d(TAG, "MenuFragment: Group item clicked, focusing on position $position")
    }

    // TVListAdapter.ItemListener
    override fun onItemClicked(position: Int, type: String) {
        if (!this::viewModel.isInitialized) return
        // 优先用右侧列表正在展示的数据源 + adapter 快照，避免 getCurrentList 与画面不一致
        val fromAdapter = listAdapter.currentList.getOrNull(position)
        val listModel = displayedListModel ?: viewModel.groupModel.getCurrentList()
        val tvModel = fromAdapter
            ?: listModel?.getTVModel(position)
            ?: run {
                Log.w(TAG, "onItemClicked: no channel at $position")
                return
            }
        listModel?.setPosition(position)
        listModel?.setPositionPlaying()
        viewModel.groupModel.setPositionPlaying()
        Log.i(TAG, "onItemClicked menu: ${tvModel.tv.title} pos=$position uris=${tvModel.tv.uris.size}")
        (activity as? MainActivity)?.selectChannelFromMenu(tvModel)
        (activity as? MainActivity)?.menuActive()
        // 延后关菜单，避免与起播抢 Fragment 事务
        view?.post { hideSelf() }
    }

    // GroupAdapter.ItemListener 的 onKey
    override fun onKey(keyCode: Int): Boolean {
        val mainActivity = activity as? MainActivity
        mainActivity?.menuActive()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (listAdapter.itemCount == 0) return true
                listAdapter.focusable(true)
                groupAdapter.focusable(false)
                val position = if ((viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0) >= 0 &&
                    (viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0) < listAdapter.itemCount) {
                    viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0
                } else {
                    0 // 默认滚动到顶部
                }
                // 重试机制，最多尝试 3 次，间隔 200ms
                fun trySetFocus(attempt: Int = 1) {
                    binding.list.postDelayed({
                        if (isAdded && isVisible) {
                            (binding.list.layoutManager as? LinearLayoutManager)?.scrollToPosition(position)
                            val holder = binding.list.findViewHolderForAdapterPosition(position)
                            if (holder != null) {
                                holder.itemView.isFocusable = true
                                holder.itemView.isFocusableInTouchMode = true
                                holder.itemView.requestFocus()
                                Log.d(TAG, "Focus set to list item at position $position (attempt $attempt)")
                            } else if (attempt < 3) {
                                Log.d(TAG, "No ViewHolder found at position $position (attempt $attempt), retrying")
                                trySetFocus(attempt + 1)
                            } else {
                                binding.list.isFocusable = true
                                binding.list.isFocusableInTouchMode = true
                                binding.list.requestFocus()
                                Log.d(TAG, "No ViewHolder found at position $position after $attempt attempts, focusing on list")
                            }
                        }
                    }, 200L * attempt)
                }
                trySetFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                binding.group.requestFocus()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                hideSelf()
                return true
            }
        }
        return false
    }

    // TVListAdapter.ItemListener 的 onKey
    override fun onKey(listAdapter: TVListAdapter, keyCode: Int): Boolean {
        (activity as? MainActivity)?.menuActive() // 重置计时器
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                binding.group.requestFocus()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                (activity as? MainActivity)?.menuActive()
                return true
            }
        }
        return false
    }

    fun onVisible() {
        if (viewModel.groupModel.tvGroupValue.size < 2 || viewModel.groupModel.getAllList()?.size() == 0) {
            //Log.w(TAG, "MenuFragment: No data available in group or list")
            return
        }
        val position = viewModel.groupModel.positionPlayingValue
        if (position != viewModel.groupModel.positionValue) {
            updateList(position)
        }
        viewModel.groupModel.getCurrentList()?.let {
            listAdapter.update(it)
            listAdapter.toPosition(it.positionPlayingValue)
            // 延迟请求焦点，确保 RecyclerView 更新完成
            view?.postDelayed({
                if (isAdded && isVisible) {
                    requestFocus()
                    Log.d(TAG, "Delayed focus requested after listAdapter update")
                }
            }, 300) // 100ms 延迟
        } ?: run {
            //Log.w(TAG, "MenuFragment: Current list is null, retrying")
            view?.postDelayed({ if (isAdded) onVisible() }, 1000)
        }
        (activity as MainActivity).menuActive()
    }

    private fun requestFocus() {
        binding.group.isFocusable = true
        binding.group.isFocusableInTouchMode = true
        binding.list.isFocusable = true
        binding.list.isFocusableInTouchMode = true
        if (listAdapter.itemCount > 0) {
            listAdapter.focusable(true)
            groupAdapter.focusable(false)
            binding.list.requestFocus()
            val position = viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0
            listAdapter.toPosition(position)
        } else {
            groupAdapter.focusable(true)
            listAdapter.focusable(false)
            binding.group.requestFocus()
            val groupPosition = viewModel.groupModel.positionValue
            groupAdapter.scrollToPositionAndSelect(groupPosition)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // 添加 OnBackPressedCallback，处理返回键（可选）
        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 可选：自定义返回键逻辑，例如隐藏 MenuFragment
                if (isVisible && isAdded) {
                    hideSelf()
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "MenuFragment"
    }
}