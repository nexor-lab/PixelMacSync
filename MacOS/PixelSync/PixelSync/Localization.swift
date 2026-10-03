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
        case disconnected, scanning, connecting, cacheConnecting, connected,
             bluetoothOff, waking, restarting
    }

    static func state(_ s: State) -> String {
        switch s {
        case .disconnected:    return t("Disconnected", "未连接")
        case .scanning:        return t("Scanning…", "正在搜索…")
        case .connecting:      return t("Connecting…", "正在连接…")
        case .cacheConnecting: return t("Connecting (cache)…", "正在连接（缓存）…")
        case .connected:       return t("Connected", "已连接")
        case .bluetoothOff:    return t("Bluetooth OFF", "蓝牙已关闭")
        case .waking:          return t("Waking…", "正在唤醒…")
        case .restarting:      return t("Restarting…", "正在重启…")
        }
    }

    // MARK: - Menu bar / app

    static var appName: String { "PixelSync" }
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
}
