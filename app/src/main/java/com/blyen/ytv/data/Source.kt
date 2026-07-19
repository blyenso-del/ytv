package com.blyen.ytv.data

import java.io.Serializable

data class StableSource(
    val id: Int,
    val name: String,
    val title: String,
    val description: String?,
    val logo: String,
    val image: String?,
    val uris: List<String>,
    val videoIndex: Int,
    val headers: Map<String, String>?,
    val group: String,
    val sourceType: String,
    val number: Int,
    val child: List<TV>,
    val timestamp: Long,
    // 历史缓存兼容字段（WebView 已移除，读旧 stable 时仍可能存在）
    val playerType: PlayerType = PlayerType.IPTV,
    val block: List<String>? = emptyList(),
    val script: String? = null,
    val selector: String? = null,
    val started: String? = null,
    val finished: String? = null
) : Serializable
