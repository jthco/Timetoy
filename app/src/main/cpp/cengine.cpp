#include <jni.h>
#include <android/log.h>
#include <sys/sysinfo.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
static const char* BUILD_MARKER = "TIMETOY_CENGINE_TRACE_20260818";
extern "C"
__attribute__((used))
const char TIMETOY_BUILD_MARKER[] = "TIMETOY_CENGINE_TRACE_20260818";

#include <memory>
#include <mutex>
#include <vector>

#define LOG_TAG "TimeToyCEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// Native diagnostic trace
// ============================================================

static void ctrace(const char* text) {
    FILE* f = std::fopen(
            "/storage/emulated/0/Documents/cengine_trace.txt",
            "a"
    );

    if (!f) {
        return;
    }

    std::fprintf(f, "%s\n", text);
    std::fflush(f);
    std::fclose(f);
}

namespace {

struct Chunk {
    uint8_t* data;
    size_t bytes;

    explicit Chunk(size_t n)
        : data((uint8_t*)std::malloc(n)),
          bytes(n) {
    }

    ~Chunk() {
        std::free(data);
    }

    Chunk(const Chunk&) = delete;
    Chunk& operator=(const Chunk&) = delete;
};


struct Engine {
    static const int FRAMES_PER_CHUNK = 16;

    int width;
    int height;
    int fps;

    size_t yBytes;
    size_t uvBytes;
    size_t frameBytes;

    std::vector<std::unique_ptr<Chunk>> chunks;
    std::vector<uint8_t*> frames;
    std::vector<std::unique_ptr<std::mutex>> slotLocks;
    std::vector<int64_t> timestamps;

    std::mutex meta;

    int writeIndex = 0;
    int count = 0;
    uint64_t totalFrames = 0;
    int allocatedMiB = 0;


    Engine(int w, int h, int f)
        : width(w),
          height(h),
          fps(std::max(1, f)),
          yBytes((size_t)w * h),
          uvBytes((size_t)(w / 2) * (h / 2)),
          frameBytes(yBytes + 2 * uvBytes) {

        ctrace("Engine constructor entered");

        struct sysinfo info{};

        ctrace("before sysinfo");

        sysinfo(&info);

        ctrace("after sysinfo");

        uint64_t totalRam =
                (uint64_t)info.totalram *
                (uint64_t)info.mem_unit;

        (void)totalRam;

        // Diagnostic build:
        // keep allocation conservative until native path is proven.
        int targetMiB = 256;

        size_t target =
                (size_t)targetMiB *
                1024ULL *
                1024ULL;

        size_t chunkBytes =
                frameBytes *
                FRAMES_PER_CHUNK;

        ctrace("before chunk allocation loop");

        while (frames.size() * frameBytes < target) {

            if (frames.empty()) {
                ctrace("before first chunk malloc");
            }

            std::unique_ptr<Chunk> c(
                    new Chunk(chunkBytes)
            );

            if (frames.empty()) {
                ctrace("after first chunk malloc");
            }

            if (!c->data) {
                ctrace("chunk malloc returned null");
                break;
            }

            uint8_t* base = c->data;

            chunks.push_back(
                    std::move(c)
            );

            for (int i = 0;
                 i < FRAMES_PER_CHUNK;
                 i++) {

                frames.push_back(
                        base +
                        (size_t)i *
                        frameBytes
                );

                slotLocks.emplace_back(
                        new std::mutex()
                );

                timestamps.push_back(0);
            }
        }

        ctrace("after chunk allocation loop");

        allocatedMiB =
                (int)(
                        frames.size() *
                        frameBytes /
                        (1024ULL * 1024ULL)
                );

        LOGI(
                "created %dx%d@%d "
                "frameBytes=%zu "
                "capacity=%zu "
                "allocatedMiB=%d",
                width,
                height,
                fps,
                frameBytes,
                frames.size(),
                allocatedMiB
        );

        ctrace("Engine constructor finished");
    }


    int capacity() const {
        return (int)frames.size();
    }


