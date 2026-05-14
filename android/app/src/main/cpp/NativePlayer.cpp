#include "NativePlayer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libavutil/rational.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
}

#define ECH_LOG_TAG "ECHPlayer"
#define ECH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ECH_LOG_TAG, __VA_ARGS__)
#define ECH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ECH_LOG_TAG, __VA_ARGS__)

NativePlayer::NativePlayer(JavaVM *vm, JNIEnv *env, jobject javaPlayer)
        : formatContext(nullptr),
          nativeWindow(nullptr),
          videoStreamIndex(-1),
          audioStreamIndex(-1),
          prepared(false),
          released(false),
          playing(false),
          stopRequested(false),
          paused(false),
          javaVm(vm),
          javaPlayerObject(nullptr),
          onNativeAudioInfoMethod(nullptr),
          onNativeAudioDataMethod(nullptr) {

    if (env != nullptr && javaPlayer != nullptr) {
        javaPlayerObject = env->NewGlobalRef(javaPlayer);

        jclass clazz = env->GetObjectClass(javaPlayer);
        if (clazz != nullptr) {
            onNativeAudioInfoMethod = env->GetMethodID(
                    clazz,
                    "onNativeAudioInfo",
                    "(II)V"
            );

            onNativeAudioDataMethod = env->GetMethodID(
                    clazz,
                    "onNativeAudioData",
                    "([BI)V"
            );

            env->DeleteLocalRef(clazz);
        }
    }

    ECH_LOGI("NativePlayer create");
}

NativePlayer::~NativePlayer() {
    released = true;

    stop();
    releaseSurface();
    releaseFormatContext();
    releaseJavaCallback();

    ECH_LOGI("NativePlayer destroy");
}

void NativePlayer::setDataSource(const std::string &source) {
    dataSource = source;
    ECH_LOGI("setDataSource: %s", dataSource.c_str());
}

void NativePlayer::setSurface(ANativeWindow *window) {
    std::lock_guard<std::mutex> lock(windowMutex);

    if (nativeWindow != nullptr) {
        ANativeWindow_release(nativeWindow);
        nativeWindow = nullptr;
    }

    if (window != nullptr) {
        ANativeWindow_acquire(window);
        nativeWindow = window;

        ECH_LOGI(
                "setSurface success, size=%dx%d",
                ANativeWindow_getWidth(nativeWindow),
                ANativeWindow_getHeight(nativeWindow)
        );
    } else {
        ECH_LOGI("setSurface null");
    }
}

