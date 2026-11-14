package com.iris.localai.ui.models

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.ScrollBody
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisType

/**
 * 2.5.8: 模型与加载 › 看懂模型 — 名称、格式、参数量、量化、稠密/MoE、手机能跑多大。
 * 纯静态内容；中文界面（简/繁）用中文，其余语言用英文。
 */
object ModelGuide {
    data class Item(val term: String, val text: String)
    data class Section(val title: String, val items: List<Item>)

    fun chinese(): Boolean = runCatching { com.iris.localai.I18n.s(com.iris.localai.R.string.lang_code) }.getOrDefault("zh").startsWith("zh")

    val TITLE: String get() = if (chinese()) "看懂模型" else "Model basics"
    val ENTRY_HINT: String get() = if (chinese()) "名称、格式、参数量、量化、MoE 是什么" else "Names, formats, size, quantization, MoE"

    fun sections(): List<Section> = if (chinese()) ZH else EN

    private val ZH = listOf(
        Section("怎么读模型名", listOf(
            Item("例 1", "Qwen3.5-9B-Instruct-Q4_K_M.gguf：Qwen3.5 是系列，9B 是 90 亿参数，Instruct 是对话版，Q4_K_M 是量化方式，.gguf 是文件格式。"),
            Item("例 2", "Gemma 4 26B A4B MXFP4：总共 260 亿参数的 MoE 模型，每个字只激活约 40 亿（A4B），权重用 MXFP4 4 位格式。"),
            Item("顺序", "一般是 系列 + 版本 → 参数量 → 用途/后缀 → 量化 → 格式，不同作者顺序会略有不同。"),
        )),
        Section("文件格式", listOf(
            Item("GGUF", "llama.cpp 用的单文件格式，最通用。支持 CPU、GPU，量化选择最多。"),
            Item("MNN", "阿里的推理框架，一个文件夹装一个模型。CPU/GPU 下速度稳。"),
            Item("LiteRT-LM", "Google 的 .litertlm，主要走 GPU，Gemma 系列用得多。"),
            Item("NPU", "骁龙 8 Gen 2 起可用，模型要专门编译，速度快、省电，但能用的模型少。"),
            Item("mmproj", "视觉投影文件，让 GGUF 模型能识图；要和主模型配套。"),
        )),
        Section("参数量", listOf(
            Item("B", "B = 十亿（Billion）。0.6B 是 6 亿，9B 是 90 亿。参数越多一般越聪明，也越慢、越占内存。"),
            Item("估内存", "大约 参数量 × 每个权重的位数 ÷ 8，再加上下文缓存。9B 用 4 位 ≈ 5 GB，8 位 ≈ 9.5 GB。"),
            Item("上下文", "128K、512K 是能记住的最大长度。开得越长，KV 缓存越占内存，用不到就别开太大。"),
        )),
        Section("量化", listOf(
            Item("是什么", "把权重从 16 位压到更少位数。位数越低越小越快，质量损失越大。"),
            Item("F16 / BF16", "不压缩，质量最好，体积最大，手机上一般不用。"),
            Item("Q8_0", "8 位，几乎无损，体积约为 F16 的一半。"),
            Item("Q6_K / Q5_K_M", "质量接近 Q8，更小，内存够时的好选择。"),
            Item("Q4_K_M", "最常用的平衡点，推荐优先选。"),
            Item("Q4_0", "老式 4 位，ARM 上有专门加速，有时比 Q4_K_M 更快。"),
            Item("Q3 / Q2 / IQ", "更小，适合大模型硬塞进内存，质量明显下降。IQ 开头是改进的低位量化。"),
            Item("MXFP4", "4 位浮点格式，gpt-oss、部分 Gemma 4 官方发布用这种。"),
            Item("K / S / M / L", "K = k-quant 分组量化；S/M/L 是小/中/大，越大质量越好、文件越大。"),
            Item("imatrix / UD", "imatrix 用校准数据决定哪些权重更重要；UD 是 Unsloth 动态量化，低位时质量更好。"),
        )),
        Section("稠密与 MoE", listOf(
            Item("稠密", "每生成一个字，所有参数都参与计算。9B 稠密 = 每个字算 90 亿参数。"),
            Item("MoE", "混合专家：模型分成很多「专家」，每个字只用其中几个。名字里的 A3B、A4B 就是激活的参数量。"),
            Item("怎么看", "内存按总参数算（26B 要装下 26B 的权重），速度接近激活参数（A4B 接近 4B 的速度）。"),
            Item("专家按需加载", "内存不够时，不常用的专家留在存储里用到再读，能跑起来但会慢。"),
        )),
        Section("常见后缀", listOf(
            Item("Instruct / Chat / it", "对话微调版，聊天用这个。"),
            Item("Base", "基础版，没做对话训练，不适合直接聊天。"),
            Item("Thinking / Reasoning", "会先思考再回答，更准但更慢、更费上下文。"),
            Item("Distill", "蒸馏版，用大模型教小模型。"),
            Item("VL / Omni", "能看图（VL），或图像、语音都能处理（Omni）。"),
            Item("Abliterated / Uncensored", "去掉了拒答，回答更放开。"),
            Item("MTP", "多 token 预测，一次猜多个字来加速，不需要另外的草稿模型。"),
        )),
        Section("手机能跑多大", listOf(
            Item("8 GB", "4B 以内的 Q4，或小型 MoE。"),
            Item("12 GB", "7–9B 的 Q4；4B 以上建议从这档开始。"),
            Item("16 GB 以上", "14B 的 Q4，或 26B-A4B 这类 MoE（会比较紧）。"),
            Item("速度", "GPU、NPU 比 CPU 快；同样内存下 MoE 往往比稠密更快。先跑「测速」看实际表现。"),
        )),
    )

