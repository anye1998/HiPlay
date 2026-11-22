# 接收端界面：三个页面、显示模式与自适应

这个 APK 只有一个 Activity（`HiCarHostActivity`），它画三个页面。不再有 CarPlay 首页、
设置 Activity、集群/HUD 页面。

## 一、三个页面

| 页面 | 什么时候出现 | 有什么 |
| --- | --- | --- |
| 连接页 | 空闲、准备热点、广播中、等待手机、失败 | 6 位连接码、开始/停止、首次配对说明、最近 12 行日志、右上角「设置」入口 |
| 会话页 | 手机认证通过之后（`Authenticated` / `ControlChannelReady` / `Streaming`） | 手机画面（等比缩放）、状态、设置、停止、返回/主页、麦克风、日志开关 |
| 设置页 | 任意时刻点「设置」 | 显示、声音、连接、诊断、关于 |

设置页是**叠在**当前页面之上的一层，不是另一个 Activity。原因很实际：`TextureView` 的
`SurfaceTexture` 属于窗口，窗口一旦被另一个 Activity 覆盖并暂停，镜像画面就会黑掉；在同一个
Activity 里叠一层，解码器和会话都不受影响，改完设置直接看到新效果。

## 二、显示模式

`HiCarWindowMode`（`shared/.../hicar/HiCarDisplaySettings.kt`）三选一，存在
`hicar-display` 偏好里，启动时读取，设置页改动后立即应用：

| 值 | 状态栏 | 底部导航栏 |
| --- | --- | --- |
| `FULLSCREEN`（默认） | 隐藏 | 隐藏 |
| `STATUS_BAR` | 显示 | 隐藏 |
| `STATUS_BAR_AND_NAVIGATION` | 显示 | 显示 |

实现要点：

- 三种模式都执行 `WindowCompat.setDecorFitsSystemWindows(window, false)`，系统栏不会背着我们
  改变内容区域大小；内容自己的内边距（`Content()` 里的 `windowInsetsPadding`）决定可见系统栏
  占多少位置。
- 全屏模式保留 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`：车机没有 Home 键时，划一下还能唤出
  系统栏，不会被锁在应用里。
- 切换模式不需要断开连接，也不需要重建 Activity。

## 三、画面为什么不会畸形

`HiCarVideoFit.fit(videoAspect, 容器宽, 容器高)` 返回**保持视频宽高比**的最大矩形，多出来的
地方留黑边（letterbox）。`TextureView` 的尺寸就是这个矩形，所以：

- 画面永不被拉伸：宽容器按高度贴合，高容器按宽度贴合；
- 触控坐标正确：`sendTouchAt` 用的是这个视图自己的宽高，视图等于画面，映射才对得上；
- 视频比例来自解码器的真实输出（`HiCarController.onPictureSize`），不是我们请求的分辨率；
  手机给 4:3 就按 4:3 显示。

纯算术放在 `HiCarVideoFit` 里，由 `HiCarVideoFitTest` 覆盖（宽容器、高容器、正好、退化尺寸、
异常比例）。

## 四、连接中改设置

- **显示**：`selectWindowMode()` 立刻 `applyWindowMode()`，画面重新按新区域等比缩放。
- **声音（车速增益）**：参数存 `hicar-speed-volume`，同时调用
  `HiCarController.refreshSpeedVolume()`，把运行中的音频通路换成新的曲线；不需要重连。
  通道选择只有音乐通道（`HiCarAudioRoute.MEDIA`）走增益，导航和通话不受影响。
- **连接**：机型码、蓝牙地址、连接码、重置配对。改动在下一轮连接生效（这些值在会话开始时
  已经发给手机）。

## 五、验证情况（2026-11-22，MuMu 模拟器，无实车）

- 连接页、设置页、显示模式切换（全屏 → 状态栏 → 状态栏+导航栏）在模拟器上确认：状态栏出现
  且内容下移、没有遮挡；模式重启后保持。
- 界面整体改为深色（`darkColorScheme()`）：夜间的车机与全屏画面一致，连接页/设置页不再是一块白。
- CarPlay 时期的布局、图标、多语言字符串已删除，图标换成矢量 `ic_hicar`，应用名 `HiCar`。
- 模拟器镜像没有蓝牙，接收端停在「失败：bluetooth off」，所以会话页、画面等比缩放、连接中
  改设置这三项**尚未在真实设备上验证**，只有单元测试覆盖几何计算。
- 该模拟器 ROM 没有导航栏窗口（`dumpsys window` 里没有 `ITYPE_NAVIGATION_BAR`），第三种模式
  在它上面看不到差别；车机（Android 10）上导航栏由系统绘制，`show(navigationBars)` 才会有效果。
