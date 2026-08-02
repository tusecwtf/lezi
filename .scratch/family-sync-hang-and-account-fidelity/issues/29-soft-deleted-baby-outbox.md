# 29 — Soft-deleted baby must not brick outbox push

**What to build:** After a baby is soft-deleted, remaining dirty records/care plans for that baby still push (or are intentionally retired) without throwing and aborting the entire outbox loop. Capture and push use the same baby visibility rules (including deleted parents when product keeps historical facts).

**Blocked by:** None.

**Status:** ready-for-agent

- [ ] Soft-delete baby with dirty child records does not hard-fail push with “本地宝宝档案不存在”.
- [ ] Other family outbox roots continue to push in the same cycle.
- [ ] JVM regression for tombstoned baby + dirty record package.
