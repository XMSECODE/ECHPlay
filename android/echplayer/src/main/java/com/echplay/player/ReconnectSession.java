package com.echplay.player;

/** 一轮重连的独立预算和状态；所有读写由 ECHPlayer 对象锁保护。 */
final class ReconnectSession {
    /** 本轮配置快照，手动重连固定一次。 */
    final int maxAttempts;
    /** 本轮等待间隔，手动重连固定为零。 */
    final long delayMs;
    /** 已真正开始的连接尝试次数，不包含等待。 */
    int attempts;
    /** 最近一次失败的稳定错误码。 */
    int errorCode;
    /** 最近一次失败的诊断信息。 */
    String reason;
    /** 当前重连阶段。 */
    ECHPlayer.ReconnectState state = ECHPlayer.ReconnectState.WAITING;

    /** 建立本轮预算，避免后续配置修改影响已经开始的重连。 */
    ReconnectSession(boolean manual, int maxAttempts, long delayMs, int errorCode, String reason) {
        this.maxAttempts = manual ? 1 : Math.max(0, maxAttempts);
        this.delayMs = manual ? 0 : Math.max(0L, delayMs);
        this.errorCode = errorCode;
        this.reason = reason == null ? "" : reason;
    }

    /** 仅明确的暂时性网络错误允许自动重试。 */
    static boolean isRetryable(int code) {
        return code == ECHPlayer.ERROR_NETWORK_TIMEOUT
                || code == ECHPlayer.ERROR_CONNECTION_FAILED
                || code == ECHPlayer.ERROR_CONNECTION_LOST;
    }

    /** 开始一次实际连接；等待取消不会消耗次数。 */
    boolean beginAttempt() {
        if (state != ECHPlayer.ReconnectState.WAITING || attempts >= maxAttempts) return false;
        attempts++;
        state = ECHPlayer.ReconnectState.CONNECTING;
        return true;
    }

    /** 保存最新失败；鉴权等永久性错误或预算耗尽时立即结束。 */
    void failAttempt(int code, String message) {
        if (state != ECHPlayer.ReconnectState.CONNECTING) return;
        errorCode = code;
        reason = message == null ? "" : message;
        state = isRetryable(code) && attempts < maxAttempts
                ? ECHPlayer.ReconnectState.WAITING : ECHPlayer.ReconnectState.FAILED;
    }

    /** 连接及播放启动成功后结束本轮。 */
    void succeed() {
        if (state == ECHPlayer.ReconnectState.CONNECTING) {
            state = ECHPlayer.ReconnectState.SUCCEEDED;
        }
    }

    /** 主动取消只结束仍在进行的重连，不产生最终错误。 */
    void cancel() {
        if (state == ECHPlayer.ReconnectState.WAITING
                || state == ECHPlayer.ReconnectState.CONNECTING) {
            state = ECHPlayer.ReconnectState.CANCELLED;
        }
    }
}
