# GitHub Release 说明模板

版本：`vX.Y`

发布时间：`YYYY-MM-DD`

## 一、版本定位

用 2 到 4 句话说明本版本解决什么问题，例如：

1. 本版本聚焦 SDK 发布形态。
2. 本版本新增 Maven Local 发布能力。
3. 本版本明确当前 ABI 支持边界，避免接入方误用。

## 二、新增能力

1. 新增能力 1。
2. 新增能力 2。
3. 新增能力 3。

## 三、兼容性

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| Android minSdk | 28 | 与播放器库 `build.gradle` 保持一致 |
| ABI | arm64-v8a | 其他 ABI 见 `abi_release_status.md` |
| 本地 MP4 | 待填写 | 发版前填写真实验证结果 |
| HTTP / HLS / RTSP | 待填写 | 发版前填写真实验证结果 |

## 四、接入方式

Maven Local：

```gradle
repositories {
    mavenLocal()
    google()
    mavenCentral()
}

dependencies {
    implementation 'com.echplay:echplayer:X.Y.Z'
}
```

AAR：

```gradle
dependencies {
    implementation files('libs/echplayer-release.aar')
}
```

## 五、验证记录

1. `cd android && ./tools/smoke_check.sh`：待填写。
2. `cd android && ./tools/publish_maven_local.sh`：待填写。
3. 真机或模拟器启动 Demo：待填写。
4. 关键协议播放结果：待填写。

## 六、已知问题

1. 当前只声明 `arm64-v8a` 已支持。
2. `armeabi-v7a` 和 `x86_64` 需要补齐 FFmpeg so 后才能进入 AAR。
3. RTSP 能力依赖设备、网络、防火墙和服务端传输方式，发版时需要记录真实源地址和结果。

## 七、License 说明

1. ECHPlay 自有代码按仓库 License 执行。
2. 当前 AAR 包含 FFmpeg 动态库，需遵守 FFmpeg 对应 LGPL/GPL 配置要求。
3. `ijkplayer/` 源码只作为功能参考，不直接复制源码进入 ECHPlay 主线。
