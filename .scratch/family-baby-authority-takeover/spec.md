# Spec: 加入家庭后宝宝管理权限上移

Status: complete
Feature: family-baby-authority-takeover
Product: 乐记 (`com.lezi.babylog`)
Source: `/to-spec` 2026-07-28 · 用户决策：加入家庭后宝宝管理由管理员接管
Related:

- `CONTEXT.md`（家庭 membership、家庭管理员、账户概览、宝宝区）
- `docs/prd/sync-home-lan.md` §4.3 权限矩阵、§5 同步域与冲突
- `docs/prd/data-model.md` §3.4 Baby、§6 SyncPort
- `docs/prd/ui.md` §5.7 账户概览宝宝区
- `docs/adr/0002-family-identity-and-account-overview.md`
- `docs/adr/0007-separate-family-membership-from-credentials.md`
- `docs/adr/0008-support-only-fresh-current-product-contracts.md`

测试 seam（已与用户确认）：

1. **CareLog** — 加入后宝宝增删改权限、本机孤宝宝降级与（必要时）记录再绑定
2. **ReplicaSyncEngine** — 成员不 push 宝宝实体；pull 只认家庭权威宝宝档案

---

## Problem Statement

照护者在本机建好宝宝后加入已有家庭时，本机宝宝与家庭服务器上的宝宝是两条互不识别的档案（不同 `client_uuid`）。当前同步按 uuid 幂等 upsert，本机脏宝宝会 push 上家庭，家庭宝宝又 pull 下来，结果**同一真实宝宝在列表里出现两个**。

更深层的问题是权限边界：现行规格允许成员 push/pull 宝宝档案字段（仅头像限管理员）。成员设备上的本机宝宝档案与管理员建立的家庭宝宝档案地位对等，加入家庭并不会把宝宝管理权上移。用户只能事后靠「合并」手工收拾，体验像数据冲突，而不是「进了家，宝宝由家里管」。

---

## Solution

**加入家庭之后，宝宝档案的家庭权威归管理员；成员设备只认家庭同步下来的宝宝信息。**

用户可感知结果：

1. **成员加入后不再「造出第二个宝宝」**
   本机加入前的宝宝不会作为新家庭宝宝上传；列表以家庭服务器上的宝宝为准。
2. **成员不能再管理家庭宝宝档案**
   已加入且角色为成员时：不能添加、编辑档案字段、删除家庭宝宝；头像继续仅管理员可改（与现行一致并扩展到全档案）。
   本机展示偏好（主题色、排序）仍属本机域，可改、不同步。
3. **管理员仍全权管理宝宝**
   新建家庭的管理员、已加入的管理员可添加/编辑/删除/合并宝宝，变更经同步成为全家权威。
4. **本机加入前的护理数据尽量不丢**
   成员本机上、仅属于「本机孤宝宝」的护理记录 / 护理计划 / 相关媒体，在可安全判定时再绑定到家庭权威宝宝；无法安全自动判定时保留可合并路径，而不是静默双宝宝并推上家庭。
5. **服务端护栏**
   NAS 拒绝成员对 baby 实体的创建/更新/软删；与客户端策略一致，防止旧客户端或旁路写污染家庭宝宝集。

产品原则：

- **只认管理员同步的宝宝信息** = 家庭域宝宝集合与档案字段的权威在 NAS，且只有 owner 可写；成员只读 pull。
- **权限上移** 发生在「已加入且 role=member」之后，不是 onboarding 未加入阶段。
- **不做昵称自动匹配合并** 作为权威裁决（昵称可重复、可改）；合并身份只按 `client_uuid` / 显式用户合并 / 明确的单宝宝再绑定规则。

---

## User Stories

### 加入时的双宝宝问题

1. As a 成员照护者, I want 用已有本机宝宝加入已有家庭后列表里不要凭空多出一个宝宝, so that 我不会搞不清该记在谁名下
2. As a 成员照护者, I want 加入后看到的宝宝档案来自家庭服务器, so that 全家对「有哪些宝宝」一致
3. As a 成员照护者, I want 本机加入前的宝宝不要被上传成新的家庭宝宝, so that 不会污染家里的宝宝集合
4. As a 管理员, I want 成员加入后不会在家庭服务器上多出我从未创建的宝宝, so that 我仍能掌控家里有几个宝宝档案
5. As a 成员照护者, I want 加入后第一次同步完成时当前宝宝能落到家庭权威宝宝上, so that 我能继续记日常而不卡在空宝宝
6. As a 成员照护者, I want 若家里只有一个权威宝宝，本机旧记录能挂到这个宝宝下, so that 加入前记的喂养睡眠不会消失
7. As a 成员照护者, I want 若家里有多个权威宝宝且无法安全自动挂接，系统提示我用合并把本机数据并入正确宝宝, so that 我不会丢数据也不会自动并错人
8. As a 成员照护者, I want 自动再绑定或降级后不再显示「同名两个宝宝」这种对等档案, so that 账户宝宝区恢复安静

