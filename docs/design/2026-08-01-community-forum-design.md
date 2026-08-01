# 家庭社区论坛 — 技术设计文档

> **状态**：Draft  
> **创建日期**：2026-08-01  
> **关联**：新功能模块 `feature:community`，扩展 `sync` 模块  
> **前提**：现有 `SyncPort` + `lezi-sync` NAS 同步架构，ADR-0011

---

## 1. 目标与范围

### 1.1 产品目标

在乐记 App 内提供家庭私有社区论坛，支持：

- **发帖**：标题、正文（Markdown 富文本）、分类、最多 9 张图片附件
- **评论**：一级平铺评论（不做嵌套回复树）
- **表情回应**：6 种预设 emoji 反应（❤️ 👍 😂 😮 😢 👶）
- **分类**：家庭管理员预设的分类标签（如「日常」「提问」「分享」「求助」）
- **排序**：最新回复优先（默认）、最新发帖优先、最热（回应+评论数）
- **通知**：新帖/新评论的家庭内推送通知（可关闭）

### 1.2 不做的事

- 不做公开/跨家庭论坛
- 不做私信/DM
- 不做嵌套回复树（评论只有一级）
- 不做富文本编辑器（使用纯文本 + 简单 Markdown 子集）
- 不做匿名发帖
- 不做用户等级/积分/徽章
- 不做帖子置顶/精华（V1 不做，架构预留）
- 不做搜索帖子（V1 不做，架构预留 `PostEntity` 的 `title` + `body` 全文索引）

### 1.3 交付分期

| 期 | 范围 | 依赖 |
|----|------|------|
| V1 | 发帖/评论/回应/分类，家庭内同步，离线可用 | 无 |
| V1.5 | 通知推送（新帖/新评论提醒） | lezi-sync WebSocket 或 polling |
| V2 | 帖子搜索、图片懒加载优化、帖子置顶 | 全量同步完成 |

---

## 2. 模块架构

### 2.1 新增模块

```text
:feature:community          # 社区 UI（Compose screens + ViewModel）
:core:model                 # 扩展：CommunityPost, CommunityComment, CommunityReaction, CommunityCategory
:core:database              # 扩展：PostEntity, CommentEntity, ReactionEntity, CategoryEntity + DAOs
:domain                     # 扩展：CommunityPostCoordinator, CommunityCommentCoordinator
:sync                       # 扩展：SyncPort 新增 community 方法，CommunitySyncEngine
```

### 2.2 依赖方向

```text
:feature:community → :domain → :core
                          ↓
                        :sync    # community 写入后触发同步
```

遵循现有架构原则：`feature:community` **不依赖**其他 feature 模块。`domain → sync` 是已有的有意边（见 tech.md §2）。

### 2.3 新增文件清单

```
feature/community/
├── build.gradle.kts
├── consumer-rules.pro
└── src/
    ├── main/kotlin/com/lezi/babylog/feature/community/
    │   ├── CommunityScreen.kt              # 社区主页（帖子列表）
    │   ├── CommunityPostDetailScreen.kt    # 帖子详情 + 评论
    │   ├── CommunityPostComposerScreen.kt  # 发帖/编辑
    │   ├── CommunityCommentSheet.kt        # 评论输入 BottomSheet
    │   ├── CommunityViewModel.kt           # 社区主页 ViewModel
    │   ├── CommunityPostDetailViewModel.kt # 帖子详情 ViewModel
    │   ├── CommunityPostComposerViewModel.kt # 发帖 ViewModel
    │   ├── CommunityModels.kt              # UI state models
    │   └── CommunityNavigation.kt          # Navigation route definitions
    └── test/kotlin/com/lezi/babylog/feature/community/
        └── CommunityViewModelTest.kt

core/model/src/main/kotlin/com/lezi/babylog/core/model/
├── CommunityPost.kt           # 帖子领域模型
├── CommunityComment.kt        # 评论领域模型
├── CommunityReaction.kt       # 回应领域模型
├── CommunityCategory.kt       # 分类领域模型
└── CommunitySortMode.kt       # 排序枚举

core/database/src/main/kotlin/com/lezi/babylog/core/database/
├── PostEntity.kt              # Room entity
├── CommentEntity.kt           # Room entity
├── ReactionEntity.kt          # Room entity
├── CategoryEntity.kt          # Room entity
├── PostDao.kt                 # Room DAO
├── CommentDao.kt              # Room DAO
├── ReactionDao.kt             # Room DAO
├── CategoryDao.kt             # Room DAO
└── CommunityMediaRefEntity.kt # 帖子图片关联（复用 MediaAsset 模式）

domain/src/main/kotlin/com/lezi/babylog/domain/
├── CommunityPostCoordinator.kt    # 帖子 CRUD + 分类
├── CommunityCommentCoordinator.kt # 评论 CRUD
└── CommunityReactionCoordinator.kt # 回应 CRUD

sync/src/main/kotlin/com/lezi/babylog/sync/
├── CommunitySyncEngine.kt     # 帖子/评论/回应的同步引擎
└── SyncPort.kt                # 扩展：新增 community 方法

tools/lezi-sync/src/
├── community.rs               # 帖子/评论/回应 API handlers
├── community_store.rs         # SQLite 存储层
└── model.rs                   # 扩展：wire models
```

