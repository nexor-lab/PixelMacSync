# PixelMacSync — iOS（Face ID 解锁 Mac）

Status（2026-10-18）：**Beta / 会话认证已完成；Face ID 解锁诊断中**
工作区：`/Users/yanzhequ/PixelMacSync-Workspace/PixelMacSync`
断点：`../../RESUME.md`（优先读）

## 当前状态
- 已实现 **session 认证**：`PAIR_SESSION_PUB`、`SESSION_CHALLENGE/MAC_PROOF/PHONE_PROOF/OK/READY`。
- 已修 **iOS 27.x 启动闪退**：`CBAdvertisementDataServiceDataKey: [CBUUID: Data]` → **manufacturer data**。
- 解锁密钥：Secure Enclave + Face ID（`.biometryCurrentSet`）；session 密钥：非生物识别、`AfterFirstUnlock`。
- Face ID 解锁 UX：通知点击 → `onAppActive`；App 内新增 **“使用面容 ID 解锁 Mac”** 按钮（**未提交**）。
- 当前 IPA：`iOS/build-device/PixelMacSync-unsigned.ipa`（22:21，470 KB）；
  安装包目录 `~/Desktop/PixelMacSync-Install`。
- **待确认**：iPhone 按 Face ID 后 Mac 是否真正解锁。

## 解锁链路（排错顺序）
1. Mac `HELLO` → iOS `startSession`（trust 有 Mac session pub 才发 `SESSION_CHALLENGE`）。
2. `SESSION_MAC_PROOF`（Mac session pub 验签）→ `SecureKey.signSession` → `SESSION_PHONE_PROOF`
   → `SESSION_OK` → `SESSION_READY` → “已连接”。
3. Mac 锁屏且 `sessionVerified` → `UNLOCK_REQUEST`。
4. iOS `handleUnlockRequest`（要求 `sessionAuthenticated`）→ 验 Mac 解锁签名 → `pendingUnlock`
   → 通知 / App 按钮 → `approveUnlock` → `SecureKey.sign`（Face ID）→ `AUTH_RESPONSE`。
5. Mac `authResponse` 验签 → grant → PAM 解锁。

## 构建 / 安装
```sh
cd /Users/yanzhequ/PixelMacSync-Workspace/PixelMacSync/iOS
./build_device_ipa.sh
# 产物：build-device/PixelMacSync-unsigned.ipa
```
- IPA 是 **unsigned**：Sideloadly / AltStore 自签（免费账号 7 天），安装后信任开发者。
- Xcode 14.2 对 iOS 27.2 显示 **Offline**（无 DeviceSupport，正常）；App 安装仍用 Sideloadly。
- 覆盖安装通常保留数据；旧配对缺 session 公钥时 Mac 会自动发起重新配对（一次 Face ID）。

## 日志 / 实机测试
- iPhone USB 已连接；可读日志：
  1. **macOS 控制台 App → 设备 → iPhone → 开始**，过滤 `PixelMacSync`；
  2. iPhone 设置 → 隐私与安全性 → 分析与改进 → 分析数据 → `PixelMacSync-*.ips`；
  3. Mac：`log show --last 5m --predicate 'process == "PixelSync"' | grep -Ei "unlock|session|auth|grant|PAM"`。
- `pymobiledevice3` 安装因网络超时失败；若网络恢复可用它读 syslog/crash。
- Face ID 必须真人按；测试时保持 Mac 开盖并锁屏。

## 已知限制
- iOS 无法同步其他 App 通知；后台 BLE 受限；热点/音乐/拨号不可用。
- Face ID 重录后 `.biometryCurrentSet` 密钥失效，需要重新配对。
- Release `v2.3-beta.3` 里的 IPA 是 21:55，不含 22:21 的 Face ID UX 修复。

## 目录
```
iOS/
├── project.yml
├── build_device_ipa.sh / build_sim.sh
└── PixelMacSync/
    ├── App/PixelMacSyncApp.swift
    ├── Core/BLEPeripheralManager.swift / UnlockController.swift
    │       / UnlockProtocol.swift / SecureKey.swift / TrustedMacStore.swift
    │       / NotificationManager.swift / UnlockSettings.swift
    ├── Views/Theme.swift / ContentView.swift
    └── Info.plist
```
