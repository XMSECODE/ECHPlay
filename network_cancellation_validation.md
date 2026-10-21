# 网络取消与生命周期回收：第 2 步实现及验证

日期：2026-10-21。基于 `80dcc25` 开始实现。

## 本轮目标

解决首次准备、重连准备、播放读取时，网络阻塞导致 stop/reset/release 无法及时进入的问题。此次交付是网络取消机制与锁调整，不代表 RTSP TCP/UDP 兼容性矩阵、弱网恢复和全部版本目标已经验收。

## 实现

1. 创建 AVFormatContext 后、打开地址前设置 `interrupt_callback`，覆盖打开输入、流信息探测和读取。回调只读原子状态，不等待 Java 对象锁。
2. 打开阶段使用 `timeout/stimeout`，探测和每次读取使用 `rw_timeout` 作为单调时钟期限。FFmpeg 自身的协议选项保留。主动取消和达到期限分别记录；中断后仍返回缓存包时，也保留超时原因。
3. 新一轮准备启动前才清除取消标记。开始某次读取不会清除取消，避免“刚点击停止，工作线程又覆盖停止请求”的竞态。
4. Java 的阻塞准备、停止、释放通过工作任务执行。等待结果使用 `wait/notifyAll`，等待期间释放播放器对象锁；常规 JNI 调用等任务结束后再访问媒体上下文。
5. stop/reset/release 先让旧播放代次失效、发出 Native 中断，再等待任务退出并回收资源。多个生命周期调用串行执行，不在旧任务仍访问对象时 delete Native 指针。
6. 重连等待可被唤醒，每个阻塞步骤返回后都检查重连代次；取消后的旧结果不能把状态改回 PREPARED/STARTED，也不能继续重试。
7. Native 信息、错误、视频尺寸通知转到主线程并检查代次，防止业务在解码线程回调中停止并等待自身。AudioTrack 使用可取消的非阻塞写入循环。
8. MainActivity 和 ECHPlayerView 改用 `prepareAsync()`，准备成功后再启动播放；连接期间跳过同步统计查询并禁用暂停/截图等需要准备完成的操作，保留停止入口。自动回归截图和录制延迟从播放启动后开始计时，旧实例的延迟动作不影响新实例。

## API 与调用约定

- `prepare()` 仍同步等待结果，可由其他线程调用 stop/reset/release 取消；取消返回 `prepare cancelled`，不作为一次播放失败触发自动重连。
- UI 使用 `prepareAsync()`。同步 `prepare()` 和需要等待 Native 的同步查询仍可能阻塞调用线程，释放对象锁不等于 API 异步返回。
- stop/reset/release 等待任务退出后返回。不同协议、DNS、设备驱动和业务回调的耗时仍需设备验证，不承诺所有场景的硬性耗时上限。
- Native 的 info/error/video-size 通知现在经主线程分发；同步 Java API 自身触发的回调仍在调用线程，异步准备完成回调仍由准备线程发出。业务 UI 应统一切回主线程。
- MediaCodec 选择回调保持同步，只用于返回是否接受解码器；不能在该回调里同步 stop/reset/release。此类调用会被拒绝并回退软解，避免解码线程等待自身。
- 关闭正在进行的自动重连会取消当前尝试并进入 STOPPED；后续可显式 prepare 或更换数据源。

## 已执行验证

### 宿主机真实 FFmpeg I/O

```bash
bash android/tools/test_io_interrupt.sh
```

测试编译时使用项目实际的 `IoInterrupt.h`，链接宿主机 FFmpeg，环回服务仅监听 `127.0.0.1`。TS 样本临时生成，结束后清理。编译使用 `-Wall -Wextra -Werror`。

本轮结果：

| 场景 | 结果 |
| --- | --- |
| RTSP 已连接、服务端不回应，主动取消 | 通过，约 105 ms |
| RTSP 已连接、服务端不回应，达到期限 | 通过，约 302 ms |
| HTTP TS 成功读出媒体包后断流，主动取消 | 通过，39 个包，约 304 ms |
| HTTP TS 成功读出媒体包后断流，达到期限 | 通过，39 个包，约 326 ms |
| 取消请求先于 I/O 开始 | 通过，begin 不覆盖取消 |
| 显式重置后再次打开并解析本地 MP4 | 通过 |

这些耗时只代表此次本机测试。宿主机 libavformat 为 `62.3.100`，Android 项目头文件为 `62.12.101`；宿主机结果不替代打包的 Android so、JNI、音频输出和设备渲染验收。

### Android 构建

```bash
cd android
./gradlew :echplayer:assembleDebug :echplayer:assembleRelease \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

最终 AAR、Demo 和测试 APK 构建均通过，结果为 `BUILD SUCCESSFUL`。脚本通过 `bash -n` 检查，代码差异通过 `git diff --check`。

## 已添加、待设备执行的测试

`NetworkCancellationInstrumentedTest` 使用真实 ECHPlayer/JNI 和环回 RTSP 服务，网络超时设为 30 秒，取消验收阈值为 2 秒：

1. stop 取消异步准备。
2. release 取消异步准备并支持重复释放。
3. stop 取消另一个线程的同步 prepare。
4. reset 取消后重新设置本地文件并成功 prepare。
5. stop 取消正在准备的自动重连。
6. release 取消正在准备的自动重连。
7. stop 唤醒重连间隔等待。
8. stop 与 release 并发时安全回收。
9. prepareAsync 后立即 stop，连续执行 20 次。
10. 禁用自动重连取消当前尝试。

```bash
cd android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.abcplaydemo.NetworkCancellationInstrumentedTest,com.example.abcplaydemo.DataSourceInstrumentedTest
```

当前 `adb devices -l` 无设备，因此新增 10 项和上一轮 8 项仪器测试均没有本轮设备通过记录。

后续补充（2026-10-21）：错误分类与重连开发时接入 V2359A（Android 16），本文件所列 10 项网络取消测试全部通过。重连用例的首次失败改为明确的 RTSP 503，避免将无协议响应关闭误判为暂时性错误。数据源 UI 回归仍存在待定位阻塞，最终代码设备复测被安装权限拒绝，完整记录见 `network_reconnect_validation.md`。

## 后续验收

在 arm64 设备执行以上测试，并补充播放读取中、暂停音频输出后、截图/录制并行时的停止与释放；记录 Demo/PlayerView 点击停止的响应时间、线程和 fd 是否回收。然后推进第 3 步的错误分类与重连状态整理、第 4 步的 TCP/UDP 服务端断流恢复矩阵。
