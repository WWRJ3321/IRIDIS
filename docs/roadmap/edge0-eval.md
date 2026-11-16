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
- 资产已转换并上传（2026-11-16 15:20）：https://huggingface.co/WWRJ-2025/Edge0-35B-IRIDIS（公开，Apache-2.0，中文 README），9 个资产文件合计 21,950,687,475 B（base.gguf 21.75 GB），官方 check 两次 passed，HF 上大小/LFS sha256 与 assets.tsv 一致。本地 /workspace/work/edge0-assets/edge0-35b。注：make_assets 末段被 20 分钟限时打断，用 finish.py 按原顺序补完 write_catalog/check/rename。

## Edge0-8B-A1B 评估（2026-11-17，2.6.8）——结论：不接，等上游
- 上游现状：edge0-infer（2026-11-04）只支持 edge0-35b；README 写明 8B「native-engine support is planned」。
- 架构差异大，不是换个配置：8B 是 Ling 3.0 混合架构（bailing_moe_v3：MLA 注意力 + MoE，24 层、128 路由专家 + 1 共享专家、top-8、sigmoid + 分组 top-k n_group=8/topk_group=4/routed_scaling=2.5），35B 是 Qwen3.6-35B-A3B（qwen35moe）。edge0-infer 的图构建、prerouter（8B 是 16 头、owner 层 7..22、1–6 层用原路由）、Recover-LoRA 挂载、资产转换脚本（tools/assets/edge0_35b）都按 35B 写死。
- 自行适配需要：① 在 edge0-infer 里新写 bailing_moe_v3 前向（MLA 的 KV 压缩/解压、分组 sigmoid 路由）；② 改 prerouter 层映射与专家流式调度；③ 新写 8B 的 safetensors→GGUF 资产转换（4-bit affine group64 反量化再量化）；④ 主机逐层数值对比（需要能跑参考实现的 Python 环境 + 16 GB 以上内存，本沙箱 7 GB）；⑤ 手机验证。估计 3–5 天工作量，1 小时内不可能接通，也写不出有意义的单测。
- 替代：Ling 2.x 在 llama.cpp 有 bailingmoe2；若 llama.cpp 上游加了 bailing_moe_v3，可以直接走普通 GGUF（不带 prerouter/流式），比改 edge0-infer 便宜。等上游 edge0-infer 支持 8B 后，按 2.6.5 的方式（独立 .so + 转换资产上传 WWRJ-2025/Edge0-8B-A1B-IRIDIS）接入，约半天。