std::string NativePlayer::prepare() {
    if (dataSource.empty()) {
        return "prepare failed: dataSource is empty";
    }

    releaseFormatContext();

    ECH_LOGI("prepare start: %s", dataSource.c_str());

    int ret = avformat_open_input(&formatContext, dataSource.c_str(), nullptr, nullptr);
    if (ret < 0) {
        std::string error = makeErrorString(ret);
        ECH_LOGE("avformat_open_input failed: %s", error.c_str());

        return "prepare failed\n"
               "step: avformat_open_input\n"
               "error: " + error + "\n"
               "source: " + dataSource;
    }

    ret = avformat_find_stream_info(formatContext, nullptr);
    if (ret < 0) {
        std::string error = makeErrorString(ret);
        ECH_LOGE("avformat_find_stream_info failed: %s", error.c_str());

        releaseFormatContext();

        return "prepare failed\n"
               "step: avformat_find_stream_info\n"
               "error: " + error + "\n"
               "source: " + dataSource;
    }

    videoStreamIndex = av_find_best_stream(
            formatContext,
            AVMEDIA_TYPE_VIDEO,
            -1,
            -1,
            nullptr,
            0
    );

    audioStreamIndex = av_find_best_stream(
            formatContext,
            AVMEDIA_TYPE_AUDIO,
            -1,
            -1,
            nullptr,
            0
    );

    if (videoStreamIndex < 0) {
        releaseFormatContext();
        return "prepare failed: no video stream found";
    }

    prepared = true;

    std::ostringstream oss;

    oss << "prepare success\n";
    oss << "source: " << dataSource << "\n";

    if (formatContext->iformat && formatContext->iformat->name) {
        oss << "format: " << formatContext->iformat->name << "\n";
    }

    if (formatContext->duration != AV_NOPTS_VALUE) {
        double durationSeconds = formatContext->duration / 1000000.0;
        oss << "duration: " << std::fixed << std::setprecision(2) << durationSeconds << "s\n";
    } else {
        oss << "duration: unknown\n";
    }

    oss << "bitrate: " << formatContext->bit_rate << "\n";

    AVStream *videoStream = formatContext->streams[videoStreamIndex];
    AVCodecParameters *videoCodecPar = videoStream->codecpar;

    oss << "\n";
    oss << "video stream index: " << videoStreamIndex << "\n";
    oss << "video codec: " << avcodec_get_name(videoCodecPar->codec_id) << "\n";
    oss << "video size: " << videoCodecPar->width << "x" << videoCodecPar->height << "\n";

    if (videoStream->avg_frame_rate.num > 0 && videoStream->avg_frame_rate.den > 0) {
        double fps = av_q2d(videoStream->avg_frame_rate);
        oss << "video fps: " << std::fixed << std::setprecision(2) << fps << "\n";
    }

    if (audioStreamIndex >= 0) {
        AVStream *audioStream = formatContext->streams[audioStreamIndex];
        AVCodecParameters *audioCodecPar = audioStream->codecpar;

        oss << "\n";
        oss << "audio stream index: " << audioStreamIndex << "\n";
        oss << "audio codec: " << avcodec_get_name(audioCodecPar->codec_id) << "\n";
        oss << "audio sample rate: " << audioCodecPar->sample_rate << "\n";
        oss << "audio channels: " << audioCodecPar->ch_layout.nb_channels << "\n";
    } else {
        oss << "\naudio stream: not found\n";
    }

    ECH_LOGI("prepare success");

    return oss.str();
}

std::string NativePlayer::play() {
    if (!prepared || formatContext == nullptr) {
        return "play failed: player is not prepared";
    }

    {
        std::lock_guard<std::mutex> lock(windowMutex);
        if (nativeWindow == nullptr) {
            return "play failed: surface is null";
        }
    }

    if (playing) {
        return "play ignored: already playing";
    }

    if (playThread.joinable()) {
        playThread.join();
    }

    stopRequested = false;
    paused = false;
    playing = true;

    playThread = std::thread(&NativePlayer::decodeLoop, this);

    if (audioStreamIndex >= 0) {
        audioThread = std::thread(&NativePlayer::audioDecodeLoop, this);
    }

    ECH_LOGI("play started");

    return "play started";
}

void NativePlayer::pause() {
    if (playing.load() && !stopRequested.load()) {
        paused = true;
        ECH_LOGI("play paused");
    }
}

void NativePlayer::resume() {
    if (playing.load() && !stopRequested.load()) {
        paused = false;
        ECH_LOGI("play resumed");
    }
}

void NativePlayer::stop() {
    stopRequested = true;

    if (playThread.joinable()) {
        playThread.join();
    }

    if (audioThread.joinable()) {
        audioThread.join();
    }

    playing = false;

    ECH_LOGI("play stopped");
}

