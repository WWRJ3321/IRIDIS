package com.iris.localai.imagegen

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 2.4.6 生图独立进程: every generation (sd.cpp GPU/CPU/NPU and the QNN SD1.5 graphs) runs in the
 * `:imagegen` process. A native crash (OpenCL driver, Hexagon, OOM kill) now ends only that process:
 * the chat, the agent, the terminal and the local API keep running, and the 生图 page shows an error.
 * The GPU / HTP crash markers SdCppEngine writes before each attempt are files, so the next
 * attempt (in a fresh process) still falls back to CPU as before.
 *
 * Protocol (Messenger, one generation at a time): GENERATE(bundle) → PROGRESS(step,total)* → RESULT(path | error).
 * The PNG goes through a file in cacheDir/imagegen-out (a Binder transaction is limited to ~1 MB).
 */
object ImageGenIpc {
    const val GENERATE = 1
    const val CANCEL = 2
    const val RELEASE = 3
    const val RELEASE_WHEN_IDLE = 4
    const val PROGRESS = 10
    const val RESULT = 11

    const val ERR_GENERIC = 0
    const val ERR_LOW_MEMORY = 1

    fun isImageGenProcess(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":imagegen")

    fun toBundle(req: ImageRequest, modelId: String, forceOnce: Boolean) = Bundle().apply {
        putString("prompt", req.prompt); putString("negative", req.negative)
        putInt("width", req.width); putInt("height", req.height); putInt("steps", req.steps)
        putFloat("cfg", req.cfg); putLong("seed", req.seed); putString("init", req.initImagePath)
        putFloat("strength", req.strength); putString("model", modelId); putBoolean("force", forceOnce)
    }

    fun requestOf(b: Bundle) = ImageRequest(
        prompt = b.getString("prompt").orEmpty(), negative = b.getString("negative").orEmpty(),
        width = b.getInt("width"), height = b.getInt("height"), steps = b.getInt("steps"),
        cfg = b.getFloat("cfg"), seed = b.getLong("seed"), initImagePath = b.getString("init"),
        strength = b.getFloat("strength"),
    )
}

/** Runs in `:imagegen`. */
class ImageGenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thread = HandlerThread("iris-imagegen-ipc").apply { start() }
    private var job: Job? = null
    private val engines by lazy { ImageGenEngines.get(this) }

    private val messenger by lazy { Messenger(Handler(thread.looper) { msg -> handle(msg); true }) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(msg: Message) {
        when (msg.what) {
            ImageGenIpc.GENERATE -> {
                val reply = msg.replyTo ?: return
                val b = msg.data
                job = scope.launch { run(b, reply) }
            }
            ImageGenIpc.CANCEL -> engines.cancel()
            ImageGenIpc.RELEASE -> scope.launch { runCatching { engines.release() } }
            ImageGenIpc.RELEASE_WHEN_IDLE -> runCatching { engines.releaseWhenIdle() }
        }
    }

    private suspend fun run(b: Bundle, reply: Messenger) {
        val id = b.getString("model").orEmpty()
        val model = ImageCatalog.all.firstOrNull { it.id == id } ?: ImageCatalog.byId(id)
        val result: Result<ByteArray> = if (model == null) Result.failure(IllegalStateException("unknown model $id")) else {
            if (b.getBoolean("force")) engines.sdEngine().forceOnce = true
            engines.generateLocal(ImageGenIpc.requestOf(b), model) { step, total ->
                runCatching { reply.send(Message.obtain(null, ImageGenIpc.PROGRESS, step, total)) }
            }
        }
        val out = Message.obtain(null, ImageGenIpc.RESULT)
        out.data = Bundle().apply {
            result.onSuccess { png ->
                val dir = File(cacheDir, "imagegen-out").apply { mkdirs() }
                val f = File(dir, "out-${System.nanoTime()}.png")
                runCatching { f.writeBytes(png) }.onSuccess { putString("path", f.path) }
                    .onFailure { putString("error", it.message ?: it.javaClass.simpleName) }
            }.onFailure { e ->
                putString("error", e.message ?: e.javaClass.simpleName)
                putInt("kind", if (e is SdRules.LowMemory) ImageGenIpc.ERR_LOW_MEMORY else ImageGenIpc.ERR_GENERIC)
            }
        }
        runCatching { reply.send(out) }
    }

    override fun onDestroy() {
        runCatching { engines.release() }
        thread.quitSafely()
        super.onDestroy()
    }
}

