# 移除 CarPlay：边界、顺序与当前进度

目标：这个 APK 最终只做 HiCar 车机接收端。HiCar 需要的保留，其余（CarPlay / AirPlay / iAP2 /
HUD / 导航投屏 / 相关界面与设置）从代码里删掉，不是隐藏。

## 一、为什么不能一次 `rm -rf`

HiCar 的依赖是干净的（`shared/.../hicar/*` 只 import 了热点 6 个类与 hicar-auth），
但**热点包不是干净的**：`network/LocalOnlyHotspotManager`、`WifiP2pGroupManager`、`WifiP2pChannels`
仍然 import 了 AirPlay 时期的 `transport.Iap2WirelessSecurity` 和 `orchestration.ConnectionFailure*`。
直接删 `airplay/` 或 `transport/` 会让热点先编译不过，HiCar 也就没有网络。

所以顺序必须是：**先解耦热点 → 再删协议层 → 再删界面 → 最后清资源与测试**。

## 二、保留清单（HiCar 的依赖闭包）

| 位置 | 保留 | 理由 |
| --- | --- | --- |
| `shared/.../hicar/` | 全部 | 接收端本体 |
| `shared/.../network/` | `LocalOnlyHotspotManager`、`WifiP2pGroupManager`、`WifiP2pChannels`、`WirelessHotspotInfo`、`HotspotInterfaceBssid` | 热点创建与 BSSID |
| `shared/.../network/` 依赖的两个小类型 | `Iap2WirelessSecurity`（枚举）、`ConnectionFailureCause/Exception` | 需内联到热点包，才能删 `transport` / `orchestration` |
| `shared/.../hud/` | `BydAdbShell`、`BydParcel`、`BydVehicleFieldStore`、`BydOutputSettings`、`BydClusterSpeedReader`、`BydWheelSpeed` | 车速音量要用 |
| `shared/.../adb/` | `AdbKeys`、`LocalAdb` | `BydAdbShell` 的依赖 |
| `shared/.../transport/` | `VehicleGear`、`VehicleSpeedSample`、`VehicleSpeedReading`、`VehicleSpeedSource`、`EvChargingConnectors` | 车速数据结构 |
| `common/.../HiCar*.kt` | 全部 | 接收端界面、体检、日志 |
| `hicar-auth/` | 全部 | 原生认证 |

## 三、删除清单

| 位置 | 规模 | 说明 |
| --- | --- | --- |
| `shared/.../airplay/` | 44 文件 / 7030 行 | AirPlay 协议栈 |
| `shared/.../transport/` 其余 | iAP2、CH341、Ethernet 等 | CarPlay 传输 |
| `shared/.../media/` | 氛围灯、CarPlay 媒体 | HiCar 用自己的 `MediaCodec` |
| `shared/.../location/` | CarPlay iAP2 定位 | HiCar 用 `HiCarLocationSpeed` |
| `shared/.../hud/` 其余 | 导航输出、HUD、集群 | CarPlay 专属 |
| `shared/.../mfi/`、`orchestration/` | CarPlay 认证与编排 | 待热点解耦后删 |
| `common/` 除 `HiCar*` | 约 130 文件 | `DiPlayActivity`、`CarPlayHostActivity`、`Settings` 的 CarPlay 部分、集群、HUD 界面 |
| `common/src/main/res/` | CarPlay 布局/图标/字符串 | 删除前先用 `grep -r "@drawable/..."` 确认无 HiCar 引用 |
| `common/src/test/` | CarPlay 测试 | 与源码同步删 |
| `mobile/src/debug/` | HUD 演示 | CarPlay 专属 |
| `settings.gradle.kts` | `:maphost`、`:home` | 无 Kotlin 源，确认无引用后移除 |

## 四、执行顺序（每一步都要能编译）

1. **热点解耦**：把 `Iap2WirelessSecurity` 复制成热点包内的枚举；把 `ConnectionFailure*` 换成包内异常；
   让热点 5 个文件不再 import `airplay`/`transport`/`orchestration`。
