<p align="center"><a href="README.md">中文</a> | <b>English</b></p>

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
  <b>All-in-one on-device AI for Android</b><br/>
  Offline · Your data stays on the phone · Free and open source
</p>

<p align="center">
  <a href="https://github.com/WWRJ3321/IRIDIS/releases/latest"><b>Download APK</b></a> ·
  <a href="docs/BUILD.md">Build</a> ·
  <a href="docs/API.md">API</a> ·
  <a href="docs/ARCHITECTURE.md">Architecture</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

<p align="center">
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B%20arm64-3DDC84?logo=android&logoColor=white"/>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"/></a>
  <a href="CHANGELOG.md"><img alt="Version" src="https://img.shields.io/badge/version-2.3.2-5660F0"/></a>
</p>

<p align="center"><img src="docs/assets/cover.webp" alt="IRIDIS: on-device AI for Android" width="720"></p>

---

## Highlights

| | |
|---|---|
| **Four inference engines** | llama.cpp · MNN · LiteRT-LM · GenieX (GGUF on the Qualcomm NPU), plus a built-in Hexagon runtime; picks the fastest of CPU / GPU / NPU for your phone |
| **Fully offline** | Inference, voice and history stay on the device; works in airplane mode |
| **Character cards** | Import / export SillyTavern V2/V3 cards (PNG / JSON / CHARX), switchable greetings, keyword-triggered embedded lorebook, optional model and voice binding |
| **Agent** | Files, web search, on-device image generation, remote MCP tools, with undoable changes; GUI agent (test) can operate the phone for you |
| **Memory & knowledge base** | Long-term memory, local document search (folders, editing, pasted text), editable messages with multiple regenerated versions |
| **On-device images** | Stable Diffusion 1.5 / Anything V5 on the Snapdragon NPU (QNN) |
| **Local voice & translation** | Offline speech recognition and TTS (or the system TTS), hands-free chat; local translation (current model or offline Bergamot packs) |
| **Model downloads** | Recommended list, online GGUF search, automatic source choice (ModelScope / hf-mirror / Hugging Face), import from file; GGUF + mmproj for images |
| **LAN API** | OpenAI / Ollama compatible: your phone becomes a model server |
| **More** | 7 UI languages, HTML / Three.js preview, Files page, chat trash, storage cleanup |
| **Tuned for phones** | KV cache reuse, thermal throttling and over-heat stop, idle unload |

## Quick start

1. Install the APK from [Releases](https://github.com/WWRJ3321/IRIDIS/releases/latest)
2. Home model menu › Models & loading › Download models: pick from the recommended list, search GGUF online, or import a file
3. Start chatting; turn on Agent when you need tools

**Requirements**: Android 12+, arm64 (Snapdragon 8 Gen 1 or newer); 12 GB RAM recommended for models above 4B.

> [!NOTE]
> Upgrading from IRIS LocalAI: from 2.1.0 the package is `com.iridis` and cannot install over the old app. Export a backup in the old app, then import it in Settings › Storage › Backup & restore.

## Engines

| Engine | Format | Hardware |
|---|---|---|
| llama.cpp | `.gguf` (mmproj for images) | CPU · GPU (OpenCL) · NPU (built-in Hexagon runtime, test) |
| GenieX | `.gguf` | Qualcomm NPU (runtime downloaded in the app) |
| MNN-LLM | MNN model folder | CPU · GPU (OpenCL) |
| LiteRT-LM | `.litertlm` | GPU · Qualcomm NPU |

- LiteRT on the NPU needs a Snapdragon 8-series chip and the QNN runtime, downloaded in the app after accepting Qualcomm's license.
- GGUF on the NPU: GenieX first when installed; otherwise (or when it fails) the llama.cpp Hexagon runtime bundled in the APK (HTP v73+, i.e. Snapdragon 8 Gen 2 and newer), unpacked on first use; then GPU / CPU fallback. Not verified on every device.
- Q1_0 / Q2_0 low-bit GGUF (e.g. PrismML Bonsai) run on CPU / GPU only; PrismML's private PQ2_0 / PTQ1_0 are not supported and the app asks for the Q2_0 file.

## Local API

Turn it on in Settings › API server. It listens on localhost only by default; LAN access must be enabled manually and requires an API key.

```bash
curl http://127.0.0.1:8686/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello"}]}'
```

## Privacy

Chats, memory and files stay on the phone, with cloud backup disabled. The app goes online only to download models / voice packs / translation packs / runtimes (with a quick speed probe of the download sources), when the agent searches or fetches the web, when you use a remote MCP server you added, or when you enable the API.

## Build

```bash
sh scripts/fetch-deps.sh
cp local.properties.example local.properties   # set sdk.dir / ndk.path
./gradlew assembleFullRelease                  # llama.cpp only: assembleLiteRelease
```

JDK 17 · Android SDK 35 · NDK r27c+ · CMake. See [docs/BUILD.md](docs/BUILD.md).

## License

[Apache-2.0](LICENSE). Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The QNN runtime is proprietary Qualcomm software and is not distributed with the source; parts of the image-generation native library derive from Local Dream (CC BY-NC 4.0, non-commercial); some optional voice models are non-commercial only.

> AI output can be wrong; do not rely on it for medical, legal or financial decisions. The agent can go online and change workspace files, and the GUI agent can operate the screen, so only enable it for content you trust.

UI languages: Simplified Chinese, Traditional Chinese, English, Japanese, German, Spanish, Hindi.
