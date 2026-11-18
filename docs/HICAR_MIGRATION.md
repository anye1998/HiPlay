# HiCar 接收端迁移：实现状态、能力缺口与后续步骤

本文件对应分支 `feat/hicar-migration`。侦察结论见 [HICAR_RECON.md](HICAR_RECON.md)。
本文把"已验证"和"写完但未验证"分开写，并明确列出做不到的部分。

结论先行：**这个分支交付的是"协议层真实、可编译、可单测的 HiCar 车机侧骨架"，不是能连上手机出画面的成品。**
HiChain 鉴权已按公开实现集成：`:hicar-auth` 模块把 OpenHarmony 的 `deviceauth_lite`（HiChain Lite）与
`huks_lite`（软件密钥库，Apache-2.0）编成 `libhicarauth.so`（四个 ABI 全部编出并打进 APK），Kotlin 侧已接线，
首连靠车机显示的六位连接码授权，**不需要华为签发的配件证书**。
但**没有在真机上与手机完成过一次真实 HiChain 交换**——这条链路是本次唯一"写完了但没验证过"的部分，
其余未完成项见第 2 节。

---

## 1. 已实现并通过单测的部分

| 层 | 实现 | 测试 |
| --- | --- | --- |
| 发现 | `HiCarBeacon`：0xFDEE 服务数据（4 字节型号 + 轮转序号）；`HiCarBleAdvertiser`：可连接、不配对、低延迟广播 | `HiCarFramingTest` |
| RFCOMM | `HiCarRfcommServices`：**insecure** 注册 0xFE35/0xFE36 两条服务记录，反射读取业务通道号，读不到就失败而不是猜端口；**全程不调用 `createBond`** | `channelFromDescription` 用例 |
| 帧格式 | `HiCarFraming`：`0x7F` 标记、LEB128（终结位在高位）、TLV、CRC-16/MODBUS（小端）、增量解码器（半包缓存、协议违规即终止） | 10 个用例 |
| 参数协商 | `HiCarChannelNegotiator`：CCMP 版本 / 摘要 / 分帧三问三答，seen 掩码凑齐才允许业务通道申请；回包带动态通道号与 FE36 UUID | 6 个用例 |
| Wi-Fi 协商 | `HiCarNetworkOffer` + `HiCarNetworkNegotiator`：能力位校验（cap2/cap4/cap5 频率掩码）、数据端口分配、选网与角色、凭据串按列下发 | 9 个用例 |
| 控制通道 | `HiCarControlSession`：DMSDP 文本记录、`CryptoSwitch: 222` 切换 AES-128-CTR、心跳、时钟参数、`$` 二进制业务帧 | 9 个用例 |
| 录制加密 | `HiCarRecordCipher`：16 字节随机 IV + AES-128-CTR，32 字节密钥裁到 AES-128 | 同上 |
| 编排 | `HiCarController`：先起热点 → 注册两条 RFCOMM 记录 → 广播 → 双通道 accept → 协商/鉴权；状态经 `HiCarStatus` 上报 | 手工/真机待验 |
| 界面 | `HiCarHostActivity`：启动/停止、状态、日志（屏幕 200 行环形、导出保留 5000 行）、权限申请、**导出日志 / 分享** | `HiCarLogReportTest` 7 个用例 + `HiCarExportUiTest` 端到端 |

日志导出（真机带回问题的唯一入口）：

- 点 **Export log** 把本次会话写成 `hicar-log-<yyyyMMdd-HHmmss>.txt`，走应用既有的
  `DiagnosticExportStore`：Android 10+ 先写 `Downloads/DiPlay`（MediaStore，无需权限），失败再退到
  公共 `Download/DiPlay`、`Android/data/<包名>/files/diagnostic-reports`、最后应用私有目录；
  界面会把实际落盘路径显示出来，取不到时用 **Share** 走系统分享。
- 报告头部含包名/版本、Android 版本与机型、广播用的 model、identity、六位连接码、当前状态；
  **连接码是故意写进去的**（"码被拒"本身就是需要排查的失败），文件里因此带一行提醒按敏感文件处理。
- 屏幕只留 200 行是为了车机上可读，导出保留最近 5000 行；真被截断时报告里会写"最早 N 行已丢弃"，
  避免把一份短日志误读成一次短会话。
- 每次 Start/Stop 都会往历史里写一条分隔行，所以连续重试的多次尝试在同一份导出里可分辨；
  **Clear log** 会同时清掉历史（用户明确要求干净），`Reset pairing` 只清屏幕、保留历史。

关键设计取舍（都写进了代码注释）：

- **失败即关闭**：`HiCarUnavailableAuthenticator` 在收到第一条 HiChain 消息时抛 `HiCarAuthException`，
  绝不返回伪造密钥。伪造密钥会让会话"看起来连上了"，但用手机不共享的密钥加密，必然在后面失败。
- **不猜端口**：业务通道号来自系统绑定结果，读不到就整体失败。固定端口在部分机型上"看起来能用"，
  在另一部分机型上表现为手机连不上，更难排查。
- **凭据列格式校验**：SSID/密码中出现 `#`、控制字符直接拒绝建网，因为凭据串用 `##` 分列，
  一个 `#` 会让手机解析出错误的密码。
- **2.4 GHz 直接拒绝**：手机能力位只覆盖 5180–5825 MHz，2.4 GHz 热点会在协商阶段被拒，
  表现成"手机没反应"，所以建网阶段就报错并说明原因。

---

## 2. 未完成 / 做不到的部分（逐条如实记录）

### 2.1 仍未闭环的部分

| 缺口 | 原因 | 后果 |
| --- | --- | --- |
| **HiChain 真机验证** | 引擎已编入 APK、Kotlin 已接线（见第 6 节），但没有真机与华为手机做过一次完整交换 | 无真机证据前只能算"代码完成"，会话可能停在 `Blocked` |
| **HiSight 投屏会话** | 依赖已认证会话的密钥；且 RTSP 语义（ANNOUNCE/OPTIONS/SETUP/PLAY、RTP→PES→ES 解密）尚未实现 | 无画面 |
| **音频** | 同上：AAC 上下行都走会话密钥加密的媒体通道 | 无声音 |

### 2.2 侧载 APK 下无法完整工作的能力

| 能力 | 侧载是否可行 | 说明与降级方案 |
| --- | --- | --- |
| 画面 + 触控/按键 | 可行（不依赖签名，只依赖 HiChain 引擎） | 待 2.1 的原生引擎集成完成后即可 |
| 音频（媒体/导航） | 部分可行 | 降级：HiSight 解密出的 PCM/AAC 走车机**公开**音频接口（`AudioTrack` + `USAGE_MEDIA`），不做厂商私有通道；音量为系统媒体音量 |
| 麦克风上行 | 可行但体验受限 | 走 `RECORD_AUDIO` + 公开输入源，回声消除可复用项目已有的 speexdsp 通路 |
| 通话音频 | 不可行 | HiCar 通话走蓝牙 HFP/厂商通话通道，侧载无法介入；如实标记为不支持 |
| 方控（方向盘键） | 不可行 | 依赖厂商输入服务（本项目已为 CarPlay 做过 Geely/BYD 适配，但那不是 HiCar 通道） |
| 仪表 / HUD 导航卡片 | 不可行 | 华为私有投屏子通道，无 SDK 不可得；现有 BYD HUD 实现只解析 CarPlay 的导航数据 |
| 免输码回连 | 部分可行 | 依赖 HiChain 的凭据持久化（HUKS 存 `info1.data`/`info2.data`）；参考实现在重启后回连仍不可靠，属未解决问题 |

**不使用"应该可以"这类表述**：上表中"不可行"的三项在侧载条件下没有任何公开接口，只能在
厂商预装（系统签名 + HiCar 认证）的前提下才可能具备。

### 2.3 代码里的 TODO 位置

- `HiCarAuth.kt`：`HiCarAuthBlocker` 三个枚举值即三条阻断原因（引擎未集成 / 六位码未被信任 / 需厂商签名）；
  `HiCarUnavailableAuthenticator` 是替身实现，收到第一条 HiChain 消息即抛异常。
- `HiCarController.startChain()` 之后没有"认证成功 → 建立 DMSDP → 开 TCP 监听 → HiSight"的分支：
  这条分支在拿到会话密钥前无法测试，故未写死。`HiCarController.kt` 末尾的 `openListener` 是给该阶段预留的入口。
- 界面文案未做本地化：`HiCarHostActivity` 里是硬编码英文，理由写在类注释里（Settings 的翻译测试会强制 12 个语言变体）。

---

## 3. 检查更新模块的镜像策略

| 阶段 | 顺序 | 说明 |
| --- | --- | --- |
| 查询 | `api.github.com` →（失败）→ `raw.githubusercontent.com/…/main/version.json` → `ghfast.top` → `gh-proxy.com` | **不对 API 走公共代理**：公共镜像通常不转发 `api.github.com`，代理只会返回错误页 |
| 下载 | 直连 GitHub → `ghfast.top` → `gh-proxy.com` → `v4.gh-proxy.org` | 每个源失败后自动切换，原因写进日志与 `UpdateAvailability` |
| 校验 | 每个源独立校验 SHA-256（Release 的 `SHA256SUMS.txt` 或清单内联 `sha256`） | 不匹配即删除该文件并切下一个源；**没有任何摘要的 Release 直接拒绝** |
| 版本比较 | SemVer 2.0（含预发布排序），从字符串中提取版本号 | 不依赖 Release 名称格式，`v0.2.15`、`DiPlay 0.2.15` 等价 |
| 更新源仓库 | `anye1998/HiPlay`（本 fork） | 原为上游 `shihabal3amri/DiPlay`：上游 Release 不含 HiCar 代码，且签名与本项目不同，装不上 |
| 测试构建 | **不检查更新**（`UpdatePolicy`，按 `FLAG_DEBUGGABLE` 判定） | debug 版签名与 Release 不同，覆盖安装必然 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；且 `0.2.22-hud-test` 按 SemVer 低于 `0.2.22`，提示"有新版本"在形式上正确、实际是死路 |

`version.json` 格式（由 tag 工作流生成，签名成功时才发布）：

```json
{
  "version": "v0.3.0",
  "apk": "mobile-release.apk",
  "url": "https://github.com/<owner>/<repo>/releases/download/v0.3.0/mobile-release.apk",
  "sha256": "<64 位十六进制>",
  "notes": "Published from tag v0.3.0"
}
```

字段别名：`version`/`tag_name`/`tag`、`apk`/`apk_name`/`asset`、`url`/`apk_url`/`browser_download_url`、
`sha256`/`apk_sha256`、`checksums`/`checksums_url`。

**仓库坐标**：`UpdateRepository` 的默认值是上游 `shihabal3amri/DiPlay`；把本分支发布到自己的 fork 时，
只需改 `UpdateMirrors.DEFAULT_OWNER / DEFAULT_REPOSITORY / DEFAULT_BRANCH` 三处。

---

## 4. GitHub Actions

`.github/workflows/android-release.yml`

| 触发 | 产物 |
| --- | --- |
| push 到 `main` | debug APK（Artifact `debug-apk`）+ release APK（Artifact `release-apk`） |
| 手动 `workflow_dispatch` | 同上 |
| push `v*` tag | 同上，并创建 GitHub Release，附 APK + `SHA256SUMS.txt` + `version.json` |

- **签名缺失不影响构建**：`mobile/build.gradle.kts` 只在 `ANDROID_KEYSTORE_PATH` 指向真实文件时才注册
  签名配置，否则产出 `mobile-release-unsigned.apk`；未签名的 tag 构建**不会**发布 `version.json`，
  以免把无法安装的包推给自动更新。
- **失败阶段可见**：构建日志经 `tee` 落盘，失败时跑 `scripts/build_stage_report.py`，
  输出 `FAILED STAGE: DEPENDENCY RESOLUTION | NDK BUILD | KOTLIN COMPILE | RESOURCES / PACKAGING | LINT`
  并以 `::error` 注解显示在 Actions 摘要里。
- `[skip ci]` 由发布作业写入 `version.json` 提交时带上，避免触发循环构建。

需要的 Secrets（可选）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

---

## 5. 本地验证记录

- 环境：Windows 11 + JDK 25（`gradle/gradle-daemon-jvm.properties` 要求 25）+ Android SDK 37 + NDK 28.2.13676358。
- `:mobile:assembleDebug` 成功（24 MB APK，含 `BLUETOOTH_ADVERTISE`）。
- `:mobile:assembleRelease` 成功，未配置签名时产出 `mobile-release-unsigned.apk`。
- `:shared:testDebugUnitTest --tests "com.shilapi.xcertplay.hicar.*"`：36 个用例通过。
- `:common:testDebugUnitTest --tests "com.shilapi.xcertplay.update.*"`：73 个用例通过。
- 已知本地环境问题 1：首次切到 release 变体时 `configureNdkBuild*` 偶发
  `hash_key.txt 拒绝访问`（Windows 文件占用），重试即通过；CI 在 Linux 上未观察到。
- 已知本地环境问题 2：**`:mobile:lintDebug` 与 assemble 任务放在同一次 `./gradlew` 调用里，
  在本机会在 lint 写完 SARIF 后假死**（无输出、无文件写入、客户端空等，日志停在
  `Wrote SARIF report` 之后）。已复现两次，根因未定位到 AGP/lint 内部，但形态稳定。
  因此本地验证改用 `scripts/verify.sh`：**每个阶段一次 Gradle 调用 + 单阶段超时 + 分阶段日志**
  （`build/verify/<stage>.log`），超时会被明确报成 `STALLED` 并自动 `./gradlew --stop`，
  不再需要人肉判断"是慢还是死了"。`--quick` 只跑单测与 debug APK。
  CI 不受影响：workflow 里 lint 不与 assemble 同调用，且 job 已设 `timeout-minutes`。

---

## 6. HiChain 集成方案调研（两个参考项目都有，且是同一套）

**结论：有。** 两个参考项目都集成了 HiChain，而且用的是同一份**公开上游源码**，不是华为 SDK：

| 项目 | 做法 | 证据（本地克隆） |
| --- | --- | --- |
| `huducat586/hicar-android-receiver` | 把 OpenHarmony 的 `deviceauth_lite` + `huks_lite` + securec 编成 `libcarlink_hichain.so`，用 107 行 JNI 暴露 `init/create/step/sessionKey/close` | `native-auth/sources/**`（97 个 `.c`、53 个 `.h`）、`native-auth/runtime/{CMakeLists.txt,compat.c,android_jni.c}`、`native-auth/dependency-lock.json` |
| `Aki894/HelloCar`（CarLink） | 同一套 C 库，Rust 侧用 `libloading` 动态加载 | `native/sources.json`（同样的三个 commit）、`src/hicar/native.rs` |

钉住的上游版本（两边一致）：

- `openharmony/security_deviceauth` @ `552edb521d0bd7c17f2dab2791e7cdc3de284405`
- `openharmony/security_huks` @ `89f0842ef2600a0d9b5351ef11e1f8acab8ff439`
- `openharmony/third_party_bounds_checking_function` @ `ebef58f44419d0694e984d25acac231e737d60ff`
- 依赖库：mbedtls 2.28.9、openssl 3.5.4、cJSON 1.7.19（参考项目按 SHA-256 校验压缩包后才解包）
- 许可：`native-auth/sources` 中 113 个文件带 Apache License 头（Apache-2.0，与本项目 GPL-3.0 兼容）

### 为什么侧载 APK 能跑通

因为 HiCar 首次连接走的是 **PIN（PAKE）模式**，不是证书模式：

- `create(selfAuthId, peerAuthId, pin, role=2)`，`package_name = "com.huawei.hicar"`，`service_type = "CarDevice"`，密钥长度 16 或 32；
- `pin` 是**车机自己生成的六位数字**，显示给用户，由手机输入；双方各自用 PIN 派生出同一把会话密钥；
- HUKS 在这里是**软件密钥库**：文件回调只允许 `info1.data` / `info2.data` / `hks_keystore` 三个名字，落在 app 私有目录（0700）；UDID 由调用方给的种子做 SHA-256 得到，参考实现传的是本机持久化的 `localId`；
- 所以**不需要华为签发的配件证书**，也不需要 MFi 那种身份。此前本文件把"缺证书"当成阻断原因，是错的，已更正。

### 已落地的实现（本分支已完成）

按"引上游源码、自己写适配层"的方向做完了，没有复制参考项目的适配代码：

1. **独立模块 `hicar-auth`**（`com.android.library` + `externalNativeBuild.cmake`），与 `shared` 现有的
   ndkBuild（speexdsp）互不干扰。
2. **源码由脚本拉取、不入库**：`scripts/fetch-hichain-sources.py` 钉住三个上游 commit 与两个归档的 SHA-256，
   支持 `--verify` / `--clean`；四个依赖：`security_deviceauth`、`security_huks`、`third_party_bounds_checking_function`、
   `mbedtls v2.28.9`，外加 `cJSON v1.7.19`（deviceauth_lite 的 JSON 层要用）。
   拉取后 7.6 MB、189 个 `.c`。
3. **自写 JNI** `hicar-auth/src/main/cpp/hichain_jni.c`：`init/create/step/sessionKey/close`，
   HUKS 存储用 `openat + O_NOFOLLOW + 三个文件名的白名单`，目录必须 `0700`；
   `confirm_receive_request` **只放行 BIND(1) 与 AUTHENTICATE(2)**，`REMOVE_AUTHINFO/UNBIND/SEC_CLONE` 一律拒绝。
4. **编掉了 OpenSSL**：`_CUT_AUTHENTICATE_` 是上游自带的裁剪开关，去掉的只是 x25519 辅助函数（huks_lite 里唯一
   直接 include OpenSSL 内部头 `ec_local.h` 的地方）。成品 `.so` 的 `NEEDED` 只有 `libc/libm/libdl/liblog`。
5. **Kotlin 接线**：`HiChainAuthenticator` 实现 `HiCarAuthenticator`；`HiCarIdentityExchange` 把 `oper_type=3`
   的报文原样转给引擎（含 message 17 的 `keyLength`），密钥只从 `sessionKey()` 取；
   拿到密钥后 `HiCarController` 在同一 socket 上开 DMSDP 控制通道（`HiCarStatus.Authenticated` → `ControlChannelReady`）。
6. **界面**：`HiCarHostActivity` 展示/持久化六位连接码，`Reset pairing` 会清空 `files/hicar-huks` 并换码
   （否则手机信任的还是旧码对应的密钥）。
7. **CI 防回归**：workflow 先拉源码再构建，并在构建后断言 APK 里确实存在 `lib/arm64-v8a/libhicarauth.so`
   与 `lib/armeabi-v7a/libhicarauth.so`——避免"绿了但引擎没进去"。

### 仍然没做的（按重要性）

1. **真机验证**：需要一台华为手机 + 车机，走"显示码 → 手机输码 → 绑定 → 再认证"。没有真机证据前，
   不要把这条链路描述为可用。首次绑定后手机的信任记录保存在 HUKS 文件里，重启/清数据后的行为未验证。
