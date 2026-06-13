#include "NativePlayer.h"

#include "MediaCodecVideoDecoder.h"

#include <android/log.h>
#include <android/native_window.h>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <cctype>
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
static constexpr int PLAYER_ERROR_NETWORK_TIMEOUT = 1005;
static constexpr int PLAYER_ERROR_UNKNOWN = 1999;
static constexpr int PLAYER_INFO_BUFFERING_START = 2011;
static constexpr int PLAYER_INFO_BUFFERING_END = 2012;
static constexpr int PLAYER_INFO_DECODE_MODE_CHANGED = 2013;
static constexpr int PLAYER_INFO_MEDIACODEC_OPENED = 2014;
static constexpr int PLAYER_INFO_MEDIACODEC_FALLBACK = 2015;
static constexpr int PLAYER_INFO_MEDIACODEC_UNSUPPORTED = 2016;

/** 判断字符串是否以指定前缀开头，比较时忽略大小写。 */
static bool startsWithIgnoreCase(const std::string &value, const char *prefix) {
    size_t prefixLength = std::strlen(prefix);
    if (value.size() < prefixLength) {
        return false;
    }

    for (size_t i = 0; i < prefixLength; ++i) {
        unsigned char left = static_cast<unsigned char>(value[i]);
        unsigned char right = static_cast<unsigned char>(prefix[i]);
        if (std::tolower(left) != std::tolower(right)) {
            return false;
        }
    }
    return true;
}

/** 判断当前数据源是否是 RTSP 地址。 */
static bool isRtspSource(const std::string &source) {
    return startsWithIgnoreCase(source, "rtsp://");
}

/** 判断当前数据源是否是网络地址。 */
static bool isNetworkSource(const std::string &source) {
    return isRtspSource(source)
           || startsWithIgnoreCase(source, "http://")
           || startsWithIgnoreCase(source, "https://");
}

/** 创建 Native 播放器实例并缓存 Java 回调。 */
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
          surfaceScaleType(0),
          renderMode(0),
          decodeMode(0),
          glVideoRenderer(),
          glRenderFailed(false),
          swsContextCache(nullptr),
          captureSwsContextCache(nullptr),
          rgbaFrameCache(nullptr),
          captureFrameCache(nullptr),
          captureFrameWidth(0),
          captureFrameHeight(0),
          renderSrcWidth(0),
          renderSrcHeight(0),
          renderSrcFormat(-1),
          renderDstWidth(0),
          renderDstHeight(0),
          captureSrcWidth(0),
          captureSrcHeight(0),
          captureSrcFormat(-1),
          rtspTransport(0),
          openTimeoutUs(5000000),
          readWriteTimeoutUs(5000000),
          inputBufferSize(1024000),
          maxDelayUs(500000),
          seekable(false),
          videoWidth(0),
          videoHeight(0),
          buffering(false),
          currentDecodeType("software"),
          currentDecoderName("ffmpeg"),
          lastDecodeFallbackReason(),
          javaVm(vm),
          javaPlayerObject(nullptr),
          onNativeAudioInfoMethod(nullptr),
          onNativeAudioDataMethod(nullptr),
          onNativeInfoMethod(nullptr),
          onNativeErrorMethod(nullptr),
          onNativeVideoSizeChangedMethod(nullptr),
          recordFormatContext(nullptr),
          recording(false),
          recordingOutputPath(),
          recordHeaderWritten(false) {

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

            onNativeInfoMethod = env->GetMethodID(
                    clazz,
                    "onNativeInfo",
                    "(ILjava/lang/String;)V"
            );

            onNativeErrorMethod = env->GetMethodID(
                    clazz,
                    "onNativeError",
                    "(ILjava/lang/String;)V"
            );

            onNativeVideoSizeChangedMethod = env->GetMethodID(
                    clazz,
                    "onNativeVideoSizeChanged",
                    "(II)V"
            );

            env->DeleteLocalRef(clazz);
        }
    }

    ECH_LOGI("NativePlayer create");
}

/** 销毁 Native 播放器并释放所有资源。 */
NativePlayer::~NativePlayer() {
    released = true;

    stop();
    clearRenderCache();
    releaseSurface();
    releaseFormatContext();
    releaseJavaCallback();

    ECH_LOGI("NativePlayer destroy");
}

/** 设置数据源路径或网络地址。 */
void NativePlayer::setDataSource(const std::string &source) {
    dataSource = source;
    seekable = false;
    clearVideoSize();
    ECH_LOGI("setDataSource: %s", dataSource.c_str());
}

