# 03 — 媒体文件与 publications 映射

**What to build:** 在本机迁移结果中，把拷出的 `media/` 与 v3 `media_publications`（及 bundle 媒体关联）变成当前服务能服务的布局与行；坏文件或不可映射 publication **整次失败**。完成后，测试夹具上「记录带图」在迁移后仍能按当前 pull/媒体路径读到字节。

**Blocked by:** 02 — 本机离线库迁移器 v3→当前

**Status:** complete

## Acceptance criteria

- [x] 迁移输入含 media 目录时，输出 data 布局满足当前服务媒体路径约定
- [x] 可映射的 publications / bundle 媒体关联进入新库且与文件一致
- [x] 缺失文件、哈希/大小不一致、或退役且不可映射的 source 形态 → 迁移失败并报告，不产出可拷回结果
- [x] 夹具测试：迁移后能解析到至少一条带媒体的权威记录路径

## Out of scope

- 在线边同步边迁
- 重新编码/压缩媒体

## Notes

### Public seams (self-confirmed)

| Seam | Role |
|------|------|
| `migrate_v3_data_dir(source_data_dir, dest_data_dir)` | One-shot: transform `lezi.db` + copy/validate authority `media/` |
| `media_file_relative_path(family_id, media_uuid)` | `media/{family}/{uuid}` layout (inventory + live final path) |
| `AuthoritativeFailure::MediaFileMissingOrMismatch` | Closed inventory failure for missing / size / sha256 / unmappable source on dest |

### Authority & fail-closed

- Authority set = retained `media_publications` (`ordinary`\|`bundle`) ∪ retained `sync_bundle_media`
- Size: entity `byte_size` and/or `declared_byte_size`/`staged_byte_size`; sha256 when `staged_sha256` present
- Staging / `bundle_pending` cleanup bytes: ignore + not copied (report-only via DB cascade)
- Media failure removes dest `lezi.db` (+ sidecars) and dest `media/` written this run

### Implementation

- `tools/lezi-sync/src/offline_migrate/media.rs`
- Re-exports: `crate::offline_migrate::{migrate_v3_data_dir, media_file_relative_path}`
- Tests: `cargo test --locked offline_migrate` (includes media module)