2. **HiSight 投屏**：RTSP/RTP→PES→AES-CTR 与渲染未实现，`HiCarControlSession` 的 `onApplicationService`
   目前只记录日志。
3. **音频**：同样依赖媒体阶段的密钥，未实现。
4. 32 位/Android 15 车机、16 KB page size 的真机验证。

### 已知风险（来自参考项目自己的记录，不是推测）

- 参考实现明确写"重启后自动回连仍未可靠解决"，不要把它当作已完成能力；
- 部分测试设备**首次需要先在系统蓝牙设置里做普通配对**才行（与协议层"不 bonding"的描述冲突，属厂商蓝牙栈行为），我们的实现里不能假设一定不需要；
- 32 位车机、Android 15 车机在参考项目中未实测；本项目要求 4 个 ABI + Android 15 的 16 KB page size；
- HUKS lite 的接口与上游 commit 强绑定，升级上游要重新验证；
- APK 体积与启动开销会增加（mbedtls/openssl/cJSON + 三个上游子集）。

---

## 7. 附：这台平板上 CarPlay 为什么起不来（与 HiCar 无关）

在 HUAWEI MRX-AL09（MatePad，Android 12 / API 31）上首次实测，现象是：首页显示"认证不可用"，
点连接没反应（弹一句同样的提示就返回）。已定位，与 HiCar 改动无关：

1. `DiPlayActivity.connect()` 开头就有 `if (setupError != null) { toast(setupError); return }`，
   所以"发起不了连接"是认证失败的**后果**，不是另一个 bug。
2. `setupError` 来自 `DiPlayBootstrap.ensure()`：它要把 `assets/offline-mfi/identity.pk8` 与
   `assets/offline-mfi/certificate.p7b` 拷进私有目录再 `LocalMfiAuthenticationClient.load()`。
   这两个是 **MFi 凭据**，按上游 `docs/BUILD.md` 的要求是**构建时用 `DIPLAY_AUTH_ASSETS_DIR` 注入的外部资产**，
   仓库里没有，普通 `:mobile:assembleDebug` / `assembleRelease` 也不会带上。
   实测我们产出的 APK：`unzip -l` 里没有任何 `assets/offline-mfi/*` → 拷贝时抛 `FileNotFoundException`。
3. 也就是说：**任何没有注入 MFi 凭据的构建，CarPlay 侧都不可能连上**，这与 HiCar 是两条独立链路
   （HiCar 用 HiChain 的 PIN 派生密钥，不碰 MFi）。即使注入了凭据，iPhone 是否信任该证书仍是另一回事，
   `LocalMfiAuthenticationClient` 的注释写明"签名只证明密钥自洽，不证明 iPhone 信任这个身份"。

为此做的两处改动（不改变行为，只是让其可解释/可进入）：

- 诊断报告里 `CarPlay setup:` 那一行现在会带上真实原因（`setupFailureDetail`），
  不再是干巴巴一句 `authentication unavailable`。
- HiCar 界面新增桌面图标 **HiCar（实验）**（`activity-alias`，独立入口，不进 DiPlay 主界面；原名 HiCar (experimental)，7.32 起汉化），
  平板上不接 adb 也能直接打开。原来的 `am start` / 自定义 action 仍然可用。

### 7.1 第二次实测：连接其实已经发起，卡在等 MFi 协处理器

同一台平板、换了新 APK 后再导出报告（15:05），关键差异是 `CarPlay setup: ready`，日志里出现：

```
Host started; MFI target=USB/CH341; transport=wireless
mfi discovery backend=CH341 devices=[UsbDeviceId(vendorId=6790, productId=21778)]
mfi CH341 not listed by Android; USB devices=[]
STEP mfi/wait: MFi coprocessor not present; polling
```

解读：

1. `MFI target` 的默认值是 **LOCAL**（`AirPlayPersistence.loadMfiTarget`），持久化里却是 `USB/CH341`，
   说明这个值在设置里被改过（`CarPlayHostActivity` 的 MFi 单选项会 `saveMfiTarget`）。
2. 选 `USB/CH341` 时 `DiPlayBootstrap.ensure()` 直接返回，所以 `setupError` 变空、首页显示 "ready"，
   `connect()` 也就不再被拦——**连接确实发起了**。
3. 但 `0x1A86:0x5512`（CH341）只出现在 `/sys` 的扫描结果里，Android 的 USB Host 列表是空的，
   于是控制器在 `WaitingForMfi` 无限轮询。这个"无限"是上游有意的：CH341 允许连接后再插。
4. 结论：这台设备上两条路都是死路——`LOCAL` 缺 `assets/offline-mfi` 凭据，`USB/CH341` 缺 MFi 协处理器芯片。
   CarPlay 不作为本项目的验证目标；要在平板上推进，只能走 "HiCar (experimental)" 那个入口。

附带发现并修掉的问题：更新检查在**测试构建**上给出了一个做不到的提议。上游 Release `v0.2.22` 确实比
`0.2.22-hud-test` 高（SemVer 把 `-hud-test` 当预发布），但 debug 包签名不同、装不上，且上游 Release 不含 HiCar 代码。
现按 `FLAG_DEBUGGABLE` 直接跳过检查，并把更新源仓库改为本 fork。

### 7.2 第三次实测（HiCar 侧首次真机日志）：卡在 2.4 GHz 热点

首次拿到 HiCar 屏幕自己的导出日志（`hicar-log-20261113-153824.txt`，23 秒后导出）：

```
--- receiver started 15:38:01 ---
LocalOnlyHotspot starting with Wi-Fi client enabled=true
LocalOnlyHotspot reservation band=2.4 GHz channel=0
```

状态停在 `preparing the access point`。定位结论：

1. 链路**确实在跑**：热点 → RFCOMM → 广播的顺序里，热点是第一环，卡在这里。
2. 根因是**频段**：HiCar 只接受能力位里的 5 GHz 信道（`HiCarNetworkOffer.FREQUENCIES` 为 5180–5825，
   与参考实现 `NetworkResource.frequencyMask` 完全一致），而 **Android 12 及更早的公开
   `startLocalOnlyHotspot` 无法指定频段**，系统自己选了 2.4 GHz。这台平板上这条路没有解。
3. 参考实现在 API < 33 时根本不走 LocalOnlyHotspot，而是用 **Wi-Fi Direct** 指定频率建组
   （`P2pHotspot.java`：`setGroupOperatingFrequency(5745)`）；API ≥ 33 才改用新公开接口
   `startLocalOnlyHotspot(SoftApConfiguration)` 指定 5 GHz。

**修复**（本分支）：

- `HiCarAccessPoints`：按顺序尝试 **Wi-Fi Direct（`WifiP2pChannels.AUTO_5_GHZ`）→ LocalOnlyHotspot**，
  谁先落在 5 GHz 就用谁；被拒的后端**先 close 再试下一个**（否则残留的组占着射频，下一次失败原因会被污染）；
  每次拒绝都带原因，失败日志形如
  `HiCar could not open a 5 GHz access point: Wi-Fi Direct failed (...); local-only hotspot settled on 2412 MHz...`
- `HiCarController.hotspot` 类型从 `LocalOnlyHotspotManager` 改为 `WirelessHotspotManager`（两个后端共用接口）。
- 复用了仓库里已有的 `WifiP2pGroupManager`（CarPlay 的 P2P 后端，优先 5 GHz、返回 SSID/口令/BSSID/频率/本机地址），
  没有新写一套建网代码，也不调用 `onCarPlayConfirmed()`（该回调只影响"记住配置"，不影响建组）。
- 界面权限从"蓝牙两项"扩展为：API ≥ 33 加 `NEARBY_WIFI_DEVICES`，否则加 `ACCESS_FINE_LOCATION`
  （Wi-Fi Direct 在 API 31 受位置权限约束）；被拒时会**列出具体哪几项**，不再只说一句"权限未授予"。
- 单测 `HiCarAccessPointsTest` 6 个用例，直接覆盖这台设备踩中的形态：
  `local-only hotspot settled on 2412 MHz → 关闭它 → 改用 Wi-Fi Direct 的 5745 MHz`。

### 7.3 第四次实测：5 GHz 通了，卡在 BSSID 占位地址

第二天用新 APK 再测（`hicar-log-20261114-114233.txt`），热点这一环**已经过了**：

```
Wi-Fi P2P preflight wifiEnabled=true locationEnabled=true permissionGranted=true stationMHz=5805 fiveGhzSupported=true
Wi-Fi P2P create mode=ALIGNED_5_GHZ frequencyMHz=5805
Wi-Fi P2P ready mode=ALIGNED_5_GHZ band=5 GHz channel=161 frequencyMHz=5805 verified=true
HiCar access point from Wi-Fi Direct at 5805 MHz
HiCar access point rejected: HiCar AP BSSID
```

解读：

1. Wi-Fi Direct 建组成功，5 GHz 信道 161 / 5805 MHz，`verified=true`；说明上一轮的建网改造有效。
2. 下一环立刻失败：`HiCarNetworkOffer` 的 BSSID 校验拒绝。原因是 `WifiP2pGroupManager` 从
   `NetworkInterface.getHardwareAddress()` 拿到的 p2p 接口地址是 Android 的占位值 `02:00:00:00:00:00`，
   而 HiCar 的凭据串必须带**真实** BSSID（占位地址会让手机"找不到网络"，在车机侧看起来像手机的问题）。
3. 参考实现对此有专门处理：`OwnGroupAddress.read()` 同时读 `NetworkInterface` 与
   `/sys/class/net/<iface>/address`，两者一致才采用；都不行时**明确失败并拒绝发送占位地址**
   （`NetworkResource.Offer.ssidOnly` 虽然支持"无 BSSID"，但在参考项目里从未被调用，属未验证的死代码，不能用）。

**修复**（本分支）：

- 新增 `HiCarAccessPointBssid`：`usable()`（格式、单播位、排除 `02:00…`/`00:00…`）、
  `choose()`（两源必须一致，不一致就不猜）、`resolve()`（优先 `NetworkInterface` 值，缺失或占位时读
  `/sys/class/net/<iface>/address`，接口名先过白名单正则再拼路径）。
- `HiCarController` 在构造 `HiCarNetworkOffer` 前先解析 BSSID；解析不到就报 `Failed("hotspot bssid")`、
  关闭本轮热点、并写明确日志（`framework=…, interface=…`），**不发送占位地址**。
- 单测 `HiCarAccessPointBssidTest` 9 个用例，含这台设备的形态：framework 给 `02:00:00:00:00:00`、
  sysfs 给真实地址 → 采用 sysfs；两源冲突 → 放弃；接口名含路径字符 → 不读文件。

### 7.4 第五次实测：BSSID 两个来源都断了，同时踩到 removeGroup 的 BUSY

装上带 `HiCarAccessPointBssid` 的 APK 再测（`hicar-log-20261114-115903.txt`），热点仍是 5 GHz 建组成功，
BSSID 这一环报出了具体内容：

```
HiCar access point from Wi-Fi Direct at 5805 MHz
HiCar cannot read this group's BSSID (framework=02:00:00:00:00:00, interface=p2p-p2p0-2); refusing to send a placeholder
Wi-Fi P2P remove rejected code=2 reason=Wi-Fi P2P is busy
```

三个结论：

1. **新增的失败日志起作用了**：这次能直接读到接口名 `p2p-p2p0-2`，不用再猜。
2. **两个来源都不可用**：框架给占位 `02:00:00:00:00:00`；`/sys/class/net/p2p-p2p0-2/address` 也读不到
   （华为在 Android 12 上封了这条路径，或该 netdev 名与 sysfs 项不一致）。
3. **`WifiP2pGroupManager` 里其实还有一个没被尝试的来源**：`group.owner.deviceAddress`。它写在
   `bssid = interfaceHardwareAddress(...) ?: group.owner?.deviceAddress` 的兜底位上，而占位地址**不是 null**，
   于是兜底永远不触发。

第四个现象是收尾时的 `remove rejected code=2 (BUSY)`：刚建组就拆，框架必然先回 BUSY，组便留在原地占着射频，
下一次 Start 就得走"回收旧组"的路径。

**修复（本分支）**：

- **四来源解析 + 逐源说明**：`HiCarAccessPointBssid.inspect()` 依次尝试
  框架接口地址 → sysfs → **内核接口表（新增 `libnetiface.so`）** → IPv6 链路本地 EUI-64 → `owner.deviceAddress`，
  失败时把每个来源的结果写进日志，例如
  `sysfs=FileNotFoundException; kernel=unavailable; link-local=interface p2p-p2p0-2 is gone; owner=absent`。
- **直读内核**：`NetworkInterface.getHardwareAddress()` 的占位值是**框架层屏蔽**的结果，不是内核没有这个地址。
  新增 `hicar-auth/src/main/cpp/netiface_jni.c`（`getifaddrs()` + `sockaddr_ll`），
  拿到的就是 `ip link` 显示的值——包括 Android 为 Wi-Fi Direct 分配的随机化地址。四个 ABI 各 ~24 KB
  （stripped 4–8 KB），`NEEDED` 只有 `libc/libm/libdl`。独立 `libnetiface.so`，不与 HiChain 引擎共命运。
- **占位地址不再挡路**：`WirelessHotspotInfo` 新增 `groupOwnerDeviceAddress`（`bssid` 语义不变，CarPlay 不受影响），
  作为最后一档来源；它本身是占位值时同样拒用。
- **两源冲突的判定改了**：原来"冲突即放弃"，现在以 **sysfs 为准**并标注 `SYSFS_OVER_INTERFACE`。
  理由是同名接口的 sysfs 值来自内核，框架值可能是接口重建后的缓存视图；这不是"猜"，是选更权威的那个来源。
- **`removeGroup` 对 BUSY 重试一次**（600 ms 后），避免失败尝试把组留在射频上。

**仍未验证的部分（不夸大）**：`libnetiface.so` 只验证了编译、符号导出与依赖，**没有在设备上跑过**；
即使拿到真实 MAC，也要确认它就是该组实际广播的 BSSID（Android 10+ 对 Wi-Fi Direct 做 per-group 随机化，
内核接口地址通常正是广播值，但本项目没有真机证据）。`owner.deviceAddress` 是持久身份，可能与广播地址不同，
故排在最后一位且日志会标注。

### 7.5 第六次实测：链路全通，卡在手机不认识这台车机

`hicar-log-20261114-123104.txt` 是本项目第一次把**接收端整条链路拉起来**：

```
Wi-Fi P2P ready mode=ALIGNED_5_GHZ band=5 GHz channel=161 frequencyMHz=5805 verified=true
HiCar access point BSSID 32:59:B5:15:B1:22 from MAC decoded from the interface's IPv6 link-local address
HiCar BSSID other sources: framework=02:00:00:00:00:00 (placeholder); sysfs=FileNotFoundException; kernel=unavailable; owner=02:00:00:00:00:00 (placeholder)
HiCar access point ready on 5 GHz, control port 4005
HiCar records ready: business channel 7, control on 0xFE35
HiCar advertisement started (11 bytes of 0xFDEE service data)
HiCar listeners started: hicar-control, hicar-business
```

四点解读：

1. **BSSID 是 EUI-64 算出来的**：`32:59:B5:15:B1:22` 首字节 `0x32` 的本地管理位置位，符合 Android 为 Wi-Fi Direct
   分配的随机化地址特征；`HiCarNetworkOffer` 接受了它，说明格式与信道都过关。**注意这不是"读到"的**，是从接口的
   IPv6 链路本地地址反解出来的（`ff:fe` 标记存在才成立）。
2. **内核来源仍然失败**（`kernel=unavailable`）。上一轮加 `libnetiface.so` 就是为了它，因此这一轮把它改成了可分辨的
   三态：库未加载 / 内核表里没有该接口 / 接口有但无 `AF_PACKET` 项。三种原因的修法完全不同。
3. **`owner` 也是占位值**，说明华为对 Wi-Fi Direct 设备地址同样做了屏蔽，不只是 `NetworkInterface`。
4. **手机端不弹窗**（用户实测）。广播已成功、两个 RFCOMM 记录已监听，但手机没有反应——问题在**发现阶段**，不在协议层。

**参考实现的关键差异（这一轮才看清）**：两个参考项目都不是"广播一开就完事"：

- `hicar-android-receiver`：`MainActivity` 先发 `ACTION_REQUEST_DISCOVERABLE`，**返回成功后才** `startFdee()`；
- `HelloCar`（BlueZ）：adapter 属性里 `Discoverable=true`、`LocalName="HelloCar"`。

也就是说，**设备必须同时对 BR/EDR 可被发现**。0xFDEE 载荷（11 字节）里只有型号和序号，**没有车机 BR 地址**，
手机拿到广播后要靠 inquiry 去找到这台设备的 BR 地址；设备不可被发现，广播就是"看得见、够不着"。

**修复（本分支）**：

- `HiCarHostActivity`：点 Start 时先检查 `scanMode`，不是 `SCAN_MODE_CONNECTABLE_DISCOVERABLE` 就发
  `ACTION_REQUEST_DISCOVERABLE`（300 秒），返回后再启动接收端；已可发现则直接启动。另加 **Discoverable + start**
  按钮，便于不停止会话地重新驱动。没有蓝牙设置 UI 的设备会记录"无法请求可发现"并继续（广播与热点本身仍有测试价值）。
- `HiCarBleAdvertiser`：启动广播时打印 `address / mode / name / payload` 十六进制，把"没人反应"变成可解释的日志。
- `HiCarController`：每 30 秒一条心跳
  `HiCar waiting for the phone: 90s, connections=0, advertising=on, listeners=open`，
  区分"手机从未尝试"与"手机连了但失败"——此前导出日志在这两种情况下一模一样。
- `HiCarAccessPointBssid` 的 `kernel=` 原因改为具体三态。

**必须说清的未验证点**：两个参考项目**自己也标注 FDEE 广播是未验证的实验**
（ref1 的日志原文：`FDEE 广播启动成功：11 字节 service data；这不是 HiCar 连接成功`
与 `未验证手机识别`）。所以"手机认识这个广播"这件事，在本项目和两个参考项目里都**没有成功证据**；
可发现性是目前最能解释"手机不弹窗"的差异，但不是已被证实的充分条件。
如果加上可发现性后手机仍不弹窗，下一个变量是**型号字节**（当前 `61460BEC` 是本机随机生成的，
参考项目的测试向量用的是 `00040A04`），手机可能用型号去匹配它的设备库。

### 7.6 第七次实测：Start 恢复，但广播被权限挡住

`hicar-log-20261114-130043.txt`（12:58 启动、13:00 导出）带来三条硬信息：

```
HiCar records ready: business channel 7, control on 0xFE35
HiCar needs the nearby-devices Bluetooth permission
Discoverable request granted for 120s; press Start to advertise   (点了两次)
```

