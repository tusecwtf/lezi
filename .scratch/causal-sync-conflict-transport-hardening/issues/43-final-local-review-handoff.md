# 43 — 完成本地复审与发版移交

**What to build:** 在固定最终 HEAD 重跑必要 gates，做 Standards/Spec 双轴复审，并把 H01–H42 与 external R12/R17/R18/R19 的可复验证据移交 0.4.0 release 09。

**Blocked by:** 32、33、34、35、36、37、38、39、40、41、42

**Status:** ready-for-agent

## Contract slice

本票只复审/汇总。新 P0/P1 必须另建 blocker 并保持本票未完成，不在此无限扩张修复；生产部署仍需 release 09 与新维护窗口确认。

## Implementation sequence

1. 固定 HEAD/status/version/schema/capability 并重跑最终 gates。
2. 分别复审 repo standards 与 spec compliance。
3. 核对 H01–H42 和 R12/R17/R18/R19 receipts/residuals。
4. 形成 release 09 的证据、风险与 unrun-gate 清单。

## Acceptance

- [ ] 所有 Must 有固定 HEAD 的代码/测试/设备或 isolated evidence
- [ ] P0/P1 为零，或已建 blocker 且本票未关闭
- [ ] 报告区分 committed code、unrelated WIP、local evidence、unrun NAS
- [ ] 未宣称 CD/发布成功

## Validation

- [ ] Android/Rust/release metadata/local E2E gates 按最终 tree 重跑
- [ ] tracker links/status/frontier/handoff 一致

## Out of scope

不构建 image、不打包/推送、不 stop/rm/replace 家庭 NAS。
