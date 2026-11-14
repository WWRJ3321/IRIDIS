package com.iris.localai.data

import android.content.Context

/**
 * 2.5.5: 模型自定义显示名（不改文件名）。按文件名存一份，[ModelRepository.refresh] 时填进 [LocalModel.alias]。
 * 纯内存表 + SharedPreferences，单元测试里不 init 就是空表。
 */
object ModelAliases {
    private const val PREFS = "iris_model_alias"
    @Volatile private var map: Map<String, String> = emptyMap()

    fun init(ctx: Context) {
        map = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    fun of(fileName: String): String? = map[fileName]

    /** null / 空白 = 恢复文件名。 */
    fun set(ctx: Context, fileName: String, name: String?) {
        val clean = normalize(name)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (clean == null) p.remove(fileName) else p.putString(fileName, clean)
        p.apply()
        map = if (clean == null) map - fileName else map + (fileName to clean)
    }

    /** 去首尾空白、压掉换行、最长 [MAX_LEN] 字。 */
    fun normalize(name: String?): String? =
        name?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_LEN)?.ifEmpty { null }

    const val MAX_LEN = 40
}
