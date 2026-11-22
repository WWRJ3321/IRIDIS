package com.iris.localai.ui.models

import com.iris.localai.I18n
import com.iris.localai.R
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iris.localai.data.RecommendedCatalog
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.launch

/**
 * 模型与加载 › 看懂模型。2.6.4: 分节（目录可跳转）、拆解示例、术语表、与推荐卡片互链。
 * 纯静态内容；中文界面（简/繁）用中文，其余语言用英文（沿用 2.5.8 的机制）。
 */
object ModelGuide {
    /** [link] = a [RecommendedCatalog] item id; shown as 「看推荐」 when that card exists. */
    data class Item(val term: String, val text: String, val link: String? = null)
    /** One piece of a model name and what it means (the breakdown example). */
    data class Part(val text: String, val label: String)
    data class Section(val key: String, val title: String, val items: List<Item>, val example: List<Part> = emptyList())

    /** Section keys other pages can jump to ([ModelGuideNav.open]). */
    const val NAME = "name"; const val FORMAT = "format"; const val SIZE = "size"; const val QUANT = "quant"
    const val MOE = "moe"; const val SUFFIX = "suffix"; const val PHONE = "phone"; const val GLOSSARY = "glossary"

    // Catalog ids the guide links to (checked by V264Test: each must exist).
    const val L_GGUF = "lmstudio-community/Qwen3.5-4B-GGUF"
    const val L_MNN = "taobao-mnn/Qwen3.5-4B-MNN"
    const val L_LITERT = "litert-community/gemma-4-E2B-it-litert-lm"
    const val L_9B = "lmstudio-community/Qwen3.5-9B-GGUF"
    const val L_SMALL = "taobao-mnn/Qwen3.5-0.8B-MNN"
    val LINKS: List<String> get() = listOf(L_GGUF, L_MNN, L_LITERT, L_9B, L_SMALL, com.iris.localai.engine.edge0.Edge0Assets.REPO)

    fun chinese(): Boolean = runCatching { I18n.s(R.string.lang_code) }.getOrDefault("zh").startsWith("zh")

    val TITLE: String get() = if (chinese()) "看懂模型" else "Model basics"
    val ENTRY_HINT: String get() = if (chinese()) "名称、格式、参数量、量化、MoE 是什么" else "Names, formats, size, quantization, MoE"
    val TOC: String get() = if (chinese()) "目录" else "Contents"
    val SEE_REC: String get() = if (chinese()) "看推荐：" else "See: "
    val GLOSSARY_TITLE: String get() = if (chinese()) "术语表" else "Glossary"
    val EXAMPLE: String get() = if (chinese()) "拆开看" else "Broken down"

    fun sections(): List<Section> = if (chinese()) ZH else EN

    /** Every term in one list, A→Z (术语表). Example rows are left out; duplicates keep the first. */
    fun glossary(): List<Item> = sections().flatMap { it.items }
        .filter { it.term !in EXAMPLE_TERMS }
        .distinctBy { it.term.lowercase() }
        .sortedBy { it.term.lowercase() }

    /** Section keys in page order, the glossary last (目录 chips). */
    fun keys(): List<String> = sections().map { it.key } + GLOSSARY

    fun titleOf(key: String): String = if (key == GLOSSARY) GLOSSARY_TITLE else sections().firstOrNull { it.key == key }?.title ?: key

    /** The catalog card behind [id], or null when the catalog no longer has it (link hidden). */
    fun linked(id: String?): RecommendedCatalog.Item? = id?.let { runCatching { RecommendedCatalog.byId(it) }.getOrNull() }

    private val EXAMPLE_TERMS = setOf("例 1", "例 2", "顺序", "Example 1", "Example 2", "Order")

