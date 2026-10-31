# 架构

```
IRIDIS/
├── app/
│   ├── src/main/
│   │   ├── cpp/                    原生层（C++17）：llama.cpp 引擎、MNN 引擎、JNI
│   │   ├── assets/                 Silero VAD、语音清单、NPU 调度库、内置技能 skills/
│   │   └── java/com/iris/localai/
│   │       ├── IrisApp.kt          AppContainer：所有单例在此创建（手动依赖注入）
│   │       ├── data/               模型、会话、设置的数据与持久化
│   │       ├── engine/             推理（L1 运行时）
│   │       │   ├── InferenceEngine.kt · EngineManager.kt · Backend.kt · NpuSupport.kt · HistoryMatch.kt
│   │       │   ├── llama/          GGUF（llama.cpp JNI）
│   │       │   ├── load/           加载：取消、遥测、GPU 缓存诊断、慢加载提示
│   │       │   └── resources/      档位、线程、温控、实时资源调整
│   │       ├── agent/              智能体（L2–L5）
│   │       │   ├── core/           循环 AgentLoop、调用解析、设置与权限、命令前缀规则
│   │       │   ├── tools/          工具注册表与分组 + 文件 / 计算器 / 记忆 / 手机 / 定时 / 技能
│   │       │   ├── web/            联网搜索（必应 / DuckDuckGo / SearXNG）、网页读取、WebView 浏览器
│   │       │   └── workspace/      工作区沙箱、撤销、SAF、位置、Download/IRIDIS 目录布局与迁移（StorageLayout）
│   │       ├── context/            零模型压缩、上下文拼装
│   │       ├── memory/             USER.md / MEMORY.md、FTS 历史索引
│   │       ├── voice/              本地语音（sherpa-onnx：识别、VAD、合成、克隆）
│   │       ├── server/             本地 API（OpenAI / Ollama 兼容）
│   │       ├── service/            前台服务：后台生成、加载保活
│   │       ├── util/               纯 Kotlin 工具（都有单元测试）
│   │       │   ├── thinking/       <think> 解析、思考预算
│   │       │   ├── streaming/      流式显示、停止策略、语速、朗读文本
│   │       │   ├── diagnostics/    活动指示、崩溃报告、性能采样、提示日志
│   │       │   └── io/             附件、下载、备份与日志导出、后台保护
│   │       └── ui/                 Jetpack Compose（L6 界面）
│   │           ├── chat/  agent/  models/  settings/
│   │           └── components/  theme/  motion/  glass/  markdown/
│   ├── src/full/ · src/lite/       full 版额外引擎（LiteRT-LM、MNN）/ lite 版
│   └── src/test/                   单元测试，按版本分目录（v190 … v1120）
├── docs/                           ARCHITECTURE · API · BUILD · CHANGELOG
│   └── changelog/                  PROGRESS-x.y.z.md（每版改动说明）
├── scripts/  native-test/          构建脚本、原生宿主机测试
├── third_party/                    llama.cpp、MNN（不随源码包分发）
└── README.md · THIRD_PARTY_NOTICES.md
```

> Kotlin 包名保持按大模块划分（`com.iris.localai.agent`、`.engine`、`.util` 等），子文件夹只用来分组文件，所以挪动文件没有改动任何类名和导入。

## 关键设计

### 单一模型持有者
`EngineManager` 是唯一能接触推理引擎的地方。聊天、语音、API 三个入口都经过它：
- 同一时间只驻留一个模型；
- 生成请求用 `Mutex` 排队（API 调用不会和界面回复抢资源）；
- 每次生成结束后开始空闲计时，超时自动卸载。

### 原生引擎与 KV 复用
`iris_engine.cpp` 记录 KV 缓存里实际有哪些 token（`kv_tokens_`）。每次请求：
1. 用模型内置模板格式化完整对话；超出上下文时按消息丢弃最早的对话轮次；
2. 与 `kv_tokens_` 求最长公共前缀，只删除并重算分叉之后的部分（多轮对话通常只需算新的一句）；
3. 解码过程中 UTF-8 安全地流式回调；`llama_set_abort_callback` 让取消在 prefill 中也能立刻生效；
4. 任何失败/中断后都会把 KV 与记录重新对齐，下一次请求不会受影响。

### 深度思考
支持 `<think>` 的模型（Qwen3、QwQ、DeepSeek-R1 蒸馏版）：
- 关闭「深度思考」时，在提示词末尾预填空的 `<think></think>`（与 Qwen3 `enable_thinking=false` 等效）；
- 界面中思考过程可折叠并显示用时；API 中以 `reasoning_content`（DeepSeek 风格）返回；
- 思考内容不会回传给模型作为历史，节省上下文。

### 功耗策略
| 措施 | 说明 |
|------|------|
| 线程 = 性能核 − 2（上限 6） | 手机上 decode 受内存带宽限制，更多线程只增加功耗 |
| 温控降档 | `PowerManager.currentThermalStatus` ≥ MODERATE 减 1 线程，≥ SEVERE 减半；每次生成前通过 `llama_set_n_threads` 动态生效 |
| 合帧刷新 | 流式文本 ~30fps 更新一次，而非每个 token 重排 Markdown |
| 空闲卸载 | 默认 10 分钟无操作卸载模型，释放数 GB 内存 |
| mmap 加载 | 模型权重按需分页，不复制到堆 |
| 省电模式 | 系统省电模式开启时玻璃降为半透明色调（不做模糊） |
| 语音 | 仅 VAD（每 32ms 一次小模型）常驻；识别只在检测到人声片段后运行；麦克风只在语音界面可见时开启 |
| API 服务 | 关闭即停止前台服务与 socket；WakeLock 只在处理请求期间持有 |
| 屏幕常亮 | 仅在生成过程中 |

