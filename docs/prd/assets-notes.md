# 排泄图标资源说明

> 对应 PRD §3.1。实现前可自绘或换合规素材；**禁止**使用 PiyoLog / ぴよログ 官方美术。

## 需要的资源

| 键 | 文件名建议 | 含义 |
|----|------------|------|
| pee_1 | `pee_amount_1.svg` | 尿尿 · 小 |
| pee_2 | `pee_amount_2.svg` | 尿尿 · 中（默认） |
| pee_3 | `pee_amount_3.svg` | 尿尿 · 大 |
| stool_a_1…4 | `stool_amount_*.svg` | 便量 一点→偏多 |
| stool_c_1…4 | `stool_consistency_*.svg` | 软硬 稀→偏硬 |
| stool_col_0…7 | `stool_color_*.svg` | 色 未选→黑（色相为主） |

目录建议：`design/assets/excretion/`（设计源）→ 接入时拷入 `designsystem` / `app` 资源树。

## 已采用（自绘 · 2026-07-23）

| 键 | 文件 | 说明 |
|----|------|------|
| poop | `design/assets/excretion/poop.svg` | 类型主图标 · 24×24 · `currentColor` · stroke 1.8 |
| poop_cartoon | `design/assets/excretion/poop-cartoon.svg` | 卡通主视觉 · 圆形暖底 · 非 UI 线标 |
| stool_a_1…4 | `stool_amount_1.svg` … `_4.svg` | 便量 一点→偏多 |
| stool_c_1…4 | `stool_consistency_1.svg` … `_4.svg` | 软硬 稀→偏硬 |
| stool_col_0…7 | `stool_color_0.svg` … `_7.svg` | 色 未选→黑（填色 + 统一外形） |
| preview | `preview.html` | 本地对照页 |

来源：**全部自绘**，无第三方素材；**未**使用 PiyoLog / ぴよログ 官方美术。

补充试验：`poop-cartoon-gen.png` / `poop-cartoon-gen-96.png` 由 Codex imagegen 生成并本地去底，仅作装饰向候选，不属于上述自绘 UI 线标；未使用第三方品牌资产。

## 风格

- 圆形/圆角底，线宽统一，深色模式反色或描边可辨  
- 尺寸：选择器约 48dp 热区；时间轴缩略约 16–20dp  
- 色档以 **填充色** 区分，可叠加简单外形避免纯色块无障碍问题  
- UI 线标：`viewBox="0 0 24 24"`，`stroke-width="1.8"`，`stroke-linecap/join="round"`，随 `currentColor`
- 语义底色用 token `--poop: oklch(68% 0.13 82)`（见 `design/template-v2/styles.css`）
- 卡通稿可带浅填充，但正式列表/快捷入口优先单色线标

## 来源策略

1. **优先自绘**（Compose Vector / SVG）— 品牌一致、无授权纠纷  
2. 或 **CC0 / 明确可商用** 图标集，改色后纳入仓库  
3. 在本文件追加「采用素材 + 许可证 URL」一行即可  

## 原型

网页原型 `docs/prd/prototype-v1/` 可用 emoji/色块占位，正式 App 换矢量图。
