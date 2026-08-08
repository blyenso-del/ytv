package com.blyen.ytv.requests

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.blyen.ytv.SP
import com.blyen.ytv.Utils.formatUrl
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

object HttpClient {
    const val TAG = "HttpClient"

    private val clientCache = mutableMapOf<String?, OkHttpClient>()

    val okHttpClient: OkHttpClient by lazy {
        getClientWithProxy()
    }

    internal val builder: OkHttpClient.Builder by lazy {
        createBuilder()
    }

    /**
     * 停掉所有在途请求，避免加载失败后仍狂拉分片拖死设备。
     *
     * 刻意**不** evictAll 连接池：狂拉分片来自 dispatcher 的在途请求，cancelAll 已完全覆盖；
     * 而 evictAll 关闭的是不产生流量的空闲连接，唯一实际效果是强制下次重新 TLS 握手。
     * 部分源站（如 iptv.852851.xyz）握手成功率仅约 60%，连接一旦建好却很稳定，
     * 主动清池会让每次换台/重试都撞上握手失败 → 单线路频道无源可换 → "加载失败"。
     * 复用不会用到坏连接：OkHttp 复用前会做健康检查，失效连接自动丢弃重建。
     */
    fun cancelAllCalls() {
        try {
            okHttpClient.dispatcher.cancelAll()
        } catch (e: Exception) {
            Log.w(TAG, "cancelAll dispatcher: ${e.message}")
        }
    }

    private fun getClientWithProxy(): OkHttpClient {
        clientCache[SP.proxy]?.let {
            return it
        }

        if (!SP.proxy.isNullOrEmpty()) {
            try {
                val proxyUri = formatUrl(SP.proxy!!).toUri()
                val proxyType = when (proxyUri.scheme) {
                    "http", "https" -> Proxy.Type.HTTP
                    "socks", "socks5" -> Proxy.Type.SOCKS
                    else -> null
                }
                proxyType?.let {
                    builder.proxy(Proxy(it, InetSocketAddress(proxyUri.host, proxyUri.port)))
                }
                Log.i(TAG, "apply proxy $proxyUri")
            } catch (e: Exception) {
                Log.e(TAG, "getClientWithProxy", e)
            }
        }

        val client = builder.build()
        clientCache[SP.proxy] = client
        return client
    }

    private fun createBuilder(): OkHttpClient.Builder {
        val trustManager = @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?
            ) {
            }

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?
            ) {
            }

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                return emptyArray()
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

        return OkHttpClient.Builder()
            // 媒体流超时不宜过长，否则坏源会长时间占连接/内存
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .dns(DnsCache())
            .apply { enableTls12OnPreLollipop() }
    }

    private fun OkHttpClient.Builder.enableTls12OnPreLollipop() {
        if (Build.VERSION.SDK_INT < 22) {
            try {
                val sslContext = SSLContext.getInstance("TLSv1.2")
                sslContext.init(null, null, java.security.SecureRandom())

                val trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
                )
                trustManagerFactory.init(null as KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                val trustManager = trustManagers[0] as X509TrustManager

                sslSocketFactory(Tls12SocketFactory(sslContext.socketFactory), trustManager)
                connectionSpecs( listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build(),
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
                )
            } catch (e: Exception) {
                Log.e(TAG, "enableTls12OnPreLollipop", e)
            }
        }
    }
}