---

## 3. 数据模型

### 3.1 Room Entities

#### PostEntity

```kotlin
@Entity(
    tableName = "community_post",
    indices = [
        Index("family_id", "created_at"),
        Index("client_uuid", unique = true),
        Index("category_id"),
    ]
)
data class PostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,              // 跨设备同步键，UNIQUE
    val familyId: String,                // 所属家庭
    val authorMembershipId: String,      // 发帖者 membership ID
    val title: String,                   // 帖子标题，必填，≤200 字符
    val body: String,                    // 正文，Markdown 子集，≤10000 字符
    val categoryId: Long?,               // 分类 ID，可空（未分类）
    val replyCount: Int = 0,             // 评论数缓存（denormalized，由 sync 维护）
    val reactionCount: Int = 0,          // 回应数缓存（denormalized）
    val lastReplyAt: Long?,              // 最新评论时间（排序用，denormalized）
    val isPinned: Boolean = false,       // 置顶（V2 预留，默认 false）
    val isDeleted: Boolean = false,      // 软删
    val createdAt: Long,                 // 发帖时间（毫秒 epoch）
    val updatedAt: Long,                 // LWW
    val deletedAt: Long? = null,         // 软删时间
    val syncDirty: Boolean = true,       // 需同步标记
    val familyPublishedUpdatedAt: Long? = null, // 根发布回 Receipt
)
```

#### CommentEntity

```kotlin
@Entity(
    tableName = "community_comment",
    indices = [
        Index("post_client_uuid"),
        Index("client_uuid", unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["client_uuid"],
            childColumns = ["post_client_uuid"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class CommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,              // 跨设备同步键
    val postClientUuid: String,          // 所属帖子
    val authorMembershipId: String,      // 评论者 membership ID
    val body: String,                    // 评论内容，≤2000 字符
    val isDeleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncDirty: Boolean = true,
)
```

#### ReactionEntity

```kotlin
@Entity(
    tableName = "community_reaction",
    indices = [
        Index("post_client_uuid"),
        Index("comment_client_uuid"),
        Index("membership_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["client_uuid"],
            childColumns = ["post_client_uuid"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postClientUuid: String,          // 所属帖子
    val commentClientUuid: String? = null, // 所属评论（null = 帖子回应）
    val membershipId: String,            // 回应者 membership ID
    val emoji: String,                   // emoji 标识：heart/thumbsup/laugh/surprise/cry/baby
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,         // null = active, 非 null = tombstone
    val syncDirty: Boolean = true,
)
```

**唯一约束**：`(post_client_uuid, comment_client_uuid, membership_id, emoji)` — 同一人对同一目标的同一 emoji 只能有一条 active 行。删除 = tombstone（`deletedAt` 非 null）。

#### CategoryEntity