void NativePlayer::decodeLoop() {
    ECH_LOGI("decodeLoop start");

    AVStream *videoStream = formatContext->streams[videoStreamIndex];
    AVCodecParameters *codecParameters = videoStream->codecpar;

    const AVCodec *decoder = avcodec_find_decoder(codecParameters->codec_id);
    if (decoder == nullptr) {
        ECH_LOGE("decoder not found");
        playing = false;
        return;
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(decoder);
    if (codecContext == nullptr) {
        ECH_LOGE("avcodec_alloc_context3 failed");
        playing = false;
        return;
    }

    int ret = avcodec_parameters_to_context(codecContext, codecParameters);
    if (ret < 0) {
        ECH_LOGE("avcodec_parameters_to_context failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        playing = false;
        return;
    }

    ret = avcodec_open2(codecContext, decoder, nullptr);
    if (ret < 0) {
        ECH_LOGE("avcodec_open2 failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        playing = false;
        return;
    }

    av_seek_frame(formatContext, videoStreamIndex, 0, AVSEEK_FLAG_BACKWARD);
    avcodec_flush_buffers(codecContext);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();

    if (packet == nullptr || frame == nullptr) {
        ECH_LOGE("alloc packet/frame failed");
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codecContext);
        playing = false;
        return;
    }

    double fps = 30.0;
    if (videoStream->avg_frame_rate.num > 0 && videoStream->avg_frame_rate.den > 0) {
        fps = av_q2d(videoStream->avg_frame_rate);
    }

    if (fps <= 0.0 || fps > 120.0) {
        fps = 30.0;
    }

    int frameDelayMs = static_cast<int>(1000.0 / fps);
    if (frameDelayMs <= 0) {
        frameDelayMs = 33;
    }

    int decodedFrameCount = 0;

    while (!stopRequested && av_read_frame(formatContext, packet) >= 0) {
        if (packet->stream_index != videoStreamIndex) {
            av_packet_unref(packet);
            continue;
        }

        ret = avcodec_send_packet(codecContext, packet);
        av_packet_unref(packet);

        if (ret < 0) {
            ECH_LOGE("avcodec_send_packet failed: %s", makeErrorString(ret).c_str());
            continue;
        }

        while (!stopRequested) {
            ret = avcodec_receive_frame(codecContext, frame);

            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }

            if (ret < 0) {
                ECH_LOGE("avcodec_receive_frame failed: %s", makeErrorString(ret).c_str());
                break;
            }

            decodedFrameCount++;

            while (paused.load() && !stopRequested.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }

            if (stopRequested.load()) {
                break;
            }

            renderFrameToSurface(frame);

            av_frame_unref(frame);

            std::this_thread::sleep_for(std::chrono::milliseconds(frameDelayMs));
        }
    }

    ECH_LOGI("decodeLoop finished, decodedFrameCount=%d", decodedFrameCount);

    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&codecContext);

    playing = false;
}