/** 设置渲染输出 Surface。 */
void NativePlayer::setSurface(ANativeWindow *window) {
    std::lock_guard<std::mutex> lock(windowMutex);
    {
        std::lock_guard<std::mutex> glLock(glRendererMutex);
        glVideoRenderer.release();
        glRenderFailed = false;
    }

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

/** 设置 Surface 渲染缩放方式，0 保持比例居中，1 拉伸填满。 */
void NativePlayer::setSurfaceScaleType(int scaleType) {
    surfaceScaleType = scaleType == 1 ? 1 : 0;
    ECH_LOGI("setSurfaceScaleType: %d", surfaceScaleType.load());
}

/** 设置渲染模式，0 自动，1 OpenGL，2 NativeWindow。 */
void NativePlayer::setRenderMode(int mode) {
    renderMode = (mode == 1 || mode == 2) ? mode : 0;
    if (renderMode.load() != 2) {
        glRenderFailed = false;
    } else {
        std::lock_guard<std::mutex> glLock(glRendererMutex);
        glVideoRenderer.release();
    }
    ECH_LOGI("setRenderMode: %d", renderMode.load());
}

/** 设置解码模式，0 自动，1 软解，2 硬解优先。 */
void NativePlayer::setDecodeMode(int mode) {
    decodeMode = (mode == 1 || mode == 2) ? mode : 0;
    ECH_LOGI("setDecodeMode: %d", decodeMode.load());
}

/** 设置 RTSP 传输方式，0 为 TCP，1 为 UDP。 */
void NativePlayer::setRtspTransport(int transport) {
    rtspTransport = transport;
    ECH_LOGI("setRtspTransport: %d", rtspTransport);
}

/** 设置 long 类型播放器选项。 */
bool NativePlayer::setLongOption(int category, const std::string &name, int64_t value) {
    (void) category;

    if (value < 0) {
        return false;
    }

    if (name == "timeout") {
        openTimeoutUs = value;
        return true;
    }

    if (name == "rw_timeout") {
        readWriteTimeoutUs = value;
        return true;
    }

    if (name == "buffer_size") {
        inputBufferSize = value;
        return true;
    }

    if (name == "max_delay") {
        maxDelayUs = value;
        return true;
    }

    return false;
}

/** 打开输入流并读取音视频信息。 */
std::string NativePlayer::prepare() {
    if (dataSource.empty()) {
        return "prepare failed: dataSource is empty";
    }

    releaseFormatContext();

    ECH_LOGI("prepare start: %s", dataSource.c_str());

    avformat_network_init();

    bool rtspSource = isRtspSource(dataSource);
    bool networkSource = isNetworkSource(dataSource);
    AVDictionary *options = nullptr;
    if (networkSource) {
        av_dict_set(&options, "timeout", std::to_string(openTimeoutUs).c_str(), 0);
        av_dict_set(&options, "rw_timeout", std::to_string(readWriteTimeoutUs).c_str(), 0);
        av_dict_set(&options, "buffer_size", std::to_string(inputBufferSize).c_str(), 0);
    }
    if (rtspSource) {
        av_dict_set(
                &options,
                "rtsp_transport",
                rtspTransport == 1 ? "udp" : "tcp",
                0
        );
        av_dict_set(&options, "max_delay", std::to_string(maxDelayUs).c_str(), 0);
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

    seekable = !rtspSource
               && formatContext->duration != AV_NOPTS_VALUE
               && formatContext->duration > 0;
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
    updateVideoSize(videoCodecPar->width, videoCodecPar->height);
    std::string softwareDecoderName = std::string("ffmpeg-")
                                      + avcodec_get_name(videoCodecPar->codec_id);
    updateDecodeInfo("software", softwareDecoderName, "");
    std::string mediaCodecProbeResult = probeMediaCodecDecoder(videoCodecPar);

    oss << "\n";
    oss << "video stream index: " << videoStreamIndex << "\n";
    oss << "video codec: " << avcodec_get_name(videoCodecPar->codec_id) << "\n";
    oss << "video size: " << videoCodecPar->width << "x" << videoCodecPar->height << "\n";
    oss << "decode mode request: " << decodeMode.load() << "\n";
    if (!mediaCodecProbeResult.empty()) {
        oss << "mediacodec probe: " << mediaCodecProbeResult << "\n";
    }
    oss << "current decoder: " << getCurrentDecodeType() << " / " << getCurrentDecoderName() << "\n";

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

/** 启动播放线程。 */
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
    buffering = false;
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

/** 暂停播放。 */
void NativePlayer::pause() {
    if (playing.load() && !stopRequested.load()) {
        paused = true;
        ECH_LOGI("play paused");
    }
}

/** 恢复播放。 */
void NativePlayer::resume() {
    if (playing.load() && !stopRequested.load()) {
        paused = false;
        ECH_LOGI("play resumed");
    }
}

/** 停止播放并同步停止录制。 */
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

    {
        std::lock_guard<std::mutex> recordLock(recordMutex);
        stopRecordingLocked();
    }

    clearPacketQueues();
    clearRenderCache();
    {
        std::lock_guard<std::mutex> glLock(glRendererMutex);
        glVideoRenderer.release();
        glRenderFailed = false;
    }
    playing = false;
    demuxFinished = false;
    activePlaybackWorkers = 0;
    audioClockUs = std::numeric_limits<int64_t>::min();

    ECH_LOGI("play stopped");
}

/** 跳转到指定毫秒位置。 */
std::string NativePlayer::seekToMs(int64_t positionMs) {
    std::lock_guard<std::mutex> seekLock(seekMutex);

    if (!prepared || formatContext == nullptr) {
        return "seek failed: player is not prepared";
    }

    if (positionMs < 0) {
        positionMs = 0;
    }

    if (!seekable) {
        return "seek failed: stream is not seekable";
    }

    bool wasPlaying = playing.load();
    bool wasPaused = paused.load();
    bool wasRecording = false;
    std::string resumeRecordPath;

    {
        std::lock_guard<std::mutex> recordLock(recordMutex);
        wasRecording = recording;
        resumeRecordPath = recordingOutputPath;
    }

    auto startPlaybackThreads = [&](bool startPaused) {
        stopRequested = false;
        paused = startPaused;
        demuxFinished = false;
        buffering = false;
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

        {
            std::lock_guard<std::mutex> recordLock(recordMutex);
            stopRecordingLocked();
        }

        clearPacketQueues();
        demuxFinished = false;
        activePlaybackWorkers = 0;
        buffering = false;
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
            if (wasRecording && !resumeRecordPath.empty()) {
                startRecording(resumeRecordPath);
            }
        }

        return "seek failed\n"
               "positionMs: " + std::to_string(positionMs) + "\n"
               "error: " + error;
    }

    avformat_flush(formatContext);
    clearPacketQueues();

    audioClockUs = audioStreamIndex >= 0 ? 0 : std::numeric_limits<int64_t>::min();
    demuxFinished = false;
    buffering = false;
    stopRequested = false;

    if (wasPlaying) {
        startPlaybackThreads(wasPaused);
        if (wasRecording && !resumeRecordPath.empty()) {
            startRecording(resumeRecordPath);
        }
    }

    return "seek success\npositionMs: " + std::to_string(positionMs);
}

/** 返回总时长，单位毫秒。 */
int64_t NativePlayer::getDurationMs() {
    if (formatContext == nullptr || formatContext->duration == AV_NOPTS_VALUE) {
        return -1;
    }

    return formatContext->duration / 1000;
}

/** 返回当前播放位置，单位毫秒。 */
int64_t NativePlayer::getCurrentPositionMs() {
    int64_t clockUs = audioClockUs.load();
    if (clockUs == std::numeric_limits<int64_t>::min()) {
        return -1;
    }

    return clockUs / 1000;
}

/** 原子复制最近一帧截图快照。 */
bool NativePlayer::copyCurrentFrameSnapshot(
        std::vector<uint8_t> &rgbaData,
        int &frameWidth,
        int &frameHeight) {
    std::lock_guard<std::mutex> lock(captureFrameMutex);
    if (captureFrameWidth <= 0 || captureFrameHeight <= 0 || captureFrameRgba.empty()) {
        return false;
    }

    rgbaData = captureFrameRgba;
    frameWidth = captureFrameWidth;
    frameHeight = captureFrameHeight;
    return true;
}

/** 开始录制当前播放中的码流到输出文件。 */
std::string NativePlayer::startRecording(const std::string &outputPath) {
    if (!prepared || formatContext == nullptr) {
        return "start recording failed: player is not prepared";
    }

    if (outputPath.empty()) {
        return "start recording failed: output path is empty";
    }

    std::lock_guard<std::mutex> recordLock(recordMutex);
    stopRecordingLocked();

    AVFormatContext *outputContext = nullptr;
    int ret = avformat_alloc_output_context2(&outputContext, nullptr, "matroska", outputPath.c_str());
    if (ret < 0 || outputContext == nullptr) {
        return "start recording failed\nerror: " + makeErrorString(ret < 0 ? ret : AVERROR_UNKNOWN);
    }

    std::vector<int> streamMapping(formatContext->nb_streams, -1);
    std::vector<int64_t> startDts(formatContext->nb_streams, AV_NOPTS_VALUE);
    std::vector<bool> streamReady(formatContext->nb_streams, false);

    for (unsigned int inputIndex = 0; inputIndex < formatContext->nb_streams; ++inputIndex) {
        AVStream *inputStream = formatContext->streams[inputIndex];
        if (inputStream == nullptr || inputStream->codecpar == nullptr) {
            continue;
        }

        AVMediaType mediaType = inputStream->codecpar->codec_type;
        if (mediaType != AVMEDIA_TYPE_VIDEO && mediaType != AVMEDIA_TYPE_AUDIO) {
            continue;
        }

        AVStream *outputStream = avformat_new_stream(outputContext, nullptr);
        if (outputStream == nullptr) {
            avformat_free_context(outputContext);
            return "start recording failed\nerror: avformat_new_stream failed";
        }

        ret = avcodec_parameters_copy(outputStream->codecpar, inputStream->codecpar);
        if (ret < 0) {
            avformat_free_context(outputContext);
            return "start recording failed\nerror: " + makeErrorString(ret);
        }

        outputStream->codecpar->codec_tag = 0;
        outputStream->time_base = inputStream->time_base;
        streamMapping[inputIndex] = outputStream->index;
    }

    if (!(outputContext->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&outputContext->pb, outputPath.c_str(), AVIO_FLAG_WRITE);
        if (ret < 0) {
            avformat_free_context(outputContext);
            return "start recording failed\nerror: " + makeErrorString(ret);
        }
    }

    ret = avformat_write_header(outputContext, nullptr);
    if (ret < 0) {
        if (!(outputContext->oformat->flags & AVFMT_NOFILE) && outputContext->pb != nullptr) {
            avio_closep(&outputContext->pb);
        }
        avformat_free_context(outputContext);
        return "start recording failed\nerror: " + makeErrorString(ret);
    }

    recordFormatContext = outputContext;
    recordStreamMapping = std::move(streamMapping);
    recordStartDts = std::move(startDts);
    recordStreamReady = std::move(streamReady);
    recordingOutputPath = outputPath;
    recordHeaderWritten = true;
    recording = true;

    ECH_LOGI("recording started: %s", recordingOutputPath.c_str());
    return "recording started\nfile: " + recordingOutputPath;
}

/** 停止录制并写入文件尾。 */
std::string NativePlayer::stopRecording() {
    std::lock_guard<std::mutex> recordLock(recordMutex);
    if (!recording) {
        return "recording not running";
    }

    std::string finishedPath = recordingOutputPath;
    stopRecordingLocked();
    return "recording stopped\nfile: " + finishedPath;
}

/** 返回当前是否正在录制。 */
bool NativePlayer::isRecording() {
    std::lock_guard<std::mutex> recordLock(recordMutex);
    return recording;
}

/** 返回当前媒体是否支持 seek。 */
bool NativePlayer::isSeekable() {
    return prepared && formatContext != nullptr && seekable;
}

/** 返回当前视频宽度。 */
int NativePlayer::getVideoWidth() {
    std::lock_guard<std::mutex> lock(videoSizeMutex);
    return videoWidth;
}

/** 返回当前视频高度。 */
int NativePlayer::getVideoHeight() {
    std::lock_guard<std::mutex> lock(videoSizeMutex);
    return videoHeight;
}

/** 返回当前实际解码方式。 */
std::string NativePlayer::getCurrentDecodeType() {
    std::lock_guard<std::mutex> lock(decodeInfoMutex);
    return currentDecodeType;
}

/** 返回当前实际解码器名称。 */
std::string NativePlayer::getCurrentDecoderName() {
    std::lock_guard<std::mutex> lock(decodeInfoMutex);
    return currentDecoderName;
}

/** 返回最近一次硬解失败回退原因。 */
std::string NativePlayer::getLastDecodeFallbackReason() {
    std::lock_guard<std::mutex> lock(decodeInfoMutex);
    return lastDecodeFallbackReason;
}

/** 解封装线程，负责读取原始包并分发给解码与录制。 */
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
            if (ret != AVERROR_EOF && !stopRequested.load()) {
                std::string error = makeErrorString(ret);
                ECH_LOGE("av_read_frame failed: %s", error.c_str());
                int errorCode = ret == AVERROR(ETIMEDOUT)
                                ? PLAYER_ERROR_NETWORK_TIMEOUT
                                : PLAYER_ERROR_UNKNOWN;
                notifyError(errorCode, "network read failed: " + error);
            }
            break;
        }

        writeRecordingPacket(packet);

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

