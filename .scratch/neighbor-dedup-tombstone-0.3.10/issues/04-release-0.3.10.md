# 04 — 0.3.10 发版与文档合同

**What to build:** 将 **01–03、05–06** 的行为作为 **0.3.10** 可发布产品交付：Android
versionName、单调 versionCode、lezi-sync 包版本与 health/app-update 元数据一致；产品/技术
合同写明近邻裁决、记录墓碑永胜、三数据页短暂浅同步 chrome、成员上次同步全角色可见；发版
门禁通过；在用户确认后的 NAS CD 中保留数据 bind、bootstrap secret 与 TLS 身份，并完成隔离
或联调冒烟证明家庭可见收敛与 chrome 合同。

**Blocked by:** 01 — 护理记录墓碑永胜；02 — 家庭近邻裁决；03 — 客户端收敛与近邻落选轻提示；
05 — 三页统一短暂浅同步 chrome；06 — 成员上次同步时间（全角色）

**Status:** ready-for-agent

- [ ] versionName `0.3.10` 与 lezi-sync 版本、签名 APK、`app-update` 的 version/sha256/signer 针一致；versionCode 相对 0.3.9 基线单调递增
- [ ] `docs/prd`（及 tech 版本表）与 ADR-0018/CONTEXT 对齐：record 墓碑永胜、近邻规则、触达分片、显式落选信号；去掉可 LWW restore 的过时合同
- [ ] 文档写明：数据页浅同步统一元素 + 下拉约 5s 缩入 top bar（失败不再内容区常驻）；成员 `last_sync_at` 全角色可读、设备明细 ACL 不变
- [ ] 若 wire 对旧客户端不兼容：签名 0.3.10 包进入可验证更新通道后，才抬 `min_supported`；旧端走既有强制升级路径
- [ ] Rust：`cargo fmt --check`、`test --locked`、`clippy -D warnings`；Android：相关 test/lint/可组装 release
- [ ] 发版冒烟：隔离实例上双端近邻收敛 + 墓碑永胜；三数据页下拉短暂 chrome；成员页全角色上次同步；不在家庭 NAS 上做证书破坏性实验
- [ ] NAS CD 仅在用户确认后执行；pre/post 证书指纹与 SPKI 相等；数据 bind 与 secret 合同保持
- [ ] `.scratch` tracker 状态可标 implementation-complete / 发版证据链接（提交 hash、包版本、健康协议）
