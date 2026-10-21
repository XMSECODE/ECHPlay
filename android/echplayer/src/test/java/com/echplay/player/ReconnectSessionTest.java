package com.echplay.player;

import org.junit.Test;

import static org.junit.Assert.*;

/** 验证生产重连状态机，无需 Android 设备或加载 Native 库。 */
public class ReconnectSessionTest {
    /** 仅允许明确的暂时性网络故障重试，未知/鉴权/媒体错误均终止。 */
    @Test public void onlyTransientErrorsRetry() {
        for (int code : new int[]{1005, 1011, 1012}) assertTrue(ReconnectSession.isRetryable(code));
        for (int code : new int[]{0, 1001, 1002, 1003, 1004, 1006, 1007, 1008,
                1009, 1010, 1013, 1014, 1015, 1016, 1017, 1999}) {
            assertFalse("must not retry " + code, ReconnectSession.isRetryable(code));
        }
    }

    /** 三次预算精确耗尽，终态不能再次开始。 */
    @Test public void retryBudgetIsExact() {
        ReconnectSession session = automatic(3);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertTrue(session.beginAttempt());
            assertEquals(attempt, session.attempts);
            session.failAttempt(ECHPlayer.ERROR_NETWORK_TIMEOUT, "timeout " + attempt);
        }
        assertEquals(ECHPlayer.ReconnectState.FAILED, session.state);
        assertFalse(session.beginAttempt());
        assertEquals("timeout 3", session.reason);
    }

    /** 本轮鉴权失败立即终止，并覆盖最初的网络超时原因。 */
    @Test public void permanentFailureReplacesOriginalCause() {
        ReconnectSession session = automatic(5);
        assertTrue(session.beginAttempt());
        session.failAttempt(ECHPlayer.ERROR_RTSP_AUTH_FAILED, "401");
        assertEquals(ECHPlayer.ReconnectState.FAILED, session.state);
        assertEquals(ECHPlayer.ERROR_RTSP_AUTH_FAILED, session.errorCode);
        assertEquals("401", session.reason);
        assertEquals(1, session.attempts);
    }

    /** 等待中取消不计为一次连接，也不会允许后续重试。 */
    @Test public void waitingCancellationDoesNotConsumeBudget() {
        ReconnectSession session = automatic(3);
        session.cancel();
        assertEquals(0, session.attempts);
        assertEquals(ECHPlayer.ReconnectState.CANCELLED, session.state);
        assertFalse(session.beginAttempt());
    }

    /** 连接中取消后，迟到的成功和失败都不能覆盖取消终态。 */
    @Test public void cancelledAttemptIgnoresLateResult() {
        ReconnectSession session = automatic(3);
        assertTrue(session.beginAttempt());
        session.cancel();
        session.failAttempt(ECHPlayer.ERROR_RTSP_AUTH_FAILED, "late error");
        session.succeed();
        assertEquals(ECHPlayer.ReconnectState.CANCELLED, session.state);
    }

    /** 手动重连在自动次数为零且间隔很长时仍立即尝试一次。 */
    @Test public void manualRetryHasIndependentImmediateBudget() {
        ReconnectSession session = new ReconnectSession(true, 0, 60000, 1005, "manual");
        assertEquals(1, session.maxAttempts);
        assertEquals(0, session.delayMs);
        assertTrue(session.beginAttempt());
        session.failAttempt(ECHPlayer.ERROR_NETWORK_TIMEOUT, "timeout");
        assertEquals(ECHPlayer.ReconnectState.FAILED, session.state);
        assertFalse(session.beginAttempt());
    }

    /** 成功后下一次断线获得全新预算，不继承上一轮尝试数。 */
    @Test public void nextOutageReceivesFreshBudget() {
        ReconnectSession first = automatic(2);
        first.beginAttempt();
        first.failAttempt(ECHPlayer.ERROR_CONNECTION_LOST, "lost");
        first.beginAttempt();
        first.succeed();
        assertEquals(ECHPlayer.ReconnectState.SUCCEEDED, first.state);
        assertFalse(first.beginAttempt());
        ReconnectSession second = automatic(2);
        assertEquals(0, second.attempts);
        assertTrue(second.beginAttempt());
        second.failAttempt(ECHPlayer.ERROR_NETWORK_TIMEOUT, "timeout");
        assertTrue(second.beginAttempt());
    }

    /** 同一次尝试不能在连接中重复开始；零预算不能启动。 */
    @Test public void duplicateAndZeroBudgetAttemptsAreRejected() {
        assertFalse(automatic(0).beginAttempt());
        ReconnectSession session = automatic(1);
        assertTrue(session.beginAttempt());
        assertFalse(session.beginAttempt());
        assertEquals(1, session.attempts);
    }

    /** 创建带固定间隔的自动重连样本。 */
    private ReconnectSession automatic(int maxAttempts) {
        return new ReconnectSession(false, maxAttempts, 2000,
                ECHPlayer.ERROR_NETWORK_TIMEOUT, "initial timeout");
    }
}