/** 视频解码线程，负责解码和按时钟渲染。 */
void NativePlayer::decodeLoop() {
    ECH_LOGI("decodeLoop start");

    if (formatContext == nullptr || videoStreamIndex < 0) {
        ECH_LOGE("decodeLoop invalid state");
        markPlaybackWorkerFinished();
        return;
    }

    AVStream *videoStream = formatContext->streams[videoStreamIndex];
    AVCodecParameters *codecParameters = videoStream->codecpar;

    if (tryMediaCodecDecodeLoop(videoStream, codecParameters)) {
        markPlaybackWorkerFinished();
        return;
    }

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
    updateDecodeInfo("software", std::string("ffmpeg-") + decoder->name, "");

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

/** 尝试使用 MediaCodec 硬解视频，失败时返回 false 让上层回退软解。 */
bool NativePlayer::tryMediaCodecDecodeLoop(
        AVStream *videoStream,
        const AVCodecParameters *codecParameters) {
    if (videoStream == nullptr || codecParameters == nullptr) {
        return false;
    }

    int requestedDecodeMode = decodeMode.load();
    if (requestedDecodeMode == 1) {
        return false;
    }

    if (!MediaCodecVideoDecoder::isSupportedCodecId(codecParameters->codec_id)) {
        notifyInfo(
                PLAYER_INFO_MEDIACODEC_UNSUPPORTED,
                std::string("unsupported codec: ") + avcodec_get_name(codecParameters->codec_id)
        );
        return false;
    }

    MediaCodecVideoDecoder decoder;
    MediaCodecVideoDecoder::Status status = decoder.configure(codecParameters);
    if (status != MediaCodecVideoDecoder::Status::OK) {
        std::string reason = MediaCodecVideoDecoder::statusToString(status);
        notifyInfo(
                requestedDecodeMode == 2
                ? PLAYER_INFO_MEDIACODEC_FALLBACK
                : PLAYER_INFO_MEDIACODEC_UNSUPPORTED,
                "mediacodec start failed\nreason: " + reason
        );
        updateDecodeInfo(
                "software",
                std::string("ffmpeg-") + avcodec_get_name(codecParameters->codec_id),
                reason
        );
        return false;
    }

    std::string mediaCodecName = decoder.getCodecName();
    updateDecodeInfo("mediacodec", mediaCodecName, "");
    notifyInfo(PLAYER_INFO_MEDIACODEC_OPENED, "mediacodec opened\ncodec: " + mediaCodecName);

    double fps = 30.0;
    if (videoStream->avg_frame_rate.num > 0 && videoStream->avg_frame_rate.den > 0) {
        fps = av_q2d(videoStream->avg_frame_rate);
    }
    if (fps <= 0.0 || fps > 120.0) {
        fps = 30.0;
    }

    int64_t frameDurationUs = static_cast<int64_t>(1000000.0 / fps);
    if (frameDurationUs <= 0) {
        frameDurationUs = 33333;
    }

    int decodedFrameCount = 0;
    int outputTryAgainCount = 0;
    bool sentEndOfStream = false;
    bool outputEndOfStream = false;
    bool hardDecodeFailed = false;
    bool hasPendingPacket = false;
    AVPacket pendingPacket = {0};
    std::string fallbackReason;

    while (!stopRequested.load() && !outputEndOfStream) {
        if (!sentEndOfStream) {
            if (!hasPendingPacket) {
                hasPendingPacket = dequeueVideoPacket(&pendingPacket);
            }

            if (hasPendingPacket) {
                int64_t packetPts = pendingPacket.pts != AV_NOPTS_VALUE
                                    ? pendingPacket.pts
                                    : pendingPacket.dts;
                int64_t ptsUs = packetPts != AV_NOPTS_VALUE
                                ? av_rescale_q(packetPts, videoStream->time_base, AV_TIME_BASE_Q)
                                : 0;
                status = decoder.queueInput(
                        pendingPacket.data,
                        static_cast<size_t>(pendingPacket.size),
                        ptsUs,
                        false
                );
                if (status == MediaCodecVideoDecoder::Status::OK) {
                    av_packet_unref(&pendingPacket);
                    hasPendingPacket = false;
                } else if (status != MediaCodecVideoDecoder::Status::INPUT_TRY_AGAIN) {
                    hardDecodeFailed = true;
                    fallbackReason = MediaCodecVideoDecoder::statusToString(status);
                    break;
                }
            } else if (demuxFinished.load()) {
                status = decoder.queueInput(nullptr, 0, 0, true);
                sentEndOfStream = status == MediaCodecVideoDecoder::Status::OK;
            }
        }

        MediaCodecVideoDecoder::DecodedVideoFrame decodedFrame;
        status = decoder.dequeueOutput(decodedFrame, 10000);
        if (status == MediaCodecVideoDecoder::Status::OK) {
            outputTryAgainCount = 0;
            while (paused.load() && !stopRequested.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            if (stopRequested.load()) {
                break;
            }
            renderMediaCodecFrame(decodedFrame);
            decodedFrameCount++;
            std::this_thread::sleep_for(std::chrono::microseconds(frameDurationUs));
        } else if (status == MediaCodecVideoDecoder::Status::OUTPUT_FORMAT_CHANGED
                   || status == MediaCodecVideoDecoder::Status::OUTPUT_TRY_AGAIN) {
            outputTryAgainCount++;
            if (outputTryAgainCount > 300 && !demuxFinished.load()) {
                hardDecodeFailed = true;
                fallbackReason = "mediacodec output timeout";
                break;
            }
        } else if (status == MediaCodecVideoDecoder::Status::OUTPUT_END_OF_STREAM) {
            outputEndOfStream = true;
        } else {
            hardDecodeFailed = true;
            fallbackReason = MediaCodecVideoDecoder::statusToString(status);
            break;
        }
    }
    if (hasPendingPacket) {
        av_packet_unref(&pendingPacket);
    }

    decoder.release();

    if (hardDecodeFailed || decodedFrameCount == 0) {
        if (fallbackReason.empty()) {
            fallbackReason = decodedFrameCount == 0 ? "no mediacodec output frame" : "unknown";
        }
        notifyInfo(
                PLAYER_INFO_MEDIACODEC_FALLBACK,
                "mediacodec fallback\nreason: " + fallbackReason
        );
        updateDecodeInfo(
                "software",
                std::string("ffmpeg-") + avcodec_get_name(codecParameters->codec_id),
                fallbackReason
        );
        return false;
    }

    ECH_LOGI("MediaCodec decode finished, decodedFrameCount=%d", decodedFrameCount);
    return true;
}

/** 将 MediaCodec 输出帧包装成 AVFrame 并走现有渲染链路。 */
bool NativePlayer::renderMediaCodecFrame(
        const MediaCodecVideoDecoder::DecodedVideoFrame &decodedFrame) {
    if (decodedFrame.width <= 0
        || decodedFrame.height <= 0
        || decodedFrame.yPlane.empty()
        || decodedFrame.uPlane.empty()
        || decodedFrame.vPlane.empty()) {
        return false;
    }

    AVFrame *frame = av_frame_alloc();
    if (frame == nullptr) {
        return false;
    }

    frame->format = AV_PIX_FMT_YUV420P;
    frame->width = decodedFrame.width;
    frame->height = decodedFrame.height;
    frame->pts = decodedFrame.ptsUs;
    frame->best_effort_timestamp = decodedFrame.ptsUs;
    frame->data[0] = const_cast<uint8_t *>(decodedFrame.yPlane.data());
    frame->data[1] = const_cast<uint8_t *>(decodedFrame.uPlane.data());
    frame->data[2] = const_cast<uint8_t *>(decodedFrame.vPlane.data());
    frame->linesize[0] = decodedFrame.yStride;
    frame->linesize[1] = decodedFrame.uStride;
    frame->linesize[2] = decodedFrame.vStride;

    bool rendered = renderFrameToSurface(frame);
    av_frame_free(&frame);
    return rendered;
}

/** 把解码后视频帧渲染到 Surface。 */
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
    updateVideoSize(videoWidth, videoHeight);
    updateCaptureFrameSnapshot(frame);

    bool rendered = tryRenderFrameWithOpenGL(window, frame);
    if (!rendered) {
        rendered = renderFrameWithNativeWindow(window, frame);
    }

    ANativeWindow_release(window);
    return rendered;
}

/** 尝试用 OpenGL ES 三纹理渲染 YUV420P 视频帧。 */
bool NativePlayer::tryRenderFrameWithOpenGL(ANativeWindow *window, AVFrame *frame) {
    if (window == nullptr || frame == nullptr || glRenderFailed.load()) {
        return false;
    }

    int currentRenderMode = renderMode.load();
    if (currentRenderMode == 2) {
        return false;
    }

    if (frame->format != AV_PIX_FMT_YUV420P) {
        return false;
    }

    int surfaceWidth = ANativeWindow_getWidth(window);
    int surfaceHeight = ANativeWindow_getHeight(window);
    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
        return false;
    }

    std::lock_guard<std::mutex> glLock(glRendererMutex);

    if (!glVideoRenderer.matchesSurfaceSize(surfaceWidth, surfaceHeight)) {
        std::string initResult = glVideoRenderer.initialize(window);
        if (!glVideoRenderer.isInitialized()) {
            ECH_LOGE("OpenGL renderer init failed: %s", initResult.c_str());
            glVideoRenderer.release();
            glRenderFailed = true;
            return false;
        }
    }

    std::string renderResult = glVideoRenderer.renderYuv420PFrame(
            frame->data[0],
            frame->linesize[0],
            frame->data[1],
            frame->linesize[1],
            frame->data[2],
            frame->linesize[2],
            frame->width,
            frame->height,
            surfaceScaleType.load()
    );

    if (!renderResult.empty() && renderResult.rfind("OpenGL YUV frame rendered", 0) == 0) {
        return true;
    }

    ECH_LOGE("OpenGL YUV render failed: %s", renderResult.c_str());
    glVideoRenderer.release();
    glRenderFailed = true;
    if (currentRenderMode == 1) {
        ECH_LOGE("OpenGL mode fallback to NativeWindow");
    }
    return false;
}

