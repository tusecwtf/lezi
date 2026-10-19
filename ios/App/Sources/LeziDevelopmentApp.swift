import Foundation
import SwiftUI
import LeziStorage
import LeziUI

/// Unsigned development host for the native offline slice, not the full product.
@main
@MainActor
struct LeziDevelopmentApp: App {
    var body: some Scene {
        WindowGroup {
            LeziTheme {
                DevelopmentBootstrapView()
            }
        }
    }
}

@MainActor
private struct DevelopmentBootstrapView: View {
    private enum State {
        case loading
        case ready(OfflineStore)
        case failed
    }

    @State private var state: State = .loading
    @State private var attempt = 0

    var body: some View {
        Group {
            switch state {
            case .loading:
                ProgressView("正在打开本机开发数据")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case let .ready(store):
                OfflinePeeView(store: store)
            case .failed:
                VStack(alignment: .leading, spacing: 16) {
                    LeziText("无法打开本机开发数据", role: .title)
                    LeziText("存储可能不可用，或数据库版本不受此开发版本支持。未清空或替换现有数据。")
                    Button("重试打开") { attempt += 1 }
                        .accessibilityIdentifier("development.bootstrap.retry")
                    LeziText("开发版本仅支持本机宝宝与无照片尿记录；尚未提供家庭同步。", role: .label)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: attempt) {
            state = .loading
            do {
                // SQLite initialization and directory I/O must not block the UI actor.
                let store = try await Task.detached(priority: .userInitiated) {
                    try DevelopmentStore.open()
                }.value
                try Task.checkCancellation()
                state = .ready(store)
            } catch is CancellationError {
                // A disappearing view must not publish a stale bootstrap result.
            } catch {
                // Do not log profile data, paths, or SQLite error payloads.
                state = .failed
            }
        }
    }
}

private enum DevelopmentStore {
    static func open() throws -> OfflineStore {
        let files = FileManager.default
        let support = try files.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = support.appendingPathComponent("LeziDevelopment", isDirectory: true)
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        // An independent native schema, never an Android database or family store.
        return try OfflineStore(path: directory.appendingPathComponent("offline-v1.sqlite").path)
    }
}