1. **点 Start 不再闪退**（用户确认）。上一版的闪退来自 Start 里直接拉起系统可发现界面，已把它移出 Start。
2. **广播没有发出去**：`records ready` 说明 RFCOMM 建记录成功，也就说明 `BLUETOOTH_CONNECT` 是有的；
   因此这里的 `SecurityException` 只能是 **`BLUETOOTH_ADVERTISE` 未授予**。链路走到了最后一步才被挡，而日志原文
   只写"需要权限"，不点名是哪一项。
3. **可发现性拿到了**，但系统把请求的 300 秒压成 **120 秒**（日志两次 `granted for 120s`）。也就是说：
   点了"Make discoverable"之后必须在 120 秒内让手机发起连接，否则窗口过期，手机又能看到广播却找不到地址。

另外 BSSID 这次是 `02:DC:03:2B:48:D0`，来源仍是 IPv6 EUI-64；内核来源报
`kernel=p2p-p2p0-5 has no AF_PACKET entry`——接口在、但没有 `AF_PACKET` 项，说明 bionic 的 `getifaddrs()`
在该版本上不返回链路层地址（`libnetiface.so` 本身加载成功了，这从错误措辞能区分出来）。

**修复（本分支）**：

- **权限点名**：SecurityException 分支会列出缺的具体权限（`missing: android.permission.BLUETOOTH_ADVERTISE`），
  全部已授予时改为打印平台给的原始消息，不再只说"需要权限"。
- **广播器先查权限**：`startAdvertising` 之前显式检查 `BLUETOOTH_ADVERTISE`，缺了就写一行明确日志并返回 false；
  同时把 `address/mode/name/payload` 这段诊断日志包进 `runCatching`——上一版它自己就会抛 SecurityException，
  是"诊断代码把功能搞挂"的典型。
- **BSSID 基名兜底**：框架给 `p2p-p2p0-5`、内核只认 `p2p0` 时，sysfs 与内核读取都会再试基名；
  若内核值与 IPv6 EUI-64 反解值一致，标注为 `KERNEL_AND_LINK_LOCAL`（两个独立内核视图互相印证，
  这是当前能拿到的最强证据）。
- **心跳带上可发现性**：`discoverable=yes / no (connectable only)`，这样 120 秒窗口过期在日志里是可见的，
  而不是看起来像"手机不理我"。

**下一步的唯一判据**：先让平板授予"附近的设备"权限（系统设置 → 应用 → 权限），
确认 `HiCar advertisement started` 出现、心跳里 `advertising=on`；再在 120 秒窗口内用手机发起连接。

### 7.7 第八次实测：广播起来了，但无法判断它是否真的在空口

`hicar-log-20261114-131827.txt`：

```
HiCar records ready: business channel 7, control on 0xFE35
HiCar advertisement started (11 bytes of 0xFDEE service data)
HiCar listeners started: hicar-control, hicar-business
HiCar waiting for the phone: 30s, connections=0, advertising=on, listeners=open, discoverable=unknown
... 每 30 秒一条，直到 240s
```

**这次广播真的启动了**（`advertisement started`），可发现性也授予过（120s），但 4 分钟内
`connections=0`，手机端始终没有弹窗。同时暴露两个诊断缺陷：

1. **`HiCar advertisement: address=...` 整行缺失**。这一行在 7.6 版新增，理论上应打印在
   `advertisement started` 之前。它没出现，说明 `describeAdapter` 里的某个 getter 抛了异常，
   又被 `runCatching` 整体吞掉——**诊断代码自己吞掉了诊断信息**。
2. **`discoverable=unknown`**。`getScanMode()` 要么抛异常，要么返回了未列举的值，而代码只回落到
   字符串 `unknown`，没有带上原因或原始值。

也就是说，此刻"广播到底有没有真的发到空口、内容是不是我们以为的 11 字节"这件事，**无法自证**。
手机沉默既可能是它过滤掉了载荷，也可能是广播根本没出去——两者在日志里完全一样。

**修复（本分支）**：

- `HiCarAdvertisementProbe`：广告启动后用平板**自己扫自己**（20 秒，`ScanFilter` 只匹配 0xFDEE），
  结束时给出三种明确结论之一：
  - `no 0xFDEE advertisement heard ... so discovery is not reaching the air`（广播没出去）；
  - `our advertisement is on the air (N of M match the payload we sent); a phone that ignores it is filtering on content`
    （广播出去了，手机是按内容过滤）；
  - `M 0xFDEE advertisements heard, none matches our payload` + 最多 5 条样本（地址/RSSI/原始字节）。
  这把"猜"换成了一次可读的判定。其单测覆盖无蓝牙射频时不得抛异常、`running` 必须为 false。
- `BLUETOOTH_SCAN` 权限（`neverForLocation`）已声明并加入运行时申请，缺失时探针明确报"需要权限"而不是静默跳过。
- `describeAdapter` 改为**逐字段保护**：`address` / `mode` / `name` 各自 try/catch，失败的字段打印异常类名，
  例如 `address=SecurityException`。一个被拒的 getter 不再让整行消失。
- `discoverableState()` 带上原因或原始值：`unknown(SecurityException: ...)`、`unknown(scanMode=35)`、`unknown(no adapter)`。

**这一步的意义**：在此之前，每一轮都在"手机为什么不理我"上打转；有了自扫描，下一份日志会直接落在
"广播没出去"或"广播出去了但手机按内容过滤"其中一支，而这两支的后续动作完全不同
（前者查广播参数/射频，后者查载荷与型号字节）。

### 7.8 第九次实测：终于拿到广播器的真实状态，而我的上一条判定是错的

`hicar-log-20261114-133444.txt` 补上了 7.7 里缺失的那一行，并暴露顺序问题：

```
HiCar advertisement: address=02:00:00:00:00:00, mode=connectable, not discoverable,
                     name='HUAWEI MatePad Pro', payload=01 01 01 03 61 46 0b 04 ec 10 06
HiCar advertisement started (11 bytes of 0xFDEE service data)
HiCar self-scan started for 20s; looking for our own 0xFDEE data
Discoverable request granted for 120s; press Start to advertise      ← 在广播之后
HiCar self-scan: no 0xFDEE advertisement heard in this window ...
HiCar waiting for the phone: 30s ... discoverable=yes
HiCar waiting for the phone: 150s ... discoverable=no (connectable only)   ← 120s 窗口过期
```

三点，其中第一点是对我自己上一轮措辞的更正：

1. **"自扫描没听到"不等于"广播没发出去"**。射频收发互斥，很多控制器也不把本机发出的 ADV 回报给主机。
   7.7 那句 `so discovery is not reaching the air` 是**过度断言**。探针已改为**不带过滤器**扫描，并把结论分成
   四种：完全没听到任何广播（扫描器自身没工作）、听到其它设备但没有 0xFDEE（无法区分"没发"与"自己听不到"，
   需第二台设备判定）、听到与我们相同的载荷（确定在空口）、听到别的 0xFDEE（附样本）。
2. **顺序错了**：这次是 `Start`（广播）之后才 `Make discoverable`，也就是广播的头 120 秒是在**不可发现**状态下发的。
   手机即便收到广播也无法用 inquiry 拿到 BR 地址。界面现在会在 Start 时检查并提示
   `Not discoverable yet ... Press 'Make discoverable' first`。
3. **`address=02:00:00:00:00:00`** 说明框架对本机 BR 地址同样屏蔽——而 11 字节载荷里不含地址，
   所以手机只能通过 inquiry 找车机。这条路径成立的前提就是"可被发现"。
   ***已更正（见 7.10）**：手机取的是 BLE 广播的"广播地址"，不做 inquiry；可发现性不是必要条件。*

**另一个需要记录的硬限制**：`BluetoothAdapter.getBluetoothClass()` 不在公开 SDK 中，应用**连本机 CoD 都读不到**，
更不可能把它改成"车载音响"。平板的 CoD 属于"计算机"类，如果手机的 HiCar 只接受车载类设备，这就是侧载无法绕过的
限制。**目前没有证据表明它确实在按 CoD 过滤**，所以只作为风险记录，不作为结论。

### 7.9 一个与 HiCar 无关的既有 flaky 用例（如实记录）

本地 `:common:testDebugUnitTest`（1754 用例）多次全量运行中，`CarPlayHostClusterStartupTest >
aReplacementSessionDetachesTheOldClusterSurfaceUntilItsOwnMainFrame` 失败过 **2 次**，报
`TooFewActualInvocations`（`setSurface(111, surface)` 期望 2 次、实际 1 次，`CarPlayHostClusterStartupTest.kt:178`）。

- 单独运行该类 **2 次均通过**，整包重跑也通过；失败只出现在全量并发时。
- 该用例测的是 CarPlay 集群投屏的挂载/卸载，与本次 HiCar 改动无交集（本次未修改 `CarPlayHostActivity`、
  `AndroidMediaSink`、`CarPlayController`）。
- 已定位到可疑的时序点，但**没有据此改上游测试**：`dismissClusterPresentation()` 会把 `clusterSurface` 置空，
  而 `ensureClusterPresentation()` 只有在 `clusterSurface != null` 时才调用 `setSurface`；测试直接注入
  `clusterSurface` 字段，因此断言成立与否取决于这两条路径中哪一条在并发下被触发。
  这是上游测试依赖内部字段的脆弱点，修复它需要改动 CarPlay 侧行为，不属于本次任务范围。

处理方式：记录在此，不通过重跑或跳过掩盖；CI 若因此偶发变红，应按"重跑该 job"处理，而不是改断言。

### 7.10 第八次实测：广播确认在空口，手机缺的是"BR 地址"

`hicar-log-20261114-142324.txt` 与手机端 nRF Connect 截图（14:22）是这一轮的两份证据。第一次拿到**第三方证明广播真的发了出去**：

```
HiCar advertisement started (11 bytes of 0xFDEE service data, device name in the scan response)
手机端: HUAWEI MatePad Pro  65:4C:79:F7:29:33  -59 dBm
        Service Data: UUID 0xFDEE Data 0x0101010361460B04EC100A
        Complete Local Name: HUAWEI MatePad Pro
```

三点解读：

1. **"N/A 行"的歧义解决了**。上一轮手机截图里有三个 `N/A`，无法指认哪个是我们的；把名字放进扫描响应后，
   手机直接显示 `HUAWEI MatePad Pro`，payload 与我们发出的 11 字节逐字节一致（序号从 `08` 走到 `0A`）。
   **广播在空口，手机收到了。** 这句话现在有证据，不再是推断。
2. **`F8:AF:05:1B:02:90` 结案**。新的自扫描逐台报告带出了名字 `华为AI音箱 2e-04113`——它是隔壁的华为音箱，
   不是平板本身。上一轮"可能是系统自己的 0xFDEE 广播顶掉了我们的包"这个猜想**排除**。
3. **手机仍不弹框**，而且这次全程 `discoverable=no (connectable only)`。按下面的官方流程，可发现性**不是**必要条件。

**根因（有官方文档，不是推测）**：华为《HiCar 蓝牙》适配文档写得很直：

> 本方案有两处修改点：`btm_ble_set_random_address(*controller_get_interface()->get_address())`，
> Android 源码里此处使用的是随机地址，需要修改成上面代码片段中的内容；
> `p_cb->own_addr_type=BLE_ADDR_PUBLIC`，Android 源码里使用的 `BLE_ADDR_RANDOM`，需要修改成为 `BLE_ADDR_PUBLIC`。

同一文档给出的替代方案（`persist.sys.hicar.random.mac=true`）同样说明随机 MAC 时不够用：
开启后 `startAdv` 会**额外起一个 GATT Server**，手机连上 GATT Server 才从数据里拿到固定 MAC。
也就是说"随机地址"要么被改成公开地址，要么得配一条额外的私有通道——不存在"随机地址也能用"。

机制读起来是这样：**手机把 BLE 广播的"广播地址"直接当作车机的 BR/EDR 地址**，用它发起经典蓝牙连接，
连上（RFCOMM 免配对）之后才弹 HiCar 框。我们今天发出去的地址是 `65:4C:79:F7:29:33`，首字节 `0x65` 的最高两位是
`01`，按 Core 规范这是**可解析私有地址（RPA）**，不可能是任何真实设备的 BR MAC。手机拿这个地址拨号自然拨不到，
于是"包收到了、什么都没发生"——与观测完全吻合。

**这条要求在 App 层无法满足（已核实）**：

- `javap android.bluetooth.le.AdvertiseSettings$Builder`（`android-37.0/android.jar`）公开方法只有
  `setAdvertiseMode / setConnectable / setDiscoverable / setTimeout / setTxPowerLevel`；
  `AdvertisingSetParameters$Builder` 同样没有 `setOwnAddressType`。
- AOSP 源码里两者都是 `@hide @SystemApi`，注释原文：*"If used to set address type anything other than
  ADDRESS_TYPE_DEFAULT, then it will require BLUETOOTH_PRIVILEGED permission"*。
- 华为文档的落点同样是"修改 AOSP / 设置系统属性"，属于 ROM 侧改动。

**同时排除的一个旧猜想**：7.8 第 3 条"手机要靠 inquiry 找车机，所以必须 BR 可被发现"**不成立**——手机用的是广播地址，
不做 inquiry。本次 `discoverable=no` 与失败无关；可发现性只在配对/回连这类经典流程里才需要。

**这一轮加的东西**：

- `HiCarBleAdvertiser.requestPublicAddress()`：反射尝试隐藏的 `setOwnAddressType(0)`，成功与失败都写一行明确日志。
  失败时不假装成功，日志直接写明"广播地址是私有地址，手机没有可拨的 BR 地址"。
- 广播设置处注明 `setConnectable(true)` 就是 **ADV_IND**：华为文档明确警告非定向不可连接事件
  （`ADV_NONCONN_IND`）会让手机连上后仍不弹框，这一项我们本来就满足（legacy + connectable）。
- 自扫描报告逐台列出（地址 / 次数 / RSSI / 名字 / payload），这一轮正是靠它把"华为AI音箱"认出来的。

**剩余路线（都不在 App 层）**：

| 路线 | 前提 | 代价 |
| --- | --- | --- |
| 改 ROM：`own_addr_type = BLE_ADDR_PUBLIC`，并用控制器地址替换随机地址 | root / 定制 ROM | 与"侧载 APK"的目标冲突 |
| 华为 `persist.sys.hicar.random.mac` + 自建 GATT Server | 系统属性 + HiCar SDK 的 GATT 协议（未公开） | 协议不可得 |
| 换到能控制 HCI 的平台（Linux/BlueZ，即参考项目 `HelloCar` 的路径） | 非 Android 车机 | 偏离"Android 车机端"前提 |

**结论**：在未改 ROM 的普通 Android 平板上，HiCar 的 BLE 发现这一步走不通。原因不是我们的载荷、
不是权限、不是可发现性，而是"广播地址必须是 BR 地址"这个系统级要求。这条写进最终交付报告的能力缺口表，
并作为本项目**唯一已定位到根因的外部限制**记录在案。

### 7.11 第九次实测：地址这条路在 App 层彻底封闭，可发现性假设被实际运行排除

`hicar-log-20261114-144153.txt`（14:40 启动）+ 手机端截图（14:41）。三件事一次落定：

1. **隐藏 setter 连反射都不可达**：

   ```
   HiCar advertisement: the platform keeps a private advertising address (NoSuchMethodException);
   a HiCar phone reads the BR address from that field, so it has nothing to dial and never shows its prompt
   ```

   `NoSuchMethodException` 不是"反射被拒"，是**该方法在运行时不可见**（hidden API 黑名单）。也就是说
   `own_addr_type` 这条路不仅没有公开 API，连宿主的隐藏入口都摸不到——7.10 列的三条出路里，
   "App 层自己解决"这一条到此为止。

2. **地址确实在轮换**：14:22 手机看到 `65:4C:79:F7:29:33`，14:41 看到 `51:FA:3E:3D:72:72`。两次首字节
   （`0x65`、`0x51`）的最高两位都是 `01`，且值不同——这是**可解析私有地址（RPA）**的直接证据，不是"某个固定地址"。
   一个轮换的地址不可能等于任何设备的 BR MAC。

3. **可发现性假设被实际运行排除**：这一轮全程 `discoverable=yes`（14:22 那轮是 `no`），两次结果一样——不弹框。
   结合 7.10 的机制，这符合预期：手机用广播地址拨号，不做 inquiry，可发现性与发现阶段无关。
   至此"必须 BR 可被发现"这条**实测否定**，不再是"更可能的解释"。

**新增的一条观察**：自扫描这次听到 9 台 0xFDEE 广播，其中 4 台的载荷是
`06 05 a8 78 33 00 00 04 00 00 10 0e a5 d8 d0 09 d7 dd 85 e0`（20+ 字节），与我们的 11 字节格式完全不同。
这说明**0xFDEE 上并存多种载荷格式**；我们这 11 字节来自参考项目的测试向量，至今没有任何手机确认过它。
但要说清：**在广播地址修好之前，载荷正确性根本无法单独验证**——手机连"可拨的地址"都没有，
它有没有读懂载荷都看不出来。两个未知量不能在一台设备上分离。

**这一轮只加了一条诊断**（不解决根因）：广播前打印本机已配对设备（`bonds=`）。
理由是一个还没试过的假设——手机若与本机有 **LE 绑定**，就持有 IRK，能把 RPA 还原成身份地址（即 BR 地址），
这条地址才变得可拨。本机目前没有任何配对记录，所以这个假设还没被检验过；`bonds=` 让"配过没配过"在日志里可见。

**下一步的顺序（按代价从低到高）**：

| 顺序 | 实验 | 判别什么 |
| --- | --- | --- |
| 1 | 手机先在系统蓝牙里与本机配对，再点 Start | RPA 能否被 IRK 还原成可拨地址（本轮新增 `bonds=` 用于确认绑定已建立） |
| 2 | 把 APK 装到**非华为**的安卓手机上当接收端，用 nRF 看它的广播地址 | 别的 ROM 是否用公开地址；若是，可先在这台设备上验证载荷与整条链路 |
| 3 | 改 ROM（`own_addr_type = BLE_ADDR_PUBLIC`） | 唯一确定的解法，但需要 root/定制 ROM |

结论不变：**这台平板在未改 ROM 的前提下，HiCar 的发现阶段走不通**，且现在有三次独立实测支持这个判断
（地址为 RPA、地址会轮换、隐藏入口不可达）。

### 7.12 嗅探模式：把真车机当对照，而不是在我们的设备上继续猜

新增界面按钮 **Sniff 0xFDEE (60s)**：只扫描、不广播、不开热点、不听 RFCOMM。用途是把**真实车机**的
0xFDEE 广播原样读回来，包括我们改不了的那一项——广播地址。

判读方式（日志一次给全）：

```
HiCar sniff: 3 0xFDEE advertisement(s) captured; payloads below
  4C:xx:xx:xx:xx:xx x37 rssi=-48 name='...' data=.. .. .. .. 
  ...
```

1. **地址列**：真车机的广播地址等于它的 BR 地址，首字节最高两位不会是 `01`（私有）或 `11`（静态随机）。
   我们平板发出去的是 `01`——这一列就是 7.10 那个根因的直接对照。
