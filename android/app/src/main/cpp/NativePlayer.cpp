#include "NativePlayer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <iomanip>
#include <limits>
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

static constexpr size_t VIDEO_PACKET_QUEUE_MAX = 120;
static constexpr size_t AUDIO_PACKET_QUEUE_MAX = 240;
static constexpr int64_t AV_SYNC_THRESHOLD_MIN_US = 40000;   // 40ms
static constexpr int64_t AV_SYNC_THRESHOLD_MAX_US = 100000;  // 100ms
static constexpr int64_t AV_NOSYNC_THRESHOLD_US = 10000000;  // 10s

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
          demuxFinished(false),
          activePlaybackWorkers(0),
          audioClockUs(std::numeric_limits<int64_t>::min()),
          swsContextCache(nullptr),
          rgbaFrameCache(nullptr),
          renderSrcWidth(0),
          renderSrcHeight(0),
          renderSrcFormat(-1),
          renderDstWidth(0),
          renderDstHeight(0),
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
    clearRenderCache();
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

    avformat_network_init();

    AVDictionary *options = nullptr;
    if (dataSource.rfind("rtsp://", 0) == 0) {
        av_dict_set(&options, "rtsp_transport", "tcp", 0);
        av_dict_set(&options, "timeout", "5000000", 0);
        av_dict_set(&options, "rw_timeout", "5000000", 0);
        av_dict_set(&options, "buffer_size", "1024000", 0);
        av_dict_set(&options, "max_delay", "500000", 0);
    }

    int ret = avformat_open_input(&formatContext, dataSource.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (ret < 0) {
        std::string error = makeErrorString(ret);
        ECH_LOGE("avformat_open_input failed: %s", error.c_str());

        return "prepare failed\n"
               "step: avformat_open_input\n"
               "error: " + error + "\n"
               "hint: " + makeOpenInputHint(error) + "\n"
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

    if (demuxThread.joinable()) {
        demuxThread.join();
    }

    if (playThread.joinable()) {
        playThread.join();
    }

    if (audioThread.joinable()) {
        audioThread.join();
    }

    clearPacketQueues();

    stopRequested = false;
    paused = false;
    demuxFinished = false;
    playing = true;
    activePlaybackWorkers = audioStreamIndex >= 0 ? 2 : 1;
    audioClockUs = std::numeric_limits<int64_t>::min();

    demuxThread = std::thread(&NativePlayer::demuxLoop, this);
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
    packetQueueCond.notify_all();

    if (demuxThread.joinable()) {
        demuxThread.join();
    }

    if (playThread.joinable()) {
        playThread.join();
    }

    if (audioThread.joinable()) {
        audioThread.join();
    }

    clearPacketQueues();
    clearRenderCache();
    playing = false;
    demuxFinished = false;
    activePlaybackWorkers = 0;
    audioClockUs = std::numeric_limits<int64_t>::min();

    ECH_LOGI("play stopped");
}

std::string NativePlayer::seekToMs(int64_t positionMs) {
    if (!prepared || formatContext == nullptr) {
        return "seek failed: player is not prepared";
    }

    if (positionMs < 0) {
        positionMs = 0;
    }

    bool wasPlaying = playing.load();
    bool wasPaused = paused.load();

    auto startPlaybackThreads = [&](bool startPaused) {
        stopRequested = false;
        paused = startPaused;
        demuxFinished = false;
        playing = true;
        activePlaybackWorkers = audioStreamIndex >= 0 ? 2 : 1;
        audioClockUs = audioStreamIndex >= 0 ? 0 : std::numeric_limits<int64_t>::min();

        demuxThread = std::thread(&NativePlayer::demuxLoop, this);
        playThread = std::thread(&NativePlayer::decodeLoop, this);
        if (audioStreamIndex >= 0) {
            audioThread = std::thread(&NativePlayer::audioDecodeLoop, this);
        }
    };

    if (wasPlaying) {
        stopRequested = true;
        packetQueueCond.notify_all();

        if (demuxThread.joinable()) {
            demuxThread.join();
        }
        if (playThread.joinable()) {
            playThread.join();
        }
        if (audioThread.joinable()) {
            audioThread.join();
        }

        clearPacketQueues();
        demuxFinished = false;
        activePlaybackWorkers = 0;
        playing = false;
    }

    int64_t seekTargetUs = positionMs * 1000;

    int ret = avformat_seek_file(
            formatContext,
            -1,
            std::numeric_limits<int64_t>::min(),
            seekTargetUs,
            std::numeric_limits<int64_t>::max(),
            AVSEEK_FLAG_BACKWARD
    );

    if (ret < 0) {
        ret = av_seek_frame(
                formatContext,
                -1,
                seekTargetUs,
                AVSEEK_FLAG_BACKWARD
        );
    }

    if (ret < 0) {
        std::string error = makeErrorString(ret);

        if (wasPlaying) {
            startPlaybackThreads(wasPaused);
        }

        return "seek failed\n"
               "positionMs: " + std::to_string(positionMs) + "\n"
               "error: " + error;
    }

    avformat_flush(formatContext);
    clearPacketQueues();

    audioClockUs = audioStreamIndex >= 0 ? 0 : std::numeric_limits<int64_t>::min();
    demuxFinished = false;
    stopRequested = false;

    if (wasPlaying) {
        startPlaybackThreads(wasPaused);
    }

    return "seek success\npositionMs: " + std::to_string(positionMs);
}

int64_t NativePlayer::getDurationMs() {
    if (formatContext == nullptr || formatContext->duration == AV_NOPTS_VALUE) {
        return -1;
    }

    return formatContext->duration / 1000;
}

int64_t NativePlayer::getCurrentPositionMs() {
    int64_t clockUs = audioClockUs.load();
    if (clockUs == std::numeric_limits<int64_t>::min()) {
        return -1;
    }

    return clockUs / 1000;
}

void NativePlayer::demuxLoop() {
    ECH_LOGI("demuxLoop start");

    AVPacket *packet = av_packet_alloc();
    if (packet == nullptr) {
        demuxFinished = true;
        packetQueueCond.notify_all();
        ECH_LOGE("demux packet alloc failed");
        return;
    }

    while (!stopRequested.load()) {
        int ret = av_read_frame(formatContext, packet);
        if (ret < 0) {
            break;
        }

        if (packet->stream_index == videoStreamIndex || packet->stream_index == audioStreamIndex) {
            enqueuePacket(packet);
        }

        av_packet_unref(packet);
    }

    av_packet_free(&packet);
    demuxFinished = true;
    packetQueueCond.notify_all();

    ECH_LOGI("demuxLoop finished");
}

void NativePlayer::decodeLoop() {
    ECH_LOGI("decodeLoop start");

    if (formatContext == nullptr || videoStreamIndex < 0) {
        ECH_LOGE("decodeLoop invalid state");
        markPlaybackWorkerFinished();
        return;
    }

    AVStream *videoStream = formatContext->streams[videoStreamIndex];
    AVCodecParameters *codecParameters = videoStream->codecpar;

    const AVCodec *decoder = avcodec_find_decoder(codecParameters->codec_id);
    if (decoder == nullptr) {
        ECH_LOGE("decoder not found");
        markPlaybackWorkerFinished();
        return;
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(decoder);
    if (codecContext == nullptr) {
        ECH_LOGE("avcodec_alloc_context3 failed");
        markPlaybackWorkerFinished();
        return;
    }

    int ret = avcodec_parameters_to_context(codecContext, codecParameters);
    if (ret < 0) {
        ECH_LOGE("avcodec_parameters_to_context failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        markPlaybackWorkerFinished();
        return;
    }

    ret = avcodec_open2(codecContext, decoder, nullptr);
    if (ret < 0) {
        ECH_LOGE("avcodec_open2 failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        markPlaybackWorkerFinished();
        return;
    }

    AVFrame *frame = av_frame_alloc();
    if (frame == nullptr) {
        ECH_LOGE("alloc frame failed");
        avcodec_free_context(&codecContext);
        markPlaybackWorkerFinished();
        return;
    }

    double fps = 30.0;
    if (videoStream->avg_frame_rate.num > 0 && videoStream->avg_frame_rate.den > 0) {
        fps = av_q2d(videoStream->avg_frame_rate);
    }

    if (fps <= 0.0 || fps > 120.0) {
        fps = 30.0;
    }

    int64_t defaultFrameDurationUs = static_cast<int64_t>(1000000.0 / fps);
    if (defaultFrameDurationUs <= 0) {
        defaultFrameDurationUs = 33333;
    }

    AVRational videoTimeBase = videoStream->time_base;

    int decodedFrameCount = 0;
    int droppedFrameCount = 0;

    auto renderFrameWithSync = [&](AVFrame *decodedFrame) -> bool {
        while (paused.load() && !stopRequested.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }

        if (stopRequested.load()) {
            av_frame_unref(decodedFrame);
            return false;
        }

        int64_t frameDurationUs = defaultFrameDurationUs;
        if (decodedFrame->duration > 0 && videoTimeBase.num > 0 && videoTimeBase.den > 0) {
            int64_t packetDurationUs = av_rescale_q(
                    decodedFrame->duration,
                    videoTimeBase,
                    AV_TIME_BASE_Q
            );

            if (packetDurationUs > 0 && packetDurationUs < AV_NOSYNC_THRESHOLD_US) {
                frameDurationUs = packetDurationUs;
            }
        }

        int64_t videoPtsUs = std::numeric_limits<int64_t>::min();
        int64_t framePts = decodedFrame->best_effort_timestamp;
        if (framePts == AV_NOPTS_VALUE) {
            framePts = decodedFrame->pts;
        }

        if (framePts != AV_NOPTS_VALUE && videoTimeBase.num > 0 && videoTimeBase.den > 0) {
            videoPtsUs = av_rescale_q(framePts, videoTimeBase, AV_TIME_BASE_Q);
        }

        int64_t delayUs = frameDurationUs;
        if (audioStreamIndex >= 0 && videoPtsUs != std::numeric_limits<int64_t>::min()) {
            int64_t masterClockUs = audioClockUs.load();

            if (masterClockUs != std::numeric_limits<int64_t>::min()) {
                int64_t diffUs = videoPtsUs - masterClockUs;
                int64_t absDiffUs = diffUs >= 0 ? diffUs : -diffUs;

                if (absDiffUs < AV_NOSYNC_THRESHOLD_US) {
                    int64_t syncThresholdUs = std::max<int64_t>(
                            AV_SYNC_THRESHOLD_MIN_US,
                            std::min<int64_t>(AV_SYNC_THRESHOLD_MAX_US, frameDurationUs)
                    );

                    if (diffUs <= -syncThresholdUs) {
                        // Video frame is late relative to audio clock; drop it.
                        droppedFrameCount++;
                        av_frame_unref(decodedFrame);
                        return true;
                    }

                    if (diffUs >= syncThresholdUs) {
                        delayUs = diffUs;
                    }
                }
            }
        }

        if (delayUs > 0) {
            std::this_thread::sleep_for(std::chrono::microseconds(delayUs));
        }

        if (stopRequested.load()) {
            av_frame_unref(decodedFrame);
            return false;
        }

        renderFrameToSurface(decodedFrame);
        av_frame_unref(decodedFrame);
        return true;
    };

    while (!stopRequested.load()) {
        AVPacket packet = {0};
        if (!dequeueVideoPacket(&packet)) {
            break;
        }

        ret = avcodec_send_packet(codecContext, &packet);
        av_packet_unref(&packet);

        if (ret < 0) {
            ECH_LOGE("avcodec_send_packet failed: %s", makeErrorString(ret).c_str());
            continue;
        }

        while (!stopRequested.load()) {
            ret = avcodec_receive_frame(codecContext, frame);

            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }

            if (ret < 0) {
                ECH_LOGE("avcodec_receive_frame failed: %s", makeErrorString(ret).c_str());
                break;
            }

            decodedFrameCount++;
            if (!renderFrameWithSync(frame)) {
                break;
            }
        }
    }

    // Flush buffered frames.
    ret = avcodec_send_packet(codecContext, nullptr);
    if (ret >= 0 || ret == AVERROR_EOF) {
        while (!stopRequested.load()) {
            ret = avcodec_receive_frame(codecContext, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }
            if (ret < 0) {
                break;
            }

            decodedFrameCount++;
            if (!renderFrameWithSync(frame)) {
                break;
            }
        }
    }

    ECH_LOGI(
            "decodeLoop finished, decodedFrameCount=%d, droppedFrameCount=%d",
            decodedFrameCount,
            droppedFrameCount
    );

    av_frame_free(&frame);
    avcodec_free_context(&codecContext);

    markPlaybackWorkerFinished();
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

    if (!ensureRenderCache(videoWidth, videoHeight, frame->format, renderWidth, renderHeight)) {
        ANativeWindow_release(window);
        return false;
    }

    sws_scale(
            swsContextCache,
            frame->data,
            frame->linesize,
            0,
            videoHeight,
            rgbaFrameCache->data,
            rgbaFrameCache->linesize
    );

    ANativeWindow_Buffer windowBuffer;
    ret = ANativeWindow_lock(window, &windowBuffer, nullptr);

    if (ret < 0) {
        ANativeWindow_release(window);
        return false;
    }

    uint8_t *dst = static_cast<uint8_t *>(windowBuffer.bits);
    int dstStride = windowBuffer.stride * 4;

    uint8_t *src = rgbaFrameCache->data[0];
    int srcStride = rgbaFrameCache->linesize[0];

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

    ANativeWindow_release(window);

    return true;
}

bool NativePlayer::ensureRenderCache(
        int srcWidth,
        int srcHeight,
        int srcFormat,
        int dstWidth,
        int dstHeight) {

    if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
        return false;
    }

    bool cacheMatches = swsContextCache != nullptr
                        && rgbaFrameCache != nullptr
                        && renderSrcWidth == srcWidth
                        && renderSrcHeight == srcHeight
                        && renderSrcFormat == srcFormat
                        && renderDstWidth == dstWidth
                        && renderDstHeight == dstHeight;

    if (cacheMatches) {
        return true;
    }

    clearRenderCache();

    swsContextCache = sws_getContext(
            srcWidth,
            srcHeight,
            static_cast<AVPixelFormat>(srcFormat),
            dstWidth,
            dstHeight,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr
    );

    if (swsContextCache == nullptr) {
        return false;
    }

    int rgbaBufferSize = av_image_get_buffer_size(
            AV_PIX_FMT_RGBA,
            dstWidth,
            dstHeight,
            1
    );

    if (rgbaBufferSize <= 0) {
        clearRenderCache();
        return false;
    }

    rgbaFrameCache = av_frame_alloc();
    if (rgbaFrameCache == nullptr) {
        clearRenderCache();
        return false;
    }

    rgbaBufferCache.resize(static_cast<size_t>(rgbaBufferSize));

    int fillRet = av_image_fill_arrays(
            rgbaFrameCache->data,
            rgbaFrameCache->linesize,
            rgbaBufferCache.data(),
            AV_PIX_FMT_RGBA,
            dstWidth,
            dstHeight,
            1
    );

    if (fillRet < 0) {
        clearRenderCache();
        return false;
    }

    renderSrcWidth = srcWidth;
    renderSrcHeight = srcHeight;
    renderSrcFormat = srcFormat;
    renderDstWidth = dstWidth;
    renderDstHeight = dstHeight;

    return true;
}

void NativePlayer::clearRenderCache() {
    if (swsContextCache != nullptr) {
        sws_freeContext(swsContextCache);
        swsContextCache = nullptr;
    }

    if (rgbaFrameCache != nullptr) {
        av_frame_free(&rgbaFrameCache);
    }

    rgbaBufferCache.clear();

    renderSrcWidth = 0;
    renderSrcHeight = 0;
    renderSrcFormat = -1;
    renderDstWidth = 0;
    renderDstHeight = 0;
}

void NativePlayer::audioDecodeLoop() {
    ECH_LOGI("audioDecodeLoop start");

    if (formatContext == nullptr || audioStreamIndex < 0) {
        ECH_LOGE("audioDecodeLoop invalid state");
        markPlaybackWorkerFinished();
        return;
    }

    AVStream *audioStream = formatContext->streams[audioStreamIndex];
    AVCodecParameters *codecParameters = audioStream->codecpar;

    const AVCodec *decoder = avcodec_find_decoder(codecParameters->codec_id);
    if (decoder == nullptr) {
        ECH_LOGE("audio decoder not found");
        markPlaybackWorkerFinished();
        return;
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(decoder);
    if (codecContext == nullptr) {
        ECH_LOGE("audio avcodec_alloc_context3 failed");
        markPlaybackWorkerFinished();
        return;
    }

    int ret = avcodec_parameters_to_context(codecContext, codecParameters);
    if (ret < 0) {
        ECH_LOGE("audio avcodec_parameters_to_context failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        markPlaybackWorkerFinished();
        return;
    }

    ret = avcodec_open2(codecContext, decoder, nullptr);
    if (ret < 0) {
        ECH_LOGE("audio avcodec_open2 failed: %s", makeErrorString(ret).c_str());
        avcodec_free_context(&codecContext);
        markPlaybackWorkerFinished();
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
        markPlaybackWorkerFinished();
        return;
    }

    ret = swr_init(swrContext);
    if (ret < 0) {
        ECH_LOGE("swr_init failed: %s", makeErrorString(ret).c_str());
        swr_free(&swrContext);
        av_channel_layout_uninit(&outChannelLayout);
        avcodec_free_context(&codecContext);
        markPlaybackWorkerFinished();
        return;
    }

    notifyAudioInfo(outSampleRate, outChannels);
    audioClockUs = 0;

    AVFrame *frame = av_frame_alloc();

    if (frame == nullptr) {
        swr_free(&swrContext);
        av_channel_layout_uninit(&outChannelLayout);
        avcodec_free_context(&codecContext);
        markPlaybackWorkerFinished();
        return;
    }

    int decodedAudioFrameCount = 0;

    while (!stopRequested.load()) {
        AVPacket inputPacket = {0};
        if (!dequeueAudioPacket(&inputPacket)) {
            break;
        }

        ret = avcodec_send_packet(codecContext, &inputPacket);
        av_packet_unref(&inputPacket);

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

            if (stopRequested.load()) {
                break;
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
                    int64_t playedSamples = convertedSamples;
                    int64_t playedUs = (playedSamples * 1000000LL) / outSampleRate;
                    audioClockUs.fetch_add(playedUs);
                    decodedAudioFrameCount++;
                }
            }

            av_frame_unref(frame);
        }
    }

    // Flush buffered audio frames.
    ret = avcodec_send_packet(codecContext, nullptr);
    if (ret >= 0 || ret == AVERROR_EOF) {
        while (!stopRequested.load()) {
            ret = avcodec_receive_frame(codecContext, frame);

            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }

            if (ret < 0) {
                break;
            }

            while (paused.load() && !stopRequested.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }

            if (stopRequested.load()) {
                break;
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
                    int64_t playedSamples = convertedSamples;
                    int64_t playedUs = (playedSamples * 1000000LL) / outSampleRate;
                    audioClockUs.fetch_add(playedUs);
                    decodedAudioFrameCount++;
                }
            }

            av_frame_unref(frame);
        }
    }

    ECH_LOGI("audioDecodeLoop finished, decodedAudioFrameCount=%d", decodedAudioFrameCount);

    av_frame_free(&frame);
    swr_free(&swrContext);
    av_channel_layout_uninit(&outChannelLayout);
    avcodec_free_context(&codecContext);

    markPlaybackWorkerFinished();
}

