# HiCar 接收端迁移：实现状态、能力缺口与后续步骤

本文件对应分支 `feat/hicar-migration`。侦察结论见 [HICAR_RECON.md](HICAR_RECON.md)。
本文只写已经完成并经过验证的内容，以及**明确做不到**的部分。

结论先行：**这个分支交付的是"协议层真实、可编译、可单测的 HiCar 车机侧骨架"，不是能连上手机出画面的成品。**
卡点是 HiChain 鉴权的**原生引擎没有集成进 APK**——不是"做不到"：OpenHarmony 以 Apache-2.0 公开了
`deviceauth_lite`（HiChain Lite）与 `huks_lite`（软件密钥库），两个参考项目都是把这套 C 源码编进 APK 后跑通的，
首连靠车机显示的六位连接码授权，**不需要华为签发的配件证书**。因此代码在 HiChain 处
**直接失败并上报原因**，而不是伪造一个会话密钥；集成路径见第 6 节。

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
| 界面 | `HiCarHostActivity`：启动/停止、状态、日志（200 行环形）、权限申请 | 编译通过 |

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

### 2.1 阻断性缺口

| 缺口 | 原因 | 后果 |
| --- | --- | --- |
| **HiChain 鉴权** | 本仓库未集成 `deviceauth_lite` 原生引擎（公开、Apache-2.0、参考项目已跑通，属**未做**而非**不可做**） | 手机在交换身份后要求 HiChain，会话在此终止，`HiCarStatus.Blocked` |
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
- 已知本地环境问题：首次切到 release 变体时 `configureNdkBuild*` 偶发
  `hash_key.txt 拒绝访问`（Windows 文件占用），重试即通过；CI 在 Linux 上未观察到。

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

### 落到本项目的步骤（未做，按顺序）

1. **引上游源码而非参考项目代码**（原任务要求"不要直接复制参考项目代码"，且参考项目的 JNI/适配层与其上游许可混在一起）。
   新建独立模块 `hicar-auth`（`com.android.library`），`externalNativeBuild.cmake`，源码放 `hicar-auth/src/main/cpp/third_party/`，
   保留 `LICENSE`/`NOTICE`。**不要动 `shared` 现有的 ndkBuild（speexdsp）**，两个原生构建分开。
2. 写**我们自己的** JNI：`init(path, seed)`、`create(self, peer, pin, role)`、`step(packet, keyLength)`、`sessionKey()`、`close()`。
   HUKS 文件回调必须 `O_NOFOLLOW` + 名字白名单 + 私有目录，越界即失败。
3. Kotlin 侧实现已有的 `HiCarAuthenticator` 接口：把 `HiCarIdentityExchange` 的 `oper_type = 3` 消息交给 native `step`，
   密钥只从 `sessionKey()` 取；`HiCarUnavailableAuthenticator` 换成 `HiChainAuthenticator`。
4. 界面：`HiCarHostActivity` 展示六位连接码并支持重新生成（重新生成必须清除 auth info，否则旧的信任关系对不上）。
5. 验证顺序：先只验"身份交换 + HiChain 完成 + 拿到 32 字节密钥"，再谈 DMSDP、热点、HiSight。

### 已知风险（来自参考项目自己的记录，不是推测）

- 参考实现明确写"重启后自动回连仍未可靠解决"，不要把它当作已完成能力；
- 部分测试设备**首次需要先在系统蓝牙设置里做普通配对**才行（与协议层"不 bonding"的描述冲突，属厂商蓝牙栈行为），我们的实现里不能假设一定不需要；
- 32 位车机、Android 15 车机在参考项目中未实测；本项目要求 4 个 ABI + Android 15 的 16 KB page size；
- HUKS lite 的接口与上游 commit 强绑定，升级上游要重新验证；
- APK 体积与启动开销会增加（mbedtls/openssl/cJSON + 三个上游子集）。
