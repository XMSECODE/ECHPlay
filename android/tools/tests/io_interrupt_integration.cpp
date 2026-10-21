#include "IoInterrupt.h"

#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/log.h>
}

/** 验证失败时携带场景说明，便于脚本返回明确的失败原因。 */
static void require(bool passed, const char *message) {
    if (!passed) throw std::runtime_error(message);
}

/** 环回服务：RTSP 不回应，HTTP 发送有限 TS 数据后保持连接。 */
class StalledServer {
public:
    /** 已接受连接的标记，取消测试等待它再发送取消。 */
    std::atomic<bool> accepted{false};
    /** 动态分配的监听端口。 */
    uint16_t port = 0;

    /** 启动本机测试服务，可选 TS 数据决定是否回应 HTTP 请求。 */
    explicit StalledServer(std::vector<char> payload = {}) {
        listener = socket(AF_INET, SOCK_STREAM, 0);
        require(listener >= 0, "socket failed");
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        require(bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0,
                "bind failed");
        require(listen(listener, 1) == 0, "listen failed");
        socklen_t size = sizeof(address);
        require(getsockname(listener, reinterpret_cast<sockaddr *>(&address), &size) == 0,
                "getsockname failed");
        port = ntohs(address.sin_port);
        worker = std::thread([this, payload = std::move(payload)] {
            int connected = accept(listener, nullptr, nullptr);
            client = connected;
            if (connected < 0) return;
            accepted = true;
            if (payload.empty()) return;
            std::string request;
            char byte;
            while (recv(connected, &byte, 1, 0) == 1) {
                request += byte;
                if (request.find("\r\n\r\n") != std::string::npos) break;
            }
            std::string header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n"
                    "Content-Length: " + std::to_string(payload.size() + 1000000) + "\r\n\r\n";
            sendAll(connected, header.data(), header.size());
            sendAll(connected, payload.data(), payload.size());
            // 保留连接，不发送剩余 Content-Length，模拟播放中断流。
        });
    }

    /** 关闭连接并回收测试线程。 */
    ~StalledServer() {
        shutdown(listener, SHUT_RDWR);
        close(listener);
        if (client >= 0) shutdown(client, SHUT_RDWR);
        if (worker.joinable()) worker.join();
        if (client >= 0) close(client);
    }

private:
    /** 服务端监听句柄。 */
    int listener = -1;
    /** 跨线程读取的客户端句柄。 */
    std::atomic<int> client{-1};
    /** 接收连接并发送测试数据的线程。 */
    std::thread worker;

    /** 处理短写；客户端主动取消时允许发送提前结束。 */
    static void sendAll(int fd, const char *data, size_t size) {
        while (size > 0) {
            ssize_t sent = send(fd, data, size, 0);
            if (sent <= 0) return;
            data += sent;
            size -= static_cast<size_t>(sent);
        }
    }
};

/** 复用播放器实际使用的无锁取消与期限实现。 */
static int interruptIo(void *opaque) {
    return static_cast<IoInterrupt *>(opaque)->shouldInterrupt();
}

/** 把实际耗时换算成毫秒，用于验证没有等到 30 秒协议超时。 */
static int64_t elapsedMs(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
}

/** 在真实 RTSP 打开阶段验证主动取消和整体超时。 */
static void testOpen(bool cancel) {
    StalledServer server;
    IoInterrupt interrupt;
    AVFormatContext *context = avformat_alloc_context();
    context->interrupt_callback = {interruptIo, &interrupt};
    interrupt.begin(cancel ? 30000000 : 250000);
    std::thread canceller;
    if (cancel) {
        canceller = std::thread([&] {
            auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
            while (!server.accepted && std::chrono::steady_clock::now() < deadline) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            interrupt.cancel();
        });
    }
    auto started = std::chrono::steady_clock::now();
    std::string url = "rtsp://127.0.0.1:" + std::to_string(server.port) + "/live";
    int result = avformat_open_input(&context, url.c_str(), nullptr, nullptr);
    if (canceller.joinable()) canceller.join();
    interrupt.end();
    avformat_close_input(&context);
    require(server.accepted && result < 0, "stalled RTSP must be interrupted after connect");
    require(elapsedMs(started) < 2000, "RTSP interrupt took over two seconds");
    require(cancel ? interrupt.isCancelled() : interrupt.hasTimedOut(), "wrong interrupt cause");
    std::cout << (cancel ? "RTSP cancel" : "RTSP deadline") << ": "
              << elapsedMs(started) << " ms\n";
}

/** 已有媒体包成功读出后断流，验证 av_read_frame 也能退出。 */
static void testRead(const std::vector<char> &payload, bool cancel) {
    StalledServer server(payload);
    IoInterrupt interrupt;
    AVFormatContext *context = avformat_alloc_context();
    context->interrupt_callback = {interruptIo, &interrupt};
    std::string url = "http://127.0.0.1:" + std::to_string(server.port) + "/live.ts";
    interrupt.begin(2000000);
    int opened = avformat_open_input(&context, url.c_str(), av_find_input_format("mpegts"), nullptr);
    interrupt.end();
    require(opened == 0, "HTTP TS open failed");
    AVPacket *packet = av_packet_alloc();
    int packets = 0;
    std::thread canceller;
    if (cancel) {
        canceller = std::thread([&] {
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
            interrupt.cancel();
        });
    }
    auto started = std::chrono::steady_clock::now();
    int result;
    do {
        interrupt.begin(cancel ? 30000000 : 250000);
        result = av_read_frame(context, packet);
        interrupt.end();
        if (result >= 0) ++packets;
        av_packet_unref(packet);
    } while (result >= 0 && !interrupt.hasTimedOut() && !interrupt.isCancelled());
    if (canceller.joinable()) canceller.join();
    av_packet_free(&packet);
    avformat_close_input(&context);
    require(packets > 0, "must read media before testing a stalled read");
    require(cancel ? interrupt.isCancelled() : interrupt.hasTimedOut(), "read was not interrupted");
    require(elapsedMs(started) < 2000, "read interrupt took over two seconds");
    std::cout << (cancel ? "Read cancel" : "Read deadline") << ": " << packets
              << " packets, " << elapsedMs(started) << " ms\n";
}

/** 取消不能被 begin 覆盖；显式重置后同一个状态对象可重新打开本地文件。 */
static void testReset(const char *path) {
    IoInterrupt interrupt;
    interrupt.cancel();
    interrupt.begin(30000000);
    require(interrupt.shouldInterrupt(), "begin erased cancellation");
    interrupt.reset();
    AVFormatContext *context = avformat_alloc_context();
    context->interrupt_callback = {interruptIo, &interrupt};
    require(avformat_open_input(&context, path, nullptr, nullptr) == 0, "reset did not allow reopen");
    require(avformat_find_stream_info(context, nullptr) >= 0, "reopened media was not readable");
    avformat_close_input(&context);
    std::cout << "Cancel-before-begin and reset reuse: passed\n";
}

/** 执行真实 FFmpeg I/O 测试；Android 生命周期另由仪器测试覆盖。 */
int main(int argc, char **argv) {
    try {
        require(argc == 3, "usage: test fixture.ts fixture.mp4");
        av_log_set_level(AV_LOG_ERROR);
        avformat_network_init();
        std::ifstream input(argv[1], std::ios::binary);
        std::vector<char> payload((std::istreambuf_iterator<char>(input)), {});
        require(!payload.empty(), "missing TS fixture");
        testOpen(true);
        testOpen(false);
        testRead(payload, true);
        testRead(payload, false);
        testReset(argv[2]);
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
