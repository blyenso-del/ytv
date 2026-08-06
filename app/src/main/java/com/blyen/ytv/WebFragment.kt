package com.blyen.ytv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.fragment.app.Fragment
import android.net.http.SslError
import com.blyen.ytv.data.Global
import com.blyen.ytv.models.TVModel
import java.io.ByteArrayInputStream

/**
 * WebView 播放器：负责播放 webview:// 网页直播源（tv.cctv.com / yangshipin.cn 等）。
 * 由 PlayerFragment.playInternal 的 WEBVIEW 分支挂载到 R.id.web_view 容器。
 * 从 yourtv 移植（仅系统 WebView 分支）。
 */
@Suppress("DEPRECATION")
class WebFragment : Fragment(), WebFragmentCallback {
    private var webView: WebView? = null
    private var tvModel: TVModel? = null
    private val handler = Handler(Looper.myLooper()!!)
    private val scriptMap get() = Global.scriptMap
    private val blockMap get() = Global.blockMap
    private var finished = 0
    private var callback: WebFragmentCallback? = null
    internal var isPlaying = false
    private var playbackStartTime = 0L
    private var lastErrorTime = 0L
    private val errorSuppressionWindow = 2_000L // 2秒去抖窗口

    fun setCallback(callback: WebFragmentCallback) {
        this.callback = callback
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.black))
        }

        try {
            webView = WebView(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    mediaPlaybackRequiresUserGesture = false // 允许无手势自动播放
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString =
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                }
                webChromeClient = object : WebChromeClient() {
                    override fun getDefaultVideoPoster(): Bitmap {
                        return createBitmap(1, 1) // 1x1 透明位图，防黑屏
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null) {
                            Log.d(TAG, "Console: ${consoleMessage.message()} [${consoleMessage.lineNumber()}]")
                            if (consoleMessage.message() == "success") {
                                Log.i(TAG, "${tvModel?.tv?.title} success")
                                tvModel?.tv?.finished?.let {
                                    webView?.evaluateJavascript(it) { res ->
                                        Log.i(TAG, "${tvModel?.tv?.title} finished: $res")
                                    }
                                }
                                tvModel?.setErrInfo("") // 成功：清空错误（watch() 以空串恢复 playerFragment）
                                isPlaying = true
                                playbackStartTime = System.currentTimeMillis()
                                callback?.onPlaybackStarted()
                            }
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }
                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        handler?.proceed() // 无条件放行，直播页自签证书常见
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        val currentTime = System.currentTimeMillis()
                        // 避免短时间内重复触发
                        if (currentTime - lastErrorTime < errorSuppressionWindow) {
                            Log.d(TAG, "Suppressed WebView error (within ${errorSuppressionWindow}ms): ${error?.description}, url=${request?.url}")
                            return
                        }
                        lastErrorTime = currentTime

                        val isMainFrame = request?.isForMainFrame ?: false
                        val errorDesc = error?.description ?: "Unknown error"
                        val url = request?.url?.toString() ?: "Unknown URL"

                        Log.e(TAG, "WebView error: $errorDesc, url=$url, isMainFrame=$isMainFrame, isPlaying=$isPlaying")

                        // 仅主框架错误或未开始播放时标记停止；恢复靠 5s 超时 + PlayerFragment 轮询
                        if (isMainFrame || !isPlaying) {
                            isPlaying = false
                        } else {
                            Log.d(TAG, "Ignored non-main frame error for ${tvModel?.tv?.title}: $errorDesc, url=$url")
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val uri = request?.url
                        // 广告/干扰资源屏蔽：分组级 blockMap + 频道级 tv.block
                        blockMap[tvModel?.tv?.group]?.let {
                            for (i in it) {
                                if (uri?.path?.contains(i) == true) {
                                    Log.i(TAG, "block path ${uri.path}")
                                    return WebResourceResponse("text/plain", "utf-8", null)
                                }
                            }
                        }
                        tvModel?.tv?.block?.let {
                            for (i in it) {
                                if (uri?.path?.contains(i) == true) {
                                    Log.i(TAG, "block path ${uri.path}")
                                    return WebResourceResponse("text/plain", "utf-8", null)
                                }
                            }
                        }
                        if (uri?.path?.endsWith(".css") == true) {
                            return WebResourceResponse(
                                "text/css",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        val path = uri?.path
                        val isImage = path != null && (path.endsWith(".jpg") || path.endsWith(".jpeg")
                            || path.endsWith(".png") || path.endsWith(".gif")
                            || path.endsWith(".webp") || path.endsWith(".svg"))
                        if (request?.isForMainFrame != true && isImage) {
                            return WebResourceResponse(
                                "image/png",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.i(TAG, "onPageStarted $url")
                        super.onPageStarted(view, url, favicon)
                        val jsCode = """
    (() => {
        const style = document.createElement('style');
        style.type = 'text/css';
        style.innerHTML = `
        body {
            margin: 0;
            padding: 0;
            background-color: #000;
            width: 100vw !important;
            height: 100vh !important;
            position: absolute !important;
            left: 0 !important;
            top: 0 !important;
        }
        img:not([role="presentation"]) {
            display: none !important;
        }
        video, iframe, object, embed {
            display: block !important;
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            object-fit: fill !important;
            background-color: transparent !important;
            z-index: 9999 !important;
        }
        `;
        document.head.appendChild(style);
    })();
""".trimIndent()
                        webView?.evaluateJavascript(jsCode, null)
                    }

                    @SuppressLint("UseKtx")
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if ((webView?.progress ?: 0) < 100) {
                            super.onPageFinished(view, url)
                            return
                        }
                        finished++
                        if (finished < 1) {
                            super.onPageFinished(view, url)
                            return
                        }
                        Log.i(TAG, "onPageFinished $finished $url")
                        tvModel?.tv?.started?.let {
                            webView?.evaluateJavascript(it, null)
                            Log.i(TAG, "started")
                        }
                        tvModel?.tv?.script?.let {
                            webView?.evaluateJavascript(it, null)
                            Log.i(TAG, "script")
                        }
                        val uri = Uri.parse(url)
                        var script = scriptMap[uri?.host]
                        if (script == null) {
                            script = R.raw.ahtv1
                        }
                        // 注入站点脚本：读取 raw 资源可能失败（IO/资源缺失），失败时跳过注入不崩溃
                        var s: String? = null
                        try {
                            s = requireContext().resources.openRawResource(script)
                                .bufferedReader()
                                .use { it.readText() }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read raw script $script: ${e.message}")
                        }
                        val scriptContent = s
                        if (scriptContent != null) {
                            // 替换 object-fit 和 body.style.left
                            var modified = scriptContent.replace(
                                "video.style.objectFit = 'contain';",
                                "video.style.objectFit = 'fill';"
                            ).replace(
                                "body.style.left = '100vw';",
                                "body.style.left = '0'; body.style.width = '100vw'; body.style.height = '100vh';"
                            )
                            tvModel?.tv?.id?.let {
                                modified = modified.replace("{id}", "$it")
                            }
                            tvModel?.tv?.selector?.let {
                                modified = modified.replace("{selector}", it)
                            }
                            tvModel?.videoIndexValue?.let {
                                modified = modified.replace("{index}", "$it")
                            }
                            Log.d(TAG, "Modified script: $modified")
                            webView?.evaluateJavascript(modified, null)
                        }
                        // 注入视频播放监听脚本
                        val jsCode = """
    (() => {
        const video = document.querySelector('video');
        if (video && !video.paused && !video.ended) {
            console.log('success');
            return;
        }
        const iframe = document.querySelector('iframe');
        if (iframe && iframe.src && !iframe.hidden) {
            console.log('success');
            return;
        }
        setTimeout(() => {
            const v = document.querySelector('video');
            if (v && !v.paused && !v.ended) {
                console.log('success');
            }
        }, 1000);
    })();
""".trimIndent()
                        webView?.evaluateJavascript(jsCode, null)
                        Log.i(TAG, "Injected enhanced playback listener")
                    }
                }
            }
            // 确保 webView 无父视图后挂载
            webView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                root.addView(it)
            } ?: run {
                Log.e(TAG, "Failed to create WebView, webView is null")
                tvModel?.setErrInfo("WebView creation failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebView: ${e.message}", e)
            webView = null
            tvModel?.setErrInfo("WebView 初始化失败")
        }

        // 页面不可交互：触摸强制转发给 MainActivity 手势检测器（调音量/亮度/换台）
        webView?.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, event ->
                (activity as? MainActivity)?.gestureDetector?.onTouchEvent(event) ?: false
                true // 强制返回 true
            }
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (webView == null) {
            Log.e(TAG, "WebView not initialized")
            tvModel?.setErrInfo("WebView initialization failed")
        }
    }

    fun play(tvModel: TVModel) {
        finished = 0
        if (this.tvModel != tvModel) {
            stopPlayback()
        }
        this.tvModel = tvModel
        val url = tvModel.getVideoUrl()
        Log.i(TAG, "play ${tvModel.tv.id} ${tvModel.tv.title} $url")

        if (webView == null) {
            Log.e(TAG, "WebView is null, cannot play $url")
            tvModel.setErrInfo("WebView not initialized")
            callback?.onPlaybackError("WebView not initialized")
            return
        }
        if (url == null) {
            Log.e(TAG, "No URL for ${tvModel.tv.title}")
            tvModel.setErrInfo("No URL")
            callback?.onPlaybackError("No URL")
            return
        }

        // 5 秒起播超时：未播则尝试回退/重载，并通知 PlayerFragment 换源（不写 errInfo，避免误触错误页）
        handler.postDelayed({
            if (!isPlaying && webView != null) {
                Log.w(TAG, "Playback timeout for ${tvModel.tv.title}, attempting recovery")
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    webView?.reload()
                }
                callback?.onPlaybackError("Playback timeout")
            }
        }, 5_000L)

        webView?.loadUrl(url)
        webView?.visibility = View.VISIBLE
    }

    /** 全屏/布局变化时刷新 WebView 布局（PlayerFragment.onFullScreenModeChanged 调用） */
    fun updateWebViewLayout() {
        webView?.let {
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            it.requestLayout()
        }
    }

    fun stopPlayback() {
        if (isPlaying || playbackStartTime > 0) {
            isPlaying = false
            playbackStartTime = 0L
            callback?.onPlaybackStopped()
            webView?.let {
                it.stopLoading()
                it.loadUrl("about:blank")
            }
            Log.d(TAG, "Stopped WebView playback for ${tvModel?.tv?.title}")
        } else {
            Log.d(TAG, "Skipped stopPlayback, already stopped for ${tvModel?.tv?.title}")
        }
    }

    /** 超时重载：当前未播放时刷新页面（PlayerFragment.onLoadTimeout 调用） */
    fun reloadWebView() {
        webView?.let {
            if (!isPlaying) {
                Log.i(TAG, "Reloading WebView for ${tvModel?.tv?.title}")
                finished = 0
                it.reload()
            }
        }
    }

    override fun onPlaybackStarted() {
        isPlaying = true
        playbackStartTime = System.currentTimeMillis()
        Log.d(TAG, "onPlaybackStarted for ${tvModel?.tv?.title}")
    }

    override fun onPlaybackStopped() {
        isPlaying = false
        playbackStartTime = 0L
        Log.d(TAG, "onPlaybackStopped for ${tvModel?.tv?.title}")
    }

    override fun onPlaybackError(error: String) {
        isPlaying = false
        playbackStartTime = 0L
        Log.e(TAG, "onPlaybackError for ${tvModel?.tv?.title}: $error")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null) // 清掉 5s 超时等所有回调
        webView?.let {
            val parent = it.parent as? ViewGroup
            parent?.removeView(it)
            it.destroy()
        }
        webView = null
        callback = null
    }

    companion object {
        private const val TAG = "WebFragment"
    }
}
