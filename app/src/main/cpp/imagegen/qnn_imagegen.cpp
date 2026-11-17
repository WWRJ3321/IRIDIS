// libiris_imagegen.so — IRIDIS 的 QNN context binary 运行器（2.6.12 全新编写，Apache-2.0）。
//
// 只依赖 Qualcomm 公开的 QNN C API 头文件（third_party/qnn-api，不提交进仓库）。运行库
// libQnnHtp.so / libQnnSystem.so / libQnnHtpV*Stub.so 由用户下载到 App 私有目录，这里按完整路径 dlopen，
// 不链接任何高通库，所以在所有 arm64 设备上都能加载。
//
// JNI（com.iris.localai.imagegen.QnnNative）：
//   nativeProbe(dir)                -> JSON {backendOk, deviceOk, error?, cdsp:{present, unsignedPd, arch}}
//   nativeOpen(dir, bin)            -> 句柄（文件里的第一个图），0 = 失败（nativeLastError）
//   nativeOpenGraph(dir, bin, name) -> 句柄（按名字挑图；空名 = 第一个）
//   nativeGraphNames(dir, bin)      -> 图名，'\n' 分隔；失败为 null
//   nativeInfo(h)                   -> JSON {graph, inputs:[{name,dims,dtype,bytes,scale?,offset?}], outputs:[…]}
//   nativeExecute(h, ins, outs)     -> null = 成功，否则错误信息（缓冲区为 direct ByteBuffer）
//   nativeClose(h)
//   nativeSetPerf(burst)            -> null = 已投票，否则原因
//
// 运行库全进程一份：没有打开的图时可以换目录（先整个卸载再加载新的）。

#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "HTP/QnnHtpCommon.h"
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "System/QnnSystemInterface.h"

#define TAG "iris_imagegen"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// 不依赖 C++ 标准库（只用 bionic），所以这里有一点点自己的容器。
void* operator new(size_t n) { void* p = malloc(n ? n : 1); if (!p) abort(); return p; }
void operator delete(void* p) noexcept { free(p); }
void operator delete(void* p, size_t) noexcept { free(p); }
inline void* operator new(size_t, void* p) noexcept { return p; }