/** 用 NativeWindow RGBA 兼容路径渲染视频帧。 */
bool NativePlayer::renderFrameWithNativeWindow(ANativeWindow *window, AVFrame *frame) {
    if (window == nullptr || frame == nullptr) {
        return false;
    }

    int surfaceWidth = ANativeWindow_getWidth(window);
    int surfaceHeight = ANativeWindow_getHeight(window);

    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
        return false;
    }

    int videoWidth = frame->width;
    int videoHeight = frame->height;

    if (videoWidth <= 0 || videoHeight <= 0) {
        return false;
    }

    int renderWidth = surfaceWidth;
    int renderHeight = surfaceHeight;
    int offsetX = 0;
    int offsetY = 0;

    if (surfaceScaleType.load() != 1) {
        float scaleX = static_cast<float>(surfaceWidth) / static_cast<float>(videoWidth);
        float scaleY = static_cast<float>(surfaceHeight) / static_cast<float>(videoHeight);
        float scale = std::min(scaleX, scaleY);

        renderWidth = static_cast<int>(videoWidth * scale);
        renderHeight = static_cast<int>(videoHeight * scale);
        offsetX = (surfaceWidth - renderWidth) / 2;
        offsetY = (surfaceHeight - renderHeight) / 2;
    }

    if (renderWidth <= 0 || renderHeight <= 0) {
        return false;
    }

    int ret = ANativeWindow_setBuffersGeometry(
            window,
            surfaceWidth,
            surfaceHeight,
            WINDOW_FORMAT_RGBA_8888
    );

    if (ret < 0) {
        return false;
    }

    if (!ensureRenderCache(videoWidth, videoHeight, frame->format, renderWidth, renderHeight)) {
        return false;
    }
    updateCaptureFrameSnapshot(frame);

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
        return false;
    }

    uint8_t *dst = static_cast<uint8_t *>(windowBuffer.bits);
    int dstStride = windowBuffer.stride * 4;

    uint8_t *src = rgbaFrameCache->data[0];
    int srcStride = rgbaFrameCache->linesize[0];

    for (int y = 0; y < windowBuffer.height; ++y) {
        memset(dst + y * dstStride, 0, dstStride);
    }

    int copyHeight = std::min(renderHeight, windowBuffer.height - offsetY);
    int copyWidthBytes = std::min(renderWidth, windowBuffer.width - offsetX) * 4;

    for (int y = 0; y < copyHeight; ++y) {
        uint8_t *dstRow = dst + (offsetY + y) * dstStride + offsetX * 4;
        uint8_t *srcRow = src + y * srcStride;
        memcpy(dstRow, srcRow, copyWidthBytes);
    }

    ANativeWindow_unlockAndPost(window);

    return true;
}

