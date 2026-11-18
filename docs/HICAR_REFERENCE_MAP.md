# 参考实现对照表（huducat586/hicar-android-receiver）

这份文件存在的目的只有一个：**把参考实现里每一个协议模块，和我们的实现一一对上**，
说清楚"我们已有 / 我们缺 / 我们故意不做"，避免再出现"协议在别人代码里写着、我却在抓包里猜"的弯路。

参考实现：<https://github.com/huducat586/hicar-android-receiver>（MIT），
其协议部分移植自 <https://github.com/Aki894/CarLink>（MIT，Python 工具集）。
我们这边是 GPL-3.0；MIT 与 GPL 兼容，移植时保留版权声明与本对照表即可。
用户提供的 `11.11.apk` 已解包核对：`classes.dex` 里的 46 个类与仓库 `app/src/local/hicarlab/`
完全一致（`local.hicarlab.*`），原生库 `libcarlink_hichain.so` 也来自同一构建，
即该 APK 就是这一版源码（帖子里说的分辨率/UI 修改不影响协议层）。

## 一、链路总览（两边一致的部分）

```
0xFDEE BLE 广播(11B)  →  RFCOMM 0xFE35 控制 / 0xFE36 业务  →  HiChain 认证
        →  Wi-Fi 5GHz 热点  →  TCP 三个端口(4005/4006/4007)  →  DMSDP 控制  →  HiSight 视频
```

## 二、逐模块对照

| 参考实现文件 | 作用 | 我们的对应实现 | 状态 |
| --- | --- | --- | --- |
| `DiscoveryBeacon` | 0xFDEE payload（型号 + 递增序号） | `HiCarBeacon` | 已有，字段一致 |
| `NearbyWire` | 帧：`0x7F`+varint kind+flags+opts+len+payload+CRC16 | `HiCarFraming`（`HiCarWire`/`HiCarFrameDecoder`/`HiCarVarint`/`hiCarCrc16`） | 已有，逐字段一致 |
| `NearbyWire.Negotiator` | 0xFE35：CCMP 版本/设备摘要/分帧方式，再答业务通道号 | `HiCarChannelNegotiator` | 已有，顺序与拒绝条件一致 |
| `NetworkResource` | 网络能力、数据端口、选网、凭据串 | `HiCarNetworkNegotiator` + `HiCarNetworkOffer` | 已有，凭据串逐字一致 |
| `AuthBusiness` | 0xFE36：身份交换 + HiChain 引擎驱动 | `HiCarIdentityExchange` + `HiChainAuthenticator` | 已有（本轮之前已打通到 END_SUCCESS） |
| `NativeAuth` | JNI：`init/create/step/sessionKey/close` | `HiChainNative` + `hichain_jni.c` | 已有 |
| `DmsdpWire` | **TCP 4006**：入口模块帧，答控制端口（+2） | `HiCarDmsdpTransport`（本轮新增） | **本轮补齐** |
| `ControlSession` | **TCP 4007**：`OPTIONS * DMSDP/1.0`、`CryptoSwitch 222` 转 AES-CTR、心跳、时钟、`$` 业务包 | `HiCarControlSession`（已有）+ `HiCarDmsdpEndpoints`（本轮接线到 TCP） | **本轮补齐** |
| `SessionCipher` | AES-128-CTR，密钥取 HiChain 会话密钥前 16 字节，每条记录前置随机 IV | `HiCarRecordCipher` | 已有，逐字节一致 |
| `DeviceBusiness` | 模块 3：指令 101 身份、104 服务目录、102 宣布服务；模块 5 拿视频端口 | `HiCarDeviceBusiness` | 已有：显示/扬声器/麦克风三项按就绪状态宣布 |
| `HiSightWire` | 视频 RTSP：`ANNOUNCE`(aes128ctr) → `OPTIONS`/`GET_PARAMETER`/`SET_PARAMETER` → `SETUP`(server_port) → `PLAY` | `HiCarHiSightWire` | **本轮移植**，含 7 个单元测试 |
| `HiSightClient` | 控制/视频/输入三条 TCP，媒体线程与空闲/断流判定 | `HiCarHiSightClient` | **本轮移植** |
| `HiSightMedia` | RTP → PES → 解密 → H.264 访问单元 | `HiCarHiSightMedia` | **本轮移植**（视频走 TCP，不是 UDP） |
| `VideoSink` | H.264 → Surface，有界队列，窗口可来可走 | `HiCarVideoSink` | **本轮移植**（分离式 `SurfaceTexture`） |
| `AudioMedia`/`AudioSink`/`AudioReceiver` | 加密 UDP → RTP → ADTS → AAC → AudioTrack；`SpeakerControl` 处理扬声器指令 | `HiCarAudioWire`/`HiCarAudioSink`/`HiCarAudioReceiver`/`HiCarSpeakerControl` | **本轮移植** |
| `MicrophoneSender`/`MicrophoneWire`/`MicrophoneControl` | 语音上行（需录音权限，默认关闭） | `HiCarMicrophone`/`HiCarMicrophoneControl` | **本轮移植**，默认关闭，需手动打开 |
| `InputChannel`/`InputRouter`/`UiInput` | 触控/返回/主页（UIBC） | `HiCarUiInput`（编码器 + 路由 + 发送通道） | **本轮移植** |
| `ReconnectGate` | 断线后重连门控，避免误判断开 | `HiCarController` 的 `generation` + `reconnectPending` | **本轮移植**：一轮一次、延迟 1.5 s、保留解码器与窗口（`HiCarVideoSink.restart`） |
| `WireIdentity` | **用本机蓝牙地址派生 deviceId**，让手机的记忆键稳定 | `HiCarHostActivity.rotateIdentity`（随机 16 位） | 部分：我们可换标识，但不是从 BT 地址派生 |
| `P2pHotspot`/`LocalHotspot`/`LocalApInfo`/`OwnApScan` | 5GHz 热点与地址读取 | `WifiP2pGroupManager`/`LocalOnlyHotspotManager`/`HiCarAccessPoints`/`HiCarAccessPointBssid` | 已有（且我们补了 BSSID 三源取证） |
| `RfcommPort`/`ClientPreflight` | 反射取 RFCOMM 动态通道号、前置检查 | `HiCarRfcommServices` | 已有 |