```kotlin
@Entity(
    tableName = "community_category",
    indices = [
        Index("family_id"),
        Index("client_uuid", unique = true),
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val familyId: String,
    val name: String,                    // 分类名，≤20 字符
    val sortOrder: Int = 0,              // 排序序号
    val isDeleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncDirty: Boolean = true,
)
```

### 3.2 帖子图片关联

复用现有 `MediaAsset` 模式，新增 `kind = "community_post"`：

```kotlin
// MediaAsset.kind 扩展
// 当前：log | avatar
// 新增：community_post

@Entity(
    tableName = "community_post_media",
    indices = [
        Index("post_client_uuid"),
        Index("client_uuid", unique = true),
    ]
)
data class CommunityPostMediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientUuid: String,
    val postClientUuid: String,          // 所属帖子
    val localUri: String,                // 本机私有路径
    val remoteUri: String? = null,       // 服务端已上传标记
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val byteSize: Long = 0,
    val sortOrder: Int = 0,              // 图片顺序
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncDirty: Boolean = true,
)
```

### 3.3 领域模型

```kotlin
data class CommunityPost(
    val clientUuid: String,
    val title: String,
    val body: String,
    val categoryId: Long?,
    val categoryName: String?,           // JOIN 查询结果
    val authorMembershipId: String,
    val authorDisplayName: String?,      // JOIN membership 结果
    val replyCount: Int,
    val reactionCount: Int,
    val lastReplyAt: Long?,
    val myReactions: Set<String>,        // 当前用户已回应的 emoji 集合
    val mediaCount: Int,                 // 图片数量
    val mediaThumbs: List<String>,       // 缩略图本地路径（前 3 张）
    val isPinned: Boolean,
    val isEditable: Boolean,             // 当前 membership 是否可编辑
    val isDeletable: Boolean,            // 当前 membership 是否可删除
    val createdAt: Long,
    val updatedAt: Long,
)

data class CommunityComment(
    val clientUuid: String,
    val postClientUuid: String,
    val body: String,
    val authorMembershipId: String,
    val authorDisplayName: String?,
    val myReactions: Set<String>,
    val isEditable: Boolean,
    val isDeletable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CommunityReaction(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)

data class CommunityCategory(
    val id: Long,
    val clientUuid: String,
    val name: String,
    val sortOrder: Int,
    val postCount: Int,                  // JOIN 查询结果
)
```

---

## 4. Wire Protocol（lezi-sync 扩展）

### 4.1 新增实体类型

在现有 `SyncEntity.type` 枚举中新增：

| type | 说明 |
|------|------|
| `community_post` | 帖子 |
| `community_comment` | 评论 |
| `community_reaction` | 回应 |
| `community_category` | 分类 |
| `community_post_media` | 帖子图片 |

### 4.2 Wire Payload 格式

#### community_post

```json
{
  "type": "community_post",
  "client_uuid": "uuid",
  "title": "宝宝今天第一次笑了",
  "body": "今天下午喂奶的时候...",
  "category_client_uuid": "category-uuid-or-null",
  "author_membership_id": "membership-uuid",
  "reply_count": 3,
  "reaction_count": 5,
  "last_reply_at": 1753504800000,
  "is_pinned": false,
  "created_at": 1753504800000,
  "updated_at": 1753504800000
}
```

#### community_comment

```json
{
  "type": "community_comment",
  "client_uuid": "uuid",
  "post_client_uuid": "post-uuid",
  "author_membership_id": "membership-uuid",
  "body": "太可爱了！",
  "created_at": 1753504900000,
  "updated_at": 1753504900000
}
```

#### community_reaction

```json
{
  "type": "community_reaction",
  "client_uuid": "uuid",
  "post_client_uuid": "post-uuid",
  "comment_client_uuid": null,
  "membership_id": "membership-uuid",
  "emoji": "heart",
  "created_at": 1753504900000
}
```

#### community_category

```json
{
  "type": "community_category",
  "client_uuid": "uuid",
  "name": "日常",
  "sort_order": 0,
  "created_at": 1753504800000,
  "updated_at": 1753504800000
}
```

### 4.3 同步策略

复用现有 Outbox push/pull 模式：

