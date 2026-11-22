package com.iris.localai.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 生图提示词标签库 + 扩写（纯逻辑，单测覆盖；调研见 docs/roadmap/imagegen-prompt.md）。
 *
 * 标签：每条有分类、中文名、写进提示词的英文值；[danbooru] 为 Danbooru 风格的写法（Anime/Anima 用下划线
 * 之外的空格形式，如 "1girl"、"looking at viewer"）。用户可自定义增删（[TagStore] 存 JSON），内置标签可隐藏。
 */
enum class TagCat(val zh: String, val en: String) {
    QUALITY("画质", "Quality"), STYLE("风格", "Style"), PERSON("人物", "Character"), SUBJECT("主体", "Subject"),
    SCENE("场景", "Scene"), COMPOSE("构图", "Composition"), LIGHT("光线", "Lighting"), COLOR("色调氛围", "Color & mood"),
    NEGATIVE("负面", "Negative");
    companion object { fun of(s: String?): TagCat = entries.firstOrNull { it.name.equals(s, true) } ?: SUBJECT }
}

data class PromptTag(val cat: TagCat, val zh: String, val en: String, val danbooru: String? = null, val custom: Boolean = false) {
    /** 写进提示词的文本：标签风格模型优先用 Danbooru 写法。 */
    fun text(tagStyle: Boolean): String = if (tagStyle && !danbooru.isNullOrBlank()) danbooru else en
    val key: String get() = "${cat.name}:${en.lowercase()}"
}

object PromptTags {
    private fun t(c: TagCat, zh: String, en: String, db: String? = null) = PromptTag(c, zh, en, db)

    val BUILTIN: List<PromptTag> = listOf(
        t(TagCat.SUBJECT, "一个女孩", "a girl", "1girl, solo"),
        t(TagCat.SUBJECT, "一个男孩", "a boy", "1boy, solo"),
        t(TagCat.SUBJECT, "猫", "a cat", "cat"),
        t(TagCat.SUBJECT, "风景", "landscape", "scenery, no humans"),
        t(TagCat.SUBJECT, "城市街道", "city street", "city, street"),
        t(TagCat.SUBJECT, "看向镜头", "looking at viewer", "looking at viewer"),
        t(TagCat.SUBJECT, "微笑", "smiling", "smile"),
        t(TagCat.STYLE, "照片写实", "photorealistic photo", "realistic, photorealistic"),
        t(TagCat.STYLE, "动漫", "anime style", "anime coloring"),
        t(TagCat.STYLE, "水彩", "watercolor painting", "watercolor (medium)"),
        t(TagCat.STYLE, "油画", "oil painting", "oil painting (medium)"),
        t(TagCat.STYLE, "像素风", "pixel art", "pixel art"),
        t(TagCat.STYLE, "3D 渲染", "3d render", "3d"),
        t(TagCat.STYLE, "赛博朋克", "cyberpunk", "cyberpunk"),
        t(TagCat.STYLE, "水墨", "chinese ink painting", "ink wash painting"),
        t(TagCat.LIGHT, "柔光", "soft lighting", "soft lighting"),
        t(TagCat.LIGHT, "黄金时刻", "golden hour", "sunset, golden hour"),
        t(TagCat.LIGHT, "逆光", "backlighting", "backlighting"),
        t(TagCat.LIGHT, "电影光", "cinematic lighting", "cinematic lighting"),
        t(TagCat.LIGHT, "霓虹", "neon lights", "neon lights"),
        t(TagCat.LIGHT, "夜晚", "night", "night"),
        t(TagCat.COMPOSE, "特写", "close-up", "close-up"),
        t(TagCat.COMPOSE, "半身", "upper body", "upper body"),
        t(TagCat.COMPOSE, "全身", "full body", "full body"),
        t(TagCat.COMPOSE, "广角", "wide shot", "wide shot"),
        t(TagCat.COMPOSE, "俯视", "from above", "from above"),
        t(TagCat.COMPOSE, "景深", "shallow depth of field", "depth of field"),
        t(TagCat.QUALITY, "高质量", "high quality, highly detailed", "masterpiece, best quality"),
        t(TagCat.QUALITY, "细节丰富", "intricate details", "absurdres, highres"),
        t(TagCat.QUALITY, "锐利对焦", "sharp focus", "sharp focus"),
        t(TagCat.QUALITY, "新作", "modern", "newest"),
        t(TagCat.NEGATIVE, "低质量", "low quality, worst quality", "worst quality, low quality"),
        t(TagCat.NEGATIVE, "模糊", "blurry", "blurry"),
        t(TagCat.NEGATIVE, "多手指", "extra fingers, bad hands", "bad hands, extra digits"),
        t(TagCat.NEGATIVE, "水印文字", "watermark, text", "watermark, signature, text"),
        t(TagCat.NEGATIVE, "压缩噪点", "jpeg artifacts", "jpeg artifacts"),
    ) + PromptTagData.parse()