## 三、三个 TCP 端口的分工（本轮实现的依据）

| 端口 | 分帧 | 内容 |
| --- | --- | --- |
| `controlPort` (4005) | nearby wire：`0x7F`+kind | 加入热点后的补做：能力(kind 4 tag 1)、数据端口(tag 3)、选网(tag 7)、凭据(kind 33) |
| `controlPort+1` (4006) | DMSDP 模块：`0x7F`+module+len+payload+CRC | 入口握手：模块 1、16 字节全零 → 回 16 字节，第 1、2 字节是控制端口 |
| `controlPort+2` (4007) | DMSDP 记录：`marker`+len(2) | 控制通道：明文 `OPTIONS` → `CryptoSwitch 222` 转 AES-CTR → 心跳 → `$` 业务包 |

`$` 业务包的固定 11 字节头：`'$' | module(4, BE) | type(4, BE, 0xFFFFFFFF) | len(2, BE) | body`。
body 以 varint 指令号开头，101=身份、102=服务宣布、104=服务请求；模块 5 + 指令 101 携带视频端口。

## 四、本轮做了什么、故意没做什么

做（累计）：

- `HiCarDmsdpTransport.kt`：DMSDP 模块帧、增量解码器、入口应答。
- `HiCarDeviceBusiness.kt`：身份（101）与服务目录（104/102），`$` 包头封装；三个服务各自按就绪状态宣布。
- `HiCarDmsdpEndpoints.kt`：三个 TCP 监听，只接受"本轮发过凭据的那个手机地址 + 已认证"的连接，
  控制通道带 1 秒心跳与 30 秒空闲断开；控制连接建立时构建媒体（音频 UDP、麦克风），连接结束即释放。
- `HiCarHiSightWire.kt` / `HiCarHiSightClient.kt` / `HiCarHiSightMedia.kt`：RTSP 握手、三条 TCP、
  RTP→PES→H.264。
- `HiCarVideoSink.kt`：`MediaCodec` 解码到 `SurfaceTexture`，队列有界，窗口可分离。
- `HiCarAudioWire.kt` / `HiCarAudioSink.kt` / `HiCarAudioReceiver.kt` / `HiCarSpeakerControl`：
  加密 UDP、RTP/ADTS、AAC→AudioTrack、`speaker1` 协商。
- `HiCarMicrophone.kt`：`mic1` 协商 + `AudioRecord`→AAC→RTP→UDP，默认关闭。
- `HiCarUiInput.kt`：UIBC 编码、按下/抬起配对、发送队列、800×480 坐标映射。
- `HiCarController`：热点就绪即开监听并预热解码器；认证后把会话密钥复制给 TCP 侧；
  媒体端口一到就 dial HiSight；`sendTouchAt`/`sendKey` 给界面用。

故意没做 / 保持保守的地方：

1. **显示服务只在解码器真的就绪时宣布**（`displayReady = { video.ready }`）。宣布了却放不出画面，
   手机只会报"连接失败"，比"没有这个功能"更难排查。
2. **麦克风默认关闭**：需要 `RECORD_AUDIO` 权限并且界面上手动打开；关闭时对手机的 `mic1` 请求回 403。
3. **不宣布方向盘按键与 HUD**：`his_uibc_capability` 只声明触控与返回/主页，方向盘的按键码没有验证过。
4. **不改 deviceId 派生方式**：参考实现用蓝牙地址派生，我们目前用 ANDROID_ID 派生（同样稳定、可轮换），
   换成 BT 地址会让已经配对成功的手机把车当成新车，等真车链路稳定后再评估。

