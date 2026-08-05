# Greenfield follow-on specs（无感替换拆分）

面向「生产可替换 → 用户无感替换」的两块独立工程。**可并行**；合取才接近无感 cutover。

| Spec | 主题 | 解决什么 | 不解决什么 | 1.0.0 |
|------|------|----------|------------|-------|
| [01 — 后端/数据库迁移](./01-backend-data-migration.md) | 数据与会话重建 | 0.3.x 护理/宝宝/计划/媒体 → GF 可导入；维护窗剧本 | APK 长相；生产 NAS CD 密钥 | 并行，不挡壳层冻 |
| [02 — APK 设计一致](./02-apk-visual-parity.md) | 质感 · 元素 · 动画 | 与 0.3.x 视觉与交互语言一致；**1.0.0 以单场景 S-freeze 冻结** | Room 迁移；服务器 schema；完整 §2.3 盲测 chrome | **S-freeze PASS · residual 延期** |
| [03 — UI/UX 差异 inventory](./03-uiux-diff-inventory.md) | 双装场景差异清单 | P0 场景矩阵 + R-* residual + polish 顺序 | 像素 CI；自动改 UI | **inventory complete · polish open** |

## 关系

```text
当前 GF 1.0.0
  ├─ 产品线可独立发布（功能 + 数据面门闩）     ← 已具备
  ├─ Spec 01  历史数据可迁入                     ← 本拆分（并行）
  └─ Spec 02  壳层                               ← 1.0.0 = S-freeze 结构门闩
                                                 完整盲测 chrome = post-1.0.0

无感替换 ≈ Spec01 ∧ Spec02(§2.3) ∧ 运维切换（endpoint / 全员重登）
1.0.0 可发 ≠ 无感替换完成
```

## 权威上游

- 产品：`docs/prd/` · `CONTEXT.md` · `docs/adr/`  
- 已有 cutover 草案：`../cutover-mapping-checklist-draft.md`  
- 生产门闩现状：`../production-readiness.md`  
- UI 差距史：`../uiux-parity-audit.md`  
- 体量：`../code-volume-compare.md`