    /** 搜索 + 分类筛选：[cat] 为 null = 全部分类；[query] 匹配中文名、英文值、Danbooru 写法（不分大小写）。 */
    fun filter(tags: List<PromptTag>, cat: TagCat?, query: String): List<PromptTag> {
        val q = query.trim()
        return tags.filter { t ->
            (cat == null || t.cat == cat) && (q.isEmpty() || t.zh.contains(q, true) || t.en.contains(q, true) || (t.danbooru ?: "").contains(q, true))
        }
    }

    fun isBuiltin(tag: PromptTag): Boolean = BUILTIN.any { it.key == tag.key }

    /** 内置（去掉隐藏的）+ 自定义，按分类顺序。 */
    fun merged(custom: List<PromptTag>, hidden: Set<String>): List<PromptTag> =
        custom.map { it.key }.toSet().let { mine ->
            // a custom tag with a built-in's key is that built-in edited: it replaces the original.
            (BUILTIN.filter { it.key !in hidden && it.key !in mine } + custom).sortedBy { it.cat.ordinal }
        }

    /** 智能体可读的紧凑清单（每类一行），给模型挑选；标签风格模型列 Danbooru 写法。 */
    fun forModel(tags: List<PromptTag>, tagStyle: Boolean, maxPerCat: Int = 24): String =
        TagCat.entries.mapNotNull { c ->
            val list = tags.filter { it.cat == c }.take(maxPerCat)
            if (list.isEmpty()) null else "${c.en}: " + list.joinToString("; ") { it.text(tagStyle) }
        }.joinToString("\n")

    /** 把一个标签追加到提示词末尾（已含则不重复）。 */
    fun append(prompt: String, tag: String): String {
        val p = prompt.trim().trimEnd(',', '，').trim()
        if (tag.isBlank()) return prompt
        if (p.split(',', '，').any { it.trim().equals(tag.trim(), true) }) return prompt
        return if (p.isEmpty()) tag.trim() else "$p, ${tag.trim()}"
    }
}

/** 标签库 JSON：{"custom":[{cat,zh,en,db}], "hidden":["CAT:en"]}。坏文件 = 空库。 */
object TagStore {
    data class Data(val custom: List<PromptTag> = emptyList(), val hidden: Set<String> = emptySet())

