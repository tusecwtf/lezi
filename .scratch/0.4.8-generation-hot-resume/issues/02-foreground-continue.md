# 02: 前台自动续完 full resync

**What to build:** 副本失败不进 `InvalidInput`；full resync 每页 checkpoint；前台未收敛静默再入队 Foreground。零进度熔断。不做后台轮询。

**Status:** done

- [x] `FamilySyncErrorProductCopyTest` 副本文案不含称呼/草稿
- [x] full resync 中途失败 cursor > 0
- [x] Port：`SyncTookTooLong` 有进度则 Success + 再入队，不进 Error
