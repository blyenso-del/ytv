package com.blyen.ytv.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Global {
    val gson = Gson()

    val typeTvList = object : TypeToken<List<TV>>() {}.type
    val typeStableSourceList = object : TypeToken<List<StableSource>>() {}.type
}