namespace {

class Str {
public:
    Str() { set("", 0); }
    Str(const char* c) { set(c ? c : "", c ? strlen(c) : 0); }
    Str(const Str& o) { set(o.p_, o.n_); }
    Str& operator=(const Str& o) { if (this != &o) { free(p_); set(o.p_, o.n_); } return *this; }
    ~Str() { free(p_); }
    const char* c_str() const { return p_; }
    size_t size() const { return n_; }
    bool empty() const { return n_ == 0; }
    bool operator==(const Str& o) const { return n_ == o.n_ && memcmp(p_, o.p_, n_) == 0; }
    bool operator!=(const Str& o) const { return !(*this == o); }
    Str& append(const char* c, size_t k) {
        if (n_ + k + 1 > cap_) { cap_ = (n_ + k + 1) * 2; p_ = (char*) realloc(p_, cap_); if (!p_) abort(); }
        memcpy(p_ + n_, c, k); n_ += k; p_[n_] = 0; return *this;
    }
    Str& operator+=(const Str& o) { return append(o.p_, o.n_); }
    Str& operator+=(const char* c) { return append(c, strlen(c)); }
    Str& operator+=(char c) { return append(&c, 1); }
private:
    void set(const char* c, size_t k) { cap_ = k + 1; p_ = (char*) malloc(cap_); if (!p_) abort(); memcpy(p_, c, k); p_[k] = 0; n_ = k; }
    char* p_ = nullptr; size_t n_ = 0, cap_ = 0;
};
inline Str operator+(Str a, const Str& b) { a += b; return a; }
inline Str operator+(Str a, const char* b) { a += b; return a; }
inline Str operator+(const char* a, const Str& b) { Str r(a); r += b; return r; }

Str toStr(long long v) { char b[32]; snprintf(b, sizeof b, "%lld", v); return Str(b); }

template <typename T>
class Vec {
public:
    Vec() = default;
    Vec(const Vec& o) { for (size_t i = 0; i < o.n_; i++) push_back(o.p_[i]); }
    Vec& operator=(const Vec& o) { if (this != &o) { clear(); for (size_t i = 0; i < o.n_; i++) push_back(o.p_[i]); } return *this; }
    ~Vec() { clear(); free(p_); }
    void push_back(const T& v) {
        if (n_ == cap_) {
            size_t nc = cap_ ? cap_ * 2 : 4;
            T* np = (T*) malloc(nc * sizeof(T)); if (!np) abort();
            for (size_t i = 0; i < n_; i++) { new (&np[i]) T(p_[i]); p_[i].~T(); }
            free(p_); p_ = np; cap_ = nc;
        }
        new (&p_[n_++]) T(v);
    }
    void clear() { for (size_t i = 0; i < n_; i++) p_[i].~T(); n_ = 0; }
    size_t size() const { return n_; }
    bool empty() const { return n_ == 0; }
    T& operator[](size_t i) { return p_[i]; }
    const T& operator[](size_t i) const { return p_[i]; }
    T* data() { return p_; }
    const T* data() const { return p_; }
    T* begin() { return p_; } T* end() { return p_ + n_; }
    const T* begin() const { return p_; } const T* end() const { return p_ + n_; }
    T* rbeginPtr() { return n_ ? p_ + n_ - 1 : nullptr; }
private:
    T* p_ = nullptr; size_t n_ = 0, cap_ = 0;
};

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
struct Lock { Lock() { pthread_mutex_lock(&g_mu); } ~Lock() { pthread_mutex_unlock(&g_mu); } };

}  // namespace

namespace {

Str g_lastError;

void setError(const Str& e) {
    g_lastError = e;
    LOGW("%s", e.c_str());
}

Str errStr(const char* what, Qnn_ErrorHandle_t e) {
    char buf[160];
    snprintf(buf, sizeof buf, "%s 失败（QNN 错误 %llu）", what, (unsigned long long) e);
    return buf;
}

bool fileExists(const Str& p) {
    struct stat st{};
    return stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

// ---------------------------------------------------------------- runtime

struct Runtime {
    Str dir;
    Vec<void*> libs;  // 按加载顺序，卸载时倒序
    const QNN_INTERFACE_VER_TYPE* qnn = nullptr;
    const QNN_SYSTEM_INTERFACE_VER_TYPE* sys = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    bool deviceOk = false;
    int openGraphs = 0;
    uint32_t powerId = 0;
    bool havePowerId = false;
};

Runtime* g_rt = nullptr;

void teardown() {
    if (!g_rt) return;
    Runtime* r = g_rt;
    if (r->qnn) {
        if (r->device && r->qnn->deviceFree) r->qnn->deviceFree(r->device);
        if (r->backend && r->qnn->backendFree) r->qnn->backendFree(r->backend);
    }
    for (size_t i = r->libs.size(); i > 0; i--) dlclose(r->libs[i - 1]);
    delete r;
    g_rt = nullptr;
}

void* openLib(Runtime* r, const Str& path, bool required, Str& err) {
    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!h && required) {
        const char* e = dlerror();
        const char* base = strrchr(path.c_str(), '/');
        err = Str("无法加载 ") + (base ? base + 1 : path.c_str()) + "：" + (e ? e : "?");
    }
    if (h) r->libs.push_back(h);
    return h;
}

// 只在持有 g_mu 时调用。成功返回 g_rt；失败返回 nullptr 并设 g_lastError。
Runtime* ensureRuntime(const Str& dir) {
    if (g_rt && g_rt->dir == dir) return g_rt;
    if (g_rt) {
        if (g_rt->openGraphs > 0) {
            setError("NPU 运行库正被另一个模型占用，请先释放后再试");
            return nullptr;
        }
        teardown();
    }
    if (!fileExists(dir + "/libQnnHtp.so") || !fileExists(dir + "/libQnnSystem.so")) {
        setError("缺少 NPU 运行库（" + dir + "）");
        return nullptr;
    }
    // DSP 侧的 skel 从 ADSP_LIBRARY_PATH 里找，先放运行库目录
    Str adsp = dir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    auto* r = new Runtime();
    r->dir = dir;
    g_rt = r;
    Str err;
    // 先按完整路径加载各架构的 stub：libQnnHtp 之后按库名 dlopen 时直接命中已加载的
    static const char* const kArch[] = {"68", "69", "73", "75", "79", "81", "85"};
    for (const char* v : kArch) {
        Str p = dir + "/libQnnHtpV" + v + "Stub.so";
        if (fileExists(p)) openLib(r, p, false, err);
    }
    void* sysLib = openLib(r, dir + "/libQnnSystem.so", true, err);
    void* htpLib = sysLib ? openLib(r, dir + "/libQnnHtp.so", true, err) : nullptr;
    if (!htpLib) { setError(err); teardown(); return nullptr; }

    using GetProviders = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
    using GetSysProviders = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);
    auto getP = reinterpret_cast<GetProviders>(dlsym(htpLib, "QnnInterface_getProviders"));
    auto getS = reinterpret_cast<GetSysProviders>(dlsym(sysLib, "QnnSystemInterface_getProviders"));
    if (!getP || !getS) { setError("NPU 运行库缺少入口函数"); teardown(); return nullptr; }

