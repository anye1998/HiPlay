# HiCar 迁移侦察报告（阶段 0）

本文件是 `feat/hicar-migration` 分支的第一份交付物：在改动任何代码之前，先固定源项目的结构事实。
所有结论都来自本地克隆的源码，给出文件路径，便于复核。

- 克隆来源：`https://github.com/anye1998/HiPlay`（GitHub 会 301 重定向到 `shihabal3amri/DiPlay`）
- 侦察时 HEAD：`615b32c`
- 许可证：**GPL-3.0**（`LICENSE`）。任何分发衍生版本都必须按 GPL-3.0 提供源码。
- 语言构成：Kotlin 918 个文件、C/C++ 23 个文件（仅 `shared/src/main/jni`），没有 Java 源文件。

---

## 1. 模块与角色

| 模块 | 路径 | 角色 |
| --- | --- | --- |
| `common` | `common/` | 应用 UI、设置页、诊断、更新模块、`AndroidManifest.xml` 中绝大多数组件声明 |
| `shared` | `shared/` | **协议栈本体**：AirPlay / iAP2 / MFi / USB / Wi-Fi / 媒体管线 / 会话编排 |
| `mobile` | `mobile/` | 唯一带 `applicationId = com.shihab.diplay` 的 APK 壳；只有清单、资源、签名配置，**没有 Java/Kotlin 源文件** |
| `automotive` | `automotive/` | Car App Library 变体（`MyCarAppService` 在 `mobile` 清单里被 `tools:node="remove"` 掉） |
| `samples/maphost`、`samples/home` | `samples/` | 示例工程，与协议无关 |
| `site` | `site/` | 项目网页（GitHub Pages） |

依赖方向：`mobile → common + shared`；`common → shared`。协议能力全部沉在 `shared`，UI 层只接缝调用。

---

## 2. CarPlay 连接建立相关模块、类与入口

### 2.1 入口组件（`common/src/main/AndroidManifest.xml`）

| 组件 | 类 | 作用 |
| --- | --- | --- |
| LAUNCHER Activity | `com.shilapi.xcertplay.DiPlayActivity` | 主页与设置页；承载手动检查更新、启动会话 |
| 投屏宿主 Activity | `com.shilapi.xcertplay.CarPlayHostActivity` | 真正出画面的 Activity，`taskAffinity=".carplay"`，带 `USB_DEVICE_ATTACHED` intent-filter（有线接 iPhone 直接拉起） |
| 同任务变体 | `com.shilapi.xcertplay.SameTaskCarPlayHostActivity` | 继承上面的类，仅换任务栈（给卡片/PiP 启动器用） |
| 前台服务 | `com.shilapi.xcertplay.DiPlaySessionService` | `foregroundServiceType="connectedDevice|microphone|location"`，会话存活保障 |
| 视频播放 | `com.shilapi.xcertplay.CarPlayVideoActivity` | Video-in-Car |
| USB 授权辅助 | `com.shilapi.xcertplay.UsbAutoConfirmService`（AccessibilityService） | 自动确认 USB 权限弹窗 |

`CarPlayHostActivity` 是整个投屏会话的组装点：它在 `CarPlayHostActivity.kt:5693` 构造 `CarPlayController`，
并注入 `AirPlayConfig`、`AirPlayIdentity`、`PairingStore`、`AirPlaySessionListener`、`AirPlayMediaHandler`、
pair-record 读写、定位与车辆状态提供者。会话收尾走 `CarPlayBackgroundSession.store(...)`。

### 2.2 连接编排器

`shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`（3265 行）是唯一编排者：

- 状态机 `private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }`
- 对外状态 `sealed class CarPlayStatus`（60+ 个状态，含 `DiscoveringMfi` / `StartingHotspot` / `HotspotReady` /
  `WaitingForPairedIphone` / `ConnectingBluetooth` / `RunningWireless` / `Pairing` …），通过构造参数
  `reportStatus: (CarPlayStatus) -> Unit` 回吐给 UI
