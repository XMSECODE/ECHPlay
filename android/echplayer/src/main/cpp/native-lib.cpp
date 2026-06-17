#include <jni.h>
#include <string>

#include <android/native_window_jni.h>

#include "NativePlayer.h"

/** 根据 nativeHandle 还原 NativePlayer 指针。 */
static NativePlayer *getPlayer(jlong nativeHandle) {
    return reinterpret_cast<NativePlayer *>(nativeHandle);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeInit(
        JNIEnv *env,
        jobject thiz) {

    JavaVM *javaVm = nullptr;
    env->GetJavaVM(&javaVm);

    NativePlayer *player = new NativePlayer(javaVm, env, thiz);
    return reinterpret_cast<jlong>(player);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeRelease(
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
Java_com_echplay_player_ECHPlayer_nativeSetDataSource(
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
Java_com_echplay_player_ECHPlayer_nativeSetSurface(
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
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetSurfaceScaleType(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint scaleType) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->setSurfaceScaleType(static_cast<int>(scaleType));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetRenderMode(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint renderMode) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->setRenderMode(static_cast<int>(renderMode));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetDecodeMode(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint decodeMode) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->setDecodeMode(static_cast<int>(decodeMode));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetRtspTransport(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint transport) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->setRtspTransport(static_cast<int>(transport));
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetLongOption(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint category,
        jstring name,
        jlong value) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr || name == nullptr) {
        return JNI_FALSE;
    }

    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    if (nameChars == nullptr) {
        return JNI_FALSE;
    }

    bool handled = player->setLongOption(
            static_cast<int>(category),
            nameChars,
            static_cast<int64_t>(value)
    );

    env->ReleaseStringUTFChars(name, nameChars);
    return handled ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetStringOption(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint category,
        jstring name,
        jstring value) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr || name == nullptr) {
        return JNI_FALSE;
    }

    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    if (nameChars == nullptr) {
        return JNI_FALSE;
    }

    std::string valueText;
    if (value != nullptr) {
        const char *valueChars = env->GetStringUTFChars(value, nullptr);
        if (valueChars != nullptr) {
            valueText = valueChars;
            env->ReleaseStringUTFChars(value, valueChars);
        }
    }

    bool handled = player->setStringOption(
            static_cast<int>(category),
            nameChars,
            valueText
    );

    env->ReleaseStringUTFChars(name, nameChars);
    return handled ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativePrepare(
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
Java_com_echplay_player_ECHPlayer_nativePlay(
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
Java_com_echplay_player_ECHPlayer_nativePause(
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
Java_com_echplay_player_ECHPlayer_nativeResume(
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
Java_com_echplay_player_ECHPlayer_nativeStop(
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
Java_com_echplay_player_ECHPlayer_nativeSeekToMs(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jlong positionMs) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("seek failed: NativePlayer is null");
    }

    std::string result = player->seekToMs(static_cast<int64_t>(positionMs));
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetDurationMs(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return static_cast<jlong>(-1);
    }

    return static_cast<jlong>(player->getDurationMs());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentPositionMs(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return static_cast<jlong>(-1);
    }

    return static_cast<jlong>(player->getCurrentPositionMs());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetReadBytes(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jlong>(player->getReadBytes());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetReadSpeedBytesPerSecond(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jlong>(player->getReadSpeedBytesPerSecond());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetVideoPacketQueueSize(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jint>(player->getVideoPacketQueueSize());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetAudioPacketQueueSize(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jint>(player->getAudioPacketQueueSize());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetBufferedPercent(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jint>(player->getBufferedPercent());
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetDecodeFps(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0.0;
    }

    return static_cast<jdouble>(player->getDecodeFps());
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetRenderFps(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0.0;
    }

    return static_cast<jdouble>(player->getRenderFps());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetDecodedFrameCount(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jlong>(player->getDecodedFrameCount());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetRenderedFrameCount(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jlong>(player->getRenderedFrameCount());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetDroppedFrameCount(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jlong>(player->getDroppedFrameCount());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetMediaInfoText(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("");
    }

    std::string text = player->getMediaInfoText();
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetTrackInfoText(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("");
    }

    std::string text = player->getTrackInfoText();
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_echplay_player_ECHPlayer_nativeIsSeekable(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return JNI_FALSE;
    }

    return player->isSeekable() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetVideoWidth(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jint>(player->getVideoWidth());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetVideoHeight(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return 0;
    }

    return static_cast<jint>(player->getVideoHeight());
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentFrameRgba(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return nullptr;
    }

    std::vector<uint8_t> rgbaData;
    int frameWidth = 0;
    int frameHeight = 0;
    if (!player->copyCurrentFrameSnapshot(rgbaData, frameWidth, frameHeight) || rgbaData.empty()) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(rgbaData.size()));
    if (result == nullptr) {
        return nullptr;
    }

    env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(rgbaData.size()),
            reinterpret_cast<const jbyte *>(rgbaData.data())
    );
    return result;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentFrameSize(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return nullptr;
    }

    std::vector<uint8_t> rgbaData;
    int frameWidth = 0;
    int frameHeight = 0;
    if (!player->copyCurrentFrameSnapshot(rgbaData, frameWidth, frameHeight)) {
        return nullptr;
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        return nullptr;
    }

    jint sizeArray[2] = {
            static_cast<jint>(frameWidth),
            static_cast<jint>(frameHeight)
    };
    env->SetIntArrayRegion(result, 0, 2, sizeArray);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeStartRecording(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jstring outputPath) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("start recording failed: NativePlayer is null");
    }

    if (outputPath == nullptr) {
        return env->NewStringUTF("start recording failed: output path is null");
    }

    const char *pathChars = env->GetStringUTFChars(outputPath, nullptr);
    if (pathChars == nullptr) {
        return env->NewStringUTF("start recording failed: cannot read output path");
    }

    std::string result = player->startRecording(pathChars);
    env->ReleaseStringUTFChars(outputPath, pathChars);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeStopRecording(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("stop recording failed: NativePlayer is null");
    }

    std::string result = player->stopRecording();
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_echplay_player_ECHPlayer_nativeIsRecording(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return JNI_FALSE;
    }

    return player->isRecording() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentDecodeType(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("software");
    }

    std::string decodeType = player->getCurrentDecodeType();
    return env->NewStringUTF(decodeType.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentDecoderName(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("ffmpeg");
    }

    std::string decoderName = player->getCurrentDecoderName();
    return env->NewStringUTF(decoderName.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetLastDecodeFallbackReason(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("");
    }

    std::string fallbackReason = player->getLastDecodeFallbackReason();
    return env->NewStringUTF(fallbackReason.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetFFmpegVersion(
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
