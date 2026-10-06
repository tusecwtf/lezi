# 02: 服务端——commit 后自动写 canonical 来源关系

**What to build:** 去掉自动路径白名单/同作者拒绝；commit 后扫邻域并写关系。

**Blocked by:** 01

**Status:** done

- [x] `suspected_duplicates` 全类型 + 名称/项目分片
- [x] `source_relations` 自动路径与 Owner resolve 允许同作者、全类型
- [x] causal commit 成功后自动收口；`mutation_id` 前缀 `auto-near-neighbor:`
- [x] pull summary 加性 `auto_aligned`

## Comments
