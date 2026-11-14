//
//  Theme.swift
//  PixelMacSync iOS
//
//  A Liquid-Glass-inspired look built from standard materials/blur so it builds
//  with the iOS 16 SDK. On iOS 26+ this can be swapped for .glassEffect().
//

import SwiftUI

struct GlassCard: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(.ultraThinMaterial,
                        in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.18), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.18), radius: 18, y: 10)
    }
}

extension View {
    func glassCard() -> some View { modifier(GlassCard()) }
}

struct GlassBackground: View {
    var body: some View {
        LinearGradient(
            colors: [
                Color(red: 0.06, green: 0.09, blue: 0.16),
                Color(red: 0.10, green: 0.16, blue: 0.28),
                Color(red: 0.05, green: 0.07, blue: 0.12),
            ],
            startPoint: .topLeading, endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}
