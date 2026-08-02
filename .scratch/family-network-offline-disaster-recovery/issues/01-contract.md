# 01 — 固化产品与架构合同

**Status:** complete

## Acceptance criteria

- [x] `CONTEXT.md` 定义「离线可用状态」「设备灾难恢复批次」「家庭网络设置」。
- [x] 同步/UI/技术/数据 PRD 与 README 删除“不提供网络设置/客户端恢复”的旧边界。
- [x] ADR 明确只允许旧 Owner → 空服务器，并取代相关旧边界；两个 configured 家庭不合并。
- [x] Room v24、本地数据契约 v1、服务端当前 schema 不变。
