# 在电脑上能测到什么（以及测不到什么）

这台电脑有无线、蓝牙、USB 口，但**它本身不能当接收端**：这个 APK 是 Android 应用，而它依赖的四样
东西没有一样能从 Windows 交给它。

| 依赖 | 为什么电脑给不了 |
| --- | --- |
| Android 运行时 | 只能靠模拟器/虚拟机，见下一张表 |
| BLE 广播（0xFDEE）+ BR/EDR 可被发现 + RFCOMM 服务端 | 需要真实的蓝牙控制器；宿主机蓝牙不能被 Android 应用直接使用 |
| 5 GHz 热点（手机要接进来） | 模拟器的 Wi-Fi 是虚拟的 `AndroidWifi`，没有射频可以给一台真手机连 |
| USB **host** 角色（车机是主，手机是从） | 模拟器没有 USB host 控制器；USB 直通的方向是「把宿主机设备交给 guest」，不是「guest 当主机接手机」 |

## 为什么模拟器不行

| 方案 | 真实情况 |
| --- | --- |
| MuMu / 蓝叠 / 雷电 | 蓝牙是空壳：`BluetoothAdapter` 为 null 或 `bluetooth off`。本仓库调试时就卡在「失败：bluetooth off」 |
| Android Studio AVD | 官方文档自己也矛盾：networking 页说 API 31+ 有 Classic/BLE，advanced usage 页仍说模拟器不含虚拟蓝牙硬件。实际实现是 RootCanal 虚拟控制器——**只能和另一个模拟器/模拟设备互通**，真手机不在那个模拟世界里 |
| AVD + `-forward-vhci` + Bumble + USB 蓝牙 dongle | 这是唯一能把真实 dongle 接进模拟器 HCI 的实验路径。但即使蓝牙通了，热点仍是虚拟的、USB host 仍没有，HiCar 链路照样走不完 |
| WSA（Windows Subsystem for Android） | 已停止支持，且没有蓝牙透传 |
| Android-x86 / Bliss OS 虚拟机 + USB 直通 | 蓝牙 dongle 也许能通，但 5 GHz 热点在 Android-x86 上基本不可用，VM 里做 USB host 接手机也别指望。成本远高于收益 |

一句话：**HiCar 接收端的三条腿（蓝牙广播、5 GHz 热点、USB host）在电脑上最多能凑出半条。**

## 电脑真正能做的事：当调试终端，不当被测端

车机的无线 adb 是常开的，所以让车机留在车里，电脑在旁边看——这能省掉「导出日志 → 传文件 →
再读」的来回，是目前最省事的路径。

```bash
# 车机和电脑在同一个局域网（家里的 Wi-Fi、手机热点都行）
scripts/hicar-live.sh connect 192.168.1.50:5555   # 首次会要求在车机屏幕上点「允许」
scripts/hicar-live.sh log                        # 实时看 HiCar 日志（Ctrl-C 停）
scripts/hicar-live.sh log 200                    # 只取最近 200 行
scripts/hicar-live.sh shot build/car.png         # 截车机当前屏幕
scripts/hicar-live.sh status                     # 版本 + 前台 Activity + 最近几条关键日志
scripts/hicar-live.sh start | stop | settings    # 远程打开/停止/直接进设置
scripts/hicar-live.sh install build/hiplay-0.2.22-debug.apk
scripts/hicar-live.sh pull-log                   # 拉最新一份导出（需先在应用里导出）
```

脚本会记住上次 `connect` 的地址：adb server 重启后 TCP 设备会掉线，下次运行会自动重拨，不用每次
手动 `adb connect`。

**做不到的**：用 Windows 内置蓝牙抓 0xFDEE 广播（需要支持 monitor mode 的适配器，比如 nRF52840 +
Wireshark，这属于「观测设备」）；用 USBPcap 看车机与手机之间的 USB 流量（那条总线在车机里，
电脑不在链路上，除非上硬件 USB 分析仪）。

## 桌面全链路测试：拿一台旧手机/平板当「假车机」

这是唯一能在桌面上把链路跑完的办法：把 `mobile-debug.apk` 装到一台旧 Android 设备上，用华为手机
当发送端。项目早期就是用 HUAWEI MRX-AL09 平板做的链路调试。

能验证：BLE 广播有没有被手机看到、连接码/扫码配对、HiChain、HiSight 画面、触控与方向盘按键、
音频分路、随速音量（如果那台设备能取到定位）。

不能替代车机的地方：热点频段与 Wi-Fi Direct 行为跟车机不同（很多手机开不出 5 GHz 热点）、
USB host 供电与 OTG 行为不同、车型相关的车辆总线读不到、系统栏与窗口尺寸也不同。所以它适合
验证「协议和界面」，不适合验证「这台车」。

## 相关文档

- 车机 adb 判定与取证：`docs/HICAR_ADB.md`
- 有线链路的真实验证边界：`docs/HICAR_WIRED.md`
- 逐轮实测记录：`docs/HICAR_MIGRATION.md`
