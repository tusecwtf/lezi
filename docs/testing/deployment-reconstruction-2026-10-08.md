# Deployment and validation reconstruction, 2026-10-08

Scope: US-037, US-058, US-059, US-060, US-061 and US-062; remaining-gate rows V16/V23. This is local synthetic fixture evidence, not a NAS deployment, image build, signed APK, actual SSH connection, or Rust/Android dual-side run.

## Reconstructed behavior

- Update publication has one runtime-uid writer, a private durable transaction marker and prior-pair snapshots. Both files are verified before publication commits or the old container can stop. Failures before commit restore the prior bytes; failed rollback and uncatchable termination preserve evidence and make the next invocation refuse.
- Publication commit retains its snapshots until the replacement's existing image, health/version and TLS checks succeed. Cleanup no longer discards the only rollback snapshot before a helper's successful response is known. Post-commit deployment failure does not imply an old schema/protocol downgrade is safe.
- The TLS forwarding fixture uses current released 0.5.4 package identity, exact inventory/checksums, real isolated owner leases and promotion, with fake Docker/SSH/SCP and a backup result adapter. It covers explicit fresh authorization with a synthetic secret on stdin, ordinary CD without secret/TLS-generation forwarding, failed backup before replacement, invalid authorization and a competing owner lease. Encryption and remote TLS generation are not proved by that forwarding fixture; existing separate TLS guards also run.
- Packaging smoke resolves channel metadata against the release catalog independently of current source versions. A separate frozen-wire fixture models Android 0.5.4 with server/channel 0.5.3, floor21 and local contract6. It does not claim candidate Android 0.5.5/wire0.5 is compatible with a wire0.4 server, and does not rewrite release metadata.
- Isolated integration launcher adapters prove a newer executable external-cache binary is ignored, this checkout's output path/digest/revision is propagated to both test modules, and build failure, test failure, missing/non-executable output and missing sqlite3 fail rather than skip.
- Schema rehearsal validates the isolated source boundary before requiring Docker. Its negative-path test can therefore prove rejection without Docker installed; its real rehearsal still requires Docker and explicit isolated inputs.

## Evidence

The final local sweep passed 16/16 drivers: 13 deployment fixtures, two Docker-build helper fixtures and one integration-launcher fixture. The production publication fragment passed all 21 injected cases. Shell syntax and `git diff --check` also passed.

The adjacent `evidence/2026-10-08-deployment-reconstruction/` directory contains the complete sweep result, one raw log per test driver, and a SHA-256 inventory of the tested shell sources. The comparison baseline is recovered commit `c705c797da3e27ea783bad3f43094ba64bca5a14` (which follows the exact partial recovery `e9f175d`). Historical passes of the lost full candidate are not reused as current proof.

Environment: Linux x86-64, Bash 5.2.37, Python 3.12.14, OpenSSL 3.5.7, SQLite 3.53.4. All test data are synthetic and temporary. The SIGKILL fixture kills only its own synthetic publication child; it performs no cross-process `/proc` observation.

Commands:

```sh
for test in tools/lezi-sync/deploy/test-*.sh tools/lezi-sync/docker/test-*.sh tools/testing/test-isolated-integration.sh; do
  bash "$test"
done
find tools/lezi-sync/deploy tools/lezi-sync/docker tools/testing -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
git diff --check
```

The app-update driver exercises the actual production publication fragment with fake Docker and curl, rather than reimplementing its algorithm. Its stop marker proves control flow cannot reach container replacement on a failed publication; no real container stop occurs.

## Follow-on maintenance proof

The separate maintenance-publication gap identified here was subsequently reproduced and repaired. See [the follow-on report](schema-publication-reconstruction-2026-10-08.md); the 16-driver evidence below remains the first candidate's evidence, not a claim that it tested the later helper.

## Still open at this checkpoint

- The legacy `schema-cutover-steps.sh` 11/12-to-13 maintenance flow has a separate root-helper prepublication/rollback implementation. Its orchestration adapters are covered by this sweep, but they do not establish filesystem fault recovery inside that separate remote path. The 21-case result applies to ordinary `remote-deploy.sh`, not every maintenance path or all of US-037/V16.

- An actual Docker image and the uid-10001 helper environment, power-loss/fsync behavior on a NAS filesystem, production certificate preservation, signing, release and deployment remain unexecuted and separately authorized.
- The new 0.5.5 source candidate has no approved full-package rollback stanza. Unknown-version packaging remains fail-closed unless the operator supplies an explicit schema and rollback identity. This reconstruction does not invent 0.5.5-to-0.5.4 compatibility for new domain values or journal protocol2.
- `run-production-export-parent-death.py`, `test-production-export-parent-death.py`, `run-production-installer-process-death.py` and `test-production-installer-process-death.py` referenced by earlier device documentation are absent from the partial recovered tree. Their historical observer controls are not current proof. The previously blocked cross-process `/proc` observer route was not recreated or run. Real process, installer, Application and platform acceptance remain open.
- Launcher fixtures establish wiring only. The integrated final source still needs the actual Rust gates, both Android golden consumers, pinned Rust/Android integration and applicable device/R8/CI gates.