/** 更新最近一帧截图缓存。 */
bool NativePlayer::updateCaptureFrameSnapshot(AVFrame *frame) {
    if (frame == nullptr || frame->width <= 0 || frame->height <= 0) {
        return false;
    }

    if (!ensureCaptureCache(frame->width, frame->height, frame->format)) {
        return false;
    }

    std::lock_guard<std::mutex> captureLock(captureFrameMutex);
    sws_scale(
            captureSwsContextCache,
            frame->data,
            frame->linesize,
            0,
            frame->height,
            captureFrameCache->data,
            captureFrameCache->linesize
    );
    captureFrameWidth = frame->width;
    captureFrameHeight = frame->height;
    captureFrameRgba = captureFrameBufferCache;
    return true;
}

/** 确保渲染缓存尺寸与像素格式匹配。 */
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

/** 确保截图缓存尺寸与当前原始帧一致。 */
bool NativePlayer::ensureCaptureCache(
        int srcWidth,
        int srcHeight,
        int srcFormat) {

    if (srcWidth <= 0 || srcHeight <= 0) {
        return false;
    }

    bool cacheMatches = captureSwsContextCache != nullptr
                        && captureFrameCache != nullptr
                        && captureSrcWidth == srcWidth
                        && captureSrcHeight == srcHeight
                        && captureSrcFormat == srcFormat;

    if (cacheMatches) {
        return true;
    }

    if (captureSwsContextCache != nullptr) {
        sws_freeContext(captureSwsContextCache);
        captureSwsContextCache = nullptr;
    }

    if (captureFrameCache != nullptr) {
        av_frame_free(&captureFrameCache);
    }

    captureFrameBufferCache.clear();

    captureSwsContextCache = sws_getContext(
            srcWidth,
            srcHeight,
            static_cast<AVPixelFormat>(srcFormat),
            srcWidth,
            srcHeight,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr
    );

    if (captureSwsContextCache == nullptr) {
        return false;
    }

    int rgbaBufferSize = av_image_get_buffer_size(
            AV_PIX_FMT_RGBA,
            srcWidth,
            srcHeight,
            1
    );

    if (rgbaBufferSize <= 0) {
        sws_freeContext(captureSwsContextCache);
        captureSwsContextCache = nullptr;
        return false;
    }

    captureFrameCache = av_frame_alloc();
    if (captureFrameCache == nullptr) {
        sws_freeContext(captureSwsContextCache);
        captureSwsContextCache = nullptr;
        return false;
    }

    captureFrameBufferCache.resize(static_cast<size_t>(rgbaBufferSize));

    int fillRet = av_image_fill_arrays(
            captureFrameCache->data,
            captureFrameCache->linesize,
            captureFrameBufferCache.data(),
            AV_PIX_FMT_RGBA,
            srcWidth,
            srcHeight,
            1
    );

    if (fillRet < 0) {
        av_frame_free(&captureFrameCache);
        sws_freeContext(captureSwsContextCache);
        captureSwsContextCache = nullptr;
        captureFrameBufferCache.clear();
        return false;
    }

    captureSrcWidth = srcWidth;
    captureSrcHeight = srcHeight;
    captureSrcFormat = srcFormat;
    return true;
}

