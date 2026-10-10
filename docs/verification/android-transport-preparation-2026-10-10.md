# Android transport acceptance preparation: US-027 / US-029 / US-071

Status: **source prepared; Android compilation and all device cases not run**.
This work does not close the three stories. No ADB, device, NAS, signing, deploy,
Gradle/Cargo aggregate, or production behavior change was performed.

Authority: `.scratch/lezi-reliability-remediation/spec.md` US-027, US-029 and
US-071; `docs/spec/layers/sync.md` §§6–7; the audit's three remaining transport
rows. Existing `FamilyHttpDeadlineAcceptanceTest` remains a JVM seam proof;
these new tests do not relabel its fake connections as platform transport.

## What is prepared

The manual class is:
`com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest`.
It contains 14 tests:

| Selector suffix (`#method`) | Real boundary and oracle |
| --- | --- |
| `trustedLoopbackHealthUsesTheRealPlatformConnection` | Valid loopback SAN + certificate validity + production TOFU pin, actual Android HTTPS GET, parsed health, one anonymous request |
| `cancellingSlowJsonReleasesTheExchangeAndPreservesCancellation` | Incomplete trickling JSON response, client call and `cancel()` caller return within 1,500 ms, `CancellationException`, no success |
| `cancellingSlowMediaReleasesTheExchangeAndPreservesCancellation` | Same assertions through `getMedia` |
| `cancellingSlowApkIntoPrivateFileClosesCallerOwnedFileAfterReturn` | Real slow HTTPS APK to a private temporary file; actual partial bytes, caller's `use` exited and file cleanup |
| `cancellingARealBackpressuredUploadReleasesWriteAndSource` | Platform TLS send to a peer that stops reading; real file source progress plateau below full 8 MiB; short cancel return and source close |
| `slowJsonUsesOneRealElapsedParentBudget` | A real 2,500 ms monotonic enclosing budget, no fake clock or resolver; typed elapsed failure, no late success |
| `slowMediaUsesOneRealElapsedParentBudget` | Same enclosing budget through media read |
| `slowApkIntoPrivateFileUsesTheSameElapsedParentBudget` | Test-supplied 2,500 ms enclosing context honored by the APK adapter; not a built-in app-update deadline |
| `backpressuredUploadCannotOutliveTheElapsedParentBudget` | Same enclosing budget while real socket write is stalled |
| `realTlsTimeAndTrickleJsonShareTheProductionEightSecondProbeBudget` | TLS handshake is delayed 1,800 ms after TCP accept, then one byte per 200 ms; actual 8 s probe budget must include both stages |
| `trickleSessionJsonCannotOutliveTheProductionTwelveSecondBudget` | Real trickling session response must fail at the production 12 s budget; no successful return after expiry |
| `readOnlyLostResponseRetriesAtMostTwiceOnRealHttps` | Actual truncated HTTP body, exactly two accepted requests and terminal failure |
| `mutationWithoutExactReceiptNeverReplaysALostResponse` | Same fault through member creation, exactly one request |
| `receiptClassifiedRetryKeepsTheExactRequestIdentityAndBody` | Owner-login classified exact-receipt path, exactly two requests with identical request line, headers and bytes |

All cancellation/deadline cases also require one entered request, peer-side
terminal input read (EOF or peer I/O termination), zero active fixture sockets,
one locally closed accepted socket, and no fixture errors **before teardown**.
The closed counter is published only after `socket.close()` succeeds; a close
failure is recorded and does not remove the socket from the active set. The
oracle waits for the closed counter and empty active set together, rather than
inferring closure from only one asynchronously updated field. They never use a
fake `disconnect()` flag as socket/FD evidence. For upload,
the fixture only resumes draining after the short-return assertion; drain cannot
rescue a blocked client and make that assertion pass. Received upload bytes must
be less than the declared full file. If 8 MiB does not produce observable
backpressure on a device, that case fails as an unsatisfied fixture condition;
it is not silently skipped or presented as a write-cancellation pass.

The tests log `LeziTransportProof` counters before cleanup. The XML testcase,
APK digest/revision, API level/device build, device clock, and these counters must
be retained together when the owner eventually runs them. A short-bound failure
is still a failure if cleanup subsequently releases the thread.

## Manual build and selector (not executed here)

Ordinary instrumentation suites exclude `@ManualAndroidTransport`. Opt-in both
packages the synthetic certificate and enables the runner gate; no `Assume` skip
can masquerade as a pass. Normal app APKs and ordinary test APKs do not include
the generated identity. No production manifest, TLS policy, hostname verifier,
CA store, SELinux mode, or dependency version is changed.

