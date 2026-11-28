# 有线连接（USB CDC-NCM，用户态）

本文记录这一轮加入的有线链路：它做了什么、依据是什么、哪些部分验证过、哪些还没有。
**整条有线链路没有在实车上跑过**，设置页里标了「还没有在实车上跑过」。

## 一、为什么要在用户态驱动

HiCar 的有线连接不是「第二个帧格式」，而是换了一条承载：

1. 车机通过 USB 控制请求把一段 91 字节的「车机信息」交给手机；
2. 手机按这段信息重新枚举成一块 **NCM 网卡**；
3. 手机按信息里的地址与端口拨车机，之后的 TCP 握手、认证、HiSight 与无线完全一致。

第 2 步之后，Linux 内核如果有 `cdc_ncm` / `rndis_host` 就会自动建出网卡。这台车机两项都加载了，
但 `huawei_cdc_ncm` 只绑定 `class 0xff / subclass 0x02 / protocol 0x16`（以及 0x46、0x76）的接口，
而手机枚举出来的是标准 CDC：控制口 `2/13`、数据口 `10`。**没有驱动认领它**，所以网卡不会自己出现。

于是这一轮的做法是：自己认领接口、自己搬包，用 `VpnService` 的 TUN 当网络端点。

## 二、链路组成

| 文件 | 职责 |
| --- | --- |
| `shared/.../hicar/HiCarWiredLink.kt` | 高层：监听插拔、发 91 字节车机信息、等待重新枚举、组装下面三件 |
| `shared/.../hicar/HiCarNcmUsb.kt` | USB 侧：选接口、切备用设置、`GET_NTB_PARAMETERS`、bulk 收发（实现 `NtbPort`）|
| `shared/.../hicar/HiCarNcmFraming.kt` | NTB16 组帧/拆帧、出向布局、参数解析、`SET_NTB_INPUT_SIZE` 的取值 |
| `shared/.../hicar/HiCarEthernet.kt` | 字节序、校验和、以太网头、ARP、IPv4 检查、以太网端点 |
| `shared/.../hicar/HiCarNcmDataPath.kt` | 两个搬运线程：bulk ↔ NTB16 ↔ 以太网 ↔ TUN |
| `shared/.../hicar/HiCarWiredVpn.kt` | `VpnService` + TUN 描述符（`poll` + 唤醒管道）|

数据流：

```
手机 ──bulk IN──▶ NTB16 拆帧 ──▶ 以太网端点 ──ARP 应答──▶ bulk OUT
                                      └──IPv4──▶ TUN ──▶ 内核 ──▶ TCP 4005/4006/4007
TUN ──IPv4──▶ 以太网端点加头 ──▶ NTB16 组帧 ──bulk OUT──▶ 手机
```

ARP 必须由我们应答：TUN 是三层设备，没有以太网头，内核也不会替它回 ARP；手机每条包之前都要
先解析车机地址，没有应答它一条也发不出来。

## 三、几个必须对的细节

- **地址一致**：91 字节信息里写的「车机地址 : 端口」就是手机要拨的地址，TUN 必须建在同一地址上
  （`192.168.66.100–254`，随机取一个）。两者不一致时手机连不上，而两边都不会报错。
- **NTB 布局**：NDP16 偏移是 `wNdpOutAlignment` 的整数倍，数据报偏移满足
  `offset % wNdpOutDivisor == wNdpOutPayloadRemainder`。手机报 4/0/4 时是 NDP16@12、数据报@28。
- **短帧补齐**：ARP 应答只有 42 字节，补齐到 60 并按 60 声明长度，否则有的对端整帧丢弃。
- **整包对齐**：块长正好是端点包长的整数倍时多补 1 字节，否则对端要等一个零长包才认为传输结束。
- **接收上限**：Android 9 以前单次 `bulkTransfer` 上限 16384 字节，手机报的块更大时要先
  `SET_NTB_INPUT_SIZE` 压下来，否则块被截断，看起来像「帧格式错误」。
