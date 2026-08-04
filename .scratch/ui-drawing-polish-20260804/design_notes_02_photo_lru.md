# Design notes — 02 bounded photo LRU

## Public seams under test

1. **`LocalPhotoMemoryCache`** (designsystem)
   - `get(key)` / `put(key, value, plan)` / `unpin(key)`
   - Key: `LocalPhotoCacheKey(path, target, sourceWidth, sourceHeight, orientation)`
   - Dual bounds from `LocalPhotoCachePolicy`:
     - THUMBNAIL max entries = 24
     - FULLSCREEN max entries = 2
     - shared max decoded bytes = 24 MiB (ARGB_8888 = 4 B/px)
   - Eviction: LRU among **unpinned** entries; `release` callback on eviction
   - Pin: put/get grant one pin to caller; unpin balances; cancel must not leave a pin/entry for a non-returned Ready

2. **`BoundedLocalPhotoLoader.load`** with optional cache
   - After inspect + plan, cache hit returns Ready without decode/orientation
   - Successful miss decodes then `put`s; ownership of value transfers to cache
   - `CancellationException` after partial decode releases bitmap and does **not** put
   - Cancel after a pin was taken (hit path interrupted) unpins before rethrow
   - Identity miss: different target/orientation/source size → different key → re-decode

3. **`rememberLocalPhoto`** (Android)
   - Uses shared loader + cache only (no second decode path)
   - On dispose: `unpin(cacheKey)` — never `release` while entry may still be cached
   - Cache recycles only on eviction of unpinned entries

## Out of scope

- Disk cache, Coil/Glide, network images
- Changing import/decode budgets in `RecordPhotoResourcePolicy`
