# 0.3.13 审查修复与预切割闭环

Status: ready-for-agent

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Source authority: `CONTEXT.md`、`docs/prd/`、ADR-0019/0020/0021，以及
`lossless-family-causal-sync` 已冻结的因果 wire。本文不改变领域或 wire 决策，只修复固定
HEAD 审查发现的实现、产品接线、测试与发版证据缺口。

## Problem

0.3.13 tree 已包含因果版本、WakeObservation、非破坏性疑似重复和 LocalWrite no-pull，
但还不能作为完整交付接受：Room 26→27 后缺少因果标识的历史 dirty 根在第一次 full pull
存在被远端稳定投影覆盖的风险；Wake/conflict/duplicate/bounds 领域状态没有完整进入产品 UI；
退役的 B1、开放睡眠自动闭合、neighbor winner 与空 façade 仍保留；部分 feature 测试只重复
domain 投影，未证明真实 UI。当前签名 Release APK 与更新 metadata 的哈希也未形成同一份
可复验产物。

## Outcome

修复后，一个从 Room 26 升级且带有离线 dirty 事实的家庭可以安全进入因果协议：本机修改先
被保留、reconcile/commit 后在第二客户端可见或成为明确分支，pull 不会静默清除 pending。
时间轴和汇总完整呈现 WakeObservation、字段冲突、疑似重复来源与统计上下界，并提供受 ACL
和 CAS 约束的解决入口。退役路径及其重复测试被删除，固定 HEAD 的 Android、Rust、设备、
隔离双客户端、回滚和 Release APK/metadata 证据全部可复验。

## Ownership boundary

本 tracker 只拥有审查修复与生产切割前证据。家庭 NAS 的 stop/rm/replace、TLS/SPKI 前后
比对和 joined-device 生产 smoke 仍由现有 `lossless-family-causal-sync/09` 独占，且必须等待
用户再次明确确认维护窗口。本 tracker 不修改或关闭该票。
