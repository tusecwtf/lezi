# 10 — journal 主色政策 + PRD 坞条款 + 死组件

**Parent:** [../spec.md](../spec.md)

**What to build:** （1）明确 **journal 模板主色政策**并落地：要么主按钮/选中强调跟随宝宝主题色（对齐 PRD §2.1），要么改 PRD/设置说明写清「journal 品牌珊瑚例外」，禁止代码与文档长期分叉。（2）**坞与惯用手**：PRD `docs/prd/ui.md` 与 `CONTEXT.md` 对齐为绝对左右槽序、惯用手只影响圆盘/表单靠边；设置文案不暗示坞会镜像。（3）退役或隔离仅 Preview/无生产引用的死组件（如平行摘要条、未用的上下文行），避免误用带回错误触控尺寸。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M  
**Theme:** F（U1 / U4 / R19）  
**Seams:** Theme color scheme；PRD/CONTEXT；designsystem 死 API

## Acceptance criteria

- [x] 书面政策二选一已写进 PRD（及必要 CONTEXT），与 `resolveLeziColorScheme`（或等价）行为一致
- [x] journal 下主 CTA / 选中强调符合该政策；顶栏宝宝强调不无故回退
- [x] `docs/prd/ui.md` 不再要求四槽随惯用手镜像；与 `CONTEXT.md`「常用记录」一致；设置「单手操作」说明不承诺坞镜像
- [x] 确认无生产 call site 的死组件删除或移入明确 preview-only 并加注释；触控 <48dp 的死 API 不进入生产
- [x] 文档与主题相关测试/静态检查通过

## Implementation notes

- live audit 确认历史提交 `b91601c` 已使 warm / journal 在有宝宝主题时共享
  `resolveLeziColorScheme` 主色，并使 `resolveLeziExtendedColors.babyAccent` 跟随该主色；
  `LeziPrimaryButton` 的启用态也已消费 `MaterialTheme.colorScheme.primary`。本票不改主题算法。
- PRD 现在明确顶栏宝宝强调、主 CTA 与选中态都跟随当前宝宝主题色；journal 珊瑚只在
  无宝宝主题时充当模板默认色。测试覆盖 light / dark、warm / journal 与顶栏 accent。
- PRD、`CONTEXT.md` 与设置页保持同一坞契约：四槽按用户编排的绝对左右序展示，惯用手只
  影响圆盘/表单靠边。设置页只保留一份说明，避免重复文案继续漂移。
- `JournalSummaryStrip` 与 `AppContextRow` 已在 `b91601c` 删除且无生产 call site；静态回归
  锁住这两个平行 API 不回流，并锁住生产主按钮继续使用主题主色。

## Validation evidence

- 有效 TDD RED：源/文档契约测试因 PRD 缺少 journal CTA 明文政策失败；补齐政策并移除设置页
  重复说明后定向测试 GREEN。对已存在的主题算法与死 API 状态仅补诚实 characterization，
  未伪造 RED。
- `./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest :feature:settings:testDebugUnitTest :designsystem:lintDebug :feature:settings:lintDebug :app:assembleDebug --no-daemon`：
  通过（594 tasks，7s；验证基线 `215f761d3040202808dc9ffb162461b5c79969a1`）。
- 完整回执见 `../evidence/10/validation.md`；未改版本、设备行为、主题算法或其他 Program WIP。

## Out of scope

- 重做整套 journal 视觉
- Search 滑动编辑（审查 defer）
