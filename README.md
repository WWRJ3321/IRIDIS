<p align="center"><b>中文</b> | <a href="README.en.md">English</a></p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/icon-dark.png">
    <img src="docs/assets/icon-light.png" alt="IRIDIS logo" width="112">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/wordmark-dark.svg">
    <img src="docs/assets/wordmark-light.svg" alt="IRIDIS" width="320">
  </picture>
</p>

<p align="center">
  <b>一站式安卓本地 AI 平台</b><br/>
  离线运行 · 数据不出手机 · 免费开源<br/>
  <sub>All-in-one on-device AI for Android. Offline, private, open source.</sub>
</p>

<p align="center">
  <a href="https://github.com/WWRJ3321/IRIDIS/releases/latest"><b>下载 APK</b></a> ·
  <a href="docs/BUILD.md">构建</a> ·
  <a href="docs/API.md">API</a> ·
  <a href="docs/ARCHITECTURE.md">架构</a> ·
  <a href="CHANGELOG.md">更新日志</a>
</p>

<p align="center">
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B%20arm64-3DDC84?logo=android&logoColor=white"/>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"/></a>
  <a href="CHANGELOG.md"><img alt="Version" src="https://img.shields.io/badge/version-2.3.2-5660F0"/></a>
</p>

<p align="center"><img src="docs/assets/cover.webp" alt="IRIDIS 一站式安卓本地 AI 平台" width="720"></p>

---

## 亮点

| | |
|---|---|
| **四种推理引擎** | llama.cpp · MNN · LiteRT-LM · GenieX（高通 NPU 跑 GGUF），另有内置 Hexagon 运行库；CPU / GPU / NPU 按机型选最快 |
| **完全离线** | 推理、语音、记录都在本机，飞行模式照常可用 |
| **角色卡** | 导入 / 导出 SillyTavern V2/V3 角色卡（PNG / JSON / CHARX），开场白可切换，内置世界书按关键词触发，可绑定模型和音色 |
| **智能体** | 读写文件、联网搜索、本地生图、远程 MCP 工具，改动可撤销；GUI 智能体（测试）可代你操作手机 |
| **记忆与知识库** | 长期记忆、本地文档检索（文件夹、编辑、粘贴文字），消息可编辑、可多版本重新生成 |
| **本地生图** | Stable Diffusion 1.5 / Anything V5，骁龙 NPU（QNN）运行 |
| **本地语音与翻译** | 离线识别与合成（也可用系统 TTS），免提对话；本地翻译（当前模型或 Bergamot 离线包） |
| **模型下载** | 推荐列表、在线搜索 GGUF、魔搭 / hf-mirror / Hugging Face 自动选源，可从文件导入；GGUF 配 mmproj 可看图 |
| **局域网 API** | OpenAI / Ollama 兼容，手机就是模型服务器 |
| **更多** | 7 种界面语言、HTML / Three.js 预览、文件页、历史回收站、存储空间清理 |
| **为手机调校** | KV 缓存复用、温度限速与过热停止、空闲卸载 |

## 快速开始

1. 从 [Releases](https://github.com/WWRJ3321/IRIDIS/releases/latest) 安装 APK
2. 首页模型菜单 ›「模型与加载」›「下载模型」：从推荐列表下载、在线搜索 GGUF，或从文件导入
3. 开聊；需要工具时打开「智能体」

**要求**：Android 12+、arm64（骁龙 8 Gen 1 及以上）；4B 以上模型建议 12 GB 内存。

> [!NOTE]
> 从 IRIS LocalAI 升级：2.1.0 起包名改为 `com.iridis`，不能覆盖安装。先在旧版导出备份，装新版后在 设置 › 存储 › 备份与恢复 导入。

## 引擎

| 引擎 | 格式 | 硬件 |
|---|---|---|
| llama.cpp | `.gguf`（可配 mmproj 看图） | CPU · GPU (OpenCL) · NPU（内置 Hexagon 运行库，测试） |
| GenieX | `.gguf` | 高通 NPU（App 内下载运行库） |
| MNN-LLM | MNN 模型文件夹 | CPU · GPU (OpenCL) |
| LiteRT-LM | `.litertlm` | GPU · 高通 NPU |

- LiteRT 的 NPU 需骁龙 8 系芯片，并在 App 内同意高通许可后下载 QNN 运行库。
- GGUF 走 NPU：有 GenieX 时先用 GenieX，失败或没有时用 APK 内置的 llama.cpp Hexagon 运行库（HTP v73 及以上，即骁龙 8 Gen 2 起），首次使用自动解压；仍失败则回退 GPU / CPU。均未在所有机型上验证。
- Q1_0 / Q2_0 低比特 GGUF（如 PrismML Bonsai）只走 CPU / GPU；PrismML 专用的 PQ2_0 / PTQ1_0 不支持，会提示改下 Q2_0。

## 本地 API

设置 › API 服务 中开启。默认只监听本机；开放局域网须手动开启并使用 API Key。

```bash
curl http://127.0.0.1:8686/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好"}]}'
```

## 隐私

对话、记忆和文件只存于本机，禁止云备份。仅在这些情况下联网：下载模型 / 语音包 / 翻译包 / 运行库（下载前会对下载源测速）、智能体联网搜索或网页抓取、使用你添加的远程 MCP 服务、开启 API。

## 构建

```bash
sh scripts/fetch-deps.sh
cp local.properties.example local.properties   # 填 sdk.dir / ndk.path
./gradlew assembleFullRelease                  # 仅 llama.cpp：assembleLiteRelease
```

JDK 17 · Android SDK 35 · NDK r27c+ · CMake。详见 [docs/BUILD.md](docs/BUILD.md)。

## 许可证

[Apache-2.0](LICENSE)。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；QNN 运行库为高通专有软件，不随源码分发；生图原生库部分源自 Local Dream（CC BY-NC 4.0，非商业）；部分可选语音模型仅限非商业使用。

> AI 输出可能出错，请勿用于医疗、法律、金融等重要决策。智能体可联网、改动工作区文件，GUI 智能体可操作屏幕，请只对信任的内容开启。

界面语言：简体中文、繁體中文、English、日本語、Deutsch、Español、हिन्दी。
