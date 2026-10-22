#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <new>

// Only the public LibVLC 3 audio API is used. No VLC JNI object layout is assumed.
struct Player;
using Play = void (*)(void *, const void *, unsigned, int64_t);
using Timed = void (*)(void *, int64_t);
using Plain = void (*)(void *);
struct Api {
    void (*retain)(Player *);
    void (*release)(Player *);
    void (*callbacks)(Player *, Play, Timed, Timed, Timed, Plain, void *);
    void (*format)(Player *, const char *, unsigned, unsigned);
    void (*volume)(Player *, void (*)(void *, float, bool));
    int64_t (*clock)();
};
static Api api{};
static JavaVM *vm;

struct Env {
    JNIEnv *env = nullptr;
    bool attached = false;
    Env() {
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};
struct Output {
    Player *player;
    jobject sink;
    jmethodID audio, control, gain;
};
static void clearException(JNIEnv *env, Output *output) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->CallVoidMethod(output->sink, output->control, jint(4));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}
static void play(void *opaque, const void *samples, unsigned count, int64_t pts) {
    auto *output = static_cast<Output *>(opaque);
    Env e;
    if (!e.env) return;
    jobject buffer = e.env->NewDirectByteBuffer(const_cast<void *>(samples), int64_t(count) * 2);
    if (buffer) {
        e.env->CallVoidMethod(output->sink, output->audio, buffer, jlong(pts));
        e.env->DeleteLocalRef(buffer);
    }
    clearException(e.env, output);
}
static void control(void *opaque, jint action) {
    auto *output = static_cast<Output *>(opaque);
    Env e;
    if (!e.env) return;
    e.env->CallVoidMethod(output->sink, output->control, action);
    clearException(e.env, output);
}
static void pause(void *o, int64_t) { control(o, 0); }
static void resume(void *o, int64_t) { control(o, 1); }
static void flush(void *o, int64_t) { control(o, 2); }
static void drain(void *o) { control(o, 3); }
static void volume(void *opaque, float gain, bool muted) {
    auto *output = static_cast<Output *>(opaque);
    Env e;
    if (!e.env) return;
    e.env->CallVoidMethod(output->sink, output->gain, gain, jboolean(muted));
    clearException(e.env, output);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *javaVm, void *) {
    vm = javaVm;
    return JNI_VERSION_1_6;
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_videolan_vlc_audio_NoiseOutput_attach(JNIEnv *env, jobject sink, jlong pointer) {
    void *library = dlopen("libvlc.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return 0;
    if (!pointer) { dlclose(library); return 0; }
    // The library remains loaded while retained players/callbacks exist.
#define SYMBOL(field, name) api.field = reinterpret_cast<decltype(api.field)>(dlsym(library, name))
    SYMBOL(retain, "libvlc_media_player_retain");
    SYMBOL(release, "libvlc_media_player_release");
    SYMBOL(callbacks, "libvlc_audio_set_callbacks");
    SYMBOL(format, "libvlc_audio_set_format");
    SYMBOL(volume, "libvlc_audio_set_volume_callback");
    SYMBOL(clock, "libvlc_clock");
#undef SYMBOL
    dlclose(library); // LibVLC itself owns the loaded library for the lifetime of the player.
    if (!api.retain || !api.release || !api.callbacks || !api.format || !api.volume || !api.clock) return 0;
    auto *output = new (std::nothrow) Output{};
    if (!output) return 0;
    output->player = reinterpret_cast<Player *>(pointer);
    jclass type = env->GetObjectClass(sink);
    output->audio = env->GetMethodID(type, "onAudio", "(Ljava/nio/ByteBuffer;J)V");
    output->control = env->GetMethodID(type, "onControl", "(I)V");
    output->gain = env->GetMethodID(type, "onGain", "(FZ)V");
    env->DeleteLocalRef(type);
    output->sink = env->NewGlobalRef(sink);
    if (!output->sink || env->ExceptionCheck()) {
        if (output->sink) env->DeleteGlobalRef(output->sink);
        delete output;
        return 0;
    }
    api.retain(output->player);
    api.format(output->player, "S16N", 48000, 1);
    api.callbacks(output->player, play, pause, resume, flush, drain, output);
    api.volume(output->player, volume);
    return reinterpret_cast<jlong>(output);
}
extern "C" JNIEXPORT void JNICALL
Java_org_videolan_vlc_audio_NoiseOutput_detach(JNIEnv *env, jobject, jlong handle) {
    auto *output = reinterpret_cast<Output *>(handle);
    if (!output) return;
    // Caller has stopped the player and joined its audio callbacks before detaching.
    api.volume(output->player, nullptr);
    api.callbacks(output->player, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
    api.release(output->player);
    env->DeleteGlobalRef(output->sink);
    delete output;
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_videolan_vlc_audio_NoiseOutput_clock(JNIEnv *, jobject) { return api.clock(); }