/** 清理渲染相关缓存。 */
void NativePlayer::clearRenderCache() {
    {
        std::lock_guard<std::mutex> glLock(glRendererMutex);
        glVideoRenderer.release();
    }

    if (swsContextCache != nullptr) {
        sws_freeContext(swsContextCache);
        swsContextCache = nullptr;
    }

    if (rgbaFrameCache != nullptr) {
        av_frame_free(&rgbaFrameCache);
    }

    rgbaBufferCache.clear();

    if (captureSwsContextCache != nullptr) {
        sws_freeContext(captureSwsContextCache);
        captureSwsContextCache = nullptr;
    }

    if (captureFrameCache != nullptr) {
        av_frame_free(&captureFrameCache);
    }

    captureFrameBufferCache.clear();

    {
        std::lock_guard<std::mutex> captureLock(captureFrameMutex);
        captureFrameRgba.clear();
        captureFrameWidth = 0;
        captureFrameHeight = 0;
    }

    renderSrcWidth = 0;
    renderSrcHeight = 0;
    renderSrcFormat = -1;
    renderDstWidth = 0;
    renderDstHeight = 0;
    captureSrcWidth = 0;
    captureSrcHeight = 0;
    captureSrcFormat = -1;
}

/** 音频解码线程，负责解码、重采样并回调 Java 播放。 */
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

/** 从视频包队列取一个包。 */
bool NativePlayer::dequeueVideoPacket(AVPacket *outPacket) {
    if (outPacket == nullptr) {
        return false;
    }

    std::unique_lock<std::mutex> lock(packetQueueMutex);

    while (!stopRequested.load() && videoPacketQueue.empty()) {
        if (demuxFinished.load()) {
            return false;
        }
        updateBufferingState(true, "video packet queue is empty");
        packetQueueCond.wait(lock);
    }

    if (stopRequested.load() || videoPacketQueue.empty()) {
        return false;
    }

    updateBufferingState(false, "video packet queue recovered");

    AVPacket *srcPacket = videoPacketQueue.front();
    videoPacketQueue.pop_front();
    av_packet_move_ref(outPacket, srcPacket);
    av_packet_free(&srcPacket);

    packetQueueCond.notify_all();
    return true;
}

/** 从音频包队列取一个包。 */
bool NativePlayer::dequeueAudioPacket(AVPacket *outPacket) {
    if (outPacket == nullptr) {
        return false;
    }

    std::unique_lock<std::mutex> lock(packetQueueMutex);

    while (!stopRequested.load() && audioPacketQueue.empty()) {
        if (demuxFinished.load()) {
            return false;
        }
        updateBufferingState(true, "audio packet queue is empty");
        packetQueueCond.wait(lock);
    }

    if (stopRequested.load() || audioPacketQueue.empty()) {
        return false;
    }

    updateBufferingState(false, "audio packet queue recovered");

    AVPacket *srcPacket = audioPacketQueue.front();
    audioPacketQueue.pop_front();
    av_packet_move_ref(outPacket, srcPacket);
    av_packet_free(&srcPacket);

    packetQueueCond.notify_all();
    return true;
}