### 成员侧权限（已加入）

9. As a 成员, I want 不能添加新的家庭宝宝, so that 宝宝集合不会被各人随意扩增
10. As a 成员, I want 不能编辑家庭宝宝的昵称/性别/生日/出生体重等档案字段, so that 档案只跟管理员权威
11. As a 成员, I want 不能删除家庭宝宝档案, so that 家人不会误删全家可共享的宝宝
12. As a 成员, I want 不能改家庭宝宝头像（延续现行）, so that 头像与档案同属管理员权威
13. As a 成员, I want 尝试管理宝宝时看到可理解的拒绝说明, so that 我知道要找管理员改档案
14. As a 成员, I want 仍可切换当前宝宝（在家庭权威宝宝之间）, so that 多宝宝家庭里我仍能记对孩子
15. As a 成员, I want 仍可改本机主题色与排序, so that 界面偏好不被家庭同步打架
16. As a 成员, I want 仍可写护理记录、护理计划与日志照片并同步, so that 日常记事不被宝宝档案权限卡住
17. As a 成员, I want 离开家庭后恢复本机自主管理宝宝的能力, so that 退出共享不等于永久失去本机控制
18. As a 成员, I want 账户宝宝区在成员身份下隐藏或禁用添加/编辑档案/删除入口, so that UI 与权限一致

### 管理员侧

19. As a 管理员, I want 加入或创建家庭后仍可添加宝宝, so that 家里新增孩子时有入口
20. As a 管理员, I want 可编辑宝宝档案与头像, so that 全家看到一致信息
21. As a 管理员, I want 可删除多余宝宝档案（至少保留一个的现有约束仍适用）, so that 能清理错误档案
22. As a 管理员, I want 可合并两个宝宝档案, so that 历史重复档案能收敛
23. As a 管理员, I want 我对宝宝的变更经家庭同步到成员设备, so that 权限上移后成员「只认」我同步出去的信息
24. As a 管理员, I want 成员无法通过旧客户端或旁路把宝宝写进家庭服务器, so that 服务端护栏可信

### 新建家庭 / 未加入

25. As a 未加入的照护者, I want 本机仍可自由建宝宝、改档案, so that 单机使用不受家庭权限影响
26. As a 新建家庭的管理员, I want 建家后本机已有宝宝能成为家庭权威宝宝并同步, so that 我不是「空家」
27. As a 未加入的照护者, I want onboarding 与设置里的添加宝宝流程保持可用, so that 权限上移不破坏首启

### 同步与冲突语义

28. As a 成员设备, I want pull 到的宝宝档案按家庭权威覆盖本机同 uuid 档案字段, so that 「只认管理员同步的宝宝信息」
29. As a 成员设备, I want 本机对家庭宝宝档案字段的本地脏写在成员角色下不会被推上 NAS, so that 不会和权威对打
30. As a 成员设备, I want 从未在家庭服务器出现过的本机孤宝宝永不进入 baby outbox, so that 不会同步生成第二个宝宝
31. As a 管理员设备, I want 宝宝档案仍按既有 LWW / tombstone 规则同步, so that 多管理员设备之间行为可预期
32. As a 成员设备, I want 对孤宝宝的本地软删或降级不向 NAS 发送该 uuid 的 baby tombstone, so that 不会在服务器上制造幽灵实体
33. As a 家庭, I want Record / CarePlan 仍用 `baby_client_uuid` 引用权威宝宝, so that 跨机引用不依赖本机自增 id

### 合并与本机数据

34. As a 成员, I want 在需要时仍可把本机孤宝宝的数据合并进某个家庭权威宝宝（若产品保留该入口）, so that 多宝宝家庭不会卡死
35. As a 成员, I want 合并预览仍只谈记录/计划数量、不靠昵称裁决身份, so that 与现有合并语义一致
36. As a 成员, I want 合并完成后源孤宝宝从本机列表消失且不上传为家庭宝宝, so that 列表干净
37. As a 照护者, I want 合并后当前宝宝指针落在目标权威宝宝上, so that 不会指到已降级档案

