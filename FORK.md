# Fork 说明 — PixelMacSync（macOS 12 / Intel 移植 + 音乐控件）

> 本文件说明：这个仓库是什么、能做什么、从哪里来、相比上游改了什么、怎么实现、怎么构建与安装，以及已知限制。

## 1. 这是什么

**PixelMacSync** 是一个把 **Android 手机** 与 **macOS 电脑** 通过 **蓝牙低功耗（BLE GATT）** 直连的
个人向工具：手机作为 GATT 外设（Peripheral），Mac 作为中心（Central）。**不使用 Wi‑Fi / 局域网 /
互联网 / 云 / TCP**，全部数据只在 BLE 链路上、按事件触发传输（无轮询）。

本仓库是上游项目的一个 **Fork**，目标是把原本面向较新 macOS 的工程移植到
**macOS 12.7.6 (Monterey) / Intel x86_64**（2015 Intel Mac），并在此之上新增了若干功能
（见第 4、5 节），其中最新的是 **音乐控件**。

**本仓库**：`https://github.com/nexor-lab/PixelMacSync`
**预编译包（Releases）**：`https://github.com/nexor-lab/PixelMacSync/releases`
- Android：`PixelMacSync-Android.apk`
- macOS：`PixelMacSync-macOS12-Intel.dmg`（ad-hoc 签名，不含个人证书）

## 2. 能做什么（功能总览）

- **通知同步**：手机通知实时推到 Mac 原生通知中心，通知头显示来源 App 名，可选带 App 图标。
- **电话事件**：来电 / 接通 / 未接 / 挂断 事件推送到 Mac（号码、联系人名尽力解析）。
- **远程热点**：Mac 一键开启/关闭手机热点，显示手机上报的**真实**状态（非乐观 UI）。
- **音乐控件**：Mac 显示手机正在播放的 **歌名 / 歌手 / 专辑 / 封面**，并可 **播放/暂停、上一首/
  下一首、拖动进度 seek、调节手机媒体音量**。
- **遥测**：电量、充电、网络类型/SSID、信号、机型等。
- **系统集成**：菜单栏常驻、开机自启；中英文界面。

## 3. 从哪里来（来源与变更基线）

- **上游**：`https://github.com/LK024/PixelMacSync`（原作者项目）。
- **基线提交**：`e5a5c060…`（上游 `main`）。
- **本仓库分支**：`macos12-port`，所有改动都叠加在该基线之上。
- 上游原始协议与思路保留，本 Fork 以 **移植 + 功能增补** 为主。

## 4. 实现方式（架构与协议要点）

- **链路**：纯 BLE GATT。Android 侧用 `BluetoothGattServer` + `BluetoothLeAdvertiser`
  （前台服务常驻）；macOS 侧用 `CoreBluetooth`（`CBCentralManager`）。
- **协议**：`US`（`0x1F`）分隔的紧凑字节协议，非 JSON；所有 payload ≤ 180 字节；
  事件驱动，仅在变化时发送。
  - 通道 UUID：Service `58DF214B-…`，Telemetry(Read/Notify)、Notifications(Notify)、
    Command(Write，Mac→Android)。
  - 详见仓库根目录 **`BLE_PROTOCOL.md`**。
- **macOS 12 适配**：移除 macOS 13+ 的 `MenuBarExtra`，改用 AppKit `NSStatusItem` + `NSPopover`；
  解析逻辑抽到无硬件依赖的 `Protocol.swift` 以便单元测试。
- **音乐控件实现**：Android 复用**已启用的通知监听器身份**
  （`MediaSessionManager.getActiveSessions(ComponentName)`）读取 `MediaController`，监听
  active-session / metadata / playback 回调；无需新增敏感权限。封面按曲目一次性传输
  （base64 JPEG，分片 + 限速），Mac 缓存到 `~/Pictures/MacSyncCovers/<key>.jpg`；
  进度条由 Mac 本地按时钟推进（`position + 已过时间`），拖动才发送 seek；音量通过
  `AudioManager`（`STREAM_MUSIC`）+ `Settings.System` 观察者双向同步。