1. **本地写入** → Room 写入 + `syncDirty = true` → Outbox 入队
2. **前台 push** → Outbox 行序列化为 `SyncEntity` → atomic bundle commit
3. **前台 pull** → 接收 `SyncEntity` 列表 → LWW apply 到 Room
4. **denormalized 字段**（`replyCount`, `reactionCount`, `lastReplyAt`）由服务端在 commit 时计算并返回，客户端合并

### 4.4 服务端 API（lezi-sync Rust）

```
POST   /v1/community/posts              # 创建帖子（atomic bundle）
GET    /v1/community/posts?cursor=N     # 拉取帖子列表（分页）
GET    /v1/community/posts/:uuid        # 获取单个帖子
PUT    /v1/community/posts/:uuid        # 更新帖子
DELETE /v1/community/posts/:uuid        # 删除帖子（软删）

POST   /v1/community/posts/:uuid/comments     # 创建评论
GET    /v1/community/posts/:uuid/comments     # 获取评论列表
DELETE /v1/community/comments/:uuid           # 删除评论

POST   /v1/community/posts/:uuid/reactions     # 添加回应
DELETE /v1/community/reactions/:uuid           # 移除回应

GET    /v1/community/categories                # 获取分类列表
POST   /v1/community/categories                # 创建分类（Owner only）
PUT    /v1/community/categories/:uuid          # 更新分类
DELETE /v1/community/categories/:uuid          # 删除分类

POST   /v1/community/posts/:uuid/media         # 上传帖子图片
GET    /v1/community/media/:uuid               # 下载帖子图片
```

所有 API 遵循现有鉴权模式：Bearer token + `X-Lezi-Client-Version-Code` header。

### 4.5 权限矩阵

| 操作 | Owner | Member |
|------|-------|--------|
| 发帖 | ✓ | ✓ |
| 编辑帖子 | 全部 | 仅自己的 |
| 删除帖子 | 全部 | 仅自己的 |
| 评论 | ✓ | ✓ |
| 编辑评论 | 全部 | 仅自己的 |
| 删除评论 | 全部 | 仅自己的 |
| 回应 | ✓ | ✓ |
| 管理分类 | ✓ | ×（只读） |
| 置顶帖子 | V2 | × |

---

## 5. Domain Layer

### 5.1 CommunityPostCoordinator

```kotlin
@Singleton
class CommunityPostCoordinator @Inject constructor(
    private val postDao: PostDao,
    private val categoryDao: CategoryDao,
    private val postMediaDao: CommunityPostMediaDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val syncPort: SyncPort,       // 写入后触发同步
    private val clock: PolicyClock,
) {
    /** 创建帖子，写入 Room + Outbox，触发同步 */
    suspend fun createPost(
        title: String,
        body: String,
        categoryId: Long?,
        mediaRefs: List<MediaRef>,        // 本地图片引用
    ): Result<CommunityPost>

    /** 编辑帖子（仅作者） */
    suspend fun updatePost(
        postClientUuid: String,
        title: String,
        body: String,
        categoryId: Long?,
    ): Result<Unit>

    /** 删除帖子（软删 + Outbox） */
    suspend fun deletePost(postClientUuid: String): Result<Unit>

    /** 观察帖子列表（按排序模式） */
    fun observePosts(
        categoryId: Long? = null,
        sortMode: CommunitySortMode = CommunitySortMode.LATEST_REPLY,
    ): Flow<List<CommunityPost>>

    /** 观察单个帖子详情 */
    fun observePost(postClientUuid: String): Flow<CommunityPost?>

    /** 观察帖子图片 */
    fun observePostMedia(postClientUuid: String): Flow<List<PostMediaRef>>
}
```

### 5.2 CommunityCommentCoordinator

```kotlin
@Singleton
class CommunityCommentCoordinator @Inject constructor(
    private val commentDao: CommentDao,
    private val postDao: PostDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val syncPort: SyncPort,
    private val clock: PolicyClock,
) {
    /** 创建评论 */
    suspend fun createComment(
        postClientUuid: String,
        body: String,
    ): Result<CommunityComment>

    /** 删除评论 */
    suspend fun deleteComment(commentClientUuid: String): Result<Unit>

    /** 观察帖子的评论列表 */
    fun observeComments(postClientUuid: String): Flow<List<CommunityComment>>
}
```

