//
//  Localization.swift
//  PixelSync
//
//  Minimal zh/en localization helper (no bundle resources required, so it works
//  with the CLT-only build). Default language is English; Simplified Chinese is
//  selected when the system prefers a Chinese locale.
//
import Foundation

/// Real hotspot state as reported by Android.
enum HotspotState { case unknown, off, enabling, on, disabling, error }

enum L10n {

    static var isChinese: Bool {
        (Locale.preferredLanguages.first ?? "en").hasPrefix("zh")
    }

    /// English / Chinese selection.
    static func t(_ en: String, _ zh: String) -> String {
        isChinese ? zh : en
    }

    // MARK: - Connection state

    enum State {
        case disconnected, scanning, connecting, cacheConnecting, handshaking, connected,
             rejected, bluetoothOff, waking, restarting, autoReconnecting, needsUnpair
    }

    static func state(_ s: State) -> String {
        switch s {
        case .disconnected:     return t("Disconnected", "未连接")
        case .scanning:         return t("Scanning…", "正在搜索…")
        case .connecting:       return t("Connecting…", "正在连接…")
        case .cacheConnecting:  return t("Connecting (cache)…", "正在连接（缓存）…")
        case .handshaking:      return t("Verifying…", "正在验证…")
        case .connected:        return t("Connected", "已连接")
        case .rejected:         return t("Not the active Mac", "非当前 Mac 设备")
        case .bluetoothOff:     return t("Bluetooth OFF", "蓝牙已关闭")
        case .waking:           return t("Waking…", "正在唤醒…")
        case .restarting:       return t("Restarting…", "正在重启…")
        case .autoReconnecting: return t("Reconnecting…", "自动重连中…")
        case .needsUnpair:      return t("Unpair Bluetooth Classic", "需取消经典蓝牙配对")
        }
    }

    // MARK: - Auto-reconnect (Mac-side interference recovery)

    static var reconnectTitle: String {
        t("PixelSync link interrupted", "PixelSync 连接中断")
    }
    static var reconnectBody: String {
        t("Bluetooth interference detected — reconnecting automatically…",
          "检测到蓝牙干扰，正在自动重连…")
    }
    static var recoveredTitle: String {
        t("PixelSync reconnected", "PixelSync 已恢复")
    }
    static var recoveredBody: String {
        t("The connection to the phone is restored.",
          "与手机的连接已恢复。")
    }
    static var reconnectFailedTitle: String {
        t("PixelSync reconnection failed", "PixelSync 重连失败")
    }
    static var reconnectFailedBody: String {
        t("Unpair the phone in System Settings → Bluetooth, then retry.",
          "请在“系统设置 → 蓝牙”中取消与手机的经典蓝牙配对后重试。")
    }
    static var unpairHint: String {
        t("Bluetooth Classic pairing can block the BLE link (ADR-028). Unpair the phone, then reconnect.",
          "经典蓝牙配对会阻断 BLE 连接（ADR-028）。请先取消与手机的配对，再重连。")
    }
    static var reconnectTooltip: String {
        t("Reconnect now", "立即重连")
    }

    // MARK: - Menu bar / app

