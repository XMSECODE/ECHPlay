# 错误分类与重连状态：实现和验证

日期：2026-10-21。接续 `a583b43` 的网络取消与资源回收工作，本轮完成稳定性开发第 3 步。

## 本轮目标与实现

1. Native 按 FFmpeg 数值错误分类，通过 JNI 独立传递准备错误码；保留 `prepare()` 原有可读结果。URL 中包含 `timeout`、`unauthorized` 不再影响分类。
2. 只有网络超时、连接失败、连接中断触发 RTSP 自动重连。鉴权、权限、资源不存在、无效媒体、未知错误立即结束。
3. 重试预算按每轮断线独立计算；`getReconnectCount()` 仍累计当前数据源的全部重试次数。等待期间取消不增加次数。
4. `reconnectImmediately()` 使用独立的一次尝试和零等待，即使自动重连关闭、次数为零也生效；已有任务时复用，不另建并发连接。
5. 每次失败都重新分类，重连中出现 401 立即停止，最终错误使用最新原因；终态回调中 stop/reset/release 后不继续发布旧事件。
6. Demo 和 PlayerView 展示等待/连接阶段及本轮次数，并区分鉴权、权限、地址和网络问题。继续使用 `findViewById`。

## 错误码与重试规则

原有 1001-1010、1999 数值保持兼容。1006 保留 `ERROR_RTSP_AUTH_FAILED` 名称，也用于 HTTP 401。

| 错误码 | 常量 | 含义 | RTSP 自动重试 |
| --- | --- | --- | --- |
| 1005 | `ERROR_NETWORK_TIMEOUT` | 打开、探测或读取超时 | 是 |
| 1006 | `ERROR_RTSP_AUTH_FAILED` | 401 鉴权失败 | 否 |
| 1011 | `ERROR_CONNECTION_FAILED` | 连接拒绝、网络不可达、5xx 暂时不可用 | 是 |
| 1012 | `ERROR_CONNECTION_LOST` | 网络连接重置、中断、网络 I/O 错误；RTSP 读取 EOF | 是 |
| 1013 | `ERROR_SOURCE_NOT_FOUND` | 文件不存在或 404 | 否 |
| 1014 | `ERROR_ACCESS_DENIED` | 权限不足或 403 | 否 |
| 1015 | `ERROR_INVALID_SOURCE` | 地址、请求或媒体数据无效 | 否 |
| 1016 | `ERROR_UNSUPPORTED_PROTOCOL` | 未支持的协议 | 否 |
| 1017 | `ERROR_OPERATION_CANCELLED` | 主动取消的内部诊断码 | 否，且不发送 OnError |
| 1001/1002/1999 | 原有未细分错误 | 无法可靠确认原因 | 否 |

错误分类适用于 HTTP/RTSP；自动重连范围仍为 RTSP。FFmpeg 对 5xx 使用统一错误，暂不能进一步区分具体 5xx 状态。真实测试确认：RTSP 连接后不返回任何协议响应就关闭，FFmpeg 可能返回 `AVERROR_INVALIDDATA`，因此保守归为 1015，不猜测成可重试断线。文件和 HTTP 点播正常 EOF 继续按播放完成处理。

## 状态和 API

`getReconnectState()` 返回独立状态；`getReconnectAttemptCount()` 返回本轮已开始的尝试次数；`getReconnectMaxAttempts()` 返回本轮预算。三个查询均不等待阻塞网络 I/O。

| 状态 | 含义 |
| --- | --- |
| IDLE | 当前数据源尚无重连记录 |
| WAITING | 等待间隔，可随时停止 |
| CONNECTING | 正在尝试连接、准备及启动 |
| SUCCEEDED | 已恢复播放 |
| FAILED | 次数耗尽或遇到不可重试错误 |
| CANCELLED | 活跃重连被用户取消 |

典型转换：`IDLE -> WAITING -> CONNECTING -> SUCCEEDED`。暂时失败且有预算时回到 WAITING；否则转 FAILED。stop/release/关闭重连取消活跃任务；reset 或更换数据源清空为 IDLE。成功/失败历史保留到下一轮或 reset/换源。

