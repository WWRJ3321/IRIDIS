# native-patches

对 llama.cpp（rev bee2332，编译脚本 LLAMA_DIR）的本地补丁，编译原生库前在 llama.cpp 目录 `git apply <patch>`。

- `llama-mmap-prefetch.patch`（2.5.1）：新增 `llama_set_mmap_prefetch(bool)`（全局开关，默认 true，`LLAMA_HAS_MMAP_PREFETCH` 宏标识）。false 时加载 `init_mappings(false)`（无 MAP_POPULATE / WILLNEED），整文件 `posix_fadvise` 与 `posix_madvise` 均为 RANDOM，供 MoE「专家按需加载」使用；iris_engine.cpp 在加载前设置、加载后复位为 true。
