package com.blyen.ytv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Utils {
    const val TAG = "Utils"

    fun getDateFormat(format: String): String {
        return SimpleDateFormat(format, Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
    }

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