### 玻璃效果
见 `ui/glass/Glass.kt` 顶部注释：内容层录制为 `GraphicsLayer`（RenderNode 引用，不重复光栅化），每个玻璃面板截取其背后区域（先铺页面底色，越界处不透明），降采样后应用高斯模糊 + 饱和度增强，按面板自身圆角路径裁剪绘制，再叠加色调、顶部高光渐变与描边。档位：省电=半透明色调（无离屏）；均衡=毛玻璃，降采样 2/4；华丽=毛玻璃，降采样 1/2，且仅华丽对对话框/底部弹窗开启系统跨窗口模糊（同时调淡 dim/遮罩）。1.7.3 起移除 AGSL 液态透镜（真机上出现黑边、色散蓝边、直角白块）。

## 扩展点
- 新推理后端：实现 `InferenceEngine`，在 `EngineManager.load()` 中按 `ModelFormat` 分派。
- 新语音模型：在 `assets/voice/` 增加清单 JSON，并在 `VoicePack` / `SpeechEngines` 中接入。
- 新 API 路由：在 `ApiRoutes.handle()` 中添加。

## MNN 引擎（1.6.0）
- 模型 = 一个文件夹（taobao-mnn 仓库全部文件）。`ModelRepository` 把含 `config.json` + `llm.mnn` 的子目录识别为 `ModelFormat.MNN`；可「导入 MNN 文件夹」（SAF 目录选择）或「下载 MNN 模型」（`MnnHub`：HF API 列文件 → 逐个断点续传到 `<名>.part/` → 改名）。
- 生成：先 `response(history, max_new_tokens=0)` 只做预填充（MNN prompt cache 复用共同前缀的 KV），再循环 `generate(1)` 逐 token 取流，便于取消 / 限速。结束后 `syncPromptCache(history + 去掉思考的回复)` 与 App 下一轮发回的历史对齐。
- 采样参数变更：对 MNN 打了一个小补丁 `Llm::reset_sampler()`（scripts/mnn-port-patch.py）。线程数在加载时确定（MNN 不支持运行中改线程，自适应线程对 MNN 不生效，限速 pacer 仍生效）。
- 思考：加载时用模板渲染探测是否支持 `enable_thinking`、是否在生成提示里预开 `<think>`；与 llama.cpp 路径一致地交给 StreamingThinkSplitter。
- 后端：设置 → 性能 → MNN 后端（CPU / GPU OpenCL）。OpenCL 不可用 / 加载失败 → CPU；OpenCL 加载或首轮生成导致进程崩溃 → 该模型以后自动用 CPU（MnnGpuGuard）。
- NPU（QNN / Hexagon）未编入：MNN 的 NPU 路径需要用高通 QNN SDK 的 x86 工具按芯片离线转换专门导出的模型，taobao-mnn 通用模型不能直接上 NPU。

## 1.7.1：统一后端 / 资源档 / 按模型配置 / 上下文管理
- **后端**（`engine/Backend.kt`）：`Backend { CPU, GPU, NPU }`；`BackendCapabilities.options(format, file, env)` 列出每个引擎对该模型可用的后端及不可用原因（GGUF：CPU/GPU-OpenCL；LiteRT：通用文件 CPU/GPU，`_sm8750` 编译文件只能 NPU；MNN：CPU/GPU-OpenCL）。`EngineManager.load` 按 `BackendPlanner.chain`（所选后端起，NPU→GPU→CPU，跳过崩溃过的）逐个尝试；引擎内部不再回退。`BackendCrashMemory` 在加载与首次生成前后 arm/disarm，上次退出确为崩溃（ApplicationExitInfo）才记为坏后端。`ModelDetails.activeBackend/fallbackNote` 给界面显示实际后端。旧的 GGUF OpenCL 开关、LiteRT 后端、MNN 后端、NpuCrashGuard、MnnGpuGuard 已移除，旧值由 `AppSettings.migrated()` 迁到 `defaultBackends`。
- **资源档**（`engine/Resources.kt`）：低/中/高/全部 → `ResourcePlan`（线程、绑核、nice、批大小、自动是否用 GPU、MNN power）。不再有 TokenPacer/限速。`NativeThreadPolicy.apply` 通过 JNI `sched_setaffinity + setpriority` 作用于引擎线程，ggml/XNNPACK/MNN 之后创建的工作线程继承。
- **按模型配置**（`data/ModelConfig.kt`）：`ModelOverrides`（全可空）存 `files/model_configs.json`；`EffectiveModelConfig.resolve(settings, format, overrides)` 合并。UI：`ui/models/ModelConfigSheet.kt`（模型选择器 ⚙、模型管理 ⚙）。
- **上下文**（`util/ContextPlanner.kt`，纯函数）：滑动窗口 / token 预算 / 自动摘要 / KV 平移（llama.cpp `shift_context`，其他引擎退化为预算截断）+ 手动分隔 `Conversation.contextStartAfterId`。裁剪按整轮、按块移动以保持前缀稳定（KV/prompt cache 复用）。用量显示 `ContextMeter`（输入框上方），菜单 `ContextSheet`。
- **llama.cpp 新参数**：presence/frequency 惩罚、种子、惩罚窗口、KV F16/Q8_0/Q4_0、mmap、批大小、context shift（`n_keep`）。
