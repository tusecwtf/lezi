# 母婴 App 动画交互时长研究（2026-09-10）

问题：安卓母婴类 App 的动画过渡用什么时长/缓动最合适？乐记现状离最佳实践有多远？

## 一、权威时长标尺

### Material 3 官方 duration token（已在本地 material-1.12.0.aar `values.xml` 核验）

| 档位 | token | 毫秒 | 适用 |
|---|---|---|---|
| short | short1~4 | 50 / 100 / 150 / 200 | 小组件微变化：ripple、chips、选中态、tab 指示 |
| medium | medium1~4 | 250 / 300 / 350 / 400 | 元素进入/离开屏幕、内容切换 |
| long | long1~4 | 450 / 500 / 550 / 600 | 大面积展开/移动 |
| extra long | extraLong1~4 | 700 / 800 / 900 / 1000 | 全屏变换、页面级导航 |

来源：[M3 Easing & Duration](https://m3.material.io/styles/motion/easing-and-duration)（页面 JS 渲染，数值以本地 material-components-android 1.12.0 AAR 内 `m3_sys_motion_duration_*` 为准，两处一致）。

### Material 3 官方 easing token（同上 AAR 核验）

| token | 值 | 用途 |
|---|---|---|
| `emphasized_decelerate` | cubic-bezier(0.1, 0.7, 0.1, 1) | **入场**：快起步、长减速尾 |
| `emphasized_accelerate` | cubic-bezier(0.3, 0, 0.8, 0.2) | **出场**：慢起步、快离开 |
| `emphasized` | path 曲线（非简单贝塞尔） | 大型连续容器变换 |
| `legacy`（=M2 standard） | cubic-bezier(0.4, 0, 0.2, 1) | 小组件；**恰等于 Compose `FastOutSlowInEasing`** |
| `legacy_decelerate` / `legacy_accelerate` | (0,0,0.2,1) / (0.4,0,1,1) | 旧版入场/出场 |

注：m3.material.io 早先文档写过 decelerate=(0.05,0.7,0.1,1)、accelerate=(0.3,0,0.8,0.15)；
Android 实际发货的 AAR 值为 (0.1,0.7,0.1,1) / (0.3,0,0.8,0.2)。乐记取 AAR 值（与 M3
Android 组件行为一致）。

### HCI 研究共识（NN/g）

- [Executing UX Animations: Duration and Motion](https://www.nngroup.com/articles/animation-duration/)：
  大多数 UI 动画应落在 **100–500ms**；<100ms 感知不到，>500ms 感觉迟滞。
- [Animation for Attention and Comprehension](https://www.nngroup.com/articles/animation-usability/)：
  反馈须在动作后 **100ms 内开始**才被归因为因果（微反馈要快过 100ms 起步）。
- [The Role of Animation and Motion in UX](https://www.nngroup.com/articles/animation-purpose-ux/)：
  动画须不显眼、短、克制，只用于反馈/状态变化/导航隐喻。

### 平台参考

- [Apple HIG · Motion](https://developer.apple.com/design/human-interface-guidelines/motion)：
  原则型（优先系统动画、自定义须与系统一致），不发布固定毫秒——佐证「跟平台默认走」即可。
- Android 12+ 冷启动：系统一律显示启动窗（App 图标 + 背景色），不可关闭；
  [官方迁移指南](https://developer.android.com/develop/ui/views/launch/splash-screen/migrate)与社区共识
  （[Reddit r/androiddev](https://www.reddit.com/r/androiddev/comments/131k5kb/double_splash_screen_on_android_12_or_higher/)、
  [Ionic 论坛](https://forum.ionicframework.com/t/double-splash-screen-with-flash-in-between/187916)）：
  **防闪的唯一可靠做法 = 启动窗背景色与首帧内容背景色完全一致**，配合
  `core-splashscreen` 的 `setKeepOnScreenCondition` 把启动窗按到数据就绪再放行。

## 二、母婴场景的特殊约束

母婴/育儿记录 App 的使用情境（宝宝在抱、单手操作、夜间哄睡、老人带娃）对动效的推论：

1. **高频单手快记**（喂奶/换尿布一键记录）：微反馈必须快——按压、选中、tab 指示 100–150ms，
   且反馈须立即起步（NN/g 100ms 因果窗）。**不适合把微反馈放慢来显得"温柔"。**
2. **夜间/暗光哄睡查看**：冷启动与页面切换**绝不能闪白**——首帧颜色一致性的优先级高于
   任何装饰动画；深色模式是主场景不是附属。
3. **温和亲切的产品语气**（暖色模板、长辈模式）：体现在**页面级与装饰级**——入场 decelerate
   缓动、出场快、装饰循环（浮动/脉冲）用 1s+ 慢节奏；而不是把所有交互都放慢。
4. **长辈模式/可达性**：乐记已有 reduce-motion 归零策略（`ANIMATOR_DURATION_SCALE`），
   符合 [Apple Reduced Motion 评审标准](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria/)
   的同类要求，保持即可。

竞品参考（无公开动效规格，仅方向佐证）：[Nara Baby Tracker 案例](https://www.everydayindustries.com/casestudy/mobile-app-ui-design-case-study/)、
[BabyTracker "gentle app"](https://play.google.com/store/apps/details?id=com.nighp.babytracker_android&hl=en_US)、
[Dribbble 育儿动效方向](https://dribbble.com/shots/26556110-Parenting-App-Animation-Newborn-App-Design-Baby-Care-App-UI)——
共识是柔和缓动 + 克制的位移幅度，无「更慢=更温柔」的证据。

## 三、乐记动效规格表（研究落点）

对照 [inventory.md](inventory.md) 现状：

| 交互类型 | 推荐时长 / 缓动 | M3 对应 | 乐记现状 | 结论 |
|---|---|---|---|---|
| 微反馈（按压 scale、tab 指示、选色、swipe 回弹） | 100–150ms，legacy 曲线（FastOutSlowIn 即可） | short2~3 | `LeziMotion.Fast=150`，多数已接 token | ✅ 不动；个别显式 150 补迁 token（票 03） |
| 内容切换（AnimatedContent/Crossfade：顶栏、加载态、向导步骤） | 200ms | short4 | `Base=200` | ✅ 不动；游离 200/250 补迁（票 03） |
| 页面级进入（NavHost 全屏 push：timer/search/export/calendar） | **250–300ms + decelerate 入场** | medium1~2 | fade 150 + slide 200，默认曲线 | ⚠️ 偏快且曲线未分入出场（票 04） |
| 页面级退出 | ≤150ms fade + accelerate | short3 + accelerate | fade 150 ✅ 数值对 | ⚠️ 曲线待补（票 02→04） |
| 装饰循环（月亮帽 bob、wake pulse） | 1000–1400ms 慢节奏 | extraLong 档 | 1400/1000 | ✅ 保留并标注「装饰节奏」，不迁 token |
| 冷启动 | 启动窗背景=首帧背景；`Checking` 期间按住启动窗 | — | 白窗→spinner→硬切×3 | ❌ 票 01 修复 |
| reduce-motion | 系统缩放≤0 归零 | — | 已实现 | ✅ 保持，游离时长迁 token 后同样受管 |

**核心结论：LeziMotion 三档数值（150/200/300）恰为 M3 short3/short4/medium2，无需改数值、
不得另立第二套时长表（ui.md §2.1.1 既有政策）。差距在缓动语义（入场 decelerate/出场
accelerate 无 token）与页面级过渡档位（push 应升 medium 档），以及开屏首帧链路。**
