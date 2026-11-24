// 3.0.0 Beta5: libiris_upscale.so only runs the ESRGAN upscaler, which needs no tokenizer.
// Replaces sd.cpp's src/tokenizers/vocab/vocab.cpp (≈30 MB of embedded vocabularies).
#include "vocab.h"
std::string load_clip_merges() { return {}; }
std::string load_qwen2_merges() { return {}; }
std::string load_mistral_merges() { return {}; }
std::string load_mistral_vocab_json() { return {}; }
std::string load_t5_tokenizer_json() { return {}; }
std::string load_umt5_tokenizer_json() { return {}; }
std::string load_gemma_merges() { return {}; }
std::string load_gemma_vocab_json() { return {}; }
