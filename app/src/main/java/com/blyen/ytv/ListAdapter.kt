package com.blyen.ytv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blyen.ytv.databinding.ListItemBinding
import com.blyen.ytv.models.TVListModel
import com.blyen.ytv.models.TVModel

// 改名為 TVListAdapter 避免與 ListAdapter 類名衝突
class TVListAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var listener: ItemListener
) : androidx.recyclerview.widget.ListAdapter<TVModel, TVListAdapter.ViewHolder>(TVModelDiffCallback()) {

    private var isVisible = false // 改名為 isVisible 避免衝突
    private var focused: View? = null
    private var focusRunnable: Runnable? = null
    private val application = context.applicationContext as YTVApplication

    interface ItemListener {
        fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean)
        fun onItemClicked(position: Int, type: String = "list")
        fun onKey(listAdapter: TVListAdapter, keyCode: Int): Boolean
    }

    inner class ViewHolder(val binding: ListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private var cachedBitmap: Bitmap? = null

        @SuppressLint("ClickableViewAccessibility")
        fun bind(tvModel: TVModel) {
            val view = binding.root
            view.isFocusable = true
            view.isFocusableInTouchMode = true

            like(tvModel.like.value as Boolean)

            binding.heart.setOnClickListener {
                tvModel.setLike(!(tvModel.like.value as Boolean))
                like(tvModel.like.value as Boolean)
            }

            view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                listener.onItemFocusChange(tvModel, hasFocus)
                if (hasFocus) {
                    focus(true)
                    focused = view
                    isVisible = true
                } else {
                    focus(false)
                }
            }

            view.setOnClickListener {
                listener.onItemClicked(adapterPosition)
            }

            view.setOnTouchListener { v, event ->
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!v.hasFocus()) {
                            v.requestFocus()
                        }
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }

            view.setOnKeyListener { _, keyCode, event ->
                if (event?.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> if (adapterPosition == 0) {
                            val p = itemCount - 1
                            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
                            recyclerView.postDelayed({
                                val v = recyclerView.findViewHolderForAdapterPosition(p)
                                v?.itemView?.isSelected = true
                                v?.itemView?.requestFocus()
                                if (v != null) {
                                    Log.d(TAG, "ListAdapter: Focused on position $p")
                                } else {
                                    Log.w(TAG, "ListAdapter: ViewHolder not found for position $p")
                                    // 回退到 toPosition 确保焦点
                                    this@TVListAdapter.toPosition(p)
                                }
                            }, 0)
                            true
                        } else false
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (adapterPosition == itemCount - 1) {
                            val p = 0
                            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
                            recyclerView.postDelayed({
                                val v = recyclerView.findViewHolderForAdapterPosition(p)
                                v?.itemView?.isSelected = true
                                v?.itemView?.requestFocus()
                                if (v != null) {
                                    Log.d(TAG, "ListAdapter: Focused on position $p")
                                } else {
                                    Log.w(TAG, "ListAdapter: ViewHolder not found for position $p")
                                    this@TVListAdapter.toPosition(p)
                                }
                            }, 0)
                            true
                        } else false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            tvModel.setLike(!(tvModel.like.value as Boolean))
                            like(tvModel.like.value as Boolean)
                            true
                        }
                        else -> listener.onKey(this@TVListAdapter, keyCode)
                    }
                } else false
            }

            bindTitle(tvModel.tv.title)
            bindImage(tvModel)
        }

        fun bindTitle(text: String) {
            binding.title.text = text
        }

        fun bindImage(tvModel: TVModel) {
            val tv = tvModel.tv
            val width = 300
            val height = 180

            if (cachedBitmap == null) {
                cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(cachedBitmap!!)
                val channelNum = if (tv.number == -1) tv.id.plus(1) else tv.number
                var size = 150f
                if (channelNum > 99) size = 90f
                if (channelNum > 999) size = 75f
                val paint = Paint().apply {
                    color = ContextCompat.getColor(context, R.color.title_blur)
                    textSize = size
                    textAlign = Paint.Align.CENTER
                }
                val x = width / 2f
                val y = height / 2f - (paint.descent() + paint.ascent()) / 2
                canvas.drawText(channelNum.toString(), x, y, paint)
            }

            // 远程 logo 加载已移除，仅显示频道号占位图
            binding.icon.setImageBitmap(cachedBitmap)
        }

        fun focus(hasFocus: Boolean) {
            if (hasFocus) {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.root.setBackgroundResource(R.color.focus)
            } else {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.title_blur))
                binding.root.setBackgroundResource(R.color.listcolor)
            }
        }

        fun like(liked: Boolean) {
            binding.heart.setImageDrawable(
                ContextCompat.getDrawable(
                    context,
                    if (liked) R.drawable.baseline_favorite_24 else R.drawable.baseline_favorite_border_24
                )
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ListItemBinding.inflate(LayoutInflater.from(context), parent, false)
        binding.icon.layoutParams.width = application.px2Px(binding.icon.layoutParams.width)
        binding.icon.layoutParams.height = application.px2Px(binding.icon.layoutParams.height)
        binding.icon.setPadding(application.px2Px(binding.icon.paddingTop))
        binding.title.layoutParams.width = application.px2Px(binding.title.layoutParams.width)
        binding.title.layoutParams.height = application.px2Px(binding.title.layoutParams.height)
        binding.title.textSize = application.px2PxFont(binding.title.textSize)
        binding.heart.layoutParams.width = application.px2Px(binding.heart.layoutParams.width)
        binding.heart.layoutParams.height = application.px2Px(binding.heart.layoutParams.height)
        binding.heart.setPadding(application.px2Px(binding.heart.paddingTop))
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun update(listModel: TVListModel) {
        submitList(listModel.tvList.value?.toList() ?: emptyList())
    }

    fun toPosition(position: Int) {
        focusRunnable?.let { recyclerView.removeCallbacks(it) }
        focusRunnable = Runnable {
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPosition(position)
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            if (viewHolder != null) {
                viewHolder.itemView.requestFocus()
                (viewHolder as ViewHolder).focus(true)
                // 确保其他项取消高亮
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i)
                    if (child != viewHolder.itemView) {
                        val otherHolder = recyclerView.getChildViewHolder(child) as? ViewHolder
                        otherHolder?.focus(false)
                    }
                }
                Log.d(TAG, "ListAdapter: Focused on position $position")
            } else {
                Log.w(TAG, "ListAdapter: ViewHolder not found for position $position, retrying")
                recyclerView.postDelayed({ toPosition(position) }, 50)
            }
        }
        recyclerView.post(focusRunnable!!)
    }

    fun focusable(able: Boolean) {
        recyclerView.isFocusable = able
        recyclerView.isFocusableInTouchMode = able
        recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    // 添加公開方法控制 isVisible
    fun setVisible(visible: Boolean) {
        isVisible = visible
    }

    companion object {
        private const val TAG = "ListAdapter"
    }
}

class TVModelDiffCallback : DiffUtil.ItemCallback<TVModel>() {
    override fun areItemsTheSame(oldItem: TVModel, newItem: TVModel): Boolean {
        return oldItem.tv.id == newItem.tv.id
    }

    override fun areContentsTheSame(oldItem: TVModel, newItem: TVModel): Boolean {
        return oldItem.tv.title == newItem.tv.title &&
                oldItem.like.value == newItem.like.value &&
                oldItem.tv.number == newItem.tv.number &&
                oldItem.tv.logo == newItem.tv.logo
    }
}