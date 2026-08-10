package com.blyen.ytv

object Utils {
    const val TAG = "Utils"

    fun formatUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") || url.startsWith(
                "socks://"
            ) || url.startsWith("socks5://")
        ) {
            return url
        }

        if (url.startsWith("//")) {
            return "http:$url"
        }

        return "http://$url"
    }
}