    fun parse(json: String?): Data = runCatching {
        val o = Json.parseToJsonElement(json ?: return Data()).jsonObject
        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val custom = (o["custom"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val t = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val en = t.str("en")
            if (en.isEmpty()) null else PromptTag(TagCat.of(t.str("cat")), t.str("zh").ifEmpty { en }, en, t.str("db").ifEmpty { null }, custom = true)
        }
        val hidden = (o["hidden"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.filter { it.isNotEmpty() }.toSet()
        Data(custom, hidden)
    }.getOrDefault(Data())

    fun toJson(d: Data): String = JsonObject(mapOf(
        "custom" to JsonArray(d.custom.map {
            JsonObject(mapOf("cat" to JsonPrimitive(it.cat.name), "zh" to JsonPrimitive(it.zh), "en" to JsonPrimitive(it.en), "db" to JsonPrimitive(it.danbooru ?: "")))
        }),
        "hidden" to JsonArray(d.hidden.sorted().map { JsonPrimitive(it) }),
    )).toString()

    /** 新增：英文值必填，最长 80 字；同分类同值替换。 */
    fun add(d: Data, cat: TagCat, zh: String, en: String): Data {
        val v = en.trim().take(80)
        if (v.isEmpty()) return d
        val tag = PromptTag(cat, zh.trim().take(20).ifEmpty { v }, v, null, custom = true)
        return d.copy(custom = d.custom.filter { it.key != tag.key } + tag, hidden = d.hidden - tag.key)
    }

    /**
     * 修改：先删旧的（内置的记为隐藏），再按新值加一条自定义标签；英文值为空则不改。
     * 修改内置标签时保留它的 Danbooru 写法（英文值没变时）。
     */
    fun edit(d: Data, old: PromptTag, cat: TagCat, zh: String, en: String): Data {
        val v = en.trim().take(80)
        if (v.isEmpty()) return d
        val removed = remove(d, old)
        val db = if (v.equals(old.en, true)) old.danbooru else null
        val tag = PromptTag(cat, zh.trim().take(20).ifEmpty { v }, v, db, custom = true)
        return removed.copy(custom = removed.custom.filter { it.key != tag.key } + tag, hidden = removed.hidden - tag.key)
    }

    /** 删除：自定义的直接删，内置的记为隐藏。 */
    fun remove(d: Data, tag: PromptTag): Data =
        if (PromptTags.BUILTIN.any { it.key == tag.key }) d.copy(hidden = d.hidden + tag.key, custom = d.custom.filter { it.key != tag.key })
        else d.copy(custom = d.custom.filter { it.key != tag.key })
}

/**
 * 提示词扩写：几句描述 → 模型能吃的完整提示词。有已加载的文本模型就让它改写（[system] / [clean]），
 * 否则用规则模板（[rules]：补质量标签 + 默认负面词）。标签风格模型（Anima / Anything）用 Danbooru 逗号标签。
 */
object PromptExpand {
    const val MAX_LEN = 600

    /** 标签风格（Danbooru）模型。 */
    fun tagStyle(m: ImageModel): Boolean = m.tagStyle

    fun system(tagStyle: Boolean): String = com.iris.localai.prompts.Prompts.get(
        if (tagStyle) com.iris.localai.prompts.PromptKey.IMAGE_EXPAND_TAGS else com.iris.localai.prompts.PromptKey.IMAGE_EXPAND,
    )

    /** 模型输出 → 干净的提示词；不可用（空、太短、像拒答）返回 null。 */
    fun clean(raw: String, original: String): String? {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "")
        s = s.trim().removePrefix("```").removeSuffix("```").trim()
        s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        s = s.replace(Regex("^(prompt|提示词|tags)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "").trim().trim('"', '“', '”', '「', '」')
        if (s.length < 8 || s.length < original.trim().length / 2) return null
        if (Regex("^(i'm sorry|sorry|i cannot|i can't|抱歉|对不起)", RegexOption.IGNORE_CASE).containsMatchIn(s)) return null
        return s.take(MAX_LEN)
    }

    /** 已经是一长串提示词（够长或已含质量词）就不扩写。 */
    fun needed(prompt: String): Boolean {
        val p = prompt.trim()
        if (p.isEmpty()) return false
        val words = p.split(Regex("[\\s,，]+")).count { it.isNotEmpty() }
        val cjk = p.count { it.code in 0x4E00..0x9FFF }
        return words + cjk / 2 < 40 && !p.contains("masterpiece", true) && !p.contains("best quality", true)
    }

    /** 规则模板：补质量标签（标签风格放前面）。 */
    fun rules(prompt: String, tagStyle: Boolean): String {
        val p = prompt.trim().trimEnd(',', '，', '。', '.')
        if (p.isEmpty()) return p
        return if (tagStyle) "masterpiece, best quality, newest, $p"
        else "$p, highly detailed, sharp focus, natural lighting, high quality"
    }

    /** 默认负面词（CFG > 1 时才生效）；用户填了就不动。 */
    fun negative(current: String, tagStyle: Boolean): String = current.ifBlank {
        if (tagStyle) "worst quality, low quality, blurry, jpeg artifacts, bad hands, extra digits, watermark, signature, text"
        else "low quality, worst quality, blurry, deformed, extra fingers, watermark, text"
    }
}

/** 标签库文件 filesDir/imagegen/tags.json（自定义 + 隐藏的内置）。 */
object TagLibrary {
    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "imagegen/tags.json")
    @Synchronized fun load(ctx: android.content.Context): TagStore.Data =
        TagStore.parse(runCatching { file(ctx).takeIf { it.isFile }?.readText() }.getOrNull())
    @Synchronized fun save(ctx: android.content.Context, d: TagStore.Data) {
        runCatching { val f = file(ctx); f.parentFile?.mkdirs(); val t = java.io.File(f.path + ".tmp"); t.writeText(TagStore.toJson(d)); t.renameTo(f) }
    }
    fun tags(ctx: android.content.Context): List<PromptTag> = load(ctx).let { PromptTags.merged(it.custom, it.hidden) }
}

/**
 * 3.0.0 Beta3 r5：标签提示词整理 + 预设（原创实现，参照 Anima / Illustrious / Noob 这类标签模型公开的写法约定：
 * 画质 / 年代 / 分级 → 人数 → 角色、作品 → 画师（@ 前缀）→ 其余标签 → 自然语言句子）。纯逻辑，单测 V300B3R5Test。
 * 不含任何第三方插件代码或词库。
 */
object PromptOrder {
    enum class Slot { QUALITY, META, RATING, COUNT, ARTIST, GENERAL, SENTENCE }

    private val QUALITY = Regex("^(masterpiece|best quality|high quality|good quality|normal quality|low quality|worst quality|amazing quality|very aesthetic|aesthetic|displeasing|very displeasing|absurdres|highres|lowres|score_\\d(_up)?)$")
    private val META = Regex("^(newest|recent|mid|early|old|year \\d{4}|\\d{4}s?)$")
    private val RATING = Regex("^(safe|sensitive|nsfw|explicit|general|questionable|rating:\\s*\\w+)$")
    private val COUNT = Regex("^(\\d+\\+?(girl|boy|other)s?|solo|solo focus|multiple (girls|boys|others)|no humans)$")

    /** 标签风格：下划线写成空格（score_9 这类保留），去掉多余空白。 */
    fun normalizeTag(t: String): String {
        val x = t.trim().replace(Regex("\\s+"), " ")
        return if (Regex("^score_\\d").containsMatchIn(x)) x else x.replace('_', ' ')
    }

    fun slotOf(tag: String): Slot {
        val t = tag.trim().lowercase()
        return when {
            QUALITY.matches(t) -> Slot.QUALITY
            META.matches(t) -> Slot.META
            RATING.matches(t) -> Slot.RATING
            COUNT.matches(t) -> Slot.COUNT
            t.startsWith("@") || t.startsWith("artist:") || t.startsWith("by ") -> Slot.ARTIST
            // 一整句话（有句号或 6 个词以上）放最后
            t.endsWith(".") || t.endsWith("。") || t.split(' ').size >= 6 -> Slot.SENTENCE
            else -> Slot.GENERAL
        }
    }

    /** 整理：拆逗号 → 规范写法 → 去重（不分大小写）→ 按槽位排序（同一槽位保持原来的先后）。 */
    fun arrange(prompt: String): String {
        val seen = HashSet<String>()
        val tags = prompt.split(',', '，', '\n').map(::normalizeTag).filter { it.isNotEmpty() && seen.add(it.lowercase()) }
        return tags.withIndex().sortedWith(compareBy({ slotOf(it.value).ordinal }, { it.index })).joinToString(", ") { it.value }
    }

    /** 一键画质词（标签模型在最前面，其他模型补在后面）。 */
    fun quality(tagStyle: Boolean): String =
        if (tagStyle) "masterpiece, best quality, score_7, newest, safe" else "highly detailed, sharp focus, high quality"

    /** 一键负面词。 */
    fun negative(tagStyle: Boolean): String =
        if (tagStyle) "worst quality, low quality, score_1, score_2, score_3, blurry, jpeg artifacts, sepia, bad hands, extra digits, watermark, signature, text"
        else "low quality, worst quality, blurry, deformed, extra fingers, watermark, text"

    /** 把一串标签并进提示词（已有的不重复）；标签模型再整理一遍顺序。 */
    fun addAll(prompt: String, tags: String, tagStyle: Boolean): String {
        var p = prompt
        tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { p = PromptTags.append(p, it) }
        return if (tagStyle) arrange(p) else p
    }
}
