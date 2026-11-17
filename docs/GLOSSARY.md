# 术语表

| 术语 | 含义 |
|---|---|
| IRIDIS / Iris | 应用名；代码包 com.iris.localai |
| LES | 前瞻专家调度（Lookahead Expert Scheduling）：MoE 模型在 CPU 上提前预取下一层专家，llama.cpp 分支 iris-v1 |
| iris-v1 | 自维护的 llama.cpp 分支（v1.0.0 + LES + mmap 预取），产物 libiris_llama.so |
| compat / legacy | libiris_llama_compat.so，给无 i8mm 的老芯片；自 2.5.3 未重编，普通版出包时剥离 |
| Edge0 | Edge0-35B 实验引擎（独立 libiris_edge0.so，专家从存储流式读取），资产在 HF WWRJ-2025/Edge0-35B-IRIDIS |
| GenieX | 高通 NPU 上跑 GGUF 的运行时（可下载） |
| QAIRT / AI Hub | 高通预编译 NPU 模型文件夹，可直接导入 |
| sd.cpp / SDCPP | stable-diffusion.cpp，生图 GPU（Vulkan）/CPU 后端，libiris_sd.so |
| sdq | sd.cpp 高通分支（HTP v79+ NPU 生图），libiris_sdq.so |
| SdFiles | sd.cpp 三件套：diffusion（主模型）+ llm（文本编码器）+ vae |
| ImportedImages | 2.6.9 用户导入的生图模型清单（filesDir/imagegen/imported.json） |
| MNN / MnnHub | 阿里 MNN 推理引擎；MnnHub = 整仓库下载器（多源、分块、sha256） |
| CtxScale | 上下文长度档位（上限 512K） |
| UpdateNotes | 应用内「此次更新/更新历史」数据，每版首位加一条（V255Test） |
| 思考档位 | 思考开关短按、长按横条只选 低/中/高 |
| IrisCard / clipDraw | 卡片容器；长容器勿用 Modifier.clip(smoothShape)（>8192px 丢触摸） |
| VxxxTest | 按版本命名的单元测试（如 V269Test），runtestsXXX.sh 汇总运行 |
| 未真机测试 | 2.4.6 之后的版本均未在真机验证，发版必须注明 |