    const QnnInterface_t** prov = nullptr;
    uint32_t n = 0;
    if (getP(&prov, &n) != QNN_SUCCESS || !prov) { setError("读取 QNN 接口失败"); teardown(); return nullptr; }
    for (uint32_t i = 0; i < n; i++) {
        const QnnInterface_t* p = prov[i];
        if (p && p->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            p->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            r->qnn = &p->QNN_INTERFACE_VER_NAME;
            break;
        }
    }
    const QnnSystemInterface_t** sprov = nullptr;
    uint32_t sn = 0;
    if (getS(&sprov, &sn) == QNN_SUCCESS && sprov) {
        for (uint32_t i = 0; i < sn; i++) {
            const QnnSystemInterface_t* p = sprov[i];
            if (p && p->systemApiVersion.major == QNN_SYSTEM_API_VERSION_MAJOR &&
                p->systemApiVersion.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
                r->sys = &p->QNN_SYSTEM_INTERFACE_VER_NAME;
                break;
            }
        }
    }
    if (!r->qnn || !r->sys) { setError("NPU 运行库版本不兼容"); teardown(); return nullptr; }

    Qnn_ErrorHandle_t e = r->qnn->backendCreate(nullptr, nullptr, &r->backend);
    if (e != QNN_SUCCESS) { setError(errStr("创建 HTP 后端", e)); teardown(); return nullptr; }
    if (r->qnn->deviceCreate) {
        e = r->qnn->deviceCreate(nullptr, nullptr, &r->device);
        if (e != QNN_SUCCESS) { setError(errStr("创建 HTP 设备", e)); r->device = nullptr; }
        else r->deviceOk = true;
    }
    return r;
}

// ---------------------------------------------------------------- binary info

struct TensorDesc {
    uint32_t id = 0;
    Str name;
    Qnn_TensorType_t type = QNN_TENSOR_TYPE_APP_WRITE;
    Qnn_TensorDataFormat_t format{};
    Qnn_DataType_t dtype = QNN_DATATYPE_UNDEFINED;
    bool scaleOffset = false;
    float scale = 0.f;
    int32_t offset = 0;
    Vec<uint32_t> dims;
};

struct GraphDesc {
    Str name;
    Vec<TensorDesc> inputs, outputs;
};