### 5.3 CommunityReactionCoordinator

```kotlin
@Singleton
class CommunityReactionCoordinator @Inject constructor(
    private val reactionDao: ReactionDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val syncPort: SyncPort,
) {
    /** 添加/切换回应 */
    suspend fun toggleReaction(
        postClientUuid: String,
        commentClientUuid: String? = null,
        emoji: String,
    ): Result<Unit>

    /** 观察帖子的回应汇总 */
    fun observeReactions(
        postClientUuid: String,
        commentClientUuid: String? = null,
    ): Flow<List<CommunityReaction>>
}
```

---

## 6. Sync Layer

### 6.1 SyncPort 扩展

```kotlin
// SyncPort.kt 新增方法
interface SyncPort {
    // ... 现有方法 ...

    /** 创建帖子（写 Room + Outbox + 触发 sync） */
    suspend fun createCommunityPost(
        title: String,
        body: String,
        categoryId: Long?,
        mediaClientUuids: List<String>,
    ): Result<CommunityPost>

    /** 更新帖子 */
    suspend fun updateCommunityPost(
        postClientUuid: String,
        title: String,
        body: String,
        categoryId: Long?,
    ): Result<Unit>

    /** 删除帖子 */
    suspend fun deleteCommunityPost(postClientUuid: String): Result<Unit>

    /** 创建评论 */
    suspend fun createCommunityComment(
        postClientUuid: String,
        body: String,
    ): Result<CommunityComment>

    /** 删除评论 */
    suspend fun deleteCommunityComment(commentClientUuid: String): Result<Unit>

    /** 切换回应 */
    suspend fun toggleCommunityReaction(
        postClientUuid: String,
        commentClientUuid: String?,
        emoji: String,
    ): Result<Unit>

    /** 拉取社区数据（foreground pull 时顺带） */
    suspend fun pullCommunityPosts(cursor: Long): Result<CommunityPullResult>

    /** 上传帖子图片 */
    suspend fun uploadCommunityPostMedia(
        postClientUuid: String,
        mediaClientUuid: String,
        source: SyncMediaUploadSource,
    ): Result<Unit>
}
```

### 6.2 CommunitySyncEngine

```kotlin
internal class CommunitySyncEngine(
    private val backend: SyncBackend,
    private val postDao: PostDao,
    private val commentDao: CommentDao,
    private val reactionDao: ReactionDao,
    private val categoryDao: CategoryDao,
    private val postMediaDao: CommunityPostMediaDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) {
    /** Push dirty community entities */
    suspend fun pushPending(session: SyncSession) {
        // 1. 分类（无依赖，先推）
        pushDirtyCategories(session)
        // 2. 帖子（依赖分类）
        pushDirtyPosts(session)
        // 3. 评论（依赖帖子）
        pushDirtyComments(session)
        // 4. 回应（依赖帖子/评论）
        pushDirtyReactions(session)
        // 5. 帖子图片
        pushDirtyPostMedia(session)
    }

    /** Pull community entities from server */
    suspend fun pull(session: SyncSession, cursor: Long): CommunityPullResult {
        // 复用现有 pull 分页模式
        // applyRemote: LWW merge 到 Room
        // 更新 denormalized 字段（replyCount, reactionCount, lastReplyAt）
    }

    /** Apply remote entities to local Room */
    internal suspend fun applyRemote(
        session: SyncSession,
        entities: List<SyncEntity>,
    ) {
        transactionRunner.run {
            for (entity in entities) {
                when (entity.type) {
                    "community_post" -> applyPost(entity)
                    "community_comment" -> applyComment(entity)
                    "community_reaction" -> applyReaction(entity)
                    "community_category" -> applyCategory(entity)
                    "community_post_media" -> applyPostMedia(entity)
                }
            }
            // 更新 denormalized 计数
            updateDenormalizedCounts(entities)
        }
    }
}
```

### 6.3 与现有 ReplicaSyncEngine 的集成

社区同步作为 ReplicaSyncEngine 的一个阶段，在现有 pull/push 周期中执行：

