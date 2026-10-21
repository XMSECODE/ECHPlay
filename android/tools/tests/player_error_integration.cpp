#include "IoInterrupt.h"
#include "PlayerError.h"

#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <iostream>
#include <stdexcept>
#include <thread>

extern "C" {
#include <libavformat/avformat.h>
}

/** 断言实际 FFmpeg 返回值与播放器分类符合预期。 */
static void require(bool passed, const char *message) {
    if (!passed) throw std::runtime_error(message);
}

/** 只响应 OPTIONS 和失败的 DESCRIBE，模拟摄像头返回不同错误。 */
class ErrorServer {
public:
    /** 本机临时监听端口。 */
    uint16_t port = 0;

    /** 创建环回 RTSP 服务；状态零代表接收连接后直接断开。 */
    explicit ErrorServer(int status) {
        listener = socket(AF_INET, SOCK_STREAM, 0);
        require(listener >= 0, "socket failed");
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        require(bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0,
                "bind failed");
        require(listen(listener, 1) == 0, "listen failed");
        socklen_t length = sizeof(address);
        require(getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length) == 0,
                "getsockname failed");
        port = ntohs(address.sin_port);
        worker = std::thread([this, status] {
            int fd = accept(listener, nullptr, nullptr);
            client = fd;
            if (fd < 0) return;
            timeval timeout{3, 0};
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            while (status != 0) {
                std::string request;
                char byte;
                while (recv(fd, &byte, 1, 0) == 1) {
                    request += byte;
                    if (request.find("\r\n\r\n") != std::string::npos) break;
                }
                if (request.empty()) break;
                bool options = request.find("OPTIONS ") == 0;
                size_t sequence = request.find("CSeq: ");
                if (sequence == std::string::npos) break;
                std::string cseq = request.substr(sequence,
                        request.find("\r\n", sequence) - sequence);
                std::string response = "RTSP/1.0 " + std::to_string(options ? 200 : status)
                        + " Test\r\n" + cseq + "\r\nContent-Length: 0\r\n\r\n";
                size_t offset = 0;
                while (offset < response.size()) {
                    ssize_t sent = send(fd, response.data() + offset, response.size() - offset, 0);
                    if (sent <= 0) break;
                    offset += static_cast<size_t>(sent);
                }
                if (!options) break;
            }
            shutdown(fd, SHUT_RDWR);
        });
    }

    /** 回收服务线程和两个 socket，不保留后台任务。 */
    ~ErrorServer() {
        shutdown(listener, SHUT_RDWR);
        close(listener);
        if (client >= 0) shutdown(client, SHUT_RDWR);
        if (worker.joinable()) worker.join();
        if (client >= 0) close(client);
    }

private:
    /** 监听连接的 socket。 */
    int listener = -1;
    /** 服务线程接受的客户端句柄。 */
    std::atomic<int> client{-1};
    /** 独立 RTSP 响应线程。 */
    std::thread worker;
};

/** 为测试设置总体期限，避免协议变动使测试无限等待。 */
static int interruptIo(void *opaque) {
    return static_cast<IoInterrupt *>(opaque)->shouldInterrupt();
}

/** 验证真实 RTSP 返回值，URL 特意带有会误导旧字符串匹配的关键词。 */
static void testStatus(int status, int expected) {
    ErrorServer server(status);
    IoInterrupt interrupt;
    interrupt.begin(3000000);
    AVFormatContext *context = avformat_alloc_context();
    context->interrupt_callback = {interruptIo, &interrupt};
    std::string url = "rtsp://127.0.0.1:" + std::to_string(server.port)
            + "/timeout/unauthorized";
    int result = avformat_open_input(&context, url.c_str(), nullptr, nullptr);
    avformat_close_input(&context);
    int actual = PlayerError::fromAvError(result, true, PlayerError::OPEN_INPUT);
    std::cout << "RTSP " << status << ": avError=" << result << ", playerError=" << actual << '\n';
    require(result < 0 && !interrupt.hasTimedOut(), "RTSP fixture must return an error promptly");
    require(actual == expected, "RTSP status classification mismatch");
}

/** 检查阶段无关分类及真实协议错误。 */
int main() {
    try {
        av_log_set_level(AV_LOG_ERROR);
        for (int fallback : {PlayerError::OPEN_INPUT, PlayerError::STREAM_INFO}) {
            require(PlayerError::fromAvError(AVERROR(ETIMEDOUT), true, fallback)
                    == PlayerError::TIMEOUT, "open/probe timeout mismatch");
            require(PlayerError::fromAvError(AVERROR_EXIT, true, fallback) == fallback,
                    "FFmpeg EXIT alone must not be mistaken for user cancellation");
        }
        require(PlayerError::fromAvError(AVERROR(EIO), false, PlayerError::OPEN_INPUT)
                == PlayerError::OPEN_INPUT, "local I/O error must not become network failure");
        require(PlayerError::fromAvError(AVERROR(ENOENT), false, PlayerError::OPEN_INPUT)
                == PlayerError::SOURCE_NOT_FOUND, "missing file mismatch");
        require(PlayerError::fromAvError(AVERROR_INVALIDDATA, true, PlayerError::OPEN_INPUT)
                == PlayerError::INVALID_SOURCE, "invalid media mismatch");
        require(PlayerError::fromAvError(AVERROR_PROTOCOL_NOT_FOUND, false, PlayerError::OPEN_INPUT)
                == PlayerError::UNSUPPORTED_PROTOCOL, "unsupported protocol mismatch");
        testStatus(401, PlayerError::AUTH);
        testStatus(403, PlayerError::ACCESS_DENIED);
        testStatus(404, PlayerError::SOURCE_NOT_FOUND);
        testStatus(400, PlayerError::INVALID_SOURCE);
        testStatus(503, PlayerError::CONNECTION_FAILED);
        // FFmpeg 将没有任何 RTSP 响应的提前断开归为 INVALIDDATA，不能凭空推断为暂时故障。
        testStatus(0, PlayerError::INVALID_SOURCE);
        std::cout << "Player error integration tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
