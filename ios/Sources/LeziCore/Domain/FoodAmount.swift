import Foundation

/// Source: FoodAmount.kt. Amount remains free-form text, with no unit conversion.
public enum FoodAmount {
    private static let hanzi: [Character: String] = ["半": "0.5", "一": "1", "两": "2", "二": "2", "三": "3", "四": "4", "五": "5", "六": "6", "七": "7", "八": "8", "九": "9", "十": "10"]
    public static func normalize(_ raw: String) -> String {
        let text = String(String.UnicodeScalarView(raw.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.map { scalar in
            switch scalar.value {
            case 0xFF10...0xFF19: UnicodeScalar(scalar.value - 0xFF10 + 0x30)!
            case 0xFF0E, 0x3002: UnicodeScalar(0x2E)!
            case 0xFF0C: UnicodeScalar(0x2C)!
            default: scalar
            }
        }))
        guard let head = text.first, let number = hanzi[head] else { return text }
        let rest = text.dropFirst()
        if rest.unicodeScalars.first.map({ $0.value <= 0xFFFF && $0.properties.generalCategory == .decimalNumber }) == true || rest.contains(where: { hanzi[$0] != nil || $0 == "几" }) { return text }
        return number + rest
    }
    public static func parseValue(_ amount: String?) -> Double? {
        guard let text = amount?.trimmingCharacters(in: .whitespacesAndNewlines),
              let match = text.range(of: #"^([0-9]+(?:\.[0-9]+)?)"#, options: .regularExpression) else { return nil }
        if match.upperBound < text.endIndex, [".", ","].contains(text[match.upperBound]) { return nil }
        guard let value = Double(text[match]), value.isFinite, value <= 1_000_000 else { return nil }
        return value
    }
}
