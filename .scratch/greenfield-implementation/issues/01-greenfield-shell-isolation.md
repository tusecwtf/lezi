# 01 — 绿场空壳、隔离与基础门禁

**What to build:** 本机可安装绿场 APK 并探活独立同步服务：与旧栈硬隔离、固定端口与数据根，具备最小测试门禁样例。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 存在独立 greenfield/android 构建入口（根 settings 不 include 绿场模块）
- [ ] 存在独立 greenfield/sync-server，本机 HTTPS 18765 返回健康/就绪
- [ ] applicationId 为 com.lezi.babylog.gf，可与现网 App 并立安装
- [ ] 数据根使用 gitignored 的 greenfield/.data（或等价本机路径）
- [ ] 绿场构建不 project/path 依赖旧 :app/:domain/:sync/:feature/:core/:designsystem/tools/lezi-sync
- [ ] 根工程与生产 deploy 路径不引用绿场产物
- [ ] 默认不指向家庭 NAS；Android 与 cargo 最小 L1/测试样例可通过
