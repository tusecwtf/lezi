# Spec: 家庭局域网同步设备验收

Status: implementation-complete; Ticket 09 partial
Feature: home-lan-sync
Authority: [`docs/prd/sync-home-lan.md`](../../docs/prd/sync-home-lan.md)

Fresh-deployment override（2026-07-27）：只交付 current Android/NAS protocol，Android 与 Docker/NAS
均从空数据根部署。旧数据库升级、旧 NAS wire/capability/字段省略软兼容已经 superseded，不再是
Release blocker。current protocol 的 health、API、原子包、canonical ack、pre-join Record 回填、
写入后同版本重启持久化与前台双端收敛仍必须由最终候选验证。

Android `:sync`、家庭 UI、Outbox、媒体链路和 Rust `tools/lezi-sync` 已实现并有自动化覆盖。
本机 Docker 运行与双模拟器前台 formula/pee UI 交叉可见已验证；严格使用
`http://192.168.50.4:8765` 的服务端也已完成建家、邀请码加入、双向协议记录和头像 ACL。

剩余工作是 fresh-current 最终候选与目标环境中的设备级验收：相机扫码、日志图跨端 UI、蜂窝离家与回家冲刷、
伴侣通知观察、双端独立设置，以及物理 NAS/双真机部署路径。不得把协议测试或模拟器结果
写成这些设备场景已经通过。

唯一活动票：[09 — 双机前台同步验收](./issues/09-dual-device-foreground-acceptance.md)。