2. **payload 列**：拿到真车机的完整字节，就能替换我们从参考项目抄来的 11 字节测试向量。
   在地址问题解决前，这是我们唯一能取得真值（ground truth）的办法。
3. **name 列**：真车机是否把车名放进扫描响应，决定手机弹框里显示什么。

如果车机有可用的 adb（USB 调试口、工程模式），还能拿到比广播更硬的证据：

- 开发者选项里开"启用蓝牙 HCI 信息收集日志"，跑一次连接，再 `adb bugreport` 取 `btsnoop_hci.log`，
  用 Wireshark 看 `LE Set Advertising Parameters` 里的 **own_address_type**——这是"公开地址还是私有地址"的
  最终判据，比从广播地址反推更直接。
- `adb shell dumpsys bluetooth_manager` 能看到适配器地址、广播注册与扫描状态。没有前装 HiCar 的车机上
  不存在"车机侧 HiCar 服务"，这一条看的是**蓝牙栈本身**，不是某个互联应用。

**注意（仅适用于自带 HiCar 的车机）**：那种车机上不要安装本 APK 去"试"。它自带的 HiCar 服务已经在用 0xFDEE
广播、5 GHz 热点和 RFCOMM 记录，我们的接收端会和它抢同一个射频与广播位；而且它的 HiCar 栈是系统签名的，
侧载 APK 既装不上，也代表不了我们的实现。那种车机的价值是**对照样本**，不是运行环境。

本项目的车机**两样都没有前装**（HiCar 与 CarPlay），所以结论相反：它没有自带服务可观察、也没有被占用的广播位，
**本 APK 就是它唯一的互联栈**，直接装上去跑才是正确用法（见 7.13）。

### 7.13 换到有 adb 的安卓车机：判定清单独立成文

车机（安卓、**无前装 HiCar、无前装 CarPlay**、有 adb）比平板更接近项目目标环境，而且 adb 让 7.10 的根因可以直接判定而不是推断。
判定步骤写在 **`docs/HICAR_ADB.md`**，核心只有一步：

> 在车机上跑本 APK 发出 0xFDEE 广播，用手机 nRF 看这条广播的 MAC，和 `dumpsys bluetooth_manager` 里的
> `Address`（BR 地址）比。**相等** → 7.10 的阻塞在车机上不存在，可以继续调载荷与 RFCOMM；
> **是 `0x4x–0x7x` 的私有地址** → 车机同样被封死，只能走改 ROM。

同时配了 HCI 取证法（`btsnoop_hci.log` 里 `LE Set Advertising Parameters` 的 `Own_Address_Type`），
这是"公开还是私有"的最终判据，不依赖任何推断。

为此本轮把应用日志**镜像到 logcat**（tag `HiCar`）：车机上 `adb logcat -s HiCar` 与界面日志逐行一致，
不用截图、不用导出文件。组件名也记在清单里（`com.shihab.diplay.hudtest/com.shilapi.xcertplay.HiCarLauncherAlias`）。

**适用范围更正（15:05）**：7.12 的"拿真车机当对照样本"前提是那台车机**自带 HiCar**。本项目实际使用的车机
**没有前装 HiCar**（用户确认），所以它没有可采集的 HiCar 广播——对它正确的做法是反过来：把本 APK 直接装上去当
接收端，并用 adb 判定广播地址（见 7.13 与 `docs/HICAR_ADB.md`）。

### 7.14 把取证做进 App：装上去、按两下、导出就有

车机没有前装 HiCar、也没有前装 CarPlay（用户确认 15:05/15:06），所以它没有"自带服务可观察"，
只能由我们自己的接收端去跑。为了让"跑一次 + 导出"就能拿到全部判据，本轮把 `docs/HICAR_ADB.md` 的 A–D 步
搬进了应用：

- **导出日志自带 `--- device audit ---` 段**（`HiCarDeviceAudit.collect()`），内容：
  系统版本/机型/指纹、蓝牙开关与名称与 scanMode、**三个来源的本机地址**（API、Settings.Secure、内核 `/sys/class/net/*/address`）、
  广播能力（`multipleAdvertisement`/`extendedAdvertising`/LE 2M/Coded/offloaded filtering）、已配对设备、
  权限授予情况、Wi-Fi 与 5GHz、系统特性（`bluetooth_le`、`wifi.direct` 等）、su 是否存在。
- **Device audit 按钮**：同一段直接打到界面，没有文件管理器的车机上拍照即可。
- **Root probe 按钮**：显式触发、**只读**，`su -c` 跑三条命令读 `bt_config.conf`（含 `LE_LOCAL_KEY_IRK` 是否存在的判定）
  与 `dumpsys bluetooth_manager` 的地址行。su 被拒时写明"未授予、未继续尝试"，不重试、不降级成猜测。
- 每条拿不到的值都写明原因（`masked by the platform`、`needs API 26`、`FileNotFoundException`），
  不静默省略、不用占位值假装读到。

**一个必须记录的方法论更正**：7.10 用"首字节最高两位是 01 → RPA"来判定地址不可拨。这个推理**太强**：
`4C:5E:0C` 既是真实 OUI，也符合私有地址的形状，光看字符串无法区分。可靠的两条判据是：

1. 首字节的 **I/G 位为 1（奇数）** → 不可能是公开地址（公开地址必然是单播）。我们实际看到的
   `65:…`、`51:…` 都满足这条，所以结论不变，理由更硬。
2. 首字节最高两位为 **`10`（0x80–0xBF）** → 不可能是随机地址的合法形式 → 只能是公开地址。

其余情况（`00`/`01` 开头且 I/G 位为 0）**是歧义的**，只能由扫描器打印的地址类型来定
（`LE_ADV_IND` 的 TxAdd 位）。`HiCarDeviceAudit.addressType()` 就按这三种情况输出，歧义时明确写"ambiguous"。

### 7.15 第一次上车机：广播被"不存在的权限"挡住（已修）

`hicar-log-20261114-203804.txt`，车机实测。环境一次看清：

```
build: Android 10 (SDK 29) / QUALCOMM qti trinket for arm64 / user/release-key
bluetooth: enabled=true name='宋Plus' scanMode=discoverable
adapter: multipleAdvertisement=true extendedAdvertising=true le2m=true leCoded=true offloadedFiltering=true
bonds: 2 (00:BB:1C:44:3D:FE 'anye的HONOR V30 PRO', F8:3B:7E:3E:DD:06 'anye的Mate 80 Pro Max')
features: bluetooth=true, bluetooth_le=true, wifi=true, direct=true, host=true
```

链路其实走得很远：

```
Wi-Fi P2P ready mode=BAND_5_GHZ band=5 GHz channel=48 frequencyMHz=5240 verified=true
HiCar access point BSSID 9a:bb:1e:f0:95:2d from interface only; sysfs unavailable
HiCar access point ready on 5 GHz, control port 4005
HiCar records ready: business channel 3, control on 0xFE35
HiCar advertisement needs the nearby-devices permission (BLUETOOTH_ADVERTISE)   <- 卡在这里
```

**根因是一个纯粹的版本判断错误**：`HiCarBleAdvertiser` 无条件检查 `BLUETOOTH_ADVERTISE`。这个权限
**在 API 31 才存在**，Android 10 上它根本不存在，`checkSelfPermission` 对不存在的权限一律返回 DENIED，
于是"权限齐全的车机"被判定为缺权限，广播一次都没发出去。而 `HiCarController.hasPermissions()` 里写了
`SDK_INT < 31 return true`，所以热点、BSSID、RFCOMM 记录全都正常建立——日志看起来"只差一步"，其实是差在一个假的缺口上。

顺带暴露的第二个问题：设备自检段把 API-31 的三个权限名列在 Android 10 上，`permissions missing:
BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE` 这句话**技术上为真、实用上是误导**——它描述的是
"平台没有这些权限"，不是"用户拒绝了"。

**修复**：新增 `HiCarPermissions`（`shared/.../hicar/`），把两个时代的规则收在一处：

| 能力 | API ≥ 31 | API ≤ 30 |
| --- | --- | --- |
| 连接 / RFCOMM | `BLUETOOTH_CONNECT`（运行时） | `BLUETOOTH`（安装时） |
| 广播 | `BLUETOOTH_ADVERTISE`（运行时） | `BLUETOOTH_ADMIN`（安装时） |
| LE 扫描 | `BLUETOOTH_SCAN`（运行时） | `ACCESS_FINE_LOCATION`（运行时） |

`runtimeRequests(sdk)` 只返回该版本**真的能弹窗**的权限；广播器/探针/控制器/界面/设备自检全部改用它，
日志里也按版本说明"这版 Android 需要哪个权限"。设备自检的权限段改成逐项列状态 +
一行 `not applicable on SDK 29: BLUETOOTH_CONNECT, ...`，不再把"不存在"说成"被拒"。

**同时修掉的诊断缺陷**：`interfaces: unreadable (/sys/class/net)` —— Android 10 上 SELinux 禁止列目录，
但按名字直读仍然可行。现在列目录失败会退回到固定名字（wlan0/p2p0/bt-pan/eth0/rndis0/wlan1）直读，
并注明"listing unreadable, direct reads: …"。

**这台车机上仍然拿不到的**：BR 地址。`bluetooth address (api)` 被框架屏蔽成 `02:00:00:00:00:00`，
`Settings.Secure.bluetooth_address` 为 `none`，`/data/misc/bluedroid` 也读不到——
`Root probe` 明确报 `Cannot run program "su": error=13, Permission denied`（这台车机没有 su）。
所以 7.10 的对照只能靠 **adb**（`adb shell "dumpsys bluetooth_manager | grep -i Address"`）
或车机设置里的"蓝牙地址"项，App 这一侧到此为止，不再假装能读到。

### 7.16 车机上广播终于发出，而且用的是**公开地址**——7.10 的阻塞在这台设备上不存在

`hicar-log-20261114-211733.txt` + 手机 nRF 截图（21:17）。权限修好后第一次真正把 0xFDEE 广播打出去：

```
手机端 nRF:
  宋Plus   98:BB:1E:67:87:ED   BONDED   -57 dBm
  Device type: CLASSIC and LE      Advertising type: Legacy
  Flags: GeneralDiscoverable
  Service Data: UUID 0xFDEE  Data: 0x010101038DC1E204341003
  Complete Local Name: 宋Plus

车机端:
  HiCar advertisement started (11 bytes of 0xFDEE service data, device name in the scan response)
  HiCar access point ready on 5 GHz, control port 4005   (channel 40 / 5200 MHz)
  HiCar records ready: business channel 3, control on 0xFE35
```

payload `01 01 01 03 8D C1 E2 04 34 10 03` 与车机日志里的 `payload=` 逐字节一致，机型 `8DC1E234` 也在里面。

**地址这一关，这台车机是通的**：

- `98` = `1001 1000`，I/G 位为 0，最高两位 `10`——按 7.14 的两条硬判据，它**只可能是公开地址**
  （`10` 不是随机地址的合法前缀）。
- 更需要留意的是 nRF 把这条 LE 广播标成 **BONDED**：绑定是经典链路的 BD_ADDR 建立的，说明手机拿到的这个地址
  能对上已有的绑定——要么它本身就是 BR 地址（公开），要么被 LE 隐私解析成了身份地址。两种情况手机**都有可拨的地址**。

对比 7.10：华为平板的 `65:…`/`51:…` 是轮换的私有地址、且从未绑定，手机"看见但拨不到"。
**这台车机上那个阻塞不存在。**

**但手机仍然没拨号**：`HiCar waiting for the phone: 60s, connections=0`（可发现、广播在、监听在）。
所以剩下的变量不再是地址，而是**载荷/机型码**，以及手机侧 HiCar 服务本身的状态。

**一条被实测推翻的日志措辞（已改）**：`requestPublicAddress()` 失败时原来写的是
"the platform keeps a private advertising address … so it has nothing to dial"。这次车机同样抛
`NoSuchMethodException`，可它实际发的是公开地址——`NoSuchMethodException` 只说明**我们没权限问**，
不说明平台用什么。措辞已改成"这版不让应用选地址，结论要看手机扫描器"，不再预设。

**下一步两个零成本实验**（都不需要重编）：

1. **换机型码**：界面新增一键 `Use test model 00040A04`（参考项目的测试向量）。华为的机型码由 Partner 平台
   分配，我们那个 `8DC1E234` 是本机随机生成的，手机很可能在"设备库"里查不到它，于是不弹框。
2. **看手机怎么想**：手机开 USB 调试，扫描时 `adb logcat | grep -iE "hicar|fdee"`，看 HiCar 服务读到这条广播后
   丢在哪一步。这是唯一能看到手机侧判断依据的窗口。

自扫描这次听到 12 台其它 BLE 设备、没有任何 0xFDEE 载荷——与之前一致：控制器不把自己的广播回报给宿主，
这条自检在车机上同样是盲的，不能用来判断"是否发出"。

### 7.17 手机弹框了，连上来了，卡在 HiChain 最后一步的"密码不对"

`hicar-log-20261114-212622.txt`（21:25 启动）+ 用户实述："手机有弹窗提示了，我输入了密码，但是不正确"。

**第一个重大结论：机型码就是手机的识别键。** 上一轮 payload 用随机机型 `8DC1E234`，手机毫无反应；
这一轮只把机型改成参考项目的测试向量 `00040A04`（payload `01 01 01 03 00 04 0a 04 04 10 05`），
**手机立刻弹框**。其余条件（同一车机、同一广播地址、同一权限、同一 5 GHz 热点）全部没变。
所以"手机认不认这台车机"由机型码决定，华为的机型码按产品分配，随机值不在设备库里。

**第二个结论：整条链路只差鉴权确认。** 手机拨号后，车机侧日志：

```
HiCar control channel connected
HiCar business channel connected
HiCar identity exchanged
HiChain step: 1 packet(s)   ×6
HiCar channel closed: IOException
HiCar channel closed: IOException
HiCar waiting for the phone: 30s, connections=2, ...
```

也就是说：BLE 发现 → 经典拨号 → 免配对 RFCOMM 双通道 → 业务通道协商 → `oper_type` 1 身份交换 →
HiChain PAKE 六步，全部打通；**在最后一步被手机拒掉**（手机侧提示密码不正确，随后关掉两条通道）。
车机侧没有抛鉴权异常、没拿到 session key，说明我们这六步的每一步 `error==0`，是手机侧算完 confirm 后判定不一致。

**这意味着 PAKE 用的那个六位数，和手机期望的不一致。** 两种可能，需要手机弹窗原文才能区分：

1. 手机要求输入**车机屏幕上的连接码**（我们的假设）→ 那这次就是输入值/时序问题（例如看了旧屏幕、或输错一位）；
2. 手机自己生成一个码、要求**在车机上输入**（方向相反）→ 我们的界面没有输入口，必然失败。
   这种情况下弹窗会写"请在车机上输入以下连接码 123 456"。

**本轮加的诊断**（为了让下一次能定位，而不是继续猜）：

- `HiChain step N failed: engine error=<数字>, keyLength=…`：以前所有非零错误都被映射成
  "需要厂商签名"，把"密码错"和"密钥长度不支持"混为一谈。现在原始错误码进日志，异常改报
  `PIN_NOT_TRUSTED`（更接近事实）。
- `HiChain begin: role=accessory peer=… pin=53…31 (6 digits, …)`：把**实际参与 PAKE 的码**（掩码后）打进日志，
  可以直接与屏幕上的码对照。
- `HiCar business/control channel connected (peer <MAC>)`：哪台手机拨进来的，一次看清。

**下一次操作建议**：先点 `Reset pairing`（清掉此前多次失败留下的半成品信任状态并换新码），记下新码，
再 Start，弹窗出现时**照着屏幕逐位输入**；同时把手机弹窗截图发来——它的措辞能直接分辨上面两种可能。

### 7.18 手机只要求"输入密码"：方向确认了，但码对不上

用户补充的三条事实（Mate 80 Pro Max，装的是 21:26 那版 `8cd3c37` 之前的 APK）：

- 手机弹窗**只有输入密码**，没有"请在车机上输入"这类反向提示 → 7.17 列的两种可能里，**方向是第 1 种**，
  即手机要求输入车机屏幕上的六位数，我们的假设成立，不需要在车机端加输入框。
- 用户输入的是车机屏幕上的 `530631`。
- 结果是"密码不正确"。

**先把"数字被弄坏"这一类可能排除掉（读代码，不是猜）**：

| 环节 | 代码事实 | 结论 |
| --- | --- | --- |
| 生成 | `String.format("%06d", random.nextInt(1_000_000))` | 以 0 开头也保留六位，不会变成 5 位 |
| 存储/显示 | 同一个 `String` 直接显示、直接传给控制器 | 显示值与使用值同源 |
| JNI | `copy_string(..., PIN_DIGITS)`，逐位校验 `'0'..'9'` | 非 6 位数字直接返回 0，不会"半截"参与运算 |
| 引擎 | `hc_pin { uint32_t length; uint8_t pin[HC_PIN_BUFF_LEN]; }`，PAKE 的 HKDF 输入按 `length` 取 | 不依赖字符串结尾，不会被后面的脏字节影响 |
| 密钥长度 | `keyLength` 只接受 16/32，其他会抛 `key length` | 21:26 的日志里没有这行，**密钥长度不是原因** |

排除之后只剩两种，而它们的分界线就是新日志里的那一行：

1. **那一轮用的码和屏幕上显示的不是同一个**。码存在首选项里、只在 `Reset pairing` 或清数据时更换；
   用户此前被要求按过 Reset，很可能读到的是**上一轮**的码（`530631`）而引擎用的是新码。
   新日志会在鉴权开始时打 `HiChain begin: ... pin=53…31 (6 digits, ...)`，掩码后仍可逐位对首尾。
2. **码相同但协议参数不同**。HiCar 的"密码不正确"是 **confirm 不匹配的通用文案**，不是"数字比对"的结果；
   PAKE 的 confirm 覆盖派生密钥，任何参数不一致都会显示同一句话。

**本轮加的一条日志（把第 1 种和第 2 种分开）**：

```
HiChain ended after 6 step(s) with no session key: the phone's confirm did not match ours, which for
this protocol means the code on the phone differs from the code on this screen (or the link dropped first)
```

以前通道被手机关掉只打一行 `HiCar channel closed: IOException`，看不出"鉴权走到第几步、有没有拿到密钥"。
现在只要 PAKE 跑过步数却没拿到 session key，就会明确写出这句。

界面顺带做了两件小事：连接码改成大号等宽字；下面那行说明改成"每次按 Reset pairing 都会换新码，
弹窗出现时照屏幕读"，因为这次的失败很可能就是读了旧码。

**下一次运行的判读顺序**（先 Reset、再 Start，不要反过来）：

1. 按 `Reset pairing` → 记下屏幕上的新码（此时旧码立刻作废）；
2. `Start` → 手机弹窗出现时**照屏幕逐位输入**；
3. 导出日志，看两行：`HiChain begin: ... pin=XX…YY`（是否与屏幕一致）与
   `HiChain ended after N step(s) ...`（N 是否 6、有没有 session key）。

