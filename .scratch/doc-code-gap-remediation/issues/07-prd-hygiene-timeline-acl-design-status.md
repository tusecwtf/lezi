# 07 — PRD/design hygiene（时间轴文本、Record ACL、Status）

**What to build:** 文档 hygiene，不改业务行为代码（除已由其他票覆盖的部分）。

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] `docs/prd/ui.md`：时间轴 cell 写明 **Composer 排泄图标；时间轴为文本摘要**；备注/图以文本或打开编辑为准（本批不实现行内 thumbs）
- [x] `docs/prd/data-model.md` 权限表：成员「编辑/删任意记录」改为 **当前契约允许**（任成员 LWW），去掉「建议仅自己」歧义
- [x] design Status：`timeline-swipe` → Implemented；`qr-join` Must Implemented + 列开 Should；`layout-edit` 规范化
- [ ] 可选轻量：`docs/prd/tech.md` 补 `ACCESS_WIFI_STATE` / `ACCESS_COARSE_LOCATION`、onboarding/settings→sync 边、CareLog 写路径措辞、FileProvider `cache/camera`
