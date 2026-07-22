# Agent A：PiyoLog 功能全量清单

> 团队角色：功能调研（一手优先）  
> 产品：PiyoLog / ぴよログ / Piyo日志 · 包名 `jp.co.sakabou.piyolog` · iOS `id1252857347`  
> 日期：2026-07-22  
> 标注：`[官方]` / `[商店]` / `[FAQ]` / `[官方X]` / `[二手]` / `[未确认]`

---

## 0. 产品定位（一句话）

面向新生儿/婴幼儿家庭的**一站式育儿记录 + 实时家庭共享** App：单手快速记账、日时间条/周汇总/成长曲线、PDF 留存。核心功能免费，Premium 主打去广告与更多主题/媒体质量。

- 官网：https://www.piyolog.com/
- Play：https://play.google.com/store/apps/details?id=jp.co.sakabou.piyolog
- App Store：https://apps.apple.com/us/app/piyolog-baby-feeding-tracker/id1252857347
- FAQ：https://piyolog-official.blogspot.com/2020/12/q.html

---

## 1. 官方记录类型（商店完整列表）

| # | 日文 | 中文 | 英文 | 一键? | 关键字段 |
|---|------|------|------|-------|----------|
| 1 | 母乳 | 母乳 | Nursing | ✓/计时 | 左右分钟、顺序、可选 ml |
| 2 | ミルク | 配方奶 | Formula | ✓ | ml、可选制作量/耗时 |
| 3 | 搾母乳 | 喂挤出乳 | Pumped breast milk | ✓ | ml |
| 4 | 搾乳 | 挤奶 | Pump express | ✓ | ml **[二手区分]** |
| 5 | 離乳食 | 辅食 | Baby food | ✓ | 备注/食材/照片 |
| 6 | おやつ | 零食 | Snacks | ✓ | 备注/照片 |
| 7 | のみもの | 饮料 | Drink | ✓ | ml（可进まとめ配置）[FAQ] |
| 8 | おしっこ | 尿 | Pee | ✓ | — |
| 9 | うんち | 便 | Poop | ✓ | **量·かたさ·色** [官方记法] |
| 10 | 両方 | 尿+便 | Both | ✓ | — |
| 11 | 睡眠 | 睡眠 | Sleep | 寝る/起きる | 起止、异常(!) |
| 12 | 体温 | 体温 | Temperature | 表单 | ℃、音波计可选 |
| 13 | 身長 | 身高 | Height | 表单 | cm |
| 14 | 体重 | 体重 | Weight | 表单 | g/kg |
| 15 | 頭囲/胸囲 | 头围/胸围 | Head/Chest | 表单 | cm |
| 16 | 足のサイズ | 足长 | Foot size | 表单 | v9.2+ [商店 What's New] |
| 17 | お風呂 | 洗澡 | Baths | ✓ | — |
| 18 | さんぽ | 散步 | Walks | ✓ | 可选时长 |
| 19 | せき | 咳嗽 | Coughing | ✓ | 备注 |
| 20 | 発疹 | 皮疹 | Rashes | ✓ | 备注/照片 |
| 21 | 嘔吐 | 呕吐 | Vomiting | ✓ | 备注/照片 |
| 22 | けが | 外伤 | Injuries | ✓ | 备注 |
| 23 | くすり | 用药 | Medicine | 表单 | 药名/剂量 |
| 24 | 病院 | 医院 | Hospitals | 表单 | 名称/事由 |
| 25 | 予防接種 | 疫苗 | Vaccine | 表单 | + 姐妹 App |
| 26 | その他/自由記述 | 其他 | Other | 表单 | 文本 |
| 27 | メモ | 轻备注 | Memo | 表单 | 文本/照片 |
| 28 | 育児日記 | 育儿日记 | Diary | 表单 | 长文+照片(1200px) |
| 29 | カスタム×10 | 自定义 | Custom | ✓ | 仅改名称 [FAQ] |

来源（记录主表）：  
[App Store JP](https://apps.apple.com/jp/app/id1252857347) · [Play EN](https://play.google.com/store/apps/details?id=jp.co.sakabou.piyolog&hl=en) · 便便记法 https://www.piyolog.com/app/piyolog/notation_specifications.html · 足尺寸 What's New

### 1.1 便便结构化枚举（官方记法）

| 字段 | 值 |
|------|-----|
| 量 | 1ちょこっと / 2少なめ / 3ふつう / 4多め |
| かたさ | 1下痢 / 2やわらかめ / 3ふつう / 4かため |
| 色 | 0色なし / 1白 / 2黄 / 3橙 / 4茶 / 5緑 / 6赤 / 7黒 |

来源：https://www.piyolog.com/app/piyolog/notation_specifications.html · 官方 X 色字段 https://x.com/piyolog_app/status/1780795923163120022

---

## 2. 特色能力

### 2.1 授乳タイマー
- 左右分别开始/停止；「◀最後▶」提示上次侧 [FAQ]
- 关 App 仍可跑、到点提醒 [官网]
- 记录时刻：开始 or 结束时间 [FAQ]
- 分段闹钟 1/3/5/7/10 分 **[二手评测]**
- App 内声控「左/右/ストップ」**仅 iOS** [PR TIMES]
- 助手语音 **不能** 操作计时器 [FAQ]

### 2.2 タイムバー + 日集计
- 一日横向色带概览 [Play 特色]
- 自动合计授乳时间/奶量/睡眠/排泄次数 [Play]

### 2.3 まとめ（周图）
- 食事 / 睡眠 / 排泄 / 体温 [Play]
- 食事量默认：授乳+ミルク+搾母乳 [FAQ]
- 食事まとめ表示内容可配 [设定文二手]
- 先週との比較 [设定文二手]
- 週のはじまり [官方 X + 设定文]

### 2.4 成長曲線
- 身高体重（+头围胸围足长）
- 修正月龄（需预产期；橙点线）[FAQ + 官方 X]
- 数据源可选日本(2023 令和5)/WHO [官方 X]
- 多胎用图依赖日本数据源 [官方 X]
- 年龄段 1/2/4/12 岁 [官方 X]

### 2.5 家庭共享
- ぴよログ ID + 共有用コード / QR [FAQ]
- 人数无硬上限 [BabyTech]
- 全量共享、不可部分隐藏 [FAQ]
- 伴侣新记录无推送 [FAQ]
- 不共享：主题/排序/暗色/通知 [二手交叉]
- 管理者可移交权限 **[二手矩阵]**

### 2.6 ぴよサポ（保育共享）
- 日记完全隐藏；可只读；可隐藏过去记录 [官网 piyosup]
- 多家庭、人数无上限 [官网]

### 2.7 通知
- 下次授乳/ミルク间隔通知；ピヨピヨ 铃声 [官网 + 二手]
- 各机独立设置，不随共享同步 [FAQ/二手]

### 2.8 导出
- PDF 电子书（封面/记录/まとめ/成长/离乳食/封底）[官方博客]
- TXT 文本导出 [FAQ]
- 制本导流 [官网]

### 2.9 Widget / Wear
- Widget：食事/睡眠/排泄最近 + 快捷图标 [官方博客]
- Apple Watch / Wear OS 记录与计时 [商店]

### 2.10 语音助手
- Siri / Alexa / Google 助手录入记录 [官网/商店]
- 不等于声控计时器

### 2.11 食材リスト
- ~250 食材；○△×；好き/普通/苦手/アレルギー [官方博客/PR]

### 2.12 搜索 / 日历
- 记录+日记搜索；结果可 PDF [官方 X]
- 日期跳转月历 [官方博客]
- v9 予定/リマインダー日历（可共享）[官方 X]

### 2.13 多宝宝 / 自定义 / 主题
- 多宝宝；主题色防误记
- 自定义项最多 10，图标不可改 [FAQ]
- 暗色模式；主题色/图标风格；Premium 扩展

### 2.14 Premium
- 去广告、更多主题、高画质、视频等
- 参考价 JP ¥400/月 · ¥3,800/年；US $3.49/$34.99 **[商店列表，勿硬编码]**

### 2.15 其他
- 音波体温计 欧姆龙 MC-6800B [官方博客]
- 子育て支援 Tab v9.1+ [商店/官方 X]
- 姐妹 App：予防接種、陣痛タイマー [官网]

---

## 3. 功能条数统计

| 类别 | 约数 |
|------|------|
| 记录类型 | 29 |
| 系统能力（计时/共享/导出/图…） | 20+ |
| 设置项（粗分） | 25+ |

---

## 4. 一手源列表

1. https://www.piyolog.com/
2. https://play.google.com/store/apps/details?id=jp.co.sakabou.piyolog
3. https://apps.apple.com/us/app/piyolog-baby-feeding-tracker/id1252857347
4. https://apps.apple.com/jp/app/id1252857347
5. https://piyolog-official.blogspot.com/2020/12/q.html
6. https://www.piyolog.com/app/piyolog/notation_specifications.html
7. https://www.sakabou.co.jp/app/piyolog/privacy_en.html
8. https://x.com/piyolog_app
9. https://babytech.jp/en/2021/04/piyolog/
10. https://prtimes.jp/main/html/rd/p/000000013.000019025.html
11. https://www.piyolog.com/app/piyosup/piyosup.html
