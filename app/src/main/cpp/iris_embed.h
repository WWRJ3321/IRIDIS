// IRIS LocalAI — 2.5.1 知识库嵌入模型 (text → L2-normalised vector).
//
// A separate llama_model + llama_context, CPU only, mmap, embeddings=true, pooling from the model.
// Never shares anything with iris::Engine (the chat model): loading / freeing one leaves the other alone.
#pragma once

#include <string>
#include <vector>

namespace iris {

struct Embedder;

/** nullptr on failure ([error] says why). n_ctx = n_batch = n_ubatch = 2048. */
Embedder *embed_load(const std::string &path, int threads, std::string &error);

/** Output dimension (llama_model_n_embd_out). */
int embed_dim(const Embedder *e);

/** One text → [out] (L2-normalised). Thread-safe per embedder (serialised). */
bool embed_text(Embedder *e, const std::string &text, std::vector<float> &out, std::string &error);

void embed_free(Embedder *e);

} // namespace iris
