# 方向盘按键：把车机的媒体键转给手机

借鉴自 [GGBondLab/OpenHC](https://github.com/GGBondLab/OpenHC)（GPL-3.0-only）的
`MediaKeys` / `MediaKeyForwarder` / `MediaKeySession`。我们的项目也是 GPL-3.0，移植时保留了来源说明。

## 一、为什么不能只在自己窗口里收按键

方向盘上的「上一首 / 下一首 / 播放暂停」是系统媒体键。应用在前台时，按键先送到当前窗口（`dispatchKeyEvent`）；
一旦司机切到车机自己的桌面或收音机，按键就被系统派给"最近放过媒体声音"的那个应用。只做窗口这一条路，
表现就是：投屏页上能切歌，退到桌面再按就没反应。

所以两条入口共用一个转发器：

| 入口 | 什么时候起作用 | 实现 |
| --- | --- | --- |
| 前台窗口 | 接收端在屏幕上 | `HiCarHostActivity.dispatchKeyEvent` |
| 媒体按钮会话 | 应用不在前台，但仍然拿着媒体焦点 | `HiCarMediaKeySession`（`android.media.session.MediaSession`，播放状态固定"正在播放"，声明播放/暂停/上一首/下一首/停止） |

会话音只在**同时满足**两件事时存在：手机这次连接选回了按键事件（`left_key_down`/`left_key_up`），
并且音频通路（`HiCarAudioSink`）拿着媒体焦点。第二个条件不能省：没有焦点时按键本来就属于别人，
我们再挂一个活动会话等于把别人的按键抢走。

## 二、转发的规则（`HiCarMediaKeyForwarder`，纯逻辑 + 单测）

- 只处理 8 个媒体键：85 播放暂停、86 停止、87 下一首、88 上一首、89 快退、90 快进、126 播放、127 暂停。
  别的键（返回、音量、方向键）一律交还车机。
- 没有能发键的连接时不处理，车机自己的收音机照常。
- 自动重复的按下吞掉不转（长按不会变成几十次"下一首"），但仍算"处理过"，不让系统再派一次。
- 同一个键没抬起又来一次按下：先补一个抬起再按下，手机不会卡住一个按下的键。
- 抬起只在转过它的按下时才转；孤立的抬起交还系统，让它那边的按下、抬起配成对。
- 同一个事件（键码、动作、事件时间相同）从两个入口各来一次时只转一次——有些 ROM 的确两边都给。
- 媒体会话的播放控制命令（没有按下/抬起）合成一对；这个键正按着时不合成，避免双击。

## 三、协议侧要满足的条件

按键包和触摸包同一条 UIBC 连接，编码在 `HiCarUiInputEncoder`：

```
动作(1: 3 按下 / 4 抬起) | BE16 9 | 0 | BE16 键码 | BE16 0 | BE32 修饰位
```

- 键码是安卓键码，修饰位为 0。这 8 个媒体键属于**手机不需要额外协商就能收的键**
  （OpenHC 里叫基础键集合），所以不用要求手机选 `source_device_type 7`；它们的字节在两种源设备类型下相同。
- 是否需要按下/抬起配对由路由器（`HiCarInputRouter.mediaKey`）保证；连接收尾时 `cancel()` 会把还按着的键逐个抬起。
- **能力协商现在会被记录**：手机的 `SET_PARAMETER` 里 `his_uibc_capability` 的 `generic_cap_list`
  会被解析，日志里出现一行

  ```
  HiSight: phone selected input events left_point_down,left_point_up,left_move,left_key_down,left_key_up; key input available
  ```

  或 `key input not selected, the steering wheel stays on the car`。这一行是"方向盘没反应"和
  "手机根本没选按键"的唯一区分依据——11:11 那次会话对触摸也缺这一行。

## 四、日志

```
keys: next(87) down from window; forwarded
keys: next(87) up from window; forwarded
keys: media-button session open; wheel keys reach the phone in the background too
keys: no media-button session (SecurityException); wheel keys work only while the receiver is on screen
keys: steering wheel forwarding off
```

日志保持英文（协议证据，`docs/HICAR_MIGRATION.md` 要逐字引用）；界面文案是中文。

## 五、设置

设置页新增「按键」一节：「方向盘按键转给手机」，默认开，改完立刻生效（关掉就关会话、`ready=false`，
按键马上回到车机）。存 `hicar-keys` 偏好。

## 六、验证情况（2026-11-22）

- 已做：`HiCarMediaKeyForwarderTest` 9 个用例（键码、交还、自动重复、补抬起、孤立抬起、双入口去重、
  命令合成、断连清状态、日志名）。
- 已做：模拟器上用 `adb shell input keyevent 87 / 126` 注入，确认窗口入口收到并给出
  `keys: next(87) down from window; no key-capable connection; the key stays with the car`
  （没有连接时按键确实交还系统，这是正确行为）。
- **未验证**：真手机是否选回按键、手机收到后是否真的切歌、后台媒体会话在比亚迪车机上是否拿到按键
  （需要媒体焦点 + 系统放行）。这三点都要上车才能确认，不能写成"已支持"。
