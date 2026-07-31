# 03 — 媒体文件与 publications 映射

**What to build:** 在本机迁移结果中，把拷出的 `media/` 与 v3 `media_publications`（及 bundle 媒体关联）变成当前服务能服务的布局与行；坏文件或不可映射 publication **整次失败**。完成后，测试夹具上「记录带图」在迁移后仍能按当前 pull/媒体路径读到字节。

**Blocked by:** 02 — 本机离线库迁移器 v3→当前

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 迁移输入含 media 目录时，输出 data 布局满足当前服务媒体路径约定
- [ ] 可映射的 publications / bundle 媒体关联进入新库且与文件一致
- [ ] 缺失文件、哈希/大小不一致、或退役且不可映射的 source 形态 → 迁移失败并报告，不产出可拷回结果
- [ ] 夹具测试：迁移后能解析到至少一条带媒体的权威记录路径

## Out of scope

- 在线边同步边迁
- 重新编码/压缩媒体