- **关闭顺序**：先 `wakeup` TUN、等两个搬运线程退出，再关 TUN 与 USB。旧系统上并发关闭 USB 连接
  不安全，文件描述符关早了还可能被复用。

## 四、验证情况（如实）

**单元测试覆盖（`:shared`）**：`HiCarNcmTest` 19 个用例

- NTB16 组帧：签名、长度字段、NDP16 偏移、短帧补齐、整包补 1、拆帧回环；
- 坏签名/截断块在交出任何数据报之前拒绝；
- 出向布局按 4/0/4 与 8/0/8 计算，非法参数回退默认；
- `GET_NTB_PARAMETERS` 按文档偏移解析；接收上限与 `SET_NTB_INPUT_SIZE` 的决策；
- ARP 请求→应答的逐字节内容；给别的地址的 ARP 只计数不应答；探测帧忽略；
- IPv4 包投递时去掉以太网头；不是本机地址的不投递；TUN 出来的包加头；
- 校验和正负例；IPv6 帧按「不支持」处理而不是「格式错误」。

**实车上第一次插线（2026-11-27，宋 Plus 车机 + Mate 80 Pro Max）**：部分环节跑通了，整条没通。

| 环节 | 结果 |
| --- | --- |
| 91 字节 car-info | **被接受**：`car info 91 bytes -> 91 (address 192.168.66.178:4005)` |
| 手机重新枚举 | **发生了**：2 接口 → 3 接口（`/dev/bus/usb/001/012`） |
| 认出 NCM 布局 | **当时没认出**，日志写 `did not re-enumerate as NCM`，随后手机又退回 2 接口 |
| 新设备的 USB 授权 | **被拒**（`permission denied for /dev/bus/usb/001/013`）：重新枚举是新的设备对象，授权不会带过来 |

其中「没认出」这条，事后查明是我们自己的判断有漏洞，不是手机没切：

`awaitNcm` 用 `serialNumber` 过滤候选设备，而**刚枚举出来的设备还没被授权，读 serial 会抛异常**。
strict 比较于是把「正在等的那个设备」排除掉，循环空转到超时。已改成读不到 serial 时不排除
（`sameSerial`），并把等待上限从 8 s 放到 15 s。

另外这次日志把「接口数量」升级成**逐个接口的 class/subclass/protocol**，并在超时时列出端口上每个
华为设备及其布局（`CDC-NCM` / `RNDIS` / `CDC 但不是 NCM` / 厂商自定义）。下次插线日志能直接回答
「手机切到了什么」，不用再猜。

**仍未验证**：

- 手机切出来的 3 个接口到底是不是 CDC-NCM（上面的改动让它有机会被认出，但还没有一次成功日志）；
- 91 字节信息里的地址手机是否真的去拨；
- `VpnService` 授权对话框在这台车机上的样子、TUN 与 USB 线程的实际吞吐；
- 模拟器没有 USB host，以上一项都替代不了。

因此设置页里写明「还没有在实车上跑过」。有线现在**默认开启**，并且优先于无线（见下一节）；
关掉后插线只做诊断枚举。切换开关要重启接收端才生效（`HiCarWiredSettings`）。

## 四点五、2026-11-28：与 OpenHC 逐项对照后改了什么

第二份实车日志（`hicar-log-20261128-175523`）里 car-info **根本没发出去**：请求了 USB 授权，
之后再没有任何授权结果，手机始终停在普通布局（接口 `06/01/01` PTP + `08/06/50` MTP，日志里没记 pid）。
拿 OpenHC 的 `HuaweiPhone` / `CarInfoSender` / `WiredSession` / `DeviceId` 逐条对：

