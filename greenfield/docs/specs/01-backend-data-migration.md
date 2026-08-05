# Spec 01 — 后端 / 数据库迁移（0.3.x → Greenfield）

**Status:** draft for implementation  
**Goal:** 使现网或导出的 0.3.x 护理与家庭**数据**可进入 greenfield 栈，支撑维护窗 cutover；**不是**日常 APK 原地 Room 升级的替代叙述。  
**Authority:** `docs/prd/` · `docs/adr/`（尤其 ADR-0001/0005/0008/0012/0013/0014）· `CONTEXT.md` · `greenfield/docs/cutover-mapping-checklist-draft.md` · `greenfield/docs/production-readiness.md`  
**Out of scope for this spec:** APK 像素/动效一致（见 [02-apk-visual-parity.md](./02-apk-visual-parity.md)）；生产 NAS CD 命令与密钥。

---

## 1. Problem

Greenfield 1.0 已具备独立产品线能力，但与 0.3.x 在**持久化与协议面**上断裂：

| 面 | 0.3.x | Greenfield |
|----|--------|------------|
| 客户端本地库 | Room（契约 v3 / Room ~v26 链） | JSON 原子文件（`AtomicFileStore`）+ 内存域 |
| 服务端库 | `lezi-sync` SQLite + 厚 bundle/schema | `lezi-gf-sync` 薄 SQLite + `gf-1` wire |
| 会话 | 既有 DeviceSession / token | **不可迁移**；必须重建 |
| 布局/主题 | 本机 | **不可迁移**（设备本地） |

用户「无感替换」的前置是：**护理事实、计划、宝宝、自定义定义、媒体**在维护窗后可见且可同步；本 spec 只解决这条数据路径。

---

## 2. Goals & non-goals

### Goals

1. **可重复的迁移管线**：从 0.3.x **备份**（客户端导出 和/或 服务端 data 根快照）生成 greenfield 可导入的 **交换包**。  
2. **实体映射完整且可验证**：Record/Plan/Baby/CustomDef/NonAdopted + 照片原子性。  
3. **fail-closed**：半包媒体、未知类型、坏 payload、映射冲突 → 拒绝或隔离报告，禁止半导入当成功。  
4. **身份重建契约**：不导入 access/refresh；Owner/成员在新 endpoint 上重建会话后 pull 对齐。  
5. **可回滚**：旧栈备份未毁时可退回 0.3.x；不合并两家庭。

### Non-goals

- 长期双协议（ADR-0008）。  
- 自动在 server startup 迁移 NAS schema（ADR-0013：仅维护窗 CLI）。  
- 布局坞序、深色、惯用手、本机设置的跨设备迁移。  
- 两已配置家庭合并。  
- 像素 UI / 动画（Spec 02）。  
- 默认真家庭 NAS 作为 GF 默认 endpoint。

---

## 3. Actors & environments

| Actor | 职责 |
|-------|------|
| 操作者 | 维护窗备份、跑迁移 CLI、验证、切换 endpoint |
| Owner 客户端 | 新装/切换 GF → TOFU → 登录/恢复 → 全量对账 |
| 成员客户端 | 重新申请或扫 QR；不携带旧 token |
| `migrate` CLI（待实现） | 只读旧备份 → 写交换包 / 导入 GF 空库 |
| `lezi-gf-sync` | 只接受导入后的当前 schema；不自动升级旧 lezi-sync 库 |

**环境：** 独立空数据根（开发机或临时 NAS 路径），**禁止**对现网 data bind 就地试验。

---

## 4. Data products

### 4.1 输入（0.3.x）

至少支持一种权威输入（实现可分阶段）：

| 输入 | 内容 | 优先级 |
|------|------|--------|
| **A. 服务端 SQLite 备份** | 家庭、membership 元数据（仅用于展示名映射）、entity 护理/计划/宝宝/定义、媒体 blob 路径 | P0（家庭真相） |
| **B. 客户端 Room 导出** | 本机 Record/Plan/媒体 + 孤宝宝 | P0（离线用户） / 与 A 二选一或合并规则见 §6 |
| **C. 既有导出 TXT/PDF** | 仅人读，**不作**机器导入源 | 不做 |

### 4.2 交换包（Greenfield interchange）

建议格式（实现锁定一种）：

```text
lezi-migrate-<family_or_device>-<utc>.zip
  manifest.json          # schema_version, source, counts, content hashes
  entities/
    babies.jsonl
    records.jsonl
    plans.jsonl
    custom_defs.jsonl
    non_adopted.jsonl    # optional
  media/
    <media_uuid>.bin     # or .jpg; sha256 in jsonl
  report.json            # dry-run issues (warnings/errors)
```

`manifest.schema_version`：**interchange v1**（与 wire `gf-1` 解耦；导入器负责映射到当前 GF store）。

### 4.3 输出

| 目标 | 结果 |
|------|------|
| GF 服务端空库 | 导入后 `setup-status=configured`（或 empty→导入→configured）；entity 可 pull |
| GF 客户端 | 仅本机设置/布局仍为默认；护理数据来自 pull 或离线导入文件 |

---

## 5. Entity mapping (normative)

继承 cutover 草案，细化可测字段：

