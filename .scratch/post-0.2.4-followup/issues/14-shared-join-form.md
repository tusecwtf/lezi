# 14 — 引导与家庭共用加入表单/用例

**Parent:** [../spec.md](../spec.md)

**What to build:** 首次引导与账户家庭页共用同一套加入家网草稿状态与提交用例（基于 13 的命令 + 12 的配置真源）。删除双份 host/port/ssid/scheme 草稿逻辑。扫码预填只写共享草稿。建家 UI 侧 bootstrap 非空与 Port 一致。

**Blocked by:** 08 — 家庭拆分；12 — endpoint 真源；13 — Join 命令 Port

**Status:** complete

## Acceptance criteria

- [x] onboarding 与家庭加入走同一用例/表单状态，无双份草稿实现
- [x] 扫码预填只影响共享草稿，不在 Port 内另起优先级
- [x] 建家 UI 拒绝空 bootstrap，与 13 一致
- [x] 两入口错误文案策略一致（或明确单一 productUiError 策略）
- [x] 手动/既有 suite：双入口关键路径不回归

## Comments

- R2：原 10 的 UI 半程。
