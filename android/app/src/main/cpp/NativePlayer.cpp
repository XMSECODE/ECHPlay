#include "NativePlayer.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <algorithm>
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
}

#define ECH_LOG_TAG "ECHPlayer"
#define ECH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ECH_LOG_TAG, __VA_ARGS__)
#define ECH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ECH_LOG_TAG, __VA_ARGS__)

static std::string renderRgbaFrameToWindow(
        ANativeWindow *nativeWindow,
        AVFrame *rgbaFrame,
        int width,
        int height) {

    if (nativeWindow == nullptr) {
        return "render failed: nativeWindow is null";
    }

    int ret = ANativeWindow_setBuffersGeometry(
            nativeWindow,
            width,
            height,
            WINDOW_FORMAT_RGBA_8888
    );

    if (ret < 0) {
        return "render failed: ANativeWindow_setBuffersGeometry failed";
    }

    ANativeWindow_Buffer windowBuffer;
    ret = ANativeWindow_lock(nativeWindow, &windowBuffer, nullptr);

    if (ret < 0) {
        return "render failed: ANativeWindow_lock failed";
    }

    uint8_t *dst = static_cast<uint8_t *>(windowBuffer.bits);
    int dstStride = windowBuffer.stride * 4;

    uint8_t *src = rgbaFrame->data[0];
    int srcStride = rgbaFrame->linesize[0];

    int copyHeight = std::min(height, windowBuffer.height);
    int copyWidthBytes = std::min(width, windowBuffer.width) * 4;

    for (int y = 0; y < copyHeight; ++y) {
        memcpy(dst + y * dstStride, src + y * srcStride, copyWidthBytes);
    }

    ANativeWindow_unlockAndPost(nativeWindow);

    return "render first video frame success";
}

NativePlayer::NativePlayer()
        : formatContext(nullptr),
          videoStreamIndex(-1),
          audioStreamIndex(-1),
          prepared(false),
          released(false) {
    ECH_LOGI("NativePlayer create");
    ECH_LOGI("NativePlayer init");
}

NativePlayer::~NativePlayer() {
    if (!released) {
        released = true;
        ECH_LOGI("NativePlayer release");
    }

    releaseFormatContext();

    ECH_LOGI("NativePlayer destroy");
}

