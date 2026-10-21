package com.example.abcplaydemo;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.echplay.player.ECHPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 使用本机无响应 RTSP 服务验证真实 JNI 取消，不依赖公网或摄像头。 */
@RunWith(AndroidJUnit4.class)
public class NetworkCancellationInstrumentedTest {
    /** 正常超时故意设置很长，确保测试测到主动取消而非自然超时。 */
    private static final long NETWORK_TIMEOUT_US = 30_000_000L;
    /** 本机中断和回收的验收上限，保留设备调度余量。 */
    private static final long CANCEL_LIMIT_MS = 2000L;
    /** 当前用例的播放器。 */
    private ECHPlayer player;
    /** 当前用例的本机 RTSP 服务。 */
    private StalledRtspServer server;
    /** 执行同步准备与生命周期并发操作。 */
    private ExecutorService callers;
    /** 成功或错误终态通知次数，主动取消不得发布这些通知。 */
    private final AtomicInteger terminalEvents = new AtomicInteger();

    /** 每个用例使用独立播放器和线程池。 */
    @Before
    public void setUp() {
        callers = Executors.newCachedThreadPool();
        player = new ECHPlayer();
        player.setDecodeMode(ECHPlayer.DECODE_MODE_SOFTWARE);
        player.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_TIMEOUT,
                NETWORK_TIMEOUT_US);
        player.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_RW_TIMEOUT,
                NETWORK_TIMEOUT_US);
        player.setOnPreparedListener(ignored -> terminalEvents.incrementAndGet());
        player.setOnErrorListener((ignored, code, message) -> {
            terminalEvents.incrementAndGet();
            return true;
        });
    }

    /** 先关闭测试端连接作为失败兜底，再释放播放器，避免失败用例挂住测试进程。 */
    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (player != null) {
            callers.submit(player::release).get(5, TimeUnit.SECONDS);
        }
        callers.shutdownNow();
        assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** 连接已建立但没有 RTSP 响应时，stop 必须提前结束异步准备。 */
    @Test
    public void stopCancelsAsyncPrepare() throws Exception {
        startStalledPrepare(false);
        assertPromptControl(player::stop);
        assertEquals(ECHPlayer.State.STOPPED, player.getState());
        assertNoTerminalEvents();
    }

    /** release 取消网络操作后才能销毁 Native 对象，并允许重复调用。 */
    @Test
    public void releaseCancelsAsyncPrepare() throws Exception {
        startStalledPrepare(false);
        assertPromptControl(player::release);
        player.release();
        assertEquals(ECHPlayer.State.RELEASED, player.getState());
        assertNoTerminalEvents();
    }

    /** 同步 prepare 的调用线程也能被另一线程取消，返回取消而不是成功或错误。 */
    @Test
    public void stopCancelsSynchronousPrepare() throws Exception {
        configureServer(false);
        Future<String> prepare = callers.submit(() -> player.prepare());
        server.awaitConnection(1);
        assertPromptControl(player::stop);
        assertEquals("prepare cancelled", prepare.get(CANCEL_LIMIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(ECHPlayer.State.STOPPED, player.getState());
        assertNoTerminalEvents();
    }

    /** 旧 prepare 退出后 reset 重建实例，取消标记不能污染下一次本地准备。 */
    @Test
    public void resetAllowsNextLocalPrepare() throws Exception {
        startStalledPrepare(false);
        assertPromptControl(player::reset);
        assertEquals(ECHPlayer.State.IDLE, player.getState());
        assertNoTerminalEvents();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File media = File.createTempFile("cancel-reuse", ".mp4", context.getCacheDir());
        try {
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("data_source_sample.mp4");
                 FileOutputStream output = new FileOutputStream(media)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            player.setDecodeMode(ECHPlayer.DECODE_MODE_SOFTWARE);
            player.setDataSource(media.getAbsolutePath());
            assertTrue(player.prepare().startsWith("prepare success"));
            assertEquals(160, player.getVideoWidth());
        } finally {
            player.release();
            assertTrue(media.delete());
        }
    }

    /** 第一次连接被断开、第二次连接不回应时，stop 可取消自动重连。 */
    @Test
    public void stopCancelsReconnectPrepare() throws Exception {
        startStalledPrepare(true);
        assertPromptControl(player::stop);
        assertEquals(ECHPlayer.State.STOPPED, player.getState());
        assertNoTerminalEvents();
        assertFalse(server.awaitThirdConnection());
    }

    /** 自动重连进入阻塞准备后，release 不再等待完整网络超时。 */
    @Test
    public void releaseCancelsReconnectPrepare() throws Exception {
        startStalledPrepare(true);
        assertPromptControl(player::release);
        assertEquals(ECHPlayer.State.RELEASED, player.getState());
        assertNoTerminalEvents();
    }

    /** 取消重连等待会唤醒线程，不能在停止后再发起一次连接。 */
    @Test
    public void stopCancelsReconnectDelay() throws Exception {
        configureServer(true);
        CountDownLatch retryWaiting = new CountDownLatch(1);
        player.setOnInfoListener((ignored, code, message) -> {
            if (code == ECHPlayer.INFO_RECONNECTING) {
                retryWaiting.countDown();
            }
            return true;
        });
        player.setReconnectEnabled(true);
        player.setReconnectConfig(3, 30_000L);
        player.prepareAsync();
        assertTrue(retryWaiting.await(5, TimeUnit.SECONDS));
        assertPromptControl(player::stop);
        assertNoTerminalEvents();
        assertEquals(1, server.connections.get());
    }

    /** 两个调用方同时停止和释放时，Native 对象只会在任务退出后销毁一次。 */
    @Test
    public void stopAndReleaseCanRace() throws Exception {
        startStalledPrepare(false);
        Future<?> stop = callers.submit(player::stop);
        Future<?> release = callers.submit(player::release);
        stop.get(CANCEL_LIMIT_MS, TimeUnit.MILLISECONDS);
        release.get(CANCEL_LIMIT_MS, TimeUnit.MILLISECONDS);
        assertEquals(ECHPlayer.State.RELEASED, player.getState());
        assertNoTerminalEvents();
    }

    /** 异步任务尚未进入 Native 时取消，旧任务不得在停止后重新打开连接。 */
    @Test
    public void stopImmediatelyAfterPrepareAsync() throws Exception {
        configureServer(false);
        for (int index = 0; index < 20; index++) {
            player.prepareAsync();
            assertPromptControl(player::stop);
            assertEquals(ECHPlayer.State.STOPPED, player.getState());
        }
        assertNoTerminalEvents();
    }

    /** 关闭自动重连也应取消正在等待响应的重试，而非等完整网络超时。 */
    @Test
    public void disablingReconnectCancelsCurrentAttempt() throws Exception {
        startStalledPrepare(true);
        assertPromptControl(() -> player.setReconnectEnabled(false));
        assertPromptControl(player::stop);
        assertEquals(ECHPlayer.State.STOPPED, player.getState());
        assertNoTerminalEvents();
    }

    /** 在已连接的套接字上等待取消；重连用例先主动关闭第一次连接。 */
    private void startStalledPrepare(boolean reconnect) throws Exception {
        configureServer(reconnect);
        player.setReconnectEnabled(reconnect);
        player.setReconnectConfig(3, 0L);
        player.prepareAsync();
        server.awaitConnection(reconnect ? 2 : 1);
    }

    /** 给播放器配置仅本机可访问的测试服务。 */
    private void configureServer(boolean rejectFirst) throws IOException {
        server = new StalledRtspServer(rejectFirst);
        player.setDataSource("rtsp://127.0.0.1:" + server.listener.getLocalPort() + "/live");
    }

    /** 用独立线程和硬性等待上限测量生命周期 API，避免死锁时阻塞测试线程。 */
    private void assertPromptControl(Runnable control) throws Exception {
        long started = SystemClock.elapsedRealtime();
        callers.submit(control).get(CANCEL_LIMIT_MS, TimeUnit.MILLISECONDS);
        assertTrue(SystemClock.elapsedRealtime() - started < CANCEL_LIMIT_MS);
    }

    /** 清空主线程已排队的事件，确认旧准备结果没有覆盖取消后的状态。 */
    private void assertNoTerminalEvents() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, terminalEvents.get());
    }

    /** 接受 TCP 连接但不返回 RTSP 响应，确定性制造阻塞 I/O。 */
    private static final class StalledRtspServer implements AutoCloseable {
        /** 绑定环回地址，不向局域网暴露测试服务。 */
        final ServerSocket listener;
        /** 记录连接次数，用于验证取消后没有额外重连。 */
        final AtomicInteger connections = new AtomicInteger();
        /** 每次连接的等待信号。 */
        final CountDownLatch[] accepted = {
                new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        /** 测试结束时统一关闭所有未回应的连接。 */
        final List<Socket> clients = new ArrayList<>();
        /** 服务线程，只负责接受连接。 */
        final Thread thread;

        /** 根据用例要求断开第一条连接，后续连接保持无响应。 */
        StalledRtspServer(boolean rejectFirst) throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try {
                    while (!listener.isClosed()) {
                        Socket client = listener.accept();
                        synchronized (clients) {
                            clients.add(client);
                        }
                        int index = connections.getAndIncrement();
                        if (rejectFirst && index == 0) {
                            client.close();
                        }
                        if (index < accepted.length) {
                            accepted[index].countDown();
                        }
                    }
                } catch (IOException ignored) {
                    // close 会结束 accept，连接失败则由等待信号的断言报告。
                }
            }, "StalledRtspServer");
            thread.start();
        }

        /** 等待指定连接已建立，保证取消发生在网络阶段。 */
        void awaitConnection(int number) throws InterruptedException {
            assertTrue("connection " + number + " was not accepted",
                    accepted[number - 1].await(5, TimeUnit.SECONDS));
        }

        /** 停止后留出短暂观察窗口，排除立即启动下一次重连。 */
        boolean awaitThirdConnection() throws InterruptedException {
            return accepted[2].await(300, TimeUnit.MILLISECONDS);
        }

        /** 关闭监听和客户端，再等待服务线程退出。 */
        @Override
        public void close() throws Exception {
            listener.close();
            thread.join(2000L);
            synchronized (clients) {
                for (Socket client : clients) {
                    client.close();
                }
            }
            assertFalse(thread.isAlive());
        }
    }
}
