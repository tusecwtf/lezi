# 暖芽 Brand Spec

“暖芽”是一套柔和但不幼稚的育儿记录界面：奶油底色降低压力，浅蓝与浅黄承担信息编码，小面积砖红只提示当下与异常。

## 核心 tokens

```css
:root {
  --bg: oklch(97.70% 0.0114 84.6);
  --surface: oklch(99.20% 0.0073 80.7);
  --fg: oklch(33.10% 0.0117 78.1);
  --muted: oklch(54.50% 0.0175 77);
  --border: oklch(90.42% 0.0201 77.3);
  --accent: oklch(56.95% 0.1326 32.5);
}
```

扩展语义色：睡眠 `oklch(92.89% 0.0185 232.5)`、喂养 `oklch(92.58% 0.0735 95.7)`、暖灰 `oklch(93.37% 0.0154 77.1)`、成功 `oklch(53.76% 0.0602 160.1)`。

## 字体

- Display：`"Songti SC", "STSong", "Noto Serif CJK SC", serif`
- Body：`"PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif`
- Mono：`"SFMono-Regular", "Roboto Mono", "Noto Sans Mono CJK SC", monospace`

## 设计姿态

- 首屏以记录与时间轴为中心，不使用欢迎语或营销叙事。
- 18–24px 圆角只用于有明确容器职责的卡片；列表优先用分隔线。
- 状态文案保持中性，不对照护者进行评判或催促。
- 图标使用 1.7px 圆角线性 SVG，始终与中文文字共同出现。
- 阴影只用于 FAB、底部抽屉与 toast 的层级分离。
