//
//  Protocol.swift
//  PixelSync
//
//  Pure, dependency-free parsing of the PixelMacSync BLE wire protocol.
//  Kept separate from BLEManager so it can be unit-tested without any
//  CoreBluetooth hardware. See BLE_PROTOCOL.md.
//
import Foundation

/// A contact synced from the phone (defined here so the parser tests stay
/// dependency-free; the encrypted store lives in ContactsStore.swift).
struct MacContact: Codable, Equatable {
    let id: String
    var name: String
    var number: String
}

enum PixelPacket {

    /// Split a raw UTF-8 characteristic value into `US`-separated fields.
    static func fields(from data: Data) -> [String]? {
        guard let string = String(data: data, encoding: .utf8) else { return nil }
        return string.components(separatedBy: "\u{1F}")
    }

    // MARK: - Telemetry

    struct Telemetry: Equatable {
        let battery: String      // e.g. "87%"
        let isCharging: Bool
        let network: String
        let signal: Int
        let isWifi: Bool
        let isHotspot: Bool
        let deviceName: String   // phone model
        let btName: String       // phone Bluetooth name (8th field; "" on older payloads)
    }

    static func parseTelemetry(_ fields: [String]) -> Telemetry? {
        guard fields.count >= 7 else { return nil }
        return Telemetry(
            battery: "\(fields[0])%",
            isCharging: fields[1] == "true",
            network: fields[2],
            signal: Int(fields[3]) ?? 0,
            isWifi: fields[4] == "true",
            isHotspot: fields[5] == "true",
            deviceName: fields[6],
            btName: fields.count >= 8 ? fields[7] : ""
        )
    }

    // MARK: - Notification

    enum NotificationKind { case post, remove }

    struct Notification: Equatable {
        let kind: NotificationKind
        let id: String
        let package: String
        let title: String
        let body: String
        let appLabel: String   // sender app name (empty on older payloads)
        let canReply: Bool     // origin app exposes a free-form reply action
    }

    static func parseNotification(_ fields: [String]) -> Notification? {
        guard let action = fields.first else { return nil }
        if action == "POST", fields.count >= 5 {
            let appLabel = fields.count >= 6 ? fields[5] : ""
            let replyFlag = fields.count >= 7 ? fields[6].lowercased() : "0"
            let canReply = (replyFlag == "1" || replyFlag == "true" || replyFlag == "reply")
            return Notification(kind: .post, id: fields[1], package: fields[2],
                                title: fields[3], body: fields[4], appLabel: appLabel,
                                canReply: canReply)
        }
        if action == "REMOVE", fields.count >= 2 {
            return Notification(kind: .remove, id: fields[1], package: "",
                                title: "", body: "", appLabel: "", canReply: false)
        }
        return nil
    }

    // MARK: - Reply result (Android -> Mac)

    /// Outcome of a `REPLY` command, so the Mac can surface failures (an app
    /// without a RemoteInput reply action, a notification already dismissed…).
    struct ReplyResult: Equatable {
        enum Status: String {
            case ok
            case notFound = "not_found"
            case noReplyAction = "no_reply_action"
            case error
        }
        let id: String
        let status: Status
    }

    static func parseReplyResult(_ fields: [String]) -> ReplyResult? {
        guard fields.count >= 3, fields[0] == "REPLY_RESULT" else { return nil }
        return ReplyResult(id: fields[1],
                           status: ReplyResult.Status(rawValue: fields[2]) ?? .error)
    }

    // MARK: - Session handshake (Android -> Mac)

    /// Confirmation that the full application session is established. The Mac
    /// only shows "Connected" after receiving this (see BLEManager).
    struct SessionReady: Equatable {
        let phoneName: String
        let macId: String
    }

    static func parseSessionReady(_ fields: [String]) -> SessionReady? {
        guard fields.count >= 2, fields[0] == "SESSION_READY" else { return nil }
        let macId = fields.count >= 3 ? fields[2] : ""
        return SessionReady(phoneName: fields[1], macId: macId)
    }

    /// The phone refused this Mac as the session peer (Multi-Mac): it is not the
    /// active Mac, or the user disconnected it. Returns the reason code.
    static func parseSessionRejected(_ fields: [String]) -> String? {
        guard fields.count >= 2, fields[0] == "SESSION_REJECTED" else { return nil }
        return fields[1]
    }

    // MARK: - Call

    struct Call: Equatable {
        enum Event: String {
            case ringing = "RINGING"
            case offhook = "OFFHOOK"
            case idle = "IDLE"
            case missed = "MISSED"
        }
        let event: Event
        let number: String
        let name: String
    }

    static func parseCall(_ fields: [String]) -> Call? {
        guard fields.count >= 3, fields[0] == "CALL",
              let event = Call.Event(rawValue: fields[1]) else { return nil }
        let number = fields[2]
        let name = fields.count >= 4 ? fields[3] : ""
        return Call(event: event, number: number, name: name)
    }

    // MARK: - Contacts sync (Android -> Mac)

    static func parseContactsBegin(_ fields: [String]) -> Int? {
        guard fields.count >= 2, fields[0] == "CONTACT_BEGIN" else { return nil }
        return Int(fields[1])
    }

