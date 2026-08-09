# 33 — 验收 delete、restore、race 与 ACL

**What to build:** 在主缝上证明 delete/edit、direct-base restore、detail 后新 branch race 与 author/Owner ACL 均 fail-safe 并最终收敛。

**Blocked by:** 32

**Status:** ready-for-agent

## Contract slice

Cases：delete/edit 两到达顺序；delete/delete；complete direct-base restore；missing bytes 拒绝；detail 后新 branch；author、Owner、other actor 三种 resolution。

## Implementation sequence

1. 运行固定 delete/restore fixtures 并核对 root/media/provenance。
2. 在 detail 与 submit 间注入新 branch，刷新后重选。
3. 运行三种 actor ACL case。
4. 所有 clients pull 并比较 stable deleted/root/media/version。

## Acceptance

- [ ] delete/edit 无静默删除/复活
- [ ] restore 只取 direct complete base，缺 bytes 拒绝
- [ ] stale race 不提交旧选择
- [ ] author/Owner allowed，other denied，最终收敛

## Validation

- [ ] isolated main-seam case table 通过
- [ ] server enforcement 与 UI affordance 结果一致

## Out of scope

不覆盖通用 N 方字段或媒体 upload faults。
