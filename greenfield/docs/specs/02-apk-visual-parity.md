# Spec 02 — APK 设计一致（质感 · 元素 · 动画）

**Status:** **1.0.0 freeze (agent single-scene)** · code V0–V3 landed · dual-install baselines 2026-08-05 · full §2.3 blind chrome parity **deferred post-1.0.0**  
**Goal:** Greenfield APK 在**视觉质感、界面元素、动效语言**上与 0.3.x 达到「用户侧无感」级一致——**不是**功能 API 对齐，也**不是**后端数据迁移。  
**Authority:** `docs/prd/ui.md` · `docs/design/` · `CONTEXT.md` · 0.3.x `designsystem/` + `feature/*`（只读参照）· `greenfield/docs/uiux-parity-audit.md`  
**Out of scope:** Room/服务端迁移（见 [01-backend-data-migration.md](./01-backend-data-migration.md)）；厨房水槽测试搬迁；像素 CI 强制门禁（可用人工双装对照）。

> **1.0.0 固化口径（本文件 §2.4 / §10）：** 不以「未看包名完全分不出 0.3.x」为发版门闩；以**单场景主路径结构 + 写前确认**为冻结门闩。全量盲测 chrome 对齐为 1.0.0 之后的 polish。  
> **Issue tracker 发布件（skills / `to-spec`）：** [`.scratch/spec-02-apk-1.0-freeze/spec.md`](../../../.scratch/spec-02-apk-1.0-freeze/spec.md) — 本文件是工程对照权威，不是 local tracker 本体。  
> **多场景双装验收（post-1.0.0）：** [`.scratch/spec-02-dual-scene-review/`](../../../.scratch/spec-02-dual-scene-review/) · 21 竖切票。  
> **UI/UX 差异 inventory：** [`.scratch/spec-02-dual-scene-review/uiux-diff-spec.md`](../../../.scratch/spec-02-dual-scene-review/uiux-diff-spec.md) · 工程指针 [03-uiux-diff-inventory.md](./03-uiux-diff-inventory.md)。

---

## 1. Problem

Greenfield 已达到「产品级功能壳」：五 Tab、坞、三日轴、类型 Composer、全屏计时、账户栈等**能力在**。  
相对 0.3.x，用户仍能感到「换了 App」，主要差在：

| 维度 | 0.3.x | GF 现状（摘要） |
|------|--------|-----------------|
| 质感 | designsystem 密度/卡片/阴影/主题色贯穿 | `LeziDensity` + Material3，journal/warm 有差但未全表面 |
| 元素 | 类型 glyph 资产、圆盘调时、滑动行、满铺「更多」网格 | 汉字 glyph 圆、步进器、显式编辑/删除按钮、简化网格 |
| 动画 | `LeziMotion` Fast/Base/Emphasized + reduce-motion | 几乎无共享 motion token 接线 |

Spec 目标：**质感 + 元素 + 动画** 与旧版一致到「日常使用不察觉换壳」。

---

## 2. Definition of “一致”

### 2.1 必须一致（用户可感知、本 spec 验收）

1. **质感（Material / 模板语言）**  
   - warm：奶油底、8dp 卡片圆角、开敞间距。  
   - journal：紧凑分割、少卡片、顶栏/密度收紧。  
   - 深色：护眼、主路径对比足够。  
   - 宝宝主题色驱动顶栏强调 / 选中 / 主 CTA（journal 珊瑚仅无主题时的默认）。

2. **元素（结构与控件身份）**  
   - 记录页三层：日期栏 → 日汇总 chips → 三日轴 → 时间轴 → 底坞 4+更多。  
   - 时间轴行：类型标 · 时刻 · 摘要 · 相对时间 · 备注/照片；左滑编辑、右滑删除（或与 a11y 完全等价且视觉有绿/红意图）。  
   - Composer：类型专属字段 + **圆盘调时**（非仅 ±5 分按钮为主路径）。  
   - 计时：大双圆 L/R、上次侧、完成→确认单。  
   - 「更多」：四列可滚网格 ≥48dp 热区。  
   - 布局编辑：全屏、可拖拽重排（视觉拖动手柄/位移，不仅上/下移文案）。

3. **动画（时间语言）**  
   - 使用与 PRD 一致的三档：Fast 150 / Base 200 / Emphasized 300（ms）。  
   - 主导航 Tab、顶/底栏出现、布局编辑进出、引导步进、内容交叉淡入接 token。  
   - 系统「减少动态效果」时非必要动效 → 0ms，状态仍瞬间可读。

### 2.2 明确不要求

