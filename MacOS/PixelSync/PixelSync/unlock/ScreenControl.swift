//
//  ScreenControl.swift
//  PixelSync
//
//  Lock the screen and wake the display without simulating keyboard input.
//  Locking uses login.framework's SACLockScreenImmediate (private, widely used);
//  waking uses the public IOPMAssertionDeclareUserActivity. No password is used.
//

import Foundation
import IOKit.pwr_mgt
import Darwin

enum ScreenControl {

    /// Lock the screen immediately (same effect as Ctrl+Cmd+Q).
    static func lockScreen() {
        guard let handle = dlopen(
            "/System/Library/PrivateFrameworks/login.framework/Versions/A/login",
            RTLD_NOW) else {
            NSLog("PixelSync: SACLockScreenImmediate unavailable")
            return
        }
        defer { dlclose(handle) }
        guard let sym = dlsym(handle, "SACLockScreenImmediate") else { return }
        typealias LockFn = @convention(c) () -> Void
        let fn = unsafeBitCast(sym, to: LockFn.self)
        fn()
        NSLog("PixelSync: screen locked (away)")
    }

    /// Wake the display (simulated local user activity; public API).
    static func wakeDisplay() {
        var id: IOPMAssertionID = 0
        IOPMAssertionDeclareUserActivity("PixelSync wake" as CFString, kIOPMUserActiveLocal, &id)
        NSLog("PixelSync: display wake requested")
    }
}
