package com.example.abcplaydemo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.echplay.player.ECHPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 使用真实 Android JNI 和环回 RTSP 协议验证错误分类、次数和取消事件。 */
@RunWith(AndroidJUnit4.class)
public class ReconnectInstrumentedTest {
    /** 每个用例独立的播放器。 */
    private ECHPlayer player;
    /** 当前用例的 RTSP 响应序列。 */
    private RtspErrorTestServer server;
    /** 等待唯一的最终错误回调。 */
    private final CountDownLatch terminal = new CountDownLatch(1);
    /** 最终错误次数，不包括中间重试失败。 */
    private final AtomicInteger errors = new AtomicInteger();
    /** 最后一次错误码。 */
    private final AtomicInteger lastError = new AtomicInteger();

    /** 使用较短网络期限，确保服务或实现故障不会无限阻塞用例。 */
    @Before public void setUp() {
        player = new ECHPlayer();
        player.setDecodeMode(ECHPlayer.DECODE_MODE_SOFTWARE);
        player.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_TIMEOUT, 2_000_000L);
        player.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_RW_TIMEOUT, 2_000_000L);
        player.setReconnectEnabled(true);
        player.setReconnectConfig(3, 0L);
        player.setOnErrorListener((ignored, code, message) -> {
            lastError.set(code);
            errors.incrementAndGet();
            terminal.countDown();
            return true;
        });
    }

    /** 回收真实 Native 对象及测试服务。 */
    @After public void tearDown() throws Exception {
        try {
            if (server != null) server.close();
        } finally {
            player.release();
        }
    }

    /** 401 不因开启自动重连而反复鉴权。 */
    @Test public void authenticationFailureDoesNotRetry() throws Exception {
        configure(401);
        player.prepareAsync();
        assertFinalError(ECHPlayer.ERROR_RTSP_AUTH_FAILED, 1, 0);
        assertEquals(ECHPlayer.ReconnectState.IDLE, player.getReconnectState());
    }

    /** 服务端禁止访问属于权限问题，不是暂时性网络故障。 */
    @Test public void forbiddenDoesNotRetry() throws Exception {
        configure(403);
        player.prepareAsync();
        assertFinalError(ECHPlayer.ERROR_ACCESS_DENIED, 1, 0);
    }

    /** URL 中的 timeout/unauthorized 不得把 404 误判为超时或鉴权。 */
    @Test public void misleadingUrlDoesNotChangeMissingSourceError() throws Exception {
        configure(404);
        player.prepareAsync();
        assertFinalError(ECHPlayer.ERROR_SOURCE_NOT_FOUND, 1, 0);
    }

    /** 重连出现 401 时立即终止，最终报告最新鉴权错误而非最初 503。 */
    @Test public void permanentErrorDuringRetryStopsImmediately() throws Exception {
        configure(503, 401);
        player.prepareAsync();
        assertFinalError(ECHPlayer.ERROR_RTSP_AUTH_FAILED, 2, 1);
        assertEquals(ECHPlayer.ReconnectState.FAILED, player.getReconnectState());
    }

    /** 最大次数指初次连接以外的重试次数，全部失败只报告一次最终错误。 */
    @Test public void retryLimitIsExact() throws Exception {
        configure(503);
        player.setReconnectConfig(2, 0L);
        player.prepareAsync();
        assertFinalError(ECHPlayer.ERROR_CONNECTION_FAILED, 3, 2);
        assertEquals(2, player.getReconnectAttemptCount());
    }

    /** 自动开关关闭且最大次数为零时，手动操作仍立即连接一次。 */
    @Test public void manualRetryIgnoresAutomaticConfig() throws Exception {
        configure(503);
        player.setReconnectEnabled(false);
        player.setReconnectConfig(0, 30000L);
        assertTrue(player.reconnectImmediately());
        assertFinalError(ECHPlayer.ERROR_CONNECTION_FAILED, 1, 1);
        assertEquals(1, player.getReconnectMaxAttempts());
    }

    /** 在等待回调中同步停止，不消耗重试次数且不再回调错误。 */
    @Test public void stopFromWaitingListenerCancelsWithoutAttempt() throws Exception {
        configure(503);
        CountDownLatch stopped = new CountDownLatch(1);
        player.setOnInfoListener((ignored, code, message) -> {
            if (code == ECHPlayer.INFO_RECONNECTING
                    && player.getReconnectState() == ECHPlayer.ReconnectState.WAITING) {
                player.stop();
                stopped.countDown();
            }
            return true;
        });
        player.prepareAsync();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertEquals(ECHPlayer.ReconnectState.CANCELLED, player.getReconnectState());
        assertEquals(0, player.getReconnectCount());
        assertEquals(1, server.connections.get());
        assertNoErrorAfterStop();
    }

    /** 最终失败 info 的监听器停止播放后，旧错误不能继续送达。 */
    @Test public void stopFromFailureListenerSuppressesStaleError() throws Exception {
        configure(503, 401);
        CountDownLatch stopped = new CountDownLatch(1);
        player.setOnInfoListener((ignored, code, message) -> {
            if (code == ECHPlayer.INFO_RECONNECT_FAILED) {
                player.stop();
                stopped.countDown();
            }
            return true;
        });
        player.prepareAsync();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertNoErrorAfterStop();
    }

    /** reset 清空上一轮重连统计和状态。 */
    @Test public void resetClearsReconnectState() throws Exception {
        configure(503, 401);
        player.prepareAsync();
        assertFinalError(ECHPlayer.ERROR_RTSP_AUTH_FAILED, 2, 1);
        player.reset();
        assertEquals(ECHPlayer.ReconnectState.IDLE, player.getReconnectState());
        assertEquals(0, player.getReconnectCount());
        assertEquals(0, player.getReconnectAttemptCount());
    }

    /** 启动本用例的本机服务并设置播放器地址。 */
    private void configure(int... statuses) throws Exception {
        server = new RtspErrorTestServer(statuses);
        player.setDataSource(server.url());
    }

    /** 核对唯一最终错误、连接数、实际重试次数及播放器终态。 */
    private void assertFinalError(int code, int connections, int retries) throws Exception {
        assertTrue("no terminal error", terminal.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(code, lastError.get());
        assertEquals(1, errors.get());
        assertEquals(connections, server.connections.get());
        assertEquals(retries, player.getReconnectCount());
        assertEquals(ECHPlayer.State.ERROR, player.getState());
    }

    /** 让后台线程获得运行机会，排除取消后又送达旧错误。 */
    private void assertNoErrorAfterStop() throws Exception {
        assertFalse(terminal.await(300, TimeUnit.MILLISECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, errors.get());
        assertEquals(ECHPlayer.State.STOPPED, player.getState());
    }
}