### 账户与设置 UI

38. As a 成员, I want 宝宝区展示家庭权威宝宝列表与只读档案信息, so that 我能核对生日昵称但改不了
39. As a 成员, I want 「添加宝宝」在已加入成员身份下不可用, so that 不会点进注定失败的流程
40. As a 管理员, I want 宝宝区管理入口保持完整, so that 我知道谁能管
41. As a 成员, I want 设置页/菜单里若另有添加宝宝入口，在成员身份下同样禁用, so that 不出现旁路

### 错误、离线与边界

42. As a 成员, I want 离线时也不能本地创建新的「将来会上传」的家庭宝宝, so that 回家网后不会突然多宝宝
43. As a 成员, I want 在家庭尚未 pull 到任何宝宝前，UI 有清晰空态而不是用本机孤宝宝冒充家庭权威, so that 权限语义不摇摆
44. As a 成员, I want 同步失败时已降级决策可在下次同步收敛, so that 半次失败不会永久双宝宝
45. As a 管理员, I want 删除家庭或成员离开后的会话清理仍遵循现有 leave/delete 语义, so that 本特性不改退出模型

### 可访问与文案

46. As a 小白成员, I want 拒绝管理宝宝的文案用人话说明「宝宝档案由家庭管理员管理」, so that 我知道找谁
47. As a 小白成员, I want 加入前明示共享范围时仍包含宝宝档案由家庭共享/由管理员管理的含义, so that 预期一致

---

## Implementation Decisions

### 权威模型

1. **家庭宝宝权威角色 = 家庭管理员（owner）**
   宝宝实体（档案字段 + 软删 + 头像引用）的家庭写权限仅 owner。成员仅 pull 应用。
   这**收紧**现行 `sync-home-lan` §4.3「成员可 push Baby 档案字段」的建议表述；本 spec 生效后以本决策为准，并回写 PRD。

2. **本机展示偏好仍本机域**
   `theme_color`、`sort_order` 继续不同步；成员与管理员皆可改本机偏好。不纳入「宝宝管理被接管」范围。

3. **未加入 / role=None**
   行为与今相同：本机自由 `addBaby` / 更新 / 删除（保留至少一个宝宝等既有约束）。

4. **role=Owner**
   行为与今相同：宝宝增删改合并可同步进家庭域。

5. **role=Member**
   - 禁止：创建宝宝、修改家庭同步档案字段、删除家庭宝宝、修改头像。
   - 允许：切换当前宝宝、改主题色/排序、写记录与计划、pull 应用权威宝宝。
   - 领域层抛出与现有 `CarePlanPermissionException` / `CustomItemPermissionException` 同风格的明确权限异常；UI 映射成人话。

### CareLog seam（领域）

6. **CareLog 是宝宝写路径的权限闸门**
   `addBaby` / `updateBabyProfile` / `deleteBaby` 在 session 为 Member 时拒绝家庭档案写。
   主题色-only 更新若走独立路径可放行；若与档案字段共用 `updateBabyProfile`，则成员调用必须被拒绝或拆成本机偏好 API——实现须保证成员无法借道改昵称等同步字段。

7. **加入后的本机孤宝宝（local-only baby）**
   定义：本机存在、其 `client_uuid` 从未作为家庭服务器 baby 实体出现过（成员侧以「不在已 pull 的家庭宝宝集合中、且不得 outbox」判定）。
   成员角色下：
   - 孤宝宝不得 `syncDirty` 上行 baby；
   - 不作为与家庭权威宝宝对等的长期档案展示目标。

8. **孤宝宝数据再绑定（优先安全自动，否则显式合并）**
   - **当家庭权威活跃宝宝恰好 1 个**：允许在 CareLog 事务内把该孤宝宝下的 Record / CarePlan / 相关 baby 向媒体再绑定到该权威宝宝，然后本机软删孤宝宝且**不**将此 baby tombstone 推上 NAS；并收敛 `currentBabyId`。
   - **当家庭权威活跃宝宝为 0 个**：不把孤宝宝上传；可短暂保留本机可见性仅用于展示空家等待管理员建宝宝，或显示空态——但**一旦** pull 到权威宝宝，立即按上条或下条收敛。优先实现：0 个权威宝宝时成员不展示「可同步的本机宝宝」为家庭档案，空态引导「等待管理员添加宝宝」。
   - **当家庭权威活跃宝宝 ≥ 2 个**：不做昵称猜测；保留显式合并到用户点选的目标权威宝宝；合并实现必须满足「源为孤宝宝时不 push 源 baby 实体」。

