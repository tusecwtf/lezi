# 02 — 家庭近邻裁决（触达分片 + 同事务落选 + 显式信号）

**What to build:** 两名（及以上）不同 membership 的家人，在同一宝宝、精确同一白名单 `RecordType`、主时间相差不超过三十分钟内各自记下的 live 护理记录，在家庭服务器权威 atomic commit 成功后收敛为组内仅一条 live；落选为近邻落选墓碑，与 commit 同一数据库事务写入。采纳顺序为：裁决时当前 Owner 作者 → 最早主时间 → `client_uuid` 字典序。同 membership（含多设备）窗内多条保留。回包携带本轮显式近邻落选 `client_uuid` 集合。只重算本轮触达的 `(宝宝, 类型)` 时间邻域，禁止每 push 全库扫描。

**Blocked by:** 01 — 护理记录墓碑永胜

**Status:** ready-for-agent

- [ ] 白名单类型 + 跨 membership + `|Δtimestamp|≤30min`（含边界）构成近邻；精确类型，非子类互并
- [ ] 连通分量组内至多一条 live；落选软删 tombstone，不合并备注/照片进胜者
- [ ] 采纳序：当前 Owner 作者优先 → 最早 timestamp → 更小 `client_uuid`
- [ ] 同 membership 窗内多条均保持 live（连喂/连记豁免）
- [ ] 无有效作者 membership 的行不进入近邻组
- [ ] 履行写出的白名单记录同等参与；落选不回滚计划完成态或履行候选证据
- [ ] 胜者再被软删后，落选不翻案；落选后窗内再新建可再次落选
- [ ] 落选与触发 commit 同事务；权威图在成功 commit 后不暴露双 live 近邻中间态
- [ ] 仅触达分片 + 主时间邻域重算；无白名单 record live 变更的 commit 跳过近邻
- [ ] 本轮显式 neighbor-loser 信号列出落选 uuid；非启发式
- [ ] 非白名单类型（sleep、成长、diary 等）行为与升级前一致
- [ ] 主验收缝：隔离服务器 atomic commit/Store 测试覆盖上述不变量（含三人连通分量、边界时刻）
