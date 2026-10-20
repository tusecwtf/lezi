import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Deterministic identities for two distinct sleep creation paths. No storage
/// or family-network behavior is performed here. SHA-1 is used only for UUIDv5
/// identity compatibility, never for security, authentication or content trust.
public enum SleepWakeIdentity {
    /// New closed-sleep entry: Android RecordMutationCoordinator.closedSleepWakeUuid.
    /// Parsing the sleep UUID before calling makes textual case irrelevant.
    public static func closedSleepObservationUUID(sleepClientUUID: UUID) -> UUID {
        nameBasedUUIDv5(namespace: sleepClientUUID, name: "lezi-closed-sleep-wake")
    }

    /// Historical closed-sleep migration only: causal-sync-wire §11 and Android
    /// core/common/CausalIdentity.wakeObservationClientUuid. Preserve the original
    /// sleep UUID string exactly, including case; do not parse/reformat or trim it.
    /// This formula must not be substituted for new closed-sleep entry identities.
    public static func legacyObservationUUID(originalSleepClientUUID: String, legacyUpdatedAt: Int64) -> UUID {
        nameBasedUUIDv5(namespace: legacyNamespace,
                       name: "wake_obs_v1:\(originalSleepClientUUID):\(legacyUpdatedAt)")
    }

    private static let legacyNamespace = UUID(uuidString: "7c9e6679-7425-40de-944b-e07fc1f90ae7")!
}

/// RFC 4122 name-based UUIDv5: network-order namespace bytes, UTF-8 name,
/// SHA-1, first 128 bits with version 5 and IETF variant. Internal test seam.
func nameBasedUUIDv5(namespace: UUID, name: String) -> UUID {
    let n = namespace.uuid
    var bytes = Data([n.0, n.1, n.2, n.3, n.4, n.5, n.6, n.7,
                      n.8, n.9, n.10, n.11, n.12, n.13, n.14, n.15])
    bytes.append(contentsOf: name.utf8)
    var digest = Array(Insecure.SHA1.hash(data: bytes).prefix(16))
    digest[6] = (digest[6] & 0x0f) | 0x50
    digest[8] = (digest[8] & 0x3f) | 0x80
    return UUID(uuid: (digest[0], digest[1], digest[2], digest[3],
                       digest[4], digest[5], digest[6], digest[7],
                       digest[8], digest[9], digest[10], digest[11],
                       digest[12], digest[13], digest[14], digest[15]))
}