- 像素级 diff / 截图像素哈希强制 CI。  
- 逐控件与 0.3.x 同源 drawable 资源（可用等价自有资产，**气质**一致即可）。  
- 复制旧包名或强制覆盖安装（包策略另定）。

### 2.3 验收口令（完整无感 · post-1.0.0）

> 双装同宝宝空数据：记录首页静置 3 秒、打开配方奶 Composer、打开计时、打开布局编辑——  
> 评审者在 **未看包名** 时不应稳定分辨「哪边是 0.3.x」。

**Agent 对双装静帧结论（2026-08-05）：** 在当前 chrome 下，评审者**可以稳定分辨**（蓝顶栏+日汇总五格 vs 奶油底+三日轴；Material 线标 vs 汉字 glyph）。故 §2.3 **未通过**，不阻塞 1.0.0 冻结——见 §2.4。

### 2.4 1.0.0 单场景冻结门闩（agent 验收 · 已执行）

**只验一个场景即可固化**（产品确认：编排双端对齐时，单场景过关即冻 1.0.0 壳层）：

| 项 | 值 |
|----|-----|
| **场景 ID** | **S-freeze** |
| **路径** | 双装同机 · 空宝宝数据 → **记录首页 warm 静置** → 点坞 **配方奶** → **Composer 面板静置**（不要求写库成功截帧，但确认 CTA 必须可见且写前确认语义在） |
| **包** | GF `com.lezi.babylog.gf` **1.0.0** (`versionCode` 100) · legacy `com.lezi.babylog.debug`（0.3.x） |
| **证据** | `baselines/log-home-warm-{gf,legacy}.png` · `baselines/composer-formula-{gf,legacy}.png` · 可选 `motion-prototype/` 视频 |
| **评审者** | agent（实现侧自动化 + 双端静帧/动效对照）；非实现者盲测 **非** 1.0.0 门闩 |

**S-freeze 通过条件（必须全部为真）：**

1. **IA 同构（首页）：** 五 Tab（记录/汇总/成长/账户/菜单）+ 底坞四槽 +「更多」入口；空态可读（引导去坞）。  
2. **Composer 身份（配方奶）：** 面板标明配方奶；有**奶量**主控；有**记录时间**主控（圆盘或等价选择器路径）；有主 CTA 确认/保存；有取消/关闭且**不写库**。  
3. **写前确认：** 确认前不落护理写（confirm-before-write）；自动化不得跳过确认。  
4. **GF 元素落地（本场景相关）：** E1 圆盘调时在 Composer 主路径可见；E10 全宽（或等价主路径）确认底栏存在。  
5. **不要求：** 顶栏色/日汇总 chips/图标资产/Composer 快捷 ml chips 与 0.3.x 像素或布局一致。

**Agent 结论：** S-freeze **PASS** → 允许将当前 GF APK **固化为 1.0.0 设计门闩版本**（壳层 Spec 02 代码侧关闭；chrome residual 显式延期）。

---

## 3. Baselines & inventory

### 3.1 采集基线（P0）

| 表面 | 基线内容 | 存放 |
|------|----------|------|
| 记录首页 warm/light | 全屏 | `greenfield/docs/specs/baselines/log-home-warm.*` 或沿用 `.scratch/.../ui-baselines/` |
| 记录首页 journal/dark | 全屏 | 同上 |
| Composer 配方奶 / 睡眠 / 尿 | 面板 | |
| 计时全屏 | L/R 运行态 | |
| 汇总周图 / 成长曲线 | 有数据态 | |
| 账户总览 / 连接向导一步 | | |
| 布局编辑 | 拖拽中一帧 | |

**来源：** 0.3.x 真机/模拟器截图（票 37 意图）；GF 同场景对照。

### 3.2 元素差距清单（相对当前 GF）

| ID | 元素 | 0.3.x | GF 现状 | 目标 |
|----|------|-------|---------|------|
| E1 | 圆盘调时 | 主路径 | 分钟步进 | 恢复圆盘为默认；步进可作 a11y 辅 |
| E2 | 时间轴滑动 | 左编右删满铺色 | 按钮 | 滑动 + TalkBack 动作 |
| E3 | 类型图标 | 设计资产/glyph | 单字圆 | 图标集或等价矢量 + 类型色 |
| E4 | 日汇总 chips | 始终可点结构 | 有数据才出 | 对齐 PRD：无数据类不进空筛选，有结构占位策略二选一并锁 |
| E5 | 更多网格 | 四列底面板 | Dialog FlowRow | 底部 sheet 四列 |
| E6 | 布局拖拽 | 拖动手势 | 上/下移 | 长按拖排序 |
| E7 | 顶栏 | 昵称+日龄+主题 | 较简 | 日龄/主题色强调 |
| E8 | 相对时间 | 行内次级 | 部分缺 | 统一「N 分钟前」 |
| E9 | 计时大圆 | 显著 >48dp | 有但仍可加强 | TimerButton 规格对齐 PRD |
| E10 | 确认底栏 | 全宽固定保存 | Dialog 确认 | Composer 全宽底栏主按钮 |

