package com.blyen.ytv.data

import com.blyen.ytv.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Global {
    val gson = Gson()

    val typeTvList = object : TypeToken<List<TV>>() {}.type
    val typeStableSourceList = object : TypeToken<List<StableSource>>() {}.type

    /** 站点适配脚本：域名 → res/raw 脚本，WebFragment.onPageFinished 注入 */
    val scriptMap = mapOf(
        "tv.cctv.com" to R.raw.ahtv1,
        "www.yangshipin.cn" to R.raw.ahtv1,
        "yangshipin.cn" to R.raw.ahtv1,
        "m-live.cctvnews.cctv.com" to R.raw.ahtv1,
        "live.kankanews.com" to R.raw.ahtv1,
        "www.cbg.cn" to R.raw.ahtv1,
        "www.yb983.com" to R.raw.ahtv1,
        "www.yntv.cn" to R.raw.ahtv1,
        "live.snrtv.com" to R.raw.ahtv1,
        "www.btzx.com.cn" to R.raw.ahtv1,
        "static.hntv.tv" to R.raw.ahtv1,
        "www.hljtv.com" to R.raw.ahtv1,
        "www.qhtb.cn" to R.raw.ahtv1,
        "www.qhbtv.com" to R.raw.ahtv1,
        "v.iqilu.com" to R.raw.ahtv1,
        "www.jlntv.cn" to R.raw.ahtv1,
        "www.cztv.com" to R.raw.ahtv1,
        "www.gzstv.com" to R.raw.ahtv1,
        "www.hnntv.cn" to R.raw.ahtv1,
        "live.mgtv.com" to R.raw.ahtv1,
        "www.hebtv.com" to R.raw.ahtv1,
        "tc.hnntv.cn" to R.raw.ahtv1,
        "live.fjtv.net" to R.raw.ahtv1,
        "tv.gxtv.cn" to R.raw.ahtv1,
        "www.nxtv.com.cn" to R.raw.ahtv1,
        "news.hbtv.com.cn" to R.raw.ahtv1,
        "www.sztv.com.cn" to R.raw.ahtv1,
        "www.setv.sh.cn" to R.raw.ahtv1,
        "www.gdtv.cn" to R.raw.ahtv1,
        "www.kangbatv.com" to R.raw.ahtv1,
    )

    /** 分组级广告/干扰资源屏蔽：分组名 → URL 路径子串列表 */
    val blockMap = mapOf(
        "央视甲" to listOf(
            "jweixin",
            "daohang",
            "dianshibao.js",
            "dingtalk.js",
            "configtool",
            "qrcode",
            "shareindex.js",
            "zhibo_shoucang.js",
            "gray",
            "cntv_Advertise.js",
            "top2023newindex.js",
            "indexPC.js",
            "getEpgInfoByChannelNew",
            "epglist",
            "epginfo",
            "getHandDataList",
            "2019whitetop/index.js",
            "pc_nav/index.js",
            "shareindex.js",
            "mapjs/index.js",
            "bottomjs/index.js",
            "top2023newindex.js",
            "2019dlbhyjs/index.js"
        ),
    )
}
