# 03 — 服务器认证的稳定家庭成员身份

**What to build:** 为每个家庭 membership 提供由 NAS 生成、不可变且可安全引用的公开身份，使后续计划作者权限、自定义定义权限和履行冲突都能依赖服务器事实而非客户端自报。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 新建家庭和加入家庭时由服务器创建稳定 membership 身份；token 更新、地址变化或重新启动不得改变该身份
- [x] 会话与安全成员投影能向当前家庭客户端提供所需最小身份键、角色和 is-self 关系
- [x] membership 身份不暴露 token、token hash、设备秘密或可用于冒充的凭据
- [x] 服务端从认证 principal 决定写入者和角色，拒绝客户端在 payload 中冒充管理员或其他成员
- [x] 旧家庭数据可加法升级并为既有 membership 分配稳定身份，不要求重新建家
- [x] 客户端本地能把当前会话和成员列表稳定映射到 membership 身份
- [x] 服务端与客户端契约测试覆盖创建、加入、重启、角色和伪造拒绝
