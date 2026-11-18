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
| `DeviceBusiness` | 模块 3：指令 101 身份、104 服务目录、102 宣布服务；模块 5 拿视频端口 | `HiCarDeviceBusiness` | **本轮新增**（显示服务先不宣布） |
| `HiSightWire` | 视频 RTSP：`ANNOUNCE`(aes128ctr) → `OPTIONS`/`GET_PARAMETER`/`SET_PARAMETER` → `SETUP`(server_port) → `PLAY` | — | **未做**，下一阶段 |
| `HiSightClient`/`HiSightMedia`/`VideoSink`/`VideoProfile` | 视频 UDP 收流、H.264 解码、800×480 起（可到 2K） | — | **未做** |
| `AudioMedia`/`AudioSink`/`AudioReceiver` | AAC 音频；`SpeakerControl` 处理手机侧扬声器指令 | — | **未做** |
| `MicrophoneSender`/`MicrophoneWire`/`MicrophoneControl` | 语音实验（默认关闭） | — | **不做**（不是当前目标） |
| `InputChannel`/`InputRouter`/`UiInput` | 触控/返回/主页（UIBC） | — | **未做** |
| `ReconnectGate` | 断线后重连门控，避免误判断开 | — | **未做**（我们目前直接关会话） |
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

做：

- `HiCarDmsdpTransport.kt`：DMSDP 模块帧、增量解码器、入口应答。
- `HiCarDeviceBusiness.kt`：身份（101）与服务目录（104），`$` 包头封装。
- `HiCarDmsdpEndpoints.kt`：三个 TCP 监听，只接受"本轮发过凭据的那个手机地址 + 已认证"的连接，
  控制通道带 1 秒心跳与 30 秒空闲断开。
- `HiCarController`：热点就绪即开监听；认证成功后把会话密钥复制给 TCP 侧（RFCOMM 关闭时密钥不会随之消失）。

故意没做：

1. **不宣布显示服务**（`displayReady = { false }`）。宣布了却放不出画面，手机只会报"连接失败"，
   比"没有这个功能"更难排查。等 HiSight + 解码器接上再打开这一位。
2. 不做麦克风、不做重连门控。

## 五、下一阶段（按顺序）

1. `HiSightWire` 等价物：RTSP 握手 + AES-CTR + `SETUP` 拿到 `server_port`/`remotectl_port`。
2. 视频 UDP 收流与 H.264 解码（我们已有 CarPlay 的解码管线可复用）。
3. 打开显示服务，`displayReady` 接真实解码器。
4. `ReconnectGate` + `WireIdentity`（BT 地址派生），解决"重启后回连"。