```kotlin
// ReplicaSyncEngine.synchronize() 扩展
suspend fun synchronize(session: SyncSession, trigger: SyncTrigger): ReplicaSyncOutcome {
    // ... 现有 care record/plan 同步 ...

    // 社区数据同步（在 record/plan 同步之后）
    communitySyncEngine.pushPending(session)
    communitySyncEngine.pull(session, currentCommunityCursor)

    return ReplicaSyncOutcome.Synchronized
}
```

---

## 7. UI Layer

### 7.1 导航路由

```kotlin
object CommunityRoutes {
    const val COMMUNITY = "community"
    const val POST_DETAIL = "community/post/{postClientUuid}"
    const val POST_COMPOSER = "community/post/new"
    const val POST_EDIT = "community/post/{postClientUuid}/edit"
}
```

### 7.2 社区主页（CommunityScreen）

```
┌─────────────────────────────────────┐
│ 社区                                │  顶栏
├─────────────────────────────────────┤
│ [全部] [日常] [提问] [分享]         │  分类 Tab（横向滚动）
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐ │
│ │ 📷  宝宝今天第一次笑了          │ │  帖子卡片
│ │     日常 · 3 小时前 · 💬 5 ❤️ 12│ │
│ │     今天下午喂奶的时候...        │ │
│ │     [图片缩略图 ×3]             │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │ 求助：宝宝发烧怎么办            │ │
│ │     提问 · 昨天 · 💬 12 ❤️ 3    │ │
│ └─────────────────────────────────┘ │
│ ...                                 │
├─────────────────────────────────────┤
│                     [✏️ 发帖]       │  FAB
├─────────────────────────────────────┤
│ 记录 | 汇总 | 成长 | 社区 | 菜单   │  底部导航（新增「社区」Tab）
└─────────────────────────────────────┘
```

**交互**：
- 分类 Tab 横向滚动筛选
- 排序切换：右上角菜单（最新回复 / 最新发帖 / 最热）
- 下拉刷新 → 触发前台同步
- 点击帖子 → 进入详情
- FAB → 发帖 Composer
- 空状态：「还没有帖子，来发第一条吧！」

### 7.3 帖子详情（CommunityPostDetailScreen）

```
┌─────────────────────────────────────┐
│ ← 帖子详情                          │
├─────────────────────────────────────┤
│ 宝宝今天第一次笑了                  │  标题
│ 日常 · 3 小时前 · 妈妈              │  元信息
├─────────────────────────────────────┤
│ 今天下午喂奶的时候，宝宝突然...     │  正文（Markdown 渲染）
│                                     │
│ [图片 1] [图片 2] [图片 3]          │  图片网格（可点击查看大图）
├─────────────────────────────────────┤
│ ❤️ 12  👍 5  😂 2                   │  回应栏（点击添加/切换）
├─────────────────────────────────────┤
│ 评论 (5)                            │
│ ┌─────────────────────────────────┐ │
│ │ 爸爸 · 2 小时前                 │ │  评论卡片
│ │ 太可爱了！                       │ │
│ │ ❤️ 2                            │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │ 奶奶 · 1 小时前                 │ │
│ │ 注意别着凉                       │ │
│ └─────────────────────────────────┘ │
│ ...                                 │
├─────────────────────────────────────┤
│ [输入评论...]            [发送]     │  固定底部评论栏
└─────────────────────────────────────┘
```

**交互**：
- 图片点击查看大图（PhotoPicker 风格）
- 回应栏：点击 emoji 添加/再次点击移除
- 评论：底部固定输入框，发送后自动滚动到最新
- 长按评论：编辑/删除（仅自己的）
- 作者标识：显示家庭称呼 + 相对时间

### 7.4 发帖 Composer（CommunityPostComposerScreen）

