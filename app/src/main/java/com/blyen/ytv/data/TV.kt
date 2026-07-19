package com.blyen.ytv.data

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 直播源类型：IPTV（传统流媒体）或 WEBVIEW（网页播放）
 */
enum class PlayerType {
    IPTV, WEBVIEW
}

/**
 * 直播频道数据模型，支持 IPTV 和 WebView 直播源
 */
@Serializable
data class TV(
    @SerializedName("name") @SerialName("name") val name: String = "",
    @SerializedName("title") @SerialName("title") val title: String = "",
    @SerializedName("description") @SerialName("description") val description: String? = null,
    @SerializedName("logo") @SerialName("logo") val logo: String = "",
    @SerializedName("image") @SerialName("image") val image: String? = null,
    @SerializedName("uris") @SerialName("uris") var uris: List<String> = emptyList(),
    @SerializedName("videoIndex") @SerialName("videoIndex") var videoIndex: Int = 0,
    @SerializedName("headers") @SerialName("headers") val headers: Map<String, String>? = emptyMap(),
    @SerializedName("group") @SerialName("group") val group: String = "",
    @SerializedName("sourceType") @SerialName("sourceType") var sourceType: SourceType = SourceType.UNKNOWN,
    @SerializedName("number") @SerialName("number") val number: Int = -1,
    @SerializedName("child") @SerialName("child") val child: List<TV> = emptyList(),
    // WebView 相关字段
    @SerializedName("playerType") @SerialName("playerType") val playerType: PlayerType = PlayerType.IPTV,
    @SerializedName("block") @SerialName("block") val block: List<String>? = emptyList(),
    @SerializedName("script") @SerialName("script") val script: String? = null,
    @SerializedName("selector") @SerialName("selector") val selector: String? = null,
    @SerializedName("started") @SerialName("started") val started: String? = null,
    @SerializedName("finished") @SerialName("finished") val finished: String? = null,
    @SerializedName("id") @SerialName("id") val id: Int = (group + name + title).hashCode()
) {
    override fun toString(): String {
        return "TV(" +
                "id=$id, " +
                "name='$name', " +
                "title='$title', " +
                "playerType=$playerType, " +
                "uris=$uris, " +
                "videoIndex=$videoIndex, " +
                "group='$group', " +
                "logo='$logo', " +
                "sourceType=$sourceType, " +
                "block=$block, " +
                "script=${script?.take(20)}..., " +
                "selector=$selector" +
                ")"
    }
}