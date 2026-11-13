# HiCar 接收端迁移：实现状态、能力缺口与后续步骤

本文件对应分支 `feat/hicar-migration`。侦察结论见 [HICAR_RECON.md](HICAR_RECON.md)。
本文只写已经完成并经过验证的内容，以及**明确做不到**的部分。

结论先行：**这个分支交付的是"协议层真实、可编译、可单测的 HiCar 车机侧骨架"，不是能连上手机出画面的成品。**
卡点是 HiChain 鉴权：它需要华为的 `deviceauth_lite` native 引擎、HUKS 密钥库与随产品下发的配件证书，
侧载 APK 无法凭空获得。因此代码在 HiChain 处**直接失败并上报原因**，而不是伪造一个会话密钥。

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
| **HiChain 鉴权** | 华为私有的配对协议，需要 `deviceauth_lite` native 库 + HUKS + 配件证书；不属于 AOSP | 手机在交换身份后要求 HiChain，会话在此终止，`HiCarStatus.Blocked` |
| **HiSight 投屏会话** | 依赖已认证会话的密钥；且 RTSP 语义（ANNOUNCE/OPTIONS/SETUP/PLAY、RTP→PES→ES 解密）尚未实现 | 无画面 |
| **音频** | 同上：AAC 上下行都走会话密钥加密的媒体通道 | 无声音 |

### 2.2 侧载 APK 下无法完整工作的能力

| 能力 | 侧载是否可行 | 说明与降级方案 |
| --- | --- | --- |
| 画面 + 触控/按键 | 理论上可行（不依赖签名） | 一旦 HiChain 解决即可，当前被 2.1 阻断 |
| 音频（媒体/导航） | 部分可行 | 降级：HiSight 解密出的 PCM/AAC 走车机**公开**音频接口（`AudioTrack` + `USAGE_MEDIA`），不做厂商私有通道；音量为系统媒体音量 |
| 麦克风上行 | 可行但体验受限 | 走 `RECORD_AUDIO` + 公开输入源，回声消除可复用项目已有的 speexdsp 通路 |
| 通话音频 | 不可行 | HiCar 通话走蓝牙 HFP/厂商通话通道，侧载无法介入；如实标记为不支持 |
| 方控（方向盘键） | 不可行 | 依赖厂商输入服务（本项目已为 CarPlay 做过 Geely/BYD 适配，但那不是 HiCar 通道） |
| 仪表 / HUD 导航卡片 | 不可行 | 华为私有投屏子通道，无 SDK 不可得；现有 BYD HUD 实现只解析 CarPlay 的导航数据 |
| 免输码回连 | 不可行 | 依赖 HiChain 的凭据持久化（HUKS） |

**不使用"应该可以"这类表述**：上表中"不可行"的三项在侧载条件下没有任何公开接口，只能在
厂商预装（系统签名 + HiCar 认证）的前提下才可能具备。

### 2.3 代码里的 TODO 位置

- `HiCarAuth.kt`：`HiCarAuthBlocker` 三个枚举值即三条阻断原因；`HiCarUnavailableAuthenticator` 是替身实现。
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