### 3.3 质感差距清单

| ID | 项 | 目标 |
|----|-----|------|
| T1 | warm 表面色/elevation | 对齐 PRD 奶油底与卡片 elevation |
| T2 | journal 分割线/无卡片列表 | 时间轴 journal 模式无 elevation 卡片 |
| T3 | 字体阶梯 | 顶栏昵称强调、时刻中等、相对时间次级灰 |
| T4 | 触控 | ≥48dp；坞 ≥56；计时圆显著更大 |
| T5 | 宝宝主题色 | 顶栏/选中/主 CTA 读当前宝宝色 |

### 3.4 动画差距清单

| ID | 项 | 目标 |
|----|-----|------|
| A1 | `LeziMotion` token 落地 | 常量化 150/200/300 |
| A2 | Tab 内容切换 | Base 交叉淡入 |
| A3 | 布局编辑进入/退出 | Emphasized |
| A4 | Composer/计时 sheet | Base；reduce-motion→0 |
| A5 | 滑动行露出 | Fast 跟手 + 满行程提交 |

---

## 4. Implementation approach

### 4.1 设计系统模块（推荐）

在 `greenfield/android` 增加 **`ui-kit` 或 `app/ui/theme` 加深**，避免散落 magic number：

```text
ui/theme/
  LeziTokens.kt      # Density / Motion / Spacing（已有则扩展）
  LeziColors.kt      # warm/journal light/dark + baby accent
  Type.kt            # 字体阶梯
  Motion.kt          # leziMotionMillis(reduce)
components/
  TimeDial.kt        # 圆盘调时
  SwipeTimelineRow.kt
  MoreSheet.kt       # 四列更多
  Dock.kt            # 已有 QuickDock 升级
  DayAxis.kt         # 已有 ThreeDayAxis 抛光
```

**禁止：** 为动画/UI 复制一套业务写路径；写仍只走 `CareService` / `FamilyService`。

### 4.2 参照策略

- **读** monorepo 0.3.x `designsystem` / `feature/log` 行为与 token（若工作区无旧代码，以 PRD + 基线截图为准）。  
- **不** copy-paste 旧模块 Gradle 依赖进 GF。  
- 类型图标：优先矢量/字体图标集；商标级小鸡素材不使用（PRD）。

### 4.3 分阶段交付

| Phase | 范围 | 完成定义 |
|-------|------|----------|
| **V0** | Token 全接：Density/Motion/Color 主路径 | 记录/汇总/成长/账户消费同一 token；reduce-motion 接线 |
| **V1** | 记录主路径元素 | E1 圆盘、E2 滑动行、E5 更多 sheet、E7/E8 顶栏与相对时间 |
| **V2** | 计时 + Composer 底栏 | E9/E10；确认单动效 A4 |
| **V3** | 布局拖拽 + journal 质感 | E6、T2；布局 Emphasized 动效 |
| **V4** | 双装评审 | §2.3 口令；基线 diff 笔记入库 |

可与 Spec 01 并行；**不阻塞** 数据迁移工程。

---

## 5. Acceptance criteria

### 5.1 1.0.0 freeze（现行门闩）

1. **S-freeze（§2.4）PASS** — agent 双装对照记录在 `baselines/review-notes.md`。  
2. **Token：** shell 转场引用 `LeziMotion`；三档 150/200/300 常量化。  
3. **元素：** E1/E2/E5/E6/E7/E8/E9/E10 有代码 + 截图/视频证据；E3/E4 允许 residual（见 §10）。  
4. **回归：** confirm-before-write；`Uiux*` 单测绿；`assembleDebug` 成功。  
5. **证据目录：** `greenfield/docs/specs/baselines/`（静帧）；可选 `motion-prototype/`（动效，不入库强制）。

### 5.2 完整无感（post-1.0.0 · 原 §2.3）

1. **质感：** warm vs journal 在记录页可盲分；深色可完成夜喂主路径。  
2. **双装盲测：** 至少 1 名非实现者按 §2.3 记录结论（通过 / 列出 residual）。  
3. **动画：** Tab / Composer / 布局可感知且 reduce-motion 可关掉（视频对照可辅助）。

---

## 6. Test & review strategy

