# 18 — 建立 Android 不可变媒体 spool

**What to build:** 冻结发表时只读一次来源 URI，原子复制到应用私有 spool，并持久化 mutation-bound manifest。

**Blocked by:** 10、17

**Status:** ready-for-agent

## Contract slice

Pending/branched manifest 绝不按时间删除；durable terminal settlement 立即 eligible。每个 spool 由独立 sidecar journal 绑定 `mutation ID + media UUID/slot + digest + length + canonical metadata`；媒体文件本体不加前缀，上传原始 bytes。Cold start 用 sidecar 补建完整 manifest。多媒体 mutation 只有全部 slots promote 且 Room manifest 原子提交后才可发表；容量压力只暂停新发表。

## Implementation sequence

1. 顺序读取来源并同步计算 digest/length 到 temp。
2. fsync/atomic promote 每个 media 与 sidecar，全部 slots 完成后在 Room 原子持久 group manifest。
3. upload/retry 只打开 immutable spool。
4. 启动时先把可证明属于 pending mutation 的无 reference 文件重新绑定，再回收其余 orphan。

## Acceptance

- [ ] 来源每 frozen mutation 只消费一次
- [ ] URI 变化/消失/失权后 retry bytes 不变
- [ ] multi-media partial group 不可发表；crash 后可从 sidecars 完整重建 manifest
- [ ] 容量不足 fail closed 且保留既有证据

## Validation

- [ ] source/digest/crash/restart/orphan tests 通过
- [ ] Android storage permission device test 通过

## Out of scope

不决定 server retention 或 terminal settlement。
