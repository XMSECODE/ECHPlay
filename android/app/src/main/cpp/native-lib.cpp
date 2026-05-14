#include <jni.h>
#include <string>

#include <android/native_window_jni.h>

#include "NativePlayer.h"

static NativePlayer *getPlayer(jlong nativeHandle) {
    return reinterpret_cast<NativePlayer *>(nativeHandle);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeInit(
        JNIEnv *env,
        jobject thiz) {

    NativePlayer *player = new NativePlayer();
    return reinterpret_cast<jlong>(player);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeRelease(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        delete player;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeSetDataSource(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jstring dataSource) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return;
    }

    if (dataSource == nullptr) {
        player->setDataSource("");
        return;
    }

    const char *sourceChars = env->GetStringUTFChars(dataSource, nullptr);
    if (sourceChars == nullptr) {
        return;
    }

    player->setDataSource(sourceChars);

    env->ReleaseStringUTFChars(dataSource, sourceChars);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeSetSurface(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jobject surface) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return;
    }

    if (surface == nullptr) {
        player->setSurface(nullptr);
        return;
    }

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    player->setSurface(window);

    if (window != nullptr) {
        ANativeWindow_release(window);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativePrepare(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("prepare failed: NativePlayer is null");
    }

    std::string result = player->prepare();
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativePlay(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("play failed: NativePlayer is null");
    }

    std::string result = player->play();
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativePause(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->pause();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeResume(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->resume();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeStop(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->stop();
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeGetFFmpegVersion(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("NativePlayer is null");
    }

    std::string version = player->getFFmpegVersion();
    return env->NewStringUTF(version.c_str());
}
