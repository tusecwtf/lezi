# 11 · Design-system consistency sweep

Status: complete — accepted on `6b278242`

## Findings

1. **A1 同页两种格式**:`feature/family/.../overview/FamilyOverview.kt:95-104`(ISO 日期 +
   `3.20kg`)vs 184-188(`yyyy年M月d日` + `3200g`),同一页同一宝宝两种生日/体重格式。
2. **A2 typography 掉队**:`designsystem/.../Tokens.kt:234-245` `LeziTypography.material()`
   未映射 `headlineSmall`/`titleSmall` → M3 默认 24sp/14sp 不在 tokens.json ramp(28/20/16/13/12)。
   受影响:`OnboardingScreen.kt:264`、`LocalDataRecoveryScreen.kt:98`、
   `RecordComposerDiscardDialog.kt:76`、`WidgetConfigurationActivity.kt:183`、
   `designsystem/.../ClockDial.kt:573`。
3. **A3 手写弹窗**:`feature/log/.../RecordComposerDiscardDialog.kt:44-101` 自绘 scrim+Surface,
   其余确认弹窗均用 `AlertDialog`。
4. **A4 主 CTA**:`feature/timer/.../TimerScreen.kt:238-248`、
   `feature/widget/.../WidgetConfigurationActivity.kt:231-237` 用裸 `Button`,
   应为 `LeziPrimaryButton`。
5. **A5 硬编码色**:`feature/log/.../QuickRecordPurposeFields.kt:578` `Color(0xFFF5C451)`,
   就近 token(如 `LeziColors.SleepSun`)。
6. **A7 时长三种格式**:`LogTimelineList.kt:618-623` `"2h 10m"` /
   `SummaryScreen.kt:1133-1138` `"2h10m"` / `RecordSummary.kt:198` `"2小时10分"`;
   统一为 `"2h10m"`(与 designsystem preview 一致)。
7. **A8 真实 NAS 地址占位符**:`feature/family/.../FamilyNetworkSettingsScreen.kt:117`
   `https://192.168.50.4:8765` → 通用示例。
8. **A6 死代码**:`feature/widget/.../WidgetScreen.kt` 无调用方 → 删除(连同孤立路由)。

## Fix

- [x] 逐项对齐;格式统一走现有 seam(`CareLogPresentation`/token),不新增平行工具函数。

## Validation

- [x] `./gradlew :app:assembleDebug lintDebug`;受影响页面 preview/截图核对。