bool NativePlayer::dequeueVideoPacket(AVPacket *outPacket) {
    if (outPacket == nullptr) {
        return false;
    }

    std::unique_lock<std::mutex> lock(packetQueueMutex);

    while (!stopRequested.load() && videoPacketQueue.empty()) {
        if (demuxFinished.load()) {
            return false;
        }
        packetQueueCond.wait(lock);
    }

    if (stopRequested.load() || videoPacketQueue.empty()) {
        return false;
    }

    AVPacket *srcPacket = videoPacketQueue.front();
    videoPacketQueue.pop_front();
    av_packet_move_ref(outPacket, srcPacket);
    av_packet_free(&srcPacket);

    packetQueueCond.notify_all();
    return true;
}

bool NativePlayer::dequeueAudioPacket(AVPacket *outPacket) {
    if (outPacket == nullptr) {
        return false;
    }

    std::unique_lock<std::mutex> lock(packetQueueMutex);

    while (!stopRequested.load() && audioPacketQueue.empty()) {
        if (demuxFinished.load()) {
            return false;
        }
        packetQueueCond.wait(lock);
    }

    if (stopRequested.load() || audioPacketQueue.empty()) {
        return false;
    }

    AVPacket *srcPacket = audioPacketQueue.front();
    audioPacketQueue.pop_front();
    av_packet_move_ref(outPacket, srcPacket);
    av_packet_free(&srcPacket);

    packetQueueCond.notify_all();
    return true;
}

