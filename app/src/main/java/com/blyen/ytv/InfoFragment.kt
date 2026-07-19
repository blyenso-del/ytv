package com.blyen.ytv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginStart
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.blyen.ytv.databinding.InfoBinding
import com.blyen.ytv.models.TVModel


class InfoFragment : Fragment() {
    private var _binding: InfoBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 5000

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = InfoBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as YTVApplication

        binding.info.layoutParams.width = application.px2Px(binding.info.layoutParams.width)
        binding.info.layoutParams.height = application.px2Px(binding.info.layoutParams.height)

        val layoutParams = binding.info.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.bottomMargin = application.px2Px(binding.info.marginBottom)
        binding.info.layoutParams = layoutParams

        binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
        var padding = application.px2Px(binding.logo.paddingTop)
        binding.logo.setPadding(padding, padding, padding, padding)
        binding.main.layoutParams.width = application.px2Px(binding.main.layoutParams.width)
        padding = application.px2Px(binding.main.paddingTop)
        binding.main.setPadding(padding, padding, padding, padding)

        val layoutParamsMain = binding.main.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsMain.marginStart = application.px2Px(binding.main.marginStart)
        binding.main.layoutParams = layoutParamsMain

        val layoutParamsDesc = binding.desc.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsDesc.topMargin = application.px2Px(binding.desc.marginTop)
        binding.desc.layoutParams = layoutParamsDesc

        binding.title.textSize = application.px2PxFont(binding.title.textSize)
        binding.desc.textSize = application.px2PxFont(binding.desc.textSize)

        binding.container.layoutParams.width = application.shouldWidthPx()
        binding.container.layoutParams.height = application.shouldHeightPx()

        _binding!!.root.visibility = View.GONE
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as MainActivity).ready()
    }

    fun show(tvModel: TVModel) {
        // TODO make sure attached
        if (!isAdded) {
            Log.e(TAG, "Fragment not attached to a context.")
            return
        }

        val tv = tvModel.tv

        val context = requireContext()

        binding.title.text = tv.title

        val width = 300
        val height = 180
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val channelNum = if (tv.number == -1) tv.id.plus(1) else tv.number
        var size = 150f
        if (channelNum > 99) {
            size = 100f
        }
        if (channelNum > 999) {
            size = 75f
        }
        val paint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.title_blur)
            textSize = size
            textAlign = Paint.Align.CENTER
        }
        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(channelNum.toString(), x, y, paint)
        // 远程 logo 加载已移除
        binding.logo.setImageBitmap(bitmap)

        binding.desc.text = "精彩節目"

        handler.removeCallbacks(removeRunnable)
        view?.visibility = View.VISIBLE
        handler.postDelayed(removeRunnable, delay)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(removeRunnable, delay)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(removeRunnable)
    }

    private val removeRunnable = Runnable {
        view?.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "InfoFragment"
    }
}