| 层 | 内容 |
|----|------|
| 单元 | Motion 时长表、Density forTemplate、滑动阈值映射（offset→edit/delete）纯函数 |
| 结构 | 源码存在 `TimeDial`/`SwipeTimelineRow`/`LeziMotion` 消费点 |
| 人工 L4 | 双装截图 + §2.3 评审 |
| 不做 | 像素 CI 强制；搬迁 0.3.x UI 巨测 |

---

## 7. Risks

| 风险 | 缓解 |
|------|------|
| 「一样好看」主观 | 用 §2.3 盲测 + E/T/A 清单关闭 |
| 范围膨胀到重写全部 feature | 锁 V0–V4；先记录主路径 |
| 无 0.3.x 源码工作区 | PRD + 基线截图为权威 |
| 动效影响性能 | reduce-motion；列表 item 动画克制 |

---

## 8. Deliverables checklist

| 交付物 | 路径建议 |
|--------|----------|
| 本 spec | `greenfield/docs/specs/02-apk-visual-parity.md` |
| Token/组件实现 | `greenfield/android/app/.../ui/theme` + `components` |
| 基线与对照 | `greenfield/docs/specs/baselines/` |
| 评审记录 | `greenfield/docs/specs/baselines/review-notes.md` |

---

## 9. Relationship to Spec 01

```text
无感替换 = Spec 01（数据还在） ∧ Spec 02（长得像、摸着像） ∧ 运维切换（endpoint/会话）
```

- 只做 02：新用户觉得「像乐记」，老用户仍丢历史。  
- 只做 01：历史在，仍一眼看出换壳。  
- 本 spec **不**依赖迁移完成即可开工（可用空数据双装评质感）。  
- **1.0.0：** Spec 02 以 §2.4 冻结；完整「摸着像到分不出」仍属 §2.3 / §5.2。

---

## 10. 1.0.0 freeze record（agent · 2026-08-05）

### 10.1 版本钉

| 项 | 值 |
|----|-----|
| Product | `ProductVersion.NAME = 1.0.0` · `CODE = 100` · `APPLICATION_ID = com.lezi.babylog.gf` |
| 设备 | AVD `lezi_api35` · `emulator-5554` · 1080×2400 |
| 对照包 | `com.lezi.babylog.debug`（0.3.x） |
| 静帧脚本 | `greenfield/scripts/capture-uiux-baselines.sh` |
| 动效 prototype | `greenfield/scripts/prototype-capture-motion-clips.sh`（可选，非门闩） |

### 10.2 S-freeze 对照表（agent）

| 检查项 | GF | Legacy | 1.0.0 |
|--------|----|--------|-------|
| 五 Tab + 坞 4+更多 | 是 | 是 | **pass** |
| 空态引导 | 「还没有记录·点底坞开始」 | 「还没有记录」+ 快捷入口文案 | **pass**（语义） |
| 配方奶 Composer | 有 | 有 | **pass** |
| 奶量主控 | ±5 ml 步进 | ±5 + 快捷 chips + 任意输入 | **pass**（身份；密度 residual） |
| 时间主控 | **圆盘 + ±5 分**（E1） | 「选择时间」行 | **pass**（GF 达标；形态不同 accepted） |
| 主 CTA / 取消 | 全宽「保存」+「取消」 | 「确认记录」+「取消」 | **pass**（confirm-before-write） |
| 顶栏 chrome | 奶油 + 昵称主题色 + 三日轴 | 蓝顶栏 + 日龄 + 五格汇总 | **waived** residual |
| 类型图标 | 汉字 glyph 圆 | Material 线标 | **waived** E3 residual |
| 日汇总 chips | 无数据不占五格 | 空数据五格 0 | **waived** E4 residual |

### 10.3 显式 residual（1.0.0 接受 · 不挡发版）

1. 顶栏语言（蓝条/日龄 vs 奶油/三日轴）。  
2. E3 图标气质（非同源 drawable）。  
3. E4 日汇总占位策略（PRD 空类不进筛选 vs 0.3.x 五格）。  
4. Composer 快捷奶量 chips / 冲调量 / 耗时字段密度。  
5. §2.3 全场景盲测（计时 + 布局 + 静置 3s 分不出包名）。  
6. 像素 CI / 动效毫秒级对齐。

### 10.4 固化声明

- **Spec 02 对 1.0.0：** 以 **S-freeze PASS** 关闭「必须再改 UI 才能叫 1.0.0」的工程门闩。  
- **不声称：** 已达用户侧「完全无感换壳」。  
- **后续：** residual → 独立 polish / 再开 §2.3 非实现者盲测；数据无感仍看 Spec 01。  
- **证据入口：** [baselines/review-notes.md](./baselines/review-notes.md) · [baselines/README.md](./baselines/README.md)。
