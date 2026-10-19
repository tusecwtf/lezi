#if canImport(SwiftUI)
import SwiftUI

extension LeziRGB {
    public var color: Color { Color(.sRGB, red: red, green: green, blue: blue, opacity: 1) }
}

private struct LeziThemeKey: EnvironmentKey {
    static let defaultValue = LeziThemeConfiguration()
}
extension EnvironmentValues {
    public var leziTheme: LeziThemeConfiguration {
        get { self[LeziThemeKey.self] }
        set { self[LeziThemeKey.self] = newValue }
    }
}

/// Wrap the actual app content; preferences/storage stay with the owner.
public struct LeziTheme<Content: View>: View {
    private let configuration: LeziThemeConfiguration
    private let preferredScheme: ColorScheme?
    private let content: Content
    public init(configuration: LeziThemeConfiguration = .init(), preferredScheme: ColorScheme? = nil,
                @ViewBuilder content: () -> Content) {
        self.configuration = configuration; self.preferredScheme = preferredScheme; self.content = content()
    }
    public var body: some View {
        ThemeSurface(content: content)
            .environment(\.leziTheme, configuration)
            .preferredColorScheme(preferredScheme)
    }
}

private struct ThemeSurface<Content: View>: View {
    @Environment(\.leziTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    let content: Content
    var body: some View {
        let palette = LeziPalette.resolve(style: theme.style, dark: colorScheme == .dark,
                                          babyAccent: theme.babyAccent, elder: theme.elder)
        content
            .foregroundStyle(palette.foreground.color)
            .tint(palette.primary.color)
            .background(palette.background.color.ignoresSafeArea())
    }
}

public enum LeziTextRole: Sendable {
    case title, titleSmall, body, label, metric
    fileprivate var size: Double {
        switch self { case .title: 20; case .titleSmall: 17; case .body: 16; case .label: 13; case .metric: 22 }
    }
    fileprivate var relativeTo: Font.TextStyle {
        switch self { case .title: .title2; case .titleSmall: .headline; case .body: .body; case .label: .caption; case .metric: .title2 }
    }
}

/// Native Dynamic Type unless an explicit elder scale replaces it. No line limits or shrinking.
public struct LeziText: View {
    @Environment(\.leziTheme) private var theme
    @ScaledMetric private var scaledSize: Double
    private let text: String
    private let role: LeziTextRole
    public init(_ text: String, role: LeziTextRole = .body) {
        self.text = text; self.role = role
        _scaledSize = ScaledMetric(wrappedValue: role.size, relativeTo: role.relativeTo)
    }
    public var body: some View {
        let size = theme.elder.fontScaleOverride.map { role.size * $0 } ?? scaledSize
        Text(text)
            .font(.system(size: size, weight: role == .body ? .regular : .semibold,
                          design: role == .metric ? .monospaced : (role == .title && theme.style == .warm && !theme.elder.enabled ? .serif : .default)))
            .lineSpacing(theme.elder.enabled ? size * 0.25 : 0)
            .fixedSize(horizontal: false, vertical: true)
    }
}

extension LeziMotion {
    /// Nonessential SwiftUI animations share Android duration and easing vocabulary.
    public func animation(reduceMotion: Bool, entering: Bool = true) -> Animation? {
        guard !reduceMotion else { return nil }
        return entering
            ? .timingCurve(0.1, 0.7, 0.1, 1, duration: seconds(reduceMotion: false))
            : .timingCurve(0.3, 0, 0.8, 0.2, duration: seconds(reduceMotion: false))
    }
}
#endif
