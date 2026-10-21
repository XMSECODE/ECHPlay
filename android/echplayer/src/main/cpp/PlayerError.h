#pragma once

#include <cerrno>
extern "C" {
#include <libavutil/error.h>
}

/** 稳定的播放器错误码；数值与 Java 公共常量保持一致。 */
namespace PlayerError {
/** 打开输入失败，尚不能确定原因。 */
constexpr int OPEN_INPUT = 1001;
/** 探测流信息失败，尚不能确定原因。 */
constexpr int STREAM_INFO = 1002;
/** 没有视频轨道。 */
constexpr int NO_VIDEO = 1003;
/** 网络连接或读取超时。 */
constexpr int TIMEOUT = 1005;
/** HTTP/RTSP 鉴权失败。 */
constexpr int AUTH = 1006;
/** 连接被拒绝、网络不可达或服务暂不可用。 */
constexpr int CONNECTION_FAILED = 1011;
/** 网络连接意外断开。 */
constexpr int CONNECTION_LOST = 1012;
/** 文件或服务端资源不存在。 */
constexpr int SOURCE_NOT_FOUND = 1013;
/** 没有资源访问权限。 */
constexpr int ACCESS_DENIED = 1014;
/** 地址、请求或媒体数据无效。 */
constexpr int INVALID_SOURCE = 1015;
/** 当前构建不支持该协议。 */
constexpr int UNSUPPORTED_PROTOCOL = 1016;
/** 主动取消，仅作内部诊断，不作为播放错误回调。 */
constexpr int CANCELLED = 1017;
/** 无法归类的错误，不自动重试。 */
constexpr int UNKNOWN = 1999;

/** 只按 FFmpeg 数值错误分类，不读取可能包含误导关键词的 URL 或日志。 */
inline int fromAvError(int error, bool network, int fallback) {
    if (error == AVERROR_HTTP_UNAUTHORIZED) return AUTH;
    if (error == AVERROR_HTTP_FORBIDDEN || error == AVERROR(EACCES)
            || error == AVERROR(EPERM)) return ACCESS_DENIED;
    if (error == AVERROR_HTTP_NOT_FOUND || error == AVERROR(ENOENT)) return SOURCE_NOT_FOUND;
    if (error == AVERROR_PROTOCOL_NOT_FOUND) return UNSUPPORTED_PROTOCOL;
    if (network && error == AVERROR(ETIMEDOUT)) return TIMEOUT;
    if (network && (error == AVERROR(ECONNREFUSED) || error == AVERROR(ENETUNREACH)
            || error == AVERROR(EHOSTUNREACH) || error == AVERROR_HTTP_SERVER_ERROR)) {
        return CONNECTION_FAILED;
    }
    if (network && (error == AVERROR(ECONNRESET) || error == AVERROR(ECONNABORTED)
            || error == AVERROR(EPIPE) || error == AVERROR(ENOTCONN)
            || error == AVERROR_EOF || error == AVERROR(EIO))) return CONNECTION_LOST;
    if (error == AVERROR(EINVAL) || error == AVERROR_INVALIDDATA
            || error == AVERROR_HTTP_BAD_REQUEST || error == AVERROR_HTTP_OTHER_4XX) {
        return INVALID_SOURCE;
    }
    return fallback;
}
}
