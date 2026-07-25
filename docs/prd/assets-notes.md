# 排泄图标资源说明

> 对应 PRD §3.1。实现前可自绘或换合规素材；**禁止**使用 PiyoLog / ぴよログ 官方美术。

## APK 实际实现

正式 APK（0.2.3）不打包 `design/assets/` 设计源。排泄分档由
`designsystem/RecordVisuals.kt` 的 Compose Canvas 组件直接绘制：

- `LeziPeeAmountMark`
- `LeziStoolAmountMark`
- `LeziStoolConsistencyMark`
- `LeziStoolColorMark`

这些组件用于 Composer 与时间轴业务标记，能随主题与深色模式调整。下列 SVG
文件名只保留为设计探索命名约定；未跟踪的 `design/assets/` 不得称为已交付资源。

## 设计源命名约定

| 键 | 文件名建议 | 含义 |
|----|------------|------|
| pee_1 | `pee_amount_1.svg` | 尿尿 · 小 |
| pee_2 | `pee_amount_2.svg` | 尿尿 · 中（默认） |
| pee_3 | `pee_amount_3.svg` | 尿尿 · 大 |
| stool_a_1…4 | `stool_amount_*.svg` | 便量 一点→偏多 |
| stool_c_1…4 | `stool_consistency_*.svg` | 软硬 稀→偏硬 |
| stool_col_0…7 | `stool_color_*.svg` | 色 未选→黑（色相为主） |

目录建议：`design/assets/excretion/`。该目录属于设计工作区，不是 APK 交付证据。

## 风格

- 圆形/圆角底，线宽统一，深色模式反色或描边可辨
- 尺寸：选择器约 48dp 热区；时间轴缩略约 16–20dp
- 色档以 **填充色** 区分，可叠加简单外形避免纯色块无障碍问题
- UI 线标：`viewBox="0 0 24 24"`，`stroke-width="1.8"`，`stroke-linecap/join="round"`，随 `currentColor`
- 语义底色使用 Compose 主题 token；网页 token 只作为视觉参考。
- 卡通稿可带浅填充，但正式列表/快捷入口优先单色线标

## 来源策略

1. **优先自绘**（Compose Vector / SVG）— 品牌一致、无授权纠纷
2. 或 **CC0 / 明确可商用** 图标集，改色后纳入仓库
3. 在本文件追加「采用素材 + 许可证 URL」一行即可

## 原型

网页或 Open Design 原型可用 emoji/色块占位；正式 APK 以 Compose 实现为准。