2. **删协议层**：`airplay/`、`mfi/`、`orchestration/`、`transport/` 其余、`media/`、`location/`。
3. **删 HUD**：`hud/` 除车速 6 个文件；`common` 的 HUD/集群界面。
4. **删界面**：`common/` 除 `HiCar*`；manifest 里除 HiCar 外的 activity/service/receiver/alias；
   `mobile/src/debug` 的 HUD 演示。
5. **清资源与测试**：未引用的 drawable/layout/string/values；CarPlay 测试。
6. **重建设置页**：HiCar 专属设置（连接、显示、音频、车速、诊断、关于），文案统一中文。

## 五、当前进度（2026-11-22，全部完成）

- [x] 启动入口切到 HiCar：`DiPlayActivity` 不再是 LAUNCHER，`HiCarLauncherAlias` 标签改为 `HiCar`
- [x] 车速音量：曲线 / 平滑 / PCM 软限幅 / 双源 / 隧道保持（128 个单测通过）
- [x] 音频 SSRC 语义：低 16 位 bus、高 16 位 usage，音乐帧单独增益
- [x] HiCar 设置页：显示（全屏/状态栏/状态栏+导航栏）、声音（车速增益）、连接、诊断、关于
- [x] 设置页作为叠加层，连接中可改；显示模式即时生效，画面按比例适配（见 `docs/HICAR_UI.md`）
- [x] 热点解耦：`Iap2WirelessSecurity` → `network.HotspotSecurity`，`ConnectionFailure*` →
      `network.HotspotFailureException/Cause`；热点 5 个文件不再 import CarPlay 包
- [x] 协议层删除：`airplay/`(44)、`mfi/`(7)、`orchestration/`(15)、`media/`(46)、`location/`(1)、
      `iap2/`(10)、`telecom/`(3)、`glance/`(1)、`shared/`(3，Car App Service)、`transport/`(43)
- [x] HUD 与界面删除：`hud/` 只留车速/车辆字段相关（`BydAdbShell`、`BydParcel`、`BydAdbAccess`、
      `BydVehicleFields`、`BydVehicleCapabilityProbe`、`Byd13CatalogProbeMain`、`BydClusterSpeedReader`、
      `BydWheelSpeed`、`BydVehicleOptions`）；`common/` 只剩 `HiCar*` 与设置页
- [x] `network/` 只留热点闭包（22 个文件）；`compat` 只留 `PlatformCompat`、`Base64Compat`
- [x] 资源清理：布局/drawable/raw/多语言 `values-*` 全删；`values` 只留 `colors`/`themes`；
      `xml` 只留 `backup_rules`/`data_extraction_rules`/`diagnostic_report_paths`
- [x] 图标换成 `ic_hicar.xml`（矢量，不再有 CarPlay 标识），应用名 `HiCar`
- [x] 测试清理：`shared/src/test` 只留 `hicar/`，`common/src/test` 只留 3 个 `HiCar*` 测试
- [x] `:maphost`、`:home` 示例模块删除（改 `settings.gradle.kts`）
- [x] HiCar 设置页

## 六、保留但换了实现位置的类型

| 旧位置 | 新位置 | 原因 |
| --- | --- | --- |
| `transport.Iap2WirelessSecurity` | `network.HotspotSecurity` | 热点要用，协议层要删 |
| `orchestration.ConnectionFailureCause/Exception` | `network.HotspotFailureCause/Exception` | 同上 |
| `transport.VehicleGear` | `vehicle.VehicleGear` | 车速监视要用，iAP2 编码不需要 |
| `hud.BydOutputSettings.legacyVehicleProbe` | `hud.BydVehicleOptions.legacyVehicleProbe` | 集群/HUD 设置已删，探针开关保留，偏好键不变 |
| `hud.BydBattery*`、`hud.BydWheelSpeedSource` | 删除 | 只为 CarPlay 的 EV 状态与 iAP2 定位服务 |

## 七、验证方式（没有实车）

- 每一步 `:shared:compileDebugKotlin` + `:common:compileDebugKotlin` 必须通过；
- `:shared:testDebugUnitTest --tests "com.shilapi.xcertplay.hicar.*"` 必须全绿；
- `:mobile:assembleDebug` 后装 MuMu，确认启动即 HiCar、无崩溃、设置页可进出；
- 删除资源前用 `grep` 确认没有 HiCar 文件引用，避免运行时 `Resources$NotFoundException`。
