//
//  LoginItem.swift
//  PixelSync
//
//  "Launch at login" using a per-user LaunchAgent (macOS 12 compatible).
//  Writes ~/Library/LaunchAgents/it.luigi.pixelsyncmac.plist and (un)loads it.
//  No sudo, no system directory, no third-party helper.
//
import Foundation

enum LoginItem {

    static let label = "it.luigi.pixelsyncmac"

    static var plistURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/LaunchAgents/\(label).plist")
    }

    /// Enabled if our LaunchAgent plist exists.
    static var isEnabled: Bool {
        FileManager.default.fileExists(atPath: plistURL.path)
    }

    static func setEnabled(_ enabled: Bool) {
        enabled ? enable() : disable()
    }

    private static func enable() {
        guard let exe = Bundle.main.executablePath else { return }
        let dir = plistURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let plist = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key>
            <string>\(label)</string>
            <key>ProgramArguments</key>
            <array>
                <string>\(xmlEscape(exe))</string>
            </array>
            <key>RunAtLoad</key>
            <true/>
            <key>LimitLoadToSessionType</key>
            <string>Aqua</string>
            <key>ProcessType</key>
            <string>Interactive</string>
        </dict>
        </plist>
        """
        try? plist.write(to: plistURL, atomically: true, encoding: .utf8)
        launchctl(["load", "-w", plistURL.path])
    }

    private static func disable() {
        if FileManager.default.fileExists(atPath: plistURL.path) {
            launchctl(["unload", "-w", plistURL.path])
            try? FileManager.default.removeItem(at: plistURL)
        }
    }

    private static func launchctl(_ args: [String]) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = args
        try? process.run()
        process.waitUntilExit()
    }

    private static func xmlEscape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
         .replacingOccurrences(of: "<", with: "&lt;")
         .replacingOccurrences(of: ">", with: "&gt;")
    }
}