9. **触发时机**
   孤宝宝降级/再绑定在 **Member 会话下宝宝集合发生变化时** 由 CareLog 可测入口执行（例如 sync apply 宝宝页后的领域回调，或 CareLog 提供的 `reconcileMemberLocalBabies(familyAuthorityBabyClientUuids)`）。
   不把降级逻辑散落在 Compose UI。

10. **显式 `mergeBabyProfiles`**
    成员仅允许「源 = 本机孤宝宝 → 目标 = 家庭权威宝宝」这类收敛合并（若保留入口）；禁止成员合并两个家庭权威宝宝或删除权威宝宝集合。管理员保持现有任意合并能力。

### ReplicaSyncEngine seam（同步）

11. **成员不 enqueue / 不 push `type=baby`**
    出站快照时，若 `session.role == Member`，跳过全部 baby 实体（含新建、更新、tombstone、头像指针变更）。
    头像 media 写限 owner 的现行行为保持。

12. **成员 pull `applyBaby` 仍应用家庭实体**
    同 uuid：LWW/既有规则应用档案字段；主题色/排序保留本机。
    新 uuid：插入家庭权威宝宝。
    apply 完成后触发 CareLog 侧孤宝宝 reconcile（见决策 9）。

13. **管理员同步路径不变**
    Owner 仍 mark/push baby；create family 后本机宝宝可上行成为权威源。

14. **Join 路径**
    `JoinFamilyUseCase` 仍是 join 命令 seam；本特性不把宝宝接管逻辑塞进 join 网络调用本身。
    join 成功后的首次 pull（已有 best-effort `requestSync(PullToRefresh)`）负责拉权威宝宝；ReplicaSyncEngine + CareLog reconcile 完成接管。

### NAS / 契约

15. **服务端拒绝成员写 baby**
    ordinary push 与任何可携带 baby 变更的路径上，member 对 `type=baby` 返回 forbidden（403 语义与现有 avatar ACL 一致）。
    不引入新的宝宝「创建者 membership」字段也能完成：写权限只看 role=owner。

16. **fresh-current**
    遵循 ADR-0008：只支持 current 契约；不为旧「成员可改宝宝档案」客户端保留写兼容。

17. **PRD 回写（实施时）**
    - `sync-home-lan` §4.3：Baby 档案字段 push 改为仅 owner；pull 全员。
    - `ui.md` 宝宝区：成员只读档案 + 切换；添加/编辑/删除仅管理员（及未加入本机）。
    - `data-model`：如有权限叙述，与上对齐。

### UI

18. **Family / Settings 消费角色，不自创第二套权限**
    `canEditFamilyAvatar` 扩展为更完整的宝宝管理可见性（例如 `canManageFamilyBabies(role)`：Owner 或未加入；Member 为 false）。
    成员宝宝区：只读档案信息、可「设为当前」；隐藏添加/编辑档案/删除；合并入口仅在存在待收敛孤宝宝时按决策 10 露出（若首版选择「仅自动单宝宝再绑定、多宝宝也自动禁用合并」，须在 Further Notes 落地——默认 **保留** 成员「孤→权威」合并以免多宝宝家庭丢数据）。

19. **文案**
    拒绝管理：`宝宝档案由家庭管理员管理`（或等价）。
    不暴露 client_uuid、token、内部 role 枚举名。

### 非目标式实现约束

20. **不引入跨设备「同一宝宝启发式合并」服务**（生日+昵称自动融合等）。
21. **不把 Record 作者权限与宝宝管理权限捆绑**——成员仍可写记录。
22. **不做管理员转移**；owner leave 语义不变。

---

## Testing Decisions

### 什么叫好测试

- 只断言**外部行为**：给定角色与本机/远端宝宝集合，同步或领域调用之后，列表中有哪些宝宝 uuid、是否 push 了 baby、记录挂在哪个宝宝、权限调用成功或失败文案/异常类型。
- 不绑定 Compose 控件树、不绑定具体 Room SQL、不测试私有函数名。
- 优先单测双 seam；UI 策略函数可薄测可见性布尔。

### 模块与 prior art