```
┌─────────────────────────────────────┐
│ ← 发帖                     [发布]  │
├─────────────────────────────────────┤
│ 标题                                │
│ ┌─────────────────────────────────┐ │
│ │ 输入帖子标题...                  │ │  单行输入
│ └─────────────────────────────────┘ │
│                                     │
│ 分类                                │
│ [全部▾] [日常] [提问] [分享]        │  分类选择 chips
│                                     │
│ 正文                                │
│ ┌─────────────────────────────────┐ │
│ │ 输入帖子内容...                  │ │  多行输入（支持简单 Markdown）
│ │                                 │ │
│ │                                 │ │
│ └─────────────────────────────────┘ │
│                                     │
│ 图片 (0/9)                          │
│ [+] [图片1] [图片2] [图片3] ...     │  图片网格（可删除、拖拽排序）
│                                     │
├─────────────────────────────────────┤
│ 标题必填 · 正文必填 · 图片可选(≤9)  │  校验提示
└─────────────────────────────────────┘
```

**交互**：
- 标题必填，≤200 字符
- 正文必填，≤10000 字符
- 分类可选（默认「未分类」）
- 图片可选，最多 9 张，复用现有图片选择 + 压缩逻辑
- 发布前校验：标题+正文必填
- 发布中禁用退出和重复提交
- 发布成功后返回社区主页并刷新

### 7.5 底部导航扩展

当前 5 个 Tab：记录 | 汇总 | 成长 | 账户 | 菜单

扩展为 5 个 Tab（替换「账户」为「社区」，账户入口移入「菜单」）：

| Tab | 中文 | 说明 |
|-----|------|------|
| 记录 | 记录 | 主路径 |
| 汇总 | 汇总 | 当前周汇总 |
| 成长 | 成长曲线 | 当前成长曲线 |
| 社区 | 社区 | 家庭论坛 |
| 菜单 | 菜单 | 设置、导出、宝宝、**账户**、关于 |

**注意**：账户入口从 Tab 移入菜单，因为社区是更高频的入口。菜单中「账户」保持原有功能不变。

---

## 8. 与现有架构的集成点

### 8.1 Membership 与作者显示

复用现有 `FamilyMember` 模型：

```kotlin
// 评论/帖子的 authorDisplayName 通过 JOIN FamilyDao 获取
// 删除成员后显示「家人」（复用现有逻辑）
// 同一 membership 多设备视为同一作者
```

### 8.2 媒体上传

复用现有 `SyncMediaFileStore` + `AtomicMediaBundlePublisher`：

```kotlin
// 帖子图片 = MediaAsset(kind = "community_post")
// 上传 = atomic bundle（帖子元数据 + 图片）
// 下载 = 现有 getMedia 路径
// 清理 = 现有 ReferenceAwareMediaFileCleanup
```

### 8.3 离线支持

```kotlin
// 未加入家庭时：帖子写入 Room，syncDirty = true
// 加入家庭后：前台 push 时自动同步
// 断网时：保留本地数据，恢复后自动同步
// 冲突解决：LWW（updatedAt 最新者胜）
```

### 8.4 ForegroundSyncGate

社区同步复用现有前台 + 信任 endpoint 门控：

```kotlin
// RealSyncPort 中的 foregroundSyncGate 同时保护社区同步
// 非前台不推送社区数据
// 未信任 endpoint 不推送社区数据
```

---

## 9. Room Schema 迁移

### 9.1 新增表