### 7.19 密码通过、手机随后报"连接失败"：我们把自己的 session key 丢了

`hicar-log-20261114-214313.txt`（21:42 起）+ 手机截图（21:45，"请重试连接／HUAWEI HiCar 连接失败，
为确保下一次连接成功，设备配对信息已被清除"）。

**码这次是对的**。车机屏幕 `216929`，日志里 `pin=21…29`，手机没有再报密码错误，PAKE 也跑完了：

```
HiCar control channel connected (peer F8:3B:7E:3E:DD:06)
HiCar business channel connected (peer F8:3B:7E:3E:DD:06)
HiChain begin: role=accessory peer=01CF…017E pin=21…29
HiCar identity exchanged
HiChain step: 1 packet(s)   ×4          <- 注意：错码那次是 6 步
HiCar channel closed: IOException
HiChain ended after 4 step(s) with no session key
```

**根因在我们这边，不在手机：引擎把 session key 派生出来了，我们用一条错误的判据把它丢掉了。**

HiChain 的回调 `set_service_result` 传的不是"成功/失败"，而是 `hc_result` **阶段枚举**
（`interfaces/innerkits/deviceauth_lite/hichain.h`）：

```c
enum hc_result { END_SUCCESS = 0, END_FAILED, KEY_AGREEMENT_PROCESSING,
                 KEY_AGREEMENT_END, OPERATION_PROCESSING, OPERATION_END };
```

而 `frameworks/deviceauth_lite/source/hichain.c` 的 `set_result_by_map()` 只在一个条件下把阶段**改写成** `END_SUCCESS`：

```c
if (hichain->operation_code != AUTHENTICATE && hichain->operation_code != AUTH_KEY_AGREEMENT) {
    goto out;          /* BIND 走这里：原样返回阶段，即 KEY_AGREEMENT_END = 3 */
}
...
hichain->cb.set_service_result(&hichain->identity, END_SUCCESS);
```

**首次配对是 BIND**，所以阶段到 `KEY_AGREEMENT_END`（3）就停了，永远不会是 0。而我们的
`HiChainNative.Step.finished` 写的是 `result == 0 && keyLength in {16,32} && error == 0`，
JNI 的 `sessionKey()` 又要求 `session->result != 0` 才拒绝——两边合起来的效果是：
**密钥已经算出来，但一次都不交给我们**。手机那边认为鉴权完成，等不到车机的后续（DMSDP），
于是清掉配对信息并弹"连接失败"。

这也解释了步数差异：错码那次 6 步（引擎走的是拒绝/提示路径），这次 4 步（PAKE 正常结束）。
步数本身不是判据，**`key=N B` 才是**。

**修复**（判据换成"有没有拿到可用的密钥"）：

| 位置 | 旧 | 新 |
| --- | --- | --- |
| `HiChainNative.Step.finished` | `result == 0 && keyLength in {16,32} && error == 0` | `error == 0 && result != END_FAILED(1) && keyLength in {16,32}` |
| `hichain_jni.c` `sessionKey()` | `session->result != 0` 就拒绝 | 只拒绝 `result == END_FAILED`，仍要求 16/32 字节 |

`END_FAILED` 是唯一表示失败的取值，保留它；`KEY_AGREEMENT_END`、`OPERATION_END` 都是进度。
单测 `HiChainStepTest` 四项锁住这条语义（含"有密钥但 END_FAILED 不许导出""没密钥不算完成"）。

**同时补上每步一行原始数字**，下次不用再靠步数猜：

```
HiChain step 4: engine=0, phase=KEY_AGREEMENT_END, key=32B, error=0, out=1
```

`phase` 由枚举翻成名字（`END_SUCCESS` / `KEY_AGREEMENT_END` / …），`key` 是引擎报的密钥长度。

**下一步预期**：若判据修对，日志应出现

```
HiChain step N finished: 32-byte session key
HiChain finished: 32-byte session key; opening DMSDP
```

随后是网络协商（Wi-Fi 凭据）与业务通道。若仍未出现，`key=` 那一列会直接指出是"引擎没派生"还是
"派生了但被拒"，不再有第三种模糊状态。

### 7.20 机型码改为默认值并持久化（用户指出的一处疏漏）

用户核对后指出：**机型码并没有真的固定成 `00040A04`**。属实，原实现是
`model = experimentalModel()`（本机派生的随机 id，如 `8DC1E234`）作为默认值，`00040A04` 只是一个需要手动点的
按钮。后果很实际：每次重装或清数据后启动，广播出去的都是一个手机从没见过的机型；21:42 那轮之所以成功，
是因为用户当场点了那个按钮（日志里第一段 payload 是 `8d c1 e2 04`，第二段才是 `00 04 0a 04`）。

改动：

- `model = storedModel()`：默认 `00040A04`，并与连接码一样存进首选项（`advertised_model`），
  重启后保持；只有用户主动改成别的值才会变。
- 输入框改为**输满 8 位十六进制即写入首选项**，避免"界面显示 A、实际用的是 B"。
- 按钮改成双向切换：当前是 `00040A04` 时显示"Use this install's own id `8DC1E234`"，反之显示
  "Use test model `00040A04`"，两个值都能一键到达，且都会持久化。
- `TEST_MODEL` 的注释同步更正：它不再是"未经真机验证的测试向量"——Mate 80 Pro Max 在这个值上弹框并拨号，
  在本机随机 id 上完全静默，这条对照已经在 7.17 记录过。

### 7.21 鉴权通过、密钥拿到，卡在 DMSDP 的第一条记录

`hicar-log-20261115-112048.txt`（11:19 起，多次尝试）。**7.19 的修复生效了**，每一轮都是同一个结局：

```
HiChain step 1: phase=KEY_AGREEMENT_PROCESSING, key=0B, error=0, out=1
HiChain step 2: phase=KEY_AGREEMENT_END,        key=32B, error=0, out=1
HiChain step 2 finished: 32-byte session key
HiChain finished: 32-byte session key; opening DMSDP
DMSDP rejected: control header          <- 卡在这里
HiCar protocol stopped: control header
HiCar business channel connected (peer F8:3B:7E:3E:DD:06)   <- 手机立刻重拨
```

用户侧看到的现象（"每次输完密码又让再输"）由这条链解释：**我们一收到不认识的记录就关掉 socket，
手机把这当成连接失败，于是重拨、重新开始 HiCar 流程，再要一次密码**。同一段日志里
`connections=4 → 8` 就是这些重拨。密码本身没有问题——每轮都走到 `32-byte session key`。

**现在唯一的未知量：手机在鉴权后第一句说的是什么。** `HiCarControlSession` 期望
`marker(1) | size(2, 大端)`，marker 只能是 `0x00`（明文）或 `0xAB`（密文），size 在 1..1497。
手机的这条记录两者都不满足，而原来的代码只报"control header"四个字、不留任何证据。

本轮改成把**帧头**写进日志（帧头不是载荷，没有泄露内容的风险）：

```
DMSDP: unexpected record header marker=0x7b size=...
       first bytes=7b 22 6f 70 65 72 5f 74 79 70 65 ...
```

加上两条定位信息：

- `HiChain finished ...; opening DMSDP on the business/control channel`：DMSDP 现在跑在**哪条** RFCOMM 上。
- `DMSDP: sent N-byte OPTIONS greeting`：拒绝发生在**我们发 OPTIONS 之前**（手机先说）还是**之后**（回应我们）。

三种可能，要看字节才能定：

| 首字节 | 含义 | 下一步 |
| --- | --- | --- |
| `0x7b`（`{`） | 手机还在说 HiCar 的 JSON 信封（`oper_type`），不是 DMSDP | 鉴权完成后仍要继续 `HiCarIdentityExchange`，DMSDP 等手机明确切换 |
| `0x00`/`0xAB` 但 size 越界 | 记录长度字段位置或宽度不同（可能是 4 字节） | 按实测改 `pack`/`feed` 的帧格式 |
| 其它（如 `0x01`） | 这条 socket 上根本不是 DMSDP，DMSDP 应在另一条通道 | 交换 business/control 的角色 |

顺带修掉一处日志插值 bug：`engine=$step.code` 被 Kotlin 解析成 `$step` 后接 `.code`，
日志里出现 `...Step@bb01e5e.code`，现改为 `${step.code}`。

### 7.22 手机鉴权后说的还是 JSON 信封，不是 DMSDP

`hicar-log-20261115-112906.txt`（11:29）。新增的帧头日志一次就把问题定位了：

```
HiChain finished: 32-byte session key; opening DMSDP on the business channel
DMSDP: sent 115-byte OPTIONS greeting
DMSDP: unexpected record header marker=0x7b size=8804,
       first bytes=7b 22 64 61 74 61 22 3a 22 7b 5c 22 6d 65 73 73 61 67 65 5c 22 3a 33 2c
DMSDP rejected: control header
```

`0x7b` 是 `{`，后面是 `"data":"{\"message\":3,` —— **手机在鉴权完成后继续使用 HiCar 的 JSON 信封
（`oper_type` + `data` 字符串），根本没切到 DMSDP**。我们却在拿到 key 的瞬间就把这条 socket 当成 DMSDP
通道，先发一条 `OPTIONS * DMSDP/1.0`（115 字节），再把对方的 JSON 当记录头解析，失败即关连接——
手机于是重拨、重开 HiCar 流程、再要一次密码。这就是"每次输完密码又让再输"的完整原因。

**修法不是猜协议，而是让内容决定走哪条路**：

- 分流：读到的第一段字节以 `{` 开头 → 继续喂信封处理器；否则（DMSDP 记录以 marker 开头）才切控制通道。
  只有这样才不会再出现"鉴权成功但被我们主动掐断"。
- **鉴权完成后到达的信封不再喂给引擎**（引擎已结束，再 `step` 是拿完成态去跑），改为记录并**不回复**：
  `oper_type`、`message`、字段名列表、字节数。当前不知道手机要什么回包，乱回比不回更糟。
- **未知 `oper_type` 不再抛异常断开**，改为记录后忽略。之前 `else -> throw "operation type"`，
  陌生消息一律等于断线，代价是整轮重来、用户再输一次密码。
- `HiCarStatus.Authenticated` 在拿到 key 时照常上报，但**不再顺手打开 DMSDP**；DMSDP 的真正起点推迟到
  "手机改用记录协议"那一刻。

下一次预期看到这一行（手机要什么，就看它的字段名）：

```
HiCar envelope after authentication: oper_type=3, message=3, keys=..., N bytes; no reply is defined for it yet
```

拿到这一行就能判断它是"鉴权结果通知"（不需要回包，继续等下一步）还是"服务/能力查询"（需要构造回包）。

顺带记录一个与本改动无关的环境问题：`:shared:testDebugUnitTest` 里
`AmbientWorkerOwnerLockSymlinkTest` 三项失败，原因是 Windows 创建符号链接需要特权
（`FileSystemException: 客户端没有所需的特权`），属本机权限限制，不是代码回归；其余 1868 项通过。

### 7.23 鉴权后的 `message 3` 是 BIND 的 exchange 阶段，不是可有可无的通知

`hicar-log-20261115-113545.txt`（11:35）。上一轮的分流生效了——不再误判 DMSDP、不再主动断链——于是第一次
看清手机在鉴权后到底要什么：

```
HiChain finished: 32-byte session key; the phone keeps the envelope channel for now, DMSDP starts when it switches
HiCar envelope after authentication: oper_type=3, message=3, keys=message,payload, 661 bytes; no reply is defined for it yet
HiCar channel closed: IOException          <- 我们没回，手机关掉连接并重开流程
```

**这 661 字节是 HiChain 的 exchange 请求。** `hichain.c` 的消息与结果映射写得很清楚：

```c
{ PAKE_REQUEST, ... , KEY_AGREEMENT_STATE },
{ PAKE_CLIENT_CONFIRM, KEY_AGREEMENT_END, OPERATION_STATE },
{ EXCHANGE_REQUEST, END_SUCCESS, OVER_STATE },      // <- 这一步之后才算整个操作结束
```

也就是说首次绑定（BIND）分两段：**PAKE 协商**（此时就派生出 session key）→ **exchange**（双方交换各自的
认证凭据并落盘）。我们在第一段结束就把引擎"关掉"了（`if (engine.complete)`，而 `complete` 只表示"有 key"），
第二段完全没喂进去，手机等不到 `EXCHANGE_RESPONSE`，于是关连接、重开流程、再要一次密码。

**修法：判据从"有 key"改成"引擎说操作结束"。**

- `HiChainNative.Step.completed`：`error == 0 && result == END_SUCCESS(0)`，这是 `OVER_STATE` 的标志。
- `HiCarAuthenticator.operationComplete`：新增，`HiChainAuthenticator` 在 `step.completed` 时置位；
  `begin()` 重置。`complete`（有 key）保留，但它不再决定"是否继续喂引擎"。
- `HiCarAuth` 的 `OPER_AUTH` 分支改为 `if (engine.operationComplete) 记录并忽略 else 正常 step`，
  于是 `message 3` 会被当作 exchange 请求处理，引擎据此产出 `EXCHANGE_RESPONSE`，装进信封回给手机。
- `close()` 增加一种独立结论：**有 key 但操作未完成**（`HiChain ended ... with a key but before the
  operation finished`）——这正是 11:35 的情形，以前这种情况一句话都不留。
- 单测：`anEnvelopeArrivingAfterTheOperationIsOverIsNotFedToTheEngineAgain`（操作结束后不再 step），
  与原有"有 key 但 END_FAILED 不许导出""没 key 不算完成"共同锁住三种状态。

**下一步预期**：

```
HiChain step 3: engine=0, phase=END_SUCCESS, key=32B, error=0, out=1
HiChain step 3 completed the operation (phase=END_SUCCESS), out=1
```

出现这两行说明 exchange 完成、凭据已落盘，手机不会再要密码，接下来才是 Wi-Fi 加入与 DMSDP
（届时日志会打 `the phone switched from envelopes to DMSDP records`）。

### 7.24 分流按"记录标记"判定，不再按"像不像 JSON"

`hicar-log-20261115-115311.txt`（11:51–11:53）。三个 `Start` 里前两个 `connections=0`（手机没连），
第三个连上了，但只走了 **1 步** 就断：

```
HiChain step 1: phase=KEY_AGREEMENT_PROCESSING, key=0B, error=0, out=1
HiCar protocol stopped: non-envelope bytes before authentication
```

**这是我们上一轮引入的回归，不是手机的问题。** 7.22 的分流规则是"第一段字节以 `{` 开头 → 信封，否则 DMSDP"。
可是**一条信封可能分两次写入**：第二次的开头是 JSON 的中间内容（例如 `"` 或数字），
于是被判成"非信封"，直接抛 `non-envelope bytes` 断链。同一份日志里前面的 `step 1` 成功、`step 2` 失败，
正是这种跨包边界造成的。

**修法：判据改成"这段字节是不是一条 DMSDP 记录的标记"（`0x00` 明文 / `0xAB` 密文），而不是"像不像 JSON"。**
两协议之间存在第三种情况——被拆开的信封续包——用"不是 JSON 就换协议"必然误判；用正向标记判定则不会：

```kotlin
if (identity.awaitingRest || !isControlRecord(buffer, read)) {   // 默认仍走信封
    ... identity.feed(...)
}
```

`HiCarIdentityExchange.awaitingRest` 是本轮新增的：只要缓冲区里还有半条信封，下一位字节就属于它。
单测 `aSplitEnvelopeIsReassembledAndSaysItIsStillAwaitingTheRest` 把这条锁住（拆成两半喂，第一半必须
`awaitingRest=true` 且无回包，第二半才组装出信封）。

**同时回答一个反复出现的操作疑问**：**不需要每次按 `Reset pairing`。** 六位码存在首选项里，重启不变，
只有按 Reset（或清数据）才会换。Reset 的真实代价是**清掉 `files/hicar-huks` 里的信任材料**——
一旦某次配对真的完成，按它等于把手机已存的凭据作废。因此界面在按钮下加了一行说明：
只在配对出错时按；配对成功后不要再按。（当前尚未有任何一次配对完成，所以现在每次都需要输码，
这是"配对未完成"的结果，不是"码会变"的结果。）

### 7.25 一次"手机完全没拨号"的会话：链路健康，静默在手机侧

`hicar-log-20261115-120003.txt`（11:58:56 起，60 秒）。**没有任何协议失败**——连一次连接都没有：

```
HiCar advertisement started (11 bytes of 0xFDEE service data, device name in the scan response)
HiCar listeners started: hicar-control, hicar-business
HiCar waiting for the phone: 30s, connections=0, advertising=on, listeners=open, discoverable=yes
HiCar waiting for the phone: 60s, connections=0, advertising=on, listeners=open, discoverable=yes
```

车机侧四项全绿（广播在发、两条 RFCOMM 在听、5 GHz 热点已就绪、可发现），**手机一次都没发起来**，
所以既没有 `control channel connected`，也不会有 HiCar 的密码弹窗——弹窗是手机拨通之后才出现的。

这类"静默不拨"在本次会话里出现过多次（11:51、11:52 两次 `connections=0`，11:52 第三次才连上），
特征与失败原因都很明确：**它发生在多次失败尝试之后**。手机端在连续失败后会把该车标记为"连接失败、
配对信息已清除"，此后一段时间不再主动拨号，直到用户在手机上重新发起。

为此在心跳里加了一条**只出现一次**的判读提示（60 秒仍无连接时）：

```
HiCar: the phone has not dialled at all, which is its side of the link. Check, in this order:
the phone is unlocked with its HiCar screen open; the HiCar entry for this car was removed after
the last failed attempt; then Start again — the code has not changed, Reset pairing is not needed
```

它替代不了手机侧的操作，但能让日志自己说明"不是接收端的问题"，避免再把这类会话当成协议 bug 排查。

**这轮同时确认的操作事实**：`Reset pairing` 换码后**手机不会因为新码而来拨**——是否拨号完全由手机侧
发起。所以正确顺序始终是"先在手机上进 HiCar 连接界面，再在车机上 Start"，而不是先 Start 等手机。

### 7.26 exchange 阶段被引擎判为 END_FAILED：先把引擎自己的错误码读出来

`hicar-log-20261115-120850.txt`（12:08）。7.23 的改动生效了——`message 3` 终于被喂进引擎——但引擎直接判失败：

```
HiChain step 2: phase=KEY_AGREEMENT_END, key=32B                       <- PAKE 正常
HiChain step 3: phase=END_FAILED, key=32B, error=0, out=1              <- exchange 被拒
HiChain step 4/5/6: phase=END_FAILED ...                               <- 手机继续重试，全部 END_FAILED
HiChain ended after 6 step(s) with a key but before the operation finished
```

手机因此弹"密码错误"——**这个文案在 exchange 失败时也会出现**，不只是码错。

