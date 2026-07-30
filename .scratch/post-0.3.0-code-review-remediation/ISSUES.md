# Post-0.3.0 code-review 残差整改 · 票索引

Spec: [spec.md](./spec.md)
Status: ready-for-agent
Audit HEAD: `766d30ae7e6094c4c5d82061f2ad916c8895488d`

- Audit OPEN findings: **5**
- Executable tickets: **9**
- Immediate ready-for-agent: **0**
- Ready structural frontier: **4**
- Planned structural tickets: **3**
- Accepted residuals without implementation ticket: **2**

## Dependency and activation graph

```text
01 next-feed 恢复真相（complete） ───────────────┬──► 07 CareLog 计划/履行协调器（planned）
                                                 └──► 09 RecordComposer UI/VM 拆分（ready）

02 冻结 fulfillment 绑定（complete）

03 LayoutEditMode 拆分（ready）

04 CareLog 查询 seam（ready）
  └──► 05 宝宝/家庭档案 seam（planned）
        └──► 06 Record/睡眠写 seam（planned）
              └──► 07 CarePlan/履行 seam（planned）

08 LogScreen 剩余宿主拆分（ready）
```

**Current frontier:** 03、04、08、09。01/02 correctness lane 已闭合，当前“实现所有 scratch
问题”的目标满足 user activation；05–07 仍按 04 → 05 → 06 → 07 依赖串行激活。

结构票默认不自动实施；正确性门闭合后由用户激活。04–07 因共享 `CareLog.kt` 必须串行。

## Tickets

| ID | Title | Audit ID | Blocked by | Size | Status |
|----|-------|----------|------------|------|--------|
| [01](./issues/01-reconcile-next-feed-after-interrupted-commit.md) | 中断恢复后对齐下次喂养持久化真相 | P0-01 | — | L | complete |
| [02](./issues/02-freeze-completed-plan-fulfillment-binding.md) | 冻结已完成计划的 fulfillment 绑定 | P1-01 | — | M | complete |
| [03](./issues/03-split-layout-edit-mode-host.md) | 拆分 LayoutEditMode 目录/坞/替代输入宿主 | P1-02 | — | L | ready-for-agent |
| [04](./issues/04-extract-carelog-query-surface.md) | 抽离 CareLog 查询与摘要 seam | P1-02 | — | L | ready-for-agent |
| [05](./issues/05-extract-carelog-baby-family-profile.md) | 抽离 CareLog 宝宝与家庭档案 seam | P1-02 | 04 | L | planned |
| [06](./issues/06-extract-carelog-record-sleep-mutations.md) | 抽离 CareLog Record 与睡眠写 seam | P1-02 | 05 | L | planned |
| [07](./issues/07-extract-carelog-plan-fulfillment-orchestration.md) | 抽离 CareLog 计划与履行协调 seam | P1-02 | 01、06 | L | planned |
| [08](./issues/08-split-log-screen-list-dialog-host.md) | 拆分 LogScreen 列表与弹窗宿主 | P1-02 | — | M–L | ready-for-agent |
| [09](./issues/09-split-record-composer-state-and-ui.md) | 拆分 RecordComposer state/VM/UI | P1-02 | — | L | ready-for-agent |

## Accepted residuals

| Audit ID | Disposition | Reopen only when |
|----------|-------------|------------------|
| P1-03 | accepted residual；保留事务内引用检查与文件删除 | 有写锁、ANR、慢盘或跨进程清理证据 |
| P1-04 | accepted residual；保留 post-commit receipt，prepare 元数据可重试 | 元数据影响本机真相、导出或 manifest 稳定性 |

## Closed historical P0

H01 reclaim scaffold、H02 pre-commit `remoteUri`、H03 timer 假 RUNNING、H04 主线程全分辨率
decode 在 audit HEAD 已关闭。只在 cherry-pick/rebase 回流审查时核对，不创建票。

## Execution discipline

- 01/02 可并行；不得把结构搬迁混进正确性提交。
- planned 票开始前先改为 ready-for-agent 并重新核对 live 行数、调用者、工作树和 blocker。
- 结构票只搬迁一个深 seam，保留 `CareLog`/`LogRoute`/`RecordComposerHost` 等既有 facade，
  禁止新增第二条产品路径。
- 一票一组窄提交；每票完成后更新本索引、证据与下一 frontier。