| # | OpenHC 的做法 | 我们原来的做法 | 结论 |
| --- | --- | --- | --- |
| 1 | 只在产品号 **0x107D / 0x107E / 0x107F** 时发 car-info；0x5000（开着 USB 调试）不发并提示关掉 | 任何华为设备都发 | **改成「照样发」**：拒绝发送是拿别人的实测当这台手机的状态，日志里既没 pid 也没有 ADB 接口（FF/42/01），手机根本没开调试。现在的做法是任何非 NCM 的华为/荣耀设备都发，pid 与返回值原样记进日志——发错只是被手机忽略，不发则无从诊断 |
| 2 | block 里的 device id 是 `SHA-256(大写十六进制文本)` | 对 10 个原始字节做 SHA-256 | **真差异**：device id 与蓝牙握手不是同一个值，手机读到的是「不认识的车」 |
| 3 | 每次插入只发一次；发过后要拔线等 5 秒 | 每次 attach 广播都重试 | **真差异**：同一次插入里反复发，手机全部忽略 |
| 4 | 等 NCM 时排除切换前就存在的旧 NCM 设备 | 只按 serial 匹配 | **真差异**：新设备未授权时读不到 serial，候选被自己排除（11-27 的「没切成 NCM」） |
| 5 | 授权等待有超时，且用 `hasPermission` 复核广播内容 | 只等广播 | **真差异**：广播丢了就永远不动（11-28 的直接原因） |

### 授权对话框为什么一次都没回

`hicar-log-20261128-175523` 第 84、95 行两次 `asking the system for permission`，全文再无
`USB: permission granted/denied`，也没有任何 `car info` 行。两次请求都发生在 `onCreate` 里
（`receiver started 17:51:34` 之后紧接着就去要设备），此时本应用的窗口还没显示。USB 授权框是
一个系统 Activity，Android 10 的后台启动限制会把这种请求静默丢掉——既没有对话框，也没有回调。

现在的处理：`HiCarWiredLink.setHostVisible()`，由 `HiCarHostActivity.onResume/onPause` 驱动。
窗口不可见时请求排队并记一行日志；可见后立刻发第一次，之后每 4 秒最多补一次，共 3 次；
每次请求都记 `ask N/3, window visible|hidden`，超时行会带上总共问了几次。

现在的实现：`PhoneMode`（pid 与接口，只作描述）、`HiCarCarInfo` 统一派生 device id（有单测）、
`maySpendThisPlug()` 一次性门控、`awaitNcm(stale)` 按设备名排除旧设备、`handOver()` 广播 +
每 250 ms 轮询 + 60 秒超时 + 窗口可见才提问。car-info 发送重试 3 次、间隔 300 ms，返回字节数
必须等于 91。

**还没对上的**：OpenHC 的「插线时不再弹窗」靠的是**清单里给 USB 过滤器挂一个 activity 别名**，
系统弹「要用 OpenHC 打开吗」时带「默认使用」勾选，勾一次以后系统当场授权。我们目前只有 receiver，
那条链走不通，所以每次插入仍要在车机屏幕上点一次「允许」。这是下一轮要做的。

### 四点六、19:21 那份日志：手机确实切成 NCM 了，卡在 TUN

第三份日志（`hicar-log-20261128-192117`）把上一节的两个猜想都终结了：

```
wired: already on the port (... pid=0x107e, 2 iface: 06/01/01, 08/06/50) — a product id OpenHC measured as answering the block
wired: asking the system for permission ... (ask 1/3, window visible)
USB: permission granted
wired: car info 91 bytes -> 91 (address 192.168.66.155:4005)
USB: device attached (001/017, pid=0x107e, 3 iface: 02/0d/00, 0a/00/01, 0a/00/01) — already CDC-NCM
wired: the phone switched to the NCM layout
```

- **pid 是 0x107e**，不是 0x5000：「是不是开着 USB 调试」这个问题到此为止；
- 窗口可见后授权框正常弹出、`91 -> 91` 被接受、手机重新枚举出 **CDC-NCM 控制口 + 两个数据口**；
- 也就是说，权限时机、car-info、枚举识别这三段现在都通了。

卡住的是接着的两行：

```
USB NCM: could not open /dev/bus/usb/001/017; another app may hold it
wired: no tunnel, so the cable carries nothing
```

两件事：

1. **NCM 是新的设备实例，授权不继承。** 切换后立刻 `openDevice`，而 017 这个实例的授权还没到，
   于是报成「另一个应用占着」——误导。现在 `openAndRun()` 先查 `hasPermission`，缺就走 `handOver()`
   申请，不再把「没授权」说成「被占用」。
