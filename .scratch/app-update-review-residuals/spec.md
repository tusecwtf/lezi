# Spec · 自托管应用内更新 — 审查残差

**Status:** complete · 6/6  
**Parent:** [`.scratch/self-hosted-app-update/`](../self-hosted-app-update/spec.md)（complete · 5/5）  
**Review:** parallel review of commits `04f7282..47828af` (2026-07-31)  
**Merged notes:** session review `grok-review-27b5de6e-merged.md`（correctness / security / tests / plan）

## Problem Statement

应用内更新主路径已合入，但并行审查发现若干会误导用户或在错误配置下卡死强制升级的问题：安装失败污染同步状态、暂存竞态、minSupported 与 versionCode 不一致、强制态兜底缺失、装前未校验 APK 身份等。需要按垂直切片修掉 P0/P1，而不是再开横向「只补测试」层。

## Solution

在现有 `SyncPort` / lezi-sync / package-nas 上修复审查问题；每票交付可独立验证的用户可见或门闩行为，并补对应外部行为测试。

## Locked product constraints (unchanged)

- 自托管 PackageInstaller；仅已加入会话；双档 minSupported  
- 更新失败 **不得** 伪装成家庭同步/NAS 故障  
- 私有 cache 暂存；无公共 Download  
- minSupported 对诚实客户端生效；**不**把 version 头当成不可伪造的安全根（B7 → 文档诚实）

## Tickets

见 [ISSUES.md](./ISSUES.md)。

## Out of scope

- Play In-App Updates、FCM、差分包、静默安装  
- 全 OEM PackageInstaller 自动化矩阵  
- 未加入设备升级通道  