- 生命周期：`start()`（:506）、`close()`（:715）
- 分阶段实现：`startMfi()`（:928）、`startWireless()`（:1269）、`startPhone()`（:1248，有线）、
  `startIphone()`（:1928）、`startWirelessHotspot()`（:2495）、`startWirelessTunnelControl()`（:1682）

### 2.3 两条物理链路

**有线（USB）**：`transport/IphoneUsbHost.kt` + `transport/NcmFunctionDiscovery.kt` / `NcmUsbBridge.kt`
（NCM 网卡）→ `transport/Iap2UsbMuxHost.kt`（USB mux）→ `transport/LockdownPairingClient.kt` /
`LockdownTlsEngineFactory.kt`（lockdown/TLS）→ `transport/Iap2WiredControlClient.kt`。

**无线**：由 `network/WirelessHotspotManager.kt` 的接口统一抽象，四种后端
`WirelessHotspotBackend { WIFI_P2P, LOCAL_ONLY_HOTSPOT, MANUAL_HOTSPOT, EXISTING_WIFI }`，实现类分别是
`WifiP2pGroupManager.kt`、`LocalOnlyHotspotManager.kt`、`ManualHotspotManager.kt`、`ExistingWifiManager.kt`；
热点就绪后再用 `network/CarPlayBonjour.kt`（jmdns）做服务发现，用 `transport/BluetoothRfcommDuplexStream.kt`
做蓝牙 bootstrap 通道，最后 `transport/Iap2WirelessControlClient.kt` 走 iAP2 无线控制。
`network/CarPlayVpnService.kt` 在需要把 iPhone 流量经车机转发时起 VPN。

发现阶段产物是 `WirelessHotspotInfo`（`WirelessHotspotManager.kt` 中定义）：`ssid / passphrase / security /
channel / frequencyMHz / bssid / interfaceName / hostAddress / bandLabel / backend`。

---

## 3. 音视频、控制通道、会话生命周期如何抽象

分四层，全部是自研 Kotlin（不是第三方库）：

| 层 | 抽象 | 文件 | 说明 |
| --- | --- | --- | --- |
| 字节流 | `BlockingDuplexByteStream : Closeable`（`send` / `recv(maxBytes, timeoutMillis)`） | `transport/BlockingDuplexByteStream.kt` | 所有控制通道的统一底座；RFCOMM、USB mux、TCP 都实现它 |
| 控制通道 | `Iap2LinkEngine` / `Iap2LinkChannel` / `Iap2CsmChannel` | `transport/Iap2LinkEngine.kt` 等 | iAP2 链路（帧、校验、CSM 会话） |
| 会话 | `AirPlaySession`（RTSP 服务端：配对/校验、SETUP/TEARDOWN、事件通道、触摸 HID、NTP 保活） | `airplay/AirPlaySession.kt` | 每路控制连接一个实例 |
| 接缝 | `AirPlaySessionListener`、`AirPlayMediaHandler`、`MediaSink`、`CarPlayVideoListener` | `airplay/AirPlaySession.kt`、`airplay/CarPlayMediaEngine.kt`、`orchestration/CarPlayVideoListener.kt` | UI/渲染层只实现这些接口 |

媒体数据通道：

- 视频：`airplay/ScreenStream.kt` 解出 H.264/HEVC NALU → `MediaSink.onVideoFrame(type, nalu, senderNanos, arrivalNanos)`
  → `MediaCodec` + `Surface`（`CarPlayHostActivity` / `CarPlayVideoActivity`）
- 音频下行：`airplay/AudioStream.kt`、`BufferedAudioStream.kt`、`media/MediaAudioBuffer.kt`、`media/MediaClockTrim.kt`、
  `media/AudioChannelMapping.kt`，输出到 `AudioTrack`（`media/AndroidMediaSink.kt`）
- 麦克风上行：`media/MicrophoneUplink.kt`、`MicrophoneOpusEncoder.kt`、`SoftwareOpusEncoder.kt`，
  回声消除走 `speexdsp` 的 JNI（`media/SpeexEchoCanceller.kt` + `shared/src/main/jni/speex_echo_jni.c`）
