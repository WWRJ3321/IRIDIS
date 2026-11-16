# 生图「几句描述出好图」调研与落地（2.6.8）

## 结论
默认开「扩写提示词」：生图页有已加载文本模型 → 让它改写（不思考、≤256 token、45 秒超时）；没加载/失败/输出不像提示词 → 规则模板。智能体 generate_image 里提示词本来就由模型写，只补质量词与默认负面词。标签库供人点选、供智能体（generate_image list_tags=true）读取。

## 调研到的方案（按投入/收益）
1. **LLM 提示词扩写**（Fooocus 的 GPT-2 expansion、Midjourney/DALL·E 3 的 prompt rewriting、Qwen-Image/Z-Image 官方 prompt enhancer、ComfyUI 的 LLM 节点）：效果最大，短描述 → 主体/外观/场景/光线/构图/风格/氛围。DALL·E 3 论文说明「训练用详细描述 + 推理时改写」是出图质量关键。代价：一次文本生成（本机 1–4B 模型约 5–20 秒）。落地：PromptExpand.system()/clean()。
2. **风格模板 / 预设**（Fooocus styles、SDXL style selector）：把「风格词 + 负面词」打包成一项，适合不会写词的用户。落地：标签库的「风格」分类（可自定义扩充）。
3. **默认负面提示词**：对 CFG>1 的模型（SD1.5 / Anything）有效；FLUX.2 Klein / Z-Image / Anima Turbo 用 CFG 1，负面词被忽略（ImageRequest.effectiveNegative）。落地：PromptExpand.negative()，只在用户没填时补。
4. **质量标签**：SD1.5 动漫系、Anima/Illustrious 系对 Danbooru 质量标签（masterpiece, best quality, newest, absurdres）敏感；FLUX/Z-Image 这类用 LLM 文本编码器的模型更吃自然语言长句，质量标签作用小。落地：规则模板按 ImageModel.tagStyle 分两套。
5. **标签自动补全 / 标签库**（a1111 tagcomplete、Danbooru 标签数据库）：Anima 官方建议 Danbooru 标签 + 自然语言混写，标签用空格不用下划线。落地：PromptTags（主体/风格/光线/构图/画质/负面，中英 + Danbooru 写法），用户可增删、内置可隐藏/恢复。
6. 未做：CLIP 反推/图生词、按模型训练的专用扩写小模型（Fooocus 方式需额外下载权重）、多候选扩写后打分。

## 代码位置
- imagegen/PromptKit.kt：TagCat、PromptTag、PromptTags（内置库、merged、forModel、append）、TagStore（JSON）、PromptExpand（system/clean/needed/rules/negative）、TagLibrary（filesDir/imagegen/tags.json）。
- ui/imagegen：标签行（分类→点选追加，长按删，「+」加，「恢复内置」），「扩写提示词」开关，结果下显示实际提示词。
- agent/tools/Tools.kt：GenerateImageTool.wantsTags/expand；ToolEnv.imageTags/imageExpand/imageLowMem。
- 设置存在 AgentSettings（imagePromptExpand 默认开、imageLowMem 默认关），生图页与智能体共用。
