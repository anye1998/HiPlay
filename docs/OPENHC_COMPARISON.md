# 与 OpenHC 的对比：无线、有线、音频

对比对象：[GGBondLab/OpenHC](https://github.com/GGBondLab/OpenHC)（GPL-3.0-only，2026-11-22 的 0.2.0）。
本项目也是 GPL-3.0，许可证兼容；下面的"借鉴"都指按 GPL 保留版权与来源声明地移植。

OpenHC 的自述边界（`README.md`）：**只在一台比亚迪安卓 10 车机 + 一部纯血鸿蒙华为手机上验证过**，
其他车机/手机的问题作者无法复现。本项目目标设备是宋Plus（高通 trinket / Android 10）+ Mate 80 Pro Max。
所以下面比较的是**方案结构**，不是"在我們车上一定行"。

---

## 一、结论

| 领域 | 哪边更好 | 一句话理由 |
| --- | --- | --- |
| **无线** | **OpenHC** | 首次用二维码把"车机蓝牙地址 + 连接码"直接交给手机，绕开 BLE 广播被不被识别；回连由**车机主动拨手机**，不再等手机自己回来 |
| **有线** | **OpenHC** | 用户态 CDC-NCM（NTB16 组帧 + 自答 ARP + `VpnService` TUN），**不需要内核 `rndis_host`**；我们的车机恰好缺这个驱动 |
| **音频** | **OpenHC** | SSRC 里带 bus 号与安卓用途号，按用途分三路播放器、各自焦点；还有按手机记忆的 PCM→AAC 回退 |

我们不该丢的两样：**5 GHz 热点**（OpenHC 受车机限制只能 2.4 GHz，自己也在 README 里写了蓝牙同频导致卡顿），
以及**已经跑通的 HiChain → DMSDP → TCP → HiSight 这一段**（11:11 日志已到 RTSP）。

---

## 二、无线：两条完全不同的路

### 2.1 流程对比

| 步骤 | 本项目（HiPlay 移植） | OpenHC 0.2.0 |
| --- | --- | --- |
| 热点 | **先开**（`prepareNetwork()` → `openAccessPoint()`，之后才广播） | **认证成功后才开**（`StartWifiNetwork` 在 `Authenticated` 之后） |
| 首次触发 | 车机发 0xFDEE BLE 广播，手机扫到后弹窗 | 车机显示**二维码**，手机扫码 |
| 手机怎么找到车机 | 从 BLE 广播的发送地址取车机 BR 地址 | 二维码里的 `mac=` 直接给出 |
| 连接码怎么给 | 手机弹窗，用户念车机屏幕上的 6 位 | 二维码里的 `pin=` 直接带上 |
| 蓝牙连接 | 手机连车机 FE35（控制） | 手机按二维码的 `mac` 查 SDP，连车机 FE35 |
| 认证 | RFCOMM 上 HiChain | RFCOMM 子通道（FE36）上 HiChain |
| 免码回连 | 手机侧记住车机，**车机被动等** | **车机主动**：监听 `ACL_CONNECTED` → 按 SDP 拨手机的蓝牙控制服务 |
| 广播现状 | 必需 | 代码还在，但 0.2.0 **已不再自动进入**（`CHANGELOG`） |

OpenHC 的二维码内容（`core/nearby/PairingQrCode.kt`）：

```
hicar://car/connect?devicename=<名称>&mac=<车机蓝牙地址>&moduleid=<车型前6位>&submoduleid=<后2位>&pin=<6位码>&ability=<4|6>
```

`ability=4` 只无线，`6` 同时支持 USB。它要求 `mac` 必须是车机**真实公共地址**，否则不生成二维码——
和我们 7.x 得出的结论（广播地址必须是 BR 公共地址）是同一个硬约束，但 OpenHC 把它写进了二维码，不再依赖手机去猜。

### 2.2 我们的三个结构性问题

1. **首次触发依赖"BLE 广播被手机识别"**。这条路我们踩过的坑最多：RPA 地址、地址轮换、机型码白名单、
   扫描响应里的设备名、广播地址必须公开。每一个都能让"手机不弹窗"，而且从车机侧看不出来。
   二维码把这整段不确定性删掉了：地址和码都是手机直接读到的。
2. **热点先开**。没有手机时热点空转；单射频车机开热点会把自己的 Wi-Fi 断掉（OpenHC 在 README 里专门写了
   "无线期间车机 WiFi 会断开，结束后要手动恢复"）。认证成功后才开，这笔代价只在真的要连的时候付。
3. **回连是等来的**。我们 7.x 反复失败的核心现象就是"认证成功、然后没有然后"——手机不主动回来。
   OpenHC 反过来：手机蓝牙一连上车机（`ACTION_ACL_CONNECTED`），车机就去拨它（`BluetoothConnectionReceiver`
   → `WirelessController.onPhoneBluetooth` → `ReconnectPolicy`）。方向一变，"是否回连"从手机的系统策略
   变成了车机自己能控制的事。

### 2.3 OpenHC 方案的代价（同样要看清）

- **首次必须扫码**：车机屏幕要显示二维码，手机要能扫（华为相机/智慧视觉）。车机屏角度、反光、
  分辨率都可能影响；OpenHC 在比亚迪上可行，我们的车机需要实测。
- **主动回连要系统放行**：静态广播接收器 + 后台拉起 + 安卓 10 起的悬浮窗权限；比亚迪还要单独关
  "禁止后台启动"。厂商限制越多，这条越脆。OpenHC 的 README 用了整整一段讲这个。
- **二维码里带连接码**：屏幕上的二维码等于把 6 位码公开给任何能拍到屏幕的人。首次配对窗口内可接受，
  但要在配对成功后让码失效/轮换。
- **2.4 GHz**：OpenHC 自己承认蓝牙同频会导致画面卡顿（"连上以后关掉手机蓝牙能缓解"）。
  我们有 5 GHz 能力，不该退回去。

### 2.4 建议

**不要推翻现有链路，而是并行加一条"扫码首次 + 主动回连"，把广播降级为回退。**

1. 车机显示二维码（`hicar://car/connect?...`，真实蓝牙地址、机型码、pin、ability），
   复用现有的 FE35 监听与 HiChain 认证——手机扫码后走的是同一条蓝牙链路，只是"怎么找到车机"变了。
2. 设置里保留"广播发现"开关，默认关；这样 7.x 已经验证的链路仍可复现，出问题能对照。
3. 热点改成**认证成功后再开**：把 `openAccessPoint()` 从 `prepareNetwork()` 里挪到认证成功之后，
   协商应答等热点就绪再发（`HiCarNetworkNegotiator` 已经有排队机制，改动集中）。
4. 主动回连放最后：`ACL_CONNECTED` → SDP 查手机控制服务 → 拨过去。它依赖系统放行，收益大但风险也大，
   等扫码链路稳定后再做。

---

## 三、有线：我们是"等内核驱动"，OpenHC 是"自己当网卡"

| | 本项目 | OpenHC |
| --- | --- | --- |
| 手机侧 | 给出 RNDIS 配置（`0xE0/0x01/0x03`） | 收到 carInfo 后切到 HiCar 的 NCM 配置 |
| 车机侧 | 等内核 `rndis_host` / `huawei_cdc_ncm` 绑定出网卡 | **用户态**读 USB 原始数据，自己组帧 |
| 组帧 | 无 | `platform/net/Ntb16.kt`：CDC-NCM 1.0 的 NTB16（NTH16 + NDP16 + 以太网帧） |
| 网络栈 | 依赖系统网卡 | `VpnService` 建 TUN，IP 包交给系统 TCP 栈；`Arp.kt` 自己答 ARP |
| 我们的实测结论 | 车机**没有 `rndis_host`**；`/sys/module/rndis` 是 gadget 侧，不是 host 侧 | 不依赖这个驱动，所以这条死路被绕开 |

我们 7.x 的结论写在 `docs/HICAR_MIGRATION.md`（约 2845–2900 行）：手机愿意交网络，但交的是 RNDIS，
而车机的 `cdc_ether`/`cdc_ncm` 都绑不上 RNDIS；要它切 NCM 需要那条 NDA 的 car-info 字节。
而 OpenHC 把这两件事都做完了：`platform/usb/CarInfoSender.kt` 发 car-info，
`platform/net/Ntb16.kt` + `VpnTunnel.kt` 在用户态收包。

**这就是"有线连接问题解决了"的真正原因**——不是找到了驱动，而是**不需要驱动**。

要移植的三块（按依赖顺序）：

1. `CarInfoSender`：那条让手机切 NCM 的控制请求（我们最新提交 `0e35ec0` 已经在发，需逐字节对照）。
2. `Ntb16` + `Ethernet`/`Arp`/`Ipv4`：纯逻辑，可以照我们的 `HiCarFraming` 风格写成 Kotlin 并配单元测试。
3. `VpnTunnel`/`WiredVpnService`：`VpnService` 需要用户在车机上授权一次（弹窗），要处理"拒绝/撤销"。

判据不变：`huawei_cdc_ncm` 绑上 `0xFF/0x02/0x16` 说明手机确实切了；但**数据通道不再等它**。

---

## 四、音频：我们的分路是"按流"，OpenHC 是"按用途 + 路号"

我们已经有的（47 个提交里推进的）：SSRC 分多路 `AudioTrack`、`AudioFocusRequest`、
把 audio-bus 声明放进 `2102`（`HiCarDeviceBusiness` 行 206–233）。

OpenHC 多出来的三件事：

1. **SSRC 的语义**（`core/dmsdp/audio/AudioRouting.kt`）：低 16 位 = `1000 + 路号`（第 8 路固定音乐，
   第 0 路是分开的导航），高 16 位 = 安卓 `USAGE_*`（2 通话、6 铃声、12 导航……）。
   我们现在按"低 12 位"分 id，能分开流，但**认不出用途**，所以没法把导航单独送到导航声道。
2. **按用途分三路播放器**（`AudioRoute`：媒体 / 导航 / 提示与语音 / 通话 / 铃声），每路各自申请焦点；
   车机别的应用抢焦点时压低或静音，并告诉手机暂停/恢复（`AudioFocusMessages`）。
3. **按手机记忆的 PCM→AAC 回退**（`SpeakerPlan` + `PhoneRef`：用手机 `deviceId` 的 SHA-256 前 8 字节做键）：
   某台手机在 PCM 下不出声，就记住它、下次直接声明 AAC。我们目前是固定声明。

建议：先做 ①（用途号映射，纯逻辑，可单测），再做 ②（分路播放器 + 焦点），③ 等有真机反馈再补。

---

## 五、落地顺序（每步都能独立验证，不用等全部做完）

| 步骤 | 内容 | 上车验证点 |
| --- | --- | --- |
| 1 | 二维码显示 + 扫码首次连接（复用 FE35/HiChain） | 手机扫码后直接弹"连接"，不再依赖广播 |
| 2 | 热点改到认证成功后再开 | 日志里 `access point ready` 出现在 `Authenticated` 之后 |
| 3 | 有线：`CarInfoSender` 逐字节对照 + `Ntb16` + `VpnTunnel` | 手机切 NCM 后，车机不加载任何驱动也能 ping 通 192.168.66.x |
| 4 | 音频：SSRC 用途号 → 分路播放器 → 焦点 | 导航播报走导航声道，音乐不中断 |
| 5 | 主动回连（`ACL_CONNECTED` → SDP 拨手机） | 手机蓝牙连上车机后，车机日志出现主动拨号 |

前两步解决"每次都要折腾广播"，第三步解决有线，第四步解决声音分路——都不需要推翻现有实现。