    private val EN = listOf(
        Section("Reading a model name", listOf(
            Item("Example 1", "Qwen3.5-9B-Instruct-Q4_K_M.gguf: Qwen3.5 is the family, 9B = 9 billion parameters, Instruct = chat-tuned, Q4_K_M = quantization, .gguf = file format."),
            Item("Example 2", "Gemma 4 26B A4B MXFP4: a 26B MoE model that activates about 4B per token (A4B), stored in 4-bit MXFP4."),
            Item("Order", "Usually family/version → size → purpose → quantization → format. Authors vary a little."),
        )),
        Section("File formats", listOf(
            Item("GGUF", "llama.cpp's single-file format. Most common; CPU and GPU; the widest choice of quantizations."),
            Item("MNN", "Alibaba's runtime; one folder per model. Steady on CPU/GPU."),
            Item("LiteRT-LM", "Google's .litertlm, mostly on GPU; common for Gemma."),
            Item("NPU", "Snapdragon 8 Gen 2 and later. Needs specially compiled models; fast and efficient, few models."),
            Item("mmproj", "Vision projector that lets a GGUF model see images; must match its model."),
        )),
        Section("Parameters", listOf(
            Item("B", "B = billion. More parameters usually means smarter, slower and more memory."),
            Item("Memory", "Roughly parameters × bits per weight ÷ 8, plus the context cache. 9B at 4-bit ≈ 5 GB; 8-bit ≈ 9.5 GB."),
            Item("Context", "128K or 512K is the longest it can remember. Longer context uses more memory for the KV cache."),
        )),
        Section("Quantization", listOf(
            Item("What", "Weights stored in fewer bits: smaller and faster, with some quality loss."),
            Item("F16 / BF16", "Uncompressed; best quality, largest."),
            Item("Q8_0", "8-bit, almost lossless, about half of F16."),
            Item("Q6_K / Q5_K_M", "Close to Q8 and smaller."),
            Item("Q4_K_M", "The usual sweet spot; pick this first."),
            Item("Q4_0", "Older 4-bit with ARM fast paths; sometimes faster than Q4_K_M."),
            Item("Q3 / Q2 / IQ", "Smaller, for squeezing big models in; clear quality drop. IQ = improved low-bit quants."),
            Item("MXFP4", "A 4-bit float format used by gpt-oss and some official Gemma 4 releases."),
            Item("K / S / M / L", "K = k-quant; S/M/L = small/medium/large, larger is better and bigger."),
            Item("imatrix / UD", "imatrix uses calibration data to protect important weights; UD = Unsloth Dynamic, better at low bits."),
        )),
        Section("Dense vs MoE", listOf(
            Item("Dense", "Every parameter is used for every token."),
            Item("MoE", "Mixture of experts: only a few experts run per token. A3B / A4B = active parameters."),
            Item("Rule of thumb", "Memory follows total parameters; speed follows active parameters."),
            Item("Experts on demand", "When memory is short, rarely used experts stay on storage and load when needed: it runs, but slower."),
        )),
        Section("Common suffixes", listOf(
            Item("Instruct / Chat / it", "Chat-tuned; use this for chatting."),
            Item("Base", "Not chat-tuned."),
            Item("Thinking / Reasoning", "Thinks before answering: more accurate, slower."),
            Item("Distill", "A small model taught by a bigger one."),
            Item("VL / Omni", "Sees images (VL), or images and audio (Omni)."),
            Item("Abliterated / Uncensored", "Refusals removed."),
            Item("MTP", "Multi-token prediction for speed; no separate draft model needed."),
        )),
        Section("What fits on a phone", listOf(
            Item("8 GB", "Up to 4B at Q4, or a small MoE."),
            Item("12 GB", "7–9B at Q4; recommended for anything above 4B."),
            Item("16 GB+", "14B at Q4, or a 26B-A4B MoE (tight)."),
            Item("Speed", "GPU and NPU beat CPU; MoE is often faster than dense at the same memory. Run the benchmark to see."),
        )),
    )
}

@Composable
fun ModelGuidePage(onBack: () -> Unit) {
    val c = Iris.colors
    SubpageScaffold(ModelGuide.TITLE, onBack) {
        ScrollBody {
            ModelGuide.sections().forEach { s ->
                SectionTitle(s.title)
                SettingsGroup {
                    s.items.forEachIndexed { i, it ->
                        if (i > 0) Divider()
                        Text(it.term, style = IrisType.body, fontWeight = FontWeight.SemiBold, color = c.text,
                            modifier = Modifier.fillMaxWidth().padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, top = IrisDimens.space12))
                        Text(it.text, style = IrisType.secondary, color = c.textSecondary,
                            modifier = Modifier.fillMaxWidth().padding(start = IrisDimens.rowPadding, end = IrisDimens.rowPadding, top = IrisDimens.space4, bottom = IrisDimens.space12))
                    }
                }
            }
            Spacer(Modifier.height(IrisDimens.space16))
        }
    }
}
