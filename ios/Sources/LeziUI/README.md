# iOS presentation foundation

Additive library seams, not a runnable or production-ready iOS app. Android and the server remain unchanged.

- Portable palette, density, touch-target, font-override and motion policy mirror the consumed subset of Android `designsystem/Tokens.kt`, `Theme.kt` and `docs/spec/contracts/ui.md`.
- The five-tab container requires all five real feature views from its owner; it contains no fake records, sync status, timer, account or network flows.
- The confirmation panel accepts real editor content and a callback. Its immutable editor baby context and draft snapshot are passed together only at explicit confirmation. Local permission, external busy and validation gates are checked first. It does not save anything itself.
- The owning feature must transactionally check permissions and scope, provide actual persistence, report completion by attempt ID, invalidate stale sessions, and prevent replacing/dismissing an active save. This is not the Android durable command, process-death recovery, media ownership, scheduling or causal-sync implementation.
- Domain validation is a floor; feature-specific issues should include a field ID and the panel's focus callback must focus/highlight that real field. No field editor is fabricated here.
- Typography uses native Dynamic Type when elder mode is off. Elder levels replace the text scale with 1.5/1.8/2.1 for Lezi text components. Injected feature content must use the same policy; arbitrary third-party views cannot be promised to comply.

SwiftUI source is conditional on `canImport(SwiftUI)`. Linux policy tests do not compile, render or exercise SwiftUI. Apple SDK compilation, simulator/device screenshots, VoiceOver, large-text reflow, contrast on every real component, safe-area/keyboard behavior, theme transitions and motion/device parity remain acceptance work. Native tab-bar and SF Symbols rendering intentionally require Apple review; no Android glyph parity is claimed.
