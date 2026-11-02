# IRIDIS 2.2.15 更新说明

- NPU 支持新模型：K2 Horizon、LFM3 等新架构 GGUF 现在可以在 NPU 上运行。内置新的 NPU 运行库（llama.cpp 官方 Hexagon 后端，和 CPU/GPU 用的是同一版 llama.cpp），不需要额外下载
- 已装 GenieX 时：NPU 仍先用 GenieX；GenieX 不认识的模型自动改用内置 NPU 运行库，再不行才回退 GPU / CPU
- 没装 GenieX 时：GGUF 也能直接选 NPU（骁龙 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5）
- 提示：NPU 上 Q4_0 / Q8_0 量化最快；Q4_K_M 等其他量化的部分计算会落在 CPU 上
- 首次用内置 NPU 时会把运行库解压到 App 存储（约 10 MB），只做一次
