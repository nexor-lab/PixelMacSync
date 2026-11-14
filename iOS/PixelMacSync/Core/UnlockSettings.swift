//
//  UnlockSettings.swift
//  PixelMacSync iOS
//

import Foundation

final class UnlockSettings: ObservableObject {
    static let shared = UnlockSettings()

    private let prefs = UserDefaults.standard
    private let kAway = "px.ios.awayAutoLock"
    private let kWake = "px.ios.remoteWake"

    @Published var awayAutoLock: Bool {
        didSet { prefs.set(awayAutoLock, forKey: kAway); onChanged?() }
    }
    @Published var remoteWake: Bool {
        didSet { prefs.set(remoteWake, forKey: kWake); onChanged?() }
    }

    var onChanged: (() -> Void)?

    private init() {
        awayAutoLock = (prefs.object(forKey: kAway) as? Bool) ?? true
        remoteWake = (prefs.object(forKey: kWake) as? Bool) ?? true
    }
}