void NativePlayer::enqueuePacket(AVPacket *packet) {
    if (packet == nullptr) {
        return;
    }

    AVPacket *clonePacket = av_packet_alloc();
    if (clonePacket == nullptr) {
        return;
    }

    int ret = av_packet_ref(clonePacket, packet);
    if (ret < 0) {
        av_packet_free(&clonePacket);
        return;
    }

    std::unique_lock<std::mutex> lock(packetQueueMutex);

    auto queueCanPush = [this, packet]() {
        if (packet->stream_index == videoStreamIndex) {
            return videoPacketQueue.size() < VIDEO_PACKET_QUEUE_MAX;
        }
        if (packet->stream_index == audioStreamIndex) {
            return audioPacketQueue.size() < AUDIO_PACKET_QUEUE_MAX;
        }
        return true;
    };

    while (!stopRequested.load() && !queueCanPush()) {
        packetQueueCond.wait(lock);
    }

    if (stopRequested.load()) {
        lock.unlock();
        av_packet_free(&clonePacket);
        return;
    }

    if (packet->stream_index == videoStreamIndex) {
        videoPacketQueue.push_back(clonePacket);
        packetQueueCond.notify_all();
        return;
    }

    if (packet->stream_index == audioStreamIndex) {
        audioPacketQueue.push_back(clonePacket);
        packetQueueCond.notify_all();
        return;
    }

    lock.unlock();
    av_packet_free(&clonePacket);
}