`exchange_auth_info.c` 里 `send_exchange_response()` 只有三处能返回错误，每处都会把
`send->msg_code` 改成 `INFORM_MESSAGE`，而 inform 的 payload 是一个**裸 int32 错误码**
（`encap_inform_message`：`*err = error_code; send->payload = err;`）：

| 步骤 | 失败会得到什么 |
| --- | --- |
| `parse_exchange_request_data`（用 PAKE 会话密钥解密、验签、比对 auth id） | `HC_DECRYPT_FAILED`(26) / `HC_VERIFY_EXCHANGE_FAILED`(29) / `HC_SIGN_EXCHANGE_FAILED`(30) |
| `build_exchange_response_data` | `HC_GET_LTPK_FAILED`(33) / `HC_GEN_ALIAS_FAILED`(34) |
| `save_auth_info(..., HC_PAIR_TYPE_BIND, &cache)` | `HC_SAVE_LTPK_FAILED`(31) / `HC_IMPORT_AUTH_DATA_FAILED`(16) |

`get_instance()` 里还有一步 `build_self_lt_key_pair(hichain)`（返回值被 `(void)` 丢弃）：
若本机长期密钥对建不出来，exchange 阶段必然在验签或取 LTPK 处失败。这是当前最可疑的一条。

此前日志只有 `END_FAILED` 四个字母，没有码。本轮把引擎自己产出的 inform 包**原样解出来**并翻译：

```
HiChain step 3: engine reported END_FAILED; its own report follows
  engine reply 1: {"message":900,"payload":26}  [HC_DECRYPT_FAILED]
```

（`ERROR_NAMES` 按 `hichain.h` 的枚举翻译，未列出的码原样打印。）同时：

- 每步日志加上手机的消息号：`HiChain step: 1 packet(s) for message 3`，一眼看出当前是哪个阶段；
- 判为 `END_FAILED` 后**停止继续喂引擎**：手机后续的重复消息只回一行
  `session already failed, N bytes ignored`，避免把真正的原因淹没在六条一样的失败里。

（注：`message 900` 是 `INFORM_MESSAGE` 的枚举值，不是错误码；错误码在 payload 里。）

### 7.27 根因定位：exchange 验签被我们自己的编译宏裁掉了（不是协议问题）

`hicar-log-20261115-121807.txt`（12:18）第一次打印出引擎自己的错误码：

```
HiChain step 3: engine reported END_FAILED; its own report follows
  engine reply 1: {"message":32896, "payload":{"errorCode":29}}
```

`29` = **`HC_VERIFY_EXCHANGE_FAILED`**。调用链一路读下来是确定的：

```
send_exchange_response()                       auth_info/exchange_auth_info.c:59
 └ parse_exchange_request_data()               解密 + 取字段 + 验签
    └ verify_peer_public_key()                 验签失败即 return HC_VERIFY_EXCHANGE_FAILED(29)
       └ verify_with_public_key()              huks_adapter.c:1215
          └ hks_asymmetric_verify()            huks_lite/.../hks_client.c:199
             #ifdef _CUT_AUTHENTICATE_
                 return HKS_ERROR_NOT_SUPPORTED;     ← 我们定义的宏，直接短路
```

**这个宏正是本项目自己在 `CMakeLists.txt` 里加的**（为了避免引入 OpenSSL：`soft_service/hks_service.c`
里 `#include <ec_local.h>` 是 OpenSSL 内部头，NDK 里没有 BoringSSL 的这套内部结构）。代价在 7.23 之前
一直没暴露，因为流程根本没走到 exchange；今天走到了，代价立刻显形：

- `hks_asymmetric_verify` 在 `_CUT_AUTHENTICATE_` 下是一个**返回"不支持"的桩**；
- 真正的实现 `hks_service_asymmetric_verify`（`soft_service/hks_service.c:679`）整段在
  `#ifndef _CUT_AUTHENTICATE_` 里，而且它调用 `ED25519_verify(...)` —— **全树只有调用、没有实现**，
  那份实现来自 OpenSSL。`CRYPTO_PUBLIC_KEY_BYTES = 32` 也说明长期密钥是 **Ed25519**，而 mbedtls 2.28.9
  没有 Ed25519 签名/验签（只有 X25519 的 ECDH）。

因此这不是协议、时序或码的问题：**当前这个 native 构建在密码学上做不完 bind 的 exchange 阶段**，
手机端看到的就是"密码错误 + 配对信息清除"。

**三条出路（都不小，必须选一条，不能再靠调参推进）**：

| 路线 | 内容 | 代价 / 风险 |
| --- | --- | --- |
| A. 自带 Ed25519 | 引入一份紧凑的 Ed25519 实现（sign+verify），把 `hks_client.c` 的两个桩与 `hks_service.c` 被裁的 verify/sign/import 路径接回去 | 需要新增第三方源码并处理许可；还要用实验确定签名输入是"原文"还是"SHA-256 摘要"（两种都试，日志会写哪个成立） |
| B. 承认边界 | 记录"Android + 本方案无法完成 HiCar bind"，能力表注明原因 | 与目标（车机端接收）冲突 |
| C. 换平台 | 走 Linux/BlueZ（参考项目 HelloCar 的路径） | 偏离"Android 车机"前提 |

另有一条**不建议**的"捷径"：跳过验签（`verify_with_public_key` 直接返回 `HC_OK`）。它能让流程继续，
但等于放弃长期密钥的签名校验，且我们的 `EXCHANGE_RESPONSE` 同样需要签名——手机端仍会拒绝，所以它也
解决不了问题，只会制造"看起来通了"的假象。若将来为排查临时启用，必须是 debug-only 并在日志里明写。

### 7.28 路线 A 第一步：自带的 Ed25519 已经进构建（尚未接线）

按 7.27 的三选一，确定走 **A（自带 Ed25519 实现）**。本轮只做第一步，且必须是"能编译、可核对"的一步：

- 新增 `hicar-auth/src/main/cpp/ed25519/`：取自 **orlp/ed25519**（zlib 许可）的原始源码，**未改一行**；
  `ed25519/NOTICE.md` 记录来源、日期、为什么需要它，`SHA256SUMS` 记录 15 个文件的校验和（便于复核）。
- 新增 `hicar-auth/src/main/cpp/hicar_ed25519.c`：把 orlp 的 `ed25519_sign/verify` 包装成 **OpenSSL 的
  `ED25519_sign/ED25519_verify` 签名顺序**（huks_lite 正是按 OpenSSL 那套调用的），参数顺序差一位就要出错，
  所以这层只做改名与重排，不含任何算法。
- `CMakeLists.txt` 增加 `ED25519_SRC` 与 include 路径；四个 ABI 的 CMake 配置与编译全部通过。

**这还不是修复本身**：huks 的桩函数仍在，`hks_asymmetric_verify` 依旧返回"不支持"。下一步是把
`hks_client.c` 里被 `_CUT_AUTHENTICATE_` 替换掉的那几个入口接回真实实现：

| 入口 | 现状 | 接线方式 |
| --- | --- | --- |
| `hks_asymmetric_verify` | 返回 `HKS_ERROR_NOT_SUPPORTED` | 对 `HKS_BLOB_TYPE_KEY` 直接 `ED25519_verify(message, len, sig, pk)` |
| `hks_asymmetric_sign` | 同上 | 从存储取私钥后 `ED25519_sign` |
| `hks_generate_key` / `hks_is_key_exist` / `hks_import_public_key` | 同上 | `ed25519_create_keypair`（种子来自 HUKS 随机/既有 seed）+ 现有文件回调落盘 |

难点不在算法，而在 `hks_storage_*` 这套内部存储也一起被裁了：要么把它的非裁版本也编进来（它会牵出
OpenSSL 的 BN/EC 内部头，NDK 没有），要么在我们这层用"别名 → 文件"的极简存储替代。后者更可控，
也更符合当前目标（一台车机、一对 LTPK）。

因此本轮**没有功能变化**，手机行为与 7.27 相同；这一轮的价值是把"不可能"变成"可实现"，并且每步都可核对。

### 7.29 路线 A 落地：不再裁 HUKS，而是补上它缺的三个声明

7.27 的结论是"`_CUT_AUTHENTICATE_` 把 Ed25519 签名/验签整段裁掉了"。继续查下去，这个宏的代价比当时写的还大，
而修法比当时估计的还小：

**一、这个宏裁掉的不只是验签。** `hks_client.c` 里有 17 处、`hks_access.c` 7 处、`hks_service.c` 7 处守卫。当
它被定义时，被替换成 `return HKS_ERROR_NOT_SUPPORTED` 的不只是 `hks_asymmetric_verify`，还包括
`hks_generate_key`（长期密钥对）、`hks_export_public_key`、`hks_import_public_key`、`hks_is_key_exist`、
`hks_delete_key`、`hks_asymmetric_sign`，以及**整个密钥存储层**（`hks_storage.c` 全文都在守卫里）。
顺带一个之前没注意的事实：`hks_register_file_callbacks` 在裁剪版里是 `return HKS_SUCCESS;`——
也就是**什么都没注册却报成功**。我们的 JNI 一直在注册那三个文件回调（`hks_keystore`/`info1.data`/`info2.data`），
从未真正生效过。

**二、OpenSSL 的依赖只是一行 `#include`。** `hks_service.c` 顶部：

```c
#ifndef _CUT_AUTHENTICATE_
#include <ec_local.h>
#endif
```

而整份 `hks_service.c` 里**没有任何** `EC_KEY` / `EC_POINT` / `BN_` / `EVP_` 调用（已 grep 全树确认）：
x25519 那一段上游早就改成 mbedtls 了。这一行存在的唯一目的，是拿到 `ED25519_sign`、`ED25519_verify`、
`ED25519_public_from_private` 三个**声明**。

**三、因此修法是补声明，而不是补算法。** 本轮：

| 改动 | 内容 |
| --- | --- |
| `hicar-auth/src/main/cpp/openssl_shim/ec_local.h` | 只放三个声明，参数顺序按 huks 的调用点写；开头加了保护，若真 OpenSSL 头先出现则 `#error` 而不是悄悄冲突 |
| `hicar_ed25519.c` | 补 `ED25519_public_from_private(out_pub, seed32)`，用 vendored 的 `ed25519_create_keypair` 派生，私钥展开后清零 |
| `CMakeLists.txt` | **删掉 `_CUT_AUTHENTICATE_`**；include 路径加上 `openssl_shim` |
| `hichain_jni.c` | `init()` 里在注册两个回调之后调用 `hks_init()`（加载密钥库 + 初始化根密钥），结果存进 `g_hks_status` |

于是 huks 用的是**上游真实的密钥管理与存储**，不是我们另写的一套：`hks_storage_write_key_info`、
`hks_service_asymmetric_verify` 现在都是 `.so` 里真实存在的函数（可用 `llvm-nm` 核对）。
7.28 里计划的"别名 → 文件"极简存储**没有做**，也不需要了。

**四、本轮新增一个不依赖手机的验证入口。** 交换阶段的失败在车上极难定位（要先有手机、PIN、
发现链路全通），所以加了 `Crypto self-test` 按钮 → `HiChainNative.selfTest()`，在 native 里按顺序做：

```
hks_init → hks_generate_key(Ed25519) → hks_export_public_key → hks_asymmetric_sign
        → hks_asymmetric_verify(正确签名, 期望 status=0)
        → hks_asymmetric_verify(翻转一位, 期望 status≠0)
        → hks_import_public_key → hks_is_key_exist → hks_delete_key → hks_is_key_exist(应为不存在)
```

每行独立打印 `PASS/FAIL + 具体 status`，最后一行给 verdict。它跑在一个一次性别名上（开头先删，
所以不依赖干净存储），**不需要手机、不需要射频**，平板或车机上点一下即可。

**五、一个必须说清的新风险。** 裁剪版的 `hks_refresh_key_info` 是空操作；现在它是真的，
而 `hks_service_refresh_key_info()` 会 `hks_storage_clean_key_info()`（清空缓冲并**回写一个空库到文件**）。
好在 deviceauth 只在 `key_info_init()` 里、且**仅当 `hks_init()` 返回 `HKS_ERROR_INVALID_KEY_FILE` 或
`HKS_ERROR_READ_FILE_FAIL` 时**才调用它，正常路径不会触发。但反过来说：如果这台设备的根密钥初始化失败，
`key_info_init()` 现在会返回失败，`get_instance()` 直接拿不到句柄——**连 PAKE 都走不到**，而裁剪版至少还能走到
exchange 再失败。所以顺序必须是：**先让 self-test 全 PASS，再上车**，否则一次车机测试会被这个前置条件拦掉。

**六、签名输入的一致性（为什么可以确定手机能验我们的签名）。** 两端跑的是同一份 deviceauth：
adapter 的 `sign()` 与 `verify_with_public_key()` 都是"先 `sha256(message)`，再把 32 字节摘要交给 huks"，
huks 把它当 Ed25519 的 message（Ed25519 内部再哈希一次）。手机侧的 `hks_service_asymmetric_sign` 是
`ED25519_sign(sig, hash->data, hash->size, pub, priv)`，我们的 shim 与之逐参数一致。所以这是**同一约定**，
不是我们自创的。

**七、还没验证的部分（诚实记录）**：以上全部是代码与构建层面的确认。Ed25519 签名/验签、密钥落盘、
跨会话持久化是否真的在这台车机上工作，**只有 self-test 的实际输出能回答**。在拿到 `verdict: PASS` 之前，
不认为 exchange 已经修好。

### 7.30 自检第一轮：只有验签失败（-132），原因是 OpenSSL 与 orlp 对"私钥"的定义不同

`hicar-log-20261115-125114.txt` 是自检第一次运行。一行失败，其余全过：

```
PASS hks_init: status=0
PASS hks_generate_key (Ed25519 pair): status=0
PASS hks_export_public_key: status=0, bytes=32
PASS hks_asymmetric_sign: status=0, bytes=64
FAIL hks_asymmetric_verify (fresh signature): status=-132
PASS hks_asymmetric_verify (tampered signature rejected): status=-132   <- 假通过
PASS ... reload / identical public key / delete ...
verdict: FAIL (1 failure(s))
```

`-132` = **`HKS_FAILURE`**，`hks_service_asymmetric_verify` 里它的含义只有一个：`ED25519_verify(...)`
返回了假。也就是说**签名与公钥对不上**，不是参数、权限或存储问题（那些都 PASS 了）。

**根因：两个库的 `ED25519_sign` 第 5 个参数不是一个东西。** 我们 7.28 的 shim 只做了改名与参数重排，
把调用方的缓冲区原样转给 orlp：

| | 第 5 个参数（私钥）的含义 |
| --- | --- |
| OpenSSL `ED25519_sign(sig, msg, len, pub, priv)` | **32 字节种子**，`SHA-512(seed)` 的展开在函数内部完成 |
| orlp `ed25519_sign(sig, msg, len, pub, priv)` | **64 字节已展开的密钥**：`[0..31]` 是 clamp 后的标量，`[32..63]` 是 nonce 前缀 |

而 huks 存的是第三种东西：`hks_service_generate_key` 里 `private_key = seed(32) || pub(32)`。
于是我们送给 orlp 的"前缀"其实是**公钥**、送给它的"标量"是**未 clamp 的种子**，签出来的 R 与 S 与规范无关，
用同一把公钥验必然失败。**讽刺的是公钥派生是对的**（那条走 `ed25519_create_keypair`，它自己做 SHA-512 与 clamp），
所以 `export_public_key`、"reload 后公钥一致"都 PASS——这正是这个 bug 能藏住的原因。

**修法**：`ED25519_sign` 里自己完成种子展开——`az = SHA-512(seed)`，clamp `az[0..31]`，
把 `az`（64 字节，标量||前缀）交给 orlp。这就是 RFC 8032 规定的展开，也是 orlp 的 `keypair` 写进私钥缓冲的布局，
所以两者从此一致。

**验证方式不再靠推理**：用 Python 按 RFC 8032 独立实现了一遍 Ed25519，先对齐官方向量
（test vector 2：`pub=3d4017c3…660c`、`sig=92a009a9…0c00`，逐字节相符），确认
"展开种子 + clamp + 上式签名"就是标准算法；随后把这组固定值**嵌进自检**，成为三条不依赖密钥库、
不依赖随机数的已知答案检查：

```
PASS RFC 8032 vector 2: derived public key matches
PASS RFC 8032 vector 2: signature matches the published value
PASS RFC 8032 vector 2: published signature verifies (returned 1)
```

同时修掉一处**误报**：上一轮 `tampered signature rejected` 显示 PASS，但那时有效签名也没过，
"拒绝一个坏签名"毫无意义。现在它在有效签名未通过时明确输出 `SKIP`，不计入通过。

**修复后在模拟器（MuMu，x86_64 / SDK 32）上的完整结果**：

```
PASS RFC 8032 vector 2 × 3
PASS hks_init / generate_key / export_public_key(32) / asymmetric_sign(64)
PASS hks_asymmetric_verify (fresh signature): status=0 (HKS_SUCCESS)
PASS hks_asymmetric_verify (tampered signature rejected): status=-132 (…returned false)
PASS import_public_key / is_key_exist / hks_init(reload) / is_key_exist(self) / identical / delete ×2
verdict: PASS (0 failure(s))
```

顺带把 huks 的状态码翻成名字进日志（`-132` → `HKS_FAILURE (the cryptographic check returned false)`），
下一次失败不用再查表。

**结论**：exchange 阶段在密码学上已经可用——签名与验签都正确、密钥能落盘且跨重开保持同一把。
尚未验证的是它与**真实手机**的互通（手机用同一份 deviceauth 的 `sha256` + `ED25519_sign(hash)` 约定），
也就是下一次车机测试要回答的问题。

### 7.31 在模拟器里跑完整 bind：两侧都是 END_SUCCESS，密钥一致

7.30 修好签名之后，还剩下一个问题：**exchange 这个阶段本身能不能走完**。以前只有车机能回答——先要手机、
先要 PIN、先要发现链路全通，一次测试几分钟，而且失败信息只有一句"密码错误"。

本轮把它变成不必出门的实验：**在本进程里起两个会话**，一个当车机（accessory），一个当手机（centre），
用 `start_pake` 让手机侧开口，然后把两边的数据包互相喂进去。两侧的身份不同（`selftest-car` / `selftest-phone`），
用的是同一个六位码，走的完全是线上那条代码路径（PAKE + exchange + 落盘）。

`Crypto self-test` 末尾新增输出：

```
HiChain loopback: 6 exchange(s); car=END_SUCCESS key=32B error=0, phone=END_SUCCESS key=32B error=0
HiChain loopback: both sides derived the same 32-byte key
HiChain loopback: operation complete car=true phone=true
HiChain loopback: trust cleanup car=0 phone=0 (0 = removed)
```

四点必须分别看：

1. **`car=END_SUCCESS`**：这正是 7.19 那次在车上永远到不了的状态。交换阶段现在真的结束了，
   不再是 `KEY_AGREEMENT_END` 之后断掉、更不是 `END_FAILED(29)`。
