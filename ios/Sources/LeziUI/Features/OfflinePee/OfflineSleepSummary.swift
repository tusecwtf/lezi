#if canImport(SwiftUI)
import SwiftUI
import LeziStorage

struct OfflineSleepSummary: View {
    let sleep: OfflineSleep
    var body: some View {
        LeziText("睡眠", role: .titleSmall).accessibilityIdentifier("offline.record.kind")
        if OfflineSleepState.supports(sleep) {
            LeziText(sleep.interval.isOverlapPending ? "重叠待确认" : sleep.interval.isProvisional ? "暂定" : sleep.interval.isOpen ? "睡眠开放" : "已闭合")
                .accessibilityIdentifier("offline.record.sleep.status")
            if let end = sleep.interval.endTimestamp {
                LeziText(OfflineSleepState.durationLabel(start: sleep.interval.startTimestamp, end: end))
                    .accessibilityIdentifier("offline.record.sleep.duration")
                Text(Date(timeIntervalSince1970: Double(end) / 1000), format: .dateTime)
                    .accessibilityIdentifier("offline.record.sleep.end")
            }
            if let id = sleep.interval.endObservationClientUUID,
               let wake = sleep.observations.first(where: { $0.clientUUID.uuidString == id }) {
                LeziText("本机醒来观察", role: .label)
                    .accessibilityIdentifier("offline.record.sleep.wake")
                    .accessibilityValue(wake.clientUUID.uuidString)
                if let note = wake.note {
                    LeziText(note).accessibilityIdentifier("offline.record.sleep.wake.note")
                }
            }
            if sleep.interval.isProvisional {
                LeziText("还没有核对到确定的醒来时间，暂按最早的一次记录显示。", role: .label)
            }
            if sleep.interval.isOverlapPending {
                LeziText("这条较早的睡眠仍保持开放，没有被后来记录自动闭合。", role: .label)
            }
        } else {
            LeziText("历史睡眠原始内容已保留，当前只读。", role: .label)
                .accessibilityIdentifier("offline.record.sleep.readonly")
        }
    }
}
#endif