```sql
-- 社区帖子
CREATE TABLE IF NOT EXISTS community_post (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    client_uuid TEXT NOT NULL UNIQUE,
    family_id TEXT NOT NULL,
    author_membership_id TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    category_id INTEGER,
    reply_count INTEGER NOT NULL DEFAULT 0,
    reaction_count INTEGER NOT NULL DEFAULT 0,
    last_reply_at INTEGER,
    is_pinned INTEGER NOT NULL DEFAULT 0,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    sync_dirty INTEGER NOT NULL DEFAULT 1,
    family_published_updated_at INTEGER
);

-- 社区评论
CREATE TABLE IF NOT EXISTS community_comment (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    client_uuid TEXT NOT NULL UNIQUE,
    post_client_uuid TEXT NOT NULL,
    author_membership_id TEXT NOT NULL,
    body TEXT NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    sync_dirty INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (post_client_uuid) REFERENCES community_post(client_uuid) ON DELETE CASCADE
);

-- 社区回应
CREATE TABLE IF NOT EXISTS community_reaction (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    post_client_uuid TEXT NOT NULL,
    comment_client_uuid TEXT,
    membership_id TEXT NOT NULL,
    emoji TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    sync_dirty INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (post_client_uuid) REFERENCES community_post(client_uuid) ON DELETE CASCADE
);

-- 社区分类
CREATE TABLE IF NOT EXISTS community_category (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    client_uuid TEXT NOT NULL UNIQUE,
    family_id TEXT NOT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    sync_dirty INTEGER NOT NULL DEFAULT 1
);

-- 帖子图片
CREATE TABLE IF NOT EXISTS community_post_media (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    client_uuid TEXT NOT NULL UNIQUE,
    post_client_uuid TEXT NOT NULL,
    local_uri TEXT NOT NULL,
    remote_uri TEXT,
    mime TEXT NOT NULL,
    width INTEGER NOT NULL DEFAULT 0,
    height INTEGER NOT NULL DEFAULT 0,
    byte_size INTEGER NOT NULL DEFAULT 0,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    sync_dirty INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (post_client_uuid) REFERENCES community_post(client_uuid) ON DELETE CASCADE
);
```

### 9.2 Schema 版本策略

遵循 ADR-0008（fresh-current only）：不做旧 schema 升级迁移。新 schema 包含社区表，旧 schema 用户在 fresh install 时获得。

---

## 10. 测试策略

### 10.1 单测

| 测试 | 覆盖 |
|------|------|
| `CommunityPostCoordinatorTest` | 帖子 CRUD、分类筛选、排序、权限（Owner vs Member） |
| `CommunityCommentCoordinatorTest` | 评论 CRUD、权限、帖子删除级联 |
| `CommunityReactionCoordinatorTest` | 回应 toggle、唯一约束、tombstone |
| `CommunitySyncEngineTest` | push dirty entities、pull apply、LWW merge、denormalized 更新 |
| `CommunityViewModelTest` | UI state 映射、分页加载、错误处理 |

### 10.2 集成测试

| 测试 | 覆盖 |
|------|------|
| `CommunityPostCreateFlowTest` | 发帖 → Room → Outbox → push → 服务端 → pull → 其他设备可见 |
| `CommunityCommentSyncTest` | 评论同步、帖子删除级联同步 |
| `CommunityReactionSyncTest` | 回应同步、唯一约束服务端验证 |

### 10.3 Compose 测试

| 测试 | 覆盖 |
|------|------|
| `CommunityScreenTest` | 帖子列表渲染、分类筛选、排序切换、空状态 |
| `CommunityPostDetailTest` | 帖子详情、评论列表、回应栏交互 |
| `CommunityPostComposerTest` | 表单校验、图片选择、发布流程 |

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 帖子图片同步延迟 | 用户发帖后图片暂时不可见 | 本地 URI 立即可用，remote_uri 异步补齐 |
| denormalized 计数不一致 | replyCount/reactionCount 与实际不符 | 服务端在 commit 时计算并返回，客户端合并；定期 full sync 修正 |
| 帖子正文过长 | 同步 payload 过大 | 限制 ≤10000 字符；图片独立上传 |
| 分类删除后帖子悬挂 | 帖子引用已删除分类 | 分类删除时帖子 categoryId 置 null（未分类） |
| 多设备同时编辑帖子 | 冲突 | LWW（updatedAt 最新者胜）；V2 可加乐观锁 |

---

## 12. 后续演进

| 项 | 时机 | 说明 |
|----|------|------|
| 帖子搜索 | V2 | Room 全文索引 + lezi-sync 搜索 API |
| 图片懒加载 | V2 | Coil 异步加载 + 占位图 |
| 帖子置顶 | V2 | Owner 可置顶，客户端 `isPinned` 排序 |
| 通知推送 | V1.5 | lezi-sync WebSocket 或 polling + Android 通知 |
| 帖子草稿 | V2 | 离线发帖草稿持久化 |
| 评论编辑 | V2 | 评论编辑功能 |
| 表情回应自定义 | V2+ | 自定义 emoji 集 |
