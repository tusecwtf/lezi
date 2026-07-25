# Spec: 乐记 V2 交付

Contract classification: `historical-delivery-contract`  
Status: ready-for-agent  
Feature: v2-delivery  
Product: 乐记 (`com.lezi.babylog`)  
Prerequisite: **V1 完成**；**强烈建议 V1.5 完成**（汇总/成长/搜索不阻塞同步核心，但交付体验更完整）  
Source: docs/prd/ · docs/prd/data-model.md SyncPort · 2026-07-22  

---

## Problem Statement

V1/V1.5 解决了「本机记全、本机看懂」。家庭场景下，伴侣与祖父母需要 **同一份日志实时一致**，而不是口头交接或截图。同时用户需要 **PDF 留念**、**自定义记录项**、**辅食类与扩展测量**、**疫苗手记**、**简单日程提醒**。V1 的账户页仍是 Stub；SyncPort 为空实现。

---

## Solution

交付 **V2**：

1. **真同步**：实现 `SyncPort`；邀请码/QR 加入家庭；全量共享育儿数据；下拉刷新；双机新记录约 **60s 内**可见  
2. **本机设置不同步**；**不做** 部分字段共享、伴侣逐条推送、奶库  
3. **PDF 电子书**导出（系统分享，App 不长期存 PDF）  
4. **自定义项目 ≤10**（可改名，图标模板固定）  
5. **辅食 / 点心 / 饮料**记录类型  
6. **头围 / 胸围 / 足长**成长扩展  
7. **疫苗**手记（无姐妹 App）  
8. **日程/提醒**（可进家庭同步域）  

仍无广告/IAP。  

### Seams

| Seam | 职责 |
|------|------|
| **CareLog** | 继续作为本地记账与查询主口；写库后投递 Outbox |
| **SyncPort**（V2 主新增 seam） | pull/push/invite/join/leave/status；冲突 LWW + client_uuid 幂等 |
| **ExportPort** | 扩展 `exportPdf`；TXT 已有则复用 |

测试：SyncPort 用假后端或内存双端模拟；断言 B 端在 pull 后看到 A 的记录。不测具体 HTTP 库细节。

---

## User Stories

### 家庭同步

1. As a 管理员, I want 生成有时效的共享码/QR, so that 家人能加入  
2. As a 成员, I want 输入码或扫码加入家庭, so that 看到同一宝宝日志  
3. As a 照护者, I want 加入前看到「将共享全部育儿记录」明示, so that 知情同意  
4. As a 照护者, I want 我在本机新记的一条在对方设备约一分钟内出现, so that 交接靠谱  
5. As a 照护者, I want 下拉刷新加速同步, so that 不等后台  
6. As a 照护者, I want 无网时仍能记账并在恢复网络后上传, so that 医院电梯可用  
7. As a 管理员, I want 停止某成员共享或解散侧权限按 PRD, so that 可控  
8. As a 成员, I want 退出家庭, so that 不再接收更新  
9. As a 照护者, I want 主题/图标排序/深色/下次喂奶提醒仍只在本机, so that 个人偏好不打架  
10. As a 照护者, I want **不要**每条对方记录推送通知, so that 夜喂不被刷屏  
11. As a 照护者, I want 不能勾选「只共享部分类型」, so that 模型简单可预期（全量）  
12. As a 开发者, I want client_uuid 幂等与 tombstone 删除, so that 双端不炸  

### PDF

13. As a 照护者, I want 导出 PDF 电子书（封面+日志，可选图）, so that 留念或打印  
14. As a 照护者, I want 用系统分享 PDF, so that App 不长期堆文件  
15. As a 照护者, I want PDF 无水印付费墙, so that 干净  

### 自定义与类型扩展

16. As a 照护者, I want 最多 10 个自定义记录项并改名, so that 覆盖家庭特殊习惯  
17. As a 照护者, I want 记辅食/点心/饮料, so that 离乳阶段有处放  
18. As a 照护者, I want 记头围/胸围/足长并在成长相关展示, so that 体检数据齐全  
19. As a 照护者, I want 手记疫苗名称与日期, so that 不依赖外部 App  

### 日程

20. As a 照护者, I want 添加简单日程/提醒, so that 体检或喂药时间不忘  
21. As a 家庭成员, I want 日程随家庭同步（全量模型下）, so that 家人看到同一予定  
22. As a 照护者, I want 到点本机提醒, so that 实用  

### 通用

23. As a 照护者, I want V1/V1.5 能力不回退, so that 升级值得  
24. As a 照护者, I want 仍无广告与会员, so that 原则不变  
25. As a 开发者, I want 双机冒烟与单机回归清单, so that V2 可关门  

---

## Implementation Decisions

### 同步架构

```text
UI → CareLog → Room → Outbox → SyncPort.push
                ↑ pull / cursor 合并
```

- 同步域：Baby、Record、Media 元数据、CustomItem、CalendarEvent、Membership  
- 不同步：SettingsLocal、下次喂奶时刻、Widget 配置、（默认）主题展示偏好  
- 冲突：同 `client_uuid` 幂等；否则 `updated_at` LWW；删除 tombstone  
- 后端：自建（实现可选 Supabase/Firebase/REST+WS）；**非**原版 Piyo 后端；TLS  
- 邀请：短码 + 可选 QR；默认短时效（如 24h，可配）  
- 已有本地数据设备加入：二次确认后清空或拒绝（需产品文案，避免静默合并两套家庭）  

### PDF

- 系统 PdfDocument 或开源库；分享后可不持久化  

### 自定义

- CustomItemDef max 10；图标固定槽位；改名影响展示  

### 新 Record types

- `baby_food`, `snack`, `drink`, `head`, `chest`, `foot_size`, `vaccine`, `custom`  

### CalendarEvent

- title, start_at, remind_at?, baby_id, family_id  

### 权限

- INTERNET 用于同步  
- 通知用于日程与既有喂奶提醒  

### 不做

- 奶库、部分字段 ACL、伴侣逐条推送、广告 IAP、视频、保育端、手表/语音/音波、官方食材库、制本服务  

---

## Testing Decisions

- **SyncPort**：内存双客户端或 fake server；A 写 → push → B pull → B CareLog 可见；重复 push 幂等  
- **冲突**：同 uuid 不双份；LWW 取新  
- **SettingsLocal** 不同步：A 改深色，B 不变  
- **PDF/自定义/类型/日程**：CareLog 与导出用例单测 + UI 冒烟  
- **双机真机**：可选但 60s 验收至少一套集成环境  

---

## Out of Scope

- V3+：Wear、语音助手、音波体温、制度 CMS、视频  
- 商业化  
- 多语言（仍可仅中文）  

---

## Further Notes

- 票单：[ISSUES.md](./ISSUES.md)  
- V1 Stub 账户 UI 在 V2 接真实现，勿另起平行账户系统  
- 后端选型可在 01 票内锁定并写 ADR 到 `docs/adr/`（若仓库启用）  

---

## Comments

- 2026-07-22: to-spec + to-tickets 发布；双 seam CareLog + SyncPort  
> status: `historical-delivery-contract`  
> 本文只保留历史交付背景，不覆盖 `docs/prd/` 与
> `.scratch/offline-v2-conformance/spec.md` 的当前离线 APK 合同。
