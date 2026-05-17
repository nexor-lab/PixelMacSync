//
//  PixelSyncApp.swift
//  PixelSync
//
//  Created by Luigi Quitadamo on 14/05/2026.
//
import SwiftUI
import AppKit

@main
struct PixelSyncApp: App {

    init() {
        NSApplication.shared.applicationIconImage = NSImage(named: "AppIcon")
    }

    var body: some Scene {
        MenuBarExtra("PixelSync", systemImage: "iphone") {
            ContentView()
        }
        .menuBarExtraStyle(.window)
    }
}
