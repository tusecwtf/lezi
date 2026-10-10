# US-047 opt-in counting-store Activity fixture

Prepared source only, 2026-10-10 UTC. No compilation or device execution has
been performed for this checkpoint. Keep the story partial until the separate
platform gates actually run.

## Scope and isolation

`CountingWidgetConfigurationDeviceTest` uses the real
`WidgetConfigurationActivity`, retained `WidgetConfigurationViewModel`,
`CareWidgetRefreshController`, Room database and SharedPreferences codec.
The sole replaced Hilt binding is `WidgetStateStore`: a counting delegate
forwards to the actual `SharedPreferencesWidgetStateStore`. A one-shot error
before the first delegate write makes the retry branch deterministic.

There is no production observer, changed configure control flow, altered
business rule, reflection, or widened module visibility. The Java test module
names the existing Kotlin-internal module's public JVM class solely for Hilt
replacement. The Hilt testing dependency and compiler reuse the repository's
Hilt version.

The `leziUiHostAcceptance=widget` test APK deliberately uses `HiltTestApplication`, NOT LeziApp.
It is not production Application startup, Enforcing-network-policy or broad
application integration evidence. Do not run `ProductionAppFixture` cases
with this runner. The runner requires a fresh disposable debug installation;
it refuses existing persistent files and never resets them.

Default builds do not add the optional source directories, Hilt testing
library, androidTest compiler or replacement module. They keep the ordinary
runner unchanged. The startup-acceptance property and this property are
mutually exclusive. The same optional selector accepts exactly `widget` or
`calendar`, including only that group's replacement module; the two test
assemblies cannot be silently combined.

## Prepared selector and assertions

Class:
`com.lezi.babylog.validation.widget.CountingWidgetConfigurationDeviceTest`

Method:
`failedSaveThenOneRetrySurvivesRealActivityRecreationWithoutDuplicateWrites`

- Allocate and later remove only this test's AppWidgetHost ID.
- Submit a first save whose counting store deliberately rejects the write.
  Require one attempt, no configuration/snapshot, visible retry and no finish.
- Recreate the real Activity: the failure remains visible and does not itself
  produce another store call.
- Explicitly retry behind a real Room transaction lease. Exactly one more
  `saveConfiguration` attempt is observed. Repeated rendered save clicks and
  Activity recreation may not create a third attempt.
- Stop the Activity, release the read, observe persisted snapshot while stopped,
  then resume and require `RESULT_OK` with the exact widget ID and configuration.
  The complete two-element attempt history must still equal the two explicit
  submitted requests.

The oracle counts actual store calls reached from configure, not
SharedPreferences change notifications or distinct keys. It catches duplicate
same-value writes. It does not count a configure invocation that exits before
reaching storage; the controlled two attempts here have a ready local-data gate
and unchanged mutation epoch. This remains narrower than every US-047 failure,
back/cancel, loading and process-death branch.

## Separate future compile/device gate

Compile source only, in the parent's controlled build slot:

```sh
./gradlew -PleziUiHostAcceptance=widget :app:compileDebugAndroidTestKotlin
```

This is a build selector, not permission to run it now or install/run a device.
Normal application host tests need their separately built default-runner APK.
Do not reuse the special test APK as if it were the normal one.

The user's future explicit device run must select the exact `class#method`
above, use runner
`com.lezi.babylog.validation.host.IsolatedUiHostTestRunner`, and supply
instrumentation argument `leziUiHostAcceptance=widget`. The runner
rejects whole-class, multiple-method and every other selector. Each method must
run in its own fresh isolated instrumentation process and disposable installation.
Hilt creates a new component per test, but the production DataStore/Room/sync
instances do not expose per-test shutdown; do not run multiple methods in one
process or reuse its persistent data. Keep API26/API35 evidence, exact final source SHA
and artifact identity distinct. No ADB command or platform pass is claimed here.
