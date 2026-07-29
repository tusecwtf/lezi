# Ticket 03 bounded off-main local photo loading

Date: 2026-07-30 (Asia/Shanghai)

## Audit disposition

Commit `eced341` had consolidated the full-screen pager and contained decode exceptions/OOM, but
the pager still decoded the original file synchronously and at full resolution. Composer and
conflict-audit thumbnails also retained separate direct `BitmapFactory.decodeFile(path)` calls.

The three user-reachable Record/CarePlan photo surfaces now use
`designsystem.rememberLocalPhoto(path, target)`:

- Composer and conflict-audit thumbnails request `THUMBNAIL` (256 x 256, 65,536 pixels).
- The shared full-screen pager requests `FULLSCREEN` (2048 x 2048, 4,194,304 pixels).

The loader reads bounds and EXIF first, rejects hostile source dimensions, selects a power-of-two
sample, disables density scaling, normalizes all eight EXIF orientations and rechecks the actual
post-transform dimensions. Inspect, decode and transform run on a two-lane IO dispatcher. A
request-key change or composition exit cancels the old producer; cancellation releases its owned
bitmap and is rethrown, while missing/corrupt/unsupported inputs, other exceptions and OOM converge
on a stable unavailable state. No global bitmap cache was added.

## RED to GREEN

1. The first loader test failed compilation because the public request/source/result seams did not
   exist. It passed after bounds-first planning and separate target budgets were implemented.
2. The EXIF matrix test failed compilation before `localPhotoOrientationForExif` existed, then
   passed for values 1-8, unknown fallback, axis swapping and orientation-aware identity.
3. The failure-boundary test first leaked an `IllegalArgumentException`; it passed after missing,
   corrupt, unsupported, OOM and hostile bounds converged on `Unavailable` without hostile decode.
4. The actual-result budget test failed compilation before decoded dimensions were part of the
   seam; it passed after post-orientation width, height and pixel checks released oversized output.
5. The UI migration contract initially failed because Composer/conflict thumbnails did not use the
   unified loader. It passed after all three production call sites selected their explicit target.
6. Cancellation, rapid A-to-B replacement and dispatcher tests pass, including stale-A release and
   inspection/decode/orientation execution on the supplied non-main dispatcher.

Targeted JVM/caller command:

```text
./gradlew :designsystem:testDebugUnitTest \
  --tests LocalPhotoLoaderTest --tests PhotoPreviewDialogTest \
  :feature:log:compileDebugKotlin :feature:settings:compileDebugKotlin
```

Result: `BUILD SUCCESSFUL` (95 tasks; 18 executed, 77 up-to-date).

## API 35 device smoke

`LocalPhotoLoaderDeviceSmokeTest` generated and verified a real 6000 x 4000 RGB_565 JPEG with EXIF
rotate-90 metadata. A 40-item lazy thumbnail strip rapidly moved 35 -> 2 -> 39 with interleaved
missing paths, then the shared full-screen pager moved valid -> missing placeholder -> valid.

```text
./gradlew :designsystem:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.designsystem.LocalPhotoLoaderDeviceSmokeTest
```

Result: `BUILD SUCCESSFUL` on `lezi_api35(AVD)-15` (50 tasks; 10 executed, 40 up-to-date).

## Final gate

Validation base HEAD: `c9e40bf35ab7a7fe80b1b7d88cfde18d695a1aab`.

The complete caller/module test gate passed:

```text
./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest \
  :feature:settings:testDebugUnitTest :feature:family:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL` (152 tasks; 19 executed, 133 up-to-date).

The related lint and app build gate then passed as a separate serial command:

```text
./gradlew :designsystem:lintDebug :feature:log:lintDebug \
  :feature:settings:lintDebug :feature:family:lintDebug \
  :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL` (771 tasks; 126 executed, 645 up-to-date).

Compose runtime 1.7.6's `ProduceStateDoesNotAssignValue` detector continued to flag the standard
inline producer even though it visibly assigns `value = Loading` and `value = loaded`. The function
therefore has a narrow function-level suppression for that identifier with an adjacent false-positive
comment; no lint baseline or module-wide suppression was added. Unit cancellation/replacement tests
and the API 35 stale-key/device lifecycle smoke retain the behavioral evidence the detector could not
infer.

Final `git diff --check` passed. Static source scan found no old `decodePhotoPreviewBitmap` or direct
`BitmapFactory.decodeFile(path)` in the Record/CarePlan preview surfaces; the only production
consumers are the two `THUMBNAIL` call sites and shared `FULLSCREEN` pager.

## Scope limits

Avatar import/crop is already an independent bounded 2048-source/512-output background path and is
not a Record/CarePlan preview consumer. PDF export is non-interactive and keeps its own sampled
renderer. Ticket 04 owns import/upload memory behavior. This ticket changes no Room/wire schema,
version metadata or Release APK claim.
