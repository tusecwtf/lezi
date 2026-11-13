#if canImport(SwiftUI)
import SwiftUI

/// Navigation chrome only. Every destination must be supplied by a real feature owner.
/// Feature navigation stacks and editor/state ownership are deliberately not duplicated here.
public struct LeziRootContainer<Log: View, Summary: View, Growth: View, Family: View, Settings: View>: View {
    @Binding private var selection: LeziDestination
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let log: Log
    private let summary: Summary
    private let growth: Growth
    private let family: Family
    private let settings: Settings
    public init(selection: Binding<LeziDestination>, @ViewBuilder log: () -> Log,
                @ViewBuilder summary: () -> Summary, @ViewBuilder growth: () -> Growth,
                @ViewBuilder family: () -> Family, @ViewBuilder settings: () -> Settings) {
        _selection = selection
        self.log = log(); self.summary = summary(); self.growth = growth()
        self.family = family(); self.settings = settings()
    }
    public var body: some View {
        TabView(selection: $selection) {
            log.tabItem { tabLabel(.log) }.tag(LeziDestination.log)
            summary.tabItem { tabLabel(.summary) }.tag(LeziDestination.summary)
            growth.tabItem { tabLabel(.growth) }.tag(LeziDestination.growth)
            family.tabItem { tabLabel(.family) }.tag(LeziDestination.family)
            settings.tabItem { tabLabel(.settings) }.tag(LeziDestination.settings)
        }
        .transaction { transaction in
            if reduceMotion { transaction.animation = nil; transaction.disablesAnimations = true }
        }
    }
    private func tabLabel(_ destination: LeziDestination) -> some View {
        Label(destination.title, systemImage: destination.systemImage)
    }
}
#endif
