# Edge0 接入评估（2026-11-15）
源码：/workspace/work/edge0（浅克隆 main）
- 手机可用：仅 edge0-35b（Qwen3.6-35B-A3B 底座，Apache-2.0，int4 + Recover-LoRA + prerouter）。145B/120B 只支持 NVIDIA/Mac；8B 原生引擎未支持。
- 安卓：仅 CPU，要求 armv8.6-a+i8mm（骁龙 8 Gen2 / 天玑 9200 及以后）。官方实测 OnePlus 16GB：解码 11.4–11.8 tok/s，627 token 首字 18.4–18.8 s，RSS 2.82 GiB。
- 引擎 edge0-infer：C++17，自带 ggml 0.24.0（llama.cpp 7ab4ee7，2026-09-22）静态编，需与我们的 ggml 隔离符号（独立 .so + 隐藏符号）。API：open_runtime/load_model/create_session/generate，流式输出含思考与工具调用。官方用 NDK 28。
- 模型：HF 只有原始 safetensors 19.7 GB，需在电脑上用 Python（make_assets.py）转成资产目录（GGUF + assets.tsv/model.json），手机上不能转。无官方预转换包 → 需要我们转好并找地方托管，或让用户自己导入。
- 已有对比：IRIDIS 2.5.4 起的 LES（qwen35moe 前瞻专家调度）思路类似，但 Edge0 多了训练过的 prerouter 和 LoRA 精度补偿。
待用户决定：是否接入；资产托管方式。