## 5. 相比上游新增/修改的内容

- **macOS 13+ API 移除**，移植到 macOS 12.7 / Intel（`PixelSyncApp.swift`、`ContentView.swift`）。
- **纯解析层** `Protocol.swift` + 单元测试 `MacOS/tests/parser_test.swift`（CLT 可直接跑）。
- **电话事件**（上游无任何电话处理）：`CALL␟event␟number␟name`。
- **远程热点**：由“广播 Intent 给 MacroDroid”改为 **Root 真实控制**
  （`cmd wifi start-softap/stop-softap`），并有真实状态机；不泄露凭据。见 `HOTSPOT.md`。
- **App 图标传输 + 缓存**：`ICON_BEGIN/DATA/END` → `~/Pictures/MacSyncIcons/<pkg>.png`。
- **通知头 = 发送方 App 名**。
- **音乐控件**（本次最新增补）：`MUSIC_META` / `ART_*` / `MUSIC_VOLUME` 协议与 Mac UI；
  支持远程播放控制与进度 seek；封面更新做了防“旧封面卡住”的修复。
- **汉化**（`values-zh-rCN/`、`Localization.swift`）、**开机自启**、**Apple 正式签名 + 原生通知**、
  **SukiSU root watchdog** 等工程化改动。
- 构建脚本：`MacOS/build_macos.sh`、`MacOS/make_dmg.sh`、`Android/build_android.sh`、
  `scripts/resign_macos.sh`。

> 逐条变更记录见 **`CHANGELOG.md`**；测试与实测结论见 **`TEST_REPORT.md`**；当前状态见
> **`PROJECT_STATUS.md`**；协议细节见 **`BLE_PROTOCOL.md`**。

## 6. 平台与兼容

- macOS：**12.7.6 (Monterey) / Intel x86_64** 实测通过；构建脚本支持 `ARCH=arm64|universal`。
- Android：**test Android phone / Android 15 (API 35) / HyperOS** 实测通过；`minSdk 35`、`targetSdk 36`。
- 通知监听器（`NotificationListenerService`）是核心授权入口；部分 OEM 需允许“自启动/后台”。

## 7. 构建与安装

### macOS

```bash
cd MacOS
./build_macos.sh                 # 用 Xcode Command Line Tools 编译 x86_64 并打包 .app
./make_dmg.sh                    # 可选：生成 .dmg
```

Apple 正式签名（原生通知/图标所需，可选）：

```bash
scripts/resign_macos.sh --app <PixelSync.app> --p12 <你的证书.p12> --password <密码>
```

### Android

```bash
cd Android
JAVA_HOME=<JDK17+> ANDROID_HOME=<Android SDK> \
  KEYSTORE=<你的.jks> KS_PASS=<密钥库密码> ./build_android.sh
```

产物：`Android/PixelMacSync-Android.apk`（release、已签名）。

## 8. 已真机验证 / 已知限制

**验证通过（真机）**：通知同步、电话事件解析、远程热点开关与状态同步、App 图标传输、
音乐元数据/封面/播放控制/进度 seek/音量双向同步、解析单测（当前 57 项全过）。

**限制（诚实）**：
- 歌词：Android 无标准歌词 API 且本项目离线，**基本拿不到**（LIMITATION）。
- 热点 NAT/上网共享受限（见 `HOTSPOT.md`）。
- 部分播放器不暴露 MediaSession / 封面，则回退为纯文字或无内容。
- 来电完整链路需第二部手机；Mac 睡眠/唤醒、长跑测试待补。

## 9. 隐私与安全

- 运行数据链路 **仅 BLE**；不使用 Wi‑Fi/局域网/互联网/云。
- 热点密码等凭据 **不写入日志、不通过 BLE 传输、不进入报告**。
- 仓库 **不包含** 私钥/密钥库/签名密码（`*.jks`、构建产物、私有交接文档均被 `.gitignore` 排除）。

## 10. 许可与致谢

- 基于上游 `LK024/PixelMacSync` 修改，遵循上游仓库所附许可。
- 感谢上游作者的基础实现。
