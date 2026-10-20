import Foundation

public enum LeziVisualStyle: String, CaseIterable, Sendable { case warm, journal }
public enum LeziElderMode: String, CaseIterable, Sendable {
    case off, l1, l2, l3
    public var enabled: Bool { self != .off }
    /// Replaces, never multiplies, the system font scale. Off leaves Dynamic Type intact.
    public var fontScaleOverride: Double? {
        switch self { case .off: nil; case .l1: 1.5; case .l2: 1.8; case .l3: 2.1 }
    }
}

public struct LeziDensity: Equatable, Sendable {
    public let cardPad, topBarHorizontal, sectionGap, panelContent, dockOuterHorizontal: Double
    public static func resolve(style: LeziVisualStyle, elder: LeziElderMode) -> Self {
        if elder.enabled { return Self(cardPad: 20, topBarHorizontal: 16, sectionGap: 20, panelContent: 20, dockOuterHorizontal: 12) }
        switch style {
        case .warm: return Self(cardPad: 16, topBarHorizontal: 12, sectionGap: 16, panelContent: 16, dockOuterHorizontal: 12)
        case .journal: return Self(cardPad: 12, topBarHorizontal: 8, sectionGap: 8, panelContent: 8, dockOuterHorizontal: 0)
        }
    }
}

public enum LeziMotion: Int, CaseIterable, Sendable {
    case fast = 150, base = 200, emphasized = 300
    public func seconds(reduceMotion: Bool) -> Double { reduceMotion ? 0 : Double(rawValue) / 1000 }
}

/// sRGB value independent of SwiftUI, so source-token and contrast tests run portably.
public struct LeziRGB: Equatable, Sendable {
    public let hex: UInt32
    public init(_ hex: UInt32) { self.hex = hex & 0xFFFFFF }
    public var red: Double { Double((hex >> 16) & 255) / 255 }
    public var green: Double { Double((hex >> 8) & 255) / 255 }
    public var blue: Double { Double(hex & 255) / 255 }
    public var luminance: Double {
        func linear(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }
    public func contrast(with other: Self) -> Double {
        (max(luminance, other.luminance) + 0.05) / (min(luminance, other.luminance) + 0.05)
    }
    public var readableContent: Self {
        let black = Self(0), white = Self(0xFFFFFF)
        return contrast(with: black) >= contrast(with: white) ? black : white
    }
    /// Android Theme.kt: fixed HSL lightness .48, saturation clamped to .32 ... .72.
    public var normalizedBabyAccent: Self {
        let high = max(red, green, blue), low = min(red, green, blue), delta = high - low
        let lightness = (high + low) / 2
        var hue = 0.0
        var saturation = 0.0
        if delta > 0.000001 {
            saturation = lightness > 0.5 ? delta / (2 - high - low) : delta / (high + low)
            if high == red { hue = (green - blue) / delta + (green < blue ? 6 : 0) }
            else if high == green { hue = (blue - red) / delta + 2 }
            else { hue = (red - green) / delta + 4 }
            hue /= 6
        }
        saturation = min(0.72, max(0.32, saturation))
        let q = 0.48 * (1 + saturation), p = 0.96 - q
        func channel(_ input: Double) -> UInt32 {
            var t = input
            if t < 0 { t += 1 }; if t > 1 { t -= 1 }
            let value: Double
            if t < 1 / 6 { value = p + (q - p) * 6 * t }
            else if t < 1 / 2 { value = q }
            else if t < 2 / 3 { value = p + (q - p) * (2 / 3 - t) * 6 }
            else { value = p }
            return UInt32((value * 255).rounded())
        }
        return Self(channel(hue + 1 / 3) << 16 | channel(hue) << 8 | channel(hue - 1 / 3))
    }
}

/// Subset consumed by the foundation; copied from Android Tokens.kt / Theme.kt.
/// No claim that the entire Android component catalog has been ported.
public struct LeziPalette: Equatable, Sendable {
    public let background, surface, foreground, muted, border, primary, danger: LeziRGB
    public let containerLow, container, containerHigh: LeziRGB
    public var onPrimary: LeziRGB { primary.readableContent }
    public static func resolve(style: LeziVisualStyle, dark: Bool, babyAccent: LeziRGB? = nil, elder: LeziElderMode = .off) -> Self {
        let values: [UInt32]
        switch (style, dark) {
        case (.warm, false): values = [0xFBF7EE, 0xFEFCF9, 0x1E2C3C, 0x526171, 0xCCDBE9, 0x007BAE, 0xC0392B, 0xF8F3E9, 0xF4EDDE, 0xEFE7D4]
        case (.warm, true): values = [0x0E1721, 0x15202C, 0xEDE7DB, 0xA8B3BF, 0x3A4A5A, 0x60B3DC, 0xFF897E, 0x19242F, 0x1D2A36, 0x232F3C]
        case (.journal, false): values = [0xF4F3F5, 0xF9F7F8, 0x36363A, 0x69666C, 0xD7D4D6, 0xB7445A, 0xC0392B, 0xF3F2F4, 0xEDECEF, 0xE6E4E9]
        case (.journal, true): values = [0x202121, 0x262727, 0xF1EFF0, 0xAAA5A7, 0x4C4D4D, 0xEC7887, 0xFF897E, 0x2A2B2B, 0x2E2F30, 0x343638]
        }
        let c = values.map(LeziRGB.init)
        // These auxiliary colors already meet 4.5:1 on the foundation's two text grounds.
        // Fail safe to foreground if future palette edits lower that floor in elder mode.
        let muted = elder.enabled && min(c[3].contrast(with: c[0]), c[3].contrast(with: c[1])) < 4.5 ? c[2] : c[3]
        return Self(background: c[0], surface: c[1], foreground: c[2], muted: muted, border: c[4], primary: babyAccent?.normalizedBabyAccent ?? c[5], danger: c[6], containerLow: c[7], container: c[8], containerHigh: c[9])
    }
}

public struct LeziThemeConfiguration: Equatable, Sendable {
    public var style: LeziVisualStyle
    public var elder: LeziElderMode
    public var babyAccent: LeziRGB?
    public init(style: LeziVisualStyle = .warm, elder: LeziElderMode = .off, babyAccent: LeziRGB? = nil) {
        self.style = style; self.elder = elder; self.babyAccent = babyAccent
    }
    public var density: LeziDensity { .resolve(style: style, elder: elder) }
    public var primaryTouchMinimum: Double { elder.enabled ? 60 : 48 }
    public var cardRadius: Double { style == .warm ? 8 : 0 }
    public var dialogRadius: Double { style == .warm ? 8 : 18 }
    public var buttonRadius: Double { 8 }
    public var pageInset: Double { 16 }
}
