package com.iris.localai.agent

/**
 * 2.1.9 「直接打开应用」: finds an installed app by Chinese name, English name or package (fuzzy),
 * for the `phone` tool's open_app, the GUI agent's Launch / open / awake and the chat's 「打开微信」.
 * Apps are (label, package) from the launcher query (manifest <queries> MAIN/LAUNCHER). Pure.
 */
object AppMatcher {
    /** Common apps: the names people say (中文 / English) → package. Used only when that package is installed. */
    private val ALIASES: List<Pair<String, List<String>>> = listOf(
        "com.tencent.mm" to listOf("微信", "wechat", "weixin"),
        "com.tencent.mobileqq" to listOf("qq"),
        "com.eg.android.AlipayGphone" to listOf("支付宝", "alipay"),
        "com.taobao.taobao" to listOf("淘宝", "taobao"),
        "com.jingdong.app.mall" to listOf("京东", "jd", "jingdong"),
        "com.xunmeng.pinduoduo" to listOf("拼多多", "pinduoduo", "pdd"),
        "com.ss.android.ugc.aweme" to listOf("抖音", "douyin", "tiktok"),
        "com.smile.gifmaker" to listOf("快手", "kuaishou"),
        "com.xingin.xhs" to listOf("小红书", "xiaohongshu", "rednote", "red"),
        "com.sankuai.meituan" to listOf("美团", "meituan"),
        "me.ele" to listOf("饿了么", "eleme"),
        "tv.danmaku.bili" to listOf("哔哩哔哩", "b站", "bilibili"),
        "com.sina.weibo" to listOf("微博", "weibo"),
        "com.zhihu.android" to listOf("知乎", "zhihu"),
        "com.autonavi.minimap" to listOf("高德地图", "高德", "amap", "gaode"),
        "com.baidu.BaiduMap" to listOf("百度地图", "baidu map"),
        "com.netease.cloudmusic" to listOf("网易云音乐", "网易云", "netease music", "cloudmusic"),
        "com.tencent.qqmusic" to listOf("qq音乐", "qq music"),
        "com.alibaba.android.rimet" to listOf("钉钉", "dingtalk"),
        "com.ss.android.lark" to listOf("飞书", "feishu", "lark"),
        "com.tencent.wework" to listOf("企业微信", "wecom", "wework"),
        "com.android.chrome" to listOf("chrome", "谷歌浏览器"),
        "com.android.settings" to listOf("设置", "系统设置", "settings"),
    )

    /** Generic words → label keywords (system apps differ by vendor: 相册 / 图库 / Gallery / Photos …). */
    private val GENERIC: List<Pair<List<String>, List<String>>> = listOf(
        listOf("设置", "系统设置", "settings") to listOf("设置", "settings"),
        listOf("相册", "图库", "照片", "gallery", "photos", "album") to listOf("相册", "图库", "照片", "gallery", "photos"),
        listOf("相机", "拍照", "camera") to listOf("相机", "camera"),
        listOf("浏览器", "browser") to listOf("浏览器", "browser", "chrome"),
        listOf("日历", "calendar") to listOf("日历", "calendar"),
        listOf("时钟", "闹钟", "clock", "alarm") to listOf("时钟", "闹钟", "clock"),
        listOf("计算器", "calculator") to listOf("计算器", "calculator"),
        listOf("文件管理", "文件", "files", "file manager") to listOf("文件管理", "文件", "files"),
        listOf("应用商店", "应用市场", "app store", "play store", "store") to listOf("应用商店", "应用市场", "play 商店", "play store", "store"),
        listOf("电话", "拨号", "phone", "dialer") to listOf("电话", "拨号", "phone", "dialer"),
        listOf("短信", "信息", "messages", "sms") to listOf("短信", "信息", "messages"),
        listOf("联系人", "通讯录", "contacts") to listOf("联系人", "通讯录", "contacts"),
        listOf("天气", "weather") to listOf("天气", "weather"),
        listOf("音乐", "music") to listOf("音乐", "music"),
        listOf("笔记", "便签", "备忘录", "notes") to listOf("笔记", "便签", "备忘录", "notes"),
    )

