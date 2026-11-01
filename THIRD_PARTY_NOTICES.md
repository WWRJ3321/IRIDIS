# 第三方组件声明

**IRIDIS（原 IRIS LocalAI）本身以 Apache License 2.0 授权**，见根目录 `LICENSE` 与 `NOTICE`。以下第三方组件各自遵循其许可证；许可证全文随 APK 附带于 `app/src/main/assets/licenses/`（清单见 `index.json`），App 内的开源许可页面可查看。

## 编译进 APK 的代码
| 组件 | 版本 | 许可证 | 义务 |
|---|---|---|---|
| llama.cpp / ggml (ggml-org) | master @ 785322d (2026-11-01) | MIT | 保留版权声明与许可证全文 |
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
| LiteRT Dispatch (Qualcomm) | assets/npu/libLiteRtDispatch_Qualcomm.so（full 版），取自 Google AI Edge Gallery 1.0.20 随附的预编译库，与本项目使用的 litertlm-android 0.18.0 配套，运行时搭配 QNN/QAIRT 2.50.0（见下表，从 Maven Central 下载） | Apache-2.0（Google，源码公开） | 附许可证；注明来源版本 |

## 闭源组件（不随源码/APK 分发，运行时下载）
| 组件 | 来源 | 许可证 | 义务 / 风险 |
|---|---|---|---|
| Qualcomm AI Engine Direct (QNN) 2.50.0：libQnnHtp、libQnnSystem、libQnnHtpV*Stub/Skel | Maven Central `com.qualcomm.qti:qnn-runtime:2.50.0`（repo1.maven.org，备用 maven.aliyun.com 镜像）；仅在用户于 App 内接受 Qualcomm 许可证后、点「安装 NPU 运行库」时下载（full 版） | Qualcomm 专有许可证（非开源） | 不提交进仓库、不打包进 APK；安装前展示许可证并取得用户同意 |

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
