#ifndef ECHPLAY_IO_INTERRUPT_H
#define ECHPLAY_IO_INTERRUPT_H

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <limits>

/** 网络 I/O 的取消与截止时间；回调只读原子变量，不等待播放器锁。 */
class IoInterrupt {
public:
    /** 开始新一轮准备，必须在旧任务退出后、启动新任务前调用。 */
    void reset() {
        cancelled = false;
        deadlineUs = 0;
        timedOut = false;
    }

    /** 请求取消；后续开始 I/O 不能清除这个请求。 */
    void cancel() { cancelled = true; }

    /** 设置本次阻塞操作的期限，非正值表示不限制时间。 */
    void begin(int64_t timeoutUs) {
        timedOut = false;
        int64_t now = nowUs();
        deadlineUs = timeoutUs <= 0 ? 0 : now +
                std::min(timeoutUs, std::numeric_limits<int64_t>::max() - now);
    }

    /** 结束本次操作，不让旧期限影响下一次读取或关闭。 */
    void end() { deadlineUs = 0; }

    /** FFmpeg 周期性调用；取消优先于超时。 */
    bool shouldInterrupt() {
        if (cancelled.load()) {
            return true;
        }
        int64_t deadline = deadlineUs.load();
        if (deadline > 0 && nowUs() >= deadline) {
            timedOut = true;
            return true;
        }
        return false;
    }

    /** 是否由调用方主动取消。 */
    bool isCancelled() const { return cancelled.load(); }

    /** 是否达到本次 I/O 的期限。 */
    bool hasTimedOut() const { return timedOut.load(); }

private:
    /** 使用单调时钟，避免系统时间调整改变超时行为。 */
    static int64_t nowUs() {
        return std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    /** 持续到下一次显式 reset 的取消请求。 */
    std::atomic<bool> cancelled{false};
    /** 当前操作的单调时钟截止时间，0 表示无期限。 */
    std::atomic<int64_t> deadlineUs{0};
    /** 记录中断原因，供错误码映射使用。 */
    std::atomic<bool> timedOut{false};
};

#endif
