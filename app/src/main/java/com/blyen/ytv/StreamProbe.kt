package com.blyen.ytv

import android.util.Log
import com.blyen.ytv.requests.HttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HLS master 探测：识别是否 4K / 是否仅有高清档。
 * - 有多档：播放时强制选 ≤1080p（频道仍可看）
 * - 仅 4K：仍尝试播放，但用更短超时 + 更小缓冲，超时硬杀防死机
 */
object StreamProbe {
    private const val TAG = "StreamProbe"
    private const val MAX_BODY = 48 * 1024

    data class Result(
        val ok: Boolean,
        val is4k: Boolean = false,
        /** master 里没有任何 ≤1080p 的视频轨 */
        val onlyHighRes: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val bandwidth: Int = 0,
        val frameRate: Float = 0f,
        val reason: String = ""
    )

    fun probe(url: String): Result {
        if (url.isBlank()) return Result(ok = false, reason = "empty")
        val client = HttpClient.okHttpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
        return try {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36"
                )
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result(ok = false, reason = "http-${resp.code}")
                }
                val body = resp.body?.bytes() ?: return Result(ok = false, reason = "no-body")
                val text = String(body.copyOf(minOf(body.size, MAX_BODY)), Charsets.UTF_8)
                parseMaster(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe fail (play normally): ${e.message}")
            Result(ok = false, reason = "probe-error")
        }
    }

    private fun parseMaster(text: String): Result {
        if (!text.contains("#EXTM3U") && !text.contains("#EXT-X-")) {
            return Result(ok = true, reason = "not-hls")
        }
        var maxW = 0
        var maxH = 0
        var maxBw = 0
        var maxFps = 0f
        var hasStreamInf = false
        var hasSafeVariant = false // ≤1080p
        val streamInf = Regex("""#EXT-X-STREAM-INF:([^\r\n]+)""", RegexOption.IGNORE_CASE)
        for (m in streamInf.findAll(text)) {
            hasStreamInf = true
            val attrs = m.groupValues[1]
            var w = 0
            var h = 0
            var bw = 0
            Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(attrs)?.let {
                bw = it.groupValues[1].toIntOrNull() ?: 0
                maxBw = maxOf(maxBw, bw)
            }
            Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(attrs)?.let {
                w = it.groupValues[1].toIntOrNull() ?: 0
                h = it.groupValues[2].toIntOrNull() ?: 0
                if (w * h > maxW * maxH) {
                    maxW = w
                    maxH = h
                }
                if (h in 1..1080 && w in 1..1920) {
                    hasSafeVariant = true
                }
            }
            Regex("""FRAME-RATE=([\d.]+)""", RegexOption.IGNORE_CASE).find(attrs)?.let {
                maxFps = maxOf(maxFps, it.groupValues[1].toFloatOrNull() ?: 0f)
            }
            // 无 RESOLUTION 但带宽不高，当可安全档
            if (w == 0 && h == 0 && bw in 1 until 6_000_000) {
                hasSafeVariant = true
            }
        }
        if (!hasStreamInf) {
            // media playlist：未知，按普通播
            return Result(ok = true, reason = "media-playlist")
        }
        val is4k = maxH >= 2160 || maxW >= 3840 || maxBw >= 12_000_000
        val onlyHighRes = is4k && !hasSafeVariant
        val reason = "res=${maxW}x${maxH} bw=$maxBw fps=$maxFps only4k=$onlyHighRes safe=$hasSafeVariant"
        Log.i(TAG, "probe: $reason")
        return Result(
            ok = true,
            is4k = is4k,
            onlyHighRes = onlyHighRes,
            width = maxW,
            height = maxH,
            bandwidth = maxBw,
            frameRate = maxFps,
            reason = reason
        )
    }
}