TensorDesc describe(const Qnn_Tensor_t& t) {
    TensorDesc d;
    const char* nm = nullptr;
    const Qnn_QuantizeParams_t* q = nullptr;
    uint32_t rank = 0;
    const uint32_t* dims = nullptr;
    if (t.version == QNN_TENSOR_VERSION_2) {
        d.id = t.v2.id; nm = t.v2.name; d.type = t.v2.type; d.format = t.v2.dataFormat; d.dtype = t.v2.dataType;
        q = &t.v2.quantizeParams; rank = t.v2.rank; dims = t.v2.dimensions;
    } else {
        d.id = t.v1.id; nm = t.v1.name; d.type = t.v1.type; d.format = t.v1.dataFormat; d.dtype = t.v1.dataType;
        q = &t.v1.quantizeParams; rank = t.v1.rank; dims = t.v1.dimensions;
    }
    d.name = nm ? nm : "";
    for (uint32_t i = 0; dims && i < rank; i++) d.dims.push_back(dims[i]);
    if (q && q->encodingDefinition == QNN_DEFINITION_DEFINED &&
        q->quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        d.scaleOffset = true;
        d.scale = q->scaleOffsetEncoding.scale;
        d.offset = q->scaleOffsetEncoding.offset;
    }
    return d;
}

template <typename G>
GraphDesc graphFrom(const G& g) {
    GraphDesc out;
    out.name = g.graphName ? g.graphName : "";
    for (uint32_t i = 0; i < g.numGraphInputs; i++) out.inputs.push_back(describe(g.graphInputs[i]));
    for (uint32_t i = 0; i < g.numGraphOutputs; i++) out.outputs.push_back(describe(g.graphOutputs[i]));
    return out;
}

bool readGraphs(Runtime* r, const void* buf, uint64_t size, Vec<GraphDesc>& out) {
    QnnSystemContext_Handle_t sc = nullptr;
    if (r->sys->systemContextCreate(&sc) != QNN_SUCCESS || !sc) { setError("创建 QNN 系统上下文失败"); return false; }
    const QnnSystemContext_BinaryInfo_t* info = nullptr;
    Qnn_ContextBinarySize_t infoSize = 0;
    Qnn_ErrorHandle_t e = r->sys->systemContextGetBinaryInfo(sc, const_cast<void*>(buf), size, &info, &infoSize);
    bool ok = e == QNN_SUCCESS && info;
    if (!ok) setError(errStr("读取模型信息", e));
    else {
        uint32_t n = 0;
        const QnnSystemContext_GraphInfo_t* gs = nullptr;
        switch (info->version) {
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: n = info->contextBinaryInfoV1.numGraphs; gs = info->contextBinaryInfoV1.graphs; break;
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: n = info->contextBinaryInfoV2.numGraphs; gs = info->contextBinaryInfoV2.graphs; break;
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: n = info->contextBinaryInfoV3.numGraphs; gs = info->contextBinaryInfoV3.graphs; break;
            default: break;
        }
        for (uint32_t i = 0; gs && i < n; i++) {
            const auto& g = gs[i];
            switch (g.version) {
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1: out.push_back(graphFrom(g.graphInfoV1)); break;
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2: out.push_back(graphFrom(g.graphInfoV2)); break;
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3: out.push_back(graphFrom(g.graphInfoV3)); break;
                default: break;
            }
        }
        if (out.empty()) { setError("模型文件里没有图"); ok = false; }
    }
    r->sys->systemContextFree(sc);
    return ok;
}

// 只读映射整个文件
struct Mapped {
    void* p = MAP_FAILED;
    size_t n = 0;
    ~Mapped() { if (p != MAP_FAILED) munmap(p, n); }
    bool open(const Str& path) {
        int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) { setError("打不开 " + path); return false; }
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0) { ::close(fd); setError("文件为空：" + path); return false; }
        n = (size_t) st.st_size;
        p = mmap(nullptr, n, PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (p == MAP_FAILED) { setError("映射文件失败：" + path); return false; }
        return true;
    }
};