    static func parseContact(_ fields: [String]) -> MacContact? {
        guard fields.count >= 4, fields[0] == "CONTACT" else { return nil }
        return MacContact(id: fields[1], name: fields[2], number: fields[3])
    }

    static func isContactsEnd(_ fields: [String]) -> Bool {
        fields.first == "CONTACT_END"
    }

    static func parseContactRemove(_ fields: [String]) -> String? {
        guard fields.count >= 2, fields[0] == "CONTACT_DEL" else { return nil }
        return fields[1]
    }

    /// Outcome of a `DIAL` command: ok | failed | invalid.
    static func parseDialResult(_ fields: [String]) -> String? {
        guard fields.count >= 2, fields[0] == "DIAL_RESULT" else { return nil }
        return fields[1]
    }

    // MARK: - Call control replies (Android -> Mac)

    /// Outcome of a `CALL_ANSWER` / `CALL_END` / `CALL_MUTE` command.
    struct CallResult: Equatable {
        let action: String   // answer | end | mute
        let status: String   // ok | failed
        var isOK: Bool { status == "ok" }
    }

    static func parseCallResult(_ fields: [String]) -> CallResult? {
        guard fields.count >= 3, fields[0] == "CALL_RESULT" else { return nil }
        return CallResult(action: fields[1], status: fields[2])
    }

    /// Simulated/real microphone mute state.
    static func parseCallMuteState(_ fields: [String]) -> Bool? {
        guard fields.count >= 2, fields[0] == "CALL_MUTE_STATE" else { return nil }
        return fields[1] == "ON"
    }

    // MARK: - Hotspot control replies

    struct Hotspot {
        enum Kind { case state, result, error }
        let kind: Kind
        /// "ON"/"OFF" for state; "OK"/"ALREADY_ON"/"ALREADY_OFF" for result;
        /// an error code for error.
        let value: String
    }

    // MARK: - App icon transfer

    struct IconPacket {
        enum Kind { case begin, data, end }
        let kind: Kind
        let package: String
        let seq: Int
        let chunk: String
    }

    static func parseIcon(_ fields: [String]) -> IconPacket? {
        guard let action = fields.first else { return nil }
        switch action {
        case "ICON_BEGIN":
            guard fields.count >= 2 else { return nil }
            return IconPacket(kind: .begin, package: fields[1], seq: 0, chunk: "")
        case "ICON_DATA":
            guard fields.count >= 4 else { return nil }
            return IconPacket(kind: .data, package: fields[1], seq: Int(fields[2]) ?? 0, chunk: fields[3])
        case "ICON_END":
            guard fields.count >= 2 else { return nil }
            return IconPacket(kind: .end, package: fields[1], seq: 0, chunk: "")
        default:
            return nil
        }
    }

    static func parseHotspot(_ fields: [String]) -> Hotspot? {
        guard fields.count >= 2 else { return nil }
        switch fields[0] {
        case "HOTSPOT_STATE": return Hotspot(kind: .state, value: fields[1])
        case "HOTSPOT_RESULT": return Hotspot(kind: .result, value: fields[1])
        case "HOTSPOT_ERROR": return Hotspot(kind: .error, value: fields[1])
        default: return nil
        }
    }

    // MARK: - Music metadata

    struct MusicMeta: Equatable {
        enum State: String { case playing, paused, stopped }
        let title: String
        let artist: String
        let album: String
        let durationMs: Int
        let positionMs: Int
        let state: State
        /// Cover-art key for this track (empty on older payloads / no art).
        let coverKey: String
        var isPlaying: Bool { state == .playing }
    }

    static func parseMusic(_ fields: [String]) -> MusicMeta? {
        guard fields.count >= 7, fields[0] == "MUSIC_META" else { return nil }
        let state = MusicMeta.State(rawValue: fields[6]) ?? .stopped
        let coverKey = fields.count >= 8 ? fields[7] : ""
        return MusicMeta(
            title: fields[1],
            artist: fields[2],
            album: fields[3],
            durationMs: Int(fields[4]) ?? 0,
            positionMs: Int(fields[5]) ?? 0,
            state: state,
            coverKey: coverKey
        )
    }

    // MARK: - Volume (media stream)

    static func parseVolume(_ fields: [String]) -> Int? {
        guard fields.count >= 2, fields[0] == "MUSIC_VOLUME" else { return nil }
        return Int(fields[1])
    }

    // MARK: - Cover art transfer

    struct ArtPacket {
        enum Kind { case begin, data, end }
        let kind: Kind
        let key: String
        let seq: Int
        let chunk: String
    }

    static func parseArt(_ fields: [String]) -> ArtPacket? {
        guard let action = fields.first else { return nil }
        switch action {
        case "ART_BEGIN":
            guard fields.count >= 2 else { return nil }
            return ArtPacket(kind: .begin, key: fields[1], seq: 0, chunk: "")
        case "ART_DATA":
            guard fields.count >= 4 else { return nil }
            return ArtPacket(kind: .data, key: fields[1], seq: Int(fields[2]) ?? 0, chunk: fields[3])
        case "ART_END":
            guard fields.count >= 2 else { return nil }
            return ArtPacket(kind: .end, key: fields[1], seq: 0, chunk: "")
        default:
            return nil
        }
    }
}
