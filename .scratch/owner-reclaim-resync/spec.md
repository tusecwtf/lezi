# Spec: 管理员卸载后接回家庭并全量重同步

Status: complete
Feature: owner-reclaim-resync
Product: 乐记 (`com.lezi.babylog`)
Source: grill → ticket 2026-07-29
Related:

- `docs/prd/sync-home-lan.md` §4 身份与鉴权、§5 同步域、§9.2 create、§9.7 pull
- `docs/adr/0007-separate-family-membership-from-credentials.md`（superseded 历史；运行期不按 device 合并）
- `docs/adr/0008-support-only-fresh-current-product-contracts.md`
- `tools/lezi-sync/README.md`（`LEZI_BOOTSTRAP_SECRET`）
- `sync/.../FamilySessionCoordinator.kt`（create 路径）
- `tools/lezi-sync/src/store.rs`（`create_family` → `FamilyAlreadyExists`）

---

## Problem Statement

一家一栈的 NAS 上已有家庭数据后，管理员卸载 APK 会清掉本机会话（`token`、
`deviceId`、`create_request_id`、`pullCursor` 等）。重装并再次「创建家庭」时，
服务端返回 **`409 Family already exists`**，无法再拿到 owner 凭证，因而也
**无法 pull 回已按 `client_uuid` 存在的家庭数据**。

缺口不在「实体没有 UUID」——NAS 实体主键已是
`(family_id, entity_type, client_uuid)`，pull 幂等 upsert 已存在。缺口是：
**卸载后如何重新获得同一 owner `membership_id` 的会话，并走标准全量同步。**

成员可用邀请码再加入（新 `membership_id`）；owner 不能 leave，也不能在已有
家庭上再 create——形成管理员专用死胡同。

---

## Confirmed decisions（grill 锁定）

| # | 决策 |
|---|------|
| 1 | 证明：部署级 **bootstrap**（`LEZI_BOOTSTRAP_SECRET` / 请求头）；**库内不存口令** |
| 2 | 身份：接回 **同一 owner `membership_id`**，不新建 owner membership |
| 3 | 数据：reclaim 后走现有 **full-resync / pull**，按 `client_uuid` 合入；不新做导入通道 |
| 4 | 入口：同一 **「创建家庭」** 动作；已有家庭且校验通过 → 自动 reclaim（无独立「恢复」入口） |
| 5 | 称呼：更新当前 owner `display_name`；**家庭名仅非空覆盖**，空则保留 NAS |
| 6 | 凭证：吊销该 membership 下 **全部** 未吊销 credentials，只发新 token；更新 `device_id` |
| 7 | 本机已有数据：无专用合并，走既有 LWW / uuid / 权威宝宝规则 |
| 8 | 语义：reclaim = owner **会话迁到本机**；其它持旧 token 的设备下次 401 |
| 9 | 范围：**仅 owner**；成员仍邀请码重加（新 membership） |
| 10 | **未配置** bootstrap 时：与开放 create 对称，**LAN 内也可 reclaim**（开发便利；生产 Compose 仍强制 secret） |

### Bootstrap 产品事实（写进票，避免误实现）

- **设置处**：NAS / Docker 环境变量 `LEZI_BOOTSTRAP_SECRET`，不是 APK 内置口令。
- **APK**：仅在「创建家庭」表单填「服务器初始化口令」，放入
  `X-Lezi-Bootstrap-Secret`，**不**持久化到 session / DataStore / 邀请载荷。
- **日常同步**：只用 family Bearer token，不再要 bootstrap。
- **卸载后**：丢的是 token；运维仍持有部署 secret 即可再填一次接回。

---

## Solution

**扩展 `POST /v1/family/create`（及 Android 同一 create 用例）：**

1. 空库 / 无家庭：行为与今相同 → 建家，返回新 `family_id` / owner `membership_id` / token。
2. 已有家庭 + bootstrap 校验通过（或未配置 bootstrap 的 dev 开放模式）：
   - 定位当前唯一家庭的 **active owner membership**
   - 吊销其全部 credentials
   - 将 `device_id` 更新为请求中的新设备 id
   - 按规则更新 `display_name`；`family_name` 仅非空时覆盖
   - 签发新 owner token（仍绑定同一 `membership_id`）
   - 响应形状与成功 create 对齐：`family_id`、`token`、`role: owner`、
     `membership_id`、`generation`、`family_name`
3. 已有家庭 + bootstrap **错误**：401/403（与今建家失败一致），**不**暗示「可抢权」。
4. 客户端成功落盘会话后：`cursor` 从 0 / 按 generation 走现有 full-resync，
   pull 全部分页与媒体；实体以 `client_uuid` 幂等写入本机。

用户可感知结果：

1. 管理员重装后仍用「新建家庭」+ 同一服务器初始化口令即可接回。
2. 接回后前台/下拉同步能看到 NAS 上原有宝宝、记录、计划、媒体等。
3. 历史记录作者仍是原管理员（同一 `membership_id`），不是「另一个家人」。
4. 轻提示可区分「已创建家庭」与「已接回家庭，正在同步」。

---

## Non-goals

- 成员用 bootstrap 接回原 membership
- 运行期按 `device_id` 自动合并 membership（违背 ADR 方向）
- 独立「恢复管理员」入口或二次 409 确认流
- 依赖卸载后本机残留 UUID / 系统备份
- 专用导出导入或 join 响应塞全量实体包
- 多 active owner membership 或长期多 owner token 并存
- 改变一家一栈、硬家网门闩、仅前台同步

---

## User stories

1. As a 管理员, I want 卸载重装后用服务器初始化口令再次「创建家庭」即可接回, so that 我不会因 409 永远进不了已有家庭
2. As a 管理员, I want 接回后同步拉回 NAS 上已有护理数据, so that 家庭历史不因换机/重装丢失
3. As a 管理员, I want 历史记录仍显示我是上传者（同一 membership）, so that 作者身份不因重装分裂
4. As a 管理员, I want 接回后旧手机上的管理员会话失效, so that 会话只在当前设备
5. As a 成员, I want 继续用邀请码加入（本能力不对我开放 bootstrap 抢权）, so that 部署口令不会变成全员超管密钥
6. As a 运维, I want bootstrap 仍只在 Docker/环境变量配置, so that 口令不进库、不进 APK

---

## Test seams

1. **lezi-sync store / HTTP** — create 在已有家庭上的 reclaim、credential 吊销、membership 稳定、无/错 bootstrap
2. **FamilySessionCoordinator / SyncPort create** — 成功响应落盘为 Joined owner、触发同步、错误映射
3. **ReplicaSyncEngine** — 新会话 cursor/generation 下 full pull 合入既有 `client_uuid`（复用现测，必要时加 reclaim 会话夹具）

---

## Documentation gate

闭合前必须更新（可与实现票同 PR）：

- `docs/prd/sync-home-lan.md` §9.2 create：已有家庭 + 合法/开放 bootstrap → reclaim 语义
- `tools/lezi-sync/README.md`：管理员重装接回说明；强调口令在部署侧、APK 不持久化
- 若 UI 成功文案变化：`docs/prd/ui.md` 账户/建家相关一句
