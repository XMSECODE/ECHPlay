#include <jni.h>
#include <string>
#include <cstdio>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_abcplaydemo_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {

    char info[512];

    snprintf(
            info,
            sizeof(info),
            "Hello from C++\n"
            "FFmpeg version: %s\n"
            "avcodec version: %u\n"
            "avformat version: %u\n"
            "avutil version: %u",
            av_version_info(),
            avcodec_version(),
            avformat_version(),
            avutil_version()
    );

    return env->NewStringUTF(info);
}
