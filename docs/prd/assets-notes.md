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

目录建议：`designsystem/src/.../excretion/` 或 `app/src/main/assets/excretion/`。

## 风格

- 圆形/圆角底，线宽统一，深色模式反色或描边可辨  
- 尺寸：选择器约 48dp 热区；时间轴缩略约 16–20dp  
- 色档以 **填充色** 区分，可叠加简单外形避免纯色块无障碍问题  

## 来源策略

1. **优先自绘**（Compose Vector / SVG）— 品牌一致、无授权纠纷  
2. 或 **CC0 / 明确可商用** 图标集，改色后纳入仓库  
3. 在本文件追加「采用素材 + 许可证 URL」一行即可  

## 原型

网页原型 `docs/prd/prototype-v1/` 可用 emoji/色块占位，正式 App 换矢量图。
