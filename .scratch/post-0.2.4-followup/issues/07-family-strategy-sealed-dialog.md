# 07 — 家庭 expand：UI 策略纯函数 + sealed 对话框模型

**Parent:** [../spec.md](../spec.md)

**What to build:** 在不大搬家的前提下，把家庭账户页的展示/可见性/成员名等策略收成可测纯函数，并把多布尔对话框控制收敛为互斥的 sealed 对话框状态（行为与 P1 结束后一致）。为后续文件拆分铺路，本身不追求五文件落地。

**Blocked by:** 05 — 网络保存 Result 化

**Status:** complete

## Acceptance criteria

- [x] 家庭 UI 策略（成员展示名、角色文案、主表面可见性等）可在无 Compose 下单测（接缝 S5）
- [x] 对话框以 sealed 状态互斥表达；用户可观察的打开/关闭/流程与改前等价
- [x] 行为冻结：不故意改产品文案语义（除已并入的 Result 路径）
- [x] P1 相关回归（保存 Result 路径、若已触及的清除入口）不被削弱
- [x] 本票不做完整五文件物理拆分、不删死路径的大搬迁（见 08）

## Comments

- R2：原 06 的 expand 半程；降低单 context 风险。