- 触摸/按键：`airplay/AirPlayHid.kt`、`AirPlayTouchSlots.kt`、`media/CarPlayTouchMapper.kt`、`CarPlayMediaButton.kt`

**对 HiCar 迁移的意义**：`MediaSink` 和 `BlockingDuplexByteStream` 是天然的可复用接缝——HiCar 的视频 ES 也可以
喂给同一个 `MediaSink`，HiCar 的 RFCOMM/TCP 通道也可以实现 `BlockingDuplexByteStream`。

---

## 4. 现有构建配置

- `compileSdk = 37`，`minSdk = 24`，`targetSdk = 37`（`shared/build.gradle`、`mobile/build.gradle.kts`）
- AGP **9.3.0**、Gradle **9.5.0**、Kotlin **2.2.10**（`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`）
- 原生库：`shared/build.gradle` 里 `ndkVersion = '28.2.13676358'`，`externalNativeBuild { ndkBuild { path = 'src/main/jni/Android.mk' } }`，
  `APP_PLATFORM=android-24`，ABI：`arm64-v8a / armeabi-v7a / x86 / x86_64`
  - `jni/speexdsp/`：Speex 回声消除（`speex_echo_jni.c`）
  - `jni/linux_i2c_jni.c`：i2c 通道（部分车机走 i2c 取车辆信号）
  - `jni/local_hotspot_radio.c`：读热点射频信息
- 第三方依赖：BouncyCastle 1.79（SRP/曲线）、jmdns 3.6.3（Bonjour）、androidx work 2.12.0（后台任务）、
  compose BOM 2026.02.01、appcompat 1.8.0、media3 1.11.1、zxing 3.5.3、robolectric 4.17（测试）
- CI 现状：`.github/workflows/android.yml`（push main / PR / 手动），JDK 25，跑 `:shared:testDebugUnitTest` 等
  单元测试 + lint + `assembleDebug`，**没有 release 构建、没有 tag 发布、没有签名兜底**

---

## 5. 现有蓝牙 / Wi-Fi Direct / Socket 代码可复用性

### 5.1 可以直接复用的

| 能力 | 位置 |
| --- | --- |
| RFCOMM 双工流（含 vendor flush/EACCES 兼容、读线程、上限保护） | `transport/BluetoothRfcommDuplexStream.kt` |
| 蓝牙链路/射频探测 | `transport/BluetoothLinkCheck.kt`、`BluetoothRadioCheck.kt` |
| 手机 ACL 连上事件（广播接收器） | `common/.../PhoneBluetoothReceiver.kt`（`ACL_CONNECTED`） |
| Wi-Fi Direct 组管理 | `network/WifiP2pGroupManager.kt`、`WifiP2pChannels.kt`、`P2pOwnership.kt`、`P2pConfigurationMemory.kt` |
| LocalOnlyHotspot（含 SoftApConfiguration 反射建网、射频校验） | `network/LocalOnlyHotspotManager.kt` |
| 手动热点 / 既有 Wi-Fi 接入 | `network/ManualHotspotManager.kt`、`ExistingWifiManager.kt` |
| TCP 存活检测、端口选择 | `network/TcpLiveness.kt`、`network/AirPlayPortSelector.kt` |

### 5.2 缺失的（HiCar 必须新增）

1. **没有任何 BLE 广播代码**。全仓 `BluetoothLeAdvertiser` / `BluetoothLeScanner` / `startAdvertising` 命中数为 0；
   HiCar 的 FDEE 广播必须从零写。
2. **没有 `createBond` 调用**（这点对 HiCar 反而有利：HiCar 不需要传统配对/Bonding）。
3. **RFCOMM 只有客户端方向**。`BluetoothRfcommDuplexStream` 是对已连接 `BluetoothSocket` 的包装，
   全仓没有 `listenUsingRfcommWithServiceRecord` / `listenUsingInsecureRfcommWithServiceRecord`。
   HiCar 车机侧是**服务端**（手机来连），需要新增 server socket 层。