| Seam / 模块 | 测什么 | Prior art |
|-------------|--------|-----------|
| **CareLog** | Member 拒绝 add/update/delete 档案；Owner/未加入放行；单权威宝宝时孤宝宝再绑定与 current 收敛；多权威时不瞎并；合并孤→权威不产生待推 baby | `CareLogTest`（create/delete/merge、权限类异常如 CarePlan/CustomItem） |
| **ReplicaSyncEngine** | Member 会话出站不含 baby；Member pull apply 新权威宝宝；apply 后孤宝宝被 reconcile；Owner 仍 push baby；孤宝宝 tombstone 不上传 | `ReplicaSyncEngineTest`、`RealSyncPortTest`（member avatar 拒绝、push 集合断言） |
| **NAS（lezi-sync）** | member push baby → forbidden；owner push baby ok | `tools/lezi-sync` 既有 API/ACL 测试风格（avatar owner-only） |
| **FamilyUiPolicy** | `canManageFamilyBabies` / 入口可见性随 role | `FamilyErrorCopyTest` 中 `canEditFamilyAvatar` |

### 关键场景清单（应有自动化）

1. 成员本机 1 宝宝 + 家庭 1 宝宝（不同 uuid）→ 同步后仅 1 个权威宝宝；本机记录挂到权威；无 baby push。
2. 成员本机 1 宝宝 + 家庭 2 宝宝 → 不自动并错；无 baby push；孤宝宝待显式合并或等价安全处理。
3. 成员调用 `addBaby` / `updateBabyProfile` / `deleteBaby` → 权限失败。
4. 管理员同上调用 → 成功且可 push。
5. 未加入 `addBaby` → 成功。
6. 成员改主题色（若支持独立路径）→ 成功且不同步脏 baby 档案字段。
7. 服务端 member push baby → 拒绝。

---

## Out of Scope

- 管理员角色转移、无管理员家庭、踢人。
- 跨设备「同一人」照护者合并（ADR-0002 已否）。
- 按昵称/生日自动把两个家庭权威宝宝合成一个。
- 成员只读浏览以外的字段级 ACL（如「只能改备注」）。
- 改变 Record/CarePlan/CustomItem/履行冲突的既有 ACL。
- 改变 leave/delete 家庭后的本机清空策略。
- 商业化账号体系、云端多租户。
- 历史已错误 push 上 NAS 的重复宝宝的自动集群清理（可用管理员手动合并/删除处理；不做迁移专项除非实施中发现阻塞）。
- 设计系统视觉改版（仅权限相关显隐与文案）。

---

## Further Notes

### 与现行能力的关系

- 今日已有**显式宝宝合并**与**头像仅 owner**；本 spec 把「档案写权限」与「成员孤宝宝不得上行」补全，并把加入后的默认结果从「双宝宝 + 手工合并」改为「权限上移 + 单宝宝场景自动收敛」。
- `JoinFamilyUseCase` 保持网络 join 单一入口；宝宝接管是 join 之后 sync/reconcile 的结果，测试不必把 HTTP join 与宝宝降级绑成一个巨型用例。

### 产品默认（已合成，实施按此）

| 场景 | 行为 |
|------|------|
| 成员 + 家庭恰好 1 个权威宝宝 | 自动再绑定孤宝宝数据并本机降级孤宝宝 |
| 成员 + 家庭 ≥ 2 个权威宝宝 | 不自动猜；保留孤→权威显式合并 |
| 成员 + 家庭 0 个权威宝宝 | 不上传本机宝宝；空态等待管理员 |
| 管理员建家 | 本机宝宝可上行成为权威 |

### 回写文档

实施合并前应更新：

- `docs/prd/sync-home-lan.md` §4.3
- `docs/prd/ui.md` 账户宝宝区
- 必要时 `CONTEXT.md` 增加「家庭宝宝权威 / 本机孤宝宝」术语（若实现中名称稳定）

### 风险

- 成员加入前已有大量记录且家庭为多宝宝：必须依赖显式合并，UI 要找得到入口。
- 若只做客户端不 push、服务端未护栏，旧包仍可能污染家庭宝宝集——**服务端拒绝与客户端跳过都要做**。
- 再绑定会改 Record 的 `baby_id` 并可能 `syncDirty`：这些 record 仍应按现规则 push；仅 baby 实体不 push。

---

## Comments

- 2026-07-28：seam 经用户确认为 **CareLog + ReplicaSyncEngine** 双 seam。
- 2026-07-28：01–04 全部实现；成员 Baby 上行/NAS 护栏、领域与 UI 写禁、单权威自动再绑定、多权威显式孤→权威合并均已闭环。
- 验收：双轴代码审查 Standards/Spec 均 PASS；`./gradlew test lintDebug assembleDebug` 与 Rust 全测试/clippy 通过。
