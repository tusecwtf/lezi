import XCTest
@testable import LeziUI

final class LeziTokensTests: XCTestCase {
    func testFourPalettesMatchAndroidGrounds() {
        XCTAssertEqual(LeziPalette.resolve(style: .warm, dark: false).background.hex, 0xFBF7EE)
        XCTAssertEqual(LeziPalette.resolve(style: .warm, dark: true).surface.hex, 0x15202C)
        XCTAssertEqual(LeziPalette.resolve(style: .journal, dark: false).background.hex, 0xF4F3F5)
        XCTAssertEqual(LeziPalette.resolve(style: .journal, dark: true).surface.hex, 0x262727)
        for style in LeziVisualStyle.allCases {
            for dark in [false, true] {
                let palette = LeziPalette.resolve(style: style, dark: dark, elder: .l3)
                for ground in [palette.background, palette.surface] {
                    XCTAssertGreaterThanOrEqual(palette.foreground.contrast(with: ground), 4.5)
                    XCTAssertGreaterThanOrEqual(palette.muted.contrast(with: ground), 4.5)
                }
                XCTAssertGreaterThanOrEqual(palette.primary.contrast(with: palette.onPrimary), 4.5)
            }
        }
    }
    func testBabyAccentOverridesBothTemplatesAndModes() {
        let baby = LeziRGB(0xFF8800)
        for style in LeziVisualStyle.allCases {
            for dark in [false, true] {
                XCTAssertEqual(LeziPalette.resolve(style: style, dark: dark, babyAccent: baby).primary, baby.normalizedBabyAccent)
            }
        }
        XCTAssertEqual(LeziRGB(0xFF0000).normalizedBabyAccent.hex, 0xD32222)
    }
    func testDensityTouchAndFontOverrides() {
        XCTAssertEqual(LeziThemeConfiguration(style: .warm).density.cardPad, 16)
        XCTAssertEqual(LeziThemeConfiguration(style: .journal).density.cardPad, 12)
        XCTAssertEqual(LeziThemeConfiguration(style: .journal).density.dockOuterHorizontal, 0)
        for style in LeziVisualStyle.allCases {
            for elder in [LeziElderMode.l1, .l2, .l3] {
                let config = LeziThemeConfiguration(style: style, elder: elder)
                XCTAssertEqual(config.density.cardPad, 20)
                XCTAssertEqual(config.primaryTouchMinimum, 60)
                XCTAssertEqual(config.pageInset, 16)
            }
        }
        XCTAssertNil(LeziElderMode.off.fontScaleOverride)
        XCTAssertEqual(LeziElderMode.l1.fontScaleOverride, 1.5)
        XCTAssertEqual(LeziElderMode.l2.fontScaleOverride, 1.8)
        XCTAssertEqual(LeziElderMode.l3.fontScaleOverride, 2.1)
    }
    func testMotionAndNavigationContract() {
        XCTAssertEqual(LeziMotion.allCases.map { $0.seconds(reduceMotion: false) }, [0.15, 0.2, 0.3])
        XCTAssertTrue(LeziMotion.allCases.allSatisfy { $0.seconds(reduceMotion: true) == 0 })
        XCTAssertEqual(LeziDestination.allCases.map(\.rawValue), ["log", "summary", "growth", "family", "settings"])
        XCTAssertEqual(LeziDestination.allCases.map(\.title), ["记录", "汇总", "成长", "账户", "菜单"])
    }
}
