package com.iris.localai.imagegen

/** Runs one text-to-image (or image-to-image) generation. Implementations are native (QNN / MNN / Hexagon). */
interface ImageGenEngine {
    /** PNG bytes of the result; [onProgress] gets (finished denoising step, total steps). */
    suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray>

    /** Stops the generation in progress (the running [generate] then fails). */
    fun cancel()
}

/** the 生图 page ships before the native runtime; every request fails with [MESSAGE]. */
class StubEngine : ImageGenEngine {
    override suspend fun generate(req: ImageRequest, model: ImageModel, onProgress: (step: Int, total: Int) -> Unit): Result<ByteArray> =
        Result.failure(IllegalStateException(MESSAGE))

    override fun cancel() = Unit

    companion object { const val MESSAGE = "运行库未就绪（下个版本接入）" }
}
