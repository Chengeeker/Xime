package com.kingzcheung.xime.password

import org.json.JSONObject

// Android 会将 JSONObject.NULL 强转成字符串 "null"，不能用于可选加密字段。
internal fun JSONObject.stringOrEmpty(name: String, fallback: String = ""): String =
    if (isNull(name)) fallback else optString(name, fallback)