2. **VPN 授权还没给过。** `VpnService.prepare()` 仍返回授意 Intent，`HiCarWiredVpn.open()` 直接返回
   null，而那句原因只写进 `android.util.Log`（导出的报告里看不到），所以报告里只剩「no tunnel」。现在：
   - 授权框在 `onResume` 时**提前**弹出（赶在手机切换之前），答案经 `onVpnConsentResult()` 回到链路；
   - 已经打开的 NCM 口**保留**，授权通过后从 `beginCarrying()` 继续，不必拔插；
   - `VpnService` 自己的日志经 `logSink` 进会话日志（`VPN: tunnel at ...` / `establish returned null`）。

顺带修掉一个竞态：`startOnWorker()` 原来在忙时**直接丢弃**新步骤，于是「切换失败那一秒恰好到的
授权回调」会被吞掉。现在改为排队（最多一项，新的覆盖旧的）。

**仍是未验证**：TUN 起来之后，手机是否按 car-info 里的地址拨 TCP、ARP 是否被正确自答。
这份日志里 `connections=0`（手机完全没拨），符合「网卡没通所以 HiCar 不发起」。


## 五、与无线的关系：有线优先

有线与无线共用同一条上层链路：控制/业务 TCP 端口、DMSDP、HiChain、HiSight 都不变。有线只是把
「手机怎么到达车机」从 5 GHz 热点换成一根线。所以有线的风险集中在本轮新增的文件，不影响
已经实车验证过的无线路径。

仲裁由 `HiCarTransportArbiter` 表达，规则只有四条：

| 事件 | 之前 | 动作 |
| --- | --- | --- |
| 数据线开始搬包 | 无线 | 关掉热点、无线 DMSDP、HiSight、音频；在 `192.168.66.x` 上重开三个端口 |
| 数据线开始搬包 | 有线 | 只按新地址重绑（重新枚举会换设备对象） |
| 拔线 | 有线 | 关掉有线端口，重开一轮无线（重新广播 + 等手机） |
| 拔线 | 无线 | 什么都不做 |

几点是刻意的：

- **蓝牙那一半不跟着断**。手机无论走 Wi-Fi 还是走线，都先在 0xFE35 上做 HiChain；所以有线接管时
  只拆「数据面」，RFCOMM 记录、广播和会话密钥都留着，否则手机还得重新走一遍发现。
- **有线时不发 Wi-Fi 凭据**。`servesWifiFollowUp = false`，控制口的 kind 4/33 帧只记日志不应答：
  没有热点，就不该交出任何网络信息。
- **有线时不等人**。无线那条路径会为了热点懒开等最多 30 秒（`awaitNetwork`）；有线没有热点要等，
  所以直接跳过，否则这个线程会被白等一轮。
- **有线期间不回连、不重启无线回合**。`scheduleReconnect` 在有线接管时只记日志：为了一条没在用的
  路径去拆正在出画面的回合，是这里最贵的错误。
- **按钮只管无线**。首页的按钮在无线开着时是「停止无线」，关掉后是「开始无线」；有线连接由数据线
  自己维持，按它不会中断画面。会话页的「停止」仍然是全部停止。
- **端口绑在隧道地址上**，不是热点地址；对端校验放宽到「同一 /24 内的另一台主机」，因为手机在这条
  链路上是自己选地址、只在 ARP 里报一次。

**这一节全部未实车验证**：手机插线后是否真的按 91 字节信息里的地址拨号、有线会话是否接受
`192.168.66.x` 上的 DMSDP、以及「无线正在投屏时插线」时手机是否愿意迁移过来，都要上车才知道。

## 六、来源

帧格式、ARP 规则、TUN 关闭顺序与 91 字节车机信息的字段布局来自 GGBondLab/OpenHC 0.2.0
（`platform/net/*`、`platform/usb/*`，GPL-3.0-only）。本项目同为 GPL-3.0，移植保留来源说明。
