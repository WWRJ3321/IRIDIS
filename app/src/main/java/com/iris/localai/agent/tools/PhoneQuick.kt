package com.iris.localai.agent.tools

/**
 * Beta3「一句话手机操作」：开/关手电筒、亮度/音量调到最高最低或百分比、静音/振动/响铃。
 * 直接走 `phone` 工具的系统接口（CameraManager / AudioManager / Settings），不叫模型、不走无障碍。纯 JVM，单测。
 */
object PhoneQuick {
    sealed class Act {
        data class Torch(val mode: String) : Act()          // on | off | toggle
        data class Brightness(val level: Int) : Act()       // 0–100
        data class Volume(val level: Int) : Act()           // 0–100，媒体音量
        data class Ringer(val mode: String) : Act()         // silent | vibrate | normal
    }

    private const val LEAD = "^(请|麻烦)?(你)?(帮我|给我|替我)?(把)?"
    private const val TAIL = "(一下)?(吧|呗|呀|啊)?[。.!！~～]*$"
    private val TORCH = "(手电筒|手电|闪光灯|电筒)"
    private val ON = "(打开|开启|开一下|开|亮起|点亮)"
    private val OFF = "(关闭|关掉|关上|关了|关)"

    private val TORCH_ON = Regex("$LEAD$ON(一下)?$TORCH$TAIL|$LEAD$TORCH(给我)?$ON$TAIL")
    private val TORCH_OFF = Regex("$LEAD$OFF(一下)?$TORCH$TAIL|$LEAD$TORCH(给我)?$OFF$TAIL")
    private val TORCH_ANY = Regex("$LEAD(手电筒|闪光灯)$TAIL")

    private val LEVEL = "(最高|最大|最亮|最响|拉满|满|最低|最小|最暗|最轻|\\d{1,3}\\s*%?|百分之\\d{1,3})"
    private val BRIGHT = Regex("$LEAD(屏幕)?亮度(调|开|设|拉)?(到|成|为)?$LEVEL$TAIL|$LEAD(屏幕)?(调|开|设)(到|成)?$LEVEL(的)?亮度$TAIL")
    private val VOLUME = Regex("$LEAD(媒体)?音量(调|开|设|拉)?(到|成|为)?$LEVEL$TAIL")
    private val SILENT = Regex("$LEAD(打开|开启|切换到|切到|调成|调到|设为|开)?静音(模式)?$TAIL")
    private val VIBRATE = Regex("$LEAD(打开|开启|切换到|切到|调成|调到|设为|开)?(振动|震动)(模式)?$TAIL")
    private val NORMAL = Regex("$LEAD(关闭|关掉|取消|退出)静音(模式)?$TAIL|$LEAD(切换到|切到|调成|调到|设为|打开|开启)?响铃(模式)?$TAIL")

    fun parse(text: String): Act? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty() || t.length > 24) return null
        return when {
            TORCH_OFF.matches(t) -> Act.Torch("off")
            TORCH_ON.matches(t) -> Act.Torch("on")
            TORCH_ANY.matches(t) -> Act.Torch("toggle")
            BRIGHT.matches(t) -> level(t)?.let { Act.Brightness(it) }
            VOLUME.matches(t) -> level(t)?.let { Act.Volume(it) }
            NORMAL.matches(t) -> Act.Ringer("normal")
            SILENT.matches(t) -> Act.Ringer("silent")
            VIBRATE.matches(t) -> Act.Ringer("vibrate")
            else -> null
        }
    }

    private fun level(t: String): Int? {
        Regex("(\\d{1,3})").find(t)?.let { return it.value.toInt().takeIf { v -> v in 0..100 } }
        return when {
            Regex("最高|最大|最亮|最响|拉满|满").containsMatchIn(t) -> 100
            Regex("最低|最小|最暗|最轻").containsMatchIn(t) -> 0
            else -> null
        }
    }

    /** 执行；返回系统接口的原话（英文，给日志/模型）。异常照抛，由调用方显示。 */
    fun run(a: Act, p: PhoneActions): String = when (a) {
        is Act.Torch -> p.flashlight(a.mode)
        is Act.Brightness -> p.brightness(a.level)
        is Act.Volume -> p.volume("media", a.level)
        is Act.Ringer -> p.ringerMode(a.mode)
    }

    /** 成功时给用户看的短句（中文；失败原因由系统接口返回的 Could not… 判定）。 */
    fun ok(raw: String): Boolean = !raw.startsWith("Could not")
}
