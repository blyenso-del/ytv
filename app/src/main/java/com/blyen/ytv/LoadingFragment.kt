package com.blyen.ytv

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.blyen.ytv.databinding.LoadingBinding

interface LoadingFragmentCallback {
    fun onLoadingCompleted()
}

class LoadingFragment : Fragment() {
    private var _binding: LoadingBinding? = null
    private val binding get() = _binding!!
    private var callback: LoadingFragmentCallback? = null
    private val TAG = "LoadingFragment"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LoadingBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as? YTVApplication
        if (application != null) {
            binding.bar.layoutParams.width = application.px2Px(binding.bar.layoutParams.width)
            binding.bar.layoutParams.height = application.px2Px(binding.bar.layoutParams.height)
        } else {
            binding.bar.layoutParams.width = (100 * resources.displayMetrics.density).toInt()
            binding.bar.layoutParams.height = (20 * resources.displayMetrics.density).toInt()
        }

        binding.bar.visibility = View.VISIBLE
        (requireActivity() as MainActivity).ready()
        Log.d(TAG, "LoadingFragment created with bar only")
        return binding.root
    }

    private fun stopMusicAndSwitchToLive() {
        val activity = activity as? MainActivity
        activity?.let {
            val channelFragment = it.supportFragmentManager.findFragmentByTag("ChannelFragmentTag") as? ChannelFragment
            channelFragment?.playNow()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopMusicAndSwitchToLive()
        }
    }

    fun setCallback(callback: LoadingFragmentCallback) {
        this.callback = callback
    }

    private fun removeFragment() {
        try {
            requireActivity().supportFragmentManager.beginTransaction()
                .remove(this)
                .commitAllowingStateLoss()
            callback?.onLoadingCompleted()
        } catch (e: Exception) {
            Log.e(TAG, "removeFragment error: ${e.message}", e)
        }
    }

    override fun onDestroyView() {
        _binding = null
        callback = null
        super.onDestroyView()
        stopMusicAndSwitchToLive()
    }

    companion object {
        const val TAG = "LoadingFragment"
    }
}