bool NativePlayer::renderFrameToSurface(AVFrame *frame) {
    if (frame == nullptr) {
        return false;
    }

    ANativeWindow *window = nullptr;

    {
        std::lock_guard<std::mutex> lock(windowMutex);

        if (nativeWindow == nullptr) {
            return false;
        }

        window = nativeWindow;
        ANativeWindow_acquire(window);
    }

    int surfaceWidth = ANativeWindow_getWidth(window);
    int surfaceHeight = ANativeWindow_getHeight(window);

    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
        ANativeWindow_release(window);
        return false;
    }

    int videoWidth = frame->width;
    int videoHeight = frame->height;

    if (videoWidth <= 0 || videoHeight <= 0) {
        ANativeWindow_release(window);
        return false;
    }

    // fitCenter：保持视频比例，完整显示在 Surface 中
    float scaleX = static_cast<float>(surfaceWidth) / static_cast<float>(videoWidth);
    float scaleY = static_cast<float>(surfaceHeight) / static_cast<float>(videoHeight);
    float scale = std::min(scaleX, scaleY);

    int renderWidth = static_cast<int>(videoWidth * scale);
    int renderHeight = static_cast<int>(videoHeight * scale);

    if (renderWidth <= 0 || renderHeight <= 0) {
        ANativeWindow_release(window);
        return false;
    }

    int offsetX = (surfaceWidth - renderWidth) / 2;
    int offsetY = (surfaceHeight - renderHeight) / 2;

    int ret = ANativeWindow_setBuffersGeometry(
            window,
            surfaceWidth,
            surfaceHeight,
            WINDOW_FORMAT_RGBA_8888
    );

    if (ret < 0) {
        ANativeWindow_release(window);
        return false;
    }

    SwsContext *swsContext = sws_getContext(
            videoWidth,
            videoHeight,
            static_cast<AVPixelFormat>(frame->format),
            renderWidth,
            renderHeight,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr
    );

    if (swsContext == nullptr) {
        ANativeWindow_release(window);
        return false;
    }

    AVFrame *rgbaFrame = av_frame_alloc();

    int rgbaBufferSize = av_image_get_buffer_size(
            AV_PIX_FMT_RGBA,
            renderWidth,
            renderHeight,
            1
    );

    if (rgbaFrame == nullptr || rgbaBufferSize <= 0) {
        av_frame_free(&rgbaFrame);
        sws_freeContext(swsContext);
        ANativeWindow_release(window);
        return false;
    }

    std::vector<uint8_t> rgbaBuffer(rgbaBufferSize);

    av_image_fill_arrays(
            rgbaFrame->data,
            rgbaFrame->linesize,
            rgbaBuffer.data(),
            AV_PIX_FMT_RGBA,
            renderWidth,
            renderHeight,
            1
    );

    sws_scale(
            swsContext,
            frame->data,
            frame->linesize,
            0,
            videoHeight,
            rgbaFrame->data,
            rgbaFrame->linesize
    );

    ANativeWindow_Buffer windowBuffer;
    ret = ANativeWindow_lock(window, &windowBuffer, nullptr);

    if (ret < 0) {
        av_frame_free(&rgbaFrame);
        sws_freeContext(swsContext);
        ANativeWindow_release(window);
        return false;
    }

    uint8_t *dst = static_cast<uint8_t *>(windowBuffer.bits);
    int dstStride = windowBuffer.stride * 4;

    uint8_t *src = rgbaFrame->data[0];
    int srcStride = rgbaFrame->linesize[0];

    // 先把整个 Surface 清黑，作为黑边背景
    for (int y = 0; y < windowBuffer.height; ++y) {
        memset(dst + y * dstStride, 0, dstStride);
    }

    // 居中绘制等比缩放后的 RGBA 图像
    int copyHeight = std::min(renderHeight, windowBuffer.height - offsetY);
    int copyWidthBytes = std::min(renderWidth, windowBuffer.width - offsetX) * 4;

    for (int y = 0; y < copyHeight; ++y) {
        uint8_t *dstRow = dst + (offsetY + y) * dstStride + offsetX * 4;
        uint8_t *srcRow = src + y * srcStride;
        memcpy(dstRow, srcRow, copyWidthBytes);
    }

    ANativeWindow_unlockAndPost(window);

    av_frame_free(&rgbaFrame);
    sws_freeContext(swsContext);
    ANativeWindow_release(window);

    return true;
}

