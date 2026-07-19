package com.blyen.ytv

import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.system.exitProcess

class YTVExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        Log.e(
            TAG,
            "APP: ${context.appVersionName}, PRODUCT: ${Build.PRODUCT}, DEVICE: ${Build.DEVICE}, " +
                "MODEL: ${Build.MODEL}, SDK: ${Build.VERSION.SDK_INT}\n" +
                "Thread: ${t.name}\nException: ${e.message}\n" +
                Log.getStackTraceString(e)
        )
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    companion object {
        private const val TAG = "YTVException"
    }
}
