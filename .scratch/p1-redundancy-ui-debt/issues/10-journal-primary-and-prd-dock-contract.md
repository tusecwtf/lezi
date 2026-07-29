# 10 — journal 主色政策 + PRD 坞条款 + 死组件

**Parent:** [../spec.md](../spec.md)

**What to build:** （1）明确 **journal 模板主色政策**并落地：要么主按钮/选中强调跟随宝宝主题色（对齐 PRD §2.1），要么改 PRD/设置说明写清「journal 品牌珊瑚例外」，禁止代码与文档长期分叉。（2）**坞与惯用手**：PRD `docs/prd/ui.md` 与 `CONTEXT.md` 对齐为绝对左右槽序、惯用手只影响圆盘/表单靠边；设置文案不暗示坞会镜像。（3）退役或隔离仅 Preview/无生产引用的死组件（如平行摘要条、未用的上下文行），避免误用带回错误触控尺寸。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M  
**Theme:** F（U1 / U4 / R19）  
**Seams:** Theme color scheme；PRD/CONTEXT；designsystem 死 API

## Acceptance criteria

- [ ] 书面政策二选一已写进 PRD（及必要 CONTEXT），与 `resolveLeziColorScheme`（或等价）行为一致
- [ ] journal 下主 CTA / 选中强调符合该政策；顶栏宝宝强调不无故回退
- [ ] `docs/prd/ui.md` 不再要求四槽随惯用手镜像；与 `CONTEXT.md`「常用记录」一致；设置「单手操作」说明不承诺坞镜像
- [ ] 确认无生产 call site 的死组件删除或移入明确 preview-only 并加注释；触控 <48dp 的死 API 不进入生产
- [ ] 文档与主题相关测试/静态检查通过

## Out of scope

- 重做整套 journal 视觉
- Search 滑动编辑（审查 defer）
