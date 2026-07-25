# 乐记第二套模板 · 视觉系统

本模板采用“珊瑚顶栏 + 浅灰分区 + 高密度日志轨道”的实用记录系统：品牌仍为乐记，仅参考真实育儿日志产品的布局密度、信息层级与图表形态。

## 来源与取色

- 参考图：`store/official-main.png`、`settings-widget/dark-mode-home.png`、`charts/summary-week-all.png`、`charts/growth-curve-jp.png`。
- 本地像素聚类观察到的代表色：浅色表面 `#F9F7F8`、珊瑚 `#EA7C8F`、深色表面 `#262727`、深色珊瑚 `#EC7887`。
- 禁止复用参考产品名称、商标、吉祥物、官方插画和图标素材；交付中的图标均为乐记自绘 SVG/CSS。

## 核心 OKLch tokens

```css
:root {
  --bg: oklch(96.5% 0.003 308.4);
  --surface: oklch(97.8% 0.002 345.2);
  --fg: oklch(33.4% 0.007 286);
  --muted: oklch(56.4% 0.010 308.3);
  --border: oklch(87.3% 0.004 337.4);
  --accent: oklch(71.4% 0.136 10.2);
}
```

深色模式沿用观察到的 `#262727` 主表面，并把珊瑚提高到 `oklch(70.9% 0.143 13.3)` 以维持可辨性。完整 light/dark、间距、圆角和图表色见 `tokens.json`。

## 字体

- Display：`"Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", sans-serif`
- Body：`"Noto Sans CJK SC", "Source Han Sans SC", "Microsoft YaHei", sans-serif`
- Mono：`"Roboto Mono", "SFMono-Regular", "Cascadia Mono", monospace`

字体全部使用本机回退，不加载外链。标题与正文保持无衬线的工具型一致性；时间、计数、奶量和图表轴统一等宽。

## 视觉姿态

1. 记录首页是日志工具，不是内容卡片流：左侧 0–24h 轨道与右侧明细共享行高。
2. 分区主要靠浅灰底、白色表面和 1px 分隔线，不使用奶油色大卡片或装饰渐变。
3. 珊瑚粉每屏只承担当前 Tab / 主按钮等关键状态，记录类型使用独立语义色。
4. 周汇总采用七列时间网格、事件点与睡眠色块；成长采用细网格、P3/P50/P97 参考线和实测点。
5. 圆角克制：列表和图表 0–8px，弹层 18px，圆形只用于记录图标与计时主按钮。
