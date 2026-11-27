# 安卓车机上的 HiCar 接收端

DiPlay 现在是一个 **HiCar 接收端**：让一台没有前装 HiCar 的安卓车机，扮演华为 HiCar 会话里的
「车」这一侧。CarPlay / AirPlay / iAP2 协议栈、HUD 与仪表盘输出、CarPlay 首页都已经从代码里删除，
剩下的是接收端本体、它的设置页，以及在一台没人试过的车机上把链路拉起来所需要的诊断。

包名 `com.anye1998.hiplay` · Android 7.0+（API 24）· GPL-3.0

![设置页：车速增益、按用途分开关、实时速度来源](docs/screenshots/hicar-settings-speed.png)

## 现状

这是实验性的接收端，下面把「有证据的」和「只在测试里跑过的」分开写。

- **无线链路已在开发车上跑通。** 比亚迪宋 Plus（Android 10）+ Mate 80 Pro Max：手机弹出 HiCar
  提示、六位连接码被接受、出画面、有声音、点按/滑动/返回/主页可用、配对过一次后免码回连。那一次
  的日志在 [docs/HICAR_MIGRATION.md](docs/HICAR_MIGRATION.md) §7.49。
- **之后新增的功能只在编译和单测里跑过，还没有上车。** 多指触控、清晰度/帧率档位、音频按用途分路
  与声道选择、扫码配对、热点懒开、车机主动回连、恢复车机自己的 Wi-Fi、随速增益（音乐/导航/通话）、
  有线 NCM 通路，都是「编译通过 + 单测覆盖」。哪些还没有上车，设置页的「关于」一节写明了。
- **有线 HiCar 没有端到端验证。** 还没有一次与手机的完整有线会话，见
  [docs/HICAR_WIRED.md](docs/HICAR_WIRED.md)。
- **HUD 与仪表盘输出没有实现。** 它们属于 CarPlay 时期的功能，随那套代码一起删除了。

## 它能做什么

### 连接

1. 车机在 BLE 上广播 HiCar 服务（`0xFDEE`），屏幕上显示六位连接码。
2. 手机发现车机并弹窗，驾驶员在手机上输入连接码。
3. RFCOMM `0xFE35` 上跑 HiChain。车机用连接码证明自己 —— 不需要华为签发的配件证书，也不需要
   任何账号服务器。
4. 认证通过后车机开 5 GHz 热点，手机接入，控制通道转到 TCP `4005`/`4006`/`4007`。插着数据线时
   不开热点：同样的三个端口改绑在线的隧道地址上，控制/画面/声音协议完全不变。
5. 画面走 HiSight/RTSP，H.264 解码后画进 `TextureView`；触摸和按键走一条 AES-128-CTR 加密的
   UIBC 通道回传。

连接页上还有一个二维码，带车机的蓝牙地址、机型码和连接码，手机扫码即可，不必手输；地址还是占位
值时只显示一句原因，不画一个「扫了也连不上」的码。

车机自己的蓝牙地址是这套身份的基础（本机标识 = `SHA-256(机型码 + 地址)`），但普通应用问不到：
Android 6 起 `BluetoothAdapter.getAddress()` 只给 `02:00:00:00:00:00`。所以设置 →「连接」里有一个
**从系统读取本机蓝牙地址**：走本应用已经授权的 adb 通道，先读系统设置里存的地址，没有再问蓝牙服务，
读到就直接填进输入框。地址一改标识就跟着变，手机需要重新配对一次。

### 连接中

- **显示方式**：全屏、状态栏、状态栏+导航栏。画面按手机的分辨率等比缩放到剩下的空间，永不拉伸；
  切换模式不需要断开连接。手机的画面尺寸是在连接建立时一次谈定的，所以当前会话只重新排布窗口，
  下次连接才会按「面板减去可见系统栏」的区域重新协商；想马上应用，用设置页里的重连按钮。
- **连接中改设置**：设置页是**叠在**会话画面上的一层，和接收端在同一个 Activity 里。第二个
  Activity 会让画面所在的 surface 暂停，镜像就黑了 —— 这是它不做成独立页面的原因。
- **方向盘与媒体键**转给手机（播放/暂停、上一首、下一首及其余标准媒体键）：手机在能力应答里声明
  接受时才转发；关掉开关，按键立刻归车机。
