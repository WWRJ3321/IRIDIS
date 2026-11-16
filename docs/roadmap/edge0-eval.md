# Edge0 接入评估（2026-11-15）
源码：/workspace/work/edge0（浅克隆 main）
- 手机可用：仅 edge0-35b（Qwen3.6-35B-A3B 底座，Apache-2.0，int4 + Recover-LoRA + prerouter）。145B/120B 只支持 NVIDIA/Mac；8B 原生引擎未支持。
- 安卓：仅 CPU，要求 armv8.6-a+i8mm（骁龙 8 Gen2 / 天玑 9200 及以后）。作者自测（非我们实测，见 edge0-infer/docs/benchmarking.md，骁龙 8 Elite OnePlus 16GB，2026-10-26）：解码 11.4–11.8 tok/s，627 token 首字 18.4–18.8 s，RSS 2.82 GiB。
- 引擎 edge0-infer：C++17，自带 ggml 0.24.0（llama.cpp 7ab4ee7，2026-09-22）静态编，需与我们的 ggml 隔离符号（独立 .so + 隐藏符号）。API：open_runtime/load_model/create_session/generate，流式输出含思考与工具调用。官方用 NDK 28。
- 模型：HF 只有原始 safetensors 19.7 GB，需在电脑上用 Python（make_assets.py）转成资产目录（GGUF + assets.tsv/model.json），手机上不能转。无官方预转换包 → 需要我们转好并找地方托管，或让用户自己导入。
- 已有对比：IRIDIS 2.5.4 起的 LES（qwen35moe 前瞻专家调度）思路类似，但 Edge0 多了训练过的 prerouter 和 LoRA 精度补偿。
待用户决定：是否接入；资产托管方式。

## 决定（2026-11-16 13:54）
- 用户：可以尝试接入 → 放 2.6.5，标实验功能。先接引擎（独立 .so、隐藏符号、导入资产目录），模型托管等用户选 HF/魔搭并给写 token 后再转换上传。
- 托管：HF 账号 WWRJ-2025（用户给的用户名 WWRJ3321 与 token 实际账号不同，以 token 为准），仓库 WWRJ-2025/Edge0-35B-IRIDIS。token 已存 vault，不入库。

## 2.6.5 接入结果（2026-11-16）
- 引擎：app/src/main/cpp/edge0/（CMakeLists + edge0_jni.cpp + exports.map），scripts/build-edge0.sh 交叉编译（clang-19 + NDK r27c sysroot，-march armv8.6-a+dotprod+i8mm+fp16，build 目录 /workspace/work/compat/edge0-build），产物 prebuilt-native/arm64-v8a/libiris_edge0.so（≈3 MB）。只导出 8 个 Java_…Edge0Native_* 符号（版本脚本 + hidden + --exclude-libs + -Bsymbolic），无 ggml 符号外泄，与 libiris_llama 不冲突。
- Kotlin：engine/edge0/（Edge0Support 设备门槛：arm64 + 每核 asimddp/i8mm/bf16/fphp；Edge0Assets 资产目录判定；Edge0Prompt 历史→JSON、事件流→<think>/<tool_call> 文本；Edge0Native；Edge0Engine）。ModelFormat.EDGE0（文件夹）；full 版 EngineFactory 支持，lite 版提示装 full。
- 来源：推荐「对话」加 Edge0-35B（实验），Kind.Hub(WWRJ-2025/Edge0-35B-IRIDIS) 整目录下载（MnnHub，自动含 hf-mirror，分块 + sha256）；「导入文件夹」识别资产目录。不支持的机型下载/导入/加载都提示。
- 资产转换：/workspace/work/edge0-assets（src = 原始权重，edge0-35b = 资产目录），上传脚本 upload.py，README 在 hf/README.md。
- 未真机测试。作者数据不当实测写。
