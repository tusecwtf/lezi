# 乐记 APK 图标候选（100 张 · imagegen）

生成：2026-07-25  
产品：乐记 CareLog（珊瑚 `#EA7C8F` / journal 壳层 / 圆标「乐」）  
工具：Grok Imagine `image_gen` · 1:1 · 约 1024×1024 JPEG  
目录体积：约 11MB  

**未接入** `ic_launcher`；选定后再做 adaptive 资源与矢量字固化。

---

## Android 桌面裁剪约束（21–100 强制写入 prompt）

从第 **21** 张起，prompt 统一要求：

1. **适配常见 launcher 蒙版**：圆形 / 圆角方 / squircle（含各 OEM 硬裁）。
2. **主体落在中心约 66% safe zone**（偏严时可 50–60%），四周留空。
3. **禁止**关键笔画/符号贴边，避免圆裁切掉「乐」字角、奶瓶嘴、月亮尖等。
4. **禁止**角落小字、「乐记」水印、状态栏 UI。
5. 构图 **居中**，单主体优先。

第 **01–20** 为早期探索，**未**强制 safe zone；评审 launcher 时优先看 **21+** 与下方推荐。

---

## 推荐入围（安全区 + 品牌贴合）

| 优先 | 文件 | 说明 |
|-----:|------|------|
| 1 | `99-safe-coral-le-padded.jpg` | 珊瑚底 + 白「乐」+ 大留白，圆裁友好 |
| 2 | `100-safe-cream-le-padded.jpg` | 奶油底 + 珊瑚「乐」，浅色桌面友好 |
| 3 | `02-le-soft-squircle.jpg` | 早期最佳圆角方字标 |
| 4 | `21-safe-le-coral-disc.jpg` | safe 版圆盘「乐」 |
| 5 | `32-safe-soft3d-le.jpg` / `13-illus-soft-3d.jpg` | 软 3D 质感 |
| 6 | `72-safe-le-extra-margin.jpg` | 额外 margin 字标 |
| 备选隐喻 | `25-safe-notebook` · `28-safe-bottle` · `29-safe-moon` · `06-mark-notebook` | 手记/喂养/睡眠 |

---

## 分档索引（1–100）

### A. 字标「乐」（贴近现状）

| # | 文件 | 备注 |
|--:|------|------|
| 01 | `01-le-coral-disc.jpg` | 珊瑚圆 + 白乐 |
| 02 | `02-le-soft-squircle.jpg` | 圆角方 + 奶油边 |
| 03 | `03-le-cream-coral.jpg` | 奶油底 + 珊瑚乐 |
| 04 | `04-le-dark-night.jpg` | 深色；**字形可能繁体/变形** |
| 05 | `05-le-gradient-bloom.jpg` | 渐变发光字 |
| 13 | `13-illus-soft-3d.jpg` | 软 3D |
| 15 | `15-illus-ink-seal.jpg` | 印章 |
| 16 | `16-glyph-only-bold.jpg` | 超大字；**字形风险** |
| 17 | `17-glyph-duo-tone.jpg` | 双色 |
| 21–24 | `21-safe-le-coral-disc` … `24-safe-le-dark` | **safe zone** 字标系 |
| 32 | `32-safe-soft3d-le.jpg` | safe 软 3D |
| 33–36 | paper-cut / seal / leaf / clock | 字 + 装饰（safe） |
| 38–44 | duo-tone / bold / mint / sky / peach / clay / outline | 色板变体（safe） |
| 72–78 | extra-margin / isometric / neon / glass / watercolor / pixel / origami | 风格实验（safe） |
| 80 | `80-safe-ribbon-badge-le.jpg` | 徽章 |
| 84 | `84-safe-wreath-le.jpg` | 花环 + 乐 |
| 87–88 | rainbow / hex | 乐 + 图案 |
| 96–100 | concentric / house / cream-badge / **padded 字标** | 收官 safe 字标 |

### B. 能力隐喻（无字或弱字）

| 主题 | 编号示例 |
|------|----------|
| 手记 / 列表 | 06, 25, 55, 71 |
| 24h / 时钟 | 07, 26, 19, 36, 66, 91 |
| 成长 / 叶芽 / 心 | 08, 27, 18, 51, 61 |
| 喂养 / 奶瓶 / 滴 | 09, 28, 45, 63, 93 |
| 睡眠 / 月 | 10, 29, 53, 70, 94 |
| 家庭 / 点 / 家 | 11, 20, 30, 37, 97 |
| 汇总 / 柱 | 12, 31 |
| 测量 | 47, 62, 64 |
| 计时 | 65, 66 |
| 隐私 / 本机 | 60, 86 |
| 日程 / 提醒 | 57, 58 |
| 其它工具 | 68 搜索, 69 设置, 67 导出, 90 指南针 |

### C. 探索 / 气质

平衡块、线团、音符、信封、山丘、无限环、锚等：`79–93` 一带。

---

## 已知问题

1. **中文「乐」不稳**（尤其 04、16 与部分暗色稿）：量产建议 **SVG/矢量「乐」叠底图**。
2. 早期 01–20 部分有 **角落水印/贴边**，launcher 圆裁可能伤主体。
3. 隐喻稿（奶瓶/月/图表）品牌辨识弱，适合作 **功能入口** 而非主图标。
4. 格式为 JPEG；上架前转 **PNG**，并按 adaptive 导出 foreground。

---

## Prompt 模板（后续加图可复用）

```text
Production Android app icon for 乐记 (Chinese baby care daily log).
[SUBJECT / STYLE / PALETTE coral #EA7C8F, cream, soft pink].
Android adaptive launcher constraints: design must survive circular,
rounded-square, and squircle crops used by common Android desktops;
keep the entire primary mark strictly inside the center 66% safe zone
with generous empty margin so nothing important is hard-cropped;
no edge text, no corner labels, no status bar UI. Centered, 1:1.
```

---

## 下一步

1. 从推荐入围中定 **1 主 + 1 备**。  
2. 矢量固化「乐」字 + 选中底图合成 foreground。  
3. 写入 `mipmap-anydpi-v26` 与 densified PNG。  
4. 真机/模拟器验证：圆形 / 圆角 / 方形 launcher 蒙版。

---

## 文件列表

共 **100** 个 `NN-*.jpg`（`01`–`100` 连续无缺）+ 本 `README.md`。  
完整文件名见目录 `ls design/assets/app-icon-candidates/`。
