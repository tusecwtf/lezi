# 10 — 其余 V1 类型 + 备注/日记照片

**Parent:** [.scratch/v1-delivery/spec.md](../spec.md)

## What to build

用户可记录 PRD V1 类型表中 **02–05 未覆盖**的类型：体温、挤奶、喂挤出乳、备注、日记、洗澡、散步、咳嗽/发疹/呕吐/受伤、用药、就医、其他；备注带**本机历史候选**；日记/备注可附**本机照片**（Photo Picker）。**无**视频、**无**云上传要求、**无**挤奶库存。

## Blocked by

07 — 编辑与删除记录（复杂类型经编辑页补全；快记可先插后编）

## Status

ready-for-agent

## 交付物

### 用户可见

- 图标网格（在未隐藏时）含中文入口，且均可落到时间轴：
  - `temperature` 体温  
  - `pump_express` 挤奶、`pumped_feed` 喂挤出乳（ml 事件）  
  - `memo` 备注、`diary` 日记  
  - `bath` 洗澡、`walk` 散步  
  - `cough` / `rash` / `vomit` / `injury`  
  - `medicine` 用药、`hospital` 就医、`other` 其他  
- 体温：手输 ℃；可选：月龄 &lt; 3 个月且 ≥38℃ 显示就医建议（可关 + 免责声明）
- 挤奶/喂挤出乳：ml；**无**库存余额 UI/表
- 备注：历史输入候选（本机曾用备注）
- 日记/备注：系统 Photo Picker 选图，本机 `MediaAsset.local_uri`；时间轴可显示缩略图或「有图」标记
- 用药：名称/剂量；就医/其他：备注或自由文本

### 工程产物

- 全部上列 `Record.type` 可经 CareLog 写入/读回
- `pumped_feed` 计入日汇总 **feedMl**（与 formula/nursing.ml 同口径）；`pump_express` **不计入**喂养量（仅事件）——与 PRD「喂挤出乳计入喂养量 / 挤奶无库存」一致
- MediaAsset 仅图片 mime；压缩建议长边 1200–2000（非 must 数值，须本机可读）
- 备注候选：本机查询历史 `note`/`memo` 去重最近 N 条
- 不申请麦克风/定位；不强制 INTERNET

## 验收标准

### Must（可测 / 自动化优先）

- [x] **类型全覆盖**：对上列每一 type 至少写入 1 条，timeline 能按 type 滤出，`type` 字符串与 PRD key 一致
- [x] **体温**：`celsius=37.5` 读回 **== 37.5**（或小数误差 &lt; 0.01）
- [x] **喂挤出乳计入奶量**：当日仅一条 pumped_feed 120 → feedMl **== 120**
- [x] **挤奶不计入奶量**：当日仅一条 pump_express 100 → feedMl **== 0**（事件仍在 timeline）
- [x] **无库存表**：代码/ schema **无**「奶库余额」实体或运行时余额字段（审查：无 inventory/stock 业务表）
- [x] **用药字段**：name/dose 读回一致
- [x] **照片关联**：diary 挂 1 张 MediaAsset → 按 record_id 查询 uri 非空、mime 为 image/*
- [x] **备注候选**：写入 note「布洛芬」后，候选列表包含「布洛芬」

### 可人工冒烟

- [x] 网格点体温录入 38.2，时间轴显示体温摘要
- [x] 低月龄宝宝 + ≥38℃ 时出现可关闭的就医建议与免责；关闭设置后不再打扰
- [x] 记挤奶与喂挤出乳各一笔，日汇总只增加喂挤出乳的 ml
- [x] 日记选一张图保存，再打开记录能看到图（本机）；无上传进度/登录云盘
- [x] 备注输入时能选到历史候选
- [x] 洗澡/散步/症状等一键类型 ≤2 击上屏
- [x] 无视频选择主路径；中文类型名完整

## 不在本票范围

- `height`/`weight` 与成长曲线（V1.5）
- `baby_food`/`snack`/`drink`、头围胸围足长、疫苗、custom（V2）
- 视频日记、云相册同步
- 官方食材库
- 搜索（V1.5）
