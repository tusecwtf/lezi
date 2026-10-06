# Baby Buddy：产品形态、理念与「同步」实现

Status: research-only（对照乐记，不改 PRD）

日期：2026-08-31

问题：Baby Buddy 的实现理念和产品形态是什么？多看护者之间的「同步」代码实际长什么样？

---

## 0. 结论

Baby Buddy **不是同步产品**。它是一台自托管 Django 网站：数据库在服务器上，浏览器和 Android 都是远程控件。两个家长看到同一份日志，是因为他们打同一个 REST API，不是因为设备之间复制。

| | Baby Buddy | 乐记 |
|--|------------|------|
| 真相源 | Django ORM（SQLite/Postgres） | 本机 Room；家庭图经 `lezi-sync` |
| 客户端 | 薄 HTTP；无护理副本 | 离线可写完整副本 |
| 多看护者 | 同一实例上的多个 Django 用户 | membership + 设备会话 + commit-first |
| 「冲突」 | 睡眠/喂养时间段交叉则 **拒绝写入** | ConflictSnapshot，choice-only |
| 离线 | 官方 Android：断网不能记。社区 iOS Companion 才有本地库+outbox | 本机照记，回来对账 |
| 身份 | Django 用户 + DRF Token | 家庭管理员 / membership / 设备 token |
| 拓扑 | 星型、服务器永远在线 | 同拓扑，但客户端是主本 |

与乐记的相似点只在部署：**都是家庭自托管中心**，不是 P2P、不是区块链。

---

## 1. 产品形态

一句话（README）：帮助看护者记录睡眠、喂养、换尿布、 tummy time 等，减少猜宝宝需求。

形态是 **Web 应用 + 可选手机壳**：

