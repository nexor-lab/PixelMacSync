//
//  BLEPeripheralManager.swift
//  PixelMacSync iOS
//
//  iPhone as the BLE peripheral (same GATT service/characteristics as the
//  Android app). The Mac stays the central and is unchanged.
//

import Foundation
import CoreBluetooth
import UIKit
import Network

final class BLEPeripheralManager: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    static let shared = BLEPeripheralManager()

    let serviceUUID = CBUUID(string: "58DF214B-9942-45A5-BAF9-7B24F5D0232C")
    let telemetryUUID = CBUUID(string: "CEFB6548-6C8A-4D25-A086-C8A69D3F6625")
    let notificationsUUID = CBUUID(string: "6E6C9609-9FFA-42E2-A882-B0C4398D58DE")
    let commandUUID = CBUUID(string: "586B06E6-CCC5-44B8-BFD9-5D2514A67842")

    @Published var bluetoothOn = false
    @Published var advertising = false
    @Published var connected = false
    @Published var macName = ""
    @Published var statusText = "等待连接…"

    private var manager: CBPeripheralManager!
    private var notificationsChar: CBMutableCharacteristic?
    private var telemetryChar: CBMutableCharacteristic?
    private var subscribed = false
    @Published private(set) var connectedMacId: String?

    private let pathMonitor = NWPathMonitor()
    private var path: NWPath?

    override init() {
        super.init()
        UIDevice.current.isBatteryMonitoringEnabled = true
        pathMonitor.pathUpdateHandler = { [weak self] p in
            self?.path = p
            DispatchQueue.main.async { self?.sendTelemetry() }
        }
        NotificationCenter.default.addObserver(forName: UIDevice.batteryLevelDidChangeNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            self?.sendTelemetry()
        }
        NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            self?.sendTelemetry()
        }
        pathMonitor.start(queue: DispatchQueue(label: "px.netpath"))
        manager = CBPeripheralManager(delegate: self, queue: nil,
                                      options: [CBPeripheralManagerOptionRestoreIdentifierKey:
                                                "it.luigi.pixelsync.ios.peripheral"])
    }

    func start() {
        if manager.state == .poweredOn && !advertising { buildAndAdvertise() }
    }

    /// Send a payload on the Notifications characteristic (iOS -> Mac).
    func send(_ payload: String) {
        guard let ch = notificationsChar, let data = payload.data(using: .utf8) else { return }
        _ = manager.updateValue(data, for: ch, onSubscribedCentrals: nil)
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        bluetoothOn = (peripheral.state == .poweredOn)
        if bluetoothOn {
            buildAndAdvertise()
            statusText = "正在广播，等待 Mac…"
        } else {
            advertising = false
            statusText = "蓝牙不可用"
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           willRestoreState dict: [String: Any]) {
        if let service = (dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService])?.first {
            for c in service.characteristics ?? [] {
                if c.uuid == notificationsUUID { notificationsChar = c as? CBMutableCharacteristic }
                if c.uuid == telemetryUUID { telemetryChar = c as? CBMutableCharacteristic }
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didAdd service: CBService, error: Error?) {
        startAdvertising()
    }

    private func buildAndAdvertise() {
        let service = CBMutableService(type: serviceUUID, primary: true)
        let telemetry = CBMutableCharacteristic(
            type: telemetryUUID,
            properties: [.read, .notify],
            value: nil, permissions: [.readable])
        let notifications = CBMutableCharacteristic(
            type: notificationsUUID,
            properties: [.notify],
            value: nil, permissions: [])
        let command = CBMutableCharacteristic(
            type: commandUUID,
            properties: [.write],
            value: nil, permissions: [.writeable])
        service.characteristics = [telemetry, notifications, command]
        telemetryChar = telemetry
        notificationsChar = notifications
        manager.removeAllServices()
        manager.add(service)
    }

    private func startAdvertising() {
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
        ])
        advertising = true
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        if characteristic.uuid == notificationsUUID {
            subscribed = true
        }
        if characteristic.uuid == telemetryUUID {
            sendTelemetry()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        if characteristic.uuid == notificationsUUID {
            subscribed = false
            connected = false
            statusText = "Mac 已断开"
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            defer { manager.respond(to: req, withResult: .success) }
            guard req.characteristic.uuid == commandUUID, let data = req.value else { continue }
            guard let text = String(data: data, encoding: .utf8) else { continue }
            handleCommand(text)
        }
    }

    // MARK: - Inbound commands (Mac -> phone)

    private func handleCommand(_ text: String) {
        let parts = text.components(separatedBy: UnlockProtocol.us)
        guard let head = parts.first else { return }

        switch head {
        case "HELLO":
            let macId = parts.count >= 2 ? parts[1] : "legacy"
            let name = parts.count >= 3 ? parts[2] : macId
            connectedMacId = macId
            DispatchQueue.main.async {
                self.macName = name
                self.connected = true
                self.statusText = "已连接"
            }
            send("SESSION_READY\(UnlockProtocol.us)\(UIDevice.current.name)\(UnlockProtocol.us)\(macId)")
            sendPolicy()
            sendTelemetry()

        case "SYNC_REQ":
            sendTelemetry()

        case "PAIR_BEGIN", "PAIR_DONE", "PAIR_CANCEL", "UNLOCK_REQUEST", "TRUST_REVOKED":
            UnlockController.shared.handleInbound(parts)

        default:
            break   // SYNC/MUSIC/HOTSPOT/DIAL/etc. are not supported on iOS
        }
    }

    func sendPolicy() {
        let s = UnlockSettings.shared
        send(UnlockProtocol.setPolicy(awayAutoLock: s.awayAutoLock, remoteWake: s.remoteWake))
    }

    func sendTelemetry() {
        guard let ch = telemetryChar else { return }
        let dev = UIDevice.current
        let battery = dev.batteryLevel < 0 ? 0 : Int(dev.batteryLevel * 100)
        let charging = (dev.batteryState == .charging || dev.batteryState == .full) ? "true" : "false"
        let wifi = path?.usesInterfaceType(.wifi) ?? false
        let network = wifi ? "Wi-Fi" : ((path?.usesInterfaceType(.cellular) ?? false) ? "蜂窝" : "—")
        let fields = [
            "\(battery)", charging, network, "0",
            wifi ? "true" : "false", "false",
            dev.model, dev.name, "1"
        ]
        let data = Data(fields.joined(separator: UnlockProtocol.us).utf8)
        _ = manager.updateValue(data, for: ch, onSubscribedCentrals: nil)
    }
}
