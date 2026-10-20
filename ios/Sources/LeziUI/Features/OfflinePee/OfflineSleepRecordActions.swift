#if canImport(SwiftUI)
import SwiftUI
import LeziStorage

struct OfflineSleepRecordActions: View {
    let sleep: OfflineSleep
    let localActorUUID: UUID
    let onEdit: (Bool) -> Void
    let onObservations: () -> Void
    var body: some View {
        if OfflineSleepState.supports(sleep) {
            Button("醒来观察", action: onObservations)
                .accessibilityIdentifier("offline.sleep.observations.open")
            Button("编辑睡下") { onEdit(false) }.accessibilityIdentifier("offline.sleep.edit.start")
            if let wake = OfflineSleepState.editableProjectedWake(sleep, localActorUUID: localActorUUID),
               wake.timestampMillis > sleep.record.timestampMillis {
                Button("编辑起止时间") { onEdit(true) }.accessibilityIdentifier("offline.sleep.edit.completed")
            }
        }
    }
}
#endif
