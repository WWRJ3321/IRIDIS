# 构建指南

## 环境
| 项 | 版本 |
|----|------|
| JDK | 17 或 21 |
| Android SDK | platform 35、build-tools 35.0.0 |
| NDK | r27c（`27.2.12479018`）或更新；arm64 Linux 主机可用第三方 arm64-host NDK（`local.properties` 中 `ndk.path`） |
| CMake | ≥ 3.22（SDK Manager 安装，或 `cmake.dir` 指定） |
| Gradle | 8.11.1（wrapper 自动下载） |
| AGP / Kotlin | 8.7.3 / 2.3.21 |

## 步骤
```bash
sh scripts/fetch-deps.sh          # llama.cpp 源码 + sherpa-onnx AAR + MNN 预编译库（均 SHA-256 校验）
cp local.properties.example local.properties   # 填写 sdk.dir
./gradlew testFullDebugUnitTest   # JVM 单元测试（lite 版用 testLiteDebugUnitTest）
./gradlew assembleFullDebug       # app/build/outputs/apk/full/debug/（lite 版：assembleLiteDebug）
```
产品 flavors：`lite`（仅 llama.cpp）/ `full`（另含 LiteRT-LM + MNN），Gradle 任务名都带 flavor 前缀。
原生库由 Gradle 调用 CMake 从 llama.cpp 源码编译（首次约 5–15 分钟），静态链接为单个 `libiris_llama.so`，按 `armv8.2-a+dotprod+i8mm+fp16` 优化并启用 KleidiAI 内核。无网络环境可加 `-Piris.kleidiai=OFF` 跳过 KleidiAI 下载。
### Release
在项目根目录创建 `keystore.properties`（不要提交；路径相对项目根目录）：
```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```
然后 `./gradlew assembleFullRelease`（或 `assembleLiteRelease`）。未配置时产出未签名 APK，而不会偷偷使用 debug 签名。

## 原生引擎测试（无需手机）
```bash
curl -L -o /tmp/smol.gguf https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf
sh scripts/host-test.sh /tmp/smol.gguf
```
覆盖：模板格式化、贪心解码确定性、多轮 KV 复用、编辑历史后的部分失效、跨线程取消（生成中 / prefill 中）、取消后状态一致性、回调中止、超长历史裁剪、超长单条消息报错、reset、卸载重载。

## arm64 Linux / Termux 主机
- `local.properties`：`ndk.path=/path/to/android-ndk-r30`（arm64-host 版本）
- `~/.gradle/gradle.properties`：`android.aapt2FromMavenOverride=/path/to/arm64/aapt2`（官方 aapt2 只有 x86_64）
- 内存 ≤ 8 GB 时建议 `org.gradle.jvmargs=-Xmx2560m` 与 `kotlin.compiler.execution.strategy=in-process`

## 模型
- **GGUF**：任意 llama.cpp 支持的模型。推荐 Qwen3-4B/8B Q4_K_M（支持深度思考）、Gemma-3-4B-it、DeepSeek-R1-Distill-Qwen-7B。
- **LiteRT-LM**：`litert-community/*` 上的通用 `.litertlm`。`*.qualcomm.sm8750.litertlm` 是 NPU 专用编译版本，需对应芯片才能加载，通用手机请用不带芯片后缀的文件。
- 导入方式：App 内「模型管理 › 导入」，或直接放到 `/sdcard/Android/data/com.iridis/files/models/`（开启「全部文件访问」后为 `/sdcard/Download/IRIDIS/models/`）。

## MNN（full 版第三引擎）
- 预编译静态库在 `third_party/mnn/`（MNN master @ 9f097c5 + `scripts/mnn-port-patch.py` 补丁，Apache-2.0 修改声明见该脚本）。`scripts/fetch-deps.sh` 会自动从 GitHub Release 下载并校验；从源码重建：`NDK=/path/to/ndk-r30 sh scripts/build-mnn-android.sh`（CPU/ARM82/KleidiAI + OpenCL + LLM，约 15–30 分钟）。
- full flavor 传 `-DIRIS_MNN=ON`，CMake 把 libMNN.a 以 whole-archive 方式链进单个 `libiris_mnn.so`（strip 后 ≈6 MB，APK 内压缩 ≈2.3 MB）。
- 主机冒烟测试：`sh scripts/mnn-host-test.sh /path/to/Qwen3-0.6B-MNN [mnn 源码目录]`（加载、贪心确定性、多轮 KV 复用、回调/跨线程取消、思考模式、max tokens、上下文裁剪）。

## 其他原生库（2.2.13 起）
- 可用预编译库构建：`-Piris.prebuiltNative=prebuilt-native`（目录内放 `arm64-v8a/libiris_*.so`，不入库）。
- `scripts/build-llama-native.sh`：NDK 独立构建 `libiris_llama.so`（llama.cpp 785322d，含 OpenCL / KleidiAI / mtmd）。
- `scripts/build-llama-htp.sh`：构建 `libiris_lhtp.so` 并整理 llama.cpp 官方 Hexagon 运行库到 `prebuilt-assets/htp/`（full 版 assets）。
- `scripts/build-bergamot.sh`：构建离线翻译 `libiris_bergamot.so`。
- 生图 `libiris_imagegen.so` 与 QNN 运行库：QNN 2.50.0 由 App 运行时从 Maven Central 下载，不随源码分发。