4. **权限缺 `BLUETOOTH_ADVERTISE`**。`shared/src/main/AndroidManifest.xml` 现有
   `BLUETOOTH / BLUETOOTH_ADMIN / BLUETOOTH_CONNECT / ACCESS_FINE_LOCATION / NEARBY_WIFI_DEVICES /
   CHANGE_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE`，缺 ADVERTISE（BLE 广播必需）。
5. **没有任何 HiCar/HiChain 协议代码**，也没有华为 SDK 依赖。

---

## 6. 更新模块现状（需求四的起点）

`common/src/main/java/com/shilapi/xcertplay/update/` 已经有一套可用的实现：

| 文件 | 现状 |
| --- | --- |
| `UpdateClient.kt` | 直连 `https://api.github.com/repos/shihabal3amri/DiPlay/releases?per_page=3`，10s 连接 / 30s 读超时，64MB 下载上限 |
| `UpdateReleaseLookup.kt` | 只走上面那一个 URL，无回退 |
| `UpdateCatalog.kt` | 解析 release 数组，只接受 `github.com` 的 https 资产地址，要求同时有 `.apk` 和 `SHA256SUMS.txt` |
| `UpdateVersion.kt` | 正则 `(\d+)\.(\d+)\.(\d+)` 三段比较（可解析 `v0.2.22` 这类前缀） |
| `UpdateChecksums.kt` | 解析 `SHA256SUMS.txt`，`MessageDigest.isEqual` 比对 |
| `BackgroundUpdateWorker.kt` | WorkManager 周期任务 **24h** + 打开应用后 2 分钟的一次性检查 |
| `UpdateAvailability.kt` | 结果持久化 + 首页横幅 |
| 手动入口 | `DiPlayActivity` 的 `update_check` 按钮（`common/src/main/res/values/strings.xml:885`） |

结论：**24h 周期和手动入口已经存在**，缺的正是"镜像回退"和"静态清单兜底"。

---

## 7. HiCar 连接链路（来自参考实现，只作协议理解，未复制代码）

参考：`huducat586/hicar-android-receiver`（MIT，其协议笔记转述自 `Aki894/CarLink` 的 python 实现）。
下面是"车机侧 = Receiver"的真实链路，与本项目要做的方向一致：

1. **先建热点，再广播**：LocalOnlyHotspot（API 33+）或 Wi-Fi P2P（旧版本）先起来，拿到 owner IP / client IP /
   SSID / passphrase / BSSID / 频率，以及约定控制端口（参考实现用 4005）。
2. **注册两个不加密的 RFCOMM 服务**（`listenUsingInsecureRfcommWithServiceRecord`，**不调用 createBond**）：
   - `0000fe36-...`：业务/鉴权通道（服务名 "Business"），通道号由系统动态分配，需反射读取
   - `0000fe35-...`：Nearby 控制通道
3. **BLE 广播 FDEE**：`AdvertiseData.addServiceData(ParcelUuid("0000fdee-..."), payload)`，
   payload 里带 4 字节型号 + 序号（参考实现为 11 字节 AD 结构）。广播里"携带车机 BR 地址"这一点由
   系统在可连接广播中提供（connectable = true），协议层不自带地址字段。
4. **FE35 上做参数协商**（自定义帧：`0x7F` 标记 + LEB128(终结位在高位) + TLV + CRC16/MODBUS）：
   手机发 kind 0（TLV tag 1/3/5：CCMP 版本、摘要、分帧方式），车机回 tag 2/4/6；
   随后手机发 kind 2 tag 3（transport=2, business=19, protocol=0）申请业务通道，车机回 tag 4：
   `(0, 动态通道号, 原字段, 16, FE36 UUID)`。