// ---------------------------------------------------------------- graphs

struct Graph {
    Qnn_ContextHandle_t ctx = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    GraphDesc desc;
};

const char* dtypeName(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_32: return "float32";
        case QNN_DATATYPE_FLOAT_16: return "float16";
        case QNN_DATATYPE_UFIXED_POINT_16: return "ufixed16";
        case QNN_DATATYPE_UFIXED_POINT_8: return "ufixed8";
        case QNN_DATATYPE_SFIXED_POINT_16: return "sfixed16";
        case QNN_DATATYPE_SFIXED_POINT_8: return "sfixed8";
        case QNN_DATATYPE_UINT_16: return "uint16";
        case QNN_DATATYPE_UINT_8: return "uint8";
        case QNN_DATATYPE_INT_32: return "int32";
        case QNN_DATATYPE_UINT_32: return "uint32";
        case QNN_DATATYPE_INT_16: return "int16";
        case QNN_DATATYPE_INT_8: return "int8";
        case QNN_DATATYPE_INT_64: return "int64";
        case QNN_DATATYPE_BOOL_8: return "bool8";
        default: return "unknown";
    }
}

uint64_t dtypeBytes(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_64: case QNN_DATATYPE_INT_64: case QNN_DATATYPE_UINT_64: return 8;
        case QNN_DATATYPE_FLOAT_32: case QNN_DATATYPE_INT_32: case QNN_DATATYPE_UINT_32:
        case QNN_DATATYPE_SFIXED_POINT_32: case QNN_DATATYPE_UFIXED_POINT_32: return 4;
        case QNN_DATATYPE_FLOAT_16: case QNN_DATATYPE_BFLOAT_16: case QNN_DATATYPE_INT_16: case QNN_DATATYPE_UINT_16:
        case QNN_DATATYPE_SFIXED_POINT_16: case QNN_DATATYPE_UFIXED_POINT_16: return 2;
        default: return 1;
    }
}

uint64_t tensorBytes(const TensorDesc& d) {
    uint64_t n = dtypeBytes(d.dtype);
    for (uint32_t v : d.dims) n *= v;
    return n;
}