    private val ZH = listOf(
        Section(NAME, "怎么读模型名", listOf(
            Item("例 1", "Gemma 4 26B A4B MXFP4：总共 260 亿参数的 MoE，每个字只用约 40 亿（A4B），权重是 4 位 MXFP4。"),
            Item("顺序", "系列 → 参数量 → 用途 → 量化 → 格式，各家略有不同。"),
        ), example = listOf(
            Part("Qwen3.5", "系列"), Part("9B", "90 亿参数"), Part("Instruct", "对话版"), Part("Q4_K_M", "4 位量化"), Part(".gguf", "格式"),
        )),
        Section(FORMAT, "文件格式", listOf(
            Item("GGUF", "llama.cpp 单文件格式，最通用，CPU/GPU 都行，量化选择最多。", L_GGUF),
            Item("MNN", "阿里推理框架，一个文件夹一个模型，CPU/GPU 稳。", L_MNN),
            Item("LiteRT-LM", "Google 的 .litertlm，主要走 GPU，Gemma 常用。", L_LITERT),
            Item("NPU", "骁龙 8 Gen 2 起可用，需专门编译；快、省电，模型少。"),
            Item("mmproj", "视觉投影文件，让 GGUF 能识图，须和主模型配套。", L_GGUF),
        )),
        Section(SIZE, "参数量与内存", listOf(
            Item("B", "十亿。0.6B = 6 亿，9B = 90 亿。越大越聪明，也越慢、越占内存。"),
            Item("估内存", "参数量 × 位数 ÷ 8，再加上下文缓存。例：9B 4 位 ≈ 5 GB，8 位 ≈ 9.5 GB。"),
            Item("上下文", "能记住的最大长度（128K、512K）。越长越占内存，用不到别开太大。"),
        )),
        Section(QUANT, "量化", listOf(
            Item("量化", "把 16 位权重压到更少位：更小更快，质量略降。"),
            Item("F16 / BF16", "不压缩，最好也最大，手机上少用。"),
            Item("Q8_0", "8 位，几乎无损，约 F16 一半大。"),
            Item("Q6_K / Q5_K_M", "接近 Q8，更小，内存够就选。"),
            Item("Q4_K_M", "最常用的平衡点，优先选。", L_GGUF),
            Item("Q4_0", "老式 4 位，ARM 有加速，有时比 Q4_K_M 快。"),
            Item("Q3 / Q2 / IQ", "更小，用来硬塞大模型，质量明显下降。IQ = 改进的低位量化。"),
            Item("MXFP4", "4 位浮点，gpt-oss 和部分 Gemma 4 官方版用。"),
            Item("K / S / M / L", "K = 分组量化；S/M/L = 小/中/大，越大越好、越大文件。"),
            Item("imatrix / UD", "imatrix 用校准数据保护重要权重；UD = Unsloth 动态量化，低位更好。"),
        )),
        Section(MOE, "稠密与 MoE", listOf(
            Item("稠密", "每个字所有参数都参与。9B 稠密 = 每字算 90 亿。"),
            Item("MoE", "混合专家：每个字只用几个专家。A3B、A4B = 激活参数量。", com.iris.localai.engine.edge0.Edge0Assets.REPO),
            Item("经验", "内存看总参数，速度看激活参数：26B-A4B 要装下 26B，速度接近 4B。"),
            Item("专家按需加载", "内存不够时冷门专家留在存储里用到再读：能跑，但慢。"),
        )),
        Section(SUFFIX, "常见后缀", listOf(
            Item("Instruct / Chat / it", "对话版，聊天选它。"),
            Item("Base", "基础版，没做对话训练。"),
            Item("Thinking / Reasoning", "先思考再回答：更准，更慢。"),
            Item("Distill", "蒸馏版：大模型教小模型。"),
            Item("VL / Omni", "能看图（VL），或图像语音都行（Omni）。"),
            Item("Abliterated / Uncensored", "去掉拒答。"),
            Item("MTP", "一次猜多个字来加速，不需要草稿模型。"),
        )),
        Section(PHONE, "手机能跑多大", listOf(
            Item("8 GB", "4B 以内的 Q4，或小 MoE。", L_SMALL),
            Item("12 GB", "7–9B 的 Q4。", L_9B),
            Item("16 GB 以上", "14B 的 Q4，或 26B-A4B 类 MoE（偏紧）。", com.iris.localai.engine.edge0.Edge0Assets.REPO),
            Item("速度", "GPU/NPU 比 CPU 快；同内存下 MoE 常比稠密快。先跑「测速」。"),
        )),
    )

