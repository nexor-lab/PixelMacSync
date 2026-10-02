//
//  LegacyNotifier.swift
//  PixelSync
//
//  Fallback notification delivery using the (deprecated) NSUserNotification API.
//
//  Why: on macOS 12.7 the modern UserNotifications framework refuses apps that
//  are not signed with an Apple-issued certificate. NSUserNotification still
//  works for such apps (it shows a one-time "allow notifications" prompt) and —
//  unlike the osascript route — the banner is owned by OUR app, so it shows the
//  sender app name and a custom content image instead of the Script Editor icon.
//
import AppKit

enum LegacyNotifier {

    static func deliver(id: String, title: String, subtitle: String,
                        body: String, sound: Bool, icon: NSImage?) {
        let n = NSUserNotification()
        n.identifier = id
        n.title = title
        if !subtitle.isEmpty { n.subtitle = subtitle }
        n.informativeText = body
        if sound { n.soundName = NSUserNotificationDefaultSoundName }
        if let icon = icon { n.contentImage = icon }
        NSUserNotificationCenter.default.deliver(n)
    }

    static func remove(id: String) {
        let center = NSUserNotificationCenter.default
        for n in center.deliveredNotifications where n.identifier == id {
            center.removeDeliveredNotification(n)
        }
    }

    /// Icon for a notification: an explicit per-package PNG if present, else the
    /// Mac counterpart app's icon (by its mapped name), else nil (= app icon).
    static func icon(package: String, appName: String?) -> NSImage? {
        let fm = FileManager.default
        if let pics = fm.urls(for: .picturesDirectory, in: .userDomainMask).first {
            let png = pics.appendingPathComponent("MacSyncIcons", isDirectory: true)
                .appendingPathComponent("\(package).png")
            if fm.fileExists(atPath: png.path), let img = NSImage(contentsOf: png) {
                return img
            }
        }
        if let name = appName, !name.isEmpty {
            let dirs = ["/Applications", "/System/Applications",
                        NSString(string: "~/Applications").expandingTildeInPath]
            for dir in dirs {
                let path = "\(dir)/\(name).app"
                if fm.fileExists(atPath: path) {
                    return NSWorkspace.shared.icon(forFile: path)
                }
            }
        }
        return nil
    }
}
