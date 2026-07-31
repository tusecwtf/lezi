# 01 — 已加入设备能检查是否有新版本

**What to build:** 已加入家庭且会话有效的用户，能在菜单关于区看到「版本 {versionName}」，点击后向家庭服务器检查更新，得到「已是最新」或「有可选更新」（确认层展示新版本信息）；未加入用户点击得到需先连接家庭的诚实说明。本票打通元数据发现路径，不要求完成 APK 下载与安装。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 服务端在部署可读位置加载更新元数据，已鉴权会话可 GET 到含 packageName、versionCode、versionName、minSupportedVersionCode、sha256（及可选 releaseNotes）的描述
- [ ] 无有效会话时不能拉取该元数据（与现有鉴权风格一致）
- [ ] 关于区展示当前 versionName（「版本 …」），不再展示「无广告 · 无内购 · 本地优先」；整块可点触发检查
- [ ] 已加入：检查结果为已最新或可选更新；可选更新出现确认层（新版本号、可选 notes；本票「立即更新」可先做到进入确认即可，完整下载安装见 02）
- [ ] 未加入：点击检查有诚实说明，不发起匿名更新请求
- [ ] 客户端以高位更新/同步端口暴露检查结果；有针对元数据检查的外部行为测试

## Comments

-