/** 把输入包推入相应音视频队列。 */
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

/** 清空音视频包队列。 */
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

/** 记录一个播放工作线程结束。 */
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

/** 把输入包写入录制输出文件。 */
void NativePlayer::writeRecordingPacket(AVPacket *packet) {
    if (packet == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> recordLock(recordMutex);
    if (!recording || recordFormatContext == nullptr) {
        return;
    }

    if (packet->stream_index < 0
        || static_cast<size_t>(packet->stream_index) >= recordStreamMapping.size()) {
        return;
    }

    int outputStreamIndex = recordStreamMapping[packet->stream_index];
    if (outputStreamIndex < 0) {
        return;
    }

    AVStream *inputStream = formatContext->streams[packet->stream_index];
    AVStream *outputStream = recordFormatContext->streams[outputStreamIndex];
    if (inputStream == nullptr || outputStream == nullptr) {
        return;
    }

    bool isVideoPacket = inputStream->codecpar != nullptr
                         && inputStream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO;
    if (!recordStreamReady[packet->stream_index]) {
        if (isVideoPacket && !(packet->flags & AV_PKT_FLAG_KEY)) {
            return;
        }
        recordStreamReady[packet->stream_index] = true;
    }

    AVPacket outputPacket = {0};
    if (av_packet_ref(&outputPacket, packet) < 0) {
        return;
    }

    outputPacket.stream_index = outputStreamIndex;

    if (recordStartDts[packet->stream_index] == AV_NOPTS_VALUE) {
        if (outputPacket.dts != AV_NOPTS_VALUE) {
            recordStartDts[packet->stream_index] = outputPacket.dts;
        } else if (outputPacket.pts != AV_NOPTS_VALUE) {
            recordStartDts[packet->stream_index] = outputPacket.pts;
        } else {
            recordStartDts[packet->stream_index] = 0;
        }
    }

    int64_t startTimestamp = recordStartDts[packet->stream_index];
    if (outputPacket.pts != AV_NOPTS_VALUE) {
        outputPacket.pts -= startTimestamp;
        if (outputPacket.pts < 0) {
            outputPacket.pts = 0;
        }
    }

    if (outputPacket.dts != AV_NOPTS_VALUE) {
        outputPacket.dts -= recordStartDts[packet->stream_index];
        if (outputPacket.dts < 0) {
            outputPacket.dts = 0;
        }
    }

    if (outputPacket.pts != AV_NOPTS_VALUE
        && outputPacket.dts != AV_NOPTS_VALUE
        && outputPacket.dts > outputPacket.pts) {
        outputPacket.dts = outputPacket.pts;
    }

    outputPacket.pos = -1;
    av_packet_rescale_ts(&outputPacket, inputStream->time_base, outputStream->time_base);

    int ret = av_interleaved_write_frame(recordFormatContext, &outputPacket);
    if (ret < 0) {
        ECH_LOGE("record write packet failed: %s", makeErrorString(ret).c_str());
        stopRecordingLocked();
    }

    av_packet_unref(&outputPacket);
}

/** 在已持有录制锁时关闭录制上下文。 */
void NativePlayer::stopRecordingLocked() {
    if (!recording && recordFormatContext == nullptr) {
        recordStreamMapping.clear();
        recordStartDts.clear();
        recordStreamReady.clear();
        recordingOutputPath.clear();
        recordHeaderWritten = false;
        return;
    }

    if (recordFormatContext != nullptr) {
        if (recordHeaderWritten) {
            av_write_trailer(recordFormatContext);
        }

        if (!(recordFormatContext->oformat->flags & AVFMT_NOFILE) && recordFormatContext->pb != nullptr) {
            avio_closep(&recordFormatContext->pb);
        }

        avformat_free_context(recordFormatContext);
        recordFormatContext = nullptr;
    }

    recordStreamMapping.clear();
    recordStartDts.clear();
    recordStreamReady.clear();
    recordingOutputPath.clear();
    recordHeaderWritten = false;
    recording = false;
}

/** 获取当前线程可用的 JNIEnv。 */
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

/** 释放当前线程绑定的 JNIEnv。 */
void NativePlayer::releaseJNIEnv(bool needDetach) {
    if (needDetach && javaVm != nullptr) {
        javaVm->DetachCurrentThread();
    }
}

/** 回调 Java 侧创建音频输出。 */
void NativePlayer::notifyAudioInfo(int sampleRate, int channels) {
    bool needDetach = false;
    JNIEnv *env = getJNIEnv(&needDetach);

    if (env != nullptr && javaPlayerObject != nullptr && onNativeAudioInfoMethod != nullptr) {
        env->CallVoidMethod(javaPlayerObject, onNativeAudioInfoMethod, sampleRate, channels);
    }

    releaseJNIEnv(needDetach);
}

/** 回调 Java 侧写入 PCM 数据。 */
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

/** 回调 Java 播放器信息事件。 */
void NativePlayer::notifyInfo(int infoCode, const std::string &message) {
    bool needDetach = false;
    JNIEnv *env = getJNIEnv(&needDetach);

    if (env != nullptr && javaPlayerObject != nullptr && onNativeInfoMethod != nullptr) {
        jstring messageString = env->NewStringUTF(message.c_str());
        if (messageString != nullptr) {
            env->CallVoidMethod(javaPlayerObject, onNativeInfoMethod, infoCode, messageString);
            env->DeleteLocalRef(messageString);
        }
    }

    releaseJNIEnv(needDetach);
}

/** 更新当前解码信息并通知 Java。 */
void NativePlayer::updateDecodeInfo(
        const std::string &decodeType,
        const std::string &decoderName,
        const std::string &fallbackReason) {
    bool changed = false;
    {
        std::lock_guard<std::mutex> lock(decodeInfoMutex);
        changed = currentDecodeType != decodeType
                  || currentDecoderName != decoderName
                  || lastDecodeFallbackReason != fallbackReason;
        currentDecodeType = decodeType;
        currentDecoderName = decoderName;
        lastDecodeFallbackReason = fallbackReason;
    }

    if (!changed) {
        return;
    }

    std::ostringstream message;
    message << "decodeType: " << decodeType << "\n";
    message << "decoder: " << decoderName;
    if (!fallbackReason.empty()) {
        message << "\n";
        message << "fallbackReason: " << fallbackReason;
    }
    notifyInfo(PLAYER_INFO_DECODE_MODE_CHANGED, message.str());
}

/** 回调 Java 播放器错误事件。 */
void NativePlayer::notifyError(int errorCode, const std::string &message) {
    bool needDetach = false;
    JNIEnv *env = getJNIEnv(&needDetach);

    if (env != nullptr && javaPlayerObject != nullptr && onNativeErrorMethod != nullptr) {
        jstring messageString = env->NewStringUTF(message.c_str());
        if (messageString != nullptr) {
            env->CallVoidMethod(javaPlayerObject, onNativeErrorMethod, errorCode, messageString);
            env->DeleteLocalRef(messageString);
        }
    }

    releaseJNIEnv(needDetach);
}

/** 回调 Java 视频尺寸变化事件。 */
void NativePlayer::notifyVideoSizeChanged(int width, int height) {
    bool needDetach = false;
    JNIEnv *env = getJNIEnv(&needDetach);

    if (env != nullptr
        && javaPlayerObject != nullptr
        && onNativeVideoSizeChangedMethod != nullptr) {
        env->CallVoidMethod(
                javaPlayerObject,
                onNativeVideoSizeChangedMethod,
                width,
                height
        );
    }

    releaseJNIEnv(needDetach);
}

/** 更新视频尺寸并在变化时通知 Java。 */
void NativePlayer::updateVideoSize(int width, int height) {
    if (width <= 0 || height <= 0) {
        return;
    }

    bool changed = false;
    {
        std::lock_guard<std::mutex> lock(videoSizeMutex);
        if (videoWidth != width || videoHeight != height) {
            videoWidth = width;
            videoHeight = height;
            changed = true;
        }
    }

    if (changed) {
        notifyVideoSizeChanged(width, height);
    }
}

/** 清空已记录的视频尺寸。 */
void NativePlayer::clearVideoSize() {
    std::lock_guard<std::mutex> lock(videoSizeMutex);
    videoWidth = 0;
    videoHeight = 0;
}

/** 更新缓冲状态并按需通知 Java。 */
void NativePlayer::updateBufferingState(bool isBuffering, const std::string &message) {
    bool expected = !isBuffering;
    if (buffering.compare_exchange_strong(expected, isBuffering)) {
        notifyInfo(
                isBuffering ? PLAYER_INFO_BUFFERING_START : PLAYER_INFO_BUFFERING_END,
                message
        );
    }
}

/** 释放 Java 回调对象引用。 */
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
    onNativeInfoMethod = nullptr;
    onNativeErrorMethod = nullptr;
    onNativeVideoSizeChangedMethod = nullptr;

    releaseJNIEnv(needDetach);
}