/** Main-process client of [ImageGenService]. */
class RemoteImageGen(private val context: Context) {
    class ProcessDied(message: String) : IllegalStateException(message)

    private val lock = Mutex()
    private val replies = HandlerThread("iris-imagegen-client").apply { start() }
    @Volatile private var service: Messenger? = null
    @Volatile private var onDeath: (() -> Unit)? = null
    @Volatile var busy = false
        private set

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
            binder?.runCatching { linkToDeath({ service = null; onDeath?.invoke() }, 0) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; onDeath?.invoke() }
        override fun onBindingDied(name: ComponentName?) {
            service = null; onDeath?.invoke()
            runCatching { context.unbindService(this) }; bound = false
        }
    }
    @Volatile private var bound = false

    private suspend fun connect(): Messenger? {
        service?.let { return it }
        if (!bound) {
            bound = runCatching {
                context.bindService(Intent(context, ImageGenService::class.java), conn,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            }.getOrDefault(false)
            if (!bound) return null
        }
        return withTimeoutOrNull(10_000) {
            while (service == null) kotlinx.coroutines.delay(20)
            service
        }
    }

    suspend fun generate(req: ImageRequest, model: ImageModel, forceOnce: Boolean, onProgress: (Int, Int) -> Unit): Result<ByteArray> = lock.withLock {
        val svc = connect() ?: return@withLock Result.failure(ProcessDied(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_proc_start_failed)))
        busy = true
        try {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(replies.looper) { msg ->
                    when (msg.what) {
                        ImageGenIpc.PROGRESS -> onProgress(msg.arg1, msg.arg2)
                        ImageGenIpc.RESULT -> {
                            val d = msg.data
                            val path = d.getString("path")
                            val r: Result<ByteArray> = if (path != null) {
                                val f = File(path)
                                runCatching { f.readBytes() }.also { f.delete() }
                            } else {
                                val err = d.getString("error") ?: "?"
                                Result.failure(if (d.getInt("kind") == ImageGenIpc.ERR_LOW_MEMORY) SdRules.LowMemory(err) else IllegalStateException(err))
                            }
                            onDeath = null
                            if (cont.isActive) cont.resume(r)
                        }
                    }
                    true
                }
                onDeath = {
                    onDeath = null
                    if (cont.isActive) cont.resume(Result.failure(ProcessDied(com.iris.localai.I18n.s(com.iris.localai.R.string.imagegen_proc_died))))
                }
                cont.invokeOnCancellation { cancel() }
                val m = Message.obtain(null, ImageGenIpc.GENERATE).apply {
                    data = ImageGenIpc.toBundle(req, model.id, forceOnce); replyTo = Messenger(handler)
                }
                runCatching { svc.send(m) }.onFailure { onDeath?.invoke() }
            }
        } finally { busy = false }
    }

    private fun post(what: Int) { runCatching { service?.send(Message.obtain(null, what)) } }
    fun cancel() = post(ImageGenIpc.CANCEL)
    fun release() = post(ImageGenIpc.RELEASE)
    fun releaseWhenIdle() = post(ImageGenIpc.RELEASE_WHEN_IDLE)

    companion object {
        @Volatile private var instance: RemoteImageGen? = null
        fun get(context: Context): RemoteImageGen =
            instance ?: synchronized(this) { instance ?: RemoteImageGen(context.applicationContext).also { instance = it } }
    }
}
