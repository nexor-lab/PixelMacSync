//
//  PixelSyncApp.swift
//  PixelSync
//
//  Created by Luigi Quitadamo on 14/05/2026.
//  macOS 12.7 (Monterey) port: MenuBarExtra (macOS 13+) replaced with
//  AppKit NSStatusItem + NSPopover.
//
import SwiftUI
import AppKit
import Combine

@main
struct PixelSyncApp: App {

    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        // A WindowGroup is required by the App protocol, but this app is a
        // menu-bar-only agent (LSUIElement). We never open this window.
        Settings {
            EmptyView()
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {

    private var statusItem: NSStatusItem!
    private let popover = NSPopover()
    private let bleManager = BLEManager()
    private var cancellables = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Agent app: no Dock icon, no main menu window.
        NSApp.setActivationPolicy(.accessory)

        // --- Status item ---
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            button.image = statusImage(connected: false)
            button.image?.isTemplate = true
            button.action = #selector(statusItemClicked(_:))
            button.target = self
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
            button.toolTip = "PixelSync"
        }

        // --- Popover hosting the SwiftUI ContentView ---
        popover.contentSize = NSSize(width: 320, height: 200)
        popover.behavior = .transient
        popover.animates = false
        popover.contentViewController = NSHostingController(
            rootView: ContentView(bleManager: bleManager)
        )

        // --- Keep the menu-bar icon in sync with connection state ---
        bleManager.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                // objectWillChange fires *before* the change; hop to the next runloop tick.
                DispatchQueue.main.async { self?.refreshStatusIcon() }
            }
            .store(in: &cancellables)

        refreshStatusIcon()

        // Auto-start at login is ON by default: register the LaunchAgent on
        // first run so the user does not have to enable anything manually.
        if !UserDefaults.standard.bool(forKey: "px_login_item_initialized") {
            LoginItem.setEnabled(true)
            UserDefaults.standard.set(true, forKey: "px_login_item_initialized")
        }
    }

    private func refreshStatusIcon() {
        let connected = bleManager.isConnected
        statusItem.button?.image = statusImage(connected: connected)
        statusItem.button?.image?.isTemplate = true
        statusItem.button?.toolTip = "\(L10n.appName) — \(bleManager.connectionStatus)"
    }

    private func statusImage(connected: Bool) -> NSImage? {
        let name = connected ? "iphone" : "antenna.radiowaves.left.and.right"
        let image = NSImage(systemSymbolName: name, accessibilityDescription: "PixelSync")
        return image ?? NSImage(systemSymbolName: "iphone", accessibilityDescription: "PixelSync")
    }

    // URL-scheme command hook, e.g.:
    //   open "pixelsync://hotspot/on" | "pixelsync://hotspot/off" | "pixelsync://hotspot/status"
    //   open "pixelsync://music/play" | ".../pause" | ".../next" | ".../prev" | ".../seek/30000"
    //   open "pixelsync://reply?to=<notifId>&text=<text>"   (inline reply, automatable)
    // Useful for automation and for the menu-free control path.
    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls { handleCommandURL(url) }
    }

    private func handleCommandURL(_ url: URL) {
        guard url.scheme == L10n.urlScheme else { return }
        let action = url.pathComponents.last ?? ""
        switch url.host {
        case "hotspot":
            switch action {
            case "on":  bleManager.setRemoteHotspot(enable: true)
            case "off": bleManager.setRemoteHotspot(enable: false)
            default:    bleManager.queryHotspotState()
            }
        case "music":
            let parts = url.pathComponents.filter { $0 != "/" }
            switch parts.first {
            case "play":   bleManager.musicPlay()
            case "pause":  bleManager.musicPause()
            case "toggle": bleManager.musicToggle()
            case "next":   bleManager.musicNext()
            case "prev":   bleManager.musicPrevious()
            case "status": bleManager.queryMusicStatus()
            case "seek":
                if parts.count >= 2, let ms = Int(parts[1]) { bleManager.musicSeek(toMs: ms) }
            case "volume":
                if parts.count >= 2, let pct = Int(parts[1]) { bleManager.setMusicVolume(percent: pct) }
            default:
                break
            }
        case "call":
            switch action {
            case "answer": bleManager.callAnswer()
            case "end":    bleManager.callEnd()
            case "mute":   bleManager.callMuteToggle()
            default:       break
            }
        case "dial":
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
            var number = comps?.queryItems?.first(where: { $0.name == "number" })?.value ?? ""
            if number.isEmpty, let a = url.pathComponents.dropFirst().first, a != "/" { number = a }
            if !number.isEmpty { bleManager.dial(number) }
        case "login":
            LoginItem.setEnabled(action == "on")
        case "reply":
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
            let to = comps?.queryItems?.first(where: { $0.name == "to" })?.value ?? ""
            let text = comps?.queryItems?.first(where: { $0.name == "text" })?.value ?? ""
            if !to.isEmpty, !text.isEmpty { bleManager.sendReply(notifId: to, text: text) }
        default:
            break
        }
    }

    @objc private func statusItemClicked(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else {
            togglePopover(sender)
            return
        }
        if event.type == .rightMouseUp {
            showContextMenu()
        } else {
            togglePopover(sender)
        }
    }

    private func togglePopover(_ sender: NSStatusBarButton) {
        if popover.isShown {
            popover.performClose(sender)
        } else {
            popover.show(relativeTo: sender.bounds, of: sender, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    private func showContextMenu() {
        let menu = NSMenu()
        menu.addItem(withTitle: L10n.appName, action: nil, keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: L10n.quit,
                     action: #selector(NSApplication.terminate(_:)),
                     keyEquivalent: "q")
        statusItem.menu = menu
        statusItem.button?.performClick(nil)
        // Detach so left-click goes back to the popover behaviour.
        statusItem.menu = nil
    }
}
