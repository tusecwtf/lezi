# Validation and release evidence

## Authority and identity

Current product contracts live in `docs/spec/`; accepted ADR decisions prevail
when older terminology or dated design references conflict. Update terminology
rather than silently reverting accepted behavior (in particular ADR-0023).
Android identity is read from `config/android-release-compatibility.json`, channel
identity from `tools/lezi-sync/deploy/app-update.json`, server version from Cargo.
These identities may legitimately differ. Platform release notes describe the
coordinated release; they are not a live-server measurement.

The architecture dependency table describes actual Gradle edges. An added edge
requires reviewing that table, not removing a legitimate edge to fit stale prose.
Historical prototypes are visual references, never product acceptance evidence.

## Public clean-checkout gates

- Public workflows check out the PR head SHA explicitly (or the event SHA for
  push/manual runs), verify `git rev-parse HEAD`, and retain commit/tree identity
  with reports even on failure. A green older SHA or a default PR merge ref is
  not evidence for the final head.
- Android workflow: `./gradlew test -PleziFastUnitTests=true -x :app:validateReleaseSigning`,
  `./gradlew :app:assembleDebug lintDebug`. The explicit fast property excludes
  real-server tests only in this job; normal `test` retains them. The task-scoped
  signing exclusion allows both debug and release JVM variants on the public
  runner; it does not assemble or sign a release, and normal `assembleRelease`
  keeps its signing and signature checks.
- Manual recovery capacity: `./gradlew :sync:publicRestoreCapacityTest --no-parallel --max-workers=1`
  verifies the public 520 MiB JVM restore flow beside a full 512 MiB ordinary
  spool in an exclusive I/O window. Ordinary suites explicitly exclude this
  resource category and the superseded store-only capacity test. See the
  [capacity runbook](../testing/public-restore-capacity.md) for preflight,
  counters, cleanup, and the limits of JVM timing evidence.
- Release shrinker: `./gradlew :app:minifyReleaseWithR8 -x :app:validateReleaseSigning`.
  This checks release compilation/R8 without a keystore and does not assemble,
  sign, publish or validate an installable release. Normal release assembly still
  requires signing and signature verification; never use the exclusion to ship.
- Required integration: `bash tools/testing/run-isolated-integration.sh` builds
  this checkout into its dedicated target, exports executable path, SHA-256 and
  current Git revision, then runs sync/media/domain real-server seams. Missing
  tools/artifact, digest/revision mismatch or test failure is fatal. No mtime,
  home-cache or executable-size fallback. Prerequisites: JDK 21, Android SDK 35,
  Cargo/Rust, openssl, curl, sqlite3 and normal Android build dependencies.
  The integration job intentionally restores no Gradle/Cargo cache. Its uploaded
  log retains the built executable path, SHA-256, revision and both seam results;
  XML/reports accompany it. The launcher forces the fast-unit exclusion off and
  rejects callers trying to override it. Missing reports or a failed bootstrap
  are not a pass.
- Rust: fmt, locked tests, all-target/all-feature clippy with warnings denied.
  Shared config and contract docs trigger Rust proof; server-only edits trigger
  current Android integration. Conservative Android routing includes docs rather
  than accidentally omitting wire inputs. `app/build.gradle.kts` also triggers
  Rust/release fixtures because it defines the metadata validator. The existing
  `validateAndroidAppUpdateMetadataCompatibility`, release-catalog and local-data
  validators are prerequisites of both `preDebugBuild` and `preReleaseBuild`;
  therefore debug assembly and release R8 must traverse those checks.
- Routing regression proof: `python3 tools/testing/test_workflow_contracts.py`
  (requires PyYAML, installed as `python3-yaml` in CI). The changed-path matrix
  covers golden/schema JSON, shared markers, compatibility catalogs, Room/server
  schema, closed codecs, contract docs, metadata and validator implementation.
  It exercises push/PR and mixed changed-file sets without dispatching workflows.
  `config/**`, `docs/spec/contracts/**`, current wire consumers and server inputs
  require both workflows. Narrative README/testing/ADR edits retain conservative
  Android routing but do not trigger Rust solely by being documentation.
  `.scratch/**`, `design/**` and `prototype/**` remain excluded references.
- Deployment shell fixtures use temp roots and fake adapters. CI runs every
  tracked deployment smoke, including current-package TLS/stdin-secret proof.
  The fixture job installs openssl/curl/sqlite3 and ripgrep before any smoke,
  rather than assuming runner-image tools. Fixture proof is not a real Docker
  build, deployment or credential-backup proof.
- `bash tools/lezi-sync/docker/test-version-contract.sh` rejects absent/stale
  image versions. Ordinary Docker/Compose builds must explicitly supply the Cargo
  version; `build-image.sh` is the supported version-derived entry.

## Devices and release approval

The Android device workflow runs the ordinary module Room/database, Compose and
instrumentation suites on API 26 (minimum) and API 35, with XML/reports retained
on failure. The two export parent-death methods and three installer process-death
methods require their separate host drivers and supplied nonces; ordinary workflow
runs skip those methods and cannot establish their process-death acceptance.
Preserve the host-driver results separately before claiming those contracts pass.
The device workflow is `workflow_dispatch`-only and requires explicit device
execution authorization; push/PR changes never start ADB or emulators. A queued/skipped job or merely
having test files is not device evidence. Flows without instrumentation coverage still require a recorded manual device run.

Before distributing a release, the reviewer must attach evidence for:

1. Signed `assembleRelease`, verified signer and packaged channel metadata;
   performed only in the authorized signing environment, never public PR secrets
2. Room/local-data upgrade from each catalogued contract boundary, preserving
   records/media, plus launch/restart on minimum and current API
3. Changed Composer, conflict/source-choice and destructive confirmation flows,
   keyboard/insets, large text and lifecycle/cancellation behavior
4. Install/update permission denial, cancel, successful install and post-update
   launch with the same data; real installer behavior cannot be proved by R8
5. Required cross-platform and relevant shell/device jobs at the PR's final commit

Missing SDK, network dependencies, emulator, signer or device access must be
reported as a blocker, not green. No public gate needs family NAS/SSH access,
private signing keys, real family data or a production maintenance window.

## Interrupted update publication

`remote-deploy.sh` claims a private `.lezi-app-update-transaction` directory in
its data bind before touching final channel files. Existing transaction state or
legacy rollback files refuses continuation. Handled publication failures restore
the prior pair before any container stop; rollback failure aborts and retains
state for operator inspection. SIGKILL/power-loss state is deliberately not
automatically reused or erased: an authorized operator must recover/verify the
pair and clear the stale transaction before another deployment. Do not exercise
this recovery on a family NAS as part of a test.