```java
// 自动重连：每轮最多三次，每次尝试前等待两秒。
player.setReconnectEnabled(true);
player.setReconnectConfig(3, 2000L);

// 手动重连独立于自动开关；当前正在重连时复用该任务。
boolean accepted = player.reconnectImmediately();

// 本轮次数与当前数据源累计次数含义不同。
int attempts = player.getReconnectAttemptCount();
int totalAttempts = player.getReconnectCount();
ECHPlayer.ReconnectState phase = player.getReconnectState();
```

自动重连配置修改从下一轮生效。普通 prepare 正在执行时，手动重连返回 false；非 RTSP 返回 false；已释放对象仍抛出 `IllegalStateException`。已有重连的间隔不会因重复手动请求被缩短。

`INFO_RECONNECTING` 在 WAITING、CONNECTING 通知阶段变化；`INFO_RECONNECTED` 表示恢复启动；`INFO_RECONNECT_FAILED` 后发布一次最终 OnError。中间失败通过下一次阶段消息携带原因。回调线程沿用上一轮约定，应用 UI 操作仍需切到主线程。

## 可复现验证

### 单元与原生测试

```bash
cd android
./gradlew :echplayer:testDebugUnitTest --console=plain
bash tools/test_io_interrupt.sh
```

8 项 JVM 测试通过：错误白名单、精确预算、最新失败原因、等待取消不计数、取消屏蔽迟到结果、手动零间隔一次尝试、新断线独立预算、重复/零预算尝试拒绝。

宿主机真实 FFmpeg 测试通过：RTSP 401/403/404/400/503/无响应即关闭；打开/探测超时分类；本地 I/O 不归为网络错误；不把 FFmpeg EXIT 无条件归为主动取消。原有 RTSP 打开及 HTTP 读取取消、期限、重用测试继续通过。宿主机 FFmpeg 与 Android 打包库版本不同，宿主机结果不替代设备验证。

### Android 构建

```bash
cd android
./gradlew :echplayer:assembleDebug :echplayer:assembleRelease \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Debug/Release AAR、Demo APK、仪器测试 APK 均编译通过。

### Android 真机

设备：vivo V2359A，Android 16，arm64。

```bash
cd android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.abcplaydemo.ReconnectInstrumentedTest,com.example.abcplaydemo.NetworkCancellationInstrumentedTest,com.example.abcplaydemo.DataSourceInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=20000
```

本轮新增 9 项真实 JNI 重连测试：401、403、404 不重试；503 后 401 立即结束并上报最新错误；精确重试上限；自动关闭时手动重连；等待回调中停止；失败回调中停止后屏蔽旧错误；reset 清空状态。上一轮 10 项网络取消测试同步执行。

首轮实际通过 22 项：新增重连 9 项、网络取消 10 项、数据源 3 项。数据源中的 `demoPlaysContentUri` 启动阶段长时间无进展，主动结束测试进程后，整体任务标记失败；不能把这次运行记为全部通过。其余 4 项没有本轮完成记录。

随后补充了重连期间屏蔽旧 Native 错误的防护，并尝试带 20 秒用例上限复跑。手机安装阶段返回 `INSTALL_FAILED_ABORTED: User rejected permissions`，最终代码未完成设备复测。需要允许测试 APK 安装后重跑上述命令，定位 Demo 页面启动阻塞并完成 27 项验收；不通过绕开设备安装权限来继续。

首轮与复跑命令输出分别保存在本机 `/tmp/echplay-reconnect-device.log`、`/tmp/echplay-reconnect-device-timeout.log`；首轮结果副本为 `/tmp/echplay-reconnect-initial-results.xml`。临时文件不随代码提交。

## 后续边界

本轮没有将整个 v2.1-v2.9 标为完成。下一步仍是使用可控真实媒体服务建立 RTSP TCP/UDP 的播放、服务端断流、恢复、多次重连和截图/录制并发矩阵，并补充 HTTP/HLS 测试源。恢复后的首帧、音频同步、硬解设备差异和长时间资源泄漏需要单独验收。