- 主 UI：响应式网页（桌面仪表盘 + 移动浏览器截图都在 README）。
- 部署：LinuxServer Docker、Home Assistant Addon、DigitalOcean、手工 nginx + uwsgi + SQLite。文档按「1 GB VPS、两个家长、任意数量孩子」来估（[deployment](https://docs.baby-buddy.net/setup/deployment/)）。
- 默认账号 `admin`/`admin`，要求立刻改密。
- 许可 BSD-2。Python/Django。
- 生态是 **把服务器当中枢的物联网**：ESP 键盘、BabyScout、BabyPod、瓶秤、HA 集成、Alexa、MCP。这些全部 `POST /api/...`，没有设备网格。

Android 官方客户端 [babybuddy-for-android](https://github.com/babybuddy/babybuddy-for-android)（MIT）自称「为 Baby Buddy web-app 做的 Android 集成，优化快速开停计时器和记尿布」。作者声明开发因搬家暂停到 Winter 2025。Play 上有偿；也有 IzzyOnDroid。**它不是独立育儿 App**，必须填已有服务器 URL + API token。

没有官方 iOS App；README 只链 [iOS Shortcuts](https://github.com/babybuddy/babybuddy/discussions/300)（一次性 HTTP）。第三方 **Baby Buddy Companion**（[kguy18/babybuddyios](https://github.com/kguy18/babybuddyios)，BSD-2，未挂在上游 README）是唯一给这套 API 补了离线引擎的客户端。

---

## 2. 理念

没有 local-first / CRDT / 去中心化表述。隐含假设：

1. **家庭有一台常开机器**（NAS、HA、VPS、树莓派）。
2. **一份数据库 = 一份家庭日志**。Child 挂在实例上，不挂在用户上。
3. **隐私靠自托管**，不是靠端到端或设备副本。生产环境要求 API Token 只走 HTTPS（[API](https://docs.baby-buddy.net/api/)）。
4. **预测与仪表盘**：默认每分钟刷新仪表盘（见下），强调「看见最近状态」，不是因果对账。
5. **可被其它软件驱动**：REST + OpenAPI 是一等公民，所以才会有一堆硬件/HA/MCP。

多语言按 **每 Django 用户** 设，不是按家庭。时区同样 per-user（`babybuddy/models.py` `Settings`）。
用户分三档（[managing-users](https://docs.baby-buddy.net/user-guide/managing-users/)）：只读（能看不能记）/ 标准（默认可增改任意类型）/ Staff（绕过权限、进 Django Admin）。仓库全文搜不到 `family` / `household` 实体。


---

## 3. 数据模型（服务器即世界）

权威：[`core/models.py`](https://github.com/babybuddy/babybuddy/blob/master/core/models.py)。

| 模型 | 要点 |
|------|------|
| `Child` | `first_name` / `last_name` / `birth_date` / `slug` / `picture`（`child/picture/`）。**没有 family_id、没有 created_by。** 有 `view_child` 的用户看见同一批孩子。 |
| `Feeding` / `Sleep` / `TummyTime` / `Pumping` | `child` FK + `start`/`end`；`save()` 算 `duration`。`clean()`：不能未来、≤24h、**同孩子时段不得交叉**。Feeding 另有 type（breast milk/formula/…）与 method（bottle/left breast/…）。 |
| `DiaperChange` / `Temperature` / 测量 / `Medication` | 时刻点事件，挂 `child`。 |
| `Note` | 文本 + 可选 `image`（`notes/images/`）。没有记录照片时间轴。 |
| `Timer` | 进行中计时；唯一带 `user` FK 的活动模型。`stop()` **删行**。API `timer` 字段会覆盖 child/start/end，校验通过后删 Timer。 |
| `Tag` | taggit；颜色、last_used。 |
| `Settings` | OneToOne 到 User：仪表盘刷新（默认 1 分钟）、语言、时区、分页。`api_key()` → DRF Token。 |

「冲突」在这里是 **业务校验失败**，不是同步分支：

```python
# core/models.py validate_unique_period
conflicting = queryset.filter(start__lt=model.end, end__gt=model.start).first()
# → ValidationError code="period_intersection"
```

两个家长同时给同一孩子记重叠睡眠：后到的 POST 400。没有 ConflictSnapshot，没有「都留下等人选」。尿布等点事件没有这段检查，并发双记会变成两条行——服务器接受，产品不管是否同一事件。

页面层对登录视图加了 `@never_cache`（`babybuddy/mixins.py`），避免浏览器把仪表盘当缓存副本。

---

## 4. 「同步」代码实际是什么

### 4.1 服务器：没有同步模块

[`api/views.py`](https://github.com/babybuddy/babybuddy/blob/master/api/views.py) 是标准 DRF `ModelViewSet`：`ChildViewSet`、`FeedingViewSet`、`SleepViewSet`… 每个 `queryset = Model.objects.all()`，序列化进出 JSON。权限是 Django 模型权限映射（[`api/permissions.py`](https://github.com/babybuddy/babybuddy/blob/master/api/permissions.py)）：GET 要 `view_*`，POST 要 `add_*`。

认证：DRF `TokenAuthentication` + `SessionAuthentication`。外部调用：

```
Authorization: Token <user-key>
```

没有：

- pull cursor / `base_version` / `mutation_id`
- 设备身份
- 照片原子包（只有 Child.picture 上传）
- 副本向量、tombstone、choice
两个家长「同步」的路径：

```
Phone A  POST /api/feedings/  ──► Django ──► INSERT feeding
Phone B  GET  /api/feedings/  ──► 同一张表 ──► JSON 列表
浏览器    仪表盘 setInterval → location.reload()
```

`Settings.dashboard_refresh_rate_milliseconds` 驱动 JS。实现是 `location.reload()`，源码注释 `TODO: Someday maybe update in place?`（`dashboard/static_src/js/dashboard.js`）。这是 **整页轮询**，不是同步协议。

### 4.2 Android：薄客户端 + 5 秒轮询，不是副本引擎

仓库 **没有 Room、没有护理 SQLite**。

- `BabyBuddyClient.java`：`HttpURLConnection`，每请求一线程；Timer CRUD、分页 GET、PATCH。用响应 `Date` 头算 `serverDateOffset`。
- `Client.kt`：OkHttp + Retrofit，baseUrl = `{server}/api/`，拦截器加 `Token …` 和 session Cookie。`getEntries` / `createEntry` / `deleteEntry`。请求 id 是随机 6 hex，只用于 debug log，不是幂等 `mutation_id`。网络切换会 cancel 再 clone 重发。

记一条喂养（`TimerControllersV2.kt`）是 **两次 HTTP，不是服务器文档里的 `timer` 字段捷径**：先 `POST /api/feedings/`（body 带 start/end），再 `DELETE /api/timers/{id}/`。非原子：POST 成功 DELETE 失败则记录在、表还在跑，弹 `ResolveConflicts` 对话框问要不要强停 Timer。

轮询（这就是官方客户端的「同步」）：

- `ChildrenStateTracker`：每 **5 秒** `GET /api/children/`（最多 100 页），diff 后写入 UI KV。
- `ChildEventHistoryLoader`：每 **5 秒** 按类型各拉 25 条时间轴，内存里 merge；指数退避，不落库。
- `RequestScheduler`：连接失败 disconnected 横幅，backoff 重试读请求；`stopScheduler()` 丢掉一切，**没有耐久 outbox**。

本地存储：

- `ActivityStorage.kt`：SQLite `store`，只有 `global_kv` / `login_kv` / `child_kv`。内容是孩子列表缓存、表单草稿、开关状态。**时间轴记录从不写入。**
- `CredStore`：`settings.conf` 存 URL/token/cookie。AES-CBC，密钥 `MD5(SALT+硬编码串)`，SALT 同文件——混淆，不是 Android Keystore。

登录：刮 `/login/` CSRF → POST 账密 → 拿 `sessionid` → `GET /api/profile` 读 `api_key`。QR 载荷 `BABYBUDDY-LOGIN:{url, api_key, session_cookies}`，不是乐记那种 membership grant。

离线：没有网就不能 POST。草稿能活过重启，记录要等服务器 2xx。没有 ETag、没有版本字段；PATCH 是盲覆盖。`ResolveConflicts` 处理的是 **Timer 停表失败 / 服务器校验 JSON**，不是两设备编辑同一行。

### 4.3 社区 iOS Companion：在缺协议的 API 上自造对账

[kguy18/babybuddyios](https://github.com/kguy18/babybuddyios) `Docs/ARCHITECTURE.md` 写明：本地库是真相源，UI 不等网络。这是上游没做、客户端硬补的。

- SwiftData（App Group SQLite）：`LocalEntity` + `PendingMutation` + `PendingImageUpload` + `ConflictRecord`。
- 写入先落本地再入队。Push：create 直接 POST；update/delete 先 GET 服务器行，和 mutation 的 `baseSnapshot` 比（忽略易变字段 `duration`），干净才 PATCH，否则升 `ConflictRecord`。UI：Keep Mine / Keep Server / 字段 Merge。
- Pull：默认 30 天窗；**不覆盖** `pending*` / `conflicted` 行。API **没有 ETag、也不能按 `updated_at` 过滤**，所以只能整窗拉 + 快照 diff——架构文档原话。
- 触发：重连、回前台、下拉、Timer 动作。401 退出登录。凭证进 Keychain。

这套更像乐记的「本机事实 + 对账」，但枢纽仍是 Django，且没有因果 `base_version`、没有家庭 membership。它证明：Baby Buddy 服务端不提供同步协议时，想离线只能客户端自己堆。

---

## 5. 多用户 ≠ 家庭域

- 一个部署 = 一个世界。加 Django 用户 = 加看护者，靠 `manage.py createuser` 和模型权限，不是邀请码/QR 家庭。
- Android 的 QR 登录是 **扫服务器 token**（`login/QRCode.kt`），不是乐记那种「endpoint + 十分钟 grant + membership」。
- Timer 有 `user`，护理行没有「作者 membership」。仪表盘不区分谁记的。
- 没有成员硬删除匿名化、没有设备吊销擦本地——本地本来就几乎没数据。

---

## 6. 对乐记的含义

Baby Buddy 验证的是：**自托管中心 + 薄客户端** 对「两个家长一份日志」足够，只要那台机器醒着。这和当前 `lezi-sync` 拓扑同类，复杂度低两个数量级，因为：

- 放弃离线写入
- 放弃设备副本
- 放弃并发编辑的无损分支
- 用时间段交叉拒绝代替家庭选择

硬件键盘能存在，正因为「同步」= HTTP POST。若乐记改 P2P，这整个生态路径会断。

若只想「没有手机网格、家里有 NAS」，Baby Buddy 是同品类的成熟对照；不是去中心化对照。乐记多出来的是：夜喂先落 Room、无网可用、因果冲突、原子照片、家庭身份。那些 Baby Buddy 根本没做。

---

## 7. 来源

- https://github.com/babybuddy/babybuddy — README、`core/models.py`、`api/views.py`、`api/permissions.py`、`babybuddy/models.py`、`babybuddy/mixins.py`
- https://docs.baby-buddy.net/ — 首页、API、Deployment
- https://github.com/kguy18/babybuddyios — `Docs/ARCHITECTURE.md`、`Sources/Sync/SyncEngine.swift`、`Sources/Persistence/LocalStore.swift`
- https://github.com/babybuddy/babybuddy/discussions/300 — iOS Shortcuts
- https://github.com/babybuddy/babybuddy-for-android — README、`BabyBuddyClient.java`、`Client.kt`、`RequestScheduler.kt`、`ChildrenStateTracker.java`
