# 10 — Shell motion + reduce-motion

**What to build:** Root navigation, top/bottom bar, layout-edit, and onboarding
step transitions use shared motion tokens. When the system reduce-motion
accessibility setting is on, non-essential transitions shorten or become
instant while state changes stay understandable.

**Blocked by:** 01 — Expand motion + density token tables; 05 — Contract ban bare Material.

**Status:** done

## Public seams (self-confirmed)

1. **`LeziMotion.nonEssentialMillis(tokenMs, motionDurationScale)`** — pure JVM
   policy: scale ≤ 0 → 0 ms; otherwise token unchanged (no pre-multiply).
2. **`leziMotionMillis(tokenMs)`** — Compose helper snapshots
   `Settings.Global.ANIMATOR_DURATION_SCALE` (no public `LocalMotionDurationScale`
   on this BOM); capture once and close over in non-@Composable
   `transitionSpec` / NavHost enter/exit lambdas.
3. **Shell sources** — `MainActivity` (root gate, top/bottom bar, NavHost),
   `AppHeader` (top-bar sleep/calendar), `OnboardingScreen` step
   `AnimatedContent`, `LogScreen` layout-edit `AnimatedContent`,
   `layoutDragFeedbackDurationMillis` → `LeziMotion.Fast`.
4. **Regression contracts kept** — onboarding multi-root `Column`+`spacedBy`;
   bottom-nav `onClick = { navigateToDestination() }` owner intact.

## Acceptance

- [x] Main shell transitions reference motion tokens (no scattered magic durations on those paths)
- [x] Reduce-motion shortens/disables non-essential motion
- [x] Layout-edit and onboarding multi-root layout regressions do not return
- [x] Bottom-nav short-press navigation still works (onClick owner intact)
- [x] `./gradlew :app:assembleDebug` + relevant tests green