## 五、下一阶段

1. 上车验证这一轮：`service request` → 显示服务宣布 → `SETUP` 拿到端口 → 首帧。
   若某条 RTSP 记录被跳过，日志会直接写出它的文本（`record ignored as … >> … <<`）。
2. 若首帧不出：按日志分层定位——`HiSight: ANNOUNCE sent` / `SETUP sent` / `video socket connected` /
   `H.264 access unit`，哪一层没有就补哪一层。
3. 画面正常后再看音频与触控（`speaker: SETUP sent`、`UIBC: … event(s) sent`）。
4. 身份派生（`WireIdentity` 的蓝牙地址派生）仍未改：现在用 `ANDROID_ID` 派生且可轮换，
   改掉会让已配对的手机把车当成新车、又要输一次码，等链路稳定后再评估。

## 六、补读：参考项目里"能跑通的流程"到底写在哪

前几轮只读了 `app/src/local/hicarlab/*.java`（约 3192 行）。用户指出参考项目里已经有跑通的流程，
于是这一轮把之前漏掉的资料全部读完：

| 位置 | 内容 | 对我们有用的结论 |
| --- | --- | --- |
| `README.md` | 功能状态表、兼容性记录、**首次使用 6 步** | 首次在两台设备的**系统蓝牙设置里完成普通配对**是部分设备连接成功的前提；我们界面原来没有这句，反而在引导"删记录 / 换标识" |
| `CHANGELOG.md` | 0.10.6 修正"发送端锁屏暂停视频时 15 秒媒体读取超时导致整轮连接被错误关闭" | 已核对：`HiCarHiSightClient.readFully` 保留部分字节、15 秒只记日志不断线，与参考一致 |
| `docs/KNOWN_ISSUES.md` | 重启后自动回连**仍未解决**、热点接口厂商差异、验证边界 | 不要把"编译/装包/解码器初始化"当成连通；我们的自动重连也只是减少手动点击，不等于解决回连 |
| `docs/VALIDATION.md` | `scripts/test-host.py` 的 19 个主机协议测试 | 见下一行 |
| `scripts/test-host.py` + `app/tests/*.java`（19 个） | `HiSightTest`、`HiSightTcpTest`（真实回环 TCP、视频+反向通道都连上才 PLAY、`--media-idle` / `--idle`）、`ControlTest`、`DmsdpTest`、`NetworkTest`、`WireTest` 等 | 这些是"跑通流程"的可执行定义；`HiSightTcpTest` 的报文序列已进 `HiCarHiSightReferenceParityTest` |
| `native-auth/wire-vectors.txt` / `dmsdp-vectors.txt` / `network-vectors.txt` | 从 Python 线协议实现产生的**逐字节向量**（42 个 nearby 帧、DMSDP 入口握手、完整 Wi-Fi 协商含凭据串） | 已复制进 `shared/src/test/resources/hicar/`，由 `HiCarReferenceVectorsTest` 逐字节验收 |

### 逐字节验收结果（不是推测）

`HiCarReferenceVectorsTest` 三项全绿：

1. **nearby 帧**：42 行的 `kind/payload → framed hex` 全部逐字节相同；每一行还遍历**所有切分点**验证增量解码，
   并把末字节翻转验证 CRC 必被拒。
2. **DMSDP 入口（4006）**：请求解码后 `hiCarDmsdpEntryReply(packet, 4007)` 与参考应答逐字节相同；同样遍历切分点、验坏 CRC。
3. **Wi-Fi 协商**：按参考 `NetworkTest` 的顺序喂 6 行向量，能力应答、选网后的队列命令（kind 33 tag 1）、
   station 地址→凭据串、后续两步应答**全部逐字节相同**；未认证时拒绝、加密帧拒绝也一致。

结论：**广播帧、DMSDP 入口、网络凭据这三段不是"看起来像"，字节级就是参考实现**。
再加上 7.39 的 HiSight RTSP 差分测试，协议层能对照的部分已经全部对照过。

### 仍然没有证据的部分（明确写出）

- 向量与单元测试证明不了真机画面/声音/触控：参考项目自己也把"编译通过、解码器初始化"与"实际可用"分开写。
- 参考 README 的实测设备是 Android 17 / Android 14 手机 + Mate 40 Pro / HarmonyOS 4；
  **安卓车机、HarmonyOS 5/6 发送端都写着"尚未实测"**，我们的组合（高通 trinket / Android 10 / Mate 80 Pro Max）
  同样在他们的未知范围里。
- 11:11 那条"OPTIONS 之后解析失败"的记录内容，现有日志里没有。新包会把它以
  `record ignored as … >> … <<` 打印出来，下一次上车才能定论。
