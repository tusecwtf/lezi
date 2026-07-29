# 03 — 全屏照片预览单源

**Parent:** [../spec.md](../spec.md)

**What to build:** 记录 Composer 与冲突审计/日历等路径打开多图预览时，使用同一全屏预览体验：黑底、可横滑分页、失败占位、关闭与无障碍描述。解码与 OOM/失败处理只维护一处（ADR-0003 公共附件 chrome 单源）。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M  
**Theme:** C（R5）  
**Seams:** 公共记录照片预览 chrome

## Acceptance criteria

- [ ] 至少两条原并行预览路径改为同一预览组件/入口
- [ ] 多图横滑、关闭、无法解码时的用户可见失败态行为一致
- [ ] 无第二套复制粘贴的 `Dialog + HorizontalPager + BitmapFactory.decodeFile` 生产实现
- [ ] 相关 feature 模块测试或截图级静态调用检查通过；预览失败不崩溃进程

## Out of scope

- 改上传/原子包协议
- 相机启动样板统一（审查 P2，非本票）