5. **FE36 上做 HiChain 鉴权**：JSON 信封 `{version:"A_0", discover_type:1, oper_type:1|3, data:"<json>"}`，
   `oper_type=1` 交换身份（deviceId / devicename / CAR_MODE_ID / has_pin），`oper_type=3` 跑 HiChain 消息
   （message 17 = STS 请求身份检查，32896 = 错误码），成功后由 **native deviceauth_lite + HUKS** 导出 16/32 字节会话密钥。
6. **控制通道（DMSDP）**：3 字节头（marker 0 / 0xAB + 长度）+ 明文 `OPTIONS * DMSDP/1.0` 起步，
   `SET_PARAMETER` 带 `CryptoSwitch: 222` / `CryptoAlg: aes128-ctr` 切换加密，之后每条记录
   `16 字节随机 IV + AES-128-CTR 密文`；`$` 开头为二进制业务帧（4 字节 module + 4 字节 type + body）。
7. **Wi-Fi 参数协商**（在 FE35 上，鉴权之后）：手机发 kind 4 tag 1（channel=3 + 能力位），
   车机校验 `cap2==1`、`cap4==1`、`cap5 & 频率掩码 != 0` 后回 tag 2；手机再发 tag 3 申请数据端口，
   车机回 tag 4（端口）；手机发 tag 7 选网 + 角色；车机随后发 kind 33 tag 1 让手机拉取凭据，
   手机回 kind 33 tag 2/4 带自己的 MAC，车机回 tag 3 = 凭据串
   `clientIp##ssid\nbssid\npassphrase\nfreq\n1##version:3##controlPort##2##hicarApIp:ownerIp`。
8. **投屏（HiSight）**：热点上开 3 个 TCP 监听（controlPort, +1 数据端口, +2）；
   RTSP/1.0 语义（ANNOUNCE 协商 `encrypt_list=aes128ctr`、OPTIONS/GET_PARAMETER 报能力
   `his_video_formats`、SET_PARAMETER 下发 `his_presentation_url`、SETUP 回 `server_port`/`remotectl_port`、PLAY），
   RTP(96..98) → PES → `AES-128-CTR` 解密 → H.264 基本流；音频独立通道；输入走 `remotectl_port`。

**结论**：HiCar 与 CarPlay 的差别不是"换个类名"，而是**完全不同的发现/鉴权/传输栈**：
BLE 广播 + RFCOMM 双服务 + HiChain（华为协议；OpenHarmony 有 Apache-2.0 的 deviceauth_lite 实现） + DMSDP 文本控制 + HiSight RTSP 投屏。
CarPlay 那套 iAP2 + AirPlay RTSP 一行都用不上，能复用的只有字节流底座、Wi-Fi/热点管理、媒体渲染接缝。

---

## 8. 由此确定的实现边界（写进最终交付报告）

| 能力 | 判定 |
| --- | --- |
| BLE FDEE 广播（不配对） | 可实现，纯 Android API |
| RFCOMM 双服务 + 参数协商 + 业务通道分配 | 可实现，纯协议逻辑，可单测 |
| DMSDP 控制通道（明文/加密、心跳、时钟） | 可实现，AES-CTR 是标准件 |
| Wi-Fi 能力协商 + 凭据下发 | 可实现，可单测 |
| HiSight RTSP 协商 | 可实现（会话层），媒体解密可单测 |
| **HiChain 鉴权** | **本分支未集成**（更正：不是不可行）。OpenHarmony 以 Apache-2.0 公开 `deviceauth_lite` + `huks_lite`，
  两个参考项目都编进 APK 后跑通，首连靠六位连接码，无需配件证书。集成步骤见 HICAR_MIGRATION.md 第 6 节 |
| 音频（AAC 上行/下行） | 依赖已认证会话的密钥，故同样受阻；标记 TODO 并给出降级方案 |
| 方控 / HUD / 仪表 | 华为私有，无 SDK 不可得；如实列为不可用 |

因此本分支交付的是**可编译、可单测、协议层真实的 HiCar 接收端骨架**，不是"能连上手机出画面"的成品。
这点会在最终报告里逐条写清，不做"应该可以"的表述。