void NativePlayer::audioDecodeLoop() {
    ECH_LOGI("audioDecodeLoop start");

    AVFormatContext *audioFormatContext = nullptr;

    int ret = avformat_open_input(&audioFormatContext, dataSource.c_str(), nullptr, nullptr);
    if (ret < 0) {
        ECH_LOGE("audio avformat_open_input failed: %s", makeErrorString(ret).c_str());
        return;
    }

    ret = avformat_find_stream_info(audioFormatContext, nullptr);
    if (ret < 0) {
        ECH_LOGE("audio avformat_find_stream_info failed: %s", makeErrorString(ret).c_str());
        avformat_close_input(&audioFormatContext);
        return;
    }

    int audioIndex = av_find_best_stream(
            audioFormatContext,
            AVMEDIA_TYPE_AUDIO,
            -1,
            -1,
            nullptr,
            0
    );

    if (audioIndex < 0) {
        ECH_LOGE("audio stream not found");
        avformat_close_input(&audioFormatContext);
        return;
    }

    AVStream *audioStream = audioFormatContext->streams[audioIndex];
    AVCodecParameters *codecParameters = audioStream->codecpar;

    const AVCodec *decoder = avcodec_find_decoder(codecParameters->codec_id);
    if (decoder == nullptr) {
        ECH_LOGE("audio decoder not found");
        avformat_close_input(&audioFormatContext);
        return;
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(decoder);
    if (codecContext == nullptr) {
        ECH_LOGE("audio avcodec_alloc_context3 failed");
        avformat_close_input(&audioFormatContext);
        return;
    }

    ret = avcodec_parameters_to_context(codecContext, codecParameters);
    if (ret < 0) {
        ECH_LOGE("audio avcodec_parameters_to_context failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        avformat_close_input(&audioFormatContext);
        return;
    }

    ret = avcodec_open2(codecContext, decoder, nullptr);
    if (ret < 0) {
        ECH_LOGE("audio avcodec_open2 failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        avformat_close_input(&audioFormatContext);
        return;
    }

    int outSampleRate = codecContext->sample_rate > 0 ? codecContext->sample_rate : 44100;
    int outChannels = 2;

    AVChannelLayout outChannelLayout;
    av_channel_layout_default(&outChannelLayout, outChannels);

    if (codecContext->ch_layout.nb_channels <= 0) {
        int inputChannels = 2;

        if (codecParameters->ch_layout.nb_channels > 0) {
            inputChannels = codecParameters->ch_layout.nb_channels;
        }

        av_channel_layout_default(&codecContext->ch_layout, inputChannels);
    }

    SwrContext *swrContext = nullptr;

    ret = swr_alloc_set_opts2(
            &swrContext,
            &outChannelLayout,
            AV_SAMPLE_FMT_S16,
            outSampleRate,
            &codecContext->ch_layout,
            codecContext->sample_fmt,
            codecContext->sample_rate,
            0,
            nullptr
    );

    if (ret < 0 || swrContext == nullptr) {
        ECH_LOGE("swr_alloc_set_opts2 failed");
        av_channel_layout_uninit(&outChannelLayout);
        avcodec_free_context(&codecContext);
        avformat_close_input(&audioFormatContext);
        return;
    }

    ret = swr_init(swrContext);
    if (ret < 0) {
        ECH_LOGE("swr_init failed: %s", makeErrorString(ret).c_str());
        swr_free(&swrContext);
        av_channel_layout_uninit(&outChannelLayout);
        avcodec_free_context(&codecContext);
        avformat_close_input(&audioFormatContext);
        return;
    }

    notifyAudioInfo(outSampleRate, outChannels);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();

    if (packet == nullptr || frame == nullptr) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        swr_free(&swrContext);
        av_channel_layout_uninit(&outChannelLayout);
        avcodec_free_context(&codecContext);
        avformat_close_input(&audioFormatContext);
        return;
    }

    int decodedAudioFrameCount = 0;

    while (!stopRequested.load() && av_read_frame(audioFormatContext, packet) >= 0) {
        if (packet->stream_index != audioIndex) {
            av_packet_unref(packet);
            continue;
        }

        ret = avcodec_send_packet(codecContext, packet);
        av_packet_unref(packet);

        if (ret < 0) {
            ECH_LOGE("audio avcodec_send_packet failed: %s", makeErrorString(ret).c_str());
            continue;
        }

        while (!stopRequested.load()) {
            ret = avcodec_receive_frame(codecContext, frame);

            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }

            if (ret < 0) {
                ECH_LOGE("audio avcodec_receive_frame failed: %s", makeErrorString(ret).c_str());
                break;
            }

            while (paused.load() && !stopRequested.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }

            int outSamples = static_cast<int>(
                    av_rescale_rnd(
                            swr_get_delay(swrContext, codecContext->sample_rate) + frame->nb_samples,
                            outSampleRate,
                            codecContext->sample_rate,
                            AV_ROUND_UP
                    )
            );

            int outBufferSize = av_samples_get_buffer_size(
                    nullptr,
                    outChannels,
                    outSamples,
                    AV_SAMPLE_FMT_S16,
                    1
            );

            if (outBufferSize > 0) {
                std::vector<uint8_t> outBuffer(outBufferSize);
                uint8_t *outData[1] = {outBuffer.data()};

                int convertedSamples = swr_convert(
                        swrContext,
                        outData,
                        outSamples,
                        const_cast<const uint8_t **>(frame->data),
                        frame->nb_samples
                );

                if (convertedSamples > 0) {
                    int dataSize = convertedSamples
                                   * outChannels
                                   * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);

                    notifyAudioData(outBuffer.data(), dataSize);
                    decodedAudioFrameCount++;
                }
            }

            av_frame_unref(frame);
        }
    }

    ECH_LOGI("audioDecodeLoop finished, decodedAudioFrameCount=%d", decodedAudioFrameCount);

    av_packet_free(&packet);
    av_frame_free(&frame);
    swr_free(&swrContext);
    av_channel_layout_uninit(&outChannelLayout);
    avcodec_free_context(&codecContext);
    avformat_close_input(&audioFormatContext);
}