Str jsonEscape(const Str& s) {
    Str o;
    for (size_t i = 0; i < s.size(); i++) { char c = s.c_str()[i];
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if ((unsigned char) c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += c;
    }
    return o;
}

void appendTensors(Str& js, const Vec<TensorDesc>& ts) {
    js += '[';
    for (size_t i = 0; i < ts.size(); i++) {
        const auto& t = ts[i];
        if (i) js += ',';
        js += "{\"name\":\"" + jsonEscape(t.name) + "\",\"dims\":[";
        for (size_t k = 0; k < t.dims.size(); k++) { if (k) js += ','; js += toStr(t.dims[k]); }
        js += "],\"dtype\":\"";
        js += dtypeName(t.dtype);
        js += "\",\"bytes\":" + toStr(tensorBytes(t));
        if (t.scaleOffset) {
            char b[64];
            snprintf(b, sizeof b, ",\"scale\":%.9g,\"offset\":%d", (double) t.scale, (int) t.offset);
            js += b;
        }
        js += '}';
    }
    js += ']';
}

Qnn_Tensor_t toQnn(const TensorDesc& d, void* data, uint32_t size) {
    Qnn_Tensor_t t = QNN_TENSOR_INIT;
    t.version = QNN_TENSOR_VERSION_1;
    t.v1.id = d.id;
    t.v1.name = d.name.c_str();
    t.v1.type = d.type;
    t.v1.dataFormat = d.format;
    t.v1.dataType = d.dtype;
    if (d.scaleOffset) {
        t.v1.quantizeParams.encodingDefinition = QNN_DEFINITION_DEFINED;
        t.v1.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
        t.v1.quantizeParams.scaleOffsetEncoding.scale = d.scale;
        t.v1.quantizeParams.scaleOffsetEncoding.offset = d.offset;
    }
    t.v1.rank = (uint32_t) d.dims.size();
    t.v1.dimensions = const_cast<uint32_t*>(d.dims.data());
    t.v1.memType = QNN_TENSORMEMTYPE_RAW;
    t.v1.clientBuf.data = data;
    t.v1.clientBuf.dataSize = size;
    return t;
}

Graph* openGraph(const Str& dir, const Str& bin, const Str& wanted) {
    Lock lk;
    Runtime* r = ensureRuntime(dir);
    if (!r) return nullptr;
    Mapped m;
    if (!m.open(bin)) return nullptr;
    Vec<GraphDesc> graphs;
    if (!readGraphs(r, m.p, m.n, graphs)) return nullptr;
    const GraphDesc* pick = &graphs[0];
    if (!wanted.empty()) {
        pick = nullptr;
        for (const auto& g : graphs) if (g.name == wanted) { pick = &g; break; }
        if (!pick) { setError("模型文件里没有图 " + wanted); return nullptr; }
    }
    auto* g = new Graph();
    g->desc = *pick;
    Qnn_ErrorHandle_t e = r->qnn->contextCreateFromBinary(r->backend, r->device, nullptr, m.p, m.n, &g->ctx, nullptr);
    if (e != QNN_SUCCESS || !g->ctx) { setError(errStr("加载模型到 NPU", e)); delete g; return nullptr; }
    e = r->qnn->graphRetrieve(g->ctx, g->desc.name.c_str(), &g->graph);
    if (e != QNN_SUCCESS || !g->graph) {
        setError(errStr("取出图", e));
        r->qnn->contextFree(g->ctx, nullptr);
        delete g;
        return nullptr;
    }
    r->openGraphs++;
    return g;
}

// ---------------------------------------------------------------- cdsp facts (best effort)

struct DspCapability { uint32_t domain; uint32_t attribute; uint32_t capability; };

bool unsignedPdSupported() {
    void* h = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) return false;
    using Ctl = int (*)(uint32_t, void*, uint32_t);
    auto ctl = reinterpret_cast<Ctl>(dlsym(h, "remote_handle_control"));
    bool ok = false;
    if (ctl) {
        DspCapability c{3 /* CDSP */, 1 /* UNSIGNED_PD_SUPPORT */, 0};
        ok = ctl(2 /* DSPRPC_GET_DSP_INFO */, &c, sizeof c) == 0 && c.capability != 0;
    }
    dlclose(h);
    return ok;
}

bool cdspPresent() {
    return access("/dev/cdsprpc-smd", F_OK) == 0 || access("/dev/fastrpc-cdsp", F_OK) == 0 ||
           access("/vendor/lib64/libcdsprpc.so", F_OK) == 0;
}

int htpArch(Runtime* r) {
    if (!r || !r->qnn || !r->qnn->deviceGetPlatformInfo) return 0;
    const QnnDevice_PlatformInfo_t* info = nullptr;
    if (r->qnn->deviceGetPlatformInfo(nullptr, &info) != QNN_SUCCESS || !info) return 0;
    int arch = 0;
    if (info->version == QNN_DEVICE_PLATFORM_INFO_VERSION_1 && info->v1.numHwDevices > 0 && info->v1.hwDevices) {
        const auto& hw = info->v1.hwDevices[0];
        if (hw.version == QNN_DEVICE_HARDWARE_DEVICE_INFO_VERSION_1 && hw.v1.deviceInfoExtension) {
            const auto* ext = reinterpret_cast<const QnnHtpDevice_DeviceInfoExtension_t*>(hw.v1.deviceInfoExtension);
            if (ext->devType == QNN_HTP_DEVICE_TYPE_ON_CHIP) arch = (int) ext->onChipDevice.arch;
        }
    }
    if (r->qnn->deviceFreePlatformInfo) r->qnn->deviceFreePlatformInfo(nullptr, info);
    return arch;
}