    private val EN = listOf(
        Section(NAME, "Reading a model name", listOf(
            Item("Example 1", "Gemma 4 26B A4B MXFP4: a 26B MoE using about 4B per token (A4B), stored in 4-bit MXFP4."),
            Item("Order", "Usually family → size → purpose → quantization → format."),
        ), example = listOf(
            Part("Qwen3.5", "family"), Part("9B", "9B params"), Part("Instruct", "chat"), Part("Q4_K_M", "4-bit"), Part(".gguf", "format"),
        )),
        Section(FORMAT, "File formats", listOf(
            Item("GGUF", "llama.cpp single file. Most common; CPU/GPU; most quantizations.", L_GGUF),
            Item("MNN", "Alibaba runtime; one folder per model; steady on CPU/GPU.", L_MNN),
            Item("LiteRT-LM", "Google's .litertlm, mostly GPU; common for Gemma.", L_LITERT),
            Item("NPU", "Snapdragon 8 Gen 2+. Specially compiled; fast, efficient, few models."),
            Item("mmproj", "Vision projector so a GGUF can see images; must match its model.", L_GGUF),
        )),
        Section(SIZE, "Size and memory", listOf(
            Item("B", "Billion. Bigger is smarter, slower, uses more memory."),
            Item("Memory", "Params × bits ÷ 8, plus context cache. 9B at 4-bit ≈ 5 GB; 8-bit ≈ 9.5 GB."),
            Item("Context", "Longest it can remember (128K, 512K). Longer uses more memory."),
        )),
        Section(QUANT, "Quantization", listOf(
            Item("Quantization", "Fewer bits per weight: smaller, faster, a little worse."),
            Item("F16 / BF16", "Uncompressed; best and largest."),
            Item("Q8_0", "8-bit, almost lossless, half of F16."),
            Item("Q6_K / Q5_K_M", "Close to Q8, smaller."),
            Item("Q4_K_M", "The usual sweet spot; pick first.", L_GGUF),
            Item("Q4_0", "Older 4-bit with ARM fast paths; sometimes faster."),
            Item("Q3 / Q2 / IQ", "Smaller, to squeeze big models in; clear quality drop."),
            Item("MXFP4", "4-bit float used by gpt-oss and some Gemma 4 releases."),
            Item("K / S / M / L", "K = k-quant; S/M/L = small/medium/large."),
            Item("imatrix / UD", "Calibration-aware quants; UD = Unsloth Dynamic, better at low bits."),
        )),
        Section(MOE, "Dense vs MoE", listOf(
            Item("Dense", "Every parameter runs for every token."),
            Item("MoE", "Mixture of experts: a few experts per token. A3B / A4B = active params.", com.iris.localai.engine.edge0.Edge0Assets.REPO),
            Item("Rule of thumb", "Memory follows total params; speed follows active params."),
            Item("Experts on demand", "Rare experts stay on storage until needed: runs, but slower."),
        )),
        Section(SUFFIX, "Common suffixes", listOf(
            Item("Instruct / Chat / it", "Chat-tuned; use for chatting."),
            Item("Base", "Not chat-tuned."),
            Item("Thinking / Reasoning", "Thinks first: more accurate, slower."),
            Item("Distill", "Small model taught by a big one."),
            Item("VL / Omni", "Sees images (VL), or images and audio (Omni)."),
            Item("Abliterated / Uncensored", "Refusals removed."),
            Item("MTP", "Multi-token prediction for speed; no draft model."),
        )),
        Section(PHONE, "What fits on a phone", listOf(
            Item("8 GB", "Up to 4B at Q4, or a small MoE.", L_SMALL),
            Item("12 GB", "7–9B at Q4.", L_9B),
            Item("16 GB+", "14B at Q4, or a 26B-A4B MoE (tight).", com.iris.localai.engine.edge0.Edge0Assets.REPO),
            Item("Speed", "GPU/NPU beat CPU; MoE often beats dense. Run the benchmark."),
        )),
    )
}

/**
 * open 看懂模型 from another page (a recommended card's detail), optionally at one section.
 * MainActivity sets [opener]; the page takes [pending] once.
 */
object ModelGuideNav {
    var opener: (() -> Unit)? = null
    @Volatile private var pending: String? = null
    fun open(sectionKey: String? = null) { pending = sectionKey; opener?.invoke() }
    fun take(): String? = pending.also { pending = null }

