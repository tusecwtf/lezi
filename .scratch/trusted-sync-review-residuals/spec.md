# Spec · Trusted-sync 实现后代码审查残差

**Status:** complete

**Source:** 2026-07-31 多 agent 并行 code review（安全 TLS/鉴权、Android sync、
Rust server、Domain/UI）+ 主审核对

**Audit range:** `e19c385` … `5f9aa3c`（产品固定点常见于 `a4dbe07` / `5f9aa3c`）

**Implemented from:** `47828af072b1e85e47b91bf6c9533feffa94766c`（2026-07-31）

**Acceptance:** 13/13 Must complete；最终固定点证据见
[evidence/final/validation.md](./evidence/final/validation.md)。Ticket 12 的可选浅状态
Should 未扩入本次安全/正确性收口。

**Parent program:** [trusted-sync-endpoint-auth](../trusted-sync-endpoint-auth/spec.md)
（01–15 实现 complete；16–17 发布验收仍 open）

**Full findings archive:** [REVIEW.md](./REVIEW.md)

## Problem statement

可信同步 cutover（HTTPS + TOFU/SPKI、设备会话、根密码 Owner、成员多设备、退役
SSID/邀请）主路径已落地并通过大量 JVM/API/设备证据。独立审查在**当前实现 HEAD**
上确认一批**新残差**：

1. 向导/账户 UI 状态机可被 pending restore 覆盖（Critical）。
2. 根密码在线猜解无失败限流，create 成为口令 oracle（High，VPS 场景更重）。
3. 同家庭 reauth 收据保护只修了 Owner `persistJoin`，Member claim/QR 与
   `isJoined` 收据门闩仍会清 receipt / 标脏（High；320ac7d/4ff0266 不完整）。
4. 探测后仍可改 host、trust 在 probe 成功前 remember、QR 校验不可取消等合同缺口。
5. 服务端 create 多设备幂等、claim 非 lost-response 幂等、内部明文全 API 口等。

本 tracker **不重开** trusted-sync 01–15，也不替代 16 跨端验收 / 17 发版。残差以
独立票、独立验收向前收敛；与 16 的关系：

- **阻断 0.3.1 用户安全或数据正确性的 P0/P1** 应在 17 前修完并反映到新候选 HEAD。
- **纯文档 / 低优先 polish** 可与 16 并行或记入 accepted residual，不阻塞发版。
- 是否阻塞 17 以本 tracker ISSUES 中各票 `Blocks release` 字段为准。

2026-07-31 在当前 committed HEAD 复核后，01–13 均未被后续实现关闭。`5f9aa3c`
之后的跨端验收/最终 APK 证据证明的是 parent ticket 16 的已执行矩阵，不等价于修复
本 tracker 的代码残差；后续 self-hosted app-update 改动也与这些路径正交。

## Locked decisions

1. 传输/会话主合同（HTTPS、TOFU、设备 session、Owner 根密码）已由 parent tracker
   锁定；本包只修审查确认的实现缺口与合同回归。
2. **产品接受的残余风险**（首连 TOFU MITM、QR 肩窥、离线撤销延迟、LAN 暴露 8765）
   不单独立实现票；见 REVIEW.md「Accepted residual risk」。
3. 根密码限流与去 oracle **计入发版前安全门**（VPS/可达 NAS 威胁模型）。
4. Member 同家庭 reauth 必须与 Owner 对齐；不得只修 Owner 路径。
5. Domain/UI 双路径（探测后改 host、pending 强制 restore）按合同收敛，不保留
   「下层 fail-closed 即可」作为完成条件。
6. 与 [p1-review-residuals](../p1-review-residuals/spec.md) 05–08 的关系：那些是
   **0.3.0 时代** 网络层复核票；本 tracker 是 **trusted-sync 实现后** 新发现。
   0.3.1 后对 05–08 复核时，应交叉对照本 tracker 已修项。

## Delivery shape

| 审查项 | Severity | Ticket | Blocks 0.3.1 |
|--------|----------|--------|--------------|
| Pending restore 覆盖任意向导状态 | Critical | 01 | yes |
| 根密码无限流 + create oracle | High | 02 | yes |
| Member reauth 收据 / isJoined 门闩 | High | 03 | yes |
| 探测后改 host / 冻结 origin | High | 04 | yes |
| trust 提前 remember | High | 05 | yes |
| QR 校验不可取消 + dataRecovery 丢失 | High | 06 | yes |
| Claim 先 save 后 reset 排序 | Medium | 07 | preferred |
| 内部明文口挂全 API | Medium | 08 | preferred |
| Create 多设备幂等 + claim 幂等 | Medium | 09 | no* |
| 秘密 toString / CT 比较 | Medium | 10 | no |
| Terminal clear mutex / saveSession 顺序 | Medium | 11 | preferred |
| UI 静默失败 / 删家空名 / 文案 | Medium–Low | 12 | no |
| 文档漂移（SECURITY.md / AGENTS health） | Low | 13 | no |

\* 09 在 flaky LAN 下影响 claim 重试体验；不阻塞最低 0.3.1 若 16 矩阵已覆盖主路径，
但应在 0.3.1 后尽快修。

## Implementation order

```text
Immediate frontier (parallel-safe where noted):
  01 UI pending restore          ──┐
  02 server root rate-limit      ──┤
  03 member reauth receipts      ──┼──► release candidate HEAD
  04 freeze origin after probe   ──┤
  05 trust remember only Ready   ──┤
  06 QR cancel + dataRecovery    ──┘

Preferred before 17 (may parallel after 01–06 start):
  07 claim ordering
  08 internal health-only router
  11 terminal clear / saveSession

Can ship after 0.3.1 if capacity tight:
  09 create/claim idempotency polish
  10 redaction + CT compare
  12 UI polish
  13 docs
```

- **01–06** 为 immediate frontier，可并行（文件面：01/04/05/06 偏 UI+domain；
  02 偏 server；03 偏 sync coordinator + ReplicaSyncEngine）。
- **04 与 05** 同属 endpoint trust 合同，建议同一 agent 或串行，避免冲突
  `RealSyncPort` / wizard submit。
- **07** 依赖 03 的 identity 语义（claim 路径），建议 03 后做。
- 关闭票须有 RED→GREEN 测试或可复核设备证据；禁止仅改注释关票。

## Global acceptance gates

- 每张票：复现审查路径的测试或对照证据；相关模块单测；触及 app 时
  `./gradlew :app:assembleDebug`；触及 server 时 `cargo test --locked` + clippy。
- 不回退 HTTPS-only、不恢复 SSID/邀请/长期 family Bearer。
- 不把「产品接受的 TOFU 风险」改写成已消除。
- 关闭 01–06 后更新 parent tracker 或 release 证据，标明新候选 HEAD。

## Out of scope

- 重做 trusted-sync 01–15 功能范围。
- App 端 NAS→VPS 迁移协议。
- 将根密码改为 argon2 用户口令产品模型（可记方向，本包只做限流+去 oracle+CT）。
- p1-review-residuals 01–04 domain/composer 即时修。
- 自托管应用内更新（self-hosted-app-update）。
