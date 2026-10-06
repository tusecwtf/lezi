# 02: 缓动 token——LeziEasing 入场 decelerate / 出场 accelerate

**What to build:** `designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Tokens.kt` 在
`LeziMotion`（:196）旁新增 `LeziEasing` object，取值以 material-components-android
1.12.0 AAR 实际发货值为准（见 research.md §一，m3.material.io 早先数值与 AAR 有出入，
以 AAR 为准）：

- `EmphasizedDecelerate = CubicBezierEasing(0.1f, 0.7f, 0.1f, 1f)` —— 入场
- `EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.2f)` —— 出场

同步：`design/tokens.json` motion 节点补 easing；`MotionDensityTokensTest` 补 token 契约
用例；`docs/spec/contracts/ui.md` §2.1.1 增补缓动 token 政策（仍禁止第二套时长表）。
本票只加 token 不改调用点（调用点迁移随票 04，避免一次性大改）。

**Blocked by:** 01（同文件栈，避免冲突）

**Status:** done（0.5.4 票 14 收编，2026-09-13）

- [x] Tokens.kt 新增 LeziEasing（含 KDoc：值来源 material 1.12.0 AAR、入场/出场语义）
- [x] design/tokens.json motion 节点同步
- [x] MotionDensityTokensTest 补缓动契约用例
- [x] docs/spec/contracts/ui.md §2.1.1 补缓动政策

## Comments

- 2026-09-13（0.5.4 票 14）：`LeziEasing`（EmphasizedDecelerate `(0.1,0.7,0.1,1)` /
  EmphasizedAccelerate `(0.3,0,0.8,0.2)`，AAR 口径）入 `designsystem/Tokens.kt`；
  `design/tokens.json` `motion.easing` 同步；`MotionDensityTokensTest` 新增三组契约
  （曲线值 + tokens.json 快照 + 静态源码断言）；`ui.md` §2.1.1 补「缓动 token 只提供
  曲线、不携带时长」政策。调用点迁移随票 03/04 同 commit 落地。
