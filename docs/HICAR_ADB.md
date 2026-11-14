# 车机上用 adb 判定 HiCar 发现阶段（安卓车机专用速查）

这份清单只回答三个问题，按顺序做，每一步都有明确的"看到什么算成立"。前提：车机是安卓系统、
**没有前装 HiCar、也没有前装 CarPlay**、有 adb。

> **不想敲命令也可以**：App 里已有 **Device audit**（把同样的信息打到界面，导出日志时自动附带）和
> **Root probe**（只读地读 `bt_config.conf` 与 `dumpsys`，需要 su）。本文保留的价值在于 adb 能拿到
> App 拿不到的两种证据：`dumpsys bluetooth_manager` 的输出，以及 HCI 层的 `btsnoop_hci.log`。

> 为什么优先在车机上做：7.10 定位到的根因是"BLE 广播地址必须是车机 BR 地址，而 Android 应用改不了它"。
> 平板上这条被系统封死；车机上是否同样被封死，**要看它的蓝牙栈怎么配**，而 adb 正好能看见、有时还能改。

---

## A. 先确认身份与版本（10 秒）

```bash
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
```

看什么：`sdk` 决定我们的 minSdk 24 是否满足（一般车机是 28–31）；顺手记下型号，后面写报告要用。

## B. 拿到车机真实的 BR 地址（关键）

```bash
adb shell "dumpsys bluetooth_manager | grep -iE 'address|name' | head -20"
```

看什么：输出里 `Address: XX:XX:XX:XX:XX:XX` 就是这台车机的 BR/EDR 地址。**记下来**，D 步要用它对照。

## C. 看 LE 隐私是否开启（有 IRK 就是开着）

需要 root（`adb root` 成功才行）：

```bash
adb root
adb shell "cat /data/misc/bluedroid/bt_config.conf | grep -iE '^Address|IRK|Name'"
```

看什么：

- 有 `LE_LOCAL_KEY_IRK` → 蓝牙栈持有 IRK，具备生成/解析私有地址（RPA）的能力，广播很可能走 RPA；
- 只有 `Address` 没有 IRK → 更可能直接用公开地址广播，这对我们是好消息。

没有 root 就跳过这步，D 步一样能得出结论。

## D. 装我们的 APK，对照"广播地址 vs BR 地址"（决定性）

```bash
adb install -r mobile-debug.apk
# 桌面图标名是 "HiCar (experimental)"；直接点名启动：
adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.HiCarLauncherAlias
adb logcat -c && adb logcat -s HiCar
```

日志同时打到 UI 和 logcat（tag `HiCar`），所以 `adb logcat -s HiCar` 会和界面逐行一致，
不用再截图或导出文件。

点 Start，然后**用手机上的 nRF Connect 扫**，找到 `HUAWEI ...`/车机名字那条，看它的 MAC，再和 B 步的 `Address` 比：

| 手机上看到的地址 | 结论 |
| --- | --- |
| 与 B 步 `Address` **完全相同** | 车机用公开地址广播。**7.10 的阻塞在车机上不存在**，可以继续往下调载荷与 RFCOMM |
| 首字节 `0x4x–0x7x`（最高两位 `01`），和 `Address` 不同 | 同样是私有地址轮换，和平板一样被封死，见 F 步 |

这一步是整个清单的核心：它把"平板做不了"和"安卓车机做不了"区分开。

## E. 如果 D 步地址相等：接着验证载荷与链路

```bash
adb logcat -s HiCar
```

看三行是否依次出现（顺序即链路顺序）：

```
HiCar records ready: business channel N, control on 0xFE35
HiCar advertisement started (11 bytes of 0xFDEE service data, device name in the scan response)
HiCar waiting for the phone: 30s, connections=0, ...
```

`connections` 一旦变成非 0，说明手机真的连上来了——那时候才轮到载荷/HiChain 的问题。

## F. 拿 HCI 层的铁证（地址到底怎么设的）

这比从广播地址反推更硬，直接看控制器参数：

```bash
adb shell setprop persist.bluetooth.btsnooplogmode full
# 重启蓝牙/跑一次广播，然后取日志
adb shell "ls -l /data/misc/bluetooth/logs/"
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
```

用 Wireshark 打开，过滤 `btle`，找 **LE Set Advertising Parameters**（命令 `0x2006`），看其中的
`Own_Address_Type`：

- `0x00` = Public（我们要的）
- `0x01` = Random（RPA/静态随机，手机拨不到）
- `0x02/0x03` = RPA / 可解析（同上）

这一条不需要猜，也不需要 root 之外的任何东西。

## G. 仅当 F 步显示 Random、且 `adb root` 可用时：配置层绕过（有风险）

已知的落点是 bt_config.conf 里的 LE 密钥段（`LE_LOCAL_KEY_IRK` 等）。删掉它并重启蓝牙，**有可能**让广播退回公开地址，
但这会改变蓝牙栈行为、并可能清掉已配对设备，属于改系统文件：

```bash
adb shell "cp /data/misc/bluedroid/bt_config.conf /data/local/tmp/bt_config.conf.bak"
# 备份一定要先做；改写方式因车机而异，建议先只做备份与观察，确认 F 步之后再决定
```

**不要**把它当默认步骤。更稳的顺序永远是：D 步对照 → F 步取证 → 再决定要不要动配置。

## H. 不要做的事

- **不要到车机里找"自带的 CarPlay/HiCar 互联应用"**：这台车机两样都没有前装，内置的那一层不存在，
  蓝牙广播、5 GHz 热点、RFCOMM 记录全部由我们自己装的 APK 提供。所以"参考车机自带实现"这类思路在这台车上
  不成立——能作参考的只有别处抓到的真实 HiCar 广播（那需要另一台真正支持 HiCar 的车）。
- **不要让两个接收端同时跑**：本仓库的 CarPlay 接收端、或任何后装 CarPlay 盒子/应用，会和 HiCar 接收端抢同一个
  蓝牙适配器与热点。测 HiCar 之前先退出它们，否则日志里的失败是互相踩出来的。
- 不要为了测试把车机的蓝牙配对记录清空而不备份——后面排查"是不是手机侧缓存了旧广播"会用到它。
- 不要用 `adb shell am force-stop` 反复杀蓝牙服务进程（`com.android.bluetooth`）来"刷新"状态，容易把适配器留在异常态。