/** 返回 FFmpeg 版本。 */
std::string NativePlayer::getFFmpegVersion() {
    return std::string(av_version_info());
}

/** 在 prepare 阶段探测 MediaCodec 是否可用。 */
std::string NativePlayer::probeMediaCodecDecoder(const AVCodecParameters *codecParameters) {
    int requestedDecodeMode = decodeMode.load();
    if (requestedDecodeMode == 1) {
        return "skip: software mode";
    }

    if (codecParameters == nullptr) {
        return "skip: invalid video codec parameters";
    }

    if (!MediaCodecVideoDecoder::isSupportedCodecId(codecParameters->codec_id)) {
        std::string reason = std::string("unsupported codec: ")
                             + avcodec_get_name(codecParameters->codec_id);
        notifyInfo(PLAYER_INFO_MEDIACODEC_UNSUPPORTED, reason);
        return reason;
    }

    MediaCodecVideoDecoder decoder;
    MediaCodecVideoDecoder::Status status = decoder.configure(codecParameters);
    if (status == MediaCodecVideoDecoder::Status::OK) {
        std::string codecName = decoder.getCodecName();
        notifyInfo(
                PLAYER_INFO_MEDIACODEC_OPENED,
                "mediacodec probe success\ncodec: " + codecName
        );
        decoder.release();
        updateDecodeInfo(
                "software",
                std::string("ffmpeg-") + avcodec_get_name(codecParameters->codec_id),
                ""
        );
        return "success: " + codecName + " available";
    }

    std::string reason = MediaCodecVideoDecoder::statusToString(status);
    notifyInfo(
            requestedDecodeMode == 2
            ? PLAYER_INFO_MEDIACODEC_FALLBACK
            : PLAYER_INFO_MEDIACODEC_UNSUPPORTED,
            "mediacodec probe failed\nreason: " + reason
    );
    updateDecodeInfo(
            "software",
            std::string("ffmpeg-") + avcodec_get_name(codecParameters->codec_id),
            reason
    );
    return "failed: " + reason;
}

/** 释放输入格式上下文。 */
void NativePlayer::releaseFormatContext() {
    stop();

    if (formatContext != nullptr) {
        avformat_close_input(&formatContext);
        formatContext = nullptr;
    }

    videoStreamIndex = -1;
    audioStreamIndex = -1;
    prepared = false;
    seekable = false;
    clearVideoSize();
    buffering = false;
}

/** 释放 Surface 引用。 */
void NativePlayer::releaseSurface() {
    std::lock_guard<std::mutex> lock(windowMutex);
    {
        std::lock_guard<std::mutex> glLock(glRendererMutex);
        glVideoRenderer.release();
        glRenderFailed = false;
    }

    if (nativeWindow != nullptr) {
        ANativeWindow_release(nativeWindow);
        nativeWindow = nullptr;
    }
}

/** 把 FFmpeg 错误码转成字符串。 */
std::string NativePlayer::makeErrorString(int ret) {
    char errorBuffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(ret, errorBuffer, sizeof(errorBuffer));
    return std::string(errorBuffer);
}

/** 生成打开输入流失败时的中文提示。 */
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