void NativePlayer::setDataSource(const std::string &source) {
    dataSource = source;
    ECH_LOGI("setDataSource: %s", dataSource.c_str());
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

    if (videoStreamIndex < 0 && audioStreamIndex < 0) {
        releaseFormatContext();
        return "prepare failed: no audio or video stream found";
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

    if (videoStreamIndex >= 0) {
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
    } else {
        oss << "\nvideo stream: not found\n";
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

std::string NativePlayer::decodeFirstVideoFrame() {
    return decodeFirstVideoFrameInternal(nullptr);
}

std::string NativePlayer::renderFirstVideoFrame(ANativeWindow *nativeWindow) {
    if (nativeWindow == nullptr) {
        return "render failed: nativeWindow is null";
    }

    return decodeFirstVideoFrameInternal(nativeWindow);
}

std::string NativePlayer::decodeFirstVideoFrameInternal(ANativeWindow *nativeWindow) {
    if (!prepared || formatContext == nullptr) {
        return "decode failed: player is not prepared";
    }

    if (videoStreamIndex < 0) {
        return "decode failed: no video stream";
    }

    bool needRender = nativeWindow != nullptr;

    ECH_LOGI(
            "%s start",
            needRender ? "renderFirstVideoFrame" : "decodeFirstVideoFrame"
    );

    AVStream *videoStream = formatContext->streams[videoStreamIndex];
    AVCodecParameters *codecParameters = videoStream->codecpar;

    const AVCodec *decoder = avcodec_find_decoder(codecParameters->codec_id);
    if (decoder == nullptr) {
        return "decode failed: decoder not found";
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(decoder);
    if (codecContext == nullptr) {
        return "decode failed: avcodec_alloc_context3 failed";
    }

    int ret = avcodec_parameters_to_context(codecContext, codecParameters);
    if (ret < 0) {
        std::string error = makeErrorString(ret);
        avcodec_free_context(&codecContext);
        return "decode failed\nstep: avcodec_parameters_to_context\nerror: " + error;
    }

    ret = avcodec_open2(codecContext, decoder, nullptr);
    if (ret < 0) {
        std::string error = makeErrorString(ret);
        avcodec_free_context(&codecContext);
        return "decode failed\nstep: avcodec_open2\nerror: " + error;
    }

    av_seek_frame(formatContext, videoStreamIndex, 0, AVSEEK_FLAG_BACKWARD);
    avcodec_flush_buffers(codecContext);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();

    if (packet == nullptr || frame == nullptr) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codecContext);
        return "decode failed: alloc packet/frame failed";
    }

    int packetCount = 0;
    bool gotFrame = false;
    std::ostringstream oss;

    while ((ret = av_read_frame(formatContext, packet)) >= 0) {
        if (packet->stream_index != videoStreamIndex) {
            av_packet_unref(packet);
            continue;
        }

        packetCount++;

        ret = avcodec_send_packet(codecContext, packet);
        av_packet_unref(packet);

        if (ret < 0) {
            std::string error = makeErrorString(ret);
            oss << "decode failed\n";
            oss << "step: avcodec_send_packet\n";
            oss << "error: " << error;
            break;
        }

        while (ret >= 0) {
            ret = avcodec_receive_frame(codecContext, frame);

            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }

            if (ret < 0) {
                std::string error = makeErrorString(ret);
                oss << "decode failed\n";
                oss << "step: avcodec_receive_frame\n";
                oss << "error: " << error;
                break;
            }

            gotFrame = true;

            const char *pixelFormatName = av_get_pix_fmt_name(
                    static_cast<AVPixelFormat>(frame->format)
            );

            oss << "decode first video frame success\n";
            oss << "codec: " << avcodec_get_name(codecParameters->codec_id) << "\n";
            oss << "frame size: " << frame->width << "x" << frame->height << "\n";
            oss << "pixel format: " << (pixelFormatName ? pixelFormatName : "unknown") << "\n";
            oss << "pts: " << frame->pts << "\n";
            oss << "best effort timestamp: " << frame->best_effort_timestamp << "\n";
            oss << "packet count: " << packetCount << "\n";

            if (needRender) {
                SwsContext *swsContext = sws_getContext(
                        frame->width,
                        frame->height,
                        static_cast<AVPixelFormat>(frame->format),
                        frame->width,
                        frame->height,
                        AV_PIX_FMT_RGBA,
                        SWS_BILINEAR,
                        nullptr,
                        nullptr,
                        nullptr
                );

                if (swsContext == nullptr) {
                    oss << "\nrender failed: sws_getContext failed";
                } else {
                    AVFrame *rgbaFrame = av_frame_alloc();

                    int rgbaBufferSize = av_image_get_buffer_size(
                            AV_PIX_FMT_RGBA,
                            frame->width,
                            frame->height,
                            1
                    );

                    std::vector<uint8_t> rgbaBuffer(rgbaBufferSize);

                    if (rgbaFrame == nullptr || rgbaBufferSize <= 0) {
                        oss << "\nrender failed: alloc RGBA frame failed";
                    } else {
                        av_image_fill_arrays(
                                rgbaFrame->data,
                                rgbaFrame->linesize,
                                rgbaBuffer.data(),
                                AV_PIX_FMT_RGBA,
                                frame->width,
                                frame->height,
                                1
                        );

                        sws_scale(
                                swsContext,
                                frame->data,
                                frame->linesize,
                                0,
                                frame->height,
                                rgbaFrame->data,
                                rgbaFrame->linesize
                        );

                        std::string renderResult = renderRgbaFrameToWindow(
                                nativeWindow,
                                rgbaFrame,
                                frame->width,
                                frame->height
                        );

                        oss << "\n";
                        oss << renderResult << "\n";
                        oss << "render format: RGBA_8888\n";
                    }

                    av_frame_free(&rgbaFrame);
                    sws_freeContext(swsContext);
                }
            }

            ECH_LOGI(
                    "%s success, size=%dx%d, format=%s",
                    needRender ? "render first video frame" : "decode first video frame",
                    frame->width,
                    frame->height,
                    pixelFormatName ? pixelFormatName : "unknown"
            );

            break;
        }

        if (gotFrame) {
            break;
        }
    }

    if (!gotFrame && oss.str().empty()) {
        oss << "decode failed: no video frame decoded";
    }

    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&codecContext);

    return oss.str();
}

std::string NativePlayer::getFFmpegVersion() {
    return std::string(av_version_info());
}

void NativePlayer::releaseFormatContext() {
    if (formatContext != nullptr) {
        avformat_close_input(&formatContext);
        formatContext = nullptr;
    }

    videoStreamIndex = -1;
    audioStreamIndex = -1;
    prepared = false;
}

std::string NativePlayer::makeErrorString(int ret) {
    char errorBuffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(ret, errorBuffer, sizeof(errorBuffer));
    return std::string(errorBuffer);
}