void NativePlayer::clearPacketQueues() {
    std::lock_guard<std::mutex> lock(packetQueueMutex);

    while (!videoPacketQueue.empty()) {
        AVPacket *packet = videoPacketQueue.front();
        videoPacketQueue.pop_front();
        av_packet_free(&packet);
    }

    while (!audioPacketQueue.empty()) {
        AVPacket *packet = audioPacketQueue.front();
        audioPacketQueue.pop_front();
        av_packet_free(&packet);
    }

    packetQueueCond.notify_all();
}

void NativePlayer::markPlaybackWorkerFinished() {
    int expected = activePlaybackWorkers.load();
    while (expected > 0) {
        if (activePlaybackWorkers.compare_exchange_weak(expected, expected - 1)) {
            if (expected - 1 == 0) {
                playing = false;
                packetQueueCond.notify_all();
            }
            return;
        }
    }
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

std::string NativePlayer::makeOpenInputHint(const std::string &error) {
    if (error.find("Connection timed out") != std::string::npos) {
        return "连接超时。请检查设备 IP、端口、网络连通性，或尝试降低延迟设置。";
    }

    if (error.find("Connection refused") != std::string::npos) {
        return "目标拒绝连接。请确认 RTSP 服务已启动，端口正确。";
    }

    if (error.find("401 Unauthorized") != std::string::npos
        || error.find("Authorization") != std::string::npos) {
        return "鉴权失败。请检查 RTSP 用户名和密码。";
    }

    if (error.find("404 Not Found") != std::string::npos
        || error.find("No such file or directory") != std::string::npos) {
        return "地址或流路径不存在。请检查 RTSP URL 是否完整。";
    }

    if (error.find("Invalid data found when processing input") != std::string::npos) {
        return "连接建立了，但返回的数据不是可识别的音视频流。";
    }

    if (error.find("Protocol not found") != std::string::npos) {
        return "当前 FFmpeg 构建未包含该协议支持。";
    }

    return "请检查 RTSP 地址、网络、账号密码，以及设备端是否正在推流。";
}