JNIEnv *NativePlayer::getJNIEnv(bool *needDetach) {
    if (needDetach != nullptr) {
        *needDetach = false;
    }

    if (javaVm == nullptr) {
        return nullptr;
    }

    JNIEnv *env = nullptr;
    int status = javaVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);

    if (status == JNI_OK) {
        return env;
    }

    if (status == JNI_EDETACHED) {
        if (javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            if (needDetach != nullptr) {
                *needDetach = true;
            }
            return env;
        }
    }

    return nullptr;
}

void NativePlayer::releaseJNIEnv(bool needDetach) {
    if (needDetach && javaVm != nullptr) {
        javaVm->DetachCurrentThread();
    }
}

void NativePlayer::notifyAudioInfo(int sampleRate, int channels) {
    bool needDetach = false;
    JNIEnv *env = getJNIEnv(&needDetach);

    if (env != nullptr && javaPlayerObject != nullptr && onNativeAudioInfoMethod != nullptr) {
        env->CallVoidMethod(javaPlayerObject, onNativeAudioInfoMethod, sampleRate, channels);
    }

    releaseJNIEnv(needDetach);
}

void NativePlayer::notifyAudioData(uint8_t *data, int size) {
    if (data == nullptr || size <= 0) {
        return;
    }

    bool needDetach = false;
    JNIEnv *env = getJNIEnv(&needDetach);

    if (env != nullptr && javaPlayerObject != nullptr && onNativeAudioDataMethod != nullptr) {
        jbyteArray byteArray = env->NewByteArray(size);
        if (byteArray != nullptr) {
            env->SetByteArrayRegion(
                    byteArray,
                    0,
                    size,
                    reinterpret_cast<jbyte *>(data)
            );

            env->CallVoidMethod(javaPlayerObject, onNativeAudioDataMethod, byteArray, size);
            env->DeleteLocalRef(byteArray);
        }
    }

    releaseJNIEnv(needDetach);
}

void NativePlayer::releaseJavaCallback() {
    if (javaVm == nullptr || javaPlayerObject == nullptr) {
        return;
    }

    bool needDetach = false;
    JNIEnv *env = getJNIEnv(&needDetach);

    if (env != nullptr) {
        env->DeleteGlobalRef(javaPlayerObject);
    }

    javaPlayerObject = nullptr;
    onNativeAudioInfoMethod = nullptr;
    onNativeAudioDataMethod = nullptr;

    releaseJNIEnv(needDetach);
}

std::string NativePlayer::getFFmpegVersion() {
    return std::string(av_version_info());
}

void NativePlayer::releaseFormatContext() {
    stop();

    if (formatContext != nullptr) {
        avformat_close_input(&formatContext);
        formatContext = nullptr;
    }

    videoStreamIndex = -1;
    audioStreamIndex = -1;
    prepared = false;
}

void NativePlayer::releaseSurface() {
    std::lock_guard<std::mutex> lock(windowMutex);

    if (nativeWindow != nullptr) {
        ANativeWindow_release(nativeWindow);
        nativeWindow = nullptr;
    }
}

std::string NativePlayer::makeErrorString(int ret) {
    char errorBuffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(ret, errorBuffer, sizeof(errorBuffer));
    return std::string(errorBuffer);
}
