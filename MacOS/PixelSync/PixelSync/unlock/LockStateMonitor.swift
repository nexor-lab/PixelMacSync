//
//  LockStateMonitor.swift
//  PixelSync
//
//  Observes the macOS lock state so the app can start the unlock flow when the
//  screen is locked (manual lock and, after wake, the post-sleep lock). The
//  Authorization Plugin performs the actual unlock; this only triggers the flow.
//

import Foundation
import CoreGraphics

final class LockStateMonitor: NSObject {

    var onLocked: (() -> Void)?
    var onUnlocked: (() -> Void)?

    private(set) var locked = false

    func start() {
        let dnc = DistributedNotificationCenter.default()
        dnc.addObserver(self, selector: #selector(screenLocked),
                        name: NSNotification.Name("com.apple.screenIsLocked"), object: nil)
        dnc.addObserver(self, selector: #selector(screenUnlocked),
                        name: NSNotification.Name("com.apple.screenIsUnlocked"), object: nil)
        // Fallback signal on some setups (screensaver-based locking).
        dnc.addObserver(self, selector: #selector(screenLocked),
                        name: NSNotification.Name("com.apple.screensaver.didstart"), object: nil)
        dnc.addObserver(self, selector: #selector(screenUnlocked),
                        name: NSNotification.Name("com.apple.screensaver.didstop"), object: nil)
        locked = Self.isScreenLocked()
    }

    func stop() {
        DistributedNotificationCenter.default().removeObserver(self)
    }

    /// Reads the console session dictionary; true while the lock screen is up.
    static func isScreenLocked() -> Bool {
        guard let dict = CGSessionCopyCurrentDictionary() as? [String: Any] else { return false }
        return (dict["CGSSessionScreenIsLocked"] as? Bool) ?? false
    }

    @objc private func screenLocked() {
        locked = true
        onLocked?()
    }

    @objc private func screenUnlocked() {
        locked = false
        onUnlocked?()
    }
}