    /// True only for `DEBUG_BUILD=1` bundles (see build_macos.sh); release is false.
    static var isDebugBuild: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "PixelSyncDebug") as? String) == "true"
    }

    /// True only for `BETA_BUILD=1` bundles; debug and release are false.
    static var isBetaBuild: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "PixelSyncBeta") as? String) == "true"
    }

    /// Fixed build timestamp stamped into Info.plist by build_macos.sh.
    static var buildTimestamp: String {
        (Bundle.main.object(forInfoDictionaryKey: "PixelSyncBuildTimestamp") as? String) ?? ""
    }

    static var appName: String {
        if isDebugBuild { return "PixelSync Debug" }
        if isBetaBuild { return "PixelSync Beta" }
        return "PixelSync"
    }

    /// App version (CFBundleShortVersionString), e.g. "2.3".
    static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? ""
    }

    /// Watermark shown in debug/beta popovers; empty in release. Matches the
    /// Android watermark format: LABEL / <version-suffix> / <build time>.
    static var buildWatermark: String {
        if isDebugBuild { return "DEBUG\n\(appVersion)-debug\n\(buildTimestamp)" }
        if isBetaBuild { return "BETA\n\(appVersion)-beta\n\(buildTimestamp)" }
        return ""
    }
    static var urlScheme: String { "pixelsync" }
    static var quit: String { t("Quit PixelSync", "退出 PixelSync") }
    static var remoteHotspot: String { t("Remote hotspot", "远程热点") }
    static var antennaRestartTooltip: String {
        t("Click to force a Bluetooth rescan", "点击以强制重新扫描蓝牙")
    }
    static var bluetoothOffTooltip: String { t("Bluetooth is off", "蓝牙已关闭") }
    static var deviceFallback: String { t("Phone", "手机") }

    // MARK: - Remote hotspot

    static func hotspotState(_ s: HotspotState) -> String {
        switch s {
        case .unknown:  return t("Unknown", "未知")
        case .off:      return t("Off", "已关闭")
        case .enabling: return t("Enabling…", "正在开启…")
        case .on:       return t("On", "已开启")
        case .disabling: return t("Disabling…", "正在关闭…")
        case .error:    return t("Error", "错误")
        }
    }
    static var hotspotTurnOn: String { t("Turn on", "开启热点") }
    static var hotspotTurnOff: String { t("Turn off", "关闭热点") }
    static var hotspotUnavailable: String { t("Connect a phone to control the hotspot", "连接手机后可控制热点") }

    // MARK: - Music control

    static var music: String { t("Music", "音乐") }
    static var musicNoTrack: String { t("No track playing", "无播放内容") }
    static var musicUnknownArtist: String { t("Unknown artist", "未知歌手") }
    static var musicUnavailable: String { t("Connect a phone to control playback", "连接手机后可控制音乐") }
    static var musicPlay: String { t("Play", "播放") }
    static var musicPause: String { t("Pause", "暂停") }
    static var musicPrevious: String { t("Previous", "上一首") }
    static var musicNext: String { t("Next", "下一首") }

    // MARK: - Call control

    static var callIncoming: String { t("Incoming call", "来电") }
    static var callCalling: String { t("Calling…", "正在呼叫") }
    static var callActive: String { t("Call in progress", "通话中") }
    static var callAnswer: String { t("Answer", "接听") }
    static var callReject: String { t("Reject", "拒绝") }
    static var callHangUp: String { t("Hang up", "挂断") }
    static var callMute: String { t("Mute", "静音") }
    static var callUnmute: String { t("Unmute", "取消静音") }
    static var callUnavailable: String { t("Connect a phone to control calls", "连接手机后可控制通话") }
    static var callFailedTitle: String { t("Call action failed", "通话操作失败") }
    static func callFailure(_ action: String) -> String {
        switch action {
        case "answer": return t("Could not answer the call.", "无法接听来电。")
        case "end":    return t("Could not end the call.", "无法挂断通话。")
        case "mute":   return t("Could not change mute.", "无法切换静音。")
        default:       return t("Could not perform the call action.", "无法执行通话操作。")
        }
    }

    // MARK: - Dialing

    static var dial: String { t("Dial", "拨号") }
    static var dialPlaceholder: String { t("Number or name", "号码或姓名") }
    static var dialAction: String { t("Call", "拨号") }
    static var callMethodLabel: String { t("Call via", "通话方式") }
    static var callMethodPhone: String { t("Phone", "手机") }
    static var callMethodMac: String { t("This Mac (Experimental)", "Mac 本机（实验性）") }
    static var callMethodMacUnavailable: String {
        t("Call audio is routed to this Mac over Bluetooth (HFP); pair the phone first.",
          "通话音频经由蓝牙（HFP）在 Mac 本机播放；请先与手机配对。")
    }
    static var callMethodMacWarning: String {
        t("Experimental: requires Bluetooth-Classic pairing and may interrupt the BLE link. Call audio is unverified. Use “Phone” by default.",
          "实验性：需经典蓝牙配对，可能中断 BLE 连接；通话音频未验证。默认请用“手机”。")
    }
    static var handsFreeConnect: String { t("Connect", "连接") }
    static var handsFreeSelectDevice: String { t("Select device to connect", "选择连接设备") }
    static var handsFreeHint: String {
        t("Pair the phone in System Settings → Bluetooth first.",
          "请先在“系统设置 → 蓝牙”中与手机完成配对。")
    }
    static func contactsSynced(_ n: Int) -> String { t("\(n) contacts", "\(n) 个联系人") }
    static var dialDisabledHint: String {
        t("Remote dialing is off on the phone (Contacts → Remote dialing).",
          "手机端已关闭“远程拨号”（联系人 → 远程拨号）。")
    }
    static var dialFailedTitle: String { t("Call not placed", "拨号未成功") }
    static func dialFailure(_ status: String) -> String {
        switch status {
        case "invalid":  return t("Invalid phone number.", "手机号无效。")
        case "disabled": return t("Remote dialing is disabled on the phone.", "手机端已关闭远程拨号。")
        default:         return t("Could not place the call.", "无法拨出电话。")
        }
    }

    // MARK: - Login item

    static var launchAtLogin: String { t("Launch at login", "开机自动启动") }

    // MARK: - Inline reply

    static var reply: String { t("Reply", "回复") }
    static var replySend: String { t("Send", "发送") }
    static var replyPlaceholder: String { t("Type a reply…", "输入回复…") }
    static var replyFailedTitle: String { t("Reply not sent", "回复未发送") }
    static func replyFailure(_ reason: String) -> String {
        switch reason {
        case "no_reply_action": return t("This app does not support replies.", "该应用不支持回复。")
        case "not_found":       return t("The notification is no longer active.", "该通知已不在。")
        default:                return t("Could not deliver the reply.", "无法发送回复。")
        }
    }

    // MARK: - Phone biometric unlock

    static var trustedDevices: String { t("Trusted devices", "受信任的设备") }
    static var addDevice: String { t("Add device", "添加设备") }
    static var noTrustedDevice: String { t("No phone paired yet.", "尚未配对手机。") }
    static var deviceAuthorized: String { t("Authorized", "已授权") }
    static var deviceRevoked: String { t("Revoked", "已撤销") }
    static var revoke: String { t("Revoke", "撤销授权") }
    static var pairCodeLabel: String { t("Confirm this code matches your phone:", "请确认与手机上显示的验证码一致：") }
    static var pairEnterCode: String { t("Enter the 6-digit code shown on your phone:", "输入手机上显示的 6 位验证码：") }
    static var pairCodeWrong: String { t("Code does not match. Try again.", "验证码不一致，请重试。") }
    static var confirm: String { t("Confirm", "确认") }
    static var testUnlock: String { t("Test unlock (send request to phone)", "测试解锁（向手机发送请求）") }
    static var pairWaitingPhone: String { t("Waiting for the phone to confirm (fingerprint)…", "等待手机确认（指纹）…") }
    static var allowManualLock: String { t("Unlock manual lock screen", "允许手动锁屏解锁") }
    static var allowLidWake: String { t("Unlock after lid-wake", "允许合盖唤醒解锁") }
    static var lastAuthJustNow: String { t("Last auth: just now", "最后认证：刚刚") }
    static var unlockGrantedTitle: String { t("Mac unlocked", "Mac 已解锁") }
    static var unlockFailedTitle: String { t("Unlock request failed", "解锁请求失败") }
    static func unlockFailure(_ reason: String) -> String {
        switch reason {
        case "signature_invalid": return t("Invalid signature.", "签名无效。")
        case "session_invalid":   return t("Challenge expired or already used.", "挑战已过期或已使用。")
        case "device_revoked":    return t("This phone is no longer authorized.", "该手机已不再被授权。")
        case "no_trusted_device": return t("No trusted phone.", "没有受信任的手机。")
        default:                  return t("Could not unlock.", "无法解锁。")
        }
    }
}
