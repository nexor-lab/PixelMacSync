//
//  ContentView.swift
//  PixelMacSync iOS
//

import SwiftUI
import UIKit

struct ContentView: View {
    @ObservedObject var ble = BLEPeripheralManager.shared
    @ObservedObject var unlock = UnlockController.shared
    @ObservedObject var settings = UnlockSettings.shared

    @State private var showPolicy = false
    @State private var confirmUnbind: TrustedMac?

    var body: some View {
        NavigationStack {
            ZStack {
                GlassBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        connectionCard
                        macsCard
                        policyCard
                        aboutCard
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                if BuildInfo.isBeta {
                    Text("BETA\n\(BuildInfo.version)\n\(BuildInfo.timestamp)")
                        .font(.system(size: 26, weight: .black))
                        .foregroundColor(.red.opacity(0.16))
                        .multilineTextAlignment(.center)
                        .rotationEffect(.degrees(-30))
                        .allowsHitTesting(false)
                }
            }
            .navigationTitle("MacSync")
            .navigationBarTitleDisplayMode(.large)
            .preferredColorScheme(.dark)
            .alert("确认解绑？", isPresented: Binding(
                get: { confirmUnbind != nil },
                set: { if !$0 { confirmUnbind = nil } }
            )) {
                Button("取消", role: .cancel) { confirmUnbind = nil }
                Button("解绑", role: .destructive) {
                    if let m = confirmUnbind {
                        Task { await unlock.unbindWithAuth(m.macId) }
                    }
                    confirmUnbind = nil
                }
            } message: {
                Text("解绑后该 Mac 将无法用本机面容 ID 解锁，需要重新绑定。")
            }
        }
    }

    // MARK: - Connection

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: ble.connected ? "iphone.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right")
                    .font(.title3)
                Text(ble.connected ? (ble.macName.isEmpty ? "Mac" : ble.macName) : ble.statusText)
                    .font(.headline)
                Spacer()
                Circle()
                    .fill(ble.connected ? Color.green : Color.orange)
                    .frame(width: 10, height: 10)
            }
            Text(ble.bluetoothOn ? "蓝牙已开启" : "蓝牙不可用（模拟器不支持 BLE）")
                .font(.caption)
                .foregroundStyle(.secondary)
            if !unlock.lastMessage.isEmpty {
                Text(unlock.lastMessage).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    // MARK: - Macs

    private var macsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "laptopcomputer").font(.title3)
                Text("已连接的设备").font(.headline)
                Spacer()
            }

            let macs = TrustedMacStore.shared.all()
            if macs.isEmpty {
                Text("尚未配对 Mac").font(.subheadline).foregroundStyle(.secondary)
            } else {
                ForEach(macs, id: \.macId) { mac in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(mac.name).font(.subheadline.weight(.medium))
                        Text("已绑定面容解锁").font(.caption2).foregroundStyle(.green)
                        HStack(spacing: 16) {
                            Button {
                                unlock.triggerUnlock(macId: mac.macId)
                            } label: {
                                Text("唤醒远程解锁").font(.caption)
                            }
                            Button(role: .destructive) {
                                confirmUnbind = mac
                            } label: {
                                Text("解绑").font(.caption).foregroundStyle(.red)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 4)
                }
            }

            if let macId = ble.connectedMacId,
               !TrustedMacStore.shared.isTrusted(macId) {
                Button {
                    unlock.requestPairing()
                } label: {
                    Label("绑定当前 Mac（面容 ID）", systemImage: "faceid")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }

            if unlock.hasPendingUnlock {
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(unlock.pendingUnlockMacName) 请求解锁").font(.caption)
                    Button {
                        Task { await unlock.approveUnlock() }
                    } label: {
                        Label("使用面容 ID 解锁 Mac", systemImage: "faceid")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            if unlock.pairingActive {
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(unlock.pairingMacName) 请求绑定").font(.caption)
                    Button {
                        Task { await unlock.confirmPairing() }
                    } label: {
                        Label("确认配对（面容 ID）", systemImage: "faceid")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    // MARK: - Policy

    private var policyCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { showPolicy.toggle() }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "lock.shield").font(.title3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("远程解锁安全策略").font(.headline)
                        Text("设备远离自动锁定 · 远程唤醒").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: showPolicy ? "chevron.up" : "chevron.down")
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)

            if showPolicy {
                Toggle(isOn: $settings.awayAutoLock) {
                    VStack(alignment: .leading) {
                        Text("设备远离自动锁定")
                        Text("手机离开后自动锁定 Mac").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: $settings.remoteWake) {
                    VStack(alignment: .leading) {
                        Text("远程唤醒")
                        Text("关闭后隐藏绑定/唤醒操作").font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    // MARK: - About

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("远程解锁安全策略").font(.headline)
            Text("解锁仅通过 BLE 本地传输，无云端、无网络。手机使用 Secure Enclave 中的 P-256 私钥，对一次性 32 字节随机挑战做 ECDSA/SHA-256 签名；Mac 验签后经 macOS PAM 授权解锁。全程不存储、不传输、不输入 Mac 密码。")
                .font(.caption).foregroundStyle(.secondary)

            Divider()
            Text("iOS 限制").font(.headline)
            Text("通知转发/内联回复、来电、远程热点、跨 App 音乐、应用图标、信号强度在 iOS 上不可用；后台 BLE 为尽力而为，iPhone 锁屏/挂起时可能延迟。")
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }
}