2. **两侧密钥相同**：PAKE 与 exchange 用的是同一份材料，说明签名、验签、加密三处都自洽。
3. **`operation complete car=true`**：引擎自己认为整个操作结束（`OVER_STATE`），
   这就是"可以停止喂引擎"的判据，也是 7.23 那个 bug 的反面。
4. **`trust cleanup car=0 phone=0`**：回环会在密钥库里给"对方"存一条信任，测试必须自己擦掉。
   这里报告的是 `delete_local_auth_info` 的返回值，`0` 表示已删除。
   （顺带一说：我本来想直接读 `hks_keystore` 核对，被本机的安全策略拦下了——拦得对，那是密钥材料。
   所以改成让 App 自己报告清理结果，反而更干净。）

**这个实验证明了什么、没证明什么**：

- 证明：从 PAKE 到 exchange 的整条流程、别名生成、长期密钥的生成/导出/签名/验签/导入、
  会话密钥的派生，全部可用；两侧独立算出同一个 32 字节密钥。
- **没有证明**：真实华为手机的实现与我们一致。两侧都是我们自己的引擎，
  所以它能排除"我们内部不自洽"，不能排除"我们与华为的实现理解不同"。
  后者只能靠车机实测。不过 7.22–7.24 的过程已经证明，手机愿意走完 PAKE 并接受我们的回包，
  差异只可能出现在 exchange 这一段——而现在这一段的密码学已经自证了。

**同时完成的两件小事**：`stepDecoded` 的相位名提成 `HiChainNative.describeResult()`（回环按数值跟踪，
不再持有 `Step` 对象）；`forgetPeer()` 是给回环用的清理入口，走引擎自己的 `delete_local_auth_info`，
不直接删文件。

### 7.32 界面汉化（只汉化控件，日志保持英文）

用户提出"可以把 HiCar (experimental) 界面汉化吗"。范围经确认后定为：**控件与状态行汉化，
日志区保持英文**。

原因不是省事，而是日志的用途不同：日志是协议证据，`docs/HICAR_MIGRATION.md` 里 7.x 每段都逐字引用了它，
贴给外部排查时也要能和文档对上；把 200 多行引擎日志译成中文，等于让"文档里的片段"和"屏幕上的片段"
永久失配。界面文字则只服务于站在车机前的人，越像母语越好。

改了什么：

- 标题、链路说明、机型码输入框标签与切换按钮、连接码标题与提示、五个操作按钮
  （开始/停止、清空日志、重置配对、允许被发现、加密自检）、措辞警告、
  导出/分享/嗅探/设备体检/root 探针按钮、状态行、本机标识、导出与分享的提示文案。
- `describe(status)` 全部分支改成中文：`热点 SSID，地址 (backend)`、`等待手机接入`、
  `业务通道已通告，等待认证`、`控制通道已就绪；媒体阶段尚未实现` 等。
  `Blocked` / `Failed` 两个分支保留引擎给的英文原文作为后半段——那行本来就是给排查用的。
- 桌面图标 `activity-alias` 的 `android:label` 从 `HiCar (experimental)` 改为 `HiCar（实验）`，
  与界面标题一致。

**没有改成 `strings.xml` 资源**，这是有意为之，理由写在 `HiCarHostActivity` 的类注释里：

1. 这个界面要在任何 ROM 语言下都显示中文。车机若以英文启动，汉化界面不该跟着变回英文——
   否则最需要看懂的那一屏（为什么配对失败）又会变成外语。
2. `JapaneseTranslationsTest` / `TraditionalChineseTranslationsTest` 要求 `values/` 里每个 key
   在 `values-ja`、`values-zh-rTW` 都有对应条目且占位符一致。为一个诊断界面新加几十条 key，
   就得同时补日文与繁中译文；这批文案还会随实测继续改，翻译债只会越滚越大。

副作用（明说）：本界面不跟随 App 的语言设置，任何语言环境下都是简体中文；
其余界面仍走原来的多语言资源，行为不变。

**未改动的部分**（仍为英文）：日志行（含本界面自己 `appendLog` 的那些，如 `Pairing reset:`）、
导出的报告文本（`HiCarLogReport`，字段名 `connection code:` 等被 `HiCarExportUiTest` 断言）、
`HiCarDeviceAudit` 的体检块、蓝牙设备名 `HiCar receiver`（这是发给手机的名字，不是界面文案）。

### 7.33 手机连上来了，但它在 AUTH_START 之后自己中止（信息被我们吞掉了）

这一轮（04:05）与前几轮不同的第一件事：**手机真的连上来了**，日志里有 6 次
`HiCar control/business channel connected (peer F8:3B:7E:3E:DD:06)`。用户看到的现象是
"来不及输密码弹窗就消失了，然后再次要求输密码，最后弹出连接失败"——和日志完全对得上：

```
HiChain begin: role=accessory peer=01CF…017E pin=61…08
HiCar identity exchanged
HiChain step 1: engine=0, phase=KEY_AGREEMENT_PROCESSING, key=0B, error=0, out=1
HiChain step: 1 packet(s) for message 17
HiChain step 2: engine=0, phase=END_FAILED, key=0B, error=0, out=1
  engine reply 1: {"message":32896, "payload":{"errorCode":5}}
HiChain step: 0 packet(s) for message 32896
```

读法：手机先发 `message 17`（`AUTH_START_REQUEST`，带 `keyLength`），我们答了一条
（此时引擎认为还在 `KEY_AGREEMENT_PROCESSING`），**然后手机发来的是 `0x8080 INFORM`**——
也就是说，是手机在说"我不干了，这是我的原因"，PAKE 根本没开始，所以弹窗连输入的时间都没有。

**为什么看不到手机的原因**：我们自己的引擎不认识 inform。
`hichain.c` 的 `build_struct_by_receive_data()` 表里只有请求/响应，没有 `INFORM_MESSAGE`，
于是它把手机的 inform 判成"未知消息"，回了一条 `errorCode=5`（`HC_UNKNOW_MESSAGE`）的 inform。
两份互不理解的通知撞在一起，留下的只有我们的 5，把手机的理由盖掉了。

三处改动：

1. **主动识别手机的 inform**（`HiCarAuth`）：遇到 `message == 0x8080` 就记下它的
   `payload.errorCode` 并用 `HiChainCodes` 翻译成人名，然后按协议结束会话，不再回一条 inform
   去对撞。这样"手机为什么不干"第一次会出现在导出日志里。
2. **同一条连接上重复的身份不再断开**：04:05 的日志里 `HiCar protocol stopped: identity state`
   出现两次，就是手机重发 identity（它自己的尝试超时后重来）被我们当成错误关掉的。现在同一个
   `deviceId` 重发时幂等地再答一遍身份；只有 `deviceId` 变了才仍然判为违约。
3. **引擎自己的日志接出来了**。以前看不到引擎说话有两个原因：`CMakeLists.txt` 里定义了
   `_CUT_LOG_`（把每个 `LOG*` 编译成空），而 `registe_log` 从来没被调用（默认实现是空函数）。
   现在两者都改掉：删掉 `_CUT_LOG_`，在 `init` 里 `registe_log` 注册一组写 logcat 的函数，
   tag `HiChain`。DEBUG 级别丢弃（一次配对能刷几百行，会把有用的 INFO/ERROR 埋掉）。

   ```bash
   adb logcat -s HiCar HiChain     # HiCar=界面逐行镜像，HiChain=底下的引擎
   ```

   这条通路在模拟器上验证过：完成一次 loopback 后 tag `HiChain` 有 80 行 INFO，
   `D HiChain` 0 行（DEBUG 已按设计丢弃）。

新增的诊断行（都在导出日志里）：

- `HiCar in: oper_type=… message=17 (0x0011 AUTH_START_REQUEST) keys=… 123B {…}`：手机发来的每一条，
  带消息名、字段名、长度和前 240 字符。
- `HiChain out: {…}`：我们回给手机的那条原文（这是下一个要看的重点：手机是在判我们的
  `0x8011` 内容）。
- `HiCar: the phone ended the operation itself (inform, errorCode=N NAME)`：手机的失败原因。
- 开始/停止行带上本次会话的连接码与连接数（7.33 之前的 `receiver started` 行没有码，
  中途重置会把某次尝试标成它没用过的码）。

**还没证明的**：手机为什么在 `0x8011` 之后立刻放弃。本地 loopback（7.31）证明两侧引擎自洽，
但那是我们自己的引擎当客户端；对方是华为的实现，差异只可能在我们那条 `0x8011` 的内容上
（字段编码、`authId`、或它期望的某种 `authForm`）。下一步要的是：手机 inform 里的那个
`errorCode`，以及同一行下面打印的我们 `0x8011` 原文。

一个构建注意：`:hicar-auth:externalNativeBuildDebug` 之后单独跑 `:mobile:assembleDebug`
有时不会重打包 .so（Gradle 认为 up-to-date），删掉
`mobile/build/outputs/apk/debug/mobile-debug.apk` 再打包即可；验证方法是装到设备上数
`adb logcat -s HiChain | grep -c ' D HiChain'`，改过 DEBUG 策略后应为 0。

### 7.34 手机说 HC_VERIFY_PROOF_FAILED，而且它要的是"认证"不是"配对"

7.33 加的三行诊断立刻给出了答案。04:22 这一轮，手机连上来之后的第一条业务消息是：

```
HiCar in: oper_type=3 message=17 (0x0011 AUTH_START_REQUEST) …
  {"authForm":0,"message":17,"payload":{"challenge":"1083…0A7D","epk":"1042…C92B",
   "operationCode":2,"version":{"currentVersion":"1.0.0","minVersion":"1.0.0"},"pkgNa…

HiChain out: {"authForm":0,"message":32785,"payload":{"authData":"60A4…485F",…

HiCar in: … message=32896 (0x8080 INFORM_MESSAGE) … errorCode=17 HC_VERIFY_PROOF_FAILED
```

三个字段放在一起，指向同一件事：

1. **`operationCode: 2`**。`hichain.h` 的枚举是 `NO_OPERATION_CODE=0, BIND=1, AUTHENTICATE=2, …`。
   手机请求的是 **AUTHENTICATE**，不是 BIND——它自认为和这台车已经配对过。
2. **`auto_connect_state: true`**（它 oper_type 1 的身份报文里）。这是一次"自动重连"，不是首次连接。
3. **全程没有 `PAKE_REQUEST`**。整个会话只有 identity → AUTH_START → inform。PAKE 是唯一会用
   6 位连接码的环节，它没发生，所以"来不及输密码"不是错觉：**手机根本没打算问**。

于是 `errorCode=17 HC_VERIFY_PROOF_FAILED` 的含义很具体：手机用它保存的材料校验我们给出的
`authData`（证明），校验不过——因为我们这边的信任早就被「重置配对」清空了。两边各有一半，
它那一半还在，我们这一半没了，任何重试都不可能对上。

**这是"重置配对"造成的不对称**：那个按钮清的是我们库里的 `hicar-huks`，
清不掉手机里那条记录。之前每一轮按一次，手机那条记录就变成孤儿，直到它开始按
"已配对" 流程来认证，然后永远失败。

处理（按顺序）：

1. **手机上删掉这条记录**：设置 → 更多连接 → HiCar → 已连接的车/我的车 → 删除；
   蓝牙里把 `宋Plus` 也取消配对（车机侧 `bonds` 里就有 Mate 80）。之后车机上直接「开始」，
   **不要再按重置配对**，手机上会停下来问码，输入屏幕上的 6 位即可。
2. 如果手机上找不到可删的条目（配对从未完成时它可能不出现在列表里），用 7.34 新增的
   **「换一个本机标识」**：把 `deviceId` 换掉，手机就会把这台车当成一台它没记录的新车，
   流程回到首次 BIND。实现是 `HiCarHostActivity.rotateIdentity()`——随机 16 位十六进制，
   写进 `hicar-receiver` 的 `local_identity`，重启保留；`identity()` 优先读它。
   已在模拟器上验证：`c404cddd…` → `c6664573…`，界面上的「本机标识」与派生的机型码同步变化。

日志侧也补了一句：`errorCode=17` 的 inform 现在会附上这个解释和该做什么，不必再回头读文档。

**这一轮确认没问题的部分**（都不再是怀疑对象）：5 GHz 热点（channel 36 / 5220 MHz，verified）、
RFCOMM 两个通道、identity 交换、广播 payload（`… 04 10 20`，末字节每轮递增）、手机侧 BLE 可见。
链路上唯一断掉的地方就是上面那一步认证。

**教训写在这里**：`重置配对` 是"让车机忘记手机"，它不能"让手机忘记车机"。
排查期间反复按它，只会把两边的状态越推越远。

### 7.35 认证之后：把手机真正要连的三个 TCP 端口开出来（照参考实现补齐）

7.34 之后，10:01 那一轮 HiChain 全通了（PAKE → 32 字节密钥 → exchange → `END_SUCCESS`），
但日志结尾仍是三十秒沉默后 `HiCar channel closed: IOException` ×2。原因写在我们自己的代码里：
`openListener()` 的注释是 "Nothing calls this yet"——**三个 TCP 端口一个都没开**。

对照参考实现（`docs/HICAR_REFERENCE_MAP.md`）后，缺的就是这一段：

| 端口 | 分帧 | 作用 |
| --- | --- | --- |
| 4005 `controlPort` | nearby wire（`0x7F`+kind） | 加入热点后的补做：能力/数据端口/选网/凭据 |
| 4006 `+1` | DMSDP 模块（`0x7F`+module+len+CRC） | 入口握手，回 16 字节，第 1、2 字节 = 4007 |
| 4007 `+2` | DMSDP 记录（`marker`+len） | 明文 `OPTIONS` → `CryptoSwitch 222` → AES-CTR → 心跳 → `$` 业务包 |

本轮新增三个文件：

- `HiCarDmsdpTransport.kt`：模块帧与增量解码器、入口应答（端口写进应答的第 1、2 字节）。
- `HiCarDeviceBusiness.kt`：模块 3 的 101（身份）与 104（服务目录），`$` + 11 字节头 + varint 指令。
- `HiCarDmsdpEndpoints.kt`：三个监听。每条连接两次校验——**只接受本轮发过凭据的那个手机地址**，
  且**必须已认证**；控制通道 1 秒心跳、30 秒空闲断开、同一时刻只允许一条。

`HiCarController` 的两处接线：

- 热点就绪就开监听（早于认证）。端口先绑着，拒绝时能说清"是没开"还是"被拒"。
- 认证成功时把会话密钥**复制**给 TCP 侧：RFCOMM 关闭时 `HiChainAuthenticator.close()` 会把它的副本清零，
  而手机这时候通常已经在连 TCP 了。

故意没开的一处：**显示服务不宣布**（`displayReady = { false }`）。宣布了却放不出画面，
手机只会报"连接失败"，比"没这功能"更难查；等 HiSight 与解码器接上再打开这一位。

新增测试 `HiCarDmsdpTransportTest`（6 个）：入口应答的端口字节、CRC 损坏必须拒绝、
101 回包的身份/地址/businessId、解码器不存在时不宣布、存在时只宣布一次、104 早于 101 必须拒绝。

**下一次上车应该看到的新行**（按顺序）：

```
DMSDP: listening on <ip> ports 4005, 4006, 4007 (network, entry, control)
HiChain finished: 32-byte session key; TCP endpoints are serving this key, so DMSDP can start on the access point
DMSDP: network connection accepted from 192.168.x.y
DMSDP entry: module 1 → control port 4007
DMSDP: control connection accepted from 192.168.x.y
DMSDP: control OPTIONS greeting sent
DMSDP: DMSDP AES-CTR negotiated
DMSDP: DMSDP control message: SET_PARAMETER ...
DMSDP device info: answered with HiCarLab, model …
DMSDP service request: mask 0x… wants display …; display ready=false, announced=false
```

看到 `service request: mask` 就说明手机已经认可这条连接并开始要服务了——下一步（HiSight 视频）
才有意义；如果卡在更前面，那一行自己会说卡在哪。

### 7.36 媒体层一次性补齐：画面、声音、触控、麦克风

用户的要求很直接："把后面要开工的参考已有的项目一次性做完，减少我要上车的次数。"
所以这一轮不再按"先视频、再音频、再触控"分批，而是把 7.35 之后剩下的整段一次性移植完，
让下次上车一次就能看到链路走到底。

**移植清单**（逐文件对照见 `docs/HICAR_REFERENCE_MAP.md`）：

| 新增文件 | 来源（MIT） | 作用 |
| --- | --- | --- |
| `HiCarHiSightWire.kt` | `HiSightWire` | 视频 RTSP：`ANNOUNCE`(aes128ctr) → `OPTIONS`/`GET_PARAMETER`/`SET_PARAMETER` → `SETUP` → `PLAY`/`TEARDOWN` |
| `HiCarHiSightClient.kt` | `HiSightClient` | 控制 / 视频 / 输入三条 TCP，媒体线程、断流与暂停判定 |
| `HiCarHiSightMedia.kt` | `HiSightMedia` | RTP → PES → 解密 → H.264 访问单元，序列跳变即丢弃半帧 |
| `HiCarVideoSink.kt` | `VideoSink` | `MediaCodec`（H.264）解码到 `SurfaceTexture`，8 帧有界队列，窗口可分离 |
| `HiCarAudioWire.kt` | `AudioMedia`/`MicrophoneWire` | 加密 UDP 记录、RTP/ADTS 拆帧、麦克风 RTP 打包 |
| `HiCarAudioSink.kt` | `AudioSink` | AAC → PCM16 → `AudioTrack`，音频焦点，格式变化即重建 |
| `HiCarAudioReceiver.kt` + `HiCarSpeakerControl` | `AudioReceiver`/`SpeakerControl` | UDP 接收（只认本轮协商的地址与端口）+ `speaker1` 文本协商 |
| `HiCarMicrophone.kt` | `MicrophoneSender`/`MicrophoneControl` | `mic1` 协商 + `AudioRecord`→AAC→RTP→UDP，默认关闭 |
| `HiCarUiInput.kt` | `UiInput`/`InputChannel`/`InputRouter` | UIBC 编码、按下/抬起配对、发送队列、800×480 坐标映射 |

`HiCarControlSession` 增加了 `TextHandler`：带 `serviceid` 的文本先交给扬声器/麦克风处理，
处理不了再落到通用分支；`HiCarDeviceBusiness` 的 104 服务目录现在按"显示 / 扬声器 / 麦克风"
三项各自是否就绪分别宣布。

**协议上两处容易搞错、这次按参考纠正的**：

1. **视频走 TCP，不是 UDP**。手机在模块 5 的 101 里给一个端口，我们 dial 过去做 RTSP，
   `SETUP` 的 `Transport` 里 `server_port` 才是收流的端口（仍是 TCP）。以前文档里写的"视频 UDP 收流"是错的。
2. **加密只覆盖 PES 负载**：RTP 头是明文，`0x84` 那 14 字节 PES 头也明文，从第 14 字节起才是 AES-CTR 密文。
   所以拆包必须先把整段 PES 重组完再解密，逐包解密会把 IV 用错。

