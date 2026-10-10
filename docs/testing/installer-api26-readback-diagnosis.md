# U01 API26 staged-read denial diagnosis

## Measured failure, not installer recovery acceptance

Source `3e028d626869535b6a8022d7478d9fb4ba883d46`, API26 emulator-5556,
SELinux Enforcing, fingerprint
`Android/sdk_phone_x86/generic_x86:8.0.0/OSR1.180418.004/4931640:userdebug/test-keys`.

- App APK: 26,991,012 bytes; SHA256 `1d4591af2ce8061a47c44dae3d207dc744b0bc0c6af8e9eb5d7221834de440cf`
- Test APK: SHA256 `a43d3ae11aecee455425df95f6594a41e88b20ee1474c554feae6839b61d6469`
- Evidence bundle: `device-validation/api26-3e028d6/installer/arm-instrumentation.txt`,
  `installer/result.json`, and sibling `installer-terminal-logcat.txt`,
  `installer-terminal-health.txt`, `installer-driver.log`
- No pending status, host arm or process-death observations were reached
- Shell-owned control was successfully abandoned by failure cleanup

The failing stack is `PackageInstaller.Session.openRead` → fixture `stage:214` →
`armRealSessionsAndSelfTerminate:88`, the first complete unsealed staging copy.
At guest log time 07:32:58.625, logcat line4771 records enforcing SELinux denial
of `{ read }` for `/data/app/vmdl859389550.tmp/lezi-update.apk`:
`scontext=u:r:untrusted_app:s0:c512,c768`, `tcontext=u:object_r:apk_tmp_file:s0`.
At 07:32:58.640, line4772 records `FAILED BINDER TRANSACTION`, parcel size132.
Instrumentation reports `RuntimeException: DeadSystemException`.

The immediate denial and operation match support a staged-file read-descriptor
handoff failure. They do not show an actual system-server death. The host recorded
system-server PID1596/start3118 before the test; the later health snapshot still
shows PID1596 (the later file did not record its start tick, so it alone does not
exclude theoretical PID reuse). No restart is demonstrated by this evidence.

[AOSP API26 Binder error mapping](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/core/jni/android_util_Binder.cpp)
uses a heuristic DeadObjectException for a small failed transaction; its comments
explicitly acknowledge other FD-related failure causes. The exception name is
therefore insufficient evidence that the system died.

## Descriptor, session and space analysis

The fixture's `openWrite` output is nested in `use`, the source input is closed,
`Session.fsync(output)` completes, and the output closes **before** `openRead`.
The enclosing session remains open until the readback attempt. This is not a
read-after-closing-the-session or commit-with-an-open-writer path. The failing
read call returns no stream, so no read descriptor was available for fixture
cleanup. All created session IDs are in the `finally` cleanup list, but the old
run does not retain a complete post-failure OS matrix; full cleanup is unproved.

At failure, source ordering reached three app-owned sessions: create-only,
partial, and fully written. The foreign/unmarked/pending app controls were not
yet created. Each written session has one `lezi-update.apk` file; partial requests
full APK allocation but writes at most4096 bytes. Thus the two requested full-length
allocations could total53,982,024 bytes, while submitted content is26,995,108 bytes
if the original partial read returned4096. These are source-derived bounds, not
measured disk usage. The saved run has no contemporaneous free-space/FD-count
snapshot. There is no ENOSPC, EMFILE or out-of-memory error around the failure;
that absence is not a complete resource measurement.

[AOSP API26 session implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerSession.java)
shows why the extra verification has a different boundary: writes return a bridge
or revocable FD, whereas reads return an ordinary staging-file FD. Commit checks
writers are closed and validates staged package/signature consistency before
requesting user action. This supports keeping genuine OS pending confirmation as
required evidence; it is not independent digest readback of staged disk bytes.

Production `AndroidPackageInstallerPlatform` uses openWrite/fsync/commit and has
no openRead call. This run therefore reproduces a fixture/platform verification
mismatch; it does not establish a production installation or recovery defect.

## Scoped correction

Only test/tooling changes, based on `80d0816`:

- Remove the denied readback on both supported emulator APIs, with no alternative
  raw staging read, privileged route, security change, or catch-and-retry fallback
- Hash/count only chunks whose actual platform `output.write` returned; preserve
  the raw platform output stream for `Session.fsync` and close it before returning
- Require exact full installed-APK digest/length for full writes; host independently
  checks full-write and partial-prefix evidence against its measured APK artifact
- Persist per-session successful write/count/digest/fsync/stream-close evidence
  with the explicit label `submitted-stream-not-staged-readback`
- Record app-data available bytes before/after staging for future diagnostics
- Keep all genuine pending status, sealed ownership, post-death OS-residue,
  separate process generations, production startup recovery and third-startup
  idempotence requirements unchanged

The independent staged-disk readback claim is explicitly retired, not silently
substituted. No production installer implementation changes. This correction
still needs independent review, an exact-source APK build and serialized actual
API26/API35 validation. U01 remains blocked until those runs establish its
required recovery evidence. There has been no speculative device retry.
