package com.blyen.ytv

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginStart
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blyen.ytv.databinding.GroupItemBinding
import com.blyen.ytv.models.TVGroupModel
import com.blyen.ytv.models.TVListModel


class GroupAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var tvGroupModel: TVGroupModel,
) :
    RecyclerView.Adapter<GroupAdapter.ViewHolder>() {

    private var listener: ItemListener? = null
    private var focused: View? = null
    private var defaultFocused = false
    private var defaultFocus: Int = -1

    var visible = false

    private var first = true

    val application = context.applicationContext as YTVApplication

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = GroupItemBinding.inflate(inflater, parent, false)

        val layoutParams = binding.title.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.marginStart = application.px2Px(binding.title.marginStart)
        layoutParams.bottomMargin = application.px2Px(binding.title.marginBottom)
        binding.title.layoutParams = layoutParams

        binding.title.textSize = application.px2PxFont(binding.title.textSize)

        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        return ViewHolder(context, binding)
    }

    fun focusable(able: Boolean) {
        recyclerView.isFocusable = able
        recyclerView.isFocusableInTouchMode = able
        if (able) {
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        } else {
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val listTVModel = tvGroupModel.getTVListModel(position)!!
        val view = viewHolder.itemView

        if (!defaultFocused && position == defaultFocus) {
            view.requestFocus()
            defaultFocused = true
        }

        val onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            listener?.onItemFocusChange(listTVModel, hasFocus)

            if (hasFocus) {
                viewHolder.focus(true)
                focused = view

                val p = listTVModel.getGroupIndex()
                if (p != tvGroupModel.positionValue) {
                    tvGroupModel.setPosition(p)
                }

            } else {
                viewHolder.focus(false)
            }
        }

        view.onFocusChangeListener = onFocusChangeListener

        view.setOnClickListener { _ ->
            listener?.onItemClicked(position)
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == getItemCount() - 1) {
                    val p = 0
                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                    return@setOnKeyListener true
                }
                return@setOnKeyListener listener?.onKey(keyCode) ?: false
            }
            false
        }

        viewHolder.bindTitle(listTVModel.getName())
    }

    override fun getItemCount() = tvGroupModel.size()

    class ViewHolder(private val context: Context, private val binding: GroupItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindTitle(text: String) {
            val localizedText = when (text) {
                context.getString(R.string.my_favorites) -> context.getString(R.string.my_favorites)
                context.getString(R.string.all_channels) -> context.getString(R.string.all_channels)
                else -> text
            }
            binding.title.text = localizedText
        }

        fun focus(hasFocus: Boolean) {
            if (hasFocus) {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.root.setBackgroundResource(R.color.focus) // 添加焦点背景
            } else {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.title_blur))
                binding.root.setBackgroundResource(R.color.transparent) // 设置透明背景
            }
        }
    }

    fun scrollToPositionAndSelect(position: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let {
            val delay = if (first) {
                100L
            } else {
                first = false
                0
            }

            recyclerView.postDelayed({
                val groupPosition =
                    if (SP.showAllChannels || position == 0) position else position - 1
                it.scrollToPositionWithOffset(groupPosition, 0)

                val viewHolder = recyclerView.findViewHolderForAdapterPosition(groupPosition)
                viewHolder?.itemView?.apply {
                    isSelected = true
                    requestFocus()
                }
            }, delay)
        }
    }

    interface ItemListener {
        fun onItemFocusChange(listTVModel: TVListModel, hasFocus: Boolean)
        fun onItemClicked(position: Int)
        fun onKey(keyCode: Int): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    fun changed() {
        recyclerView.post {
            notifyDataSetChanged()
        }
    }

    companion object {
        private const val TAG = "GroupAdapter"
    }
}