// ---------------------------------------------------------------- perf vote

Str setPerf(bool burst) {
    Lock lk;
    Runtime* r = g_rt;
    if (!r || !r->qnn || !r->qnn->deviceGetInfrastructure) return "运行库未加载";
    QnnDevice_Infrastructure_t infra = nullptr;
    if (r->qnn->deviceGetInfrastructure(&infra) != QNN_SUCCESS || !infra) return "取不到 HTP 性能接口";
    auto* htp = reinterpret_cast<QnnHtpDevice_Infrastructure_t*>(infra);
    if (htp->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) return "HTP 性能接口类型不对";
    auto& perf = htp->perfInfra;
    if (!r->havePowerId) {
        if (perf.createPowerConfigId(0, 0, &r->powerId) != QNN_SUCCESS) return "创建电源配置失败";
        r->havePowerId = true;
    }
    QnnHtpPerfInfrastructure_PowerConfig_t c{};
    c.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    auto& d = c.dcvsV3Config;
    d.contextId = r->powerId;
    d.setDcvsEnable = 1;
    d.dcvsEnable = burst ? 0 : 1;
    d.powerMode = burst ? QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE
                        : QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_MODE;
    d.setSleepLatency = 1;
    d.sleepLatency = burst ? 40 : 1000;
    d.setSleepDisable = 1;
    d.sleepDisable = burst ? 1 : 0;
    auto hi = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    auto lo = DCVS_VOLTAGE_VCORNER_NOM;
    d.setBusParams = 1;
    d.busVoltageCornerMin = burst ? hi : lo;
    d.busVoltageCornerTarget = burst ? hi : lo;
    d.busVoltageCornerMax = hi;
    d.setCoreParams = 1;
    d.coreVoltageCornerMin = burst ? hi : lo;
    d.coreVoltageCornerTarget = burst ? hi : lo;
    d.coreVoltageCornerMax = hi;
    const QnnHtpPerfInfrastructure_PowerConfig_t* list[] = {&c, nullptr};
    Qnn_ErrorHandle_t e = perf.setPowerConfig(r->powerId, list);
    return e == QNN_SUCCESS ? Str() : errStr("HTP 调频", e);
}

Str jstr(JNIEnv* env, jstring s) {
    if (!s) return Str();
    const char* c = env->GetStringUTFChars(s, nullptr);
    Str o = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return o;
}

}  // namespace