    static inline void copyPlane(
            uint8_t* dst,
            const uint8_t* src,
            int offset,
            int rowStride,
            int pixelStride,
            int cols,
            int rows) {

        src += offset;

        if (pixelStride == 1 &&
            rowStride == cols) {

            std::memcpy(
                    dst,
                    src,
                    (size_t)cols * rows
            );

            return;
        }

        for (int y = 0;
             y < rows;
             y++) {

            const uint8_t* row =
                    src +
                    (size_t)y *
                    rowStride;

            uint8_t* out =
                    dst +
                    (size_t)y *
                    cols;

            if (pixelStride == 1) {

                std::memcpy(
                        out,
                        row,
                        cols
                );

            } else {

                for (int x = 0;
                     x < cols;
                     x++) {

                    out[x] =
                            row[
                                    (size_t)x *
                                    pixelStride
                            ];
                }
            }
        }
    }


    bool push(
            const uint8_t* y,
            int yo,
            int yrs,
            int yps,

            const uint8_t* u,
            int uo,
            int urs,
            int ups,

            const uint8_t* v,
            int vo,
            int vrs,
            int vps,

            int64_t ts) {

        if (frames.empty() ||
            !y ||
            !u ||
            !v) {

            return false;
        }

        int slot;

        {
            std::lock_guard<std::mutex> g(meta);
            slot = writeIndex;
        }

        {
            std::lock_guard<std::mutex>
                    sg(*slotLocks[slot]);

            uint8_t* dst =
                    frames[slot];

            copyPlane(
                    dst,
                    y,
                    yo,
                    yrs,
                    yps,
                    width,
                    height
            );

            copyPlane(
                    dst + yBytes,
                    u,
                    uo,
                    urs,
                    ups,
                    width / 2,
                    height / 2
            );

            copyPlane(
                    dst +
                    yBytes +
                    uvBytes,
                    v,
                    vo,
                    vrs,
                    vps,
                    width / 2,
                    height / 2
            );

            timestamps[slot] = ts;
        }

        {
            std::lock_guard<std::mutex> g(meta);

            writeIndex =
                    (writeIndex + 1) %
                    capacity();

            if (count < capacity()) {
                ++count;
            }

            ++totalFrames;
        }

        return true;
    }


    bool copyOffset(
            int64_t offsetMs,
            uint8_t* y,
            uint8_t* u,
            uint8_t* v) {

        if (!y ||
            !u ||
            !v ||
            frames.empty()) {

            return false;
        }

        int slot;

        {
            std::lock_guard<std::mutex> g(meta);

            if (count <= 0) {
                return false;
            }

            int64_t back =
                    (int64_t)std::llround(
                            std::abs(
                                    (double)offsetMs
                            ) *
                            fps /
                            1000.0
                    );

            if (back >= count) {
                back = count - 1;
            }

            int newest =
                    (
                            writeIndex -
                            1 +
                            capacity()
                    ) %
                    capacity();

            slot =
                    (int)(
                            (
                                    newest -
                                    back +
                                    capacity()
                            ) %
                            capacity()
                    );
        }

        std::lock_guard<std::mutex>
                sg(*slotLocks[slot]);

        const uint8_t* src =
                frames[slot];

        std::memcpy(
                y,
                src,
                yBytes
        );

        std::memcpy(
                u,
                src + yBytes,
                uvBytes
        );

        std::memcpy(
                v,
                src +
                yBytes +
                uvBytes,
                uvBytes
        );

        return true;
    }


    double historySeconds() {

        std::lock_guard<std::mutex> g(meta);

        if (count <= 1) {
            return 0.0;
        }

        int newest =
                (
                        writeIndex -
                        1 +
                        capacity()
                ) %
                capacity();

        int oldest =
                count < capacity()
                ? 0
                : writeIndex;

        int64_t a =
                timestamps[oldest];

        int64_t b =
                timestamps[newest];

        if (a > 0 &&
            b >= a) {

            return
                    (double)(b - a) /
                    1.0e9;
        }

        return
                (double)(count - 1) /
                fps;
    }


    void clear() {

        std::lock_guard<std::mutex> g(meta);

        writeIndex = 0;
        count = 0;
        totalFrames = 0;

        std::fill(
                timestamps.begin(),
                timestamps.end(),
                0
        );
    }
};


static Engine* engine(jlong h) {

    return reinterpret_cast<Engine*>(
            (intptr_t)h
    );
}

} // namespace


// ============================================================
// JNI
// ============================================================

