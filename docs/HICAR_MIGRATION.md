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
- HiCar 界面新增桌面图标 **HiCar (experimental)**（`activity-alias`，独立入口，不进 DiPlay 主界面），
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
