# PixelMacSync — iOS 版（面容 ID 解锁 Mac）

> 目标：iPhone 用 **Face ID** 解锁 Mac；**Mac 端不改**（复用现有 BLE 协议与 PAM）。
> 只实现 iOS 能做的部分；做不到的在 App 内明确提示。

## 已实现（源码）

- **BLE 外设**：`CBPeripheralManager`，与安卓同一套 Service/Characteristic
  （Service `58DF214B…`、Telemetry、Notifications、Command）；响应 Mac 的 `HELLO`→`SESSION_READY`。
- **配对**：手机发起 `PAIR_REQUEST`；Mac 验登录密码 → `PAIR_BEGIN`；iPhone 生成
  **Secure Enclave P-256** 密钥并回 `PAIR_REPLY`；Face ID 后 `PAIR_CONFIRM` → `PAIR_DONE` 落库。
- **解锁**：Mac 发一次性挑战 → iPhone 校验 Mac 签名 → 本地通知 → 点开 → **Face ID**
  → SE 私钥签名 → `AUTH_RESPONSE` → Mac 验签 → PAM 解锁。
- **策略**：`设备远离自动锁定`、`远程唤醒` 开关（`SET_POLICY` 下发给 Mac）。
- **其他**：电量/网络状态遥测、`tel:` 拨号、关于页（含 iOS 限制说明）。
- **UI**：Liquid Glass 风格（用 `.ultraThinMaterial` + 圆角/描边/阴影近似；Liquid Glass 原生 API 需 iOS 26+）。

## iOS 上不可用（App 内已提示）
通知转发 / 内联回复、来电事件与挂断、远程个人热点、跨 App 音乐控制、应用图标同步、信号强度。
后台 BLE 为**尽力而为**：iPhone 锁屏/挂起时可能延迟收到解锁请求。

## 构建

### 推荐：新 Mac + Xcode 26+（原生 Liquid Glass）
```sh
brew install xcodegen
cd iOS
xcodegen generate        # 生成 PixelMacSyncIOS.xcodeproj
open PixelMacSyncIOS.xcodeproj
```
- 在 Xcode 里选择你的 iPhone，填 `DEVELOPMENT_TEAM` 并签名。
- 若要原生 Liquid Glass，把 `Views/Theme.swift` 的材质替换为 `.glassEffect()`（iOS 26+）。

### 本机（iOS 16.2 SDK，仅编译校验）
```sh
cd iOS
./build_sim.sh           # swiftc 直编模拟器，验证代码可编译
```
注意：本机 Xcode 14.2 只能到 iOS 16.2 SDK，**无法把 App 装到 iOS 27.2 的真机**（缺 DeviceSupport），
也无法编译 iOS 26/27 的 Liquid Glass API。真机测试需新工具链。

## 安全
- 私钥在 **Secure Enclave**，`biometryCurrentSet`：换/加面容即失效，需重新绑定。
- 只传公钥；一次性 32 字节挑战、有效期；Mac 端 PAM 放行；**不存储/不传输/不输入 Mac 密码**。
- 配对时 Mac 会弹“登录密码”，iPhone 会弹 Face ID。

## 目录
```
iOS/
├── project.yml                 # XcodeGen 规格
├── build_sim.sh                # 本机模拟器编译校验
└── PixelMacSync/
    ├── App/PixelMacSyncApp.swift
    ├── Core/UnlockProtocol.swift / SecureKey.swift / TrustedMacStore.swift
    │       / UnlockController.swift / BLEPeripheralManager.swift
    │       / UnlockSettings.swift / NotificationManager.swift
    ├── Views/Theme.swift / ContentView.swift
    └── Info.plist
```
