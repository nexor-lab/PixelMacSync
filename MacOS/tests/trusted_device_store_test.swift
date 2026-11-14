import Foundation

@main
enum TrustedDeviceStoreTests {
    static var passed = 0
    static var failed = 0

    static func check(_ condition: Bool, _ name: String) {
        if condition {
            passed += 1
            print("  PASS  \(name)")
        } else {
            failed += 1
            print("  FAIL  \(name)")
        }
    }

    static func device(
        id: String,
        name: String,
        createdAt: Date = Date()
    ) -> TrustedPhone {
        TrustedPhone(
            id: id,
            name: name,
            publicKeyB64: "AQID",
            sessionKeyB64: "BAUG",
            pairingId: "pair-\(id)",
            enabled: true,
            allowManualLock: true,
            allowLidWake: true,
            createdAt: createdAt,
            lastAuthAt: nil
        )
    }

    static func main() throws {
        print("== Trusted device cleanup ==")
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("pixelsync-trusted-store-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }

        let store = TrustedDeviceStore(folder: folder)
        let current = device(id: "current-phone", name: "POCO F5 Pro")
        store.upsert(current)
        store.upsert(device(id: "stale-phone", name: "  poco f5 pro  "))
        store.upsert(device(id: "other-phone", name: "Pixel 9"))

        check(store.devices.count == 3, "stores distinct records initially")
        check(store.removeDuplicateNames(keeping: current.id) == 1,
              "removes one stale same-name record")
        check(store.devices.count == 2, "keeps current and differently named devices")
        check(store.get(current.id) != nil, "keeps authenticated device")
        check(store.get("stale-phone") == nil, "removes stale duplicate")
        check(store.get("other-phone") != nil, "keeps different device")
        check(store.removeDuplicateNames(keeping: current.id) == 0,
              "cleanup is idempotent")
        check(store.removeDuplicateNames(keeping: "missing-phone") == 0,
              "missing current device is a no-op")

        let reloaded = TrustedDeviceStore(folder: folder)
        check(reloaded.devices.count == 2, "persists cleanup result")

        print("TrustedDeviceStore: \(passed) passed, \(failed) failed")
        if failed > 0 { exit(1) }
    }
}
