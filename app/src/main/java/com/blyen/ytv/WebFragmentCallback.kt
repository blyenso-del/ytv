package com.blyen.ytv

interface WebFragmentCallback {
    fun onPlaybackStarted()
    fun onPlaybackStopped()
    fun onPlaybackError(error: String)
}