    /** The section a catalog card's detail links to: MoE cards → MoE, GGUF → quantization, others → formats. */
    fun sectionFor(item: RecommendedCatalog.Item): String = when {
        Regex("""\bA\d+(\.\d+)?B\b""").containsMatchIn(item.name) || item.id.contains("-a4b", true) || item.id.contains("A2B") -> ModelGuide.MOE
        item.engine == RecommendedCatalog.Engine.GGUF -> ModelGuide.QUANT
        else -> ModelGuide.FORMAT
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelGuidePage(onBack: () -> Unit) {
    val c = Iris.colors
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val tops = remember { mutableStateMapOf<String, Float>() }
    fun jump(key: String) { tops[key]?.let { y -> scope.launch { scroll.animateScrollTo(y.toInt()) } } }
    LaunchedEffect(Unit) {
        ModelGuideNav.take()?.let { key ->
            // Positions arrive after the first layout pass.
            repeat(10) { if (tops[key] == null) kotlinx.coroutines.delay(50) }
            jump(key)
        }
    }
    fun Modifier.anchor(key: String) = onPlaced { tops[key] = it.positionInParent().y }
    val openRec: (String) -> Unit = { id -> DownloadNav.open(id) }

    SubpageScaffold(ModelGuide.TITLE, onBack) {
        ScrollBody(scroll) {
            // 目录
            SectionTitle(ModelGuide.TOC)
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = IrisDimens.pageGutter),
                horizontalArrangement = Arrangement.spacedBy(IrisDimens.space8),
                verticalArrangement = Arrangement.spacedBy(IrisDimens.space8),
            ) {
                ModelGuide.keys().forEach { key ->
                    Text(
                        ModelGuide.titleOf(key), style = IrisType.secondary, color = c.text,
                        modifier = Modifier.clip(IrisRadius.pillShape).background(c.surfaceVariant)
                            .clickable { jump(key) }.padding(horizontal = IrisDimens.space12, vertical = IrisDimens.space6),
                    )
                }
            }
            ModelGuide.sections().forEach { s ->
                SectionTitle(s.title, Modifier.anchor(s.key))
                SettingsGroup {
                    if (s.example.isNotEmpty()) {
                        Text(ModelGuide.EXAMPLE, style = IrisType.labelSmall, color = c.textTertiary,
                            modifier = Modifier.padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, top = IrisDimens.space12))
                        NameBreakdown(s.example)
                        Divider()
                    }
                    s.items.forEachIndexed { i, it ->
                        if (i > 0) Divider()
                        GuideRow(it, openRec)
                    }
                }
            }
            SectionTitle(ModelGuide.GLOSSARY_TITLE, Modifier.anchor(ModelGuide.GLOSSARY))
            SettingsGroup {
                ModelGuide.glossary().forEachIndexed { i, it ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space8)) {
                        Text(it.term, style = IrisType.secondary, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.width(112.dp))
                        Text(it.text, style = IrisType.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(IrisDimens.space16))
        }
    }
}

/** 「Qwen3.5 | 9B | Instruct | Q4_K_M | .gguf」: one row per part, chip on the left and its meaning beside it (aligned columns). */
@Composable
private fun NameBreakdown(parts: List<ModelGuide.Part>) {
    val c = Iris.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space8),
        verticalArrangement = Arrangement.spacedBy(IrisDimens.space6),
    ) {
        parts.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(112.dp)) {
                    Text(p.text, style = IrisType.secondary, fontWeight = FontWeight.SemiBold, color = c.accent, maxLines = 1,
                        modifier = Modifier.clip(IrisRadius.smallShape).background(c.accentContainer).padding(horizontal = IrisDimens.space8, vertical = IrisDimens.space2))
                }
                Text(p.label, style = IrisType.secondary, color = c.textSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GuideRow(it: ModelGuide.Item, openRec: (String) -> Unit) {
    val c = Iris.colors
    Text(it.term, style = IrisType.body, fontWeight = FontWeight.SemiBold, color = c.text,
        modifier = Modifier.fillMaxWidth().padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, top = IrisDimens.space12))
    val rec = ModelGuide.linked(it.link)
    Text(it.text, style = IrisType.secondary, color = c.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, top = IrisDimens.space4, bottom = if (rec == null) IrisDimens.space12 else IrisDimens.space4))
    if (rec != null) {
        Text("${ModelGuide.SEE_REC}${rec.name} ›", style = IrisType.caption, color = c.accent,
            modifier = Modifier.padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, bottom = IrisDimens.space12)
                .clip(IrisRadius.smallShape).clickable { openRec(rec.id) }.padding(vertical = IrisDimens.space4))
    }
}