    private val PUNCT = Regex("[\\s·・\\-_.,，。!！?？「」“”\"'()（）]+")
    private val SUFFIX = Regex("(app|应用|软件|客户端)$")

    fun norm(s: String): String = SUFFIX.replace(PUNCT.replace(s.trim().lowercase(), ""), "")

    /** Best match, or null. Order: exact name / package → known alias → generic name → prefix → contains → package part. */
    fun match(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = norm(query)
        if (q.isEmpty() || apps.isEmpty()) return null
        val raw = query.trim()
        apps.firstOrNull { norm(it.first) == q || it.second.equals(raw, ignoreCase = true) }?.let { return it }
        ALIASES.firstOrNull { (_, names) -> names.any { norm(it) == q } }?.let { (pkg, _) -> apps.firstOrNull { it.second == pkg }?.let { return it } }
        GENERIC.firstOrNull { (says, _) -> says.any { norm(it) == q } }?.let { (_, keys) ->
            for (k in keys) apps.firstOrNull { norm(it.first) == norm(k) }?.let { return it }
            for (k in keys) apps.firstOrNull { norm(it.first).contains(norm(k)) }?.let { return it }
        }
        apps.firstOrNull { norm(it.first).startsWith(q) }?.let { return it }
        apps.firstOrNull { norm(it.first).contains(q) }?.let { return it }
        // 「打开微信 app 吧」-style leftovers: the label inside the query (labels of ≥ 2 chars only).
        apps.filter { norm(it.first).length >= 2 && q.contains(norm(it.first)) }.maxByOrNull { norm(it.first).length }?.let { return it }
        if (q.length >= 3) apps.firstOrNull { it.second.lowercase().split('.').any { p -> p == q } }?.let { return it }
        if (q.length >= 4) apps.firstOrNull { it.second.lowercase().contains(q) }?.let { return it }
        return null
    }

    /** Close names for the not-found message (by shared characters / edit distance). */
    fun candidates(query: String, apps: List<Pair<String, String>>, max: Int = 5): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        return apps.map { it.first to score(q, norm(it.first)) }.filter { it.second > 0.0 }
            .sortedByDescending { it.second }.map { it.first }.distinct().take(max)
    }

    private fun score(q: String, l: String): Double {
        if (l.isEmpty()) return 0.0
        val shared = q.toSet().intersect(l.toSet()).size.toDouble()
        val sim = 1.0 - editDistance(q, l).toDouble() / maxOf(q.length, l.length)
        val s = shared / maxOf(q.toSet().size, 1) * 0.6 + sim.coerceAtLeast(0.0) * 0.4
        return if (shared == 0.0) 0.0 else if (s >= 0.3) s else 0.0
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Clear Chinese not-found text with the close candidates. */
    fun notFound(query: String, apps: List<Pair<String, String>>): String {
        val c = candidates(query, apps)
        return "没有找到应用「${query.trim()}」" + if (c.isEmpty()) "（已安装应用里没有相近的名字）" else "。相近的应用：" + c.joinToString("、")
    }
}

/** 2.1.9: 「打开微信」-style messages that only ask to launch an app (no further steps). Pure. */
object LaunchIntent {
    private val PURE = Regex("^(请|麻烦)?(你)?(帮我|给我|替我)?(打开|启动|开启|运行|进入)(一下)?\\s*(.{1,24}?)\\s*(app|APP|App|应用|软件)?(一下|吧|呗)?[。.!！~～]*$")
    /** Words that mean more than a launch (「打开微信给张三发消息」、「打开蓝牙」-like settings, questions). */
    private val MORE = Regex("[，,；;、？?]|然后|并且|再|把|给|发|搜|找|看看|里的|里面|中的|怎么|如何|为什么|吗|呢|蓝牙|wifi|WiFi|WIFI|手电筒|飞行模式|热点|定位|GPS|麦克风|摄像头权限|网页|网址|链接|http|www\\.")

    /** The app name of a pure launch request, or null. */
    fun appName(text: String): String? {
        val t = text.trim()
        if (t.length > 30) return null
        val m = PURE.matchEntire(t) ?: return null
        val name = m.groupValues[6].trim()
        if (name.isEmpty() || MORE.containsMatchIn(name)) return null
        return name
    }
}
