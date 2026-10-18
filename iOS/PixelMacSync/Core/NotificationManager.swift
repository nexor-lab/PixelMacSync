//
//  NotificationManager.swift
//  PixelMacSync iOS
//

import Foundation
import UserNotifications

final class NotificationManager: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationManager()

    private let center = UNUserNotificationCenter.current()

    func setup() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func postUnlock(macName: String) {
        let content = UNMutableNotificationContent()
        content.title = "解锁 Mac"
        content.body = "\(macName) 请求解锁 — 点击用面容 ID 验证"
        content.sound = .default
        let req = UNNotificationRequest(identifier: "px.unlock.\(UUID().uuidString)",
                                        content: content, trigger: nil)
        center.add(req)
    }

    // Foreground presentation
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler:
                                @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
