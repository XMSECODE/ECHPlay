package com.example.abcplaydemo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** 顺序返回指定 RTSP 错误，用于验证真实 JNI 的错误码和重连次数。 */
final class RtspErrorTestServer implements AutoCloseable {
    /** 环回监听端口，不向外部网络暴露服务。 */
    private final ServerSocket listener;
    /** 已接受的连接数量。 */
    final AtomicInteger connections = new AtomicInteger();
    /** 当前正在读取请求的连接，关闭测试时用于打断读取。 */
    private volatile Socket client;
    /** 接收与响应请求的线程。 */
    private final Thread thread;
    /** 非预期服务错误，测试结束时报告，避免把服务失败误认为播放器行为。 */
    private volatile IOException failure;

    /** 每个连接选择一个状态码，超出序列后持续使用最后一个状态。 */
    RtspErrorTestServer(int... statuses) throws IOException {
        listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    try (Socket connected = listener.accept()) {
                        client = connected;
                        int index = connections.getAndIncrement();
                        respond(connected, statuses[Math.min(index, statuses.length - 1)]);
                    }
                }
            } catch (IOException error) {
                if (!listener.isClosed()) failure = error;
            }
        }, "RtspErrorTestServer");
        thread.start();
    }

    /** 带误导性关键词的测试地址，确保播放器不靠 URL 猜错误类型。 */
    String url() {
        return "rtsp://127.0.0.1:" + listener.getLocalPort() + "/timeout/unauthorized";
    }

    /** OPTIONS 正常响应，DESCRIBE 返回指定错误，并回显客户端序号。 */
    static void respond(Socket socket, int status) throws IOException {
        socket.setSoTimeout(3000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
        while (true) {
            String request = reader.readLine();
            if (request == null) return;
            String sequence = "1";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("CSeq:")) sequence = line.substring(5).trim();
            }
            boolean options = request.startsWith("OPTIONS ");
            String response = "RTSP/1.0 " + (options ? 200 : status) + " Test\r\nCSeq: "
                    + sequence + "\r\nContent-Length: 0\r\n\r\n";
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            if (!options) return;
        }
    }

    /** 先打断 I/O 再回收线程，失败用例也不会遗留服务。 */
    @Override public void close() throws Exception {
        listener.close();
        Socket connected = client;
        if (connected != null) connected.close();
        thread.join(4000L);
        if (thread.isAlive()) throw new AssertionError("RTSP server did not stop");
        if (failure != null) throw failure;
    }
}
