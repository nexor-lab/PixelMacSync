import Foundation
import CryptoKit

/// Runtime architecture / capability detection.
///
/// The project's **minimum compatibility** is the 2015 Intel Mac (MacBookPro12,1)
/// on macOS 12.7. Apple Silicon Macs (through M4) may take newer/advanced code
/// paths behind these checks — but the Intel path must always remain the safe,
/// guaranteed baseline. Under Rosetta a process runs x86_64 and therefore uses the
/// baseline path too.
enum Platform {

    /// True only when running natively on Apple Silicon (arm64).
    static var isAppleSilicon: Bool {
        #if arch(arm64)
        return true
        #else
        return false
        #endif
    }

    /// Secure Enclave availability: true on Apple Silicon and T2 Intel Macs,
    /// **false on the 2015 Intel baseline** (no Secure Enclave), so those machines
    /// fall back to the local-key path.
    static var secureEnclaveAvailable: Bool {
        SecureEnclave.isAvailable
    }
}
