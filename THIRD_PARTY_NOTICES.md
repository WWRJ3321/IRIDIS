# 第三方组件声明

**IRIDIS（原 IRIS LocalAI）本身以 Apache License 2.0 授权**，见根目录 `LICENSE` 与 `NOTICE`。以下第三方组件各自遵循其许可证；许可证全文随 APK 附带于 `app/src/main/assets/licenses/`（清单见 `index.json`），App 内的开源许可页面可查看。

## 编译进 APK 的代码
| 组件 | 版本 | 许可证 | 义务 |
|---|---|---|---|
| llama.cpp / ggml (ggml-org) | master @ 785322d (2026-11-01) | MIT | 保留版权声明与许可证全文 |
| LES 前瞻专家调度（OpenBMB/llama.cpp-omni：llama-moe-slot、llama-moe-experts、tools/moe） | a64932e (2026-10-19)，移植进 libiris_llama（native-patches/llama-les.patch） | MIT | 保留版权声明与许可证全文 |
| nlohmann/json, cpp-httplib 等 llama.cpp vendor 库 | 随 llama.cpp | MIT | 同上（见 third_party/llama.cpp/licenses） |
| MNN (alibaba) | master @ 9f097c5，经 scripts/mnn-port-patch.py 修改 | Apache-2.0 | 附许可证；按 Apache-2.0 第 4(b) 条，补丁脚本在每个被修改的文件首行写入 `// Modified by IRIDIS: <改动说明>`（涉及 OpenCLRuntime.cpp、llm.hpp、llm.cpp、omni.cpp、MoEModule.cpp、PipelineModule.cpp） |
| LiteRT-LM (Google) | 0.18.0 (Maven) | Apache-2.0 | 附许可证与 NOTICE |
| sherpa-onnx (k2-fsa) | 1.13.8 AAR | Apache-2.0 | 附许可证 |
| ONNX Runtime（静态链接于 sherpa-onnx） | — | MIT | 保留声明 |
| Khronos OpenCL 头文件 | app/src/main/cpp/opencl | Apache-2.0 | 保留 CL/LICENSE |
| AndroidX / Jetpack Compose / Material Icons | BOM 2024.12.01 | Apache-2.0 | 附许可证 |
| Kotlin stdlib / kotlinx.coroutines / kotlinx.serialization | 2.3.21 / 1.9.0 / 1.7.3 | Apache-2.0 | 附许可证 |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | 附许可证与 NOTICE（assets/licenses/notice-apache-commons.txt） |
| Apache Commons IO / Lang3 / Codec（Commons Compress 的传递依赖） | 2.16.1 / 3.16.0 / 1.17.1 | Apache-2.0 | 同上 |
| bergamot-translator (browsermt) | 9271618 + 本项目补丁，编译为 libiris_bergamot.so（full 版，scripts/build-bergamot.sh） | MPL-2.0 | 附许可证全文（assets/licenses/mpl-2.0-bergamot.txt）；修改（构建补丁）见 scripts/build-bergamot.sh，修改后的 MPL 文件按 MPL-2.0 以源码形式提供 |
| Marian NMT（browsermt 分支）/ ruy / SentencePiece / ssplit-cpp / PCRE2 / yaml-cpp / zlib（bergamot 依赖，静态链接） | 随 bergamot | MIT / Apache-2.0 / Apache-2.0 / Apache-2.0 / BSD-3-Clause / MIT / Zlib | 保留各自声明（assets/licenses/mit-marian.txt、bsd-pcre2.txt、mit-yaml-cpp.txt、zlib-notice.txt） |
| llama.cpp Hexagon 运行库（libiris-hexagon.so、libiris-htp-v73…v85.so） | 取自 llama.cpp 官方 release b12032 android-arm64-snapdragon 包，改名（patchelf / soname 替换），作为 full 版 assets | MIT | 同 llama.cpp |
| Local Dream (xororz/local-dream) | libiris_imagegen.so 的生图 NPU 管线部分源自其代码；Anything V5 的 NPU 模型文件由用户运行时从其 HF 仓库下载 | **CC BY-NC 4.0（非商业）** | ⚠ 署名；**不得用于商业用途**；许可证全文 assets/licenses/cc-by-nc-4.0-local-dream.txt |
| three.js | r170（HTML 预览内置，assets） | MIT | 保留版权声明（assets/licenses/mit-three.js.txt） |
| Mika (wangzan101/Mika) | GUI 智能体的部分思路与代码（ScreenDiagnosis、StuckAdvice 等，已修改） | Apache-2.0 | 附许可证；修改说明见 assets/licenses/notice-mika.txt |
| Haze (chrisbanes) | 1.2.2 | Apache-2.0 | 附许可证 |
| ML Kit 中文文字识别 (Google) | 16.0.1（full 版，GUI 智能体 OCR 兜底） | ML Kit 服务条款 | 遵守 Google ML Kit Terms |
| Qualcomm GenieX Kotlin SDK 类 | 0.8.0 | BSD-3-Clause（原生运行库为高通条款，由用户下载） | 附许可证（assets/licenses/bsd-3-geniex.txt） |
| LiteRT Dispatch (Qualcomm) | assets/npu/libLiteRtDispatch_Qualcomm.so（full 版），取自 Google AI Edge Gallery 1.0.20 随附的预编译库，与本项目使用的 litertlm-android 0.18.0 配套，运行时搭配 QNN/QAIRT 2.50.0（见下表，从 Maven Central 下载） | Apache-2.0（Google，源码公开） | 附许可证；注明来源版本 |