- **多指触控**：一个触摸包携带全部手指的快照，手机地图上的双指缩放可用。
- **画面质量**：三档清晰度，帧率可跟随屏幕或固定 30/60。这两项在连接建立时一次性回答手机，改动
  从下次连接生效，设置页写明了这一点。
- **按用途分路的声音**：音乐、导航、提示音、通话、铃声各自有解码器、队列和 `AudioTrack`，导航
  播报可以走车机自己的导航声道，音乐仍留在音乐声道。每条通道可以钉到一个旧式 stream number
  （0 = 自动），并用一段短音试听。
- **随速增益**：音乐、导航、通话可以各自跟随同一条速度曲线（起始速度、满档速度、上限 dB），增益
  直接乘在接收端解出来的 PCM 采样上，不动车机和手机的系统音量。设置页会显示**当前真正在用的
  来源** —— 车辆总线、定位、信号中断时的保持、或没有 —— 以及当前速度和增益。导航与通话各有
  独立开关。车辆总线要读车机的 adb shell，设置页「连接」里有 **获取 ADB 授权** 按钮：一键把本应用
  的公钥交给 adbd，已信任时立即通过，未信任时车机弹一次「允许 USB 调试吗」；无线 adb 常开就不用插线。
- **数据线优先**：插上线就断开无线数据通路、改在线的隧道地址上服务同样的三个端口；拔线后自动
  重开无线。有线期间不开热点、不回答 Wi-Fi 凭据、也不为热点空等。整条有线链路仍未实车验证，
  见 `docs/HICAR_WIRED.md`。
- **桌面上第二个入口**：「HiCar 设置」图标打开同一个 Activity 并直接展开设置页，连接中想改设置
  不必在画面上找那个小入口。它是独立的入口 Activity，不是 `activity-alias`——alias 在不少桌面上
  只会显示一个图标。**注意**：覆盖安装后桌面可能只剩其中一个图标（桌面自己的行为，`pm` 里两条都
  在），把另一个从应用列表拖回桌面，或卸载重装即可。

### 收尾

- 热点在手机认证通过之后才开：车里没有手机时，接收端不会白占车机的 Wi-Fi。
- 已知手机的蓝牙链路一上来，车机可以主动回拨它 —— 这是对「手机自己不会拨」的唯一答案。
- 停止接收端时，会把车机自己的 Wi-Fi 要回来。
- 可选的无障碍服务替用户按系统 USB 授权弹窗上的「允许」；它只认同时提到 USB 且有确认按钮的窗口。
- **有线 HiCar** 在用户态实现（CDC-NCM 接口、NTB16 组帧、自答 ARP、`VpnService` TUN、两条传输
  线程），因为这台车机的内核没有 `rndis_host`。默认关闭，未验证。

## 环境要求

| | |
| --- | --- |
| 车机 | Android 7.0+（API 24），允许安装 APK，蓝牙 4.0+ 且能发 BLE 广播，Wi-Fi 能开 5 GHz 热点 |
| 手机 | 支持 HiCar 的华为手机。开发机是 Mate 80 Pro Max |
| 车速（车辆总线） | 厂家自己的轮速通道。比亚迪上是 `autoservice`，需要 adb 或 root；取不到就用定位 |
| 车速（定位） | 精确定位权限，且能看见天空。隧道里保持最后速度约 45 秒，然后淡回不增益 |

## 安装

装在**车机**上，不是手机。debug 包与正式包同名（`com.anye1998.hiplay`），版本名带 `-debug` 后缀，
所以两者不能并存：装 debug 会覆盖正式包。

1. 把 APK 拷到车机并安装（文件管理器或 `adb install`）。
2. 打开 **HiCar**，同意它申请的权限（蓝牙广播/连接、Wi-Fi、定位）。**「位置」这一项不能省**：
   Android 10–12L 上开 5 GHz 热点和扫蓝牙都由它门控，缺了它手机会把认证走完却拿不到网络，
   界面只会显示「开不了热点」。连接页会直接列出还缺哪一项，并给一个「打开应用权限」的入口；
   换包名重新安装后权限会回到未授予，需要再给一次。
3. 先在车机和手机的「系统蓝牙设置」里完成一次普通配对 —— 参考接收端要求这一步，配过之后再连
   成功率高得多。
