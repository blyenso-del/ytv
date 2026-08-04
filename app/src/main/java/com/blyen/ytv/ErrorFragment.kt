package com.blyen.ytv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.blyen.ytv.databinding.ErrorBinding

class ErrorFragment : Fragment() {
    private var _binding: ErrorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ErrorBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as YTVApplication

        binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
        binding.logo.layoutParams.height = application.px2Px(binding.logo.layoutParams.height)

        val layoutParams = binding.msg.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = application.px2Px(binding.msg.marginTop)
        binding.msg.layoutParams = layoutParams

        binding.msg.textSize = application.px2PxFont(binding.msg.textSize)

        _binding = ErrorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 错误页不消费触摸（黑底透明），转发给 MainActivity 手势检测：
        // 否则错误页显示时（PlayerFragment 被 hide）触摸事件无法到达 gestureDetector，
        // 双击打开菜单会失效
        view.setOnTouchListener { _, event ->
            (activity as? MainActivity)?.gestureDetector?.onTouchEvent(event) ?: false
            true
        }
    }

    fun setMsg(msg: String) {
        if (_binding != null) {
            binding.msg.text = msg
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ErrorFragment"
    }
}