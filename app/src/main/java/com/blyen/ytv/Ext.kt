@file:Suppress("DEPRECATION")

package com.blyen.ytv

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

private val Context.packageInfo: PackageInfo
    get() {
        val flag = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNING_CERTIFICATES
        }
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, flag)
        } else {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        }
    }

/** app versionName，异常上报 / 版本记录用 */
val Context.appVersionName: String get() = packageInfo.versionName!!

fun String.showToast(duration: Int = Toast.LENGTH_SHORT) {
    YTVApplication.getInstance().toast(this, duration)
}

fun Int.getString(): String {
    return YTVApplication.getInstance().getString(this)
}

fun Int.showToast(duration: Int = Toast.LENGTH_SHORT) {
    this.getString().showToast(duration)
}
