# 排泄图标资源说明

> 对应 PRD §3.1。实现前可自绘或换合规素材；**禁止**使用 PiyoLog / ぴよログ 官方美术。

## APK 实际实现

正式 APK（0.2.4）的排泄分档由 `designsystem/RecordVisuals.kt` 的
Compose Canvas 组件直接绘制：

- `LeziPeeAmountMark`
- `LeziStoolAmountMark`
- `LeziStoolConsistencyMark`
- `LeziStoolColorMark`

这些组件用于 Composer 与时间轴业务标记，能随主题与深色模式调整。仓库不再
保留独立 SVG 设计源，APK 以 Compose 实现为唯一权威来源。

## 风格

- 圆形/圆角底，线宽统一，深色模式反色或描边可辨
- 尺寸：选择器约 48dp 热区；时间轴缩略约 16–20dp
- 色档以 **填充色** 区分，可叠加简单外形避免纯色块无障碍问题
- 语义底色使用 Compose 主题 token；网页 token 只作为视觉参考。
- 卡通稿可带浅填充，但正式列表/快捷入口优先单色线标

## 来源策略

1. **优先自绘 Compose Canvas / Vector** — 品牌一致、无授权纠纷
2. 或使用 **CC0 / 明确可商用** 图标集，纳入 APK 资源时同步记录许可证