| 旧 (0.3.x) | 新 (GF) | 规则 |
|------------|---------|------|
| `Baby` | `family.Baby` / wire baby | 保留 `client_uuid`；`family_authority` 仅 Owner 侧导入为 true |
| `Record` | `CareRecord` | 保留 uuid；`type_key` 必须在 GF 已知集合；`payload_json` 经 `PayloadValidation` |
| Record photos | `PhotoRef` + wire photos | ≤3；`byte_size>0` 必须有可读字节；原子：元数据+媒体齐套才提交 |
| `CarePlan` | `CarePlan` | 状态枚举映射到 PENDING/MISSED/COMPLETED/SKIPPED |
| Plan photos | 同上原子规则 | |
| `CustomItemDef` | `CustomItemDef` | 定义迁；**坞布局不迁** |
| Non-adopted fulfill | nonAdopted | 不进普通时间轴 |
| Layout / dock / theme | — | **丢弃**；设备默认 |
| Session / token / refresh | — | **丢弃**；全员重建 |
| Membership graph | 服务端 membership | 导入时可重建 Owner+显示名；成员设备须重新加入 |

### 5.1 Payload 与类型

- 未知 `type_key` → error（整条记录失败，计入 report）。  
- 尿量/体温/睡眠起止/负奶量等 → 与现网 `PayloadValidation` / 服务端校验一致。  
- 睡眠开放会话：`open=true` / 无 end 允许；闭合须 `end>=start`。

### 5.2 媒体原子性

- 交换包内每条带图记录：jsonl 引用的每个 `media_uuid` 必须在 `media/` 存在且 sha256 匹配。  
- 缺任一媒体 → **该记录整包失败**（不得先导入无图记录）。  
- 导入服务端后客户端 pull：仍走 incomplete 拒绝/丢弃规则。

### 5.3 冲突与去重

- 同 `client_uuid`：保留一份；若两边 `updated_at_ms` 不同，取较大（与 GF LWW 一致）。  
- 客户端导出 + 服务端备份同时提供：以 **服务端实体为家庭权威**；客户端仅补「服务端没有的本机孤宝宝事实」并打 report 标记。

---

## 6. Pipeline (phases)

### Phase M0 — 清单与采样（只读）

- [ ] 固定一份脱敏 0.3.x 备份样本（开发机）。  
- [ ] 输出实体计数：baby/record/plan/def/media。  
- [ ] 对照 `cutover-mapping-checklist-draft.md` 补全字段表。

### Phase M1 — Interchange 导出 CLI

- [ ] `tools/lezi-migrate` 或 `greenfield/migrate`：**read-only** 读备份 → zip。  
- [ ] `manifest` + `report`（warn/error）。  
- [ ] Dry-run 模式不写目标库。  
- [ ] 单元测：坏媒体 / 未知类型 / 截断 json。

### Phase M2 — 导入 GF 服务端

- [ ] 仅对 **empty** data root 或显式 `--force-empty-ok`。  
- [ ] 事务导入；失败整库回滚到 empty。  
- [ ] 导入后 revision 单调；`/health` `/ready` capability 正常。  
- [ ] LiveWire 或 curl：Owner 会话 + pull 记录数与 manifest 一致。

### Phase M3 — 客户端离线导入（可选）

- [ ] 无服务器时：从 zip 导入本机 `AtomicFileStore` care/family 快照。  
- [ ] 损坏/半包 → gate 或错误页，**禁止** silent empty。  
- [ ] 之后连服务器：push 走 LWW，不双写坏包。

### Phase M4 — 维护窗剧本（文档）

- [ ] 备份 → 导出 → 新根导入 → TOFU → Owner 登录 → 成员重入 → 抽检 G1/G5。  
- [ ] 回滚：切回 0.3.x 包与旧 endpoint。  
- [ ] **不**在此 spec 写生产 push-and-deploy。

---

## 7. Acceptance criteria

1. **样本完整导入：** 对固定脱敏备份，record/plan/baby/def 计数与源一致（允许 report 中明确丢弃的坏行有列表）。  
2. **原子媒体：** 故意缺一张图 → 该记录不出现在目标库；其它完好记录仍可导入（或 fail-all 策略在 manifest 中二选一锁定，**默认 per-record fail**）。  
3. **校验对齐：** 非法 pee/temp/sleep 不进库。  
4. **无凭证泄漏：** 交换包与导入库不含 access/refresh/bootstrap 明文。  
5. **会话：** 导入后旧 token 不可用；Owner 用根密码/登录流新建 session。  
6. **回滚演练文档：** 一步不删旧备份即可退回 0.3.x 客户端。  
7. **测试：** 迁移纯逻辑单测 + 至少一条「导出→导入→pull 可见」集成路径（Fake 或本地 18765）。

---

## 8. Risks

| 风险 | 缓解 |
|------|------|
| Room schema 版本分叉 | 只支持「当前现网契约版本」；更旧需 ADR-0013 offline-migrate 先抬到基线 |
| 媒体路径权限 | 导出阶段复制字节进 zip，不依赖运行时 NAS 路径 |
| 双源冲突 | §5.3 权威规则 + report |
| 误导入生产 | CLI 默认拒绝非 empty；需双确认 flag |

---

## 9. Deliverables checklist

| 交付物 | 路径建议 |
|--------|----------|
| 本 spec | `greenfield/docs/specs/01-backend-data-migration.md` |
| 字段映射表（可执行） | `greenfield/docs/specs/01-entity-field-map.md`（实现时补） |
| CLI | `greenfield/migrate/` 或 `tools/lezi-migrate/` |
| 测试 | `.../migrate/...Test` + 样本 zip（脱敏、git-lfs 或本地 only） |
| 维护窗 runbook | `greenfield/docs/runbooks/data-cutover.md`（无密钥） |

---

## 10. Relationship to Spec 02

| Spec 01 完成后用户得到 | Spec 02 负责 |
|------------------------|--------------|
| 打开 GF 能看到**自己的历史记录与宝宝** | 看起来/摸起来**像** 0.3.x |
| 同步与身份可重建 | 元素、质感、动画一致 |

两者独立并行；**无感替换 = Spec 01 ∧ Spec 02 ∧ 运维切换**，缺一不可。