After the build coordinator permits a compile-only slot:

```bash
./gradlew :sync:assembleDebugAndroidTest -PleziAndroidTransport=true
```

Only when the owner chooses to run devices themselves:

```bash
./gradlew :sync:connectedDebugAndroidTest -PleziAndroidTransport=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest
```

For one case, append `#method` from the table to the class value. Use one device
and one suite at a time. Keep SELinux Enforcing and normal TLS validation. The
fixture binds only `127.0.0.1` on a new ephemeral port and asserts that every URL
opened by the backend is that exact HTTPS origin. No DNS override, redirect,
public endpoint, real credentials, installed root CA, or family server is used.

`prepareAndroidTransportTls` reuses the repository's openssl + PKCS12 +
SSLServerSocket fixture approach. It creates a two-day self-signed loopback
identity below ignored `sync/build/generated/androidTransportAssets`, packages
only its test PKCS12, and deletes the generation staging directory. The public
PKCS12 password is a fixture constant, not a user credential. Rebuild after
expiry or device-clock changes; never disable certificate checks. The key and
all generated APKs remain untracked and must not be added to a source artifact.

## Boundaries still open

- **Real DNS and TCP connect cancellation:** the fixture uses a literal loopback
  IP, so it does not exercise either application DNS or repeated platform DNS.
  TLS delay after TCP accept is a TLS-connect phase proof, not delayed SYN or
  slow DNS. Real DNS needs an owner-controlled test zone/resolver plus a safe
  isolated device network, query logs proving both resolutions, fresh cache
  keys, and controlled delay/response. Real TCP-connect cancellation needs a
  separate owner-authorized isolated endpoint/network that demonstrably delays
  connect. Neither is currently available. Do not replace either with an
  injected sleeping resolver, arbitrary Internet host, or production NAS.
- **All-phase US-029:** these sources cover actual TLS+read accumulation and
  real enclosing budgets on pending/trickling read/write. The slow response
  never completes valid JSON, so it does not independently prove rejection of
  a completed late success; that boundary has separate JVM phase-boundary
  coverage in `FamilyHttpDeadlineAcceptanceTest`.
  They do not establish successful slow DNS → second platform resolution →
  connect/write/final-return behavior; that remains unverified.
- **APK elapsed-context scope:** the 2,500 ms parent budget is supplied by the
  test using a real monotonic clock. `RealSyncPort.installAppUpdate` does not
  install this test budget. This case checks only that the HTTP adapter honors
  an enclosing `ElapsedBudgetContext` when supplied; it does not establish a
  product-wide absolute APK-download deadline or change the production budget.
- **APK sink ownership:** the real path is
  `RealSyncPort.installAppUpdate` → `stagingFile.outputStream().use` →
  `RefreshingSyncBackend.downloadAppUpdateApk` →
  `HttpSyncBackend.streamBoundedBodyTo`. The API documents that partial bytes
  remain for caller cleanup, and the adapter does not close the borrowed sink.
  These cases cover the production ordinary-file sink during slow network read,
  not an independently blocked arbitrary `OutputStream.write`. A full pipe is
  not a reachable app-update target and is not a confirmed product bug. If the
  spec intends arbitrary blocked sinks, ownership/cancellation must be settled
  before changing the adapter; closing caller-owned streams is not assumed safe.
- **Resources:** observed peer transport termination + fixture socket closure +
  client call completion is actual socket evidence, but it is not a native
  client-FD census or a proof of every platform keep-alive pool entry. There is
  no `/proc` observer, cross-process resource inspection, or object-count claim.
- **US-071 beyond transport:** replay tests establish client request count and
  byte identity, not a durable server receipt, commit semantics, auth-refresh
  ordering, gzip expansion limits, keep-alive lease/TTL or concurrent heartbeat
  ownership. Those retain their existing separate proof obligations.
- **Possible failures remain meaningful:** platform cancellation behavior and
  deadline-to-typed-failure mapping are deliberately asserted, not weakened to
  whatever exception occurs. A failing device case requires diagnosis before
  acceptance; source preparation is not an assertion that Android will pass.

## Checks actually performed

- `git diff --check`: passed.
- `bash -n tools/testing/prepare-android-transport-tls.sh`: passed.
- Ran only the isolated certificate preparation script and inspected its public
  certificate: SAN `localhost` / `127.0.0.1`, two-day validity, readable PKCS12.
- No Kotlin compile, Gradle/Cargo build, Android instrumentation, ADB, NAS or
  deployment commands were run. No new test result XML exists for these cases.