extern "C"
JNIEXPORT jlong JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeCreate(
        JNIEnv*,
        jclass,
        jint w,
        jint h,
        jint fps) {

    ctrace("nativeCreate entered");

    try {

        ctrace("before new Engine");

        Engine* e =
                new Engine(
                        w,
                        h,
                        fps
                );

        ctrace("after new Engine");

        if (e->capacity() <= 0) {

            ctrace(
                    "nativeCreate zero capacity"
            );

            delete e;

            return 0;
        }

        ctrace(
                "nativeCreate returning handle"
        );

        return
                (jlong)(
                        intptr_t
                )e;

    } catch (...) {

        ctrace(
                "nativeCreate exception"
        );

        LOGE(
                "nativeCreate exception"
        );

        return 0;
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeDestroy(
        JNIEnv*,
        jclass,
        jlong h) {

    ctrace("nativeDestroy entered");

    delete engine(h);

    ctrace("nativeDestroy finished");
}


extern "C"
JNIEXPORT jboolean JNICALL
Java_com_jth_smm_RamFrameBuffer_nativePush(
        JNIEnv* env,
        jclass,
        jlong h,

        jobject yb,
        jint yo,
        jint yrs,
        jint yps,

        jobject ub,
        jint uo,
        jint urs,
        jint ups,

        jobject vb,
        jint vo,
        jint vrs,
        jint vps,

        jlong ts) {

    static bool firstPush = true;

    if (firstPush) {
        ctrace("first nativePush entered");
    }

    Engine* e = engine(h);

    if (!e) {
        if (firstPush) {
            ctrace("first nativePush null engine");
            firstPush = false;
        }

        return JNI_FALSE;
    }

    auto y =
            (const uint8_t*)
            env->GetDirectBufferAddress(yb);

    auto u =
            (const uint8_t*)
            env->GetDirectBufferAddress(ub);

    auto v =
            (const uint8_t*)
            env->GetDirectBufferAddress(vb);

    if (firstPush) {
        ctrace("first nativePush got buffer addresses");
    }

    bool ok =
            e->push(
                    y,
                    yo,
                    yrs,
                    yps,

                    u,
                    uo,
                    urs,
                    ups,

                    v,
                    vo,
                    vrs,
                    vps,

                    (int64_t)ts
            );

    if (firstPush) {
        ctrace("first nativePush returning");
        firstPush = false;
    }

    return
            ok
            ? JNI_TRUE
            : JNI_FALSE;
}


extern "C"
JNIEXPORT jboolean JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeCopyOffsetMs(
        JNIEnv* env,
        jclass,
        jlong h,
        jlong off,

        jobject yb,
        jobject ub,
        jobject vb) {

    Engine* e = engine(h);

    if (!e) {
        return JNI_FALSE;
    }

    auto y =
            (uint8_t*)
            env->GetDirectBufferAddress(yb);

    auto u =
            (uint8_t*)
            env->GetDirectBufferAddress(ub);

    auto v =
            (uint8_t*)
            env->GetDirectBufferAddress(vb);

    return
            e->copyOffset(
                    off,
                    y,
                    u,
                    v
            )
            ? JNI_TRUE
            : JNI_FALSE;
}


extern "C"
JNIEXPORT jint JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeGetCount(
        JNIEnv*,
        jclass,
        jlong h) {

    Engine* e = engine(h);

    if (!e) {
        return 0;
    }

    std::lock_guard<std::mutex>
            g(e->meta);

    return e->count;
}


extern "C"
JNIEXPORT jint JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeGetCapacityFrames(
        JNIEnv*,
        jclass,
        jlong h) {

    Engine* e = engine(h);

    return
            e
            ? e->capacity()
            : 0;
}


extern "C"
JNIEXPORT jint JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeGetAllocatedMiB(
        JNIEnv*,
        jclass,
        jlong h) {

    Engine* e = engine(h);

    return
            e
            ? e->allocatedMiB
            : 0;
}


extern "C"
JNIEXPORT jlong JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeGetTotalFrames(
        JNIEnv*,
        jclass,
        jlong h) {

    Engine* e = engine(h);

    if (!e) {
        return 0;
    }

    std::lock_guard<std::mutex>
            g(e->meta);

    return
            (jlong)e->totalFrames;
}


extern "C"
JNIEXPORT jdouble JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeGetHistorySeconds(
        JNIEnv*,
        jclass,
        jlong h) {

    Engine* e = engine(h);

    return
            e
            ? e->historySeconds()
            : 0.0;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_jth_smm_RamFrameBuffer_nativeClear(
        JNIEnv*,
        jclass,
        jlong h) {

    Engine* e = engine(h);

    if (e) {
        e->clear();
    }
}