4. 点 **开始**，在手机上输入六位连接码，或扫二维码。

也可以反过来：**直接插数据线**。设置里「插线自动启动」默认打开，插上就自动打开接收端并走有线，
不用先点图标；如果车机不允许后台拉起界面，会改成一条通知，点一下即可。有线优先于无线 —— 插线时
无线让位，拔线后自动回到无线。连接页那个按钮只管无线（「停止无线」不会中断正在走线投的画面）。

连接失败时，屏幕和导出的日志会说明停在哪一步。从 **设置 → 诊断 → 导出日志** 导出；Android 10+
写到 `Downloads/DiPlay`。报告里故意包含连接码（「码被拒」本身就是被排查的失败）。

## 构建

```bash
export JAVA_HOME=/path/to/jdk-21-or-newer
./gradlew :shared:testDebugUnitTest :common:compileDebugKotlin :mobile:assembleDebug
```

`mobile` 是 APK，`samples/*` 是无关的演示应用。debug 产物是
**`mobile/build/outputs/apk/debug/mobile-debug.apk`** —— 已经用本机的 debug 密钥签过名，可以直接装。
（不需要再 `zipalign` / `apksigner`；如果产物叫 `…-unsigned.apk`，说明签名配置又丢了，见下。）

签名身份要固定：HiChain 的配对密钥存在 `app_hicar-huks` 里，是用应用 UID 加密的，而 UID 跟签名
绑定。换密钥重新签一次，车机上原有的配对就作废，手机下一次认证会失败。所以：

- **只用一台电脑构建**，debug 密钥就是 `~/.android/debug.keystore`；换机器前把它一起拷走。
- 有车载专用 keystore 时，用环境变量覆盖（四个都要给）：

```bash
export ANDROID_KEYSTORE_PATH=/path/to/car.jks
export ANDROID_KEYSTORE_PASSWORD=…
export ANDROID_KEY_ALIAS=…
export ANDROID_KEY_PASSWORD=…
./gradlew :mobile:assembleDebug
```

release 构建只有旧的 CarPlay 身份需要 `DIPLAY_AUTH_ASSETS_DIR`，这个接收端不用它。没给那四个
变量时 `assembleRelease` 产出的是未签名包，**装不上**，这是刻意的：用一个临时密钥签 release
只会让配对密钥在下次构建后失效。

`automotive` 与地图示例是 CarPlay 时期的遗留，不属于接收端。

## 文档

- [界面、显示模式与桌面上的两个入口](docs/HICAR_UI.md)
- [协议侦察与车机能力边界](docs/HICAR_RECON.md)
- [迁移日志：每一次真实尝试，附日志行](docs/HICAR_MIGRATION.md)
- [方向盘按键](docs/HICAR_KEYS.md) · [有线 HiCar](docs/HICAR_WIRED.md) · [车机 adb 判定清单](docs/HICAR_ADB.md)
- [在电脑上能测到什么（以及测不到什么）](docs/HICAR_REMOTE.md) · 远程调试脚本 `scripts/hicar-live.sh`
- [从 OpenHC 借鉴了什么、明确不做什么](docs/OPENHC_COMPARISON.md)
- [CarPlay 删除：边界与进度](docs/CARPLAY_REMOVAL.md)
- [变更日志](CHANGELOG.md) · [第三方声明](docs/THIRD_PARTY_NOTICES.md)

## 来源与许可

基于 [xcertplay](https://github.com/shilapi/xcertplay)，GPL-3.0。接收端移植了
[huducat586/hicar-android-receiver](https://github.com/huducat586/hicar-android-receiver)（MIT）与
[GGBondLab/OpenHC](https://github.com/GGBondLab/OpenHC)（GPL-3.0-only）的做法：媒体键转发、多指
触摸快照、画面档位、音频分路、二维码配对格式、用户态 NCM 与主动回连方向。HiChain 来自
OpenHarmony 的 `deviceauth_lite` 与 `huks_lite`（Apache-2.0），编进 `libhicarauth.so`。分发修改版
时请保留这些声明。

HiCar 是华为的产品；本项目与华为、比亚迪没有隶属或背书关系。只在你拥有且允许安装软件的车辆上
使用。
