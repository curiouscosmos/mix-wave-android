#include <jni.h>

// Reuse the audio callback bridge to identify decoded tones without device recording permissions.
extern "C" jlong Java_org_videolan_vlc_audio_NoiseOutput_attach(JNIEnv *, jobject, jlong);
extern "C" void Java_org_videolan_vlc_audio_NoiseOutput_detach(JNIEnv *, jobject, jlong);
extern "C" jlong Java_org_videolan_vlc_audio_NoiseOutput_clock(JNIEnv *, jobject);

extern "C" JNIEXPORT jlong JNICALL
Java_org_videolan_vlc_gui_audio_CaptionSeekTest_attach(JNIEnv *env, jobject sink, jlong player) {
    return Java_org_videolan_vlc_audio_NoiseOutput_attach(env, sink, player);
}
extern "C" JNIEXPORT void JNICALL
Java_org_videolan_vlc_gui_audio_CaptionSeekTest_detach(JNIEnv *env, jobject sink, jlong handle) {
    Java_org_videolan_vlc_audio_NoiseOutput_detach(env, sink, handle);
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_videolan_vlc_gui_audio_CaptionSeekTest_clock(JNIEnv *env, jobject sink) {
    return Java_org_videolan_vlc_audio_NoiseOutput_clock(env, sink);
}