// ---------------------------------------------------------------- JNI

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeProbe(JNIEnv* env, jclass, jstring jdir) {
    Str dir = jstr(env, jdir);
    bool backendOk = false, deviceOk = false;
    int arch = 0;
    Str err;
    {
        Lock lk;
        Runtime* r = ensureRuntime(dir);
        if (r) {
            backendOk = r->backend != nullptr;
            deviceOk = r->deviceOk;
            arch = htpArch(r);
            if (r->openGraphs == 0) teardown();  // 只是探测：不常驻
        } else err = g_lastError;
    }
    Str js = "{\"backendOk\":";
    js += backendOk ? "true" : "false";
    js += ",\"deviceOk\":";
    js += deviceOk ? "true" : "false";
    if (!err.empty()) js += ",\"error\":\"" + jsonEscape(err) + "\"";
    js += ",\"cdsp\":{\"present\":";
    js += cdspPresent() ? "true" : "false";
    js += ",\"unsignedPd\":";
    js += unsignedPdSupported() ? "true" : "false";
    js += ",\"arch\":" + toStr(arch) + "}}";
    return env->NewStringUTF(js.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeOpen(JNIEnv* env, jclass, jstring dir, jstring bin) {
    return (jlong) (intptr_t) openGraph(jstr(env, dir), jstr(env, bin), "");
}

JNIEXPORT jlong JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeOpenGraph(JNIEnv* env, jclass, jstring dir, jstring bin, jstring name) {
    return (jlong) (intptr_t) openGraph(jstr(env, dir), jstr(env, bin), jstr(env, name));
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeGraphNames(JNIEnv* env, jclass, jstring jdir, jstring jbin) {
    Lock lk;
    Runtime* r = ensureRuntime(jstr(env, jdir));
    if (!r) return nullptr;
    Mapped m;
    if (!m.open(jstr(env, jbin))) return nullptr;
    Vec<GraphDesc> graphs;
    if (!readGraphs(r, m.p, m.n, graphs)) return nullptr;
    Str out;
    for (size_t i = 0; i < graphs.size(); i++) { if (i) out += '\n'; out += graphs[i].name; }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeLastError(JNIEnv* env, jclass) {
    Lock lk;
    return g_lastError.empty() ? nullptr : env->NewStringUTF(g_lastError.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeInfo(JNIEnv* env, jclass, jlong h) {
    auto* g = reinterpret_cast<Graph*>((intptr_t) h);
    if (!g) return nullptr;
    Str js = "{\"graph\":\"" + jsonEscape(g->desc.name) + "\",\"inputs\":";
    appendTensors(js, g->desc.inputs);
    js += ",\"outputs\":";
    appendTensors(js, g->desc.outputs);
    js += '}';
    return env->NewStringUTF(js.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeExecute(JNIEnv* env, jclass, jlong h, jobjectArray ins, jobjectArray outs) {
    auto* g = reinterpret_cast<Graph*>((intptr_t) h);
    if (!g) return env->NewStringUTF("图已关闭");
    auto collect = [&](jobjectArray arr, const Vec<TensorDesc>& ds, Vec<Qnn_Tensor_t>& ts) -> Str {
        jsize n = arr ? env->GetArrayLength(arr) : 0;
        if ((size_t) n != ds.size()) return "张量个数不符";
        for (jsize i = 0; i < n; i++) {
            jobject b = env->GetObjectArrayElement(arr, i);
            void* p = b ? env->GetDirectBufferAddress(b) : nullptr;
            jlong cap = b ? env->GetDirectBufferCapacity(b) : -1;
            if (b) env->DeleteLocalRef(b);
            uint64_t need = tensorBytes(ds[i]);
            if (!p || cap < 0 || (uint64_t) cap < need) return ds[i].name + "：缓冲区太小或不是 direct";
            ts.push_back(toQnn(ds[i], p, (uint32_t) need));
        }
        return Str();
    };
    Vec<Qnn_Tensor_t> ti, to;
    Str e = collect(ins, g->desc.inputs, ti);
    if (e.empty()) e = collect(outs, g->desc.outputs, to);
    if (!e.empty()) return env->NewStringUTF(e.c_str());
    const QNN_INTERFACE_VER_TYPE* qnn;
    {
        Lock lk;
        if (!g_rt) return env->NewStringUTF("运行库未加载");
        qnn = g_rt->qnn;
    }
    Qnn_ErrorHandle_t r = qnn->graphExecute(g->graph, ti.data(), (uint32_t) ti.size(), to.data(), (uint32_t) to.size(), nullptr, nullptr);
    if (r != QNN_SUCCESS) return env->NewStringUTF(errStr("NPU 推理", r).c_str());
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeClose(JNIEnv*, jclass, jlong h) {
    auto* g = reinterpret_cast<Graph*>((intptr_t) h);
    if (!g) return;
    Lock lk;
    if (g_rt) {
        if (g->ctx) g_rt->qnn->contextFree(g->ctx, nullptr);
        if (g_rt->openGraphs > 0) g_rt->openGraphs--;
    }
    delete g;
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_imagegen_QnnNative_nativeSetPerf(JNIEnv* env, jclass, jboolean burst) {
    Str e = setPerf(burst == JNI_TRUE);
    return e.empty() ? nullptr : env->NewStringUTF(e.c_str());
}

}  // extern "C"