**保留的谨慎**（都是故意的）：

- 显示服务只在 `video.ready == true` 时宣布；解码器起不来就不宣布，手机看到的是"没有这个功能"，
  而不是"连上了但黑屏"。
- 麦克风默认关闭：需要 `RECORD_AUDIO` 权限 + 界面上手动打开；关闭时对 `mic1` 回 403。
- 界面上的按键只发 返回(4) / 主页(3)，方向盘按键码没有验证过，不宣布。
- `deviceId` 仍然由 `ANDROID_ID` 派生（可轮换），没有改成参考实现的"蓝牙地址派生"：
  改了之后已经配对成功的手机把车当成新车，又要输一次码，等链路稳定再评估。

**界面新增**：标题下的黑色画面区（`TextureView`，800×480，可直接触控）、「返回 / 主页 / 麦克风」一行。

**模拟器上已验证的部分**（没有手机、没有蓝牙，只能验证到这一层）：

- 新包安装启动无崩溃；
- `HiSight: H.264 decoder ready (800×480)` 与 `video: window attached to the decoder` 出现在同一轮启动里，
  说明解码器与该窗口接的是同一块纹理；
- 新增 19 个协议单元测试全绿（`HiCarHiSightWireTest` / `HiCarHiSightMediaTest` / `HiCarAudioAndInputTest`），
  `:shared` 的 hicar 包累计 100 个测试 0 失败。

**下一次上车要看的日志顺序**（任何一行断了，那一行就是结论）：

```
DMSDP: listening on <ip> ports 4005, 4006, 4007 (network, entry, control)
HiSight: H.264 decoder ready (800×480)
DMSDP service request: mask 0x… wants display speaker microphone; display ready=true …
DMSDP display service announced: 800×480
DMSDP media endpoint: the phone serves video on 192.168.x.y:<port>
HiSight: ANNOUNCE sent to 192.168.x.y:<port>; waiting for the phone's video negotiation
HiSight: response 200 for sequence N
HiSight: SETUP sent, waiting for the phone to name the video port
HiSight: video port negotiated; input port also offered
HiSight: video socket connected to …; PLAY follows
HiSight: waiting for encrypted H.264 data
HiSight: 1 H.264 access unit(s), 0 dropped, N bytes last
audio: AAC 48000 Hz, 2 channel(s); playback started          ← 有声音才会有
speaker: SETUP sent (AAC over UDP, encrypted, RTCP off)
UIBC: 1 encrypted input event(s) sent                         ← 手指碰到画面才会有
```

判定：出现 `H.264 access unit` 但画面是黑的 → 问题在解码/显示（把 `video: decoder output W×H` 一并发我）；
停在 `ANNOUNCE sent` → 手机的视频端口不接受我们的 RTSP，需要看它的 `response` 序列号；
停在 `service request` 之前 → 手机没有进入服务目录，那是控制通道的事，不是媒体的事。

### 7.37 认证两次都成功，手机却一次没拨 TCP：凭据命令根本没发出去

11:02 这轮日志有两个事实放在一起，指向同一个结论：

```
HiChain step 3: engine=0, phase=END_SUCCESS, key=32B, error=0, out=1   ← 第一次，成功
HiChain step 3 completed the operation (phase=END_SUCCESS), out=1
HiCar waiting for the phone: 30s, connections=2, advertising=on …
HiCar channel closed: IOException ×2
…（第二次重连，同样到 END_SUCCESS）…
HiCar waiting for the phone: 60s, connections=4
```

**认证完全成功，会话密钥 32 字节，两次都是；但 4005/4006/4007 一次都没有被连过**
（既没有 `DMSDP: network connection accepted`，也没有任何"拒绝"行——不是被拒，是没来过）。
同时，**0xFE35 控制通道上一条帧日志都没有**，因为那条路径压根不打印帧。

对照参考实现的 `ReceiverEngine` 才看出问题：手机在认证后会在 0xFE35 上跑一段 Wi-Fi 协商
（kind 4 能力 → kind 4 数据端口 → kind 4 选网 → kind 33 要凭据）。其中"选网"那一步，
车机要回的不是普通应答，而是**队列里的一条命令**（kind 33 tag 1，"把你的 station 地址给我"）：

```java
// 参考实现
if (frame.kind == 4 || frame.kind == 33) replyNetwork(frame, session.network, out);

private void replyNetwork(...) {
    byte[] reply = resource.respond(frame);
    if (reply != null) { out.write(reply); out.flush(); }
    for (byte[] pending : resource.takePending()) { out.write(pending); out.flush(); }   // ← 关键
}
```

我们的 `HiCarNetworkNegotiator` 完全一样地把这条命令放进了 `pending`，但是**冲刷队列的代码只写在
TCP 的 network 端口（`HiCarDmsdpEndpoints.serveNetwork`）里**，0xFE35 那条分支只写了即时应答：

```kotlin
val reply = when { … liveNetwork?.respond(frame) … }
if (reply != null) { output.write(reply); output.flush() }   // pending 从未写出
```

于是手机停在"等车机告诉我可以给了"的姿势上，30 秒后自己关掉两条通道——这正是日志里
`channel closed` 出现的位置，也解释了为什么 TCP 端口一次都没被拨。**凭据串（SSID/密码/hicarApIp）
从来没有离开过车机。**

三处改动：

1. **0xFE35 分支冲刷 `takePending()`**，与参考实现对齐；每条帧顺带记录
   `HiCar control frame kind=…, N bytes, reply M, queued K`，以后这条路径不再是黑盒。
2. **网络协商加步骤日志**（`HiCarNetworkNegotiator` 接受 `onLog`）：
   `network capability accepted: 5220 MHz`、`data endpoint answered: TCP 4006`、
   `network selected; asking the phone for its station address`、
   `station address AA:BB:… accepted; credentials sent for '…' on 5220 MHz, control port 4005`。
   密码本身不打印（它在凭据串里）。
3. **30 秒心跳带上协商状态**：`credentials=true/false, network=asked/silent`。
   下次看一行就知道是"手机没问"还是"问了、给了凭据、还是没连上"。

另外顺手把"网络帧在密钥可见之前到达"从静默丢弃改成明确报错行：
`HiCar network frame kind=4 arrived before the session key was visible; not answered`。
这条以前只会表现为"手机不说话"，现在它会自己说出来。

回归测试：`HiCarNetworkTest.theSelectionCommandIsWrittenBackOnTheSameChannel` —— 用一个只做
"即时应答 + 冲刷队列"的 `MiniChannel` 走完两步，断言那条 kind 33 命令确实被写出。
之前的测试是手工调 `takePending()` 的，所以**测试全绿但控制器漏了冲刷**；现在测的是控制器的契约。

**下一次上车要看到的顺序**（这次应该能走到 TCP）：

```
HiCar control frame kind=4, … reply … , queued 0        ← 手机来问能力
network capability accepted: 5200 MHz
HiCar control frame kind=4, … reply … , queued 0        ← 手机要数据端口
data endpoint answered: TCP 4006
HiCar control frame kind=4, … reply 0, queued 1         ← 选网，车机给出命令
network selected; asking the phone for its station address
HiCar control frame kind=33, …                          ← 手机报 station 地址
station address … accepted; credentials sent for '…' on 5200 MHz, control port 4005
DMSDP: network connection accepted from 192.168.x.2
DMSDP entry: module 1 → control port 4007
DMSDP: control connection accepted from 192.168.x.2
DMSDP AES-CTR negotiated
DMSDP device info: answered with HiCarLab, model 00040A04
DMSDP service request: mask 0x… wants display …; display ready=true
DMSDP display service announced: 800×480
DMSDP media endpoint: the phone serves video on 192.168.x.y:<port>
HiSight: ANNOUNCE sent …
```

若停在 `network capability accepted` 之后没有下一步，说明手机没接受 5 GHz 通道或我们的端口号；
若出现 `credentials sent` 但一直没有 TCP，那边就是手机加入热点失败（BSSID/密码），
心跳行会显示 `credentials=true`。

### 7.38 手机终于连上 TCP：卡在我们自己的 RTSP 头语法上

11:11 这轮是第一次真正走通到媒体层，日志按顺序：

```
HiCar control frame kind=4 … → network capability accepted: 5180 MHz
HiCar control frame kind=4 … reply 0, queued 1 → network selected; asking for station address
station address 56:ad:33:84:1f:0d accepted; credentials sent for 'DIRECT-…-SFaH' on 5180 MHz, port 4005
DMSDP: network connection accepted from 192.168.49.2
DMSDP entry: module 1 → control port 4007
DMSDP: control connection accepted from 192.168.49.2
DMSDP AES-CTR negotiated
DMSDP device info: answered with HiCarLab, model 00040A04, 192.168.49.1 ↔ 192.168.49.2
DMSDP service request: mask 0x31f wants display speaker microphone …
DMSDP display service announced: 800×480
DMSDP speaker service announced: AAC 48 kHz
DMSDP media endpoint: the phone serves video on 192.168.49.2:41129
HiSight: ANNOUNCE sent … → response 200 → ANNOUNCE → RTSP encryption negotiated
HiSight: OPTIONS, 34-byte body
HiSight rejected: rtsp header
```

也就是说 7.37 的修复是对的：凭据发出去了、手机加入了热点（192.168.49.2）、三个端口都拨了、
设备信息交换成功、服务宣布成功、视频端口也拿到了。**断点只剩一个：手机发来的 `OPTIONS`
被我们自己的头语法拒绝。**

原因很具体：我们的解析器要求每个头名匹配 `[a-z][a-z0-9-]{0,63}`，下划线不在里面。
手机这条 `OPTIONS` 里有带下划线的头（`his_*` 一类），于是 `parse` 抛 `rtsp header`，
整条视频会话在第二个消息就结束了——参考实现里同样的正则只是因为它没在这台手机上跑到这一步。

改动：

1. **头解析改成容忍式**：行先 `trim`，空行跳过，缺冒号的行记一行日志后跳过，
   名字不在常见语法内**只记日志、照样收**（语法放宽到 `[a-z][a-z0-9._-]*`）。
   真正的协议错误（重复头、超长、非 ASCII）仍然拒绝。
2. **每条 RTSP 消息记下方法名与头名列表**（`HiSight: OPTIONS headers=[cseq,…]`），
   只记名字不记值——下次不用再为"它到底发了什么头"跑一趟。
3. **解析失败时打印那一条报文的可打印文本**（≤400 字符，控制字符转义）。只在已经失败时打印，
   且是协议文本、不含凭据。
4. **顺带修一个重连 bug**：`HiCarDeviceBusiness` 的状态（`exchanged`、三个 `announced`）
   原来跨控制连接共享。手机重连后会重新问身份、重新要服务，而我们会回"repeated; ignoring"，
   等于让它等一个我们决定不再给的回答。现在每条新的控制连接都 `resetForNewSession()`，
   并记一行 `DMSDP: control session opened; device and service state reset`。

回归测试：`a header whose name uses an underscore is still read`、
`a blank header row is skipped rather than rejected`。

**关于"第一次要开关飞行模式"**：那一次是手机侧的 Wi-Fi 没关联上（上一轮遗留的直连状态），
第二次同一台车、同一份凭据就正常加入了（`192.168.49.2`，DHCP 与 `client##` 一致），
不是车机侧的问题。真机上如果又出现，先看心跳行是不是 `credentials=true`：是就说明凭据给过了，
问题在手机加入热点那一步。

**下一次该看到**：

```
HiSight: OPTIONS headers=[cseq,…]                 ← 头名列表会告诉我们它到底要什么
HiSight: GET_PARAMETER headers=[…]                ← 我们会按它问的字段回能力
HiSight: SET_PARAMETER headers=[…]                ← 带 his_presentation_url + trigger SETUP
HiSight: SETUP sent, waiting for the phone to name the video port
HiSight: video port negotiated; input port also offered
HiSight: video socket connected …; PLAY follows
HiSight: waiting for encrypted H.264 data
HiSight: 1 H.264 access unit(s), 0 dropped, N bytes last
audio: AAC 48000 Hz, 2 channel(s); playback started
```

若 `OPTIONS` 之后仍缺某一步，新加的头名列表和失败转储会直接指出是哪一条、哪个头。

### 7.39 不再猜：把参考实现放进测试当基准，顺手修掉三个确定的缺陷

用户的批评很直接："不要自个儿盲猜，我之前给你的参考项目里面已经有能够跑通的流程了。"
这一轮换做法：**先读完全部参考源码（3192 行），再把它搬进测试做逐字节对照**，然后只改有证据的地方。

#### 一、11:11 那次失败的重新判读（上一轮的结论是错的）

旧结论说"手机发来的 `OPTIONS` 被我们的头语法拒绝"。日志的真实顺序是：

```
121: HiSight: ANNOUNCE, 108-byte body
122: HiSight: RTSP encryption negotiated (aes128ctr)
123: HiSight: OPTIONS, 34-byte body          ← 这条已经解析成功
124: HiSight rejected: rtsp header           ← 失败的是紧接着的下一条记录
```

`OPTIONS, 34-byte body` 这行是在头解析**之后**打印的（`parse` 里 `onLog` 的位置在 header 循环之后），
所以 OPTIONS 本身没有问题。**失败的是它后面那条记录**——那条记录连一行日志都没留下，因为它在头解析阶段
就抛了。上一轮我改的"放宽头名/跳过缺冒号行"虽然没错，但并不是这次失败的原因。

#### 二、把参考实现放进测试：`HiCarHiSightReferenceParityTest`

`shared/src/test/java/com/shilapi/xcertplay/hicar/reference/` 下是参考项目的
`HiSightWire.java`、`NearbyWire.java`、`SessionCipher.java` **原样副本**（MIT，只改了 package 和可见性，
每处改动都有注释说明仅用于测试）。测试做四件事：

1. **`initial()` 逐字节相同**：两边发出的明文 `ANNOUNCE` 完全一致。
2. **完整握手的每条回复相同**：脚本按 11:11 的真实顺序走一遍
   （明文 200 → 手机 ANNOUNCE → OPTIONS → GET_PARAMETER → SET_PARAMETER 触发 SETUP → 200 带端口），
   逐条比较解密后的文本、条数、`mediaPort`/`inputPort`。
3. **`PLAY`/`TEARDOWN` 相同**。
4. **坏记录不再杀会话**：一条没有 CSeq 的记录被跳过并写日志，后续仍能协商出 41129/41234。

手机侧的记录是用**我们的 cipher 加密**后喂给参考实现的——参考能解开，本身就证明了两个 cipher 等价
（AES-128-CTR、key 取会话密钥前 16 字节、每条记录前置 16 字节随机 IV）。

这四条约等于"我的移植不是我以为的移植"，而是可执行的等价性证明。

#### 三、据此修掉的三处

1. **单条记录失败不再结束视频会话**。RTSP 有 4 字节长度分帧，下一条记录仍然可定位。现在只有三种
   会破坏分帧的失败才终止：`rtsp size`、`rtsp buffer`、`rtsp cipher selection`（ANNOUNCE 没选中加密，
   之后全解不开）。其余记录被跳过，把可打印文本写进日志（≤400 字符），会话继续。
2. **跳过之后还要回 200**。手机在等那条报文的 200，不回它就会自己把会话关掉。所以只要报文的第一行
   是一个请求方法、且能找出 `CSeq: N`，就回一条 `RTSP/1.0 200 OK`。日志里会写
   `answering that <METHOD> with 200 so the phone does not wait for it`。
3. **`his_presentation_url` 的字符集放宽**到 `rtsp://[!-~]{1,256}`（可打印 ASCII、不含空格）。
   参考是 `[A-Za-z0-9.:/_?=&%-]`，URL 里只要有一个 `~`、`+`、`@`、`;` 就会整条会话失败——
   为一个字符丢掉整个画面不值得。空格仍然禁止，因为这个值会被原样拼进请求行。

顺带补了参考有而我们缺的一处：设备信息里的 `properties["5002"] = true`（有可用麦克风时），
没有它手机不会发起 `mic1` 协商。

#### 四、自动重连：一轮结束就重新等待，不用再按一次"开始"

参考实现的 `requestReconnect` 在每条会话结束时整轮重启（`stopInternal` → `video.restart()` → 1.5 秒后
`start(...)`），我们之前没有：手机一断开，广播和监听就都没了，每次重试都要人手动点一次。

现在 `HiCarController` 有"轮次"概念：

- `generation` 每轮加一。accept 循环、心跳、`serve` 都用 `generation == round` 判断自己是否过期，
  过期就退出——这比中断每个线程可靠，也是参考实现的做法。
- `releaseChain(keepVideo)` 把清理抽出来：`close()` 用它并把 `keepVideo=false`（用户主动停止）；
  重连用它并保留解码器与窗口（`HiCarVideoSink.restart()`，参考的 `VideoSink.restart` 等价物）。
- `scheduleReconnect` 只在**本轮**通道关闭时触发，且用一把锁保证一轮只重启一次（控制与业务通道
  通常同时关闭）。等 1.5 秒、重建线程池、重新走 `startChain()`，连接码与已配对信任都不动。
- 用户主动"停止"时 `running` 已是 false，`scheduleReconnect` 直接返回——模拟器上已确认停止日志之后
  没有出现 `releasing this round`。

#### 五、模拟器上抓到的真 bug：第二次启动解码器起不来

连着按两次"开始"，第二次出现 `HiSight: video start failed (CodecException)`。

原因在 `HiCarVideoSink`：它把 `SurfaceTexture` 当自己的资源，`closeDecoder()`/`close()` 里无条件
`texture.release()`。可一旦 `attach()` 采用过 `TextureView` 自己的纹理，被释放的就是**视图的**纹理，
那个视图之后再也产不出帧。参考实现的 `detach` 返回 `false` 正是为了说"这个纹理不是我的，你（视图）自己释放"。

改法：新增 `ownedTexture`，只有 sink 自己 `SurfaceTexture(false)` 创建的才允许释放；
`closeDecoder()` 只 release `Surface`、把纹理引用置空；`detach()` 只在 `ownedTexture === value` 时返回 true。

验证（MuMu，Android 12 x86_64）：

```
--- receiver started 11:29:13 with code 340978 ---
HiSight: H.264 decoder ready (800×480)
video: window attached to the decoder (decoder=ready, …)
HiCar receiver stopped; connections=0
--- receiver started 11:29:22 with code 340978 ---
HiSight: H.264 decoder ready (800×480)          ← 第二次也正常，CodecException 消失
video: window attached to the decoder (decoder=ready, …)
```

#### 六、还在猜的部分（明确标出，不假装）

`OPTIONS` 之后那条记录到底是什么内容，现有日志里没有——那一轮还没有记录转储。现在有了：
任何被跳过的记录都会打印 `record ignored as <category>; session continues >> … <<`。
下一次上车，**即使仍然失败，失败原因会直接写在日志里**，不需要再靠推测。

测试：`:shared` 的 hicar 包 111 个用例 0 失败（新增 4 个差分用例），`HiCarExportUiTest` 与两个翻译测试通过。
