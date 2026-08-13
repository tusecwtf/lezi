# 2026-08-09 全库重复、残留与算法审查修复

Status: in-progress — ticket 01 implemented; remaining dependency graph active

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Source authority: `CONTEXT.md`、`docs/prd/`、`docs/adr/`、根 `AGENTS.md`，以及
[`post-0.3.13-review-remediation`](../post-0.3.13-review-remediation/) 的既有 ownership。

## Problem

固定 HEAD 的完整 JVM 与 lint、Rust test/Clippy 都通过，但仍存在测试未覆盖的规模、竞态和
协议失败面：因果客户端会在 65 个 root 时永久卡住并接受不完整 proof；服务器 causal validator
与既有 canonical validator 分叉；pull 在游标推进后才丢弃第 33 个 conflict sidecar；来源关系、
媒体 preimage 和 conflict branch 缺少 canonical/有界生命周期；疑似重复 bounds 会物化指数级
解释并在跨日 range 产生不存在的下界。其它模块还保留重复的 fulfillment settlement、宝宝排序、
成员 QR、取消 cause walker，以及无消费者 façade/model；相机临时文件没有 release ownership。

这些不是按文件大小或测试名推断的“坏味道”。每一票都绑定一个可观察失败、固定 HEAD 证据和
行为验收，并优先把规则放回一个 deep Module/Interface，而不是再新增 1:1 wrapper。

## Outcome

- 家庭因果同步在协议最大批次、并发本地编辑、错误/缺失响应、媒体与高 conflict cardinality 下
  都 fail closed、可重试且最终 drain，不清除尚未证明的本地事实。
- 服务器只接受能由当前客户端生成、能被 peer 有界 pull 的 canonical 实体；durable sidecar、
  source relation、preimage、branch 与 restore lock 都有明确 cursor、quota、TTL 或 pagination 合同。
- 疑似重复和 Wake 投影在跨日/DST、大数据量与单表变更下仍正确、可取消，并由批量原子读取 seam
  供 Timeline/Summary 复用。
- 重复写入规则收敛为共享事务命令；无消费者兼容层和测试专用生产 API 被删除；相机 capture
  具有显式 commit/release 生命周期。

## Audit boundary

- 审查源码只来自上述 commit。审查开始时 `.scratch/README.md` 与
  `.scratch/post-0.3.13-review-remediation/` 已是 dirty/untracked；审查期间又出现大量因果 UI、
  sync 和 Rust cleanup WIP。它们不作为“已实现”或 finding 证据，也不得被本 tracker 覆盖。
- 本 tracker 只新增 ticket 文档，不修改产品代码、不 build image/package、不连接或部署家庭 NAS。
- 每票开始前必须在一个新的 clean fixed HEAD 重新核对证据；dirty WIP 命中同一文件时先串行收口，
  不得为了让测试通过而丢弃用户改动。
- ticket 发布只是计划，不代表修复、设备验收或发版完成。

## 与 post-0.3.13 tracker 去重

| 已有 owner | 本轮发现的处置 |
|------------|----------------|
| `post.../01` | 继续独占 Room 26→27 dirty 保护、Wake/conflict/duplicate 产品 UI、真实双客户端主链、nullable stable projection 的产品结果；本 tracker 只补可独立测量的 settlement proof/批次、source graph、bounds 复杂度和批量读取 tracer。其验收不得绕过本 tracker 的 P1 gate。 |
| `post.../02` | 继续独占 B1、旧 open-sleep heal、Rust neighbor winner/tombstone、空 `neighbor_losers`、legacy 收缩和重复测试删除；本 tracker 不再创建同义票。 |
| `lossless-family-causal-sync/09` | 继续独占生产 NAS cutover、TLS/SPKI 前后比对和 joined-device production smoke。本 tracker 的 deploy helper 票只在隔离 `mktemp` fixture 验证。 |

## Non-goals

- 不按 LOC、package 数量或测试名相似度强制拆文件/删测试。
- 不改变 frozen causal wire、疑似重复领域定义、Owner/Member ACL 或计划/事实边界。
- 不引入产品无关 StructureTest、通用 Repository/Manager façade，或 feature→feature 依赖。
- 不把 cache/build/dist 等已 gitignore 机器产物当成源码 cleanup ticket。

## Acceptance policy

每票必须在其最终 fixed HEAD 上留下：实现 diff、针对失败机制的回归、相关 Android/Rust gate、
`git diff --check`，以及未运行的 device/isolated-service/NAS gate。涉及 `tools/lezi-sync/` runtime 的
票先跑 Rust gates；如需生产 CD，仍必须遵守根 `AGENTS.md` 的 propose-then-confirm，不能从本
tracker 的授权推断维护窗口。
