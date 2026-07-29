# 01 — 延续“添加记录”的四列分类卡片视觉

**What to build:** 把布局编辑态改造成与当前“添加记录”页面连续的全屏分类目录：沿用其四列圆角卡片、柔色图标底板、分类标题、排版和主题 token，同时保留稳定的底部四槽 Dock 与编辑专属拖动反馈。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] 进入编辑态后全屏替换记录页内容，日期 chrome 与主导航 tabs 不出现；顶栏只保留清晰的“编辑布局”标题和“完成”动作。
- [x] 分类标题、四列记录卡片、图标底板、字号、行列间距和页面背景与当前“添加记录”使用同一组件或同一权威 token，而不是复制一套近似常量。
- [x] 编辑目录不重复“常用补充”分区；四个常用关系只在底部 Dock 中表达。
- [x] Dock 在进入/退出编辑态时保持 4 个槽加固定“更多”的顺序、尺寸和相对位置，不因编辑视觉重构发生明显跳变。
- [x] “更多”明确呈现锁定状态，但本票不改变其拖放规则；触摸业务行为留给 Ticket 03。
- [x] 源位弱化、跟手浮层和合法落点高亮仍可见，且不会让卡片静止态重新退回桌面图标墙风格。
- [x] warm / journal、浅色 / 深色均使用各自现有主题 token；字体放大后标题、“完成”和卡片标签不裁切关键内容。
- [x] 编辑态短按卡片不打开 Composer、不创建 Record；退出后当前日期上下文和日常记录页保持不变。
- [x] UI/展示测试锁定全屏 chrome、分类标题、四列结构、无“常用补充”和固定 Dock，不以脆弱像素快照替代结构断言。

## Validation evidence

- `RecordCatalogCard`、`RecordCatalogVisualSpec` 与 `QuickDockVisualSpec` 由“添加记录”和编辑态共同消费；展示契约测试锁定四列分类、无“常用补充”及 4 槽 + 固定“更多”。
- `:feature:log:testDebugUnitTest :feature:log:lintDebug :app:testDebugUnitTest :app:assembleDebug` 在 HEAD `86539bb3572984128b58f161093d95da7d27f31d` 上通过；`git diff --check` 通过，lint HTML 已生成。
- `lezi_api35`（1080×2400 @ 420 dpi，逻辑约 411×914 dp）完成 warm / journal × light / dark smoke；日常与编辑 Dock 均为 `y=1872..2040`，编辑态无日期 chrome 和主 tabs。
- 1.3× 字体 smoke 中标题、“完成”和多字卡片标签保持完整；短按目录卡片后仍停留编辑态且无 Composer，Back 后仍为 `今天 / 7月30日 · 周四`，日常记录页未产生记录。
- 最终 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `06b60bdd91cbdac23d4fefc51bf8376ac4082c2082e6350b787a21b936a3a804`；截图与操作记录见 [`evidence/01`](../evidence/01/device-smoke.md)。

## Validation

- 运行目录展示与布局编辑相关 JVM/Compose 测试。
- 运行 `:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 在至少一种小屏规格上 smoke warm / journal 的浅色与深色编辑页，记录顶栏、首个分类、删除分区和 Dock 截图。
- 运行 `git diff --check`，确认没有新增硬编码颜色、重复间距常量或 lint 警告。

## Documentation Gate

更新布局编辑设计与 UI PRD：明确当前“添加记录”是视觉权威、编辑态隐藏日期与主 tabs、且不重复“常用补充”。删除仍把最终编辑器描述成纯 Android 桌面图标墙的过时文案。
