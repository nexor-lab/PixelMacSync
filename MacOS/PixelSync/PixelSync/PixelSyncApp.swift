//
//  PixelSyncApp.swift
//  PixelSync
//
//  Created by Luigi Quitadamo on 14/05/2026.
//
import SwiftUI

@main
struct PixelSyncApp: App {
    var body: some Scene {
        // Rimuoviamo WindowGroup e usiamo MenuBarExtra per agganciarci in alto a destra
        MenuBarExtra("PixelSync", systemImage: "iphone") {
            ContentView()
        }
        // Questo modificatore fa aprire l'app come un pannello fluttuante
        // invece che come un classico menu a tendina testuale
        .menuBarExtraStyle(.window)
    }
}