## 闭源组件（不随源码/APK 分发，运行时下载）
| 组件 | 来源 | 许可证 | 义务 / 风险 |
|---|---|---|---|
| Qualcomm AI Engine Direct (QNN) 2.50.0：libQnnHtp、libQnnSystem、libQnnHtpV*Stub/Skel | Maven Central `com.qualcomm.qti:qnn-runtime:2.50.0`（repo1.maven.org，备用 maven.aliyun.com 镜像）；仅在用户于 App 内接受 Qualcomm 许可证后、点「安装 NPU 运行库」时下载（full 版） | Qualcomm 专有许可证（非开源） | 不提交进仓库、不打包进 APK；安装前展示许可证并取得用户同意 |

## 运行时由用户下载的模型（生图 / 翻译）
| 模型 | 来源 | 许可证 |
|---|---|---|
| Stable Diffusion v1.5（QNN） | Qualcomm AI Hub | CreativeML OpenRAIL-M |
| Anything V5.0（NPU 版） | Local Dream HF xororz/sd-qnn | CreativeML OpenRAIL-M（NPU 转换包遵循 Local Dream 的 CC BY-NC 4.0） |
| Firefox Translations zh↔en（Bergamot/Marian） | Mozilla，经 HF TiberiuCristianLeon/Bergamot@18008da9 下载 | Mozilla 以 MPL-2.0 发布（HF 副本标注 CC-BY-4.0）；未修改 |

## 未使用
- OlliteRT（NightMean/OlliteRT）：仅作调研参考，未使用其代码。

## 随 APK 打包的模型
| 文件 | 来源 | 许可证 |
|---|---|---|
| assets/vad/silero_vad.onnx | snakers4/silero-vad | MIT |

## 运行时由用户下载（不随源码/APK 分发）
| 语音包 | 上游 | 许可证 | 备注 |
|---|---|---|---|
| Kokoro v1.1 多语言 | hexgrad/Kokoro → csukuangfj 转换 | Apache-2.0 | |
| Matcha zh-baker | csukuangfj/matcha-icefall-zh-baker | 训练数据标贝 Databaker（BZNSYP）：**仅限非商业** | ⚠ 非商用 |
| ZipVoice distill zh-en (Emilia) | k2-fsa/ZipVoice | 代码 Apache-2.0；蒸馏权重在 Emilia 数据集（CC BY-NC 4.0）上训练 → **权重仅限非商业** | ⚠ 非商用 |
| VITS Piper zh_CN xiao_ya | rhasspy/piper-voices → csukuangfj 转换 | 模型卡：从零训练，数据集 openspeech/BZNSYP（标贝），**仅限非商业**（https://huggingface.co/rhasspy/piper-voices/blob/main/zh/zh_CN/xiao_ya/medium/MODEL_CARD） | ⚠ 非商用 |
| VITS MeloTTS zh_en | myshell-ai/MeloTTS | MIT | |
| SenseVoice / Paraformer | FunASR (阿里达摩院) | FunASR 模型许可（要求署名） | 使用时保留署名 |
| Qwen3-ASR 0.6B | Qwen | Apache-2.0 | |
| Zipformer CTC zh | k2-fsa icefall | Apache-2.0 | |
| Vocos 声码器 | k2-fsa 发布 | MIT | |

LLM 权重（Gemma、Qwen、Llama、Agents-A1 等）全部由用户自行从 Hugging Face / hf-mirror 下载，适用各自模型条款（如 Gemma Terms of Use、Llama Community License），本项目不分发。

标注 ⚠ 非商用 的语音包由用户自行选择下载，App 与本仓库均不分发；将 IRIDIS 用于商业用途时请勿使用这些语音包。
