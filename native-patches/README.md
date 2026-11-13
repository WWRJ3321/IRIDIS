# native-patches

对 llama.cpp（rev bee2332，编译脚本 LLAMA_DIR）的本地补丁，编译原生库前在 llama.cpp 目录 `git apply <patch>`。

- `llama-mmap-prefetch.patch`（2.5.1）：新增 `llama_set_mmap_prefetch(bool)`（全局开关，默认 true，`LLAMA_HAS_MMAP_PREFETCH` 宏标识）。false 时加载 `init_mappings(false)`（无 MAP_POPULATE / WILLNEED），整文件 `posix_fadvise` 与 `posix_madvise` 均为 RANDOM，供 MoE「专家按需加载」使用；iris_engine.cpp 在加载前设置、加载后复位为 true。
- `llama-les.patch`（2.5.4）：LES 前瞻专家调度，移植自 OpenBMB/llama.cpp-omni a64932e（MIT，`src/llama-moe-slot.*`、`src/llama-moe-experts.*`、`tools/moe/*`）。相对 bee2332 的完整 diff（先打本补丁，再打 `llama-mmap-prefetch.patch`）。IRIDIS 改动：① CPU 用 `ggml_backend_cpu_buffer_type_alloc_only()`（CPU_LES，不被 mmap 包住）放专家槽位；② 图把阶段（prefill / decode 窗口）传给槽位运行时，修复 prefill 末层只剩输出行时按行数误判阶段、读错专家的问题；③ 支持「原地 manifest」（直接指向原 GGUF，stride = 每专家字节，按家族逐段读），手机上不必另存专家文件；④ Linux/Android 无 F_NOCACHE，改 posix_fadvise。主机 aarch64 用随机小 qwen35moe 模型验证：开关 LES、解码窗口、前瞻预取、A2 拆分、剥离 GGUF、原地 manifest 的 logits 与基线逐位一致。
