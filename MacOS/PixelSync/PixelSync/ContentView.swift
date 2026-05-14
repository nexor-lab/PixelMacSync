//
//  ContentView.swift
//  PixelSync
//
//  Created by Luigi Quitadamo on 14/05/2026.
//
import SwiftUI

struct ContentView: View {
    // Inizializziamo il nostro BLEManager.
    // @StateObject assicura che l'istanza sopravviva ai ricaricamenti dell'interfaccia
    @StateObject private var bleManager = BLEManager()
    
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: bleManager.isSwitchedOn ? "bluetooth" : "bluetooth.slash")
                .font(.system(size: 60))
                .foregroundStyle(bleManager.isSwitchedOn ? .blue : .gray)
            
            Text("PixelSync")
                .font(.largeTitle)
                .fontWeight(.bold)
            
            // Mostriamo lo stato della connessione in tempo reale
            Text(bleManager.connectionStatus)
                .font(.headline)
                .foregroundStyle(.secondary)
            
            // Un piccolo indicatore visivo se sta cercando o è connesso
            if bleManager.connectionStatus.contains("Scansione") {
                ProgressView()
                    .padding(.top, 10)
            } else if bleManager.connectionStatus.contains("Connesso") {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(.green)
                    .font(.title)
                    .padding(.top, 10)
            }
        }
        .padding()
        .frame(minWidth: 300, minHeight: 250)
    }
}

#Preview {
    ContentView()
}
