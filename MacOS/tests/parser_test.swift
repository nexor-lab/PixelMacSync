//
//  parser_test.swift
//  Standalone unit tests for the PixelMacSync BLE packet parser.
//  Builds and runs with Command Line Tools only (no Xcode, no hardware).
//
//  ./run_tests.sh
//
import Foundation

@main
enum ParserTests {

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

    static func data(_ s: String) -> Data { s.data(using: .utf8)! }
    static let US = "\u{1F}"

    static func main() {
        print("== Telemetry ==")
        do {
            let fields = PixelPacket.fields(from: data("87\(US)true\(US)MyWiFi\(US)3\(US)true\(US)false\(US)Poco F5 Pro"))!
            let t = PixelPacket.parseTelemetry(fields)
            check(t != nil, "parses valid telemetry")
            check(t?.battery == "87%", "battery formatting")
            check(t?.isCharging == true, "isCharging true")
            check(t?.network == "MyWiFi", "network/SSID")
            check(t?.signal == 3, "signal int")
            check(t?.isWifi == true, "isWifi true")
            check(t?.isHotspot == false, "isHotspot false")
            check(t?.deviceName == "Poco F5 Pro", "device name")

            check(PixelPacket.parseTelemetry(["1", "true"]) == nil, "rejects short telemetry")
            let bad = PixelPacket.fields(from: data("5\(US)false\(US)--\(US)x\(US)false\(US)false\(US)Phone"))!
            check(PixelPacket.parseTelemetry(bad)?.signal == 0, "non-numeric signal defaults to 0")
        }

        print("== Notification POST ==")
        do {
            let fields = PixelPacket.fields(from: data("POST\(US)n1a2b3\(US)com.tencent.mm\(US)张三\(US)晚上一起吃饭吗？"))!
            let n = PixelPacket.parseNotification(fields)
            check(n?.kind == .post, "kind == post")
            check(n?.id == "n1a2b3", "id")
            check(n?.package == "com.tencent.mm", "package")
            check(n?.title == "张三", "UTF-8 title")
            check(n?.body == "晚上一起吃饭吗？", "UTF-8 body")
            check(n?.canReply == false, "missing reply flag defaults false")

            let replyable = PixelPacket.parseNotification(PixelPacket.fields(from: data("POST\(US)n9z8y7\(US)com.tencent.mm\(US)张三\(US)msg\(US)微信\(US)1"))!)
            check(replyable?.canReply == true, "reply flag 1 -> canReply")
            check(replyable?.appLabel == "微信", "appLabel before reply flag")

            let notReplyable = PixelPacket.parseNotification(PixelPacket.fields(from: data("POST\(US)n1\(US)com.twitter.android\(US)t\(US)b\(US)X\(US)0"))!)
            check(notReplyable?.canReply == false, "reply flag 0 -> no reply")

            check(PixelPacket.parseNotification(["POST", "id"]) == nil, "rejects short POST")
        }

        print("== Notification REMOVE ==")
        do {
            let fields = PixelPacket.fields(from: data("REMOVE\(US)n1a2b3"))!
            let n = PixelPacket.parseNotification(fields)
            check(n?.kind == .remove, "kind == remove")
            check(n?.id == "n1a2b3", "remove id")
        }

        print("== Reply result ==")
        do {
            let ok = PixelPacket.parseReplyResult(PixelPacket.fields(from: data("REPLY_RESULT\(US)n1a2b3\(US)ok"))!)
            check(ok?.id == "n1a2b3", "reply result id")
            check(ok?.status == .ok, "reply result ok")

            let noAction = PixelPacket.parseReplyResult(["REPLY_RESULT", "n1", "no_reply_action"])
            check(noAction?.status == .noReplyAction, "reply result no_reply_action")

            let unknown = PixelPacket.parseReplyResult(["REPLY_RESULT", "n1", "weird"])
            check(unknown?.status == .error, "unknown reply status defaults to error")

            check(PixelPacket.parseReplyResult(["REPLY_RESULT", "n1"]) == nil, "rejects short reply result")
            check(PixelPacket.parseReplyResult(PixelPacket.fields(from: data("POST\(US)x\(US)y\(US)z\(US)w"))!) == nil, "POST is not a reply result")
        }

        print("== Call events ==")
        do {
            let ringing = PixelPacket.parseCall(PixelPacket.fields(from: data("CALL\(US)RINGING\(US)13800001234\(US)张三"))!)
            check(ringing?.event == .ringing, "RINGING event")
            check(ringing?.number == "13800001234", "caller number")
            check(ringing?.name == "张三", "caller name")

            let offhook = PixelPacket.parseCall(PixelPacket.fields(from: data("CALL\(US)OFFHOOK\(US)13800001234"))!)
            check(offhook?.event == .offhook, "OFFHOOK event")
            check(offhook?.name == "", "missing name defaults empty")

            let idle = PixelPacket.parseCall(PixelPacket.fields(from: data("CALL\(US)IDLE\(US)"))!)
            check(idle?.event == .idle, "IDLE event")

            let missed = PixelPacket.parseCall(PixelPacket.fields(from: data("CALL\(US)MISSED\(US)13800001234\(US)"))!)
            check(missed?.event == .missed, "MISSED event")

            check(PixelPacket.parseCall(PixelPacket.fields(from: data("CALL\(US)BOGUS\(US)1\(US)x"))!) == nil, "rejects unknown call event")
            check(PixelPacket.parseCall(["CALL", "RINGING"]) == nil, "rejects short call packet")
        }

        print("== Music metadata ==")
        do {
            let m = PixelPacket.parseMusic(PixelPacket.fields(from: data("MUSIC_META\(US)晴天\(US)周杰伦\(US)叶惠美\(US)269000\(US)45000\(US)playing"))!)
            check(m != nil, "parses valid music meta")
            check(m?.title == "晴天", "music title UTF-8")
            check(m?.artist == "周杰伦", "music artist")
            check(m?.album == "叶惠美", "music album")
            check(m?.durationMs == 269000, "music duration")
            check(m?.positionMs == 45000, "music position")
            check(m?.state == .playing, "music state playing")
            check(m?.isPlaying == true, "isPlaying convenience")

            let paused = PixelPacket.parseMusic(PixelPacket.fields(from: data("MUSIC_META\(US)t\(US)a\(US)b\(US)0\(US)0\(US)paused"))!)
            check(paused?.state == .paused, "music state paused")

            let keyed = PixelPacket.parseMusic(PixelPacket.fields(from: data("MUSIC_META\(US)t\(US)a\(US)b\(US)0\(US)0\(US)playing\(US)com.spotify-1a2b"))!)
            check(keyed?.coverKey == "com.spotify-1a2b", "music cover key (8th field)")
            check(PixelPacket.parseMusic(PixelPacket.fields(from: data("MUSIC_META\(US)t\(US)a\(US)b\(US)0\(US)0\(US)playing"))!)?.coverKey == "", "music cover key defaults empty")

            let bogus = PixelPacket.parseMusic(PixelPacket.fields(from: data("MUSIC_META\(US)t\(US)a\(US)b\(US)0\(US)0\(US)weird"))!)
            check(bogus?.state == .stopped, "unknown music state defaults to stopped")

            check(PixelPacket.parseMusic(["MUSIC_META", "t"]) == nil, "rejects short music meta")
            check(PixelPacket.parseMusic(PixelPacket.fields(from: data("POST\(US)x\(US)y\(US)z\(US)w"))!) == nil, "POST is not music")
        }

        print("== Volume ==")
        do {
            check(PixelPacket.parseVolume(PixelPacket.fields(from: data("MUSIC_VOLUME\(US)42"))!) == 42, "parses volume")
            check(PixelPacket.parseVolume(PixelPacket.fields(from: data("MUSIC_VOLUME\(US)0"))!) == 0, "volume 0")
            check(PixelPacket.parseVolume(PixelPacket.fields(from: data("MUSIC_VOLUME\(US)100"))!) == 100, "volume 100")
            check(PixelPacket.parseVolume(["MUSIC_VOLUME"]) == nil, "rejects short volume")
            check(PixelPacket.parseVolume(PixelPacket.fields(from: data("MUSIC_META\(US)t\(US)a\(US)b\(US)0\(US)0\(US)playing"))!) == nil, "meta is not volume")
        }

        print("== Cover art transfer ==")
        do {
            let begin = PixelPacket.parseArt(PixelPacket.fields(from: data("ART_BEGIN\(US)com.spotify-1a2b"))!)!
            check(begin.kind == .begin, "art begin kind")
            check(begin.key == "com.spotify-1a2b", "art key")

            let data1 = PixelPacket.parseArt(PixelPacket.fields(from: data("ART_DATA\(US)com.spotify-1a2b\(US)3\(US)QUJD"))!)!
            check(data1.kind == .data, "art data kind")
            check(data1.seq == 3, "art seq")
            check(data1.chunk == "QUJD", "art chunk")

            let end = PixelPacket.parseArt(PixelPacket.fields(from: data("ART_END\(US)com.spotify-1a2b"))!)!
            check(end.kind == .end, "art end kind")

            check(PixelPacket.parseArt(["ART_DATA", "k"]) == nil, "rejects short art data")
            check(PixelPacket.parseArt(["ICON_BEGIN", "x"]) == nil, "ICON is not art")
        }

        print("== Robustness ==")
        do {
            check(PixelPacket.fields(from: Data([0xFF, 0xFE, 0xFD])) == nil, "rejects invalid UTF-8")
            check(PixelPacket.parseNotification(["FOO", "bar"]) == nil, "rejects unknown action")
            check(PixelPacket.parseCall(PixelPacket.fields(from: data("POST\(US)x\(US)y\(US)z\(US)w"))!) == nil, "POST is not a call")
        }

        print("")
        print("RESULT: \(passed) passed, \(failed) failed")
        exit(failed == 0 ? 0 : 1)
    }
}
