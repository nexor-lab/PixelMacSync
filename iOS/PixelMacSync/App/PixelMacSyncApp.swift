//
//  PixelMacSyncApp.swift
//  PixelMacSync iOS
//

import SwiftUI

@main
struct PixelMacSyncApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        NotificationManager.shared.setup()
        _ = BLEPeripheralManager.shared
        _ = UnlockController.shared
        _ = UnlockSettings.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onChange(of: scenePhase) { phase in
                    if phase == .active { UnlockController.shared.onAppActive() }
                }
        }
    }